package tpDiscover.intelbras.coleta;

import tpDiscover.intelbras.connection.ConnectionConfig;
import tpDiscover.intelbras.connection.SwitchClient;
import tpDiscover.intelbras.connection.SwitchConnectionException;
import tpDiscover.intelbras.connection.SwitchConnector;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.ComwareParser;
import tpDiscover.intelbras.parser.OltParser;
import tpDiscover.intelbras.parser.Serie3000Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coleta uma lista de equipamentos de rede Intelbras, suportando:
 *  - Switches Comware (S23/S33)
 *  - Switches Série 3000 (S3028G-PB, S3054G-B, etc.)
 *  - OLTs EPON/GPON (OLT 4840E, OLT 4880E, G16, G08, etc.)
 */
public class ColetorEmLote {

    // Detecta mensagens reais de erro de sintaxe/comando inválido na CLI (sem confundir com "0.00%" ou "Unknown protocol")
    private static final Pattern ERRO_CLI_PATTERN = Pattern.compile(
            "(?im)(^\\s*%+\\s*(?:invalid|unknown|unrecognized|incomplete|ambiguous|wrong|too\\s+many|error|bad|command|parameter)\\b"
            + "|\\b(?:unrecognized\\s+command|unknown\\s+command|invalid\\s+input|invalid\\s+command"
            + "|invalid\\s+parameter|incomplete\\s+command|command\\s+incomplete|ambiguous\\s+command"
            + "|bad\\s+command|syntax\\s+error|command\\s+not\\s+found|no\\s+matched\\s+command"
            + "|error\\s+input|parameter\\s+error|too\\s+many\\s+parameters)\\b)"
    );
    private static final Pattern INTERFACE_VLAN_UP = Pattern.compile("(?im)^\\s*(vlan\\d+)\\s+\\S+\\s+up\\b");
    private static final List<String> COMANDOS_MAC_S30 = List.of(
            "show mac-address all",
            "show mac-address-table",
            "show mac address-table",
            "show mac-address",
            "show mac-address dynamic"
    );

    private final ComwareParser comwareParser = new ComwareParser();
    private final Serie3000Parser serie3000Parser = new Serie3000Parser();
    private final OltParser oltParser = new OltParser();

    public record FalhaColeta(SwitchTarget alvo, SwitchConnectionException.Tipo tipo, String mensagem) {
    }

    public record ResultadoColeta(List<SwitchIdentity> sucesso, List<FalhaColeta> falhas) {
        public int total() {
            return sucesso.size() + falhas.size();
        }
    }

    public ResultadoColeta coletarTudo(List<SwitchTarget> alvos, TopologyEngine engine) {
        List<SwitchIdentity> sucesso = new ArrayList<>();
        List<FalhaColeta> falhas = new ArrayList<>();

        for (SwitchTarget alvo : alvos) {
            System.out.println("\n--- Coletando " + alvo.ip() + " ---");
            try {
                SwitchIdentity identidade = coletarUm(alvo, engine);
                System.out.println("  OK: " + identidade);
                sucesso.add(identidade);
            } catch (SwitchConnectionException e) {
                System.out.println("  FALHA (" + e.getTipo() + "): " + e.getMessage());
                falhas.add(new FalhaColeta(alvo, e.getTipo(), e.getMessage()));
            }
        }

        if (!sucesso.isEmpty()) {
            aplicarFallbackGatewayLocal(sucesso, engine);
        }

        return new ResultadoColeta(sucesso, falhas);
    }

    private void aplicarFallbackGatewayLocal(List<SwitchIdentity> coletados, TopologyEngine engine) {
        // Caso algum IP de gateway (manual ou rota padrão de switch) não tenha entrada ARP nos switches,
        // tenta resolver o MAC na tabela ARP do próprio PC local.
        engine.ipGatewaySemMacResolvido().ifPresent(ipGw ->
                LocalGatewayDetector.resolverMacNoArpLocal(ipGw)
                        .ifPresent(mac -> engine.registrarMacExternoParaIp(ipGw, mac))
        );

        // Se nenhum switch possuía rota padrão configurada (modo L2 puro), consulta o gateway padrão do PC
        // desde que pertença à mesma sub-rede IPv4 de algum dos switches coletados.
        if (engine.gatewayIdentificado().isEmpty()) {
            List<String> ipsColetados = coletados.stream()
                    .map(SwitchIdentity::ip)
                    .filter(ip -> ip != null && !ip.isBlank())
                    .toList();
            LocalGatewayDetector.detectarGatewayLocalNaMesmaSubRede(ipsColetados)
                    .ifPresent(engine::definirGatewayFallbackLocal);
        }
    }

    private SwitchIdentity coletarUm(SwitchTarget alvo, TopologyEngine engine) throws SwitchConnectionException {
        ConnectionConfig config = ConnectionConfig.padrao(alvo.ip(), alvo.usuario(), alvo.senha());
        SwitchClient cliente = null;

        try {
            cliente = SwitchConnector.conectar(config);

            // 1) Tenta detectar se é Comware (S23/S33)
            String respComware = cliente.executarComando("screen-length disable");
            boolean ehComware = !ehComandoInvalido(respComware);

            if (ehComware) {
                String manuinfo = cliente.executarComando("display device manuinfo");
                if (!ehComandoInvalido(manuinfo)) {
                    String macTableBruta = cliente.executarComando("display mac-address");
                    SwitchIdentity identidade = comwareParser.parseDeviceManuInfo(alvo.ip(), manuinfo);
                    engine.registrarSwitch(identidade);

                    Map<String, List<String>> membrosPorBagg = Map.of();
                    List<MacEntry> entradas = comwareParser.parseMacAddressTable(identidade.macNormalizado(), macTableBruta);
                    if (possuiPortaAgregada(entradas)) {
                        String lagBruto = cliente.executarComando("display link-aggregation verbose");
                        if (!ehComandoInvalido(lagBruto)) {
                            membrosPorBagg = comwareParser.parseLinkAggregation(lagBruto);
                            entradas = comwareParser.expandirPortasAgregadas(entradas, membrosPorBagg);
                        }
                    }
                    engine.registrarEntradas(identidade.macNormalizado(), entradas);

                    // Coleta somente-leitura de rota padrão e ARP para identificar o roteador/gateway da rede
                    String rotasBrutas = cliente.executarComando("display ip routing-table");
                    if (!ehComandoInvalido(rotasBrutas)) {
                        comwareParser.parseDefaultGatewayIp(rotasBrutas)
                                .ifPresent(gwIp -> engine.registrarRotaPadrao(identidade.macNormalizado(), gwIp));
                    }
                    String arpBruto = cliente.executarComando("display arp");
                    if (!ehComandoInvalido(arpBruto)) {
                        List<ArpEntry> entradasArp = comwareParser.parseArpTable(arpBruto);
                        if (!membrosPorBagg.isEmpty()) {
                            entradasArp = comwareParser.expandirPortasArp(entradasArp, membrosPorBagg);
                        }
                        engine.registrarEntradasArp(identidade.macNormalizado(), entradasArp);
                    }

                    System.out.println("  [Comware] " + entradas.size() + " entradas de tabela MAC coletadas");
                    return identidade;
                }
            }

            // 2) Consulta show version e show system para diferenciar OLT (4840E/G16/G08) de Switch Série 3000
            String version = cliente.executarComando("show version");
            String systemInfo = cliente.executarComando("show system");
            String infoCombinada = version + "\n" + systemInfo;

            if (oltParser.ehDispositivoOlt(infoCombinada)) {
                String macTableBruta = cliente.executarComando("show mac-address-table");
                SwitchIdentity identidade = oltParser.parseDeviceInfo(alvo.ip(), "OLT", infoCombinada);
                engine.registrarSwitch(identidade);

                List<MacEntry> entradas = oltParser.parseMacAddressTable(identidade.macNormalizado(), macTableBruta);
                engine.registrarEntradas(identidade.macNormalizado(), entradas);

                String rotasBrutas = cliente.executarComando("show ip route");
                if (!ehComandoInvalido(rotasBrutas)) {
                    oltParser.parseDefaultGatewayIp(rotasBrutas)
                            .ifPresent(gwIp -> engine.registrarRotaPadrao(identidade.macNormalizado(), gwIp));
                }
                String arpBruto = cliente.executarComando("show arp");
                if (!ehComandoInvalido(arpBruto)) {
                    engine.registrarEntradasArp(identidade.macNormalizado(), oltParser.parseArpTable(arpBruto));
                }

                System.out.println("  [OLT] " + entradas.size() + " entradas de tabela MAC coletadas");
                return identidade;
            }

            // 3) Fluxo Switch Série 3000 (S30) - desabilita paginação da sessão quando suportado (---MORE--- também tratado no cliente)
            cliente.executarComando("terminal length 0");
            StringBuilder identidadeBruta = new StringBuilder(infoCombinada);

            // 3a) MAC do sistema: 'show system mac' (independe de VLAN) com fallbacks para outros firmwares S30
            String sysMacInfo = cliente.executarComando("show system mac");
            if (ehComandoInvalido(sysMacInfo)) {
                sysMacInfo = cliente.executarComando("show mac-address system-mac");
            }
            if (!ehComandoInvalido(sysMacInfo)) {
                identidadeBruta.append("\n").append(sysMacInfo);
            }

            // Se ainda não encontrou MAC válido, consulta STP Bridge MAC e interface VLAN
            if (!serie3000Parser.contemMacExtraivel(identidadeBruta.toString())) {
                String stpInfo = cliente.executarComando("show spanning-tree");
                if (!ehComandoInvalido(stpInfo)) {
                    identidadeBruta.append("\n").append(stpInfo);
                }
            }
            if (!serie3000Parser.contemMacExtraivel(identidadeBruta.toString())) {
                String mgmtInfo = cliente.executarComando("show interface vlan 1");
                if (ehComandoInvalido(mgmtInfo) || !serie3000Parser.contemMacExtraivel(mgmtInfo)) {
                    String ipBrief = cliente.executarComando("show ip interface brief");
                    if (!ehComandoInvalido(ipBrief)) {
                        Matcher mVlan = INTERFACE_VLAN_UP.matcher(ipBrief);
                        if (mVlan.find()) {
                            String numVlan = mVlan.group(1).substring(4);
                            mgmtInfo = cliente.executarComando("show interface vlan " + numVlan);
                        }
                    }
                }
                if (ehComandoInvalido(mgmtInfo) || !serie3000Parser.contemMacExtraivel(mgmtInfo)) {
                    mgmtInfo = cliente.executarComando("show interface gi1/0/1");
                }
                if (ehComandoInvalido(mgmtInfo) || !serie3000Parser.contemMacExtraivel(mgmtInfo)) {
                    mgmtInfo = cliente.executarComando("show interface gi0/1");
                }
                if (!ehComandoInvalido(mgmtInfo)) {
                    identidadeBruta.append("\n").append(mgmtInfo);
                }
            }

            // 3b) Serial Number e detalhes do cartão principal ('show system mpu', quando suportado pelo modelo)
            String mpuInfo = cliente.executarComando("show system mpu");
            if (!ehComandoInvalido(mpuInfo)) {
                identidadeBruta.append("\n").append(mpuInfo);
            }

            SwitchIdentity identidade = serie3000Parser.parseDeviceVersion(
                    alvo.ip(), null, identidadeBruta.toString());
            engine.registrarSwitch(identidade);

            // 3c) Tabela MAC completa (tenta variações de comando 'show' entre diferentes gerações de firmware S30)
            List<MacEntry> entradas = List.of();
            for (String cmdMac : COMANDOS_MAC_S30) {
                String macTableBruta = cliente.executarComando(cmdMac);
                if (!ehComandoInvalido(macTableBruta)) {
                    entradas = serie3000Parser.parseMacAddressTable(identidade.macNormalizado(), macTableBruta);
                    if (!entradas.isEmpty()) {
                        break;
                    }
                }
            }

            Map<String, List<String>> membrosPorBagg = Map.of();
            if (possuiPortaAgregada(entradas)) {
                String lagBruto = cliente.executarComando("show link-aggregation");
                if (ehComandoInvalido(lagBruto)) {
                    lagBruto = cliente.executarComando("show etherchannel summary");
                }
                if (!ehComandoInvalido(lagBruto)) {
                    membrosPorBagg = serie3000Parser.parseLinkAggregation(lagBruto);
                    entradas = serie3000Parser.expandirPortasAgregadas(entradas, membrosPorBagg);
                }
            }
            engine.registrarEntradas(identidade.macNormalizado(), entradas);

            // 3d) Rota padrão e tabela ARP (apenas leitura 'show') para identificar o roteador/gateway
            String rotasBrutas = cliente.executarComando("show ip route");
            if (!ehComandoInvalido(rotasBrutas)) {
                serie3000Parser.parseDefaultGatewayIp(rotasBrutas)
                        .ifPresent(gwIp -> engine.registrarRotaPadrao(identidade.macNormalizado(), gwIp));
            }
            String arpBruto = cliente.executarComando("show arp");
            if (!ehComandoInvalido(arpBruto)) {
                List<ArpEntry> entradasArp = serie3000Parser.parseArpTable(arpBruto);
                if (!membrosPorBagg.isEmpty()) {
                    entradasArp = serie3000Parser.expandirPortasArp(entradasArp, membrosPorBagg);
                }
                engine.registrarEntradasArp(identidade.macNormalizado(), entradasArp);
            }

            System.out.println("  [Série 3000] " + entradas.size() + " entradas de tabela MAC coletadas");
            return identidade;

        } finally {
            if (cliente != null) {
                cliente.desconectar();
            }
        }
    }

    private static boolean possuiPortaAgregada(List<MacEntry> entradas) {
        for (MacEntry e : entradas) {
            String p = e.port().toUpperCase();
            if (p.startsWith("BAGG") || p.startsWith("AGG")) {
                return true;
            }
        }
        return false;
    }

    public static boolean ehComandoInvalido(String resposta) {
        if (resposta == null || resposta.isBlank()) return true;
        return ERRO_CLI_PATTERN.matcher(resposta).find();
    }
}

package tpDiscover.intelbras.coleta;

import tpDiscover.intelbras.connection.ConnectionConfig;
import tpDiscover.intelbras.connection.SwitchClient;
import tpDiscover.intelbras.connection.SwitchConnectionException;
import tpDiscover.intelbras.connection.SwitchConnector;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.ComwareParser;
import tpDiscover.intelbras.parser.OltParser;
import tpDiscover.intelbras.parser.Serie3000Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Coleta uma lista de equipamentos de rede Intelbras, suportando:
 *  - Switches Comware (S23/S33)
 *  - Switches Série 3000 (S3028G-PB, S3054G-B, etc.)
 *  - OLTs EPON/GPON (OLT 4840E, OLT 4880E, G16, G08, etc.)
 */
public class ColetorEmLote {

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

        return new ResultadoColeta(sucesso, falhas);
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

                    List<MacEntry> entradas = comwareParser.parseMacAddressTable(identidade.macNormalizado(), macTableBruta);
                    if (possuiPortaAgregada(entradas)) {
                        String lagBruto = cliente.executarComando("display link-aggregation verbose");
                        if (!ehComandoInvalido(lagBruto)) {
                            Map<String, List<String>> membrosPorBagg = comwareParser.parseLinkAggregation(lagBruto);
                            entradas = comwareParser.expandirPortasAgregadas(entradas, membrosPorBagg);
                        }
                    }
                    engine.registrarEntradas(identidade.macNormalizado(), entradas);

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

                System.out.println("  [OLT] " + entradas.size() + " entradas de tabela MAC coletadas");
                return identidade;
            }

            // 3) Fluxo Switch Série 3000 (S30)
            cliente.executarComando("terminal length 0");
            String mgmtInfo = cliente.executarComando("show interface vlan 1");
            if (ehComandoInvalido(mgmtInfo)) {
                mgmtInfo = cliente.executarComando("show interface management1/0/0");
            }
            if (ehComandoInvalido(mgmtInfo)) {
                mgmtInfo = cliente.executarComando("show interface gi1/0/1");
            }
            String macTableBruta = cliente.executarComando("show mac-address all");

            if (ehComandoInvalido(macTableBruta)) {
                macTableBruta = cliente.executarComando("show mac-address-table");
            }

            SwitchIdentity identidade = serie3000Parser.parseDeviceVersion(
                    alvo.ip(), "INTELBRAS", infoCombinada + "\n" + mgmtInfo);
            engine.registrarSwitch(identidade);

            List<MacEntry> entradas = serie3000Parser.parseMacAddressTable(identidade.macNormalizado(), macTableBruta);
            if (possuiPortaAgregada(entradas)) {
                String lagBruto = cliente.executarComando("show link-aggregation");
                if (ehComandoInvalido(lagBruto)) {
                    lagBruto = cliente.executarComando("show etherchannel summary");
                }
                if (!ehComandoInvalido(lagBruto)) {
                    Map<String, List<String>> membrosPorBagg = serie3000Parser.parseLinkAggregation(lagBruto);
                    entradas = serie3000Parser.expandirPortasAgregadas(entradas, membrosPorBagg);
                }
            }
            engine.registrarEntradas(identidade.macNormalizado(), entradas);

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

    private static boolean ehComandoInvalido(String resposta) {
        if (resposta == null) return true;
        String r = resposta.toLowerCase();
        return r.contains("unrecognized")
                || r.contains("invalid")
                || r.contains("incomplete")
                || r.contains("bad command")
                || r.contains("%");
    }
}

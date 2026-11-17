package tpDiscover.intelbras.coleta;

import tpDiscover.intelbras.connection.ConnectionConfig;
import tpDiscover.intelbras.connection.CredencialLogin;
import tpDiscover.intelbras.connection.SwitchClient;
import tpDiscover.intelbras.connection.SwitchConnectionException;
import tpDiscover.intelbras.connection.SwitchConnector;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.descoberta.DiscoveredProduct;
import tpDiscover.intelbras.descoberta.NetworkScanTarget;
import tpDiscover.intelbras.descoberta.ProductFingerprinter;
import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.LldpNeighbor.CategoriaInteresse;
import tpDiscover.intelbras.modelos.LoopDiagnostic;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.OuiLookup;
import tpDiscover.intelbras.modelos.StpPortState;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.ApParser;
import tpDiscover.intelbras.parser.ComwareParser;
import tpDiscover.intelbras.parser.LldpInterestFilter;
import tpDiscover.intelbras.parser.OltParser;
import tpDiscover.intelbras.parser.Serie3000Parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coleta uma lista de equipamentos de rede Intelbras em paralelo (Virtual Threads),
 * com suporte somente-leitura para MAC, ARP, rotas, LLDP, STP e loopback-detection em:
 *  - Switches Comware (S23/S33)
 *  - Switches Série 3000 / Max / S2050G-A / Lite / SG MR L2+
 *  - OLTs EPON/GPON (4840E, 8820G, G16, G08, etc.)
 *  - Access Points Intelbras / OpenWrt (AP 3000 AX, AP 1800 AX, AP 1350 AC-S, AP 360, etc.)
 */
public class ColetorEmLote {

    public static final int CONCORRENCIA_PADRAO = 16;

    // Detecta mensagens reais de erro de sintaxe/comando inválido na CLI (sem confundir com "0.00%" ou "Unknown protocol")
    private static final Pattern ERRO_CLI_PATTERN = Pattern.compile(
            "(?im)(^\\s*%+\\s*(?:invalid|unknown|unrecognized|incomplete|ambiguous|wrong|too\\s+many|error|bad|command|parameter)\\b"
            + "|\\b(?:unrecognized\\s+command|unknown\\s+command|invalid\\s+input|invalid\\s+command"
            + "|invalid\\s+parameter|incomplete\\s+command|command\\s+incomplete|ambiguous\\s+command"
            + "|bad\\s+command|syntax\\s+error|illegal\\s+command\\s+line|command\\s+not\\s+found|not\\s+found"
            + "|no\\s+matched\\s+command|error\\s+input|parameter\\s+error|too\\s+many\\s+parameters)\\b)"
    );
    private static final Pattern INTERFACE_VLAN_UP = Pattern.compile("(?im)^\\s*(vlan\\d+)\\s+\\S+\\s+up\\b");
    private static final List<String> COMANDOS_MAC_S30 = List.of(
            "show mac-address all",
            "show mac-address-table",
            "show mac address-table",
            "show mac-address",
            "show mac-address dynamic",
            "show bridge address all"
    );
    private static final List<String> COMANDOS_LAG_S30 = List.of(
            "show link-aggregation group",
            "show link-aggregation",
            "show lacp port etherchannel",
            "show aggregator-group summary",
            "show interface link-aggregation",
            "show etherchannel summary"
    );

    private final ComwareParser comwareParser = new ComwareParser();
    private final Serie3000Parser serie3000Parser = new Serie3000Parser();
    private final OltParser oltParser = new OltParser();
    private final ApParser apParser = new ApParser();

    public record FalhaColeta(SwitchTarget alvo, SwitchConnectionException.Tipo tipo, String mensagem) {
    }

    public record ColetaDispositivoSnapshot(
            SwitchTarget alvo,
            SwitchIdentity identidade,
            String familiaLog,
            List<MacEntry> entradasMac,
            List<ArpEntry> entradasArp,
            String rotaPadrao,
            List<LldpNeighbor> lldpNeighbors,
            List<StpPortState> stpStates,
            List<LoopDiagnostic> loopbackHardware,
            Map<String, List<String>> baggs,
            String credencialAlternativaUsada
    ) {
        public ColetaDispositivoSnapshot {
            entradasMac = entradasMac != null ? List.copyOf(entradasMac) : List.of();
            entradasArp = entradasArp != null ? List.copyOf(entradasArp) : List.of();
            lldpNeighbors = lldpNeighbors != null ? List.copyOf(lldpNeighbors) : List.of();
            stpStates = stpStates != null ? List.copyOf(stpStates) : List.of();
            loopbackHardware = loopbackHardware != null ? List.copyOf(loopbackHardware) : List.of();
            baggs = baggs != null ? Map.copyOf(baggs) : Map.of();
        }
    }

    public record ResultadoColeta(
            List<SwitchIdentity> sucesso,
            List<FalhaColeta> falhas,
            List<ColetaDispositivoSnapshot> snapshotsSucesso
    ) {
        public ResultadoColeta(List<SwitchIdentity> sucesso, List<FalhaColeta> falhas) {
            this(sucesso, falhas, List.of());
        }

        public int total() {
            return sucesso.size() + falhas.size();
        }
    }

    public interface ColetaProgressListener {
        default void onAlvoIniciado(SwitchTarget alvo) {}

        default void onAlvoConcluido(SwitchTarget alvo, boolean sucesso, String resumoOuErro, int concluidos, int total) {}

        default void onExpansaoLldpIniciada(List<SwitchTarget> novosAlvos) {}
    }

    private record ConexaoBemSucedida(
            SwitchClient cliente,
            SwitchTarget alvoEfetivo,
            String credencialAlternativaUsada
    ) {}

    private record ColetaDispositivoResultado(
            SwitchTarget alvo,
            SwitchIdentity identidade,
            String familiaLog,
            List<MacEntry> entradasMac,
            List<ArpEntry> entradasArp,
            String rotaPadrao,
            List<LldpNeighbor> lldpNeighbors,
            List<StpPortState> stpStates,
            List<LoopDiagnostic> loopbackHardware,
            Map<String, List<String>> baggs,
            SwitchConnectionException erro,
            String credencialAlternativaUsada
    ) {
        ColetaDispositivoResultado(
                SwitchTarget alvo,
                SwitchIdentity identidade,
                String familiaLog,
                List<MacEntry> entradasMac,
                List<ArpEntry> entradasArp,
                String rotaPadrao,
                List<LldpNeighbor> lldpNeighbors,
                List<StpPortState> stpStates,
                List<LoopDiagnostic> loopbackHardware,
                Map<String, List<String>> baggs,
                SwitchConnectionException erro
        ) {
            this(alvo, identidade, familiaLog, entradasMac, entradasArp, rotaPadrao,
                    lldpNeighbors, stpStates, loopbackHardware, baggs, erro, null);
        }

        boolean obteveSucesso() {
            return erro == null && identidade != null;
        }

        ColetaDispositivoResultado comCredencialAlternativa(String cred) {
            return new ColetaDispositivoResultado(
                    alvo, identidade, familiaLog, entradasMac, entradasArp, rotaPadrao,
                    lldpNeighbors, stpStates, loopbackHardware, baggs, erro, cred);
        }

        ColetaDispositivoSnapshot toSnapshot() {
            return new ColetaDispositivoSnapshot(
                    alvo, identidade, familiaLog, entradasMac, entradasArp, rotaPadrao,
                    lldpNeighbors, stpStates, loopbackHardware, baggs, credencialAlternativaUsada);
        }
    }

    public ResultadoColeta coletarTudo(List<SwitchTarget> alvos, TopologyEngine engine) {
        return coletarTudo(alvos, engine, CONCORRENCIA_PADRAO, false, null, null, null, null);
    }

    public ResultadoColeta coletarTudo(
            List<SwitchTarget> alvos,
            TopologyEngine engine,
            int maxConcorrencia,
            boolean expandirViaLldp,
            String usuarioPadraoLldp,
            String senhaPadraoLldp
    ) {
        return coletarTudo(alvos, engine, maxConcorrencia, expandirViaLldp, usuarioPadraoLldp, senhaPadraoLldp, null, null);
    }

    public ResultadoColeta coletarTudo(
            List<SwitchTarget> alvos,
            TopologyEngine engine,
            int maxConcorrencia,
            boolean expandirViaLldp,
            String usuarioPadraoLldp,
            String senhaPadraoLldp,
            ColetaProgressListener listener,
            AtomicBoolean cancelar
    ) {
        return coletarTudo(
                alvos, engine, maxConcorrencia, expandirViaLldp, usuarioPadraoLldp, senhaPadraoLldp,
                Map.of(), CredencialAcesso.presetsPadrao(), listener, cancelar);
    }

    public ResultadoColeta coletarTudo(
            List<SwitchTarget> alvos,
            TopologyEngine engine,
            int maxConcorrencia,
            boolean expandirViaLldp,
            String usuarioPadraoLldp,
            String senhaPadraoLldp,
            Map<String, DiscoveredProduct.FamiliaProduto> familiasPorIp,
            List<CredencialAcesso> credenciaisAlternativas,
            ColetaProgressListener listener,
            AtomicBoolean cancelar
    ) {
        return coletarTudo(
                alvos, engine, maxConcorrencia, expandirViaLldp, usuarioPadraoLldp, senhaPadraoLldp,
                familiasPorIp, Map.of(), credenciaisAlternativas, listener, cancelar);
    }

    public ResultadoColeta coletarTudo(
            List<SwitchTarget> alvos,
            TopologyEngine engine,
            int maxConcorrencia,
            boolean expandirViaLldp,
            String usuarioPadraoLldp,
            String senhaPadraoLldp,
            Map<String, DiscoveredProduct.FamiliaProduto> familiasPorIp,
            Map<String, String> protocolosPreferidosPorIp,
            List<CredencialAcesso> credenciaisAlternativas,
            ColetaProgressListener listener,
            AtomicBoolean cancelar
    ) {
        List<SwitchIdentity> sucesso = new ArrayList<>();
        List<FalhaColeta> falhas = new ArrayList<>();
        List<ColetaDispositivoSnapshot> snapshots = new ArrayList<>();
        if (alvos == null || alvos.isEmpty()) {
            return new ResultadoColeta(sucesso, falhas, snapshots);
        }

        Map<String, DiscoveredProduct.FamiliaProduto> familias = familiasPorIp != null ? familiasPorIp : Map.of();
        Map<String, String> protos = protocolosPreferidosPorIp != null ? protocolosPreferidosPorIp : Map.of();
        List<CredencialAcesso> extras = credenciaisAlternativas != null ? credenciaisAlternativas : List.of();
        int concorrencia = Math.max(1, maxConcorrencia);

        List<ColetaDispositivoResultado> resultadosOnda1 = executarOndaParalela(
                alvos, concorrencia, familias, protos, extras, listener, cancelar);
        registrarResultadosNoEngine(resultadosOnda1, engine, sucesso, falhas, snapshots);

        // Onda 2 opcional: expande automaticamente switches/APs esquecidos descobertos via LLDP
        if (expandirViaLldp && !sucesso.isEmpty() && (cancelar == null || !cancelar.get())) {
            List<SwitchTarget> onda2 = descobrirAlvosFaltantesPorLldp(
                    alvos, resultadosOnda1, sucesso, usuarioPadraoLldp, senhaPadraoLldp
            );
            if (!onda2.isEmpty()) {
                System.out.println("\n[Expansão LLDP] " + onda2.size()
                        + " equipamento(s) adicional(is) encontrado(s) via LLDP; coletando em paralelo...");
                if (listener != null) {
                    listener.onExpansaoLldpIniciada(onda2);
                }
                List<ColetaDispositivoResultado> resultadosOnda2 = executarOndaParalela(
                        onda2, concorrencia, familias, protos, extras, listener, cancelar);
                registrarResultadosNoEngine(resultadosOnda2, engine, sucesso, falhas, snapshots);
            }
        }

        if (!sucesso.isEmpty() && (cancelar == null || !cancelar.get())) {
            aplicarFallbackGatewayLocal(sucesso, engine);
        }

        return new ResultadoColeta(sucesso, falhas, snapshots);
    }

    private List<ColetaDispositivoResultado> executarOndaParalela(
            List<SwitchTarget> alvos,
            int maxConcorrencia,
            Map<String, DiscoveredProduct.FamiliaProduto> familiasPorIp,
            Map<String, String> protocolosPreferidosPorIp,
            List<CredencialAcesso> credenciaisAlternativas,
            ColetaProgressListener listener,
            AtomicBoolean cancelar
    ) {
        Semaphore semaforo = new Semaphore(maxConcorrencia);
        AtomicInteger concluidos = new AtomicInteger(0);
        int total = alvos.size();
        List<ColetaDispositivoResultado> resultados = new ArrayList<>(total);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ColetaDispositivoResultado>> futures = new ArrayList<>(total);
            for (SwitchTarget alvo : alvos) {
                futures.add(executor.submit(() -> {
                    if (cancelar != null && cancelar.get()) {
                        int done = concluidos.incrementAndGet();
                        SwitchConnectionException sce = new SwitchConnectionException(
                                SwitchConnectionException.Tipo.TIMEOUT,
                                "Cancelado pelo usuário",
                                null
                        );
                        if (listener != null) {
                            listener.onAlvoConcluido(alvo, false, "Cancelado", done, total);
                        }
                        return new ColetaDispositivoResultado(
                                alvo, null, null, List.of(), List.of(), null, List.of(), List.of(), List.of(), Map.of(), sce
                        );
                    }
                    semaforo.acquire();
                    ColetaDispositivoResultado r = null;
                    try {
                        if (listener != null) {
                            listener.onAlvoIniciado(alvo);
                        }
                        if (cancelar != null && cancelar.get()) {
                            SwitchConnectionException sce = new SwitchConnectionException(
                                    SwitchConnectionException.Tipo.TIMEOUT,
                                    "Cancelado pelo usuário",
                                    null
                            );
                            r = new ColetaDispositivoResultado(
                                    alvo, null, null, List.of(), List.of(), null, List.of(), List.of(), List.of(), Map.of(), sce
                            );
                        } else {
                            r = coletarUmIsolado(alvo, familiasPorIp, protocolosPreferidosPorIp, credenciaisAlternativas);
                        }
                        return r;
                    } finally {
                        semaforo.release();
                        int done = concluidos.incrementAndGet();
                        if (listener != null) {
                            if (r != null && r.obteveSucesso()) {
                                String sufixoCred = (r.credencialAlternativaUsada() != null && !r.credencialAlternativaUsada().isBlank())
                                        ? " • cred: " + r.credencialAlternativaUsada()
                                        : "";
                                String desc = r.familiaLog() + " • " + r.entradasMac().size() + " MAC(s)" + sufixoCred;
                                listener.onAlvoConcluido(r.alvo(), true, desc, done, total);
                            } else {
                                String msgErro = (r != null && r.erro() != null) ? r.erro().getMessage() : "Falha na coleta";
                                listener.onAlvoConcluido(alvo, false, msgErro, done, total);
                            }
                        }
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                SwitchTarget alvo = alvos.get(i);
                try {
                    resultados.add(futures.get(i).get());
                } catch (Exception e) {
                    Throwable causa = e.getCause() != null ? e.getCause() : e;
                    SwitchConnectionException sce = causa instanceof SwitchConnectionException s
                            ? s
                            : new SwitchConnectionException(
                                    SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                                    "Erro inesperado na coleta: " + causa.getMessage(),
                                    causa
                            );
                    resultados.add(new ColetaDispositivoResultado(
                            alvo, null, null, List.of(), List.of(), null, List.of(), List.of(), List.of(), Map.of(), sce
                    ));
                }
            }
        }
        return resultados;
    }

    private void registrarResultadosNoEngine(
            List<ColetaDispositivoResultado> resultados,
            TopologyEngine engine,
            List<SwitchIdentity> sucesso,
            List<FalhaColeta> falhas,
            List<ColetaDispositivoSnapshot> snapshots
    ) {
        for (ColetaDispositivoResultado r : resultados) {
            System.out.println("\n--- Coletado " + r.alvo().enderecoExibicao() + " ---");
            if (!r.obteveSucesso()) {
                SwitchConnectionException e = r.erro();
                System.out.println("  FALHA (" + e.getTipo() + "): " + e.getMessage());
                falhas.add(new FalhaColeta(r.alvo(), e.getTipo(), e.getMessage()));
                continue;
            }

            ColetaDispositivoSnapshot snap = r.toSnapshot();
            registrarSnapshotNoEngine(snap, engine);

            System.out.printf("  [%s] %d MAC(s), %d LLDP, %d STP porta(s), %d loopback hw%s%n",
                    r.familiaLog(),
                    r.entradasMac().size(),
                    r.lldpNeighbors().size(),
                    r.stpStates().size(),
                    r.loopbackHardware().size(),
                    r.credencialAlternativaUsada() != null
                            ? " (credencial alternativa: " + r.credencialAlternativaUsada() + ")"
                            : "");
            System.out.println("  OK: " + r.identidade());
            sucesso.add(r.identidade());
            snapshots.add(snap);
        }
    }

    public static void registrarSnapshotNoEngine(ColetaDispositivoSnapshot snap, TopologyEngine engine) {
        if (snap == null || snap.identidade() == null || engine == null) {
            return;
        }
        SwitchIdentity id = snap.identidade();
        String swId = id.macNormalizado();
        engine.registrarSwitch(id);
        engine.registrarEntradas(swId, snap.entradasMac());
        engine.registrarEntradasArp(swId, snap.entradasArp());
        engine.registrarRotaPadrao(swId, snap.rotaPadrao());
        engine.registrarLldp(swId, snap.lldpNeighbors());
        engine.registrarStp(swId, snap.stpStates());
        engine.registrarLoopbackHardware(swId, snap.loopbackHardware());
        engine.registrarAggregacoes(swId, snap.baggs());
    }

    private List<SwitchTarget> descobrirAlvosFaltantesPorLldp(
            List<SwitchTarget> onda1Alvos,
            List<ColetaDispositivoResultado> resultadosOnda1,
            List<SwitchIdentity> coletados,
            String usuarioPadrao,
            String senhaPadrao
    ) {
        Set<String> ipsJaTentados = new LinkedHashSet<>();
        for (SwitchTarget t : onda1Alvos) {
            ipsJaTentados.add(t.ip());
        }

        String credUser = (usuarioPadrao != null && !usuarioPadrao.isBlank())
                ? usuarioPadrao
                : onda1Alvos.get(0).usuario();
        String credPass = senhaPadrao != null
                ? senhaPadrao
                : onda1Alvos.get(0).senha();
        if (credUser == null || credUser.isBlank()) {
            return List.of();
        }

        Map<String, SwitchTarget> candidatos = new LinkedHashMap<>();
        for (ColetaDispositivoResultado r : resultadosOnda1) {
            for (LldpNeighbor n : r.lldpNeighbors()) {
                String mgmtIp = n.managementIp();
                if (!NetworkScanTarget.ehIpv4Valido(mgmtIp) || ipsJaTentados.contains(mgmtIp)) {
                    continue;
                }
                if (LldpInterestFilter.resolverSwitchColetado(n, coletados).isPresent()) {
                    continue;
                }
                CategoriaInteresse cat = LldpInterestFilter.classificar(n, coletados, Optional.empty());
                if (cat == CategoriaInteresse.ROTEADOR_UPLINK) {
                    continue;
                }
                boolean pareceInfraGerenciavel = n.possuiCapability("Bridge")
                        || n.possuiCapability("WLAN")
                        || OuiLookup.ehIntelbras(n.chassisMacNormalizado())
                        || (cat == CategoriaInteresse.ACCESS_POINT)
                        || pareceswitchIntelbras(n.systemName() + " " + n.systemDescription());
                if (!pareceInfraGerenciavel) {
                    continue;
                }
                if (!SwitchConnector.portaTcpAberta(mgmtIp, 22, 450)
                        && !SwitchConnector.portaTcpAberta(mgmtIp, 23, 450)) {
                    continue;
                }
                candidatos.putIfAbsent(mgmtIp, new SwitchTarget(mgmtIp, credUser, credPass, null));
            }
        }
        return new ArrayList<>(candidatos.values());
    }

    private static boolean pareceswitchIntelbras(String texto) {
        if (texto == null || texto.isBlank()) return false;
        String lower = texto.toLowerCase();
        return lower.contains("intelbras")
                || lower.matches(".*\\b(s2[0-9]{3}|s3[0-9]{3}|sg\\s*\\d{4}|olt|sc\\s*\\d{4})\\b.*");
    }

    public static void aplicarFallbackGatewayLocal(List<SwitchIdentity> coletados, TopologyEngine engine) {
        engine.ipGatewaySemMacResolvido().ifPresent(ipGw ->
                LocalGatewayDetector.resolverMacNoArpLocal(ipGw)
                        .ifPresent(mac -> engine.registrarMacExternoParaIp(ipGw, mac))
        );

        if (engine.gatewayIdentificado().isEmpty()) {
            List<String> ipsColetados = coletados.stream()
                    .map(SwitchIdentity::ip)
                    .filter(ip -> ip != null && !ip.isBlank())
                    .toList();
            LocalGatewayDetector.detectarGatewayLocalNaMesmaSubRede(ipsColetados)
                    .ifPresent(engine::definirGatewayFallbackLocal);
        }
    }

    private ColetaDispositivoResultado coletarUmIsolado(
            SwitchTarget alvo,
            Map<String, DiscoveredProduct.FamiliaProduto> familiasPorIp,
            Map<String, String> protocolosPreferidosPorIp,
            List<CredencialAcesso> credenciaisAlternativas
    ) {
        DiscoveredProduct.FamiliaProduto familia = familiasPorIp != null ? familiasPorIp.get(alvo.ip()) : null;
        String proto = protocolosPreferidosPorIp != null ? protocolosPreferidosPorIp.get(alvo.ip()) : null;
        if (familia == null && alvo.portaCustomizada() == null) {
            // Alvo manual sem scan (ex.: switches.txt no modo CLI): identifica a família antes do login
            // para aplicar timeout/protocolo corretos (SG legado prefere Telnet) e as credenciais certas.
            Optional<DiscoveredProduct> prod = ProductFingerprinter.sondarHost(
                    alvo.ip(), "", ProductFingerprinter.ProbeConfig.preColeta());
            if (prod.isPresent()) {
                familia = prod.get().familia();
                if (proto == null || proto.isBlank()) {
                    proto = prod.get().protocoloPreferido();
                }
            }
        }
        ConnectionConfig config = criarConnectionConfigParaAlvo(alvo, alvo.usuario(), alvo.senha(), familia, proto);
        String ipIdentidade = alvo.enderecoExibicao();
        SwitchClient cliente = null;

        try {
            ConexaoBemSucedida conn = conectarComFallbackCredenciais(alvo, config, familia, credenciaisAlternativas);
            cliente = conn.cliente();
            SwitchTarget alvoEfetivo = conn.alvoEfetivo();
            ColetaDispositivoResultado bruto = executarColetaComCliente(alvoEfetivo, ipIdentidade, cliente);
            return (conn.credencialAlternativaUsada() != null)
                    ? bruto.comCredencialAlternativa(conn.credencialAlternativaUsada())
                    : bruto;
        } catch (SwitchConnectionException e) {
            return new ColetaDispositivoResultado(
                    alvo, null, null, List.of(), List.of(), null, List.of(), List.of(), List.of(), Map.of(), e
            );
        } finally {
            if (cliente != null) {
                cliente.desconectar();
            }
        }
    }

    private static ConnectionConfig criarConnectionConfigParaAlvo(
            SwitchTarget alvo,
            String usuario,
            String senha,
            DiscoveredProduct.FamiliaProduto familia,
            String proto
    ) {
        boolean legadoSgLento = familia == DiscoveredProduct.FamiliaProduto.SWITCH_SG_MR_L2PLUS
                || familia == DiscoveredProduct.FamiliaProduto.SWITCH_SG_MR
                || familia == DiscoveredProduct.FamiliaProduto.SWITCH_S2050_LITE;
        boolean preferirTelnet = "TELNET".equalsIgnoreCase(proto)
                || familia == DiscoveredProduct.FamiliaProduto.SWITCH_SG_MR_L2PLUS;
        long timeoutMs = legadoSgLento ? 45_000L
                : (familia == null || familia == DiscoveredProduct.FamiliaProduto.DESCONHECIDO_IGNORAR ? 25_000L : 12_000L);
        return ConnectionConfig.comOpcoes(
                alvo.ip(), usuario, senha, alvo.portaCustomizada(), timeoutMs, preferirTelnet);
    }

    private ColetaDispositivoResultado executarColetaComCliente(
            SwitchTarget alvo,
            String ipIdentidade,
            SwitchClient cliente
    ) throws SwitchConnectionException {
        String bannerInicial = cliente.bannerInicial();

        // 0) AP Linux / OpenWrt (# ou ubus)
        if (apParser.ehBannerOuPromptDeAp(bannerInicial)) {
            Optional<ColetaDispositivoResultado> apOpt = tentarColetarApIsolado(alvo, ipIdentidade, cliente);
            if (apOpt.isPresent()) {
                return apOpt.get();
            }
        }

        // 0b) OLTs FiberHome / Intelbras AN6000 / AN6001-G16 / AN5516-04
        if (oltParser.ehBannerOltAn(bannerInicial)) {
            return coletarOltAnIsolado(alvo, ipIdentidade, cliente, bannerInicial);
        }

        // 0c) OLTs 8820I / 8820G ("intelbras-olt>" CLI)
        if (oltParser.ehBannerOlt8820(bannerInicial)) {
            return coletarOlt8820Isolado(alvo, ipIdentidade, cliente, bannerInicial, null, null);
        }

        // 1) Comware (S23/S33)
        boolean promptTerminaComHash = promptInicialTerminaComHash(bannerInicial);
        if (!promptTerminaComHash) {
            String respComware = cliente.executarComando("screen-length disable");
            if (!ehComandoInvalido(respComware)) {
                Optional<ColetaDispositivoResultado> comwareOpt = coletarComwareIsolado(alvo, ipIdentidade, cliente);
                if (comwareOpt.isPresent()) {
                    return comwareOpt.get();
                }
            }
        }

        // 2) Consulta inicial show version / show system (antes do terminal length para diferenciar OLT)
        String version = cliente.executarComando("show version");
        String systemInfo = cliente.executarComando("show system");
        String infoCombinada = version + "\n" + systemInfo;

        if (oltParser.ehDispositivoOlt(infoCombinada)) {
            return coletarOltIsolado(alvo, ipIdentidade, cliente, infoCombinada);
        }

        // Fallback caso seja uma 8820I/8820G com banner alterado ou AP Linux
        if (ehComandoInvalido(version) && ehComandoInvalido(systemInfo)) {
            Optional<ColetaDispositivoResultado> olt8820Opt = tentarColetarOlt8820Fallback(alvo, ipIdentidade, cliente, bannerInicial);
            if (olt8820Opt.isPresent()) {
                return olt8820Opt.get();
            }
            Optional<ColetaDispositivoResultado> apOpt = tentarColetarApIsolado(alvo, ipIdentidade, cliente);
            if (apOpt.isPresent()) {
                return apOpt.get();
            }
        }

        // 3) Série 3000 / Max / S2050G-A / Lite / SG MR L2+
        return coletarSerie3000Isolado(alvo, ipIdentidade, cliente, infoCombinada);
    }

    private Optional<ColetaDispositivoResultado> coletarComwareIsolado(
            SwitchTarget alvo,
            String ipIdentidade,
            SwitchClient cliente
    ) throws SwitchConnectionException {
        String manuinfo = cliente.executarComando("display device manuinfo");
        if (ehComandoInvalido(manuinfo)) {
            return Optional.empty();
        }
        String macTableBruta = cliente.executarComando("display mac-address");
        SwitchIdentity identidade = comwareParser.parseDeviceManuInfo(ipIdentidade, manuinfo);
        String swId = identidade.macNormalizado();

        Map<String, List<String>> membrosPorBagg = Map.of();
        List<MacEntry> entradas = comwareParser.parseMacAddressTable(swId, macTableBruta);
        if (possuiPortaAgregada(entradas)) {
            String lagBruto = cliente.executarComando("display link-aggregation verbose");
            if (!ehComandoInvalido(lagBruto)) {
                membrosPorBagg = comwareParser.parseLinkAggregation(lagBruto);
                entradas = comwareParser.expandirPortasAgregadas(entradas, membrosPorBagg);
            }
        }

        String rotasBrutas = cliente.executarComando("display ip routing-table");
        String rotaPadrao = !ehComandoInvalido(rotasBrutas)
                ? comwareParser.parseDefaultGatewayIp(rotasBrutas).orElse(null)
                : null;

        List<ArpEntry> entradasArp = List.of();
        String arpBruto = cliente.executarComando("display arp");
        if (!ehComandoInvalido(arpBruto)) {
            entradasArp = comwareParser.parseArpTable(arpBruto);
            if (!membrosPorBagg.isEmpty()) {
                entradasArp = comwareParser.expandirPortasArp(entradasArp, membrosPorBagg);
            }
        }

        List<LldpNeighbor> lldp = List.of();
        String lldpVerbose = cliente.executarComando("display lldp neighbor-information verbose");
        if (!ehComandoInvalido(lldpVerbose)) {
            lldp = comwareParser.parseLldpNeighborsVerbose(swId, lldpVerbose);
        }
        if (lldp.isEmpty()) {
            String lldpList = cliente.executarComando("display lldp neighbor-information list");
            if (!ehComandoInvalido(lldpList)) {
                lldp = comwareParser.parseLldpNeighborsList(swId, lldpList);
            }
        }

        String stpBruto = cliente.executarComando("display stp brief");
        List<StpPortState> stp = !ehComandoInvalido(stpBruto)
                ? comwareParser.parseStpBrief(swId, stpBruto)
                : List.of();

        String lbBruto = cliente.executarComando("display loopback-detection");
        List<LoopDiagnostic> loopback = !ehComandoInvalido(lbBruto)
                ? comwareParser.parseLoopbackDetection(swId, lbBruto)
                : List.of();

        return Optional.of(new ColetaDispositivoResultado(
                alvo, identidade, "Comware", entradas, entradasArp, rotaPadrao, lldp, stp, loopback, membrosPorBagg, null
        ));
    }

    /**
     * Conecta percorrendo uma única cadeia de credenciais (alvo → extras aplicáveis → padrões de fábrica).
     * A cadeia é testada dentro da própria camada de conexão (na mesma sessão Telnet quando o equipamento
     * permite, ou abrindo nova sessão SSH a cada recusa), evitando reconexões e repetições desnecessárias.
     */
    private ConexaoBemSucedida conectarComFallbackCredenciais(
            SwitchTarget alvo,
            ConnectionConfig config,
            DiscoveredProduct.FamiliaProduto familia,
            List<CredencialAcesso> credenciaisAlternativas
    ) throws SwitchConnectionException {
        List<CredencialLogin> alternativas = montarCadeiaAlternativas(alvo, familia, credenciaisAlternativas);
        ConnectionConfig cfg = config.comCredenciaisAlternativas(alternativas).comTelnetAposFalhaAuth(true);

        SwitchClient cli = SwitchConnector.conectar(cfg);
        CredencialLogin efetiva = cli.credencialEfetiva();
        if (efetiva == null || efetiva.mesmoParQue(alvo.usuario(), alvo.senha())) {
            return new ConexaoBemSucedida(cli, alvo, null);
        }
        SwitchTarget alvoEfetivo = new SwitchTarget(
                alvo.ip(), efetiva.usuario(), efetiva.senha(), alvo.portaCustomizada());
        String rotulo = efetiva.rotulo().equals(efetiva.usuario())
                ? efetiva.usuario()
                : efetiva.rotulo() + " (" + efetiva.usuario() + ")";
        return new ConexaoBemSucedida(cli, alvoEfetivo, rotulo);
    }

    /** Máximo de credenciais alternativas por alvo (evita bloqueio por excesso de tentativas). */
    static final int MAX_CREDENCIAIS_ALTERNATIVAS = 6;

    static List<CredencialLogin> montarCadeiaAlternativas(
            SwitchTarget alvo,
            DiscoveredProduct.FamiliaProduto familia,
            List<CredencialAcesso> extras
    ) {
        List<CredencialAcesso> candidatas = new ArrayList<>(selecionarCredenciaisAplicaveis(alvo, familia, extras));
        for (CredencialAcesso padrao : CredencialAcesso.padroesFabrica(familia)) {
            if (padrao.aplicavelPara(familia)) {
                candidatas.add(padrao);
            }
        }
        List<CredencialLogin> logins = new ArrayList<>();
        if (alvo != null) {
            logins.add(new CredencialLogin(alvo.usuario(), alvo.senha()));
        }
        for (CredencialAcesso c : candidatas) {
            logins.add(new CredencialLogin(c.usuario(), c.senha(), c.nomePerfil()));
        }
        List<CredencialLogin> unicas = CredencialLogin.semDuplicatas(logins);
        if (alvo != null && !unicas.isEmpty() && unicas.get(0).mesmoParQue(alvo.usuario(), alvo.senha())) {
            unicas.remove(0);
        }
        return unicas.size() > MAX_CREDENCIAIS_ALTERNATIVAS
                ? new ArrayList<>(unicas.subList(0, MAX_CREDENCIAIS_ALTERNATIVAS))
                : unicas;
    }

    static List<CredencialAcesso> selecionarCredenciaisAplicaveis(
            SwitchTarget alvoPrincipal,
            DiscoveredProduct.FamiliaProduto familia,
            List<CredencialAcesso> extras
    ) {
        if (extras == null || extras.isEmpty()) {
            return List.of();
        }
        String uOrig = alvoPrincipal != null && alvoPrincipal.usuario() != null ? alvoPrincipal.usuario() : "";
        String sOrig = alvoPrincipal != null && alvoPrincipal.senha() != null ? alvoPrincipal.senha() : "";
        List<CredencialAcesso> selecionadas = new ArrayList<>();
        Set<String> chaves = new LinkedHashSet<>();
        chaves.add(uOrig + "\0" + sOrig);

        for (CredencialAcesso c : extras) {
            if (c == null || !c.ehUtilizavel() || !c.aplicavelPara(familia)) {
                continue;
            }
            String chave = c.usuario() + "\0" + c.senha();
            if (chaves.add(chave)) {
                selecionadas.add(c);
            }
        }
        return selecionadas;
    }

    private ColetaDispositivoResultado coletarOltIsolado(
            SwitchTarget alvo,
            String ipIdentidade,
            SwitchClient cliente,
            String infoCombinada
    ) throws SwitchConnectionException {
        cliente.executarComando("terminal length 0");
        String macTableBruta = cliente.executarComando("show mac-address-table");
        SwitchIdentity identidade = oltParser.parseDeviceInfo(ipIdentidade, "OLT", infoCombinada);
        String swId = identidade.macNormalizado();

        List<MacEntry> entradas = oltParser.parseMacAddressTable(swId, macTableBruta);

        String rotasBrutas = cliente.executarComando("show ip route");
        String rotaPadrao = !ehComandoInvalido(rotasBrutas)
                ? oltParser.parseDefaultGatewayIp(rotasBrutas).orElse(null)
                : null;

        List<ArpEntry> entradasArp = List.of();
        for (String cmdArp : List.of("show arp all", "show arp", "show ip fdb")) {
            String raw = cliente.executarComando(cmdArp);
            if (!ehComandoInvalido(raw)) {
                List<ArpEntry> parsed = oltParser.parseArpTable(raw);
                if (!parsed.isEmpty()) {
                    entradasArp = parsed;
                    break;
                }
            }
        }

        List<LldpNeighbor> lldp = List.of();
        for (String cmdLldp : List.of("show lldp interface", "show lldp neighbors detail", "show lldp neighbors")) {
            String raw = cliente.executarComando(cmdLldp);
            if (!ehComandoInvalido(raw)) {
                lldp = OltParser.parseLldpNeighbors(swId, raw);
                if (!lldp.isEmpty()) break;
            }
        }

        List<StpPortState> stp = List.of();
        for (String cmdStp : List.of("show spanning-tree", "show spanning-tree interface brief")) {
            String raw = cliente.executarComando(cmdStp);
            if (!ehComandoInvalido(raw)) {
                List<StpPortState> parsed = oltParser.parseSpanningTree(swId, raw);
                if (!parsed.isEmpty()) {
                    stp = parsed;
                    break;
                }
            }
        }

        return new ColetaDispositivoResultado(
                alvo, identidade, "OLT", entradas, entradasArp, rotaPadrao, lldp, stp, List.of(), Map.of(), null
        );
    }

    private ColetaDispositivoResultado coletarOltAnIsolado(
            SwitchTarget alvo,
            String ipIdentidade,
            SwitchClient cliente,
            String bannerInicial
    ) throws SwitchConnectionException {
        cliente.executarComando("config");
        cliente.executarComando("terminal length 0");

        String showCard = cliente.executarComando("show card");
        String showHostname = cliente.executarComando("show hostname");
        String showManageVlan = cliente.executarComando("show manage-vlan all");

        SwitchIdentity identidade = oltParser.parseDeviceInfoAn(
                ipIdentidade, showCard, showHostname, showManageVlan, bannerInicial);
        String swId = identidade.macNormalizado();

        String macRaw = cliente.executarComando("show mac-address all");
        List<MacEntry> entradas = !ehComandoInvalido(macRaw) ? oltParser.parseMacTableAn(swId, macRaw) : List.of();

        // Fallback para linha legada AN5516-04 (cd fdb -> show fdb slot <N>)
        if (ehComandoInvalido(macRaw)) {
            String cdResp = cliente.executarComando("cd fdb");
            if (ehComandoInvalido(cdResp)) {
                cliente.executarComando("exit");
                cdResp = cliente.executarComando("cd fdb");
            }
            if (!ehComandoInvalido(cdResp)) {
                List<Integer> slots = oltParser.extrairSlotsAtivosAn(showCard);
                if (slots.isEmpty()) {
                    slots = List.of(1, 2, 9, 19);
                }
                List<MacEntry> acumulados = new ArrayList<>();
                for (Integer slot : slots) {
                    String fdb = cliente.executarComando("show fdb slot " + slot);
                    if (!ehComandoInvalido(fdb)) {
                        acumulados.addAll(oltParser.parseMacTableAn(swId, fdb));
                    }
                }
                entradas = acumulados;
                cliente.executarComando("cd ..");
                cliente.executarComando("config");
            }
        }

        List<ArpEntry> entradasArp = List.of();
        for (String cmdArp : List.of("show arp-table all", "show arp all")) {
            String raw = cliente.executarComando(cmdArp);
            if (!ehComandoInvalido(raw)) {
                entradasArp = oltParser.parseArpTableAn(raw);
                if (!entradasArp.isEmpty()) break;
            }
        }

        String rotaPadrao = null;
        for (String cmdRota : List.of("show ipv4 route", "show static-route running-config", "show ip route")) {
            String raw = cliente.executarComando(cmdRota);
            if (!ehComandoInvalido(raw)) {
                Optional<String> gw = oltParser.parseDefaultGatewayAn(raw, identidade.ip(), entradasArp);
                if (gw.isPresent()) {
                    rotaPadrao = gw.get();
                    break;
                }
            }
        }

        List<LldpNeighbor> lldp = List.of();
        for (String cmdLldp : List.of("show lldp neighbor-information", "show lldp neighbor-information brief")) {
            String raw = cliente.executarComando(cmdLldp);
            if (!ehComandoInvalido(raw)) {
                lldp = OltParser.parseLldpNeighbors(swId, raw);
                if (!lldp.isEmpty()) break;
            }
        }

        String stpRaw = cliente.executarComando("show stp brief");
        List<StpPortState> stp = !ehComandoInvalido(stpRaw)
                ? oltParser.parseSpanningTree(swId, stpRaw)
                : List.of();

        return new ColetaDispositivoResultado(
                alvo, identidade, "OLT", entradas, entradasArp, rotaPadrao, lldp, stp, List.of(), Map.of(), null
        );
    }

    private Optional<ColetaDispositivoResultado> tentarColetarOlt8820Fallback(
            SwitchTarget alvo,
            String ipIdentidade,
            SwitchClient cliente,
            String bannerInicial
    ) throws SwitchConnectionException {
        String firmwareOut = executarComRetryBusy8820(cliente, "firmware show");
        String interfaceOut = executarComRetryBusy8820(cliente, "interface show");
        if (ehComandoInvalido(firmwareOut) && ehComandoInvalido(interfaceOut)) {
            return Optional.empty();
        }
        return Optional.of(coletarOlt8820Isolado(alvo, ipIdentidade, cliente, bannerInicial, interfaceOut, firmwareOut));
    }

    private ColetaDispositivoResultado coletarOlt8820Isolado(
            SwitchTarget alvo,
            String ipIdentidade,
            SwitchClient cliente,
            String bannerInicial,
            String preInterfaceOut,
            String preFirmwareOut
    ) throws SwitchConnectionException {
        String interfaceOut = preInterfaceOut != null ? preInterfaceOut : executarComRetryBusy8820(cliente, "interface show");
        String firmwareOut = preFirmwareOut != null ? preFirmwareOut : executarComRetryBusy8820(cliente, "firmware show");
        SwitchIdentity identidade = oltParser.parseDeviceInfo8820(
                ipIdentidade, "OLT", interfaceOut, firmwareOut, bannerInicial);
        String swId = identidade.macNormalizado();

        String macRaw = executarComRetryBusy8820(cliente, "bridge show mac all");
        List<MacEntry> entradas = !ehComandoInvalido(macRaw) ? oltParser.parseMacTable8820(swId, macRaw) : List.of();

        String routeRaw = executarComRetryBusy8820(cliente, "route show");
        String rotaPadrao = !ehComandoInvalido(routeRaw)
                ? oltParser.parseDefaultGatewayIp(routeRaw).orElse(null)
                : null;

        return new ColetaDispositivoResultado(
                alvo, identidade, "OLT", entradas, List.of(), rotaPadrao, List.of(), List.of(), List.of(), Map.of(), null
        );
    }

    private static String executarComRetryBusy8820(SwitchClient cliente, String comando) throws SwitchConnectionException {
        String resposta = cliente.executarComando(comando);
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            String lower = resposta != null ? resposta.toLowerCase() : "";
            if (!lower.contains("system is busy") && !lower.contains("try again later")) {
                break;
            }
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            resposta = cliente.executarComando(comando);
        }
        return resposta;
    }

    private ColetaDispositivoResultado coletarSerie3000Isolado(
            SwitchTarget alvo,
            String ipIdentidade,
            SwitchClient cliente,
            String infoCombinada
    ) throws SwitchConnectionException {
        cliente.executarComando("terminal length 0");
        StringBuilder identidadeBruta = new StringBuilder(infoCombinada);

        boolean ehLegadoSgMr2013 = false;
        String lowerComb = infoCombinada.toLowerCase();
        if (!lowerComb.contains("product name")
                && !lowerComb.contains("hardware model")
                && !lowerComb.contains("serial")) {
            String sysInfo2013 = cliente.executarComando("show system-info");
            if (!ehComandoInvalido(sysInfo2013)) {
                identidadeBruta.append("\n").append(sysInfo2013);
                ehLegadoSgMr2013 = true;
            }
        }

        String sysMacInfo = ehLegadoSgMr2013 ? "" : cliente.executarComando("show system mac");
        if (!ehLegadoSgMr2013 && ehComandoInvalido(sysMacInfo)) {
            sysMacInfo = cliente.executarComando("show mac-address system-mac");
        }
        if (!ehComandoInvalido(sysMacInfo)) {
            identidadeBruta.append("\n").append(sysMacInfo);
        }

        String ipAddressBruto = "";
        if (ehLegadoSgMr2013 || !serie3000Parser.contemMacExtraivel(identidadeBruta.toString())) {
            ipAddressBruto = cliente.executarComando("show ip address");
            if (!ehComandoInvalido(ipAddressBruto)) {
                identidadeBruta.append("\n").append(ipAddressBruto);
            }
        }

        // Sempre coleta STP para diagnóstico de redundância/loop (e fallback de Bridge MAC)
        String stpInfo = coletarStpBrutoSerie3000(cliente, ehLegadoSgMr2013);
        if (!stpInfo.isBlank()) {
            identidadeBruta.append("\n").append(stpInfo);
        }

        if (!serie3000Parser.contemMacExtraivel(identidadeBruta.toString())) {
            String mgmtInfo = cliente.executarComando("show interface vlan1.1");
            if (ehComandoInvalido(mgmtInfo) || !serie3000Parser.contemMacExtraivel(mgmtInfo)) {
                mgmtInfo = cliente.executarComando("show interface vlan 1");
            }
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
            for (String cmdInt : List.of("show interface gi1/0/1", "show interface gi0/1", "show interface g0/1")) {
                if (ehComandoInvalido(mgmtInfo) || !serie3000Parser.contemMacExtraivel(mgmtInfo)) {
                    mgmtInfo = cliente.executarComando(cmdInt);
                } else {
                    break;
                }
            }
            if (!ehComandoInvalido(mgmtInfo)) {
                identidadeBruta.append("\n").append(mgmtInfo);
            }
        }

        if (!ehLegadoSgMr2013) {
            String mpuInfo = cliente.executarComando("show system mpu");
            if (!ehComandoInvalido(mpuInfo)) {
                identidadeBruta.append("\n").append(mpuInfo);
            }
        }

        // Coleta tabela MAC antes de fechar a identidade para aproveitar 'local vlan1.1' (S2050G-A / Lite) caso necessário
        String macTableBrutaValida = coletarTabelaMacSerie3000(cliente, ehLegadoSgMr2013);
        if (!serie3000Parser.contemMacExtraivel(identidadeBruta.toString()) && !macTableBrutaValida.isBlank()) {
            String macLocal = Serie3000Parser.extrairMacLocalVlan(macTableBrutaValida);
            if (macLocal != null && !macLocal.isBlank()) {
                identidadeBruta.append("\nSystem MAC address: ").append(macLocal).append("\n");
            }
        }

        SwitchIdentity identidade = serie3000Parser.parseDeviceVersion(
                ipIdentidade, null, identidadeBruta.toString());
        String swId = identidade.macNormalizado();

        List<MacEntry> entradas = !macTableBrutaValida.isBlank()
                ? serie3000Parser.parseMacAddressTable(swId, macTableBrutaValida)
                : List.of();

        Map<String, List<String>> membrosPorBagg = coletarAggregacaoSerie3000(cliente, ehLegadoSgMr2013);
        if (!membrosPorBagg.isEmpty()) {
            entradas = serie3000Parser.expandirPortasAgregadas(entradas, membrosPorBagg);
        }

        String rotasBrutas = ehLegadoSgMr2013 ? "" : cliente.executarComando("show ip route");
        if (!ehLegadoSgMr2013 && ehComandoInvalido(rotasBrutas)) {
            rotasBrutas = cliente.executarComando("show route");
        }
        String rotaPadrao = !ehComandoInvalido(rotasBrutas)
                ? serie3000Parser.parseDefaultGatewayIp(rotasBrutas).orElse(null)
                : null;
        if (rotaPadrao == null) {
            if (ipAddressBruto.isBlank()) {
                String fallbackIp = cliente.executarComando("show ip address");
                if (!ehComandoInvalido(fallbackIp)) {
                    identidadeBruta.append("\n").append(fallbackIp);
                }
            }
            rotaPadrao = serie3000Parser.parseDefaultGatewayIp(identidadeBruta.toString()).orElse(null);
        }

        List<ArpEntry> entradasArp = List.of();
        if (!ehLegadoSgMr2013) {
            for (String cmdArp : List.of("show arp", "show ip arp")) {
                String arpBruto = cliente.executarComando(cmdArp);
                if (!ehComandoInvalido(arpBruto)) {
                    entradasArp = serie3000Parser.parseArpTable(arpBruto);
                    if (!entradasArp.isEmpty()) break;
                }
            }
            if (!membrosPorBagg.isEmpty() && !entradasArp.isEmpty()) {
                entradasArp = serie3000Parser.expandirPortasArp(entradasArp, membrosPorBagg);
            }
        }

        List<LldpNeighbor> lldp = ehLegadoSgMr2013 ? List.of() : coletarLldpSerie3000(cliente, swId);
        List<StpPortState> stp = !stpInfo.isBlank()
                ? serie3000Parser.parseSpanningTree(swId, stpInfo)
                : List.of();

        String lbBruto = ehLegadoSgMr2013 ? "" : cliente.executarComando("show loopback-detection");
        List<LoopDiagnostic> loopback = !ehComandoInvalido(lbBruto)
                ? serie3000Parser.parseLoopbackDetection(swId, lbBruto)
                : List.of();

        return new ColetaDispositivoResultado(
                alvo, identidade, "Série 3000", entradas, entradasArp, rotaPadrao, lldp, stp, loopback, membrosPorBagg, null
        );
    }

    private String coletarTabelaMacSerie3000(SwitchClient cliente, boolean ehLegadoSgMr2013) throws SwitchConnectionException {
        List<String> comandos = ehLegadoSgMr2013
                ? List.of("show bridge address all", "show mac-address all", "show mac-address-table")
                : COMANDOS_MAC_S30;
        String valida = "";
        for (String cmdMac : comandos) {
            String macTableBruta = cliente.executarComando(cmdMac);
            if (!ehComandoInvalido(macTableBruta)) {
                valida = macTableBruta;
                String macLocal = Serie3000Parser.extrairMacLocalVlan(macTableBruta);
                if (!serie3000Parser.parseMacAddressTable("tmp", macTableBruta).isEmpty()
                        || (macLocal != null && !macLocal.isBlank())) {
                    break;
                }
            }
        }
        return valida;
    }

    private String coletarStpBrutoSerie3000(SwitchClient cliente, boolean ehLegadoSgMr2013) throws SwitchConnectionException {
        if (!ehLegadoSgMr2013) {
            String stpInfo = cliente.executarComando("show spanning-tree");
            if (!ehComandoInvalido(stpInfo) && !serie3000Parser.parseSpanningTree("tmp", stpInfo).isEmpty()) {
                return stpInfo;
            }
            String brief = cliente.executarComando("show spanning-tree brief");
            if (!ehComandoInvalido(brief) && !serie3000Parser.parseSpanningTree("tmp", brief).isEmpty()) {
                return brief;
            }
        }
        String globalInfo = cliente.executarComando("show spanning-tree global-info");
        String portConfig = cliente.executarComando("show spanning-tree port-config");
        StringBuilder sb = new StringBuilder();
        if (!ehComandoInvalido(globalInfo)) sb.append(globalInfo).append("\n");
        if (!ehComandoInvalido(portConfig)) sb.append(portConfig);
        return sb.toString();
    }

    private Map<String, List<String>> coletarAggregacaoSerie3000(SwitchClient cliente, boolean ehLegadoSgMr2013)
            throws SwitchConnectionException {
        List<String> comandos = ehLegadoSgMr2013
                ? List.of("show interface link-aggregation", "show link-aggregation group", "show link-aggregation")
                : COMANDOS_LAG_S30;
        for (String cmd : comandos) {
            String lagBruto = cliente.executarComando(cmd);
            if (!ehComandoInvalido(lagBruto)) {
                return serie3000Parser.parseLinkAggregation(lagBruto);
            }
        }
        return Map.of();
    }

    private List<LldpNeighbor> coletarLldpSerie3000(SwitchClient cliente, String swId) throws SwitchConnectionException {
        String detail = cliente.executarComando("show lldp neighbors detail");
        if (!ehComandoInvalido(detail)) {
            List<LldpNeighbor> res = serie3000Parser.parseLldpSerie3000(swId, detail);
            if (!res.isEmpty()) return res;
        }
        String singular = cliente.executarComando("show lldp neighbor");
        if (!ehComandoInvalido(singular)) {
            List<LldpNeighbor> res = serie3000Parser.parseLldpS2050Lite(swId, singular);
            if (res.isEmpty()) {
                res = serie3000Parser.parseLldpSerie3000(swId, singular);
            }
            if (!res.isEmpty()) return res;
        }
        String plural = cliente.executarComando("show lldp neighbors");
        if (!ehComandoInvalido(plural)) {
            List<LldpNeighbor> res = serie3000Parser.parseLldpSerie3000(swId, plural);
            if (res.isEmpty()) {
                res = serie3000Parser.parseLldpS2050Lite(swId, plural);
            }
            return res;
        }
        return List.of();
    }

    private Optional<ColetaDispositivoResultado> tentarColetarApIsolado(
            SwitchTarget alvo,
            String ipIdentidade,
            SwitchClient cliente
    ) throws SwitchConnectionException {
        String brctlShow = cliente.executarComando("brctl show");
        if (ehComandoInvalido(brctlShow)) {
            return Optional.empty();
        }
        String ubusBoard = cliente.executarComando("ubus call system board");
        if (ehComandoInvalido(ubusBoard)) {
            ubusBoard = "";
        }
        String ifconfig = cliente.executarComando("ifconfig");
        if (ehComandoInvalido(ifconfig)) {
            ifconfig = "";
        }

        if (!apParser.ehDispositivoAp(ubusBoard, brctlShow, ifconfig)) {
            return Optional.empty();
        }

        SwitchIdentity identidade = apParser.parseDeviceInfo(ipIdentidade, ubusBoard, ifconfig, brctlShow);
        String swId = identidade.macNormalizado();

        Map<String, Integer> vlanPorBridge = apParser.parseBridgesEVlans(brctlShow);
        if (vlanPorBridge.isEmpty()) {
            vlanPorBridge = Map.of("br-lan", 1);
        }

        List<MacEntry> todasEntradas = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : vlanPorBridge.entrySet()) {
            String bridge = entry.getKey();
            int vlanPadrao = entry.getValue();
            String showMacs = cliente.executarComando("brctl showmacs " + bridge);
            if (!ehComandoInvalido(showMacs)) {
                todasEntradas.addAll(apParser.parseBridgeMacs(swId, vlanPadrao, showMacs));
            }
        }

        String rotasBrutas = cliente.executarComando("ip route");
        if (ehComandoInvalido(rotasBrutas)) {
            rotasBrutas = cliente.executarComando("route");
        }
        String rotaPadrao = !ehComandoInvalido(rotasBrutas)
                ? apParser.parseDefaultGatewayIp(rotasBrutas).orElse(null)
                : null;

        List<ArpEntry> entradasArp = List.of();
        String neighBruto = cliente.executarComando("ip neigh");
        if (!ehComandoInvalido(neighBruto)) {
            entradasArp = apParser.parseArpTable(neighBruto, todasEntradas);
        }

        List<LldpNeighbor> lldp = List.of();
        String lldpBruto = cliente.executarComando("lldpcli show neighbors 2>/dev/null || lldpctl 2>/dev/null");
        if (!ehComandoInvalido(lldpBruto)) {
            lldp = ApParser.parseLldpNeighbors(swId, lldpBruto);
        }

        return Optional.of(new ColetaDispositivoResultado(
                alvo, identidade, "AP", todasEntradas, entradasArp, rotaPadrao, lldp, List.of(), List.of(), Map.of(), null
        ));
    }

    private static boolean promptInicialTerminaComHash(String banner) {
        if (banner == null || banner.isBlank()) return false;
        String[] linhas = banner.split("\\R");
        for (int i = linhas.length - 1; i >= 0; i--) {
            if (!linhas[i].isBlank()) {
                return linhas[i].trim().endsWith("#");
            }
        }
        return false;
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

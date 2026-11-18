package tpDiscover.intelbras.coleta;

import tpDiscover.intelbras.connection.SwitchConnectionException;
import tpDiscover.intelbras.correlacao.RelatorioTopologia;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.descoberta.DiscoveredProduct;
import tpDiscover.intelbras.descoberta.NetworkScanTarget;
import tpDiscover.intelbras.descoberta.NetworkScanner;
import tpDiscover.intelbras.descoberta.ProductFingerprinter;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.Serie3000Parser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orquestra uma sessão completa de descoberta, coleta paralela somente-leitura,
 * correlação de topologia, detecção de loops e geração do relatório PDF.
 *
 * Compartilhada entre CLI ({@code App}) e GUI Swing ({@code TpDiscoverFrame}).
 */
public class DiscoverySessionService {

    public record DiscoveryRequest(
            List<String> scanSpecs,
            List<SwitchTarget> alvosManuais,
            String usuarioPadrao,
            String senhaPadrao,
            boolean apenasScan,
            boolean expandirViaLldp,
            boolean usarSnmp,
            int concorrencia,
            String gatewayIpManual,
            String gatewayMacManual,
            Path pastaSaidaPdf,
            Path salvarAlvosEm,
            List<CredencialAcesso> credenciaisAlternativas,
            List<DiscoveredProduct> produtosConhecidosPreviamente,
            List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsPreservados,
            List<TopologyEngine.EquipamentoSemAcesso> equipamentosSemAcessoInformados,
            boolean gerarArquivoRelatorio
    ) {
        public DiscoveryRequest {
            scanSpecs = scanSpecs != null ? List.copyOf(scanSpecs) : List.of();
            alvosManuais = alvosManuais != null ? List.copyOf(alvosManuais) : List.of();
            concorrencia = Math.max(1, concorrencia);
            credenciaisAlternativas = credenciaisAlternativas != null
                    ? List.copyOf(credenciaisAlternativas)
                    : CredencialAcesso.presetsPadrao();
            produtosConhecidosPreviamente = produtosConhecidosPreviamente != null
                    ? List.copyOf(produtosConhecidosPreviamente) : List.of();
            snapshotsPreservados = snapshotsPreservados != null
                    ? List.copyOf(snapshotsPreservados) : List.of();
            equipamentosSemAcessoInformados = equipamentosSemAcessoInformados != null
                    ? List.copyOf(equipamentosSemAcessoInformados) : List.of();
        }

        /** Compatibilidade (CLI): sempre exporta o arquivo PDF/HTML ao final da coleta. */
        public DiscoveryRequest(
                List<String> scanSpecs,
                List<SwitchTarget> alvosManuais,
                String usuarioPadrao,
                String senhaPadrao,
                boolean apenasScan,
                boolean expandirViaLldp,
                boolean usarSnmp,
                int concorrencia,
                String gatewayIpManual,
                String gatewayMacManual,
                Path pastaSaidaPdf,
                Path salvarAlvosEm,
                List<CredencialAcesso> credenciaisAlternativas,
                List<DiscoveredProduct> produtosConhecidosPreviamente,
                List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsPreservados,
                List<TopologyEngine.EquipamentoSemAcesso> equipamentosSemAcessoInformados
        ) {
            this(scanSpecs, alvosManuais, usuarioPadrao, senhaPadrao, apenasScan, expandirViaLldp,
                    usarSnmp, concorrencia, gatewayIpManual, gatewayMacManual, pastaSaidaPdf, salvarAlvosEm,
                    credenciaisAlternativas, produtosConhecidosPreviamente, snapshotsPreservados,
                    equipamentosSemAcessoInformados, true);
        }

        public DiscoveryRequest(
                List<String> scanSpecs,
                List<SwitchTarget> alvosManuais,
                String usuarioPadrao,
                String senhaPadrao,
                boolean apenasScan,
                boolean expandirViaLldp,
                boolean usarSnmp,
                int concorrencia,
                String gatewayIpManual,
                String gatewayMacManual,
                Path pastaSaidaPdf,
                Path salvarAlvosEm
        ) {
            this(scanSpecs, alvosManuais, usuarioPadrao, senhaPadrao, apenasScan, expandirViaLldp,
                    usarSnmp, concorrencia, gatewayIpManual, gatewayMacManual, pastaSaidaPdf, salvarAlvosEm,
                    CredencialAcesso.presetsPadrao(), List.of(), List.of(), List.of());
        }
    }

    public record DiscoverySessionResult(
            boolean cancelado,
            String erroFatal,
            NetworkScanner.ScanResult scanResult,
            List<SwitchTarget> alvosConsolidados,
            ColetorEmLote.ResultadoColeta resultadoColeta,
            RelatorioTopologia.ResultadoRelatorio relatorio,
            long duracaoScanMs,
            long duracaoColetaMs,
            List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsConsolidados,
            List<DiscoveredProduct> produtosConsolidados
    ) {
        public DiscoverySessionResult(
                boolean cancelado,
                String erroFatal,
                NetworkScanner.ScanResult scanResult,
                List<SwitchTarget> alvosConsolidados,
                ColetorEmLote.ResultadoColeta resultadoColeta,
                RelatorioTopologia.ResultadoRelatorio relatorio,
                long duracaoScanMs,
                long duracaoColetaMs
        ) {
            this(cancelado, erroFatal, scanResult, alvosConsolidados, resultadoColeta, relatorio,
                    duracaoScanMs, duracaoColetaMs, List.of(), List.of());
        }

        public boolean possuiErroFatal() {
            return erroFatal != null && !erroFatal.isBlank();
        }
    }

    public interface DiscoverySessionListener {
        default void onLog(String mensagem) {}

        default void onScanIniciado(int totalIps, List<String> specs) {}

        default void onScanProgresso(int processados, int totalIps, DiscoveredProduct encontradoOuNull) {}

        default void onScanConcluido(NetworkScanner.ScanResult resultado) {}

        default void onColetaIniciada(List<SwitchTarget> alvosConsolidados) {}

        default void onColetaAlvoIniciado(SwitchTarget alvo) {}

        default void onColetaAlvoConcluido(SwitchTarget alvo, boolean sucesso, String resumoOuErro, int concluidos, int total) {}

        default void onExpansaoLldp(List<SwitchTarget> novosAlvos) {}

        default void onRelatorioGerado(RelatorioTopologia.ResultadoRelatorio relatorio) {}
    }

    public DiscoverySessionResult executar(
            DiscoveryRequest req,
            DiscoverySessionListener listener,
            AtomicBoolean cancelar
    ) {
        DiscoverySessionListener cb = listener != null ? listener : new DiscoverySessionListener() {};
        AtomicBoolean cancelFlag = cancelar != null ? cancelar : new AtomicBoolean(false);

        List<DiscoveredProduct> produtosDescobertos = List.of();
        NetworkScanner.ScanResult scanRes = null;
        long duracaoScanMs = 0L;

        if (!req.scanSpecs().isEmpty()) {
            List<NetworkScanTarget> blocos;
            try {
                blocos = NetworkScanTarget.interpretarEspecificacoes(String.join(",", req.scanSpecs()));
            } catch (IllegalArgumentException e) {
                return erroFatal("Especificação de scan inválida: " + e.getMessage(), null, 0L);
            }
            List<String> ips = NetworkScanTarget.consolidarIps(blocos);
            if (ips.isEmpty()) {
                return erroFatal(
                        "Nenhuma sub-rede IPv4 local válida encontrada para scan automático (" + req.scanSpecs() + "). Informe uma faixa/CIDR manualmente.",
                        null, 0L
                );
            }

            cb.onLog(String.format(Locale.US, "Iniciando scan em %d IP(s): %s", ips.size(), req.scanSpecs()));
            cb.onScanIniciado(ips.size(), req.scanSpecs());

            ProductFingerprinter.ProbeConfig probeCfg = new ProductFingerprinter.ProbeConfig(
                    500, 900, req.usarSnmp(), List.of("public")
            );
            NetworkScanner scanner = new NetworkScanner(probeCfg, Math.max(16, req.concorrencia() * 2));
            scanRes = scanner.escanear(ips, cb::onScanProgresso, cancelFlag);
            duracaoScanMs = scanRes.duracaoMillis();
            produtosDescobertos = scanRes.produtosInteresse();
            cb.onScanConcluido(scanRes);
            cb.onLog(String.format(Locale.US,
                    "Scan concluído em %.2fs: %d produto(s) Intelbras encontrado(s), %d outro(s) host(s) ignorado(s).",
                    duracaoScanMs / 1000.0,
                    produtosDescobertos.size(),
                    scanRes.hostsIgnorados().size()));

            if (cancelFlag.get()) {
                return new DiscoverySessionResult(true, null, scanRes, List.of(), null, null, duracaoScanMs, 0L);
            }
        }

        String userCombinacao = (req.apenasScan() && !possuiTexto(req.usuarioPadrao()))
                ? "admin"
                : req.usuarioPadrao();
        List<SwitchTarget> alvos = NetworkScanner.combinarComAlvosManuais(
                produtosDescobertos,
                req.alvosManuais(),
                userCombinacao,
                req.senhaPadrao()
        );

        if (req.apenasScan()) {
            if (req.salvarAlvosEm() != null && !alvos.isEmpty()) {
                try {
                    AlvosLoader.salvarAlvos(req.salvarAlvosEm(), alvos, true);
                    cb.onLog("[Scan] Lista de alvos salva em: " + req.salvarAlvosEm().toAbsolutePath());
                } catch (Exception e) {
                    cb.onLog("[AVISO] Falha ao salvar alvos: " + e.getMessage());
                }
            }
            return new DiscoverySessionResult(false, null, scanRes, alvos, null, null, duracaoScanMs, 0L,
                    req.snapshotsPreservados(), produtosDescobertos);
        }

        Map<String, DiscoveredProduct> produtoPorIp = new LinkedHashMap<>();
        for (DiscoveredProduct p : req.produtosConhecidosPreviamente()) {
            produtoPorIp.put(extrairIpBase(p.ip()), p);
        }
        for (DiscoveredProduct p : produtosDescobertos) {
            produtoPorIp.put(extrairIpBase(p.ip()), p);
        }
        List<DiscoveredProduct> produtosConsolidados = List.copyOf(produtoPorIp.values());

        if (alvos.isEmpty() && req.snapshotsPreservados().isEmpty()) {
            return erroFatal("Nenhum switch/AP/OLT elegível para coleta encontrado.", scanRes, duracaoScanMs);
        }

        if (req.salvarAlvosEm() != null && !alvos.isEmpty()) {
            try {
                AlvosLoader.salvarAlvos(req.salvarAlvosEm(), alvos, true);
                cb.onLog("[Scan] Lista de alvos salva em: " + req.salvarAlvosEm().toAbsolutePath());
            } catch (Exception e) {
                cb.onLog("[AVISO] Falha ao salvar alvos: " + e.getMessage());
            }
        }

        TopologyEngine engine = new TopologyEngine();
        if (possuiTexto(req.gatewayIpManual()) || possuiTexto(req.gatewayMacManual())) {
            String gwIp = possuiTexto(req.gatewayIpManual()) ? req.gatewayIpManual().trim() : null;
            String gwMac = possuiTexto(req.gatewayMacManual())
                    ? Serie3000Parser.formatarMacPadrao(req.gatewayMacManual().trim())
                    : null;
            engine.definirGatewayManual(gwIp, gwMac);
        }

        Map<String, DiscoveredProduct.FamiliaProduto> familiasPorIp = new LinkedHashMap<>();
        Map<String, String> protocolosPreferidosPorIp = new LinkedHashMap<>();
        for (DiscoveredProduct p : produtosConsolidados) {
            String ipBase = extrairIpBase(p.ip());
            familiasPorIp.put(ipBase, p.familia());
            if (!p.protocoloPreferido().isBlank()) {
                protocolosPreferidosPorIp.put(ipBase, p.protocoloPreferido());
            }
            if (!p.macConhecido().isBlank()) {
                engine.registrarMacExternoParaIp(ipBase, p.macConhecido());
            }
        }

        Set<String> ipsTentados = new LinkedHashSet<>();
        for (SwitchTarget t : alvos) {
            ipsTentados.add(extrairIpBase(t.ip()));
        }

        int numPreservados = 0;
        for (ColetorEmLote.ColetaDispositivoSnapshot snap : req.snapshotsPreservados()) {
            String ipSnap = snap.identidade() != null ? extrairIpBase(snap.identidade().ip()) : "";
            if (!ipSnap.isBlank() && !ipsTentados.contains(ipSnap)) {
                ColetorEmLote.registrarSnapshotNoEngine(snap, engine);
                numPreservados++;
            }
        }
        if (numPreservados > 0) {
            cb.onLog(String.format(Locale.US,
                    "[Sessão incremental] Preservando telemetria de %d equipamento(s) já coletado(s); recoletando %d selecionado(s).",
                    numPreservados, alvos.size()));
        }

        ColetorEmLote.ResultadoColeta coletaRes;
        long duracaoColetaMs = 0L;
        if (!alvos.isEmpty()) {
            cb.onLog(String.format(Locale.US, "Iniciando coleta paralela em %d equipamento(s) (concorrência=%d)...",
                    alvos.size(), req.concorrencia()));
            cb.onColetaIniciada(alvos);

            long inicioColeta = System.currentTimeMillis();
            ColetorEmLote coletor = new ColetorEmLote();
            coletaRes = coletor.coletarTudo(
                    alvos,
                    engine,
                    req.concorrencia(),
                    req.expandirViaLldp(),
                    req.usuarioPadrao(),
                    req.senhaPadrao(),
                    familiasPorIp,
                    protocolosPreferidosPorIp,
                    req.credenciaisAlternativas(),
                    new ColetorEmLote.ColetaProgressListener() {
                        @Override
                        public void onAlvoIniciado(SwitchTarget alvo) {
                            cb.onColetaAlvoIniciado(alvo);
                        }

                        @Override
                        public void onAlvoConcluido(SwitchTarget alvo, boolean sucesso, String resumoOuErro, int concluidos, int total) {
                            cb.onColetaAlvoConcluido(alvo, sucesso, resumoOuErro, concluidos, total);
                            cb.onLog((sucesso ? "[OK] " : "[FALHA] ") + alvo.enderecoExibicao() + " -> " + resumoOuErro);
                        }

                        @Override
                        public void onExpansaoLldpIniciada(List<SwitchTarget> novosAlvos) {
                            cb.onExpansaoLldp(novosAlvos);
                            cb.onLog("[Expansão LLDP] +" + novosAlvos.size() + " equipamento(s) descoberto(s) para Onda 2.");
                        }
                    },
                    cancelFlag
            );
            duracaoColetaMs = System.currentTimeMillis() - inicioColeta;
        } else {
            coletaRes = new ColetorEmLote.ResultadoColeta(List.of(), List.of(), List.of());
        }

        List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsConsolidados = consolidarSnapshots(
                req.snapshotsPreservados(),
                coletaRes.snapshotsSucesso(),
                ipsTentados);

        if (cancelFlag.get()) {
            return new DiscoverySessionResult(
                    true, null, scanRes, alvos, coletaRes, null, duracaoScanMs, duracaoColetaMs,
                    snapshotsConsolidados, produtosConsolidados);
        }

        List<SwitchIdentity> todosSwitchesOk = snapshotsConsolidados.stream()
                .map(ColetorEmLote.ColetaDispositivoSnapshot::identidade)
                .filter(id -> id != null)
                .toList();

        if (!todosSwitchesOk.isEmpty()) {
            ColetorEmLote.aplicarFallbackGatewayLocal(todosSwitchesOk, engine);
        }

        registrarPendenciasSemAcesso(engine, coletaRes, req.equipamentosSemAcessoInformados(),
                produtosConsolidados, produtoPorIp, ipsTentados);

        if (todosSwitchesOk.isEmpty()) {
            return new DiscoverySessionResult(
                    false,
                    null,
                    scanRes,
                    alvos,
                    coletaRes,
                    null,
                    duracaoScanMs,
                    duracaoColetaMs,
                    snapshotsConsolidados,
                    produtosConsolidados
            );
        }

        Path pastaDestino = req.pastaSaidaPdf() != null
                ? req.pastaSaidaPdf().toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath().normalize();
        RelatorioTopologia.ResultadoRelatorio relatorio = RelatorioTopologia.gerarResultado(
                engine, pastaDestino, req.gerarArquivoRelatorio());
        cb.onRelatorioGerado(relatorio);
        if (relatorio.arquivoGerado() != null) {
            cb.onLog("[PDF] Relatório gerado em: " + relatorio.arquivoGerado());
        }

        return new DiscoverySessionResult(
                false,
                null,
                scanRes,
                alvos,
                coletaRes,
                relatorio,
                duracaoScanMs,
                duracaoColetaMs,
                snapshotsConsolidados,
                produtosConsolidados
        );
    }

    public static List<ColetorEmLote.ColetaDispositivoSnapshot> consolidarSnapshots(
            List<ColetorEmLote.ColetaDispositivoSnapshot> preservados,
            List<ColetorEmLote.ColetaDispositivoSnapshot> novos,
            Set<String> ipsTentadosNestaEtapa
    ) {
        Map<String, ColetorEmLote.ColetaDispositivoSnapshot> porChave = new LinkedHashMap<>();
        if (preservados != null) {
            for (ColetorEmLote.ColetaDispositivoSnapshot s : preservados) {
                if (s == null || s.identidade() == null) continue;
                String ipBase = extrairIpBase(s.identidade().ip());
                if (!ipBase.isBlank() && ipsTentadosNestaEtapa != null && ipsTentadosNestaEtapa.contains(ipBase)) {
                    continue;
                }
                String chave = ipBase.isBlank() ? s.identidade().macNormalizado() : ipBase;
                porChave.put(chave, s);
            }
        }
        if (novos != null) {
            for (ColetorEmLote.ColetaDispositivoSnapshot s : novos) {
                if (s == null || s.identidade() == null) continue;
                String ipBase = extrairIpBase(s.identidade().ip());
                String chave = ipBase.isBlank() ? s.identidade().macNormalizado() : ipBase;
                porChave.put(chave, s);
            }
        }
        return List.copyOf(porChave.values());
    }

    private static void registrarPendenciasSemAcesso(
            TopologyEngine engine,
            ColetorEmLote.ResultadoColeta coletaRes,
            List<TopologyEngine.EquipamentoSemAcesso> informados,
            List<DiscoveredProduct> produtosConsolidados,
            Map<String, DiscoveredProduct> produtoPorIp,
            Set<String> ipsTentados
    ) {
        for (ColetorEmLote.FalhaColeta f : coletaRes.falhas()) {
            String ipBase = extrairIpBase(f.alvo().ip());
            DiscoveredProduct p = produtoPorIp.get(ipBase);
            String modelo = rotuloProduto(p, "Equipamento");
            String cat = categoriaDoProduto(p);
            String mac = p != null ? p.macConhecido() : "";
            String motivo = mapearMotivoFalha(f.tipo());
            engine.registrarEquipamentoSemAcesso(new TopologyEngine.EquipamentoSemAcesso(
                    ipBase, modelo, cat, mac, motivo));
        }

        if (informados != null) {
            for (TopologyEngine.EquipamentoSemAcesso eq : informados) {
                if (eq != null && !ipsTentados.contains(eq.ipBase())) {
                    engine.registrarEquipamentoSemAcesso(eq);
                }
            }
        }

        Set<String> ipsJaPendencias = new LinkedHashSet<>();
        for (TopologyEngine.EquipamentoSemAcesso eq : engine.equipamentosSemAcesso()) {
            ipsJaPendencias.add(eq.ipBase());
        }

        for (DiscoveredProduct p : produtosConsolidados) {
            if (p.familia() == DiscoveredProduct.FamiliaProduto.ROTEADOR_GATEWAY) {
                continue;
            }
            String ipBase = extrairIpBase(p.ip());
            if (ipsTentados.contains(ipBase) || ipsJaPendencias.contains(ipBase)) {
                continue;
            }
            if (!p.suportaColetaCli()) {
                engine.registrarEquipamentoSemAcesso(new TopologyEngine.EquipamentoSemAcesso(
                        ipBase, rotuloProduto(p, "Equipamento"), categoriaDoProduto(p), p.macConhecido(), "SEM_CLI"));
            }
        }
    }

    private static String rotuloProduto(DiscoveredProduct p, String fallback) {
        if (p == null) {
            return fallback;
        }
        if (p.modeloInferido() != null && !p.modeloInferido().isBlank()) {
            return p.modeloInferido();
        }
        return p.familia() != null ? p.familia().rotulo() : fallback;
    }

    private static String categoriaDoProduto(DiscoveredProduct p) {
        if (p == null) {
            return "SWITCH";
        }
        return switch (p.familia()) {
            case OLT -> "OLT";
            case ACCESS_POINT -> (p.modeloInferido().toLowerCase(Locale.ROOT).contains("wom")
                    || p.modeloInferido().toLowerCase(Locale.ROOT).contains("apc"))
                    ? "RADIO" : "AP";
            case ROTEADOR_GATEWAY -> "ROUTER";
            default -> "SWITCH";
        };
    }

    private static String mapearMotivoFalha(SwitchConnectionException.Tipo tipo) {
        if (tipo == null) {
            return "ERRO_PROTOCOLO";
        }
        return switch (tipo) {
            case FALHA_AUTENTICACAO -> "FALHA_AUTENTICACAO";
            case TIMEOUT -> "TIMEOUT";
            case INALCANCAVEL -> "CONEXAO_RECUSADA";
            case ERRO_PROTOCOLO -> "ERRO_PROTOCOLO";
        };
    }

    private static String extrairIpBase(String ip) {
        if (ip == null) {
            return "";
        }
        int idx = ip.indexOf(':');
        return (idx > 0 ? ip.substring(0, idx) : ip).trim();
    }

    private static DiscoverySessionResult erroFatal(String msg, NetworkScanner.ScanResult scanRes, long duracaoScanMs) {
        return new DiscoverySessionResult(false, msg, scanRes, List.of(), null, null, duracaoScanMs, 0L);
    }

    private static boolean possuiTexto(String s) {
        return s != null && !s.isBlank();
    }
}

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

import java.nio.file.Paths;
import java.util.stream.Collectors;
import tpDiscover.intelbras.util.Compat;
/**
 * Orquestra uma sessão completa de descoberta, coleta paralela somente-leitura,
 * correlação de topologia, detecção de loops e geração do relatório PDF.
 *
 * Compartilhada entre CLI ({@code App}) e GUI Swing ({@code TpDiscoverFrame}).
 */
public class DiscoverySessionService {

    public static final class DiscoveryRequest {
        private final List<String> scanSpecs;
        private final List<SwitchTarget> alvosManuais;
        private final String usuarioPadrao;
        private final String senhaPadrao;
        private final boolean apenasScan;
        private final boolean expandirViaLldp;
        private final boolean usarSnmp;
        private final int concorrencia;
        private final String gatewayIpManual;
        private final String gatewayMacManual;
        private final Path pastaSaidaPdf;
        private final Path salvarAlvosEm;
        private final List<CredencialAcesso> credenciaisAlternativas;
        private final List<DiscoveredProduct> produtosConhecidosPreviamente;
        private final List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsPreservados;
        private final List<TopologyEngine.EquipamentoSemAcesso> equipamentosSemAcessoInformados;
        private final boolean gerarArquivoRelatorio;

        public DiscoveryRequest(List<String> scanSpecs, List<SwitchTarget> alvosManuais, String usuarioPadrao, String senhaPadrao, boolean apenasScan, boolean expandirViaLldp, boolean usarSnmp, int concorrencia, String gatewayIpManual, String gatewayMacManual, Path pastaSaidaPdf, Path salvarAlvosEm, List<CredencialAcesso> credenciaisAlternativas, List<DiscoveredProduct> produtosConhecidosPreviamente, List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsPreservados, List<TopologyEngine.EquipamentoSemAcesso> equipamentosSemAcessoInformados, boolean gerarArquivoRelatorio) {
            scanSpecs = scanSpecs != null ? Compat.listCopyOf(scanSpecs) : Compat.listOf();
            alvosManuais = alvosManuais != null ? Compat.listCopyOf(alvosManuais) : Compat.listOf();
            concorrencia = Math.max(1, concorrencia);
            credenciaisAlternativas = credenciaisAlternativas != null
                    ? Compat.listCopyOf(credenciaisAlternativas)
                    : CredencialAcesso.presetsPadrao();
            produtosConhecidosPreviamente = produtosConhecidosPreviamente != null
                    ? Compat.listCopyOf(produtosConhecidosPreviamente) : Compat.listOf();
            snapshotsPreservados = snapshotsPreservados != null
                    ? Compat.listCopyOf(snapshotsPreservados) : Compat.listOf();
            equipamentosSemAcessoInformados = equipamentosSemAcessoInformados != null
                    ? Compat.listCopyOf(equipamentosSemAcessoInformados) : Compat.listOf();
            this.scanSpecs = scanSpecs;
            this.alvosManuais = alvosManuais;
            this.usuarioPadrao = usuarioPadrao;
            this.senhaPadrao = senhaPadrao;
            this.apenasScan = apenasScan;
            this.expandirViaLldp = expandirViaLldp;
            this.usarSnmp = usarSnmp;
            this.concorrencia = concorrencia;
            this.gatewayIpManual = gatewayIpManual;
            this.gatewayMacManual = gatewayMacManual;
            this.pastaSaidaPdf = pastaSaidaPdf;
            this.salvarAlvosEm = salvarAlvosEm;
            this.credenciaisAlternativas = credenciaisAlternativas;
            this.produtosConhecidosPreviamente = produtosConhecidosPreviamente;
            this.snapshotsPreservados = snapshotsPreservados;
            this.equipamentosSemAcessoInformados = equipamentosSemAcessoInformados;
            this.gerarArquivoRelatorio = gerarArquivoRelatorio;
        }

        public List<String> scanSpecs() {
            return scanSpecs;
        }

        public List<SwitchTarget> alvosManuais() {
            return alvosManuais;
        }

        public String usuarioPadrao() {
            return usuarioPadrao;
        }

        public String senhaPadrao() {
            return senhaPadrao;
        }

        public boolean apenasScan() {
            return apenasScan;
        }

        public boolean expandirViaLldp() {
            return expandirViaLldp;
        }

        public boolean usarSnmp() {
            return usarSnmp;
        }

        public int concorrencia() {
            return concorrencia;
        }

        public String gatewayIpManual() {
            return gatewayIpManual;
        }

        public String gatewayMacManual() {
            return gatewayMacManual;
        }

        public Path pastaSaidaPdf() {
            return pastaSaidaPdf;
        }

        public Path salvarAlvosEm() {
            return salvarAlvosEm;
        }

        public List<CredencialAcesso> credenciaisAlternativas() {
            return credenciaisAlternativas;
        }

        public List<DiscoveredProduct> produtosConhecidosPreviamente() {
            return produtosConhecidosPreviamente;
        }

        public List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsPreservados() {
            return snapshotsPreservados;
        }

        public List<TopologyEngine.EquipamentoSemAcesso> equipamentosSemAcessoInformados() {
            return equipamentosSemAcessoInformados;
        }

        public boolean gerarArquivoRelatorio() {
            return gerarArquivoRelatorio;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof DiscoveryRequest)) return false;
            DiscoveryRequest o = (DiscoveryRequest) obj;
            return java.util.Objects.equals(scanSpecs, o.scanSpecs)
                    && java.util.Objects.equals(alvosManuais, o.alvosManuais)
                    && java.util.Objects.equals(usuarioPadrao, o.usuarioPadrao)
                    && java.util.Objects.equals(senhaPadrao, o.senhaPadrao)
                    && apenasScan == o.apenasScan
                    && expandirViaLldp == o.expandirViaLldp
                    && usarSnmp == o.usarSnmp
                    && concorrencia == o.concorrencia
                    && java.util.Objects.equals(gatewayIpManual, o.gatewayIpManual)
                    && java.util.Objects.equals(gatewayMacManual, o.gatewayMacManual)
                    && java.util.Objects.equals(pastaSaidaPdf, o.pastaSaidaPdf)
                    && java.util.Objects.equals(salvarAlvosEm, o.salvarAlvosEm)
                    && java.util.Objects.equals(credenciaisAlternativas, o.credenciaisAlternativas)
                    && java.util.Objects.equals(produtosConhecidosPreviamente, o.produtosConhecidosPreviamente)
                    && java.util.Objects.equals(snapshotsPreservados, o.snapshotsPreservados)
                    && java.util.Objects.equals(equipamentosSemAcessoInformados, o.equipamentosSemAcessoInformados)
                    && gerarArquivoRelatorio == o.gerarArquivoRelatorio;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(scanSpecs, alvosManuais, usuarioPadrao, senhaPadrao, apenasScan, expandirViaLldp, usarSnmp, concorrencia, gatewayIpManual, gatewayMacManual, pastaSaidaPdf, salvarAlvosEm, credenciaisAlternativas, produtosConhecidosPreviamente, snapshotsPreservados, equipamentosSemAcessoInformados, gerarArquivoRelatorio);
        }

        @Override
        public String toString() {
            return "DiscoveryRequest[scanSpecs=" + scanSpecs + ", alvosManuais=" + alvosManuais + ", usuarioPadrao=" + usuarioPadrao + ", senhaPadrao=" + senhaPadrao + ", apenasScan=" + apenasScan + ", expandirViaLldp=" + expandirViaLldp + ", usarSnmp=" + usarSnmp + ", concorrencia=" + concorrencia + ", gatewayIpManual=" + gatewayIpManual + ", gatewayMacManual=" + gatewayMacManual + ", pastaSaidaPdf=" + pastaSaidaPdf + ", salvarAlvosEm=" + salvarAlvosEm + ", credenciaisAlternativas=" + credenciaisAlternativas + ", produtosConhecidosPreviamente=" + produtosConhecidosPreviamente + ", snapshotsPreservados=" + snapshotsPreservados + ", equipamentosSemAcessoInformados=" + equipamentosSemAcessoInformados + ", gerarArquivoRelatorio=" + gerarArquivoRelatorio + "]";
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
                    CredencialAcesso.presetsPadrao(), Compat.listOf(), Compat.listOf(), Compat.listOf());
        }
    }

    public static final class DiscoverySessionResult {
        private final boolean cancelado;
        private final String erroFatal;
        private final NetworkScanner.ScanResult scanResult;
        private final List<SwitchTarget> alvosConsolidados;
        private final ColetorEmLote.ResultadoColeta resultadoColeta;
        private final RelatorioTopologia.ResultadoRelatorio relatorio;
        private final long duracaoScanMs;
        private final long duracaoColetaMs;
        private final List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsConsolidados;
        private final List<DiscoveredProduct> produtosConsolidados;

        public DiscoverySessionResult(boolean cancelado, String erroFatal, NetworkScanner.ScanResult scanResult, List<SwitchTarget> alvosConsolidados, ColetorEmLote.ResultadoColeta resultadoColeta, RelatorioTopologia.ResultadoRelatorio relatorio, long duracaoScanMs, long duracaoColetaMs, List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsConsolidados, List<DiscoveredProduct> produtosConsolidados) {
            this.cancelado = cancelado;
            this.erroFatal = erroFatal;
            this.scanResult = scanResult;
            this.alvosConsolidados = alvosConsolidados;
            this.resultadoColeta = resultadoColeta;
            this.relatorio = relatorio;
            this.duracaoScanMs = duracaoScanMs;
            this.duracaoColetaMs = duracaoColetaMs;
            this.snapshotsConsolidados = snapshotsConsolidados;
            this.produtosConsolidados = produtosConsolidados;
        }

        public boolean cancelado() {
            return cancelado;
        }

        public String erroFatal() {
            return erroFatal;
        }

        public NetworkScanner.ScanResult scanResult() {
            return scanResult;
        }

        public List<SwitchTarget> alvosConsolidados() {
            return alvosConsolidados;
        }

        public ColetorEmLote.ResultadoColeta resultadoColeta() {
            return resultadoColeta;
        }

        public RelatorioTopologia.ResultadoRelatorio relatorio() {
            return relatorio;
        }

        public long duracaoScanMs() {
            return duracaoScanMs;
        }

        public long duracaoColetaMs() {
            return duracaoColetaMs;
        }

        public List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsConsolidados() {
            return snapshotsConsolidados;
        }

        public List<DiscoveredProduct> produtosConsolidados() {
            return produtosConsolidados;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof DiscoverySessionResult)) return false;
            DiscoverySessionResult o = (DiscoverySessionResult) obj;
            return cancelado == o.cancelado
                    && java.util.Objects.equals(erroFatal, o.erroFatal)
                    && java.util.Objects.equals(scanResult, o.scanResult)
                    && java.util.Objects.equals(alvosConsolidados, o.alvosConsolidados)
                    && java.util.Objects.equals(resultadoColeta, o.resultadoColeta)
                    && java.util.Objects.equals(relatorio, o.relatorio)
                    && duracaoScanMs == o.duracaoScanMs
                    && duracaoColetaMs == o.duracaoColetaMs
                    && java.util.Objects.equals(snapshotsConsolidados, o.snapshotsConsolidados)
                    && java.util.Objects.equals(produtosConsolidados, o.produtosConsolidados);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(cancelado, erroFatal, scanResult, alvosConsolidados, resultadoColeta, relatorio, duracaoScanMs, duracaoColetaMs, snapshotsConsolidados, produtosConsolidados);
        }

        @Override
        public String toString() {
            return "DiscoverySessionResult[cancelado=" + cancelado + ", erroFatal=" + erroFatal + ", scanResult=" + scanResult + ", alvosConsolidados=" + alvosConsolidados + ", resultadoColeta=" + resultadoColeta + ", relatorio=" + relatorio + ", duracaoScanMs=" + duracaoScanMs + ", duracaoColetaMs=" + duracaoColetaMs + ", snapshotsConsolidados=" + snapshotsConsolidados + ", produtosConsolidados=" + produtosConsolidados + "]";
        }

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
                    duracaoScanMs, duracaoColetaMs, Compat.listOf(), Compat.listOf());
        }

        public boolean possuiErroFatal() {
            return erroFatal != null && !Compat.isBlank(erroFatal);
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

        List<DiscoveredProduct> produtosDescobertos = Compat.listOf();
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
                    500, 900, req.usarSnmp(), Compat.listOf("public")
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
                return new DiscoverySessionResult(true, null, scanRes, Compat.listOf(), null, null, duracaoScanMs, 0L);
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
        List<DiscoveredProduct> produtosConsolidados = Compat.listCopyOf(produtoPorIp.values());

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
            if (!Compat.isBlank(p.protocoloPreferido())) {
                protocolosPreferidosPorIp.put(ipBase, p.protocoloPreferido());
            }
            if (!Compat.isBlank(p.macConhecido())) {
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
            if (!Compat.isBlank(ipSnap) && !ipsTentados.contains(ipSnap)) {
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
            coletaRes = new ColetorEmLote.ResultadoColeta(Compat.listOf(), Compat.listOf(), Compat.listOf());
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
                .collect(Collectors.toList());

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
                : Paths.get("").toAbsolutePath().normalize();
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
                if (!Compat.isBlank(ipBase) && ipsTentadosNestaEtapa != null && ipsTentadosNestaEtapa.contains(ipBase)) {
                    continue;
                }
                String chave = Compat.isBlank(ipBase) ? s.identidade().macNormalizado() : ipBase;
                porChave.put(chave, s);
            }
        }
        if (novos != null) {
            for (ColetorEmLote.ColetaDispositivoSnapshot s : novos) {
                if (s == null || s.identidade() == null) continue;
                String ipBase = extrairIpBase(s.identidade().ip());
                String chave = Compat.isBlank(ipBase) ? s.identidade().macNormalizado() : ipBase;
                porChave.put(chave, s);
            }
        }
        return Compat.listCopyOf(porChave.values());
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
        if (p.modeloInferido() != null && !Compat.isBlank(p.modeloInferido())) {
            return p.modeloInferido();
        }
        return p.familia() != null ? p.familia().rotulo() : fallback;
    }

    private static String categoriaDoProduto(DiscoveredProduct p) {
        if (p == null) {
            return "SWITCH";
        }
        switch (p.familia()) {
            case OLT:
                return "OLT";
            case ACCESS_POINT:
                return (p.modeloInferido().toLowerCase(Locale.ROOT).contains("wom")
                    || p.modeloInferido().toLowerCase(Locale.ROOT).contains("apc"))
                    ? "RADIO" : "AP";
            case ROTEADOR_GATEWAY:
                return "ROUTER";
            default:
                return "SWITCH";
        }
    }

    private static String mapearMotivoFalha(SwitchConnectionException.Tipo tipo) {
        if (tipo == null) {
            return "ERRO_PROTOCOLO";
        }
        switch (tipo) {
            case FALHA_AUTENTICACAO:
                return "FALHA_AUTENTICACAO";
            case TIMEOUT:
                return "TIMEOUT";
            case INALCANCAVEL:
                return "CONEXAO_RECUSADA";
            case ERRO_PROTOCOLO:
                return "ERRO_PROTOCOLO";
            default:
                throw new IllegalStateException("Valor inesperado: " + (tipo));
        }
    }

    private static String extrairIpBase(String ip) {
        if (ip == null) {
            return "";
        }
        int idx = ip.indexOf(':');
        return (idx > 0 ? ip.substring(0, idx) : ip).trim();
    }

    private static DiscoverySessionResult erroFatal(String msg, NetworkScanner.ScanResult scanRes, long duracaoScanMs) {
        return new DiscoverySessionResult(false, msg, scanRes, Compat.listOf(), null, null, duracaoScanMs, 0L);
    }

    private static boolean possuiTexto(String s) {
        return s != null && !Compat.isBlank(s);
    }
}

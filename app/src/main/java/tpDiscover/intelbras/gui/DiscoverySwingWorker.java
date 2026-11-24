package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.coleta.DiscoverySessionService;
import tpDiscover.intelbras.coleta.DiscoverySessionService.DiscoveryRequest;
import tpDiscover.intelbras.coleta.DiscoverySessionService.DiscoverySessionListener;
import tpDiscover.intelbras.coleta.DiscoverySessionService.DiscoverySessionResult;
import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.descoberta.DiscoveredProduct;
import tpDiscover.intelbras.descoberta.NetworkScanner;
import tpDiscover.intelbras.gui.DiscoveredTargetRow.EstadoColeta;
import tpDiscover.intelbras.modelos.LoopDiagnostic;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Executa scan e/ou coleta em background fora da EDT, despachando progresso ao vivo para as abas da interface.
 */
public class DiscoverySwingWorker extends SwingWorker<DiscoverySessionResult, Void> {

    private final DiscoveryRequest request;
    private final TargetTableModel tableModel;
    private final ColetaViewPanel coletaPanel;
    private final TopologiaViewPanel topologiaPanel;
    private final AtomicBoolean cancelar;
    private final Consumer<String> statusBadgeCallback;
    private final Runnable onFinishCallback;
    private final Consumer<DiscoverySessionResult> onSessionCompletedCallback;

    public DiscoverySwingWorker(
            DiscoveryRequest request,
            TargetTableModel tableModel,
            ColetaViewPanel coletaPanel,
            TopologiaViewPanel topologiaPanel,
            AtomicBoolean cancelar,
            Consumer<String> statusBadgeCallback,
            Runnable onFinishCallback
    ) {
        this(request, tableModel, coletaPanel, topologiaPanel, cancelar, statusBadgeCallback, onFinishCallback, null);
    }

    public DiscoverySwingWorker(
            DiscoveryRequest request,
            TargetTableModel tableModel,
            ColetaViewPanel coletaPanel,
            TopologiaViewPanel topologiaPanel,
            AtomicBoolean cancelar,
            Consumer<String> statusBadgeCallback,
            Runnable onFinishCallback,
            Consumer<DiscoverySessionResult> onSessionCompletedCallback
    ) {
        this.request = request;
        this.tableModel = tableModel;
        this.coletaPanel = coletaPanel;
        this.topologiaPanel = topologiaPanel;
        this.cancelar = cancelar;
        this.statusBadgeCallback = statusBadgeCallback;
        this.onFinishCallback = onFinishCallback;
        this.onSessionCompletedCallback = onSessionCompletedCallback;
    }

    @Override
    protected DiscoverySessionResult doInBackground() {
        DiscoverySessionService service = new DiscoverySessionService();
        AtomicInteger encontradosScan = new AtomicInteger(0);

        DiscoverySessionListener listener = new DiscoverySessionListener() {
            @Override
            public void onLog(String mensagem) {
                SwingUtilities.invokeLater(() -> coletaPanel.appendLog(mensagem));
            }

            @Override
            public void onScanIniciado(int totalIps, List<String> specs) {
                encontradosScan.set(0);
                SwingUtilities.invokeLater(() -> {
                    coletaPanel.prepararNovaExecucao("Procurando equipamentos em " + String.join(", ", specs), false);
                    notificarBadge("Escaneando " + totalIps + " IPs...");
                });
            }

            @Override
            public void onScanProgresso(int processados, int totalIps, DiscoveredProduct encontradoOuNull) {
                if (encontradoOuNull != null) {
                    encontradosScan.incrementAndGet();
                }
                if (processados == totalIps || processados % 4 == 0 || encontradoOuNull != null) {
                    int achados = encontradosScan.get();
                    SwingUtilities.invokeLater(() -> coletaPanel.atualizarProgresso(
                            processados,
                            totalIps,
                            "Procurando equipamentos  ·  " + achados + (achados == 1 ? " encontrado" : " encontrados")
                    ));
                }
            }

            @Override
            public void onScanConcluido(NetworkScanner.ScanResult resultado) {
                SwingUtilities.invokeLater(() -> {
                    tableModel.mesclarProdutosDescobertos(resultado.produtosInteresse());
                    if (request.apenasScan()) {
                        String msg = String.format(Locale.US,
                                "Scan concluído em %.2fs: %d produto(s) Intelbras encontrado(s), %d host(s) ignorado(s).",
                                resultado.duracaoMillis() / 1000.0,
                                resultado.produtosInteresse().size(),
                                resultado.hostsIgnorados().size());
                        coletaPanel.definirStatusFinal(msg, 100, "Scan concluído");
                        notificarBadge(resultado.produtosInteresse().size() + " encontrado(s)");
                    }
                });
            }

            @Override
            public void onColetaIniciada(List<SwitchTarget> alvosConsolidados) {
                SwingUtilities.invokeLater(() -> {
                    tableModel.garantirAlvosNaTabela(alvosConsolidados, "Alvo consolidado");
                    tableModel.marcarAguardandoParaSelecionados();
                    coletaPanel.prepararNovaExecucao(
                            "Coletando equipamentos",
                            false
                    );
                    notificarBadge("Coletando 0/" + alvosConsolidados.size());
                });
            }

            @Override
            public void onColetaAlvoIniciado(SwitchTarget alvo) {
                SwingUtilities.invokeLater(() -> tableModel.atualizarStatusPorEndereco(
                        alvo.enderecoExibicao(), EstadoColeta.COLETANDO, "Conectando/Coletando..."
                ));
            }

            @Override
            public void onColetaAlvoConcluido(SwitchTarget alvo, boolean sucesso, String resumoOuErro, int concluidos, int total) {
                SwingUtilities.invokeLater(() -> {
                    tableModel.atualizarStatusPorEndereco(
                            alvo.enderecoExibicao(),
                            sucesso ? EstadoColeta.OK : EstadoColeta.FALHA,
                            sucesso ? ("OK · " + resumoOuErro) : ("Falha: " + TargetTableModel.motivoCurto(resumoOuErro)),
                            sucesso ? null : resumoOuErro
                    );
                    coletaPanel.atualizarProgresso(concluidos, total, "Coletando equipamentos");
                    notificarBadge("Coletando " + concluidos + "/" + total);
                });
            }

            @Override
            public void onExpansaoLldp(List<SwitchTarget> novosAlvos) {
                SwingUtilities.invokeLater(() -> tableModel.garantirAlvosNaTabela(novosAlvos, "Descoberto via LLDP"));
            }
        };

        return service.executar(request, listener, cancelar);
    }

    @Override
    protected void done() {
        try {
            DiscoverySessionResult res = get();
            if (onSessionCompletedCallback != null) {
                onSessionCompletedCallback.accept(res);
            }
            if (res.cancelado()) {
                coletaPanel.definirStatusFinal("Operação cancelada pelo usuário.", 0, "Cancelado");
                coletaPanel.appendLog("Operação cancelada pelo usuário.");
                notificarBadge("Cancelado");
            } else if (res.possuiErroFatal()) {
                coletaPanel.definirStatusFinal(res.erroFatal(), 0, "Erro");
                coletaPanel.appendLog("[ERRO] " + res.erroFatal());
                notificarBadge("Erro");
            } else if (!request.apenasScan() && res.resultadoColeta() != null) {
                int okConsolidado = !res.snapshotsConsolidados().isEmpty()
                        ? res.snapshotsConsolidados().size()
                        : res.resultadoColeta().sucesso().size();
                int okRodada = res.resultadoColeta().sucesso().size();
                int falhas = res.resultadoColeta().falhas().size();
                int totalRodada = res.resultadoColeta().total();
                String msg = totalRodada > 0
                        ? "Coleta concluída em " + duracaoLegivel(res.duracaoColetaMs()) + ": "
                                + okConsolidado + (okConsolidado == 1 ? " equipamento coletado" : " equipamentos coletados")
                                + (falhas > 0 ? ", " + falhas + " sem acesso" : "") + "."
                        : "Topologia atualizada: " + okConsolidado + " equipamento(s) coletado(s)"
                                + (request.equipamentosSemAcessoInformados().isEmpty() ? "."
                                : " e " + request.equipamentosSemAcessoInformados().size() + " sem acesso.");
                tableModel.ordenarFalhasPrimeiro();
                coletaPanel.definirStatusFinal(msg, 100, okConsolidado + " OK" + (falhas > 0 ? " • " + falhas + " falha" : ""));
                notificarBadge(okConsolidado + " OK" + (falhas > 0 ? " • " + falhas + " falha(s)" : ""));

                if (res.relatorio() != null) {
                    topologiaPanel.exibirRelatorio(res.relatorio());
                    long criticos = res.relatorio().loops().stream()
                            .filter(l -> l.severidade() == LoopDiagnostic.Severidade.CRITICO)
                            .count();
                    boolean parcial = res.relatorio().topologiaParcial() || falhas > 0;
                    String bannerMsg;
                    java.nio.file.Path arquivo = res.relatorio().arquivoGerado();
                    if (request.gerarArquivoRelatorio()) {
                        bannerMsg = arquivo != null
                                ? "PDF gerado: " + arquivo.getFileName()
                                        + (criticos > 0 ? " — atenção: " + criticos + " loop(s) crítico(s)!" : "")
                                : "Não foi possível gerar o arquivo do relatório. Veja o log abaixo.";
                        coletaPanel.definirStatusFinal(bannerMsg, 100, arquivo != null ? "PDF gerado" : "Erro no PDF");
                        notificarBadge(arquivo != null ? "PDF gerado" : "Erro no PDF");
                    } else if (criticos > 0) {
                        bannerMsg = "Atenção: " + criticos + " loop(s) crítico(s) detectado(s)! " + msg;
                    } else if (parcial) {
                        int qtdPend = Math.max(falhas, res.relatorio().pendenciasAcesso().size());
                        bannerMsg = "Topologia parcial: " + qtdPend + " equipamento(s) sem acesso. Corrija as senhas e recolete, ou gere o PDF assim mesmo.";
                    } else {
                        bannerMsg = "Topologia atualizada: " + res.relatorio().enlaces().size()
                                + " enlace(s). Terminou os testes? Clique em 'Finalizar e gerar PDF'.";
                    }
                    if (!request.gerarArquivoRelatorio()) {
                        coletaPanel.appendLog("Topologia atualizada na aba Topologia. O PDF só é gravado em "
                                + "'Finalizar e gerar PDF' (assim você pode corrigir senhas e recoletar antes).");
                    }
                    boolean finalPdf = request.gerarArquivoRelatorio();
                    coletaPanel.exibirResumoConclusao(
                            bannerMsg,
                            arquivo,
                            criticos > 0,
                            !finalPdf && parcial,
                            !finalPdf && (parcial || falhas > 0));
                } else if (okConsolidado == 0) {
                    coletaPanel.appendLog("Nenhum equipamento coletado com sucesso — confira usuários/senhas na aba Equipamentos.");
                    if (falhas > 0) {
                        coletaPanel.exibirResumoConclusao(
                                "Nenhum equipamento autenticado (" + falhas + " falha(s)). Revise usuários/senhas em Equipamentos.",
                                null, false, true, true);
                    }
                }
            }
        } catch (Exception e) {
            Throwable causa = (e instanceof java.util.concurrent.ExecutionException && e.getCause() != null)
                    ? e.getCause() : e;
            String origem = (causa.getStackTrace() != null && causa.getStackTrace().length > 0)
                    ? " em " + causa.getStackTrace()[0].getClassName() + ":" + causa.getStackTrace()[0].getLineNumber()
                    : "";
            coletaPanel.definirStatusFinal("Falha inesperada: " + causa.getMessage(), 0, "Erro");
            coletaPanel.appendLog("[ERRO] " + causa + origem);
            notificarBadge("Erro");
        } finally {
            if (onFinishCallback != null) {
                onFinishCallback.run();
            }
        }
    }

    private void notificarBadge(String texto) {
        if (statusBadgeCallback != null) {
            statusBadgeCallback.accept(texto);
        }
    }

    /** "34 s", "1 min 05 s": tempo de execução sem casas decimais nem ponto à moda americana. */
    static String duracaoLegivel(long millis) {
        long seg = Math.max(0, Math.round(millis / 1000.0));
        if (seg < 60) return seg + " s";
        return (seg / 60) + " min " + String.format("%02d", seg % 60) + " s";
    }
}

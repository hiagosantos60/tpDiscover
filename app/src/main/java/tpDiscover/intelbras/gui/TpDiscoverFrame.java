package tpDiscover.intelbras.gui;

import com.formdev.flatlaf.FlatClientProperties;
import tpDiscover.intelbras.coleta.AlvosLoader;
import tpDiscover.intelbras.coleta.ColetorEmLote;
import tpDiscover.intelbras.coleta.DiscoverySessionService.DiscoveryRequest;
import tpDiscover.intelbras.coleta.DiscoverySessionService.DiscoverySessionResult;
import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.descoberta.DiscoveredProduct;
import tpDiscover.intelbras.descoberta.NetworkScanTarget;
import tpDiscover.intelbras.gui.ColetaViewPanel.ModoOrigem;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Janela principal do tpDiscover em tema escuro minimalista (FlatLaf Dark),
 * com barra superior de navegação separando Coleta, Equipamentos, Topologia & Loops e Avançado.
 */
public class TpDiscoverFrame extends JFrame {

    private static final String CARD_COLETA = "coleta";
    private static final String CARD_EQUIPAMENTOS = "equipamentos";
    private static final String CARD_TOPOLOGIA = "topologia";
    private static final String CARD_AVANCADO = "avancado";

    private final TargetTableModel tableModel = new TargetTableModel();

    private final JToggleButton navColeta = new JToggleButton("Coleta", true);
    private final JToggleButton navEquipamentos = new JToggleButton("Equipamentos (0)");
    private final JToggleButton navTopologia = new JToggleButton("Topologia & Loops");
    private final JToggleButton navAvancado = new JToggleButton("Avançado");
    private final JLabel lblStatusBadge = new JLabel("Pronto");

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel containerCards = new JPanel(cardLayout);

    private final ColetaViewPanel coletaPanel;
    private final EquipamentosViewPanel equipamentosPanel;
    private final TopologiaViewPanel topologiaPanel;
    private final AvancadoViewPanel avancadoPanel;

    private final Map<String, ColetorEmLote.ColetaDispositivoSnapshot> sessaoSnapshotsPorIp = new LinkedHashMap<>();
    private final Map<String, DiscoveredProduct> sessaoProdutosPorIp = new LinkedHashMap<>();

    private DiscoverySwingWorker workerAtual;
    private AtomicBoolean cancelamentoAtual;

    public TpDiscoverFrame() {
        super("tpDiscover Intelbras");
        Path pastaInicial = Path.of("").toAbsolutePath().normalize();

        this.topologiaPanel = new TopologiaViewPanel(pastaInicial, this::irParaEquipamentosCorrigirFalhos);
        this.coletaPanel = new ColetaViewPanel(
                pastaInicial,
                () -> selecionarAba(CARD_TOPOLOGIA),
                this::irParaEquipamentosCorrigirFalhos);
        this.equipamentosPanel = new EquipamentosViewPanel(tableModel);
        this.avancadoPanel = new AvancadoViewPanel();

        inicializarLayout();
        registrarEventos();
        atualizarEstadoExecucao(false);
    }

    public static void exibir() {
        SwingThemeHelper.aplicarTemaMultiplataforma();
        TpDiscoverFrame frame = new TpDiscoverFrame();
        frame.setVisible(true);
    }

    private void inicializarLayout() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(880, 620));
        setSize(1020, 720);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(SwingThemeHelper.BG_WINDOW);

        root.add(criarBarraSuperior(), BorderLayout.NORTH);

        containerCards.setOpaque(false);
        containerCards.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        containerCards.add(coletaPanel, CARD_COLETA);
        containerCards.add(equipamentosPanel, CARD_EQUIPAMENTOS);
        containerCards.add(topologiaPanel, CARD_TOPOLOGIA);
        containerCards.add(avancadoPanel, CARD_AVANCADO);
        root.add(containerCards, BorderLayout.CENTER);

        setContentPane(root);
    }

    private JPanel criarBarraSuperior() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(SwingThemeHelper.BG_HEADER);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, SwingThemeHelper.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(8, 16, 8, 16)
        ));

        JPanel esquerda = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        esquerda.setOpaque(false);

        JLabel brand = new JLabel("tpDiscover");
        brand.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.NETWORK, SwingThemeHelper.ACCENT_PRIMARY));
        brand.setIconTextGap(8);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 14.5f));
        brand.setForeground(SwingThemeHelper.TEXT_PRIMARY);
        brand.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        esquerda.add(brand);

        navColeta.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.PLAY, SwingThemeHelper.TEXT_PRIMARY));
        navEquipamentos.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.LIST, SwingThemeHelper.TEXT_PRIMARY));
        navTopologia.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.NETWORK, SwingThemeHelper.TEXT_PRIMARY));
        navAvancado.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.SLIDERS, SwingThemeHelper.TEXT_PRIMARY));

        ButtonGroup group = new ButtonGroup();
        for (JToggleButton btn : new JToggleButton[]{navColeta, navEquipamentos, navTopologia, navAvancado}) {
            SwingThemeHelper.estilizarNavTab(btn);
            group.add(btn);
            esquerda.add(btn);
        }
        bar.add(esquerda, BorderLayout.CENTER);

        lblStatusBadge.putClientProperty(FlatClientProperties.STYLE,
                "arc: 999; background: #2D3139; border: 1,1,1,1,#3F4552,1,999");
        lblStatusBadge.setOpaque(true);
        lblStatusBadge.setForeground(SwingThemeHelper.TEXT_MUTED);
        lblStatusBadge.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        JPanel direita = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
        direita.setOpaque(false);
        direita.add(lblStatusBadge);
        bar.add(direita, BorderLayout.EAST);

        return bar;
    }

    private void registrarEventos() {
        navColeta.addActionListener(e -> cardLayout.show(containerCards, CARD_COLETA));
        navEquipamentos.addActionListener(e -> cardLayout.show(containerCards, CARD_EQUIPAMENTOS));
        navTopologia.addActionListener(e -> cardLayout.show(containerCards, CARD_TOPOLOGIA));
        navAvancado.addActionListener(e -> cardLayout.show(containerCards, CARD_AVANCADO));

        coletaPanel.getBtnExecutarTudo().addActionListener(e -> iniciarExecucao(false, false));
        coletaPanel.getBtnApenasEscanear().addActionListener(e -> iniciarExecucao(true, false));
        coletaPanel.getBtnCancelar().addActionListener(e -> solicitarCancelamento());

        equipamentosPanel.getBtnColetarSelecionados().addActionListener(e -> {
            selecionarAba(CARD_COLETA);
            iniciarExecucao(false, true);
        });
        equipamentosPanel.getBtnImportarTxt().addActionListener(e -> importarArquivoAlvos());
        equipamentosPanel.getBtnExportarTxt().addActionListener(e -> exportarArquivoAlvos());

        tableModel.addTableModelListener(e -> {
            navEquipamentos.setText("Equipamentos (" + tableModel.getRowCount() + ")");
            if (!emExecucao()) {
                equipamentosPanel.setEstadoExecucao(false);
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (emExecucao()) {
                    int op = JOptionPane.showConfirmDialog(
                            TpDiscoverFrame.this,
                            "Há uma coleta em andamento. Deseja cancelar e fechar o programa?",
                            "Confirmar saída",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE
                    );
                    if (op != JOptionPane.YES_OPTION) {
                        return;
                    }
                    solicitarCancelamento();
                }
                dispose();
            }
        });
    }

    private void irParaEquipamentosCorrigirFalhos() {
        if (tableModel.contarPorEstado(DiscoveredTargetRow.EstadoColeta.FALHA) > 0) {
            tableModel.selecionarApenasFalhos();
        }
        selecionarAba(CARD_EQUIPAMENTOS);
    }

    private void selecionarAba(String nomeCard) {
        cardLayout.show(containerCards, nomeCard);
        switch (nomeCard) {
            case CARD_COLETA -> navColeta.setSelected(true);
            case CARD_EQUIPAMENTOS -> navEquipamentos.setSelected(true);
            case CARD_TOPOLOGIA -> navTopologia.setSelected(true);
            case CARD_AVANCADO -> navAvancado.setSelected(true);
            default -> {
            }
        }
    }

    private void iniciarExecucao(boolean apenasScan, boolean somenteTabela) {
        if (emExecucao()) return;
        equipamentosPanel.finalizarEdicaoTabela();

        if (!somenteTabela) {
            sessaoSnapshotsPorIp.clear();
            if (apenasScan) {
                sessaoProdutosPorIp.clear();
            }
            equipamentosPanel.setModoIncremental(false);
        }

        String usuarioPadrao = coletaPanel.getUsuarioPadrao();
        String senhaPadrao = coletaPanel.getSenhaPadrao();
        ModoOrigem modo = coletaPanel.getModoOrigem();

        List<String> scanSpecs = new ArrayList<>();
        if (!somenteTabela) {
            if (modo == ModoOrigem.SCAN_AUTOMATICO) {
                scanSpecs.add("auto");
            } else if (modo == ModoOrigem.FAIXA_CIDR) {
                String spec = coletaPanel.getScanCidr();
                if (spec.isEmpty()) {
                    exibirAviso("Informe uma faixa ou CIDR para varredura (ex.: 10.1.40.0/24).");
                    selecionarAba(CARD_COLETA);
                    coletaPanel.getTxtScanCidr().requestFocusInWindow();
                    return;
                }
                try {
                    NetworkScanTarget.interpretarEspecificacoes(spec);
                } catch (IllegalArgumentException ex) {
                    exibirAviso(ex.getMessage());
                    selecionarAba(CARD_COLETA);
                    coletaPanel.getTxtScanCidr().requestFocusInWindow();
                    return;
                }
                scanSpecs.add(spec);
            }
        }

        List<SwitchTarget> alvosManuais;
        try {
            if (somenteTabela || modo == ModoOrigem.SOMENTE_LISTA) {
                alvosManuais = tableModel.extrairAlvosParaColeta(usuarioPadrao, senhaPadrao, !apenasScan);
                boolean podeRegerarComPreservados = somenteTabela
                        && !sessaoSnapshotsPorIp.isEmpty()
                        && tableModel.contarPorEstado(DiscoveredTargetRow.EstadoColeta.SEM_SENHA) > 0;
                if (alvosManuais.isEmpty() && !podeRegerarComPreservados) {
                    exibirAviso("Adicione ou marque ao menos um equipamento na aba 'Equipamentos'.");
                    selecionarAba(CARD_EQUIPAMENTOS);
                    return;
                }
            } else {
                if (!apenasScan && senhaPadrao.isEmpty()) {
                    exibirAviso("Informe a senha padrão para autenticar nos equipamentos descobertos.");
                    selecionarAba(CARD_COLETA);
                    coletaPanel.getTxtSenha().requestFocusInWindow();
                    return;
                }
                alvosManuais = tableModel.extrairOverridesManuais(usuarioPadrao, senhaPadrao);
            }
        } catch (IllegalArgumentException ex) {
            exibirAviso(ex.getMessage());
            return;
        }

        if (!apenasScan && usuarioPadrao.isBlank() && alvosManuais.isEmpty() && sessaoSnapshotsPorIp.isEmpty()) {
            exibirAviso("Informe o usuário padrão (ex.: admin).");
            coletaPanel.getTxtUsuario().requestFocusInWindow();
            return;
        }

        Path pastaPdf = coletaPanel.getPastaSaida();
        topologiaPanel.setPastaSaidaAtual(pastaPdf);
        if (!apenasScan) {
            topologiaPanel.limparParaNovaExecucao();
        }

        List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsPreservados = somenteTabela
                ? List.copyOf(sessaoSnapshotsPorIp.values())
                : List.of();
        List<DiscoveredProduct> produtosConhecidos = List.copyOf(sessaoProdutosPorIp.values());
        List<TopologyEngine.EquipamentoSemAcesso> semAcessoInformados = tableModel.extrairEquipamentosSemAcesso();

        DiscoveryRequest req = new DiscoveryRequest(
                scanSpecs,
                alvosManuais,
                usuarioPadrao.isBlank() ? "admin" : usuarioPadrao,
                senhaPadrao,
                apenasScan,
                avancadoPanel.isLldpAtivo(),
                avancadoPanel.isSnmpAtivo(),
                avancadoPanel.getConcorrencia(),
                avancadoPanel.getGatewayIp(),
                avancadoPanel.getGatewayMac(),
                pastaPdf,
                null,
                coletaPanel.getCredenciaisAlternativas(),
                produtosConhecidos,
                snapshotsPreservados,
                semAcessoInformados
        );

        cancelamentoAtual = new AtomicBoolean(false);
        atualizarEstadoExecucao(true);
        coletaPanel.prepararNovaExecucao(apenasScan ? "Preparando scan..." : "Iniciando coleta...", true);

        workerAtual = new DiscoverySwingWorker(
                req,
                tableModel,
                coletaPanel,
                topologiaPanel,
                cancelamentoAtual,
                lblStatusBadge::setText,
                () -> atualizarEstadoExecucao(false),
                res -> registrarEstadoSessao(res, apenasScan)
        );
        workerAtual.execute();
    }

    private void registrarEstadoSessao(DiscoverySessionResult res, boolean apenasScan) {
        if (res == null || res.cancelado()) {
            return;
        }
        if (res.scanResult() != null) {
            for (DiscoveredProduct p : res.scanResult().produtosInteresse()) {
                sessaoProdutosPorIp.put(p.ip(), p);
            }
        }
        for (DiscoveredProduct p : res.produtosConsolidados()) {
            sessaoProdutosPorIp.put(p.ip(), p);
        }
        if (!apenasScan && res.resultadoColeta() != null) {
            sessaoSnapshotsPorIp.clear();
            for (ColetorEmLote.ColetaDispositivoSnapshot s : res.snapshotsConsolidados()) {
                if (s.identidade() != null && s.identidade().ip() != null) {
                    String ip = s.identidade().ip();
                    int idx = ip.indexOf(':');
                    String base = idx > 0 ? ip.substring(0, idx).trim() : ip.trim();
                    sessaoSnapshotsPorIp.put(base, s);
                }
            }
            equipamentosPanel.setModoIncremental(!sessaoSnapshotsPorIp.isEmpty());
        }
    }

    private void solicitarCancelamento() {
        if (cancelamentoAtual != null) {
            cancelamentoAtual.set(true);
        }
        coletaPanel.getBtnCancelar().setEnabled(false);
        coletaPanel.appendLog("Cancelamento solicitado pelo usuário...");
    }

    private void atualizarEstadoExecucao(boolean executando) {
        coletaPanel.setEstadoExecucao(executando);
        equipamentosPanel.setEstadoExecucao(executando);
    }

    private boolean emExecucao() {
        return workerAtual != null && !workerAtual.isDone();
    }

    private void importarArquivoAlvos() {
        JFileChooser chooser = new JFileChooser(Path.of("").toAbsolutePath().toFile());
        chooser.setDialogTitle("Importar arquivo switches.txt");
        chooser.setFileFilter(new FileNameExtensionFilter("Arquivos texto (*.txt)", "txt"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path arq = chooser.getSelectedFile().toPath();
        try {
            AlvosLoader.ConfiguracaoAlvos cfg = AlvosLoader.carregarConfiguracao(arq);
            if (!cfg.alvos().isEmpty()) {
                tableModel.mesclarAlvosImportados(cfg.alvos(), "Arquivo " + arq.getFileName());
            }
            if (!cfg.scanSpecs().isEmpty()) {
                String spec = String.join(",", cfg.scanSpecs());
                if ("auto".equalsIgnoreCase(spec)) {
                    coletaPanel.selecionarModoOrigem(ModoOrigem.SCAN_AUTOMATICO, null);
                } else {
                    coletaPanel.selecionarModoOrigem(ModoOrigem.FAIXA_CIDR, spec);
                }
            } else if (!cfg.alvos().isEmpty()) {
                coletaPanel.selecionarModoOrigem(ModoOrigem.SOMENTE_LISTA, null);
            }
            if (cfg.usuarioPadrao() != null && !cfg.usuarioPadrao().isBlank()) {
                coletaPanel.setUsuarioPadrao(cfg.usuarioPadrao());
            }
            if (cfg.senhaPadrao() != null && !cfg.senhaPadrao().isBlank()) {
                coletaPanel.setSenhaPadrao(cfg.senhaPadrao());
            }
            avancadoPanel.setGatewayIp(cfg.gatewayIpManual());
            avancadoPanel.setGatewayMac(cfg.gatewayMacManual());
            coletaPanel.appendLog("Importado " + arq.getFileName() + ": " + cfg.alvos().size() + " alvo(s).");
        } catch (Exception ex) {
            exibirAviso("Não foi possível ler o arquivo: " + ex.getMessage());
        }
    }

    private void exportarArquivoAlvos() {
        if (tableModel.getRowCount() == 0) {
            exibirAviso("Não há equipamentos na tabela para exportar.");
            return;
        }
        Path pastaPdf = coletaPanel.getPastaSaida();
        JFileChooser chooser = new JFileChooser(pastaPdf.toFile());
        chooser.setDialogTitle("Salvar lista de alvos");
        chooser.setSelectedFile(pastaPdf.resolve("switches_exportados.txt").toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path destino = chooser.getSelectedFile().toPath();
        int opSenha = JOptionPane.showConfirmDialog(
                this,
                "Deseja salvar também as senhas no arquivo texto?\n(Escolha 'Não' para salvar apenas IP e usuário.)",
                "Incluir senhas?",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (opSenha == JOptionPane.CANCEL_OPTION || opSenha == JOptionPane.CLOSED_OPTION) {
            return;
        }
        boolean incluirSenhas = (opSenha == JOptionPane.YES_OPTION);
        try {
            List<SwitchTarget> lista = tableModel.extrairTodosAlvosParaExportacao(
                    coletaPanel.getUsuarioPadrao(),
                    incluirSenhas ? coletaPanel.getSenhaPadrao() : null
            );
            AlvosLoader.salvarAlvos(destino, lista, incluirSenhas);
            if (Files.exists(destino)) {
                coletaPanel.appendLog("Lista exportada em: " + destino.toAbsolutePath());
            }
        } catch (Exception ex) {
            exibirAviso("Falha ao exportar arquivo: " + ex.getMessage());
        }
    }

    private void exibirAviso(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Atenção", JOptionPane.WARNING_MESSAGE);
    }
}

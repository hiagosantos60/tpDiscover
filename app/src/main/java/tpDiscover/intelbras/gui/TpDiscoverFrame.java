package tpDiscover.intelbras.gui;

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
import java.awt.Color;
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

import java.nio.file.Paths;
import tpDiscover.intelbras.util.Compat;
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
    private final JToggleButton navTopologia = new JToggleButton("Topologia");
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

    /** Há coleta na sessão cujo PDF ainda não foi gerado. */
    private boolean pdfPendente;
    /** Fecha a janela assim que a geração do PDF final terminar (pedido feito ao sair). */
    private boolean fecharAoTerminar;

    public TpDiscoverFrame() {
        super("tpDiscover Intelbras");
        Path pastaInicial = Paths.get("").toAbsolutePath().normalize();

        this.topologiaPanel = new TopologiaViewPanel(pastaInicial, this::irParaEquipamentosCorrigirFalhos);
        this.coletaPanel = new ColetaViewPanel(
                pastaInicial,
                () -> selecionarAba(CARD_TOPOLOGIA),
                this::irParaEquipamentosCorrigirFalhos);
        this.equipamentosPanel = new EquipamentosViewPanel(tableModel);
        this.avancadoPanel = new AvancadoViewPanel();

        inicializarLayout();
        registrarEventos();
        registrarAtalhos();
        carregarPreferencias();
        atualizarEstadoExecucao(false);
    }

    // ------------------------------------------------------------ Preferências

    /** Preferências do usuário (registro do Windows). Senhas nunca são gravadas. */
    private static java.util.prefs.Preferences preferencias() {
        return java.util.prefs.Preferences.userRoot().node("intelbras/tpdiscover");
    }

    private void carregarPreferencias() {
        try {
            java.util.prefs.Preferences p = preferencias();
            coletaPanel.setUsuarioPadrao(p.get("usuario", "admin"));
            coletaPanel.setPastaSaida(p.get("pastaRelatorio", null));
            String modo = p.get("modoOrigem", null);
            if (modo != null) {
                try {
                    coletaPanel.selecionarModoOrigem(ColetaViewPanel.ModoOrigem.valueOf(modo), p.get("faixa", ""));
                } catch (IllegalArgumentException ignorado) {
                    // valor antigo/inválido: mantém o padrão
                }
            }
            avancadoPanel.setLldpAtivo(p.getBoolean("lldp", true));
            avancadoPanel.setSnmpAtivo(p.getBoolean("snmp", true));
            avancadoPanel.setConcorrencia(p.getInt("concorrencia", avancadoPanel.getConcorrencia()));
            avancadoPanel.setGatewayIp(p.get("gatewayIp", ""));
            avancadoPanel.setGatewayMac(p.get("gatewayMac", ""));
            int w = p.getInt("janelaLargura", 0);
            int h = p.getInt("janelaAltura", 0);
            if (w >= getMinimumSize().width && h >= getMinimumSize().height) {
                setSize(w, h);
                setLocationRelativeTo(null);
            }
            if (p.getBoolean("janelaMaximizada", false)) {
                setExtendedState(getExtendedState() | MAXIMIZED_BOTH);
            }
        } catch (RuntimeException e) {
            // sem acesso às preferências: segue com os valores padrão
        }
    }

    private void salvarPreferencias() {
        try {
            java.util.prefs.Preferences p = preferencias();
            p.put("usuario", coletaPanel.getUsuarioPadrao());
            p.put("pastaRelatorio", coletaPanel.getPastaSaida().toString());
            p.put("modoOrigem", coletaPanel.getModoOrigem().name());
            p.put("faixa", coletaPanel.getScanCidr());
            p.putBoolean("lldp", avancadoPanel.isLldpAtivo());
            p.putBoolean("snmp", avancadoPanel.isSnmpAtivo());
            p.putInt("concorrencia", avancadoPanel.getConcorrencia());
            p.put("gatewayIp", avancadoPanel.getGatewayIp());
            p.put("gatewayMac", avancadoPanel.getGatewayMac());
            boolean maximizada = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
            p.putBoolean("janelaMaximizada", maximizada);
            if (!maximizada) {
                p.putInt("janelaLargura", getWidth());
                p.putInt("janelaAltura", getHeight());
            }
            p.flush();
        } catch (Exception e) {
            // preferências são conveniência: falha aqui não atrapalha o uso
        }
    }

    // ---------------------------------------------------------------- Atalhos

    private void registrarAtalhos() {
        javax.swing.JRootPane raiz = getRootPane();
        javax.swing.InputMap im = raiz.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap am = raiz.getActionMap();
        atalho(im, am, "ctrl 1", "abaColeta", () -> navColeta.doClick());
        atalho(im, am, "ctrl 2", "abaEquipamentos", () -> navEquipamentos.doClick());
        atalho(im, am, "ctrl 3", "abaTopologia", () -> navTopologia.doClick());
        atalho(im, am, "ctrl 4", "abaAvancado", () -> navAvancado.doClick());
        atalho(im, am, "F5", "iniciarColeta", () -> {
            // Na aba Equipamentos o F5 recoleta as falhas (e as senhas corrigidas)
            if (equipamentosPanel.isShowing()) {
                equipamentosPanel.acionarRecoleta();
                return;
            }
            javax.swing.JButton b = coletaPanel.getBtnExecutarTudo();
            if (b.isVisible() && b.isEnabled()) {
                navColeta.doClick();
                b.doClick();
            }
        });
        atalho(im, am, "ctrl F", "buscarMapa", () -> {
            navTopologia.doClick();
            topologiaPanel.focarBuscaMapa();
        });
        atalho(im, am, "ctrl P", "gerarPdf", () -> {
            javax.swing.JButton b = topologiaPanel.getBtnGerarPdf();
            if (b.isVisible() && b.isEnabled()) b.doClick();
        });
        navColeta.setToolTipText("Coleta (Ctrl+1)");
        navEquipamentos.setToolTipText("Equipamentos (Ctrl+2)");
        navTopologia.setToolTipText("Topologia (Ctrl+3)");
        navAvancado.setToolTipText("Avançado (Ctrl+4)");
    }

    private static void atalho(javax.swing.InputMap im, javax.swing.ActionMap am, String tecla, String nome, Runnable acao) {
        im.put(javax.swing.KeyStroke.getKeyStroke(tecla), nome);
        am.put(nome, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                acao.run();
            }
        });
    }

    public static void exibir() {
        SwingThemeHelper.aplicarTemaMultiplataforma();
        TpDiscoverFrame frame = new TpDiscoverFrame();
        frame.setIconImages(SwingThemeHelper.iconesJanela());
        frame.setVisible(true);
        frame.toFront();
        frame.coletaPanel.getTxtSenha().requestFocusInWindow();
    }

    private void inicializarLayout() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(940, 680));
        setSize(1120, 800);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(SwingThemeHelper.BG_WINDOW);

        root.add(criarBarraSuperior(), BorderLayout.NORTH);

        containerCards.setOpaque(false);
        containerCards.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        containerCards.add(coletaPanel, CARD_COLETA);
        containerCards.add(equipamentosPanel, CARD_EQUIPAMENTOS);
        containerCards.add(topologiaPanel, CARD_TOPOLOGIA);
        containerCards.add(avancadoPanel, CARD_AVANCADO);
        root.add(containerCards, BorderLayout.CENTER);

        setContentPane(root);
    }

    private JPanel criarBarraSuperior() {
        JPanel bar = new JPanel(new BorderLayout(24, 0));
        bar.setBackground(SwingThemeHelper.NAV_BG);
        bar.setBorder(BorderFactory.createEmptyBorder(11, 22, 11, 22));

        // Marca
        JLabel logo = new JLabel(SwingThemeHelper.criarIconeLogo(34));
        logo.setPreferredSize(new Dimension(34, 34));
        logo.setHorizontalAlignment(JLabel.CENTER);

        JLabel nome = new JLabel("tpDiscover");
        nome.setFont(SwingThemeHelper.fonteSemibold(nome.getFont().getSize2D() + 2f));
        nome.setForeground(SwingThemeHelper.NAV_FG);
        JLabel sub = new JLabel("Intelbras");
        sub.setFont(sub.getFont().deriveFont(sub.getFont().getSize2D() - 1.5f));
        sub.setForeground(SwingThemeHelper.NAV_FG2);
        JPanel nomes = new JPanel(new BorderLayout(0, -2));
        nomes.setOpaque(false);
        nomes.add(nome, BorderLayout.NORTH);
        nomes.add(sub, BorderLayout.SOUTH);

        JPanel marca = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        marca.setOpaque(false);
        marca.add(logo);
        marca.add(nomes);
        JPanel marcaWrap = new JPanel(new java.awt.GridBagLayout());
        marcaWrap.setOpaque(false);
        marcaWrap.add(marca);
        bar.add(marcaWrap, BorderLayout.WEST);

        // Navegação
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        nav.setOpaque(false);
        Color corIconeNav = SwingThemeHelper.NAV_FG2;
        navColeta.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.SEARCH, corIconeNav));
        navEquipamentos.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.LIST, corIconeNav));
        navTopologia.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.NETWORK, corIconeNav));
        navAvancado.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.SLIDERS, corIconeNav));

        ButtonGroup group = new ButtonGroup();
        for (JToggleButton btn : new JToggleButton[]{navColeta, navEquipamentos, navTopologia, navAvancado}) {
            SwingThemeHelper.estilizarNavTab(btn);
            btn.setIconTextGap(8);
            group.add(btn);
            nav.add(btn);
        }
        bar.add(nav, BorderLayout.CENTER);

        // Status
        SwingThemeHelper.estilizarBadgeStatus(lblStatusBadge, SwingThemeHelper.TipoStatus.IDLE);
        JPanel direita = new JPanel(new java.awt.GridBagLayout());
        direita.setOpaque(false);
        direita.add(lblStatusBadge);
        bar.add(direita, BorderLayout.EAST);

        return bar;
    }

    /** Atualiza o texto do badge do cabeçalho e escolhe a cor conforme o conteúdo/estado. */
    private void atualizarBadge(String texto) {
        lblStatusBadge.setText(texto);
        String t = texto == null ? "" : texto.toLowerCase(java.util.Locale.ROOT);
        SwingThemeHelper.TipoStatus tipo;
        if (t.contains("erro")) {
            tipo = SwingThemeHelper.TipoStatus.ERR;
        } else if (t.contains("falha")) {
            tipo = SwingThemeHelper.TipoStatus.WARN;
        } else if (t.contains("cancelado") || t.equals("pronto")) {
            tipo = SwingThemeHelper.TipoStatus.IDLE;
        } else if (emExecucao() && !t.contains("gerado")) {
            tipo = SwingThemeHelper.TipoStatus.RUN;
        } else {
            tipo = SwingThemeHelper.TipoStatus.OK;
        }
        SwingThemeHelper.estilizarBadgeStatus(lblStatusBadge, tipo);
    }

    /** Coloca um ícone de alerta colorido na aba Topologia quando o diagnóstico pede atenção. */
    private void atualizarSinalTopologia() {
        SwingThemeHelper.TipoStatus tipo = topologiaPanel.getTipoStatus();
        if (tipo == SwingThemeHelper.TipoStatus.ERR || tipo == SwingThemeHelper.TipoStatus.WARN) {
            // sobre a ardósia usa os tons claros do tema escuro do STR (âmbar #E3B03A, vermelho #EE6B76)
            navTopologia.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.ALERT,
                    tipo == SwingThemeHelper.TipoStatus.ERR ? new Color(0xEE6B76) : new Color(0xE3B03A)));
            navTopologia.setToolTipText(tipo == SwingThemeHelper.TipoStatus.ERR
                    ? "Possível loop crítico detectado" : "A topologia tem avisos");
        } else {
            navTopologia.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.NETWORK, SwingThemeHelper.NAV_FG2));
            navTopologia.setToolTipText(null);
        }
    }

    private void registrarEventos() {
        navColeta.addActionListener(e -> cardLayout.show(containerCards, CARD_COLETA));
        navEquipamentos.addActionListener(e -> {
            cardLayout.show(containerCards, CARD_EQUIPAMENTOS);
            // cursor na tabela: setas, Enter (senha), Espaço (marcar) já funcionam
            javax.swing.SwingUtilities.invokeLater(() -> equipamentosPanel.focarTabela(false));
        });
        navTopologia.addActionListener(e -> cardLayout.show(containerCards, CARD_TOPOLOGIA));
        navAvancado.addActionListener(e -> cardLayout.show(containerCards, CARD_AVANCADO));

        coletaPanel.getBtnExecutarTudo().addActionListener(e -> iniciarExecucao(false, false));
        coletaPanel.getBtnApenasEscanear().addActionListener(e -> iniciarExecucao(true, false));
        coletaPanel.getBtnCancelar().addActionListener(e -> solicitarCancelamento());
        coletaPanel.getBtnGerarPdf().addActionListener(e -> gerarPdfFinal());
        topologiaPanel.getBtnGerarPdf().addActionListener(e -> gerarPdfFinal());

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
                } else if (pdfPendente && !sessaoSnapshotsPorIp.isEmpty()) {
                    int op = JOptionPane.showConfirmDialog(
                            TpDiscoverFrame.this,
                            "A coleta desta sessão ainda não virou PDF.\nDeseja gerar o relatório PDF antes de sair?",
                            "Gerar PDF antes de sair?",
                            JOptionPane.YES_NO_CANCEL_OPTION,
                            JOptionPane.QUESTION_MESSAGE
                    );
                    if (op == JOptionPane.CANCEL_OPTION || op == JOptionPane.CLOSED_OPTION) {
                        return;
                    }
                    if (op == JOptionPane.YES_OPTION) {
                        fecharAoTerminar = true;
                        gerarPdfFinal();
                        if (emExecucao()) {
                            return; // fecha automaticamente quando o PDF terminar
                        }
                        fecharAoTerminar = false;
                    }
                }
                salvarPreferencias();
                dispose();
            }
        });
    }

    private void irParaEquipamentosCorrigirFalhos() {
        if (tableModel.contarPorEstado(DiscoveredTargetRow.EstadoColeta.FALHA) > 0) {
            tableModel.selecionarApenasFalhos();
            equipamentosPanel.setSoFalhas(true);
        }
        selecionarAba(CARD_EQUIPAMENTOS);
        // primeira falha já selecionada: basta Enter para definir a senha
        javax.swing.SwingUtilities.invokeLater(() -> equipamentosPanel.focarTabela(true));
    }

    private void selecionarAba(String nomeCard) {
        cardLayout.show(containerCards, nomeCard);
        switch (nomeCard) {
            case CARD_COLETA:
                navColeta.setSelected(true);
                break;
            case CARD_EQUIPAMENTOS:
                navEquipamentos.setSelected(true);
                break;
            case CARD_TOPOLOGIA:
                navTopologia.setSelected(true);
                break;
            case CARD_AVANCADO:
                navAvancado.setSelected(true);
                break;
            default: {
                break;
            }
        }
    }

    private void iniciarExecucao(boolean apenasScan, boolean somenteTabela) {
        salvarPreferencias();
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
                    selecionarAba(CARD_COLETA);
                    SwingThemeHelper.marcarCampoComErro(coletaPanel.getTxtScanCidr());
                    exibirAviso("Informe a faixa de IPs que deseja varrer (ex.: 10.1.40.0/24).");
                    return;
                }
                try {
                    NetworkScanTarget.interpretarEspecificacoes(spec);
                } catch (IllegalArgumentException ex) {
                    selecionarAba(CARD_COLETA);
                    SwingThemeHelper.marcarCampoComErro(coletaPanel.getTxtScanCidr());
                    exibirAviso(ex.getMessage());
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
                    exibirAviso("Nenhum equipamento marcado. Adicione ou marque ao menos um na aba Equipamentos.");
                    selecionarAba(CARD_EQUIPAMENTOS);
                    return;
                }
            } else {
                // Senha em branco é permitida: equipamentos que pedem só o usuário (ou aceitam senha vazia)
                alvosManuais = tableModel.extrairOverridesManuais(usuarioPadrao, senhaPadrao);
            }
        } catch (IllegalArgumentException ex) {
            exibirAviso(ex.getMessage());
            return;
        }

        if (!apenasScan && Compat.isBlank(usuarioPadrao) && alvosManuais.isEmpty() && sessaoSnapshotsPorIp.isEmpty()) {
            SwingThemeHelper.marcarCampoComErro(coletaPanel.getTxtUsuario());
            exibirAviso("Informe o usuário de acesso (ex.: admin).");
            return;
        }

        Path pastaPdf = coletaPanel.getPastaSaida();
        topologiaPanel.setPastaSaidaAtual(pastaPdf);
        if (!apenasScan) {
            topologiaPanel.limparParaNovaExecucao();
        }

        List<ColetorEmLote.ColetaDispositivoSnapshot> snapshotsPreservados = somenteTabela
                ? Compat.listCopyOf(sessaoSnapshotsPorIp.values())
                : Compat.listOf();
        List<DiscoveredProduct> produtosConhecidos = Compat.listCopyOf(sessaoProdutosPorIp.values());
        List<TopologyEngine.EquipamentoSemAcesso> semAcessoInformados = tableModel.extrairEquipamentosSemAcesso();

        DiscoveryRequest req = new DiscoveryRequest(
                scanSpecs,
                alvosManuais,
                Compat.isBlank(usuarioPadrao) ? "admin" : usuarioPadrao,
                senhaPadrao,
                apenasScan,
                avancadoPanel.isLldpAtivo(),
                avancadoPanel.isSnmpAtivo(),
                avancadoPanel.getConcorrencia(),
                avancadoPanel.getGatewayIp(),
                avancadoPanel.getGatewayMac(),
                pastaPdf,
                null,
                avancadoPanel.getCredenciaisAlternativas(),
                produtosConhecidos,
                snapshotsPreservados,
                semAcessoInformados,
                false // coletas/testes de acesso só atualizam a prévia; o PDF sai em "Finalizar e gerar PDF"
        );

        dispararWorker(req, apenasScan ? "Preparando scan..." : "Iniciando coleta...");
    }

    /**
     * Gera o relatório PDF uma única vez, a partir da telemetria já coletada na sessão
     * (não reconecta nos equipamentos).
     */
    private void gerarPdfFinal() {
        if (emExecucao()) return;
        equipamentosPanel.finalizarEdicaoTabela();
        if (sessaoSnapshotsPorIp.isEmpty()) {
            exibirAviso("Nenhum equipamento foi coletado com sucesso ainda. Faça uma coleta antes de gerar o PDF.");
            fecharAoTerminar = false;
            return;
        }
        Path pastaPdf = coletaPanel.getPastaSaida();
        topologiaPanel.setPastaSaidaAtual(pastaPdf);
        topologiaPanel.limparParaNovaExecucao();

        DiscoveryRequest req = new DiscoveryRequest(
                Compat.listOf(),
                Compat.listOf(),
                Compat.isBlank(coletaPanel.getUsuarioPadrao()) ? "admin" : coletaPanel.getUsuarioPadrao(),
                coletaPanel.getSenhaPadrao(),
                false,
                false,
                false,
                avancadoPanel.getConcorrencia(),
                avancadoPanel.getGatewayIp(),
                avancadoPanel.getGatewayMac(),
                pastaPdf,
                null,
                avancadoPanel.getCredenciaisAlternativas(),
                Compat.listCopyOf(sessaoProdutosPorIp.values()),
                Compat.listCopyOf(sessaoSnapshotsPorIp.values()),
                tableModel.extrairEquipamentosSemAcesso(true),
                true
        );
        coletaPanel.appendLog("Gerando relatório PDF final com " + sessaoSnapshotsPorIp.size() + " equipamento(s) coletado(s)...");
        dispararWorker(req, "Gerando relatório PDF...");
    }

    private void dispararWorker(DiscoveryRequest req, String tituloInicial) {
        cancelamentoAtual = new AtomicBoolean(false);
        atualizarEstadoExecucao(true);
        coletaPanel.prepararNovaExecucao(tituloInicial, true);

        workerAtual = new DiscoverySwingWorker(
                req,
                tableModel,
                coletaPanel,
                topologiaPanel,
                cancelamentoAtual,
                this::atualizarBadge,
                this::aoTerminarExecucao,
                res -> registrarEstadoSessao(res, req)
        );
        workerAtual.execute();
    }

    private void aoTerminarExecucao() {
        atualizarEstadoExecucao(false);
        if (fecharAoTerminar) {
            fecharAoTerminar = false;
            dispose();
        }
    }

    private void registrarEstadoSessao(DiscoverySessionResult res, DiscoveryRequest req) {
        if (res == null || res.cancelado()) {
            fecharAoTerminar = false;
            return;
        }
        boolean apenasScan = req.apenasScan();
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

            boolean pdfGerado = req.gerarArquivoRelatorio()
                    && res.relatorio() != null
                    && res.relatorio().arquivoGerado() != null;
            if (req.gerarArquivoRelatorio()) {
                pdfPendente = !pdfGerado && !sessaoSnapshotsPorIp.isEmpty();
                if (!pdfGerado) {
                    fecharAoTerminar = false; // falhou: mantém a janela aberta para o usuário ver o erro
                }
            } else {
                pdfPendente = !sessaoSnapshotsPorIp.isEmpty();
            }
        } else if (res.possuiErroFatal()) {
            fecharAoTerminar = false;
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
        avancadoPanel.setEstadoExecucao(executando);
        if (executando) {
            lblStatusBadge.setText("Trabalhando...");
            SwingThemeHelper.estilizarBadgeStatus(lblStatusBadge, SwingThemeHelper.TipoStatus.RUN);
        } else {
            atualizarSinalTopologia();
        }
        boolean podeGerar = pdfPendente && !sessaoSnapshotsPorIp.isEmpty();
        coletaPanel.setGerarPdfDisponivel(podeGerar);
        topologiaPanel.setGerarPdfDisponivel(podeGerar, executando);
    }

    private boolean emExecucao() {
        return workerAtual != null && !workerAtual.isDone();
    }

    private void importarArquivoAlvos() {
        JFileChooser chooser = new JFileChooser(Paths.get("").toAbsolutePath().toFile());
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
            if (cfg.usuarioPadrao() != null && !Compat.isBlank(cfg.usuarioPadrao())) {
                coletaPanel.setUsuarioPadrao(cfg.usuarioPadrao());
            }
            if (cfg.senhaPadrao() != null && !Compat.isBlank(cfg.senhaPadrao())) {
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

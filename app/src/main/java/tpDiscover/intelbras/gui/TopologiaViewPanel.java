package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.correlacao.RelatorioTopologia;
import tpDiscover.intelbras.gui.SwingThemeHelper.IconKind;
import tpDiscover.intelbras.gui.SwingThemeHelper.TipoStatus;
import tpDiscover.intelbras.modelos.LoopDiagnostic;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import tpDiscover.intelbras.util.Compat;
/**
 * Aba "Topologia": faixa de diagnóstico (saudável / atenção / loop), mapa interativo da rede
 * para consulta rápida, problemas encontrados, conexões entre equipamentos e ações do PDF.
 */
public class TopologiaViewPanel extends JPanel {

    private static final int ABA_MAPA = 0;
    private static final int ABA_PROBLEMAS = 1;
    private static final int ABA_CONEXOES = 2;

    private final JPanel loopCard = new JPanel(new BorderLayout(12, 0));
    private final JLabel lblLoopBanner = new JLabel("Nenhuma coleta realizada ainda.");
    private final JLabel lblLoopDetalhe = SwingThemeHelper.criarTextoAjuda("Faça uma coleta na aba Coleta para ver a topologia aqui.");
    private final JButton btnCorrigirPendencias = new JButton("Corrigir senhas");
    private final JButton btnAbrirPdf = new JButton("Abrir PDF");
    private final JButton btnAbrirPasta = new JButton("Abrir pasta");
    private final JButton btnGerarPdf = new JButton("Finalizar e gerar PDF");

    private final JTabbedPane abas = new JTabbedPane();
    private final DefaultTableModel enlacesModel;
    private final DefaultTableModel loopsModel;
    private final TopologiaMapaPanel mapaPanel = new TopologiaMapaPanel();

    private static final String GRAVIDADE_SEM_ACESSO = "Sem acesso";
    /** id (MAC normalizado) do equipamento de origem de cada linha da aba Problemas, para ir até ele no mapa. */
    private final java.util.List<String> idsOrigemProblemas = new java.util.ArrayList<>();
    private final Runnable aoSolicitarCorrigirPendencias;

    private Path ultimoPdfGerado;
    private Path pastaSaidaAtual;
    private TipoStatus tipoStatus = TipoStatus.IDLE;

    public TopologiaViewPanel(Path pastaInicial) {
        this(pastaInicial, null);
    }

    public TopologiaViewPanel(Path pastaInicial, Runnable aoSolicitarCorrigirPendencias) {
        this.pastaSaidaAtual = pastaInicial;
        this.aoSolicitarCorrigirPendencias = aoSolicitarCorrigirPendencias;
        setLayout(new BorderLayout(0, 14));
        setOpaque(false);

        // Faixa de diagnóstico
        SwingThemeHelper.estilizarFaixa(loopCard, TipoStatus.IDLE, 14);
        loopCard.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 14));
        lblLoopBanner.setForeground(SwingThemeHelper.TEXT_SECONDARY);
        lblLoopBanner.setFont(SwingThemeHelper.fonteSemibold(lblLoopBanner.getFont().getSize2D() + 1.5f));
        lblLoopBanner.setIconTextGap(10);
        JPanel textos = new JPanel(new BorderLayout(0, 4));
        textos.setOpaque(false);
        textos.add(lblLoopBanner, BorderLayout.NORTH);
        textos.add(lblLoopDetalhe, BorderLayout.SOUTH);
        loopCard.add(textos, BorderLayout.CENTER);

        SwingThemeHelper.estilizarBotaoCompacto(btnCorrigirPendencias);
        btnCorrigirPendencias.setIcon(SwingThemeHelper.criarIcone(IconKind.KEY, SwingThemeHelper.STATUS_WARN_FG));
        btnCorrigirPendencias.setToolTipText("Abre a lista de equipamentos com os que falharam já marcados");
        btnCorrigirPendencias.setVisible(false);
        btnCorrigirPendencias.addActionListener(e -> {
            if (aoSolicitarCorrigirPendencias != null) aoSolicitarCorrigirPendencias.run();
        });

        SwingThemeHelper.estilizarBotaoCompacto(btnAbrirPasta);
        btnAbrirPasta.setIcon(SwingThemeHelper.criarIcone(IconKind.FOLDER, SwingThemeHelper.TEXT_SECONDARY));
        btnAbrirPasta.addActionListener(e -> {
            Path dir = ultimoPdfGerado != null && ultimoPdfGerado.getParent() != null
                    ? ultimoPdfGerado.getParent()
                    : pastaSaidaAtual;
            SwingThemeHelper.abrirCaminhoSistema(this, dir, true);
        });

        SwingThemeHelper.estilizarBotaoPrimario(btnGerarPdf);
        btnGerarPdf.setIcon(SwingThemeHelper.criarIcone(IconKind.PDF, Color.WHITE));
        btnGerarPdf.setToolTipText("Gera o relatório PDF uma única vez com tudo o que foi coletado nesta sessão");
        btnGerarPdf.setVisible(false);

        SwingThemeHelper.estilizarBotaoPrimario(btnAbrirPdf);
        btnAbrirPdf.setIcon(SwingThemeHelper.criarIcone(IconKind.PDF, Color.WHITE));
        btnAbrirPdf.setEnabled(false);
        btnAbrirPdf.addActionListener(e -> SwingThemeHelper.abrirCaminhoSistema(this, ultimoPdfGerado, false));

        JPanel acoes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        acoes.setOpaque(false);
        acoes.add(btnCorrigirPendencias);
        acoes.add(btnAbrirPasta);
        acoes.add(btnGerarPdf);
        acoes.add(btnAbrirPdf);
        JPanel acoesWrap = new JPanel(new java.awt.GridBagLayout());
        acoesWrap.setOpaque(false);
        acoesWrap.add(acoes);
        loopCard.add(acoesWrap, BorderLayout.EAST);
        add(loopCard, BorderLayout.NORTH);

        // Conteúdo
        JPanel conteudoCard = SwingThemeHelper.criarCardArredondado();
        conteudoCard.setLayout(new BorderLayout());
        conteudoCard.setBorder(BorderFactory.createEmptyBorder(6, 14, 14, 14));

        loopsModel = new DefaultTableModel(new String[]{"Gravidade", "Tipo", "Origem / Porta", "Destino", "Evidência", "O que fazer"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable tblLoops = criarTabela(loopsModel,
                "Nenhum problema encontrado",
                "Sem loops, sem portas bloqueadas pelo STP e todos os equipamentos foram acessados.");
        tblLoops.setToolTipText(null);
        tblLoops.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int linha = tblLoops.rowAtPoint(e.getPoint());
                if (linha < 0) return;
                int m = tblLoops.convertRowIndexToModel(linha);
                Object gravidade = loopsModel.getValueAt(m, 0);
                if (GRAVIDADE_SEM_ACESSO.equals(gravidade)) {
                    if (aoSolicitarCorrigirPendencias != null) aoSolicitarCorrigirPendencias.run();
                } else if (m < idsOrigemProblemas.size() && idsOrigemProblemas.get(m) != null) {
                    abas.setSelectedIndex(ABA_MAPA);
                    mapaPanel.selecionarPorId("dev-" + idsOrigemProblemas.get(m));
                }
            }
        });
        tblLoops.getColumnModel().getColumn(0).setCellRenderer(new GravidadeRenderer());
        int[] larguras = {125, 130, 230, 90, 170, 330};
        for (int i = 0; i < larguras.length; i++) {
            tblLoops.getColumnModel().getColumn(i).setPreferredWidth(larguras[i]);
        }
        tblLoops.getColumnModel().getColumn(0).setMinWidth(120);

        enlacesModel = new DefaultTableModel(new String[]{"Equipamento", "Porta", "Porta remota", "Ligado em"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable tblEnlaces = criarTabela(enlacesModel,
                "Nenhuma conexão mapeada",
                "Faça uma coleta para ver aqui quem está ligado em qual porta.");

        mapaPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        abas.addTab("Mapa", mapaPanel);
        abas.addTab("Problemas (0)", envolverScroll(tblLoops, SwingThemeHelper.BG_CARD));
        abas.addTab("Conexões (0)", envolverScroll(tblEnlaces, SwingThemeHelper.BG_CARD));
        conteudoCard.add(abas, BorderLayout.CENTER);
        add(conteudoCard, BorderLayout.CENTER);
    }

    private static JTable criarTabela(DefaultTableModel model, String vazio1, String vazio2) {
        JTable t = new JTable(model) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (getRowCount() == 0) {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    Font base = getFont();
                    g2.setFont(SwingThemeHelper.fonteSemibold(base.getSize2D() + 1.5f));
                    java.awt.FontMetrics f1 = g2.getFontMetrics();
                    int cy = Math.max(60, getVisibleRect().height / 2 - 10);
                    g2.setColor(SwingThemeHelper.TEXT_SECONDARY);
                    g2.drawString(vazio1, (getWidth() - f1.stringWidth(vazio1)) / 2, cy);
                    g2.setFont(base);
                    java.awt.FontMetrics f2 = g2.getFontMetrics();
                    g2.setColor(SwingThemeHelper.TEXT_MUTED);
                    g2.drawString(vazio2, (getWidth() - f2.stringWidth(vazio2)) / 2, cy + f1.getHeight() + 2);
                    g2.dispose();
                }
            }
        };
        t.setRowHeight(34);
        t.setFillsViewportHeight(true);
        t.setShowHorizontalLines(true);
        t.setGridColor(SwingThemeHelper.BORDER_SUBTLE);
        t.setShowVerticalLines(false);
        t.setIntercellSpacing(new java.awt.Dimension(0, 1));
        t.setDefaultRenderer(Object.class, new TooltipRenderer());
        return t;
    }

    private JScrollPane envolverScroll(Component comp, Color fundo) {
        JScrollPane s = new JScrollPane(comp);
        s.setBorder(BorderFactory.createLineBorder(SwingThemeHelper.BORDER_SUBTLE, 1, true));
        s.getViewport().setBackground(fundo);
        return s;
    }

    /** Atalho Ctrl+F: abre o mapa e põe o cursor na busca. */
    public void focarBuscaMapa() {
        abas.setSelectedIndex(ABA_MAPA);
        mapaPanel.focarBusca();
    }

    public JButton getBtnGerarPdf() {
        return btnGerarPdf;
    }

    /** Estado do último diagnóstico (usado para sinalizar a aba no cabeçalho). */
    public TipoStatus getTipoStatus() {
        return tipoStatus;
    }

    /** Alterna entre "Finalizar e gerar PDF" (coleta sem PDF) e "Abrir PDF" (PDF já gerado). */
    public void setGerarPdfDisponivel(boolean disponivel, boolean executando) {
        btnGerarPdf.setVisible(disponivel);
        btnGerarPdf.setEnabled(!executando);
        btnAbrirPdf.setVisible(!disponivel);
        revalidate();
        repaint();
    }

    public void setPastaSaidaAtual(Path pasta) {
        this.pastaSaidaAtual = pasta;
        mapaPanel.setPastaRelatorio(pasta, ultimoPdfGerado);
    }

    public void limparParaNovaExecucao() {
        this.ultimoPdfGerado = null;
        btnAbrirPdf.setEnabled(false);
        btnCorrigirPendencias.setVisible(false);
        aplicarBanner(TipoStatus.RUN, IconKind.REFRESH, "Coleta em andamento...", "A topologia será atualizada ao final.");
    }

    public void exibirRelatorio(RelatorioTopologia.ResultadoRelatorio relatorio) {
        if (relatorio == null) return;

        mapaPanel.exibir(relatorio.mapa());
        Path relatorioArquivo = relatorio.arquivoGerado() != null && Files.exists(relatorio.arquivoGerado())
                ? relatorio.arquivoGerado() : null;
        mapaPanel.setPastaRelatorio(pastaSaidaAtual, relatorioArquivo);

        enlacesModel.setRowCount(0);
        for (RelatorioTopologia.EnlaceResumo e : relatorio.enlaces()) {
            enlacesModel.addRow(new Object[]{e.origem(), e.portaOrigem(), e.portaDestino(), e.destino()});
        }
        abas.setTitleAt(ABA_CONEXOES, "Conexões (" + relatorio.enlaces().size() + ")");

        loopsModel.setRowCount(0);
        idsOrigemProblemas.clear();
        int criticos = 0;
        int avisos = 0;
        for (LoopDiagnostic d : relatorio.loops()) {
            if (d.severidade() == LoopDiagnostic.Severidade.CRITICO) criticos++;
            else avisos++;
            String orig = d.switchOrigemId() + (Compat.isBlank(d.portaOrigem()) ? "" : " [" + d.portaOrigem() + "]");
            String dest = Compat.isBlank(d.switchDestinoId())
                    ? "-"
                    : d.switchDestinoId() + (Compat.isBlank(d.portaDestino()) ? "" : " [" + d.portaDestino() + "]");
            loopsModel.addRow(new Object[]{
                    d.severidade(), d.tipo(), orig, dest, d.evidencia(), d.acaoRecomendada()
            });
            idsOrigemProblemas.add(d.switchOrigemId());
        }
        // Equipamentos sem acesso também são problemas a resolver no atendimento
        for (tpDiscover.intelbras.correlacao.TopologyEngine.EquipamentoSemAcesso eq : relatorio.pendenciasAcesso()) {
            loopsModel.addRow(new Object[]{
                    GRAVIDADE_SEM_ACESSO,
                    eq.ehFalhaAutenticacao() ? "Senha incorreta" : "Sem acesso CLI",
                    eq.ipBase() + (Compat.isBlank(eq.rotuloModelo()) ? "" : "  " + eq.rotuloModelo()),
                    "-",
                    eq.descricaoMotivo(),
                    eq.ehFalhaAutenticacao()
                            ? "Informe a senha certa em Equipamentos e use 'Recoletar falhas' (duplo clique aqui)"
                            : "Verifique se o acesso Telnet/SSH está liberado e a sessão livre; depois recolete"
            });
            idsOrigemProblemas.add(null);
        }
        int totalProblemas = relatorio.loops().size() + relatorio.pendenciasAcesso().size();
        abas.setTitleAt(ABA_PROBLEMAS, "Problemas (" + totalProblemas + ")");
        abas.setSelectedIndex(ABA_MAPA);

        atualizarBannerLoop(criticos, avisos, relatorio.topologiaParcial(), relatorio.pendenciasAcesso().size(),
                relatorio.enlaces().size());

        Path gerado = relatorio.arquivoGerado();
        if (gerado != null && Files.exists(gerado)) {
            this.ultimoPdfGerado = gerado;
            btnAbrirPdf.setEnabled(true);
            String nome = gerado.getFileName().toString().toLowerCase(Locale.ROOT);
            btnAbrirPdf.setText(nome.endsWith(".html") ? "Abrir HTML" : "Abrir PDF");
            btnAbrirPdf.setToolTipText(gerado.toAbsolutePath().toString());
        } else {
            this.ultimoPdfGerado = null;
            btnAbrirPdf.setEnabled(false);
            btnAbrirPdf.setText("Abrir PDF");
        }
    }

    private void atualizarBannerLoop(int criticos, int avisos, boolean topologiaParcial, int qtdPendencias, int enlaces) {
        btnCorrigirPendencias.setVisible(topologiaParcial || qtdPendencias > 0);
        if (criticos > 0) {
            aplicarBanner(TipoStatus.ERR, IconKind.ALERT,
                    criticos + (criticos == 1 ? " possível loop crítico detectado" : " possíveis loops críticos detectados"),
                    (avisos > 0 ? avisos + " aviso(s) de STP. " : "")
                            + (topologiaParcial ? "Topologia parcial. " : "")
                            + "No mapa, os pontos em vermelho mostram onde; a aba Problemas diz o que fazer.");
        } else if (topologiaParcial) {
            aplicarBanner(TipoStatus.WARN, IconKind.ALERT,
                    "Topologia parcial",
                    qtdPendencias > 0
                            ? qtdPendencias + " sem acesso (veja a aba Problemas). Ligações incertas ficam tracejadas."
                            : "Há um trecho sem acesso até o gateway. Ligações incertas ficam tracejadas.");
        } else if (avisos > 0) {
            aplicarBanner(TipoStatus.WARN, IconKind.ALERT,
                    avisos + " porta(s) bloqueada(s) pelo STP",
                    "Há redundância na rede, mas ela está contida pelo STP. Nenhuma ação urgente.");
        } else {
            aplicarBanner(TipoStatus.OK, IconKind.CHECK,
                    "Rede saudável",
                    "Nenhum loop detectado. " + enlaces + " conexão(ões) mapeada(s).");
        }
    }

    private void aplicarBanner(TipoStatus tipo, IconKind icone, String titulo, String detalhe) {
        this.tipoStatus = tipo;
        SwingThemeHelper.estilizarFaixa(loopCard, tipo, 14);
        Color fg = tipo == TipoStatus.IDLE ? SwingThemeHelper.TEXT_SECONDARY : SwingThemeHelper.corTexto(tipo);
        lblLoopBanner.setForeground(fg);
        lblLoopBanner.setIcon(SwingThemeHelper.criarIcone(icone, fg, 18));
        lblLoopBanner.setText(titulo);
        lblLoopDetalhe.setText(detalhe);
        lblLoopDetalhe.setBorder(new EmptyBorder(0, 28, 0, 0));
        repaint();
    }

    private static class TooltipRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String txt = value != null ? value.toString() : "";
            l.setToolTipText(Compat.isBlank(txt) ? null : txt);
            l.setBorder(new EmptyBorder(2, 10, 2, 10));
            if (!isSelected) {
                l.setBackground(SwingThemeHelper.BG_CARD);
                l.setForeground(SwingThemeHelper.TEXT_PRIMARY);
            }
            return l;
        }
    }

    private static final class GravidadeRenderer extends TooltipRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            boolean semAcesso = GRAVIDADE_SEM_ACESSO.equals(value);
            boolean critico = value == LoopDiagnostic.Severidade.CRITICO;
            String texto = semAcesso ? GRAVIDADE_SEM_ACESSO : (critico ? "Crítico" : "Aviso");
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, texto, isSelected, hasFocus, row, column);
            Color cor = critico ? SwingThemeHelper.STATUS_ERR_FG
                    : (semAcesso ? SwingThemeHelper.TEXT_SECONDARY : SwingThemeHelper.STATUS_WARN_FG);
            l.setIcon(SwingThemeHelper.criarIcone(semAcesso ? IconKind.KEY : IconKind.ALERT, cor, 13));
            l.setIconTextGap(8);
            l.setFont(SwingThemeHelper.fonteSemibold(l.getFont().getSize2D()));
            if (!isSelected) l.setForeground(cor);
            return l;
        }
    }
}

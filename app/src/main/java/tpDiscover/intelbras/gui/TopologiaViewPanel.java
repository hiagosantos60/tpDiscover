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
import javax.swing.JTextArea;
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

/**
 * Aba "Topologia": faixa de diagnóstico (saudável / atenção / loop), problemas encontrados,
 * conexões entre equipamentos, relatório completo em texto e ações do PDF.
 */
public class TopologiaViewPanel extends JPanel {

    private static final int ABA_PROBLEMAS = 0;
    private static final int ABA_CONEXOES = 1;

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
    private final JTextArea txtTopologia = new JTextArea();

    private Path ultimoPdfGerado;
    private Path pastaSaidaAtual;
    private TipoStatus tipoStatus = TipoStatus.IDLE;

    public TopologiaViewPanel(Path pastaInicial) {
        this(pastaInicial, null);
    }

    public TopologiaViewPanel(Path pastaInicial, Runnable aoSolicitarCorrigirPendencias) {
        this.pastaSaidaAtual = pastaInicial;
        setLayout(new BorderLayout(0, 14));
        setOpaque(false);

        // Faixa de diagnóstico
        SwingThemeHelper.estilizarFaixa(loopCard, TipoStatus.IDLE, 14);
        loopCard.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 14));
        lblLoopBanner.setForeground(SwingThemeHelper.TEXT_SECONDARY);
        lblLoopBanner.setFont(lblLoopBanner.getFont().deriveFont(Font.BOLD, lblLoopBanner.getFont().getSize2D() + 1.5f));
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
        JTable tblLoops = criarTabela(loopsModel);
        tblLoops.getColumnModel().getColumn(0).setCellRenderer(new GravidadeRenderer());
        tblLoops.getColumnModel().getColumn(0).setPreferredWidth(110);
        tblLoops.getColumnModel().getColumn(0).setMaxWidth(140);

        enlacesModel = new DefaultTableModel(new String[]{"Equipamento", "Porta", "Porta remota", "Ligado em"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable tblEnlaces = criarTabela(enlacesModel);

        txtTopologia.setEditable(false);
        txtTopologia.setBackground(SwingThemeHelper.BG_CONSOLE);
        txtTopologia.setForeground(new Color(0xB9C1CE));
        txtTopologia.setFont(SwingThemeHelper.fonteMonoespacada());
        txtTopologia.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        txtTopologia.setText("Faça uma coleta na aba Coleta para visualizar a topologia e o diagnóstico de loops.");

        abas.addTab("Problemas (0)", envolverScroll(tblLoops, SwingThemeHelper.BG_CARD));
        abas.addTab("Conexões (0)", envolverScroll(tblEnlaces, SwingThemeHelper.BG_CARD));
        abas.addTab("Relatório completo", envolverScroll(txtTopologia, SwingThemeHelper.BG_CONSOLE));
        conteudoCard.add(abas, BorderLayout.CENTER);
        add(conteudoCard, BorderLayout.CENTER);
    }

    private static JTable criarTabela(DefaultTableModel model) {
        JTable t = new JTable(model);
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
    }

    public void limparParaNovaExecucao() {
        this.ultimoPdfGerado = null;
        btnAbrirPdf.setEnabled(false);
        btnCorrigirPendencias.setVisible(false);
        aplicarBanner(TipoStatus.RUN, IconKind.REFRESH, "Coleta em andamento...", "A topologia será atualizada ao final.");
    }

    public void exibirRelatorio(RelatorioTopologia.ResultadoRelatorio relatorio) {
        if (relatorio == null) return;

        txtTopologia.setText(relatorio.textoCompleto());
        txtTopologia.setCaretPosition(0);

        enlacesModel.setRowCount(0);
        for (RelatorioTopologia.EnlaceResumo e : relatorio.enlaces()) {
            enlacesModel.addRow(new Object[]{e.origem(), e.portaOrigem(), e.portaDestino(), e.destino()});
        }
        abas.setTitleAt(ABA_CONEXOES, "Conexões (" + relatorio.enlaces().size() + ")");

        loopsModel.setRowCount(0);
        int criticos = 0;
        int avisos = 0;
        for (LoopDiagnostic d : relatorio.loops()) {
            if (d.severidade() == LoopDiagnostic.Severidade.CRITICO) criticos++;
            else avisos++;
            String orig = d.switchOrigemId() + (d.portaOrigem().isBlank() ? "" : " [" + d.portaOrigem() + "]");
            String dest = d.switchDestinoId().isBlank()
                    ? "-"
                    : d.switchDestinoId() + (d.portaDestino().isBlank() ? "" : " [" + d.portaDestino() + "]");
            loopsModel.addRow(new Object[]{
                    d.severidade(), d.tipo(), orig, dest, d.evidencia(), d.acaoRecomendada()
            });
        }
        abas.setTitleAt(ABA_PROBLEMAS, "Problemas (" + relatorio.loops().size() + ")");
        abas.setSelectedIndex(relatorio.loops().isEmpty() ? ABA_CONEXOES : ABA_PROBLEMAS);

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
                            + "Veja a aba Problemas para saber onde e o que fazer.");
        } else if (topologiaParcial) {
            aplicarBanner(TipoStatus.WARN, IconKind.ALERT,
                    "Topologia parcial",
                    qtdPendencias > 0
                            ? qtdPendencias + " equipamento(s) sem acesso (senha incorreta ou sem CLI). Ligações incertas aparecem tracejadas."
                            : "Há um trecho da rede sem acesso até o gateway. Ligações incertas aparecem tracejadas.");
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
            l.setToolTipText(txt.isBlank() ? null : txt);
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
            boolean critico = value == LoopDiagnostic.Severidade.CRITICO;
            String texto = critico ? "Crítico" : "Aviso";
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, texto, isSelected, hasFocus, row, column);
            Color cor = critico ? SwingThemeHelper.STATUS_ERR_FG : SwingThemeHelper.STATUS_WARN_FG;
            l.setIcon(SwingThemeHelper.criarIcone(IconKind.ALERT, cor, 13));
            l.setIconTextGap(8);
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            if (!isSelected) l.setForeground(cor);
            return l;
        }
    }
}

package tpDiscover.intelbras.gui;

import com.formdev.flatlaf.FlatClientProperties;
import tpDiscover.intelbras.correlacao.RelatorioTopologia;
import tpDiscover.intelbras.modelos.LoopDiagnostic;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Aba "Topologia & Loops": diagnóstico visual de loops (somente leitura),
 * tabela de enlaces par-a-par, árvore completa e abertura do PDF gerado.
 */
public class TopologiaViewPanel extends JPanel {

    private final JPanel loopCard = new JPanel(new BorderLayout(10, 0));
    private final JLabel lblLoopBanner = new JLabel("Nenhuma coleta realizada ainda.");
    private final JButton btnCorrigirPendencias = new JButton("Corrigir Pendências");
    private final JButton btnAbrirPdf = new JButton("Abrir PDF");
    private final JButton btnAbrirPasta = new JButton("Abrir Pasta");

    private final JTabbedPane abas = new JTabbedPane();
    private final DefaultTableModel enlacesModel;
    private final DefaultTableModel loopsModel;
    private final JTextArea txtTopologia = new JTextArea();

    private Path ultimoPdfGerado;
    private Path pastaSaidaAtual;

    public TopologiaViewPanel(Path pastaInicial) {
        this(pastaInicial, null);
    }

    public TopologiaViewPanel(Path pastaInicial, Runnable aoSolicitarCorrigirPendencias) {
        this.pastaSaidaAtual = pastaInicial;
        setLayout(new BorderLayout(0, 12));
        setOpaque(false);

        // Banner superior com ações de PDF
        loopCard.putClientProperty(FlatClientProperties.STYLE,
                "arc: 12; background: #2F333A; border: 1,1,1,1,#40454F,1,12");
        loopCard.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        lblLoopBanner.setForeground(SwingThemeHelper.TEXT_MUTED);
        lblLoopBanner.setFont(lblLoopBanner.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        loopCard.add(lblLoopBanner, BorderLayout.CENTER);

        SwingThemeHelper.estilizarBotaoSecundario(btnCorrigirPendencias);
        btnCorrigirPendencias.setVisible(false);
        btnCorrigirPendencias.addActionListener(e -> {
            if (aoSolicitarCorrigirPendencias != null) aoSolicitarCorrigirPendencias.run();
        });

        SwingThemeHelper.estilizarBotaoPrimario(btnAbrirPdf);
        btnAbrirPdf.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.PDF, Color.WHITE));
        btnAbrirPdf.setEnabled(false);
        btnAbrirPdf.addActionListener(e -> SwingThemeHelper.abrirCaminhoSistema(this, ultimoPdfGerado, false));

        SwingThemeHelper.estilizarBotaoSecundario(btnAbrirPasta);
        btnAbrirPasta.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.FOLDER, SwingThemeHelper.TEXT_PRIMARY));
        btnAbrirPasta.addActionListener(e -> {
            Path dir = ultimoPdfGerado != null && ultimoPdfGerado.getParent() != null
                    ? ultimoPdfGerado.getParent()
                    : pastaSaidaAtual;
            SwingThemeHelper.abrirCaminhoSistema(this, dir, true);
        });

        JPanel acoes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        acoes.setOpaque(false);
        acoes.add(btnCorrigirPendencias);
        acoes.add(btnAbrirPasta);
        acoes.add(btnAbrirPdf);
        loopCard.add(acoes, BorderLayout.EAST);
        add(loopCard, BorderLayout.NORTH);

        // Conteúdo em sub-abas limpas dentro de um card arredondado
        JPanel conteudoCard = SwingThemeHelper.criarCardArredondado();
        conteudoCard.setLayout(new BorderLayout());

        enlacesModel = new DefaultTableModel(new String[]{"Origem", "Porta Local", "Porta Remota", "Destino"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable tblEnlaces = new JTable(enlacesModel);
        tblEnlaces.setRowHeight(28);
        tblEnlaces.setShowHorizontalLines(false);
        tblEnlaces.setShowVerticalLines(false);

        loopsModel = new DefaultTableModel(new String[]{"Severidade", "Tipo", "Origem / Porta", "Destino", "Evidência", "Sugestão"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable tblLoops = new JTable(loopsModel);
        tblLoops.setRowHeight(28);
        tblLoops.setShowHorizontalLines(false);
        tblLoops.setShowVerticalLines(false);

        txtTopologia.setEditable(false);
        txtTopologia.setBackground(SwingThemeHelper.BG_CONSOLE);
        txtTopologia.setForeground(new Color(204, 212, 224));
        txtTopologia.setFont(SwingThemeHelper.fonteMonoespacada());
        txtTopologia.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        txtTopologia.setText("Execute uma coleta na aba 'Coleta' para visualizar a topologia e o diagnóstico de loops.");

        abas.addTab("Árvore & Relatório", envolverScroll(txtTopologia));
        abas.addTab("Enlaces Diretos (0)", envolverScroll(tblEnlaces));
        abas.addTab("Diagnóstico de Loops (0)", envolverScroll(tblLoops));
        conteudoCard.add(abas, BorderLayout.CENTER);
        add(conteudoCard, BorderLayout.CENTER);
    }

    private JScrollPane envolverScroll(java.awt.Component comp) {
        JScrollPane s = new JScrollPane(comp);
        s.setBorder(BorderFactory.createLineBorder(SwingThemeHelper.BORDER_SUBTLE, 1, true));
        return s;
    }

    public void setPastaSaidaAtual(Path pasta) {
        this.pastaSaidaAtual = pasta;
    }

    public void limparParaNovaExecucao() {
        this.ultimoPdfGerado = null;
        btnAbrirPdf.setEnabled(false);
        btnCorrigirPendencias.setVisible(false);
        lblLoopBanner.setIcon(null);
        lblLoopBanner.setForeground(SwingThemeHelper.TEXT_MUTED);
        lblLoopBanner.setText("Coleta em andamento...");
        loopCard.putClientProperty(FlatClientProperties.STYLE,
                "arc: 12; background: #2F333A; border: 1,1,1,1,#40454F,1,12");
    }

    public void exibirRelatorio(RelatorioTopologia.ResultadoRelatorio relatorio) {
        if (relatorio == null) return;

        txtTopologia.setText(relatorio.textoCompleto());
        txtTopologia.setCaretPosition(0);

        enlacesModel.setRowCount(0);
        for (RelatorioTopologia.EnlaceResumo e : relatorio.enlaces()) {
            enlacesModel.addRow(new Object[]{e.origem(), e.portaOrigem(), e.portaDestino(), e.destino()});
        }
        abas.setTitleAt(1, "Enlaces Diretos (" + relatorio.enlaces().size() + ")");

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
                    d.severidade().name(), d.tipo(), orig, dest, d.evidencia(), d.acaoRecomendada()
            });
        }
        abas.setTitleAt(2, "Diagnóstico de Loops (" + relatorio.loops().size() + ")");

        atualizarBannerLoop(criticos, avisos, relatorio.topologiaParcial(), relatorio.pendenciasAcesso().size());

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

    private void atualizarBannerLoop(int criticos, int avisos, boolean topologiaParcial, int qtdPendencias) {
        btnCorrigirPendencias.setVisible(topologiaParcial || qtdPendencias > 0);
        if (criticos > 0) {
            loopCard.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 12; background: #472428; border: 1,1,1,1,#803D44,1,12");
            lblLoopBanner.setForeground(SwingThemeHelper.STATUS_ERR_FG);
            lblLoopBanner.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.ALERT, SwingThemeHelper.STATUS_ERR_FG));
            String sufixo = topologiaParcial ? " (Topologia parcial)" : "";
            lblLoopBanner.setText(criticos + " possível(is) LOOP(S) CRÍTICO(S) detectado(s) e " + avisos + " aviso(s) STP" + sufixo);
            abas.setSelectedIndex(2);
        } else if (topologiaParcial) {
            loopCard.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 12; background: #40331D; border: 1,1,1,1,#785D2F,1,12");
            lblLoopBanner.setForeground(SwingThemeHelper.STATUS_WARN_FG);
            lblLoopBanner.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.ALERT, SwingThemeHelper.STATUS_WARN_FG));
            String detalhe = qtdPendencias > 0
                    ? (" (" + qtdPendencias + " equipamento(s) sem acesso CLI ou falha de autenticação)")
                    : " (há segmento L2 intermediário sem acesso CLI até o Gateway)";
            lblLoopBanner.setText("Topologia parcial" + detalhe + " — enlaces incertos em tracejado.");
        } else if (avisos > 0) {
            loopCard.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 12; background: #40331D; border: 1,1,1,1,#785D2F,1,12");
            lblLoopBanner.setForeground(SwingThemeHelper.STATUS_WARN_FG);
            lblLoopBanner.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.ALERT, SwingThemeHelper.STATUS_WARN_FG));
            lblLoopBanner.setText(avisos + " porta(s) com bloqueio STP detectado (redundância contida)");
        } else {
            loopCard.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 12; background: #22352C; border: 1,1,1,1,#36614A,1,12");
            lblLoopBanner.setForeground(SwingThemeHelper.STATUS_OK_FG);
            lblLoopBanner.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.CHECK, SwingThemeHelper.STATUS_OK_FG));
            lblLoopBanner.setText("Rede saudável — nenhum loop L2 detectado nos equipamentos coletados.");
        }
    }
}

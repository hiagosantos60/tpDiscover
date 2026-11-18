package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.gui.SwingThemeHelper.IconKind;
import tpDiscover.intelbras.gui.SwingThemeHelper.TipoStatus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Aba principal "Coleta": formulário em 3 passos (onde procurar, acesso, onde salvar),
 * um único botão de ação principal (que vira "Cancelar" durante a execução),
 * progresso, faixa de resultado com próximos passos e registro de atividade.
 */
public class ColetaViewPanel extends JPanel {

    public enum ModoOrigem {
        SCAN_AUTOMATICO("Minha rede local (automático)"),
        FAIXA_CIDR("Uma faixa de IPs que vou informar"),
        SOMENTE_LISTA("Só os equipamentos da lista");

        private final String rotulo;

        ModoOrigem(String rotulo) {
            this.rotulo = rotulo;
        }

        @Override
        public String toString() {
            return rotulo;
        }
    }

    private static final DateTimeFormatter HORA_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JComboBox<ModoOrigem> cmbOrigem = new JComboBox<>(ModoOrigem.values());
    private final JTextField txtScanCidr = new JTextField(18);
    private final JLabel lblDicaOrigem = SwingThemeHelper.criarTextoAjuda(" ");

    private final JTextField txtUsuario = new JTextField("admin", 12);
    private final JPasswordField txtSenha = new JPasswordField(16);

    private final JTextField txtPastaPdf = new JTextField(28);
    private final JButton btnEscolherPasta = new JButton("Procurar...");

    private final JButton btnExecutarTudo = new JButton("Iniciar coleta");
    private final JButton btnApenasEscanear = new JButton("Só procurar equipamentos");
    private final JButton btnCancelar = new JButton("Cancelar");

    private final JLabel lblStatusExecucao = new JLabel("Pronto para começar");
    private final JLabel lblProgresso = SwingThemeHelper.criarTextoAjuda("");
    private final JProgressBar progressBar = new JProgressBar(0, 100);

    private final JPanel faixaConclusao = new JPanel(new BorderLayout(0, 10));
    private final JLabel lblResumoConclusao = new JLabel();
    private final JButton btnCorrigirFalhos = new JButton("Corrigir senhas");
    private final JButton btnAbrirPdfRapido = new JButton("Abrir PDF");
    private final JButton btnGerarPdf = new JButton("Finalizar e gerar PDF");
    private final JButton btnVerTopologia = new JButton("Ver topologia");

    private final JTextArea txtLog = new JTextArea();

    private Path ultimoPdfGerado;

    public ColetaViewPanel(Path pastaInicial, Runnable aoSolicitarVerTopologia) {
        this(pastaInicial, aoSolicitarVerTopologia, null);
    }

    public ColetaViewPanel(
            Path pastaInicial,
            Runnable aoSolicitarVerTopologia,
            Runnable aoSolicitarCorrigirFalhos
    ) {
        setLayout(new BorderLayout(0, 14));
        setOpaque(false);

        txtPastaPdf.setText(pastaInicial.toString());
        SwingThemeHelper.configurarPlaceholder(txtScanCidr, "Ex.: 10.1.40.0/24 ou 10.1.40.80-250");
        SwingThemeHelper.configurarPlaceholder(txtUsuario, "admin");
        SwingThemeHelper.configurarCampoSenhaComOlho(txtSenha, "Senha dos switches");
        SwingThemeHelper.configurarPlaceholder(txtPastaPdf, "Pasta onde o PDF será salvo");

        add(criarCardParametros(), BorderLayout.NORTH);
        add(criarCardProgresso(aoSolicitarVerTopologia, aoSolicitarCorrigirFalhos), BorderLayout.CENTER);
        atualizarModoOrigem();

        // Enter em qualquer campo do formulário inicia a coleta.
        for (JTextField f : new JTextField[]{txtScanCidr, txtUsuario, txtSenha, txtPastaPdf}) {
            f.addActionListener(e -> {
                if (btnExecutarTudo.isVisible() && btnExecutarTudo.isEnabled()) {
                    btnExecutarTudo.doClick();
                }
            });
        }
    }

    // ------------------------------------------------------------------ Formulário

    private JPanel criarCardParametros() {
        JPanel card = SwingThemeHelper.criarCardArredondado();
        card.setLayout(new BorderLayout(0, 16));

        card.add(SwingThemeHelper.criarCabecalho(
                "Nova coleta",
                "Somente leitura: nenhuma configuração dos equipamentos é alterada."), BorderLayout.NORTH);

        JPanel passos = new JPanel(new GridBagLayout());
        passos.setOpaque(false);

        // Passo 1: onde procurar
        JPanel rede = new JPanel(new BorderLayout(10, 0));
        rede.setOpaque(false);
        cmbOrigem.setPreferredSize(new Dimension(280, cmbOrigem.getPreferredSize().height));
        rede.add(cmbOrigem, BorderLayout.WEST);
        rede.add(txtScanCidr, BorderLayout.CENTER);
        adicionarPasso(passos, 0, "1", "Onde procurar", rede, lblDicaOrigem);

        // Passo 2: acesso
        JPanel acesso = new JPanel(new GridBagLayout());
        acesso.setOpaque(false);
        GridBagConstraints a = new GridBagConstraints();
        a.fill = GridBagConstraints.HORIZONTAL;
        a.insets = new Insets(0, 0, 0, 10);
        a.gridx = 0; a.weightx = 0;
        acesso.add(rotuloInline("Usuário"), a);
        a.gridx = 1; a.weightx = 0.35;
        acesso.add(txtUsuario, a);
        a.gridx = 2; a.weightx = 0; a.insets = new Insets(0, 8, 0, 10);
        acesso.add(rotuloInline("Senha"), a);
        a.gridx = 3; a.weightx = 0.65; a.insets = new Insets(0, 0, 0, 0);
        acesso.add(txtSenha, a);
        adicionarPasso(passos, 1, "2", "Acesso", acesso,
                SwingThemeHelper.criarTextoAjuda("Usada em todos os equipamentos. Senhas diferentes podem ser ajustadas na aba Equipamentos."));

        // Passo 3: onde salvar
        SwingThemeHelper.estilizarBotaoCompacto(btnEscolherPasta);
        btnEscolherPasta.setIcon(SwingThemeHelper.criarIcone(IconKind.FOLDER, SwingThemeHelper.TEXT_SECONDARY));
        btnEscolherPasta.addActionListener(e -> escolherPasta());
        JPanel pasta = new JPanel(new BorderLayout(10, 0));
        pasta.setOpaque(false);
        pasta.add(txtPastaPdf, BorderLayout.CENTER);
        pasta.add(btnEscolherPasta, BorderLayout.EAST);
        adicionarPasso(passos, 2, "3", "Salvar relatório em", pasta, null);

        card.add(passos, BorderLayout.CENTER);

        // Ações
        SwingThemeHelper.estilizarBotaoPrimario(btnExecutarTudo);
        btnExecutarTudo.setIcon(SwingThemeHelper.criarIcone(IconKind.PLAY, Color.WHITE));
        btnExecutarTudo.setToolTipText("Procura os equipamentos Intelbras, conecta em cada um e monta a topologia (Enter)");

        SwingThemeHelper.estilizarBotaoSecundario(btnApenasEscanear);
        btnApenasEscanear.setIcon(SwingThemeHelper.criarIcone(IconKind.SEARCH, SwingThemeHelper.TEXT_PRIMARY));
        btnApenasEscanear.setToolTipText("Apenas lista os equipamentos encontrados, sem fazer login neles");

        SwingThemeHelper.estilizarBotaoPerigo(btnCancelar);
        btnCancelar.setIcon(SwingThemeHelper.criarIcone(IconKind.STOP, SwingThemeHelper.STATUS_ERR_FG));
        btnCancelar.setVisible(false);

        JPanel acoes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        acoes.setOpaque(false);
        acoes.add(btnApenasEscanear);
        acoes.add(btnCancelar);
        acoes.add(btnExecutarTudo);

        JPanel rodape = new JPanel(new BorderLayout(0, 14));
        rodape.setOpaque(false);
        JSeparator sep = new JSeparator();
        sep.setForeground(SwingThemeHelper.BORDER_SUBTLE);
        rodape.add(sep, BorderLayout.NORTH);
        rodape.add(acoes, BorderLayout.CENTER);
        card.add(rodape, BorderLayout.SOUTH);

        cmbOrigem.addActionListener(e -> atualizarModoOrigem());
        return card;
    }

    private void adicionarPasso(JPanel grid, int linha, String numero, String titulo, JComponent controle, JComponent ajuda) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = linha * 2;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(linha == 0 ? 0 : 12, 0, 0, 12);

        gc.gridx = 0;
        grid.add(SwingThemeHelper.criarBadgeEtapa(numero), gc);

        gc.gridx = 1;
        gc.insets = new Insets(linha == 0 ? 0 : 12, 0, 0, 16);
        JLabel lbl = SwingThemeHelper.criarRotuloCampo(titulo);
        lbl.setPreferredSize(new Dimension(150, lbl.getPreferredSize().height));
        grid.add(lbl, gc);

        gc.gridx = 2;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(linha == 0 ? 0 : 12, 0, 0, 0);
        grid.add(controle, gc);

        if (ajuda != null) {
            GridBagConstraints ga = new GridBagConstraints();
            ga.gridx = 2;
            ga.gridy = linha * 2 + 1;
            ga.anchor = GridBagConstraints.WEST;
            ga.fill = GridBagConstraints.HORIZONTAL;
            ga.weightx = 1.0;
            ga.insets = new Insets(5, 2, 0, 0);
            grid.add(ajuda, ga);
        }
    }

    private JLabel rotuloInline(String texto) {
        JLabel l = new JLabel(texto);
        l.setForeground(SwingThemeHelper.TEXT_SECONDARY);
        return l;
    }

    // ------------------------------------------------------------------ Progresso e log

    private JPanel criarCardProgresso(Runnable aoSolicitarVerTopologia, Runnable aoSolicitarCorrigirFalhos) {
        JPanel card = SwingThemeHelper.criarCardArredondado();
        card.setLayout(new BorderLayout(0, 12));

        JPanel topo = new JPanel();
        topo.setOpaque(false);
        topo.setLayout(new BoxLayout(topo, BoxLayout.Y_AXIS));

        // Linha de status
        JPanel linhaStatus = new JPanel(new BorderLayout(10, 0));
        linhaStatus.setOpaque(false);
        lblStatusExecucao.setForeground(SwingThemeHelper.TEXT_PRIMARY);
        lblStatusExecucao.setFont(lblStatusExecucao.getFont().deriveFont(Font.BOLD, lblStatusExecucao.getFont().getSize2D() + 1f));
        linhaStatus.add(lblStatusExecucao, BorderLayout.CENTER);
        linhaStatus.add(lblProgresso, BorderLayout.EAST);
        linhaStatus.setAlignmentX(LEFT_ALIGNMENT);
        topo.add(linhaStatus);
        topo.add(Box.createVerticalStrut(10));

        progressBar.setStringPainted(false);
        progressBar.setPreferredSize(new Dimension(200, 6));
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        progressBar.setAlignmentX(LEFT_ALIGNMENT);
        topo.add(progressBar);

        // Faixa de conclusão
        faixaConclusao.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 12));
        faixaConclusao.setAlignmentX(LEFT_ALIGNMENT);
        lblResumoConclusao.setFont(lblResumoConclusao.getFont().deriveFont(Font.BOLD));
        lblResumoConclusao.setIconTextGap(10);
        faixaConclusao.add(lblResumoConclusao, BorderLayout.CENTER);

        SwingThemeHelper.estilizarBotaoCompacto(btnCorrigirFalhos);
        btnCorrigirFalhos.setIcon(SwingThemeHelper.criarIcone(IconKind.KEY, SwingThemeHelper.STATUS_WARN_FG));
        btnCorrigirFalhos.setToolTipText("Abre a lista de equipamentos com os que falharam já marcados");
        btnCorrigirFalhos.setVisible(false);
        btnCorrigirFalhos.addActionListener(e -> {
            if (aoSolicitarCorrigirFalhos != null) aoSolicitarCorrigirFalhos.run();
        });

        SwingThemeHelper.estilizarBotaoCompacto(btnVerTopologia);
        btnVerTopologia.setIcon(SwingThemeHelper.criarIcone(IconKind.NETWORK, SwingThemeHelper.TEXT_SECONDARY));
        btnVerTopologia.addActionListener(e -> {
            if (aoSolicitarVerTopologia != null) aoSolicitarVerTopologia.run();
        });

        SwingThemeHelper.estilizarBotaoPrimario(btnGerarPdf);
        btnGerarPdf.setIcon(SwingThemeHelper.criarIcone(IconKind.PDF, Color.WHITE));
        btnGerarPdf.setToolTipText("Gera o relatório PDF uma única vez com tudo o que foi coletado nesta sessão");
        btnGerarPdf.setVisible(false);

        SwingThemeHelper.estilizarBotaoPrimario(btnAbrirPdfRapido);
        btnAbrirPdfRapido.setIcon(SwingThemeHelper.criarIcone(IconKind.PDF, Color.WHITE));
        btnAbrirPdfRapido.addActionListener(e -> SwingThemeHelper.abrirCaminhoSistema(this, ultimoPdfGerado, false));

        JPanel acoesResumo = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        acoesResumo.setOpaque(false);
        acoesResumo.add(btnCorrigirFalhos);
        acoesResumo.add(btnVerTopologia);
        acoesResumo.add(btnGerarPdf);
        acoesResumo.add(btnAbrirPdfRapido);
        faixaConclusao.add(acoesResumo, BorderLayout.SOUTH);
        faixaConclusao.setVisible(false);

        topo.add(Box.createVerticalStrut(12));
        topo.add(faixaConclusao);
        card.add(topo, BorderLayout.NORTH);

        // Registro de atividade
        JPanel areaLog = new JPanel(new BorderLayout(0, 6));
        areaLog.setOpaque(false);

        JPanel cabecalhoLog = new JPanel(new BorderLayout());
        cabecalhoLog.setOpaque(false);
        JLabel tituloLog = new JLabel("Registro de atividade");
        tituloLog.setForeground(SwingThemeHelper.TEXT_MUTED);
        tituloLog.setFont(tituloLog.getFont().deriveFont(Font.BOLD, tituloLog.getFont().getSize2D() - 1f));
        cabecalhoLog.add(tituloLog, BorderLayout.WEST);

        JButton btnCopiarLog = new JButton("Copiar");
        JButton btnLimparLog = new JButton("Limpar");
        SwingThemeHelper.estilizarBotaoFantasma(btnCopiarLog);
        SwingThemeHelper.estilizarBotaoFantasma(btnLimparLog);
        btnCopiarLog.setIcon(SwingThemeHelper.criarIcone(IconKind.COPY, SwingThemeHelper.TEXT_SECONDARY, 13));
        btnLimparLog.setIcon(SwingThemeHelper.criarIcone(IconKind.CLEAR, SwingThemeHelper.TEXT_SECONDARY, 12));
        btnCopiarLog.setToolTipText("Copiar o registro para enviar ao suporte");
        btnCopiarLog.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(txtLog.getText()), null);
            appendLog("Registro copiado para a área de transferência.");
        });
        btnLimparLog.addActionListener(e -> txtLog.setText(""));
        JPanel acoesLog = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        acoesLog.setOpaque(false);
        acoesLog.add(btnCopiarLog);
        acoesLog.add(btnLimparLog);
        cabecalhoLog.add(acoesLog, BorderLayout.EAST);
        areaLog.add(cabecalhoLog, BorderLayout.NORTH);

        txtLog.setEditable(false);
        txtLog.setLineWrap(true);
        txtLog.setWrapStyleWord(true);
        txtLog.setBackground(SwingThemeHelper.BG_CONSOLE);
        txtLog.setForeground(new Color(0xB9C1CE));
        txtLog.setCaretColor(Color.WHITE);
        txtLog.setFont(SwingThemeHelper.fonteMonoespacada());
        txtLog.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        txtLog.setText("[" + LocalTime.now().format(HORA_FMT)
                + "] Pronto. Informe a senha e clique em 'Iniciar coleta' (ou pressione Enter).\n");

        JScrollPane scrollLog = new JScrollPane(txtLog);
        scrollLog.setBorder(BorderFactory.createLineBorder(SwingThemeHelper.BORDER_SUBTLE, 1, true));
        scrollLog.getViewport().setBackground(SwingThemeHelper.BG_CONSOLE);
        areaLog.add(scrollLog, BorderLayout.CENTER);
        card.add(areaLog, BorderLayout.CENTER);

        return card;
    }

    // ------------------------------------------------------------------ Estado

    public void atualizarModoOrigem() {
        ModoOrigem modo = getModoOrigem();
        txtScanCidr.setVisible(modo == ModoOrigem.FAIXA_CIDR);
        lblDicaOrigem.setText(switch (modo) {
            case SCAN_AUTOMATICO -> "Detecta sua sub-rede automaticamente e mostra só produtos Intelbras.";
            case FAIXA_CIDR -> "Use CIDR (10.1.40.0/24), intervalo (10.1.40.80-250) ou vários separados por vírgula.";
            case SOMENTE_LISTA -> "Não faz varredura: usa apenas os equipamentos marcados na aba Equipamentos.";
        });
        btnApenasEscanear.setVisible(modo != ModoOrigem.SOMENTE_LISTA && !btnCancelar.isVisible());
        btnExecutarTudo.setText(modo == ModoOrigem.SOMENTE_LISTA ? "Coletar lista" : "Iniciar coleta");
        revalidate();
        repaint();
    }

    private void escolherPasta() {
        JFileChooser chooser = new JFileChooser(getPastaSaida().toFile());
        chooser.setDialogTitle("Selecionar pasta para salvar o relatório PDF");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            txtPastaPdf.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        }
    }

    public ModoOrigem getModoOrigem() {
        Object sel = cmbOrigem.getSelectedItem();
        return sel instanceof ModoOrigem m ? m : ModoOrigem.SCAN_AUTOMATICO;
    }

    public void selecionarModoOrigem(ModoOrigem modo, String spec) {
        cmbOrigem.setSelectedItem(modo);
        if (spec != null && !spec.isBlank()) {
            txtScanCidr.setText(spec);
        }
        atualizarModoOrigem();
    }

    public String getScanCidr() {
        return txtScanCidr.getText().trim();
    }

    public String getUsuarioPadrao() {
        return txtUsuario.getText().trim();
    }

    public void setUsuarioPadrao(String user) {
        if (user != null) txtUsuario.setText(user);
    }

    public String getSenhaPadrao() {
        return new String(txtSenha.getPassword());
    }

    public void setSenhaPadrao(String senha) {
        if (senha != null) txtSenha.setText(senha);
    }

    public Path getPastaSaida() {
        String txt = txtPastaPdf.getText().trim();
        return Path.of(txt.isEmpty() ? "." : txt).toAbsolutePath().normalize();
    }

    public JButton getBtnExecutarTudo() {
        return btnExecutarTudo;
    }

    public JButton getBtnApenasEscanear() {
        return btnApenasEscanear;
    }

    public JButton getBtnCancelar() {
        return btnCancelar;
    }

    public JButton getBtnGerarPdf() {
        return btnGerarPdf;
    }

    public JTextField getTxtScanCidr() {
        return txtScanCidr;
    }

    public JPasswordField getTxtSenha() {
        return txtSenha;
    }

    public JTextField getTxtUsuario() {
        return txtUsuario;
    }

    public void setEstadoExecucao(boolean executando) {
        cmbOrigem.setEnabled(!executando);
        txtScanCidr.setEnabled(!executando);
        txtUsuario.setEnabled(!executando);
        txtSenha.setEnabled(!executando);
        txtPastaPdf.setEnabled(!executando);
        btnEscolherPasta.setEnabled(!executando);

        btnExecutarTudo.setVisible(!executando);
        btnExecutarTudo.setEnabled(!executando);
        btnApenasEscanear.setVisible(!executando && getModoOrigem() != ModoOrigem.SOMENTE_LISTA);
        btnApenasEscanear.setEnabled(!executando);
        btnCancelar.setVisible(executando);
        btnCancelar.setEnabled(executando);
        btnGerarPdf.setEnabled(!executando);
        revalidate();
        repaint();
    }

    /** Mostra o botão "Finalizar e gerar PDF" quando há coleta na sessão ainda sem PDF gerado. */
    public void setGerarPdfDisponivel(boolean disponivel) {
        btnGerarPdf.setVisible(disponivel);
        if (disponivel) {
            btnAbrirPdfRapido.setVisible(false);
        }
        revalidate();
        repaint();
    }

    public void prepararNovaExecucao(String tituloEtapa, boolean indeterminado) {
        this.ultimoPdfGerado = null;
        faixaConclusao.setVisible(false);
        lblStatusExecucao.setText(tituloEtapa);
        lblProgresso.setText(indeterminado ? "" : "0%");
        progressBar.setIndeterminate(indeterminado);
        progressBar.setValue(0);
        progressBar.setString(indeterminado ? tituloEtapa : "0%");
    }

    public void atualizarProgresso(int atual, int total, String textoStatus) {
        progressBar.setIndeterminate(false);
        int max = Math.max(1, total);
        int pct = Math.min(100, (int) Math.round((atual * 100.0) / max));
        progressBar.setValue(pct);
        String txt = atual + " de " + total + "  ·  " + pct + "%";
        progressBar.setString(txt);
        lblProgresso.setText(txt);
        lblStatusExecucao.setText(textoStatus);
    }

    public void definirStatusFinal(String textoResumo, int pct, String textoBarra) {
        progressBar.setIndeterminate(false);
        progressBar.setValue(pct);
        progressBar.setString(textoBarra);
        lblProgresso.setText(textoBarra);
        lblStatusExecucao.setText(textoResumo);
    }

    public void exibirResumoConclusao(String texto, Path pdfGerado, boolean possuiLoopCritico) {
        exibirResumoConclusao(texto, pdfGerado, possuiLoopCritico, false, false);
    }

    public void exibirResumoConclusao(
            String texto,
            Path pdfGerado,
            boolean possuiLoopCritico,
            boolean possuiAlertaParcial,
            boolean mostrarBotaoCorrigir
    ) {
        this.ultimoPdfGerado = (pdfGerado != null && Files.exists(pdfGerado)) ? pdfGerado : null;
        TipoStatus tipo = possuiLoopCritico ? TipoStatus.ERR : (possuiAlertaParcial ? TipoStatus.WARN : TipoStatus.OK);
        SwingThemeHelper.estilizarFaixa(faixaConclusao, tipo, 12);
        Color fg = SwingThemeHelper.corTexto(tipo);
        lblResumoConclusao.setForeground(fg);
        lblResumoConclusao.setIcon(SwingThemeHelper.criarIcone(
                tipo == TipoStatus.OK ? IconKind.CHECK : IconKind.ALERT, fg, 16));
        lblResumoConclusao.setText(texto);
        lblResumoConclusao.setToolTipText(texto);
        btnCorrigirFalhos.setVisible(mostrarBotaoCorrigir);
        btnVerTopologia.setVisible(!texto.startsWith("Nenhum equipamento autenticado"));
        btnAbrirPdfRapido.setVisible(this.ultimoPdfGerado != null);
        faixaConclusao.setVisible(true);
        revalidate();
    }

    public void appendLog(String msg) {
        if (msg == null) return;
        txtLog.append("[" + LocalTime.now().format(HORA_FMT) + "] " + msg + "\n");
        txtLog.setCaretPosition(txtLog.getDocument().getLength());
    }
}

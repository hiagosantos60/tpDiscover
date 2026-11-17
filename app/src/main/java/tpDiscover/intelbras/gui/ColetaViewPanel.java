package tpDiscover.intelbras.gui;

import com.formdev.flatlaf.FlatClientProperties;
import tpDiscover.intelbras.coleta.CredencialAcesso;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Aba principal "Coleta": formulário enxuto (Rede, Usuário, Senha e Pasta) + botões limpos +
 * barra de progresso + barra de conclusão + console de logs em tempo real.
 */
public class ColetaViewPanel extends JPanel {

    public enum ModoOrigem {
        SCAN_AUTOMATICO("Scan automático (sub-rede local)"),
        FAIXA_CIDR("Faixa / CIDR customizada"),
        SOMENTE_LISTA("Somente lista da aba Equipamentos");

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
    private final JLabel lblDicaOrigem = new JLabel("Detecta automaticamente sua sub-rede local e filtra produtos Intelbras.");

    private final JTextField txtUsuario = new JTextField("admin", 16);
    private final JPasswordField txtSenha = new JPasswordField(18);
    private final JButton btnCredenciaisExtras = new JButton("Credenciais extras (2)");
    private final List<CredencialAcesso> credenciaisAlternativas = new ArrayList<>(CredencialAcesso.presetsPadrao());

    private final JTextField txtPastaPdf = new JTextField(28);
    private final JButton btnEscolherPasta = new JButton("Procurar...");

    private final JButton btnExecutarTudo = new JButton("Escanear e Coletar");
    private final JButton btnApenasEscanear = new JButton("Apenas Escanear");
    private final JButton btnCancelar = new JButton("Cancelar");

    private final JLabel lblStatusExecucao = new JLabel("Pronto para iniciar a coleta somente-leitura.");
    private final JProgressBar progressBar = new JProgressBar(0, 100);

    private final JPanel faixaConclusao = new JPanel(new BorderLayout(8, 0));
    private final JLabel lblResumoConclusao = new JLabel();
    private final JButton btnCorrigirFalhos = new JButton("Corrigir Falhos");
    private final JButton btnAbrirPdfRapido = new JButton("Abrir PDF");
    private final JButton btnVerTopologia = new JButton("Ver Topologia");

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
        setLayout(new BorderLayout(0, 12));
        setOpaque(false);

        txtPastaPdf.setText(pastaInicial.toString());
        SwingThemeHelper.configurarPlaceholder(txtScanCidr, "Ex: 10.1.40.0/24 ou 10.1.40.80-250");
        SwingThemeHelper.configurarPlaceholder(txtUsuario, "admin");
        SwingThemeHelper.configurarCampoSenhaComOlho(txtSenha, "Senha padrão dos switches");
        SwingThemeHelper.configurarPlaceholder(txtPastaPdf, "Pasta onde o PDF será salvo");
        atualizarRotuloCredenciaisExtras();

        add(criarCardParametros(), BorderLayout.NORTH);
        add(criarCardLogsEProgresso(aoSolicitarVerTopologia, aoSolicitarCorrigirFalhos), BorderLayout.CENTER);
        atualizarModoOrigem();
    }

    private JPanel criarCardParametros() {
        JPanel card = SwingThemeHelper.criarCardArredondado();
        card.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 8);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        // Cabeçalho do card
        JLabel titulo = SwingThemeHelper.criarTituloSecao("Parâmetros da Coleta");
        JLabel sub = new JLabel("O tpDiscover descobre sub-rede, protocolo (SSH/Telnet), gateway e fallback legado admin/admin automaticamente.");
        sub.setForeground(SwingThemeHelper.TEXT_MUTED);
        JPanel header = new JPanel(new BorderLayout(0, 3));
        header.setOpaque(false);
        header.add(titulo, BorderLayout.NORTH);
        header.add(sub, BorderLayout.SOUTH);

        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 4;
        gc.weightx = 1.0;
        card.add(header, gc);

        // Linha 1: Origem da rede
        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.gridy = 1;
        gc.gridx = 0;
        card.add(rotulo("Rede:"), gc);

        JPanel redePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        redePanel.setOpaque(false);
        redePanel.add(cmbOrigem);
        redePanel.add(txtScanCidr);
        lblDicaOrigem.setForeground(SwingThemeHelper.TEXT_MUTED);
        redePanel.add(lblDicaOrigem);

        gc.gridx = 1;
        gc.gridwidth = 3;
        gc.weightx = 1.0;
        card.add(redePanel, gc);

        // Linha 2: Usuário, Senha e botão compacto Credenciais Extras
        gc.gridwidth = 1;
        gc.gridy = 2;
        gc.gridx = 0;
        gc.weightx = 0;
        card.add(rotulo("Usuário padrão:"), gc);

        gc.gridx = 1;
        gc.weightx = 0.35;
        card.add(txtUsuario, gc);

        gc.gridx = 2;
        gc.weightx = 0;
        card.add(rotulo("Senha padrão:"), gc);

        SwingThemeHelper.estilizarBotaoSecundario(btnCredenciaisExtras);
        btnCredenciaisExtras.putClientProperty(FlatClientProperties.STYLE,
                "arc: 8; background: #353942; foreground: #D5DBE5; borderColor: #464B56; margin: 4,9,4,9");
        btnCredenciaisExtras.setToolTipText("Ajustar senhas de OLTs e credenciais alternativas automáticas");
        btnCredenciaisExtras.addActionListener(e -> abrirDialogCredenciaisExtras());

        JPanel senhaBox = new JPanel(new BorderLayout(6, 0));
        senhaBox.setOpaque(false);
        senhaBox.add(txtSenha, BorderLayout.CENTER);
        senhaBox.add(btnCredenciaisExtras, BorderLayout.EAST);

        gc.gridx = 3;
        gc.weightx = 0.65;
        card.add(senhaBox, gc);

        // Linha 3: Pasta do PDF
        gc.gridy = 3;
        gc.gridx = 0;
        gc.weightx = 0;
        card.add(rotulo("Salvar PDF em:"), gc);

        SwingThemeHelper.estilizarBotaoSecundario(btnEscolherPasta);
        btnEscolherPasta.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.FOLDER, SwingThemeHelper.TEXT_PRIMARY));
        btnEscolherPasta.addActionListener(e -> escolherPasta());

        JPanel pastaBox = new JPanel(new BorderLayout(8, 0));
        pastaBox.setOpaque(false);
        pastaBox.add(txtPastaPdf, BorderLayout.CENTER);
        pastaBox.add(btnEscolherPasta, BorderLayout.EAST);

        gc.gridx = 1;
        gc.gridwidth = 3;
        gc.weightx = 1.0;
        card.add(pastaBox, gc);

        // Linha 4: Ações principais
        SwingThemeHelper.estilizarBotaoPrimario(btnExecutarTudo);
        btnExecutarTudo.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.PLAY, Color.WHITE));

        SwingThemeHelper.estilizarBotaoSecundario(btnApenasEscanear);
        btnApenasEscanear.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.SEARCH, SwingThemeHelper.TEXT_PRIMARY));

        SwingThemeHelper.estilizarBotaoPerigo(btnCancelar);
        btnCancelar.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.STOP, SwingThemeHelper.STATUS_ERR_FG));
        btnCancelar.setEnabled(false);

        JPanel acoes = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        acoes.setOpaque(false);
        acoes.add(btnExecutarTudo);
        acoes.add(btnApenasEscanear);
        acoes.add(btnCancelar);

        gc.gridy = 4;
        gc.gridx = 1;
        gc.gridwidth = 3;
        card.add(acoes, gc);

        cmbOrigem.addActionListener(e -> atualizarModoOrigem());
        return card;
    }

    private JPanel criarCardLogsEProgresso(Runnable aoSolicitarVerTopologia, Runnable aoSolicitarCorrigirFalhos) {
        JPanel card = SwingThemeHelper.criarCardArredondado();
        card.setLayout(new BorderLayout(0, 10));

        // Topo: Progresso + resumo + faixa rápida pós-conclusão
        JPanel statusTopo = new JPanel(new BorderLayout(0, 6));
        statusTopo.setOpaque(false);

        JPanel cabecalhoLog = new JPanel(new BorderLayout(8, 0));
        cabecalhoLog.setOpaque(false);
        lblStatusExecucao.setForeground(SwingThemeHelper.TEXT_PRIMARY);
        lblStatusExecucao.setFont(lblStatusExecucao.getFont().deriveFont(java.awt.Font.BOLD));
        cabecalhoLog.add(lblStatusExecucao, BorderLayout.CENTER);

        JPanel acoesLog = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        acoesLog.setOpaque(false);
        JButton btnCopiarLog = new JButton("Copiar Log");
        JButton btnLimparLog = new JButton("Limpar");
        SwingThemeHelper.estilizarBotaoSecundario(btnCopiarLog);
        SwingThemeHelper.estilizarBotaoSecundario(btnLimparLog);
        btnCopiarLog.putClientProperty(FlatClientProperties.STYLE,
                "background: #353942; foreground: #D5DBE5; borderColor: #464B56; margin: 4,10,4,10");
        btnLimparLog.putClientProperty(FlatClientProperties.STYLE,
                "background: #353942; foreground: #D5DBE5; borderColor: #464B56; margin: 4,10,4,10");
        btnCopiarLog.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.COPY, SwingThemeHelper.TEXT_PRIMARY));
        btnLimparLog.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.CLEAR, SwingThemeHelper.TEXT_PRIMARY));
        btnCopiarLog.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(txtLog.getText()), null);
            appendLog("Log copiado para a área de transferência.");
        });
        btnLimparLog.addActionListener(e -> txtLog.setText(""));
        acoesLog.add(btnCopiarLog);
        acoesLog.add(btnLimparLog);
        cabecalhoLog.add(acoesLog, BorderLayout.EAST);

        progressBar.setStringPainted(true);
        progressBar.setString("Aguardando");
        progressBar.setPreferredSize(new Dimension(200, 18));

        statusTopo.add(cabecalhoLog, BorderLayout.NORTH);
        statusTopo.add(progressBar, BorderLayout.CENTER);

        // Faixa discreta de conclusão com botões rápidos (Abrir PDF / Ver Topologia)
        faixaConclusao.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10; background: #22352C; border: 1,1,1,1,#36614A,1,10");
        faixaConclusao.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        lblResumoConclusao.setForeground(SwingThemeHelper.STATUS_OK_FG);
        lblResumoConclusao.setFont(lblResumoConclusao.getFont().deriveFont(java.awt.Font.BOLD));
        faixaConclusao.add(lblResumoConclusao, BorderLayout.CENTER);

        SwingThemeHelper.estilizarBotaoSecundario(btnCorrigirFalhos);
        btnCorrigirFalhos.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.SLIDERS, SwingThemeHelper.STATUS_WARN_FG));
        btnCorrigirFalhos.setVisible(false);
        btnCorrigirFalhos.addActionListener(e -> {
            if (aoSolicitarCorrigirFalhos != null) aoSolicitarCorrigirFalhos.run();
        });

        SwingThemeHelper.estilizarBotaoPrimario(btnAbrirPdfRapido);
        btnAbrirPdfRapido.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.PDF, Color.WHITE));
        btnAbrirPdfRapido.addActionListener(e -> SwingThemeHelper.abrirCaminhoSistema(this, ultimoPdfGerado, false));

        SwingThemeHelper.estilizarBotaoSecundario(btnVerTopologia);
        btnVerTopologia.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.NETWORK, SwingThemeHelper.TEXT_PRIMARY));
        btnVerTopologia.addActionListener(e -> {
            if (aoSolicitarVerTopologia != null) aoSolicitarVerTopologia.run();
        });

        JPanel direitaResumo = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        direitaResumo.setOpaque(false);
        direitaResumo.add(btnCorrigirFalhos);
        direitaResumo.add(btnVerTopologia);
        direitaResumo.add(btnAbrirPdfRapido);
        faixaConclusao.add(direitaResumo, BorderLayout.EAST);
        faixaConclusao.setVisible(false);
        statusTopo.add(faixaConclusao, BorderLayout.SOUTH);

        card.add(statusTopo, BorderLayout.NORTH);

        // Console de Log escuro e limpo
        txtLog.setEditable(false);
        txtLog.setBackground(SwingThemeHelper.BG_CONSOLE);
        txtLog.setForeground(new Color(204, 212, 224));
        txtLog.setCaretColor(Color.WHITE);
        txtLog.setFont(SwingThemeHelper.fonteMonoespacada());
        txtLog.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        txtLog.setText("[" + LocalTime.now().format(HORA_FMT)
                + "] Pronto. Preencha a senha padrão e clique em 'Escanear e Coletar'.\n");

        JScrollPane scrollLog = new JScrollPane(txtLog);
        scrollLog.setBorder(BorderFactory.createLineBorder(SwingThemeHelper.BORDER_SUBTLE, 1, true));
        card.add(scrollLog, BorderLayout.CENTER);

        return card;
    }

    private JLabel rotulo(String texto) {
        JLabel l = new JLabel(texto);
        l.setForeground(SwingThemeHelper.TEXT_PRIMARY);
        return l;
    }

    public void atualizarModoOrigem() {
        ModoOrigem modo = getModoOrigem();
        boolean usaCidr = modo == ModoOrigem.FAIXA_CIDR;
        txtScanCidr.setVisible(usaCidr);
        lblDicaOrigem.setVisible(!usaCidr);
        lblDicaOrigem.setText(modo == ModoOrigem.SCAN_AUTOMATICO
                ? "Detecta sua sub-rede local e filtra produtos Intelbras."
                : "Usa apenas os IPs marcados na aba Equipamentos.");
        btnApenasEscanear.setEnabled(modo != ModoOrigem.SOMENTE_LISTA && btnExecutarTudo.isEnabled());
        btnExecutarTudo.setText(modo == ModoOrigem.SOMENTE_LISTA ? "Coletar Lista" : "Escanear e Coletar");
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

    public JTextField getTxtScanCidr() {
        return txtScanCidr;
    }

    public JPasswordField getTxtSenha() {
        return txtSenha;
    }

    public JTextField getTxtUsuario() {
        return txtUsuario;
    }

    public List<CredencialAcesso> getCredenciaisAlternativas() {
        return List.copyOf(credenciaisAlternativas);
    }

    private void abrirDialogCredenciaisExtras() {
        List<CredencialAcesso> editadas = CredenciaisExtrasDialog.exibirDialogo(this, credenciaisAlternativas);
        if (editadas != null) {
            credenciaisAlternativas.clear();
            credenciaisAlternativas.addAll(editadas);
            atualizarRotuloCredenciaisExtras();
        }
    }

    private void atualizarRotuloCredenciaisExtras() {
        btnCredenciaisExtras.setText("Credenciais extras (" + credenciaisAlternativas.size() + ")");
    }

    public void setEstadoExecucao(boolean executando) {
        cmbOrigem.setEnabled(!executando);
        txtScanCidr.setEnabled(!executando);
        txtUsuario.setEnabled(!executando);
        txtSenha.setEnabled(!executando);
        btnCredenciaisExtras.setEnabled(!executando);
        txtPastaPdf.setEnabled(!executando);
        btnEscolherPasta.setEnabled(!executando);
        btnExecutarTudo.setEnabled(!executando);
        btnApenasEscanear.setEnabled(!executando && getModoOrigem() != ModoOrigem.SOMENTE_LISTA);
        btnCancelar.setEnabled(executando);
    }

    public void prepararNovaExecucao(String tituloEtapa, boolean indeterminado) {
        this.ultimoPdfGerado = null;
        faixaConclusao.setVisible(false);
        lblStatusExecucao.setText(tituloEtapa);
        progressBar.setIndeterminate(indeterminado);
        progressBar.setValue(0);
        progressBar.setString(indeterminado ? tituloEtapa : "0%");
    }

    public void atualizarProgresso(int atual, int total, String textoStatus) {
        progressBar.setIndeterminate(false);
        int max = Math.max(1, total);
        int pct = Math.min(100, (int) Math.round((atual * 100.0) / max));
        progressBar.setValue(pct);
        progressBar.setString(atual + " / " + total + " (" + pct + "%)");
        lblStatusExecucao.setText(textoStatus);
    }

    public void definirStatusFinal(String textoResumo, int pct, String textoBarra) {
        progressBar.setIndeterminate(false);
        progressBar.setValue(pct);
        progressBar.setString(textoBarra);
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
        if (possuiLoopCritico) {
            faixaConclusao.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 10; background: #472428; border: 1,1,1,1,#803D44,1,10");
            lblResumoConclusao.setForeground(SwingThemeHelper.STATUS_ERR_FG);
        } else if (possuiAlertaParcial) {
            faixaConclusao.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 10; background: #40331D; border: 1,1,1,1,#785D2F,1,10");
            lblResumoConclusao.setForeground(SwingThemeHelper.STATUS_WARN_FG);
        } else {
            faixaConclusao.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 10; background: #22352C; border: 1,1,1,1,#36614A,1,10");
            lblResumoConclusao.setForeground(SwingThemeHelper.STATUS_OK_FG);
        }
        lblResumoConclusao.setText(texto);
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

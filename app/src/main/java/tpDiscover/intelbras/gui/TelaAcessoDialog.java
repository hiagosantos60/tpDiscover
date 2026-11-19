package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.gui.SwingThemeHelper.IconKind;
import tpDiscover.intelbras.seguranca.ControleAcesso;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.Timer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Tela de senha exibida antes da janela principal. Fecha o aplicativo ao cancelar ou depois de
 * {@link ControleAcesso#MAX_TENTATIVAS} tentativas erradas.
 *
 * <p>É uma janela própria (JFrame), e não um diálogo sem dono: assim aparece na barra de tarefas
 * do Windows e vem para a frente ao abrir. Um JDialog sem janela-mãe ficava escondido atrás das
 * outras janelas, e parecia que o aplicativo não tinha aberto.</p>
 */
public final class TelaAcessoDialog extends JFrame {

    private final JPasswordField txtSenha = new JPasswordField(22);
    private final JLabel lblErro = new JLabel(" ");
    private final JButton btnEntrar = new JButton("Entrar");
    private final JButton btnSair = new JButton("Sair");
    private boolean liberado;
    private int tentativas;
    private SecondaryLoop espera;

    private TelaAcessoDialog() {
        super("tpDiscover Intelbras - Acesso");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setIconImage(iconeJanela());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dispose();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                if (espera != null) espera.exit();
            }

            @Override
            public void windowOpened(WindowEvent e) {
                trazerParaFrente();
            }
        });

        JPanel raiz = new JPanel(new BorderLayout(0, 14));
        raiz.setBackground(SwingThemeHelper.BG_CARD);
        raiz.setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));

        JLabel titulo = new JLabel("Acesso restrito", SwingThemeHelper.criarIcone(IconKind.KEY, SwingThemeHelper.ACCENT_PRIMARY, 18), JLabel.LEFT);
        titulo.setIconTextGap(10);
        titulo.setForeground(SwingThemeHelper.TEXT_PRIMARY);
        titulo.setFont(SwingThemeHelper.fonteSemibold(titulo.getFont().getSize2D() + 4f));
        JLabel dica = SwingThemeHelper.criarTextoAjuda("Informe a senha de acesso para usar o tpDiscover.");
        JPanel topo = new JPanel(new GridLayout(2, 1, 0, 4));
        topo.setOpaque(false);
        topo.add(titulo);
        topo.add(dica);
        raiz.add(topo, BorderLayout.NORTH);

        txtSenha.putClientProperty("JTextField.placeholderText", "Senha");
        txtSenha.putClientProperty("JPasswordField.cutCopyAllowed", false);
        lblErro.setForeground(SwingThemeHelper.STATUS_ERR_FG);
        JPanel centro = new JPanel(new BorderLayout(0, 6));
        centro.setOpaque(false);
        centro.add(txtSenha, BorderLayout.NORTH);
        centro.add(lblErro, BorderLayout.SOUTH);
        raiz.add(centro, BorderLayout.CENTER);

        SwingThemeHelper.estilizarBotaoPrimario(btnEntrar);
        SwingThemeHelper.estilizarBotaoSecundario(btnSair);
        JPanel acoes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        acoes.setOpaque(false);
        acoes.add(btnSair);
        acoes.add(btnEntrar);
        raiz.add(acoes, BorderLayout.SOUTH);

        btnEntrar.addActionListener(e -> verificar());
        txtSenha.addActionListener(e -> verificar());
        btnSair.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(btnEntrar);

        setContentPane(raiz);
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
    }

    private void verificar() {
        char[] senha = txtSenha.getPassword();
        txtSenha.setText("");
        definirOcupado(true);
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                boolean ok = ControleAcesso.senhaCorreta(senha);
                if (!ok) ControleAcesso.aguardarAposErro(tentativas + 1);
                return ok;
            }

            @Override
            protected void done() {
                boolean ok;
                try {
                    ok = get();
                } catch (Exception e) {
                    ok = false;
                }
                if (ok) {
                    liberado = true;
                    dispose();
                    return;
                }
                tentativas++;
                int restantes = ControleAcesso.MAX_TENTATIVAS - tentativas;
                if (restantes <= 0) {
                    dispose();
                    return;
                }
                lblErro.setText("Senha incorreta. " + restantes + (restantes == 1 ? " tentativa restante." : " tentativas restantes."));
                definirOcupado(false);
                txtSenha.requestFocusInWindow();
            }
        }.execute();
    }

    private void definirOcupado(boolean ocupado) {
        btnEntrar.setEnabled(!ocupado);
        txtSenha.setEnabled(!ocupado);
        if (ocupado) {
            lblErro.setForeground(SwingThemeHelper.TEXT_MUTED);
            lblErro.setText("Verificando...");
        } else {
            lblErro.setForeground(SwingThemeHelper.STATUS_ERR_FG);
        }
    }

    /**
     * Mostra a tela e aguarda (sem travar a interface) até a senha correta, "Sair" ou o limite de
     * tentativas. Retorna true somente com a senha correta.
     */
    public static boolean solicitarAcesso() {
        TelaAcessoDialog tela = new TelaAcessoDialog();
        tela.espera = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        tela.setVisible(true);
        tela.trazerParaFrente();
        tela.espera.enter();
        return tela.liberado;
    }

    /** No Windows, uma janela nova pode abrir atrás das outras: força ficar na frente ao abrir. */
    private void trazerParaFrente() {
        setAlwaysOnTop(true);
        toFront();
        requestFocus();
        txtSenha.requestFocusInWindow();
        Timer soltar = new Timer(600, e -> setAlwaysOnTop(false));
        soltar.setRepeats(false);
        soltar.start();
    }

    /** Ícone da janela (chave) para a barra de tarefas. */
    static BufferedImage iconeJanela() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(SwingThemeHelper.ACCENT_PRIMARY);
        g.fillRoundRect(0, 0, 32, 32, 8, 8);
        SwingThemeHelper.criarIcone(IconKind.NETWORK, java.awt.Color.WHITE, 20).paintIcon(null, g, 6, 6);
        g.dispose();
        return img;
    }
}

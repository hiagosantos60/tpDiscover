package tpDiscover.intelbras.gui;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Diálogo modal de formulário no padrão STR, pensado para uso só pelo teclado:
 * <ul>
 *     <li>abre com o cursor no campo indicado (não no botão OK);</li>
 *     <li>Enter em qualquer campo confirma com a ação principal; Esc cancela;</li>
 *     <li>Tab percorre os campos e depois os botões, na ordem visual;</li>
 *     <li>erros de validação aparecem dentro do diálogo, sem outra janela por cima.</li>
 * </ul>
 */
public final class DialogoFormulario {

    /** Botão do rodapé. A primeira ação marcada como principal é a do Enter. */
    public static final class Acao {
        final String texto;
        final String id;
        final boolean principal;
        final boolean valida;
        final int mnemonico;

        private Acao(String texto, String id, boolean principal, boolean valida, int mnemonico) {
            this.texto = texto;
            this.id = id;
            this.principal = principal;
            this.valida = valida;
            this.mnemonico = mnemonico;
        }

        /** Ação principal (botão verde, Enter). Passa pela validação. */
        public static Acao principal(String texto, String id) {
            return new Acao(texto, id, true, true, 0);
        }

        /** Ação secundária que também aplica o formulário (passa pela validação). */
        public static Acao secundaria(String texto, String id, int mnemonico) {
            return new Acao(texto, id, false, true, mnemonico);
        }

        /** Cancelar (Esc): fecha sem validar. */
        public static Acao cancelar() {
            return new Acao("Cancelar", null, false, false, 0);
        }
    }

    private final JDialog dialogo;
    private final JLabel lblErro = new JLabel(" ");
    private String escolha;

    private DialogoFormulario(Component pai, String titulo, JComponent conteudo, JComponent focoInicial,
                              List<Acao> acoes, Function<String, String> validador) {
        Window dono = pai == null ? null : (pai instanceof Window ? (Window) pai : SwingUtilities.getWindowAncestor(pai));
        dialogo = new JDialog(dono, titulo, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialogo.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialogo.setIconImages(SwingThemeHelper.iconesJanela());

        JPanel raiz = new JPanel(new BorderLayout(0, 14));
        raiz.setBackground(SwingThemeHelper.BG_CARD);
        raiz.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));
        transparente(conteudo);

        JPanel centro = new JPanel(new BorderLayout(0, 8));
        centro.setOpaque(false);
        centro.add(conteudo, BorderLayout.CENTER);
        lblErro.setForeground(SwingThemeHelper.STATUS_ERR_FG);
        lblErro.setVisible(false);
        centro.add(lblErro, BorderLayout.SOUTH);
        raiz.add(centro, BorderLayout.CENTER);

        JPanel rodape = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rodape.setOpaque(false);
        List<JButton> botoes = new ArrayList<>();
        JButton padrao = null;
        // Ordem visual: secundárias, Cancelar, principal por último (à direita)
        List<Acao> ordenadas = new ArrayList<>();
        for (Acao a : acoes) if (!a.principal) ordenadas.add(a);
        for (Acao a : acoes) if (a.principal) ordenadas.add(a);
        for (Acao a : ordenadas) {
            JButton b = new JButton(a.texto);
            if (a.principal) {
                SwingThemeHelper.estilizarBotaoPrimario(b);
                if (padrao == null) padrao = b;
            } else {
                SwingThemeHelper.estilizarBotaoSecundario(b);
            }
            if (a.mnemonico != 0) b.setMnemonic(a.mnemonico);
            b.addActionListener(e -> concluir(a, validador));
            botoes.add(b);
            rodape.add(b);
        }
        raiz.add(rodape, BorderLayout.SOUTH);
        if (padrao != null) dialogo.getRootPane().setDefaultButton(padrao);

        // Esc cancela de qualquer lugar do diálogo
        dialogo.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "tp.cancelar");
        dialogo.getRootPane().getActionMap().put("tp.cancelar", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                escolha = null;
                dialogo.dispose();
            }
        });

        dialogo.setContentPane(raiz);
        dialogo.pack();
        dialogo.setResizable(false);
        dialogo.setLocationRelativeTo(dono);
        if (focoInicial != null) {
            dialogo.addWindowListener(new WindowAdapter() {
                @Override
                public void windowOpened(WindowEvent e) {
                    focoInicial.requestFocusInWindow();
                    if (focoInicial instanceof JTextComponent) ((JTextComponent) focoInicial).selectAll();
                }
            });
        }
    }

    private void concluir(Acao a, Function<String, String> validador) {
        if (a.valida && validador != null) {
            String erro = validador.apply(a.id);
            if (erro != null) {
                mostrarErro(erro);
                return;
            }
        }
        escolha = a.valida ? a.id : null;
        dialogo.dispose();
    }

    private void mostrarErro(String erro) {
        lblErro.setText(erro);
        lblErro.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.ALERT, SwingThemeHelper.STATUS_ERR_FG));
        lblErro.setIconTextGap(6);
        lblErro.setVisible(true);
        dialogo.pack();
    }

    /**
     * Mostra o diálogo e devolve o id da ação escolhida (ou {@code null} se cancelado/fechado).
     *
     * @param validador recebe o id da ação; devolve a mensagem de erro (o diálogo continua aberto) ou
     *                  {@code null} para fechar. Pode focar/marcar o campo com problema.
     */
    public static String exibir(Component pai, String titulo, JComponent conteudo, JComponent focoInicial,
                                List<Acao> acoes, Function<String, String> validador) {
        DialogoFormulario d = new DialogoFormulario(pai, titulo, conteudo, focoInicial, acoes, validador);
        d.dialogo.setVisible(true);
        return d.escolha;
    }

    /** Painéis e caixas de seleção sem fundo próprio (o fundo branco do diálogo aparece por trás). */
    static void transparente(Component c) {
        if (c instanceof JPanel || c instanceof JRadioButton || c instanceof JCheckBox) {
            ((JComponent) c).setOpaque(false);
        }
        if (c instanceof Container && !(c instanceof AbstractButton)) {
            for (Component f : ((Container) c).getComponents()) transparente(f);
        }
    }
}

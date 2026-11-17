package tpDiscover.intelbras.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Configuração visual FlatLaf Dark multiplataforma (Windows/Linux/macOS),
 * paleta escura consistente, ícones vetoriais Java2D e utilitários de estilo.
 */
public final class SwingThemeHelper {

    public static final Color BG_WINDOW = new Color(39, 42, 48);      // #272A30
    public static final Color BG_HEADER = new Color(31, 33, 38);      // #1F2126
    public static final Color BG_CARD = new Color(47, 51, 58);        // #2F333A
    public static final Color BG_CONSOLE = new Color(25, 27, 31);     // #191B1F
    public static final Color BG_TABLE_ALT = new Color(43, 47, 53);   // #2B2F35
    public static final Color BORDER_SUBTLE = new Color(64, 69, 79);  // #40454F

    public static final Color TEXT_PRIMARY = new Color(232, 236, 242);
    public static final Color TEXT_MUTED = new Color(152, 160, 174);

    public static final Color ACCENT_PRIMARY = new Color(53, 116, 240); // #3574F0
    public static final Color ACCENT_PRIMARY_TEXT = Color.WHITE;

    public static final Color STATUS_OK_BG = new Color(30, 62, 45);
    public static final Color STATUS_OK_FG = new Color(105, 219, 148);
    public static final Color STATUS_WARN_BG = new Color(66, 52, 28);
    public static final Color STATUS_WARN_FG = new Color(250, 198, 92);
    public static final Color STATUS_ERR_BG = new Color(72, 34, 38);
    public static final Color STATUS_ERR_FG = new Color(248, 128, 134);
    public static final Color STATUS_RUN_BG = new Color(31, 53, 84);
    public static final Color STATUS_RUN_FG = new Color(122, 180, 255);
    public static final Color STATUS_IDLE_BG = new Color(52, 56, 64);
    public static final Color STATUS_IDLE_FG = new Color(172, 180, 192);

    public enum IconKind {
        SEARCH, PLAY, STOP, REFRESH, PDF, FOLDER, PLUS, MINUS, CHECK, ALERT, CLEAR, NETWORK, LIST, SLIDERS, COPY
    }

    private SwingThemeHelper() {
    }

    public static void aplicarTemaMultiplataforma() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            System.setProperty("apple.awt.application.name", "tpDiscover");
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
        }
        try {
            UIManager.put("Component.arc", 8);
            UIManager.put("Button.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ProgressBar.arc", 8);
            UIManager.put("TabbedPane.showTabSeparators", false);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("Panel.background", BG_WINDOW);
            UIManager.put("Table.background", BG_CARD);
            UIManager.put("Table.alternateRowColor", BG_TABLE_ALT);
            UIManager.put("Table.foreground", TEXT_PRIMARY);
            UIManager.put("Table.gridColor", BORDER_SUBTLE);
            UIManager.put("TableHeader.background", BG_HEADER);
            UIManager.put("TableHeader.foreground", TEXT_MUTED);
            UIManager.put("TableHeader.separatorColor", BORDER_SUBTLE);
            UIManager.put("Separator.foreground", BORDER_SUBTLE);
            FlatDarkLaf.setup();
        } catch (Exception ignored) {
        }
    }

    public static JPanel criarCardArredondado() {
        JPanel p = new JPanel();
        p.setBackground(BG_CARD);
        p.putClientProperty(FlatClientProperties.STYLE,
                "arc: 12; background: #2F333A; border: 1,1,1,1,#40454F,1,12");
        p.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        return p;
    }

    public static JLabel criarTituloSecao(String texto) {
        JLabel lbl = new JLabel(texto);
        lbl.setForeground(TEXT_PRIMARY);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 13.5f));
        return lbl;
    }

    public static void estilizarBotaoPrimario(JButton btn) {
        btn.setFont(btn.getFont().deriveFont(Font.BOLD));
        btn.putClientProperty(FlatClientProperties.STYLE,
                "background: #3574F0; foreground: #FFFFFF; borderWidth: 0; focusWidth: 0; "
                        + "hoverBackground: #4683F7; pressedBackground: #2960CC; "
                        + "disabledBackground: #394458; disabledText: #8B95A6; margin: 7,16,7,16");
    }

    public static void estilizarBotaoSecundario(JButton btn) {
        btn.putClientProperty(FlatClientProperties.STYLE,
                "background: #3A3F48; foreground: #E8ECF2; borderColor: #4C525E; "
                        + "hoverBackground: #454B55; margin: 7,14,7,14");
    }

    public static void estilizarBotaoPerigo(JButton btn) {
        btn.putClientProperty(FlatClientProperties.STYLE,
                "background: #4A282C; foreground: #F88086; borderColor: #73393F; "
                        + "hoverBackground: #5D3136; disabledBackground: #2F333A; margin: 7,14,7,14");
    }

    public static void estilizarNavTab(JToggleButton btn) {
        btn.setFocusPainted(false);
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 8; borderWidth: 0; focusWidth: 0; margin: 6,12,6,12; "
                        + "background: #1F2126; foreground: #98A0AE; "
                        + "hoverBackground: #2B2E35; "
                        + "selectedBackground: #323741; selectedForeground: #FFFFFF");
    }

    public static void configurarPlaceholder(JTextField field, String placeholder) {
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
    }

    public static void configurarCampoSenhaComOlho(JPasswordField field, String placeholder) {
        field.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true");
        if (placeholder != null && !placeholder.isBlank()) {
            field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        }
    }

    public static Font fonteMonoespacada() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String nome = os.contains("win") ? "Consolas" : (os.contains("mac") ? "Menlo" : Font.MONOSPACED);
        return new Font(nome, Font.PLAIN, 12);
    }

    public static Icon criarIcone(IconKind kind, Color cor) {
        return new VectorIcon(kind, cor, 14);
    }

    public static void abrirCaminhoSistema(Component parent, Path caminho, boolean pasta) {
        if (caminho == null || !Files.exists(caminho)) {
            JOptionPane.showMessageDialog(parent,
                    "Caminho não encontrado: " + caminho,
                    "Aviso",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(caminho.toFile());
                return;
            }
        } catch (Exception ignored) {
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("linux")) {
                new ProcessBuilder("xdg-open", caminho.toAbsolutePath().toString()).start();
                return;
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", caminho.toAbsolutePath().toString()).start();
                return;
            } else if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "", caminho.toAbsolutePath().toString()).start();
                return;
            }
        } catch (IOException ignored) {
        }

        JOptionPane.showMessageDialog(parent,
                (pasta ? "Pasta disponível em:\n" : "Arquivo disponível em:\n") + caminho.toAbsolutePath(),
                "Abrir manualmente",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private record VectorIcon(IconKind kind, Color color, int size) implements Icon {

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                Color drawColor = c != null && !c.isEnabled() ? TEXT_MUTED : color;
                g2.setColor(drawColor);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                switch (kind) {
                    case SEARCH -> {
                        g2.drawOval(2, 2, 7, 7);
                        g2.drawLine(8, 8, 12, 12);
                    }
                    case PLAY -> {
                        Path2D tri = new Path2D.Double();
                        tri.moveTo(3, 2);
                        tri.lineTo(12, 7);
                        tri.lineTo(3, 12);
                        tri.closePath();
                        g2.fill(tri);
                    }
                    case STOP -> g2.fillRoundRect(3, 3, 8, 8, 2, 2);
                    case REFRESH -> {
                        g2.drawArc(2, 2, 10, 10, 40, 280);
                        g2.drawLine(10, 2, 12, 5);
                        g2.drawLine(12, 5, 9, 5);
                    }
                    case PDF -> {
                        g2.drawRoundRect(2, 1, 9, 12, 2, 2);
                        g2.drawLine(4, 5, 9, 5);
                        g2.drawLine(4, 8, 9, 8);
                        g2.drawLine(4, 10, 7, 10);
                    }
                    case FOLDER -> {
                        Path2D p = new Path2D.Double();
                        p.moveTo(1, 3);
                        p.lineTo(5, 3);
                        p.lineTo(7, 5);
                        p.lineTo(13, 5);
                        p.lineTo(13, 12);
                        p.lineTo(1, 12);
                        p.closePath();
                        g2.draw(p);
                    }
                    case PLUS -> {
                        g2.drawLine(7, 2, 7, 12);
                        g2.drawLine(2, 7, 12, 7);
                    }
                    case MINUS -> g2.drawLine(2, 7, 12, 7);
                    case CHECK -> {
                        g2.drawLine(2, 7, 5, 11);
                        g2.drawLine(5, 11, 12, 3);
                    }
                    case ALERT -> {
                        Path2D tri = new Path2D.Double();
                        tri.moveTo(7, 2);
                        tri.lineTo(13, 12);
                        tri.lineTo(1, 12);
                        tri.closePath();
                        g2.draw(tri);
                        g2.drawLine(7, 5, 7, 8);
                        g2.fillOval(6, 10, 2, 2);
                    }
                    case CLEAR -> {
                        g2.drawLine(3, 3, 11, 11);
                        g2.drawLine(11, 3, 3, 11);
                    }
                    case NETWORK -> {
                        g2.drawOval(5, 1, 4, 4);
                        g2.drawOval(1, 9, 4, 4);
                        g2.drawOval(9, 9, 4, 4);
                        g2.drawLine(7, 5, 7, 7);
                        g2.drawLine(3, 9, 7, 7);
                        g2.drawLine(11, 9, 7, 7);
                    }
                    case LIST -> {
                        g2.drawLine(4, 3, 12, 3);
                        g2.drawLine(4, 7, 12, 7);
                        g2.drawLine(4, 11, 12, 11);
                        g2.fillOval(1, 2, 2, 2);
                        g2.fillOval(1, 6, 2, 2);
                        g2.fillOval(1, 10, 2, 2);
                    }
                    case SLIDERS -> {
                        g2.drawLine(2, 4, 12, 4);
                        g2.drawLine(2, 10, 12, 10);
                        g2.fillOval(4, 2, 4, 4);
                        g2.fillOval(8, 8, 4, 4);
                    }
                    case COPY -> {
                        g2.drawRect(4, 4, 8, 8);
                        g2.drawLine(2, 2, 9, 2);
                        g2.drawLine(2, 2, 2, 9);
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

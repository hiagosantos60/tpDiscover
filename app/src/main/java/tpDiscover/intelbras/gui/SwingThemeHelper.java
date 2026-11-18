package tpDiscover.intelbras.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
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
 * Tema visual do tpDiscover: FlatLaf Dark com paleta própria (grafite + azul),
 * tipografia levemente maior, ícones vetoriais Java2D e utilitários de estilo
 * reutilizados por todas as abas (assim nenhuma tela define cores soltas).
 */
public final class SwingThemeHelper {

    // ---- Superfícies ----
    public static final Color BG_WINDOW = new Color(0x15171C);
    public static final Color BG_HEADER = new Color(0x101216);
    public static final Color BG_CARD = new Color(0x1D2027);
    public static final Color BG_INPUT = new Color(0x14161B);
    public static final Color BG_CONSOLE = new Color(0x0F1114);
    public static final Color BG_TABLE_ALT = new Color(0x21252D);
    public static final Color BG_HOVER = new Color(0x272B34);
    public static final Color BORDER_SUBTLE = new Color(0x2B303A);
    public static final Color BORDER_STRONG = new Color(0x3A404C);

    // ---- Texto ----
    public static final Color TEXT_PRIMARY = new Color(0xE8EBF1);
    public static final Color TEXT_SECONDARY = new Color(0xB4BAC6);
    public static final Color TEXT_MUTED = new Color(0x828A9A);

    // ---- Destaque ----
    public static final Color ACCENT_PRIMARY = new Color(0x3B82F6);
    public static final Color ACCENT_HOVER = new Color(0x5B96F8);
    public static final Color ACCENT_PRESSED = new Color(0x2563EB);
    public static final Color ACCENT_PRIMARY_TEXT = Color.WHITE;

    // ---- Estados ----
    public static final Color STATUS_OK_BG = new Color(0x15301F);
    public static final Color STATUS_OK_FG = new Color(0x5EE08F);
    public static final Color STATUS_OK_BORDER = new Color(0x255B3A);
    public static final Color STATUS_WARN_BG = new Color(0x352A12);
    public static final Color STATUS_WARN_FG = new Color(0xFBC75A);
    public static final Color STATUS_WARN_BORDER = new Color(0x6B5321);
    public static final Color STATUS_ERR_BG = new Color(0x3A1A1E);
    public static final Color STATUS_ERR_FG = new Color(0xFF8A90);
    public static final Color STATUS_ERR_BORDER = new Color(0x7A3239);
    public static final Color STATUS_RUN_BG = new Color(0x172A47);
    public static final Color STATUS_RUN_FG = new Color(0x82B6FF);
    public static final Color STATUS_RUN_BORDER = new Color(0x2B4F85);
    public static final Color STATUS_IDLE_BG = new Color(0x262A32);
    public static final Color STATUS_IDLE_FG = new Color(0xA9B0BD);
    public static final Color STATUS_IDLE_BORDER = new Color(0x363B46);

    public enum IconKind {
        SEARCH, PLAY, STOP, REFRESH, PDF, FOLDER, PLUS, MINUS, CHECK, ALERT, CLEAR, NETWORK, LIST, SLIDERS, COPY,
        KEY, FILE, CHECKBOX
    }

    public enum TipoStatus { IDLE, RUN, OK, WARN, ERR }

    private SwingThemeHelper() {
    }

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static void aplicarTemaMultiplataforma() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            System.setProperty("apple.awt.application.name", "tpDiscover");
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
        }
        try {
            FlatDarkLaf.setup();

            Font base = UIManager.getFont("defaultFont");
            if (base != null) {
                UIManager.put("defaultFont", base.deriveFont(Math.max(13.5f, base.getSize2D())));
            }

            UIManager.put("Component.arc", 10);
            UIManager.put("Button.arc", 10);
            UIManager.put("TextComponent.arc", 10);
            UIManager.put("ProgressBar.arc", 999);
            UIManager.put("CheckBox.arc", 6);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("Component.innerFocusWidth", 0);
            UIManager.put("Component.focusColor", ACCENT_PRIMARY);
            UIManager.put("Component.focusedBorderColor", ACCENT_PRIMARY);
            UIManager.put("Component.borderColor", BORDER_STRONG);
            UIManager.put("Component.disabledBorderColor", BORDER_SUBTLE);
            UIManager.put("Component.accentColor", ACCENT_PRIMARY);

            UIManager.put("Panel.background", BG_WINDOW);
            UIManager.put("TextField.background", BG_INPUT);
            UIManager.put("PasswordField.background", BG_INPUT);
            UIManager.put("FormattedTextField.background", BG_INPUT);
            UIManager.put("Spinner.background", BG_INPUT);
            UIManager.put("ComboBox.background", BG_INPUT);
            UIManager.put("ComboBox.buttonBackground", BG_INPUT);
            UIManager.put("TextComponent.placeholderForeground", TEXT_MUTED);
            UIManager.put("TextField.margin", new Insets(6, 10, 6, 10));
            UIManager.put("PasswordField.margin", new Insets(6, 10, 6, 10));
            UIManager.put("ComboBox.padding", new Insets(5, 8, 5, 8));

            UIManager.put("Button.background", BG_HOVER);
            UIManager.put("Button.borderColor", BORDER_STRONG);
            UIManager.put("Button.default.background", ACCENT_PRIMARY);
            UIManager.put("Button.default.foreground", Color.WHITE);

            UIManager.put("ProgressBar.foreground", ACCENT_PRIMARY);
            UIManager.put("ProgressBar.background", BG_INPUT);
            UIManager.put("ProgressBar.selectionForeground", Color.WHITE);
            UIManager.put("ProgressBar.selectionBackground", TEXT_PRIMARY);

            UIManager.put("ScrollBar.width", 10);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("ScrollBar.track", BG_CARD);
            UIManager.put("ScrollPane.background", BG_CARD);

            UIManager.put("Table.background", BG_CARD);
            UIManager.put("Table.foreground", TEXT_PRIMARY);
            UIManager.put("Table.gridColor", BORDER_SUBTLE);
            UIManager.put("Table.selectionBackground", new Color(0x23406E));
            UIManager.put("Table.selectionForeground", Color.WHITE);
            UIManager.put("Table.selectionInactiveBackground", new Color(0x2A3242));
            UIManager.put("Table.cellMargins", new Insets(2, 10, 2, 10));
            UIManager.put("TableHeader.background", BG_HEADER);
            UIManager.put("TableHeader.foreground", TEXT_MUTED);
            UIManager.put("TableHeader.separatorColor", BG_HEADER);
            UIManager.put("TableHeader.bottomSeparatorColor", BORDER_SUBTLE);
            UIManager.put("TableHeader.height", 34);
            UIManager.put("TableHeader.cellMargins", new Insets(4, 10, 4, 10));

            UIManager.put("TabbedPane.showTabSeparators", false);
            UIManager.put("TabbedPane.tabHeight", 38);
            UIManager.put("TabbedPane.underlineColor", ACCENT_PRIMARY);
            UIManager.put("TabbedPane.inactiveUnderlineColor", ACCENT_PRIMARY);
            UIManager.put("TabbedPane.tabSelectionHeight", 2);
            UIManager.put("TabbedPane.background", BG_CARD);
            UIManager.put("TabbedPane.selectedBackground", BG_CARD);
            UIManager.put("TabbedPane.hoverColor", BG_HOVER);
            UIManager.put("TabbedPane.contentAreaColor", BORDER_SUBTLE);

            UIManager.put("PopupMenu.background", BG_CARD);
            UIManager.put("PopupMenu.borderColor", BORDER_STRONG);
            UIManager.put("MenuItem.background", BG_CARD);
            UIManager.put("MenuItem.selectionBackground", BG_HOVER);
            UIManager.put("MenuItem.margin", new Insets(6, 10, 6, 14));
            UIManager.put("Separator.foreground", BORDER_SUBTLE);
            UIManager.put("ToolTip.background", new Color(0x262A33));
            UIManager.put("ToolTip.foreground", TEXT_PRIMARY);
            UIManager.put("OptionPane.background", BG_CARD);
            UIManager.put("RootPane.background", BG_WINDOW);
            UIManager.put("TitlePane.background", BG_HEADER);
            UIManager.put("Spinner.arrowButtonStyle", "chevron");

            // Reaplica o LaF para que a fonte base maior e os ajustes acima valham em todos os componentes.
            FlatDarkLaf.setup();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ Cards e textos

    public static JPanel criarCardArredondado() {
        JPanel p = new JPanel();
        p.setBackground(BG_CARD);
        p.putClientProperty(FlatClientProperties.STYLE,
                "arc: 14; background: " + hex(BG_CARD) + "; border: 1,1,1,1," + hex(BORDER_SUBTLE) + ",1,14");
        p.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        return p;
    }

    public static JLabel criarTituloSecao(String texto) {
        JLabel lbl = new JLabel(texto);
        lbl.setForeground(TEXT_PRIMARY);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, lbl.getFont().getSize2D() + 2.5f));
        return lbl;
    }

    public static JLabel criarRotuloCampo(String texto) {
        JLabel lbl = new JLabel(texto);
        lbl.setForeground(TEXT_PRIMARY);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        return lbl;
    }

    /** Texto auxiliar discreto (dicas abaixo de campos e subtítulos). */
    public static JLabel criarTextoAjuda(String texto) {
        JLabel l = new JLabel(texto);
        l.setForeground(TEXT_MUTED);
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 0.5f));
        return l;
    }

    /** Cabeçalho padrão de card: título + subtítulo. */
    public static JPanel criarCabecalho(String titulo, String subtitulo) {
        JPanel header = new JPanel(new java.awt.BorderLayout(0, 4));
        header.setOpaque(false);
        header.add(criarTituloSecao(titulo), java.awt.BorderLayout.NORTH);
        if (subtitulo != null && !subtitulo.isBlank()) {
            header.add(criarTextoAjuda(subtitulo), java.awt.BorderLayout.SOUTH);
        }
        return header;
    }

    /** Círculo numerado que indica a etapa de um formulário (1, 2, 3...). */
    public static JLabel criarBadgeEtapa(String numero) {
        JLabel l = new JLabel(numero, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0x1E3A66));
                g2.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                g2.setColor(ACCENT_PRIMARY);
                g2.drawOval(0, 0, getWidth() - 1, getHeight() - 1);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setForeground(STATUS_RUN_FG);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        Dimension d = new Dimension(26, 26);
        l.setPreferredSize(d);
        l.setMinimumSize(d);
        l.setMaximumSize(d);
        return l;
    }

    /** Pinta um painel/label como "faixa" de status (fundo + borda + cor do texto). */
    public static void estilizarFaixa(JComponent c, TipoStatus tipo, int arc) {
        Color[] cores = coresStatus(tipo);
        c.putClientProperty(FlatClientProperties.STYLE,
                "arc: " + arc + "; background: " + hex(cores[0]) + "; border: 1,1,1,1," + hex(cores[2]) + ",1," + arc);
    }

    public static Color corTexto(TipoStatus tipo) {
        return coresStatus(tipo)[1];
    }

    /** {fundo, texto, borda} para cada estado. */
    public static Color[] coresStatus(TipoStatus tipo) {
        return switch (tipo) {
            case RUN -> new Color[]{STATUS_RUN_BG, STATUS_RUN_FG, STATUS_RUN_BORDER};
            case OK -> new Color[]{STATUS_OK_BG, STATUS_OK_FG, STATUS_OK_BORDER};
            case WARN -> new Color[]{STATUS_WARN_BG, STATUS_WARN_FG, STATUS_WARN_BORDER};
            case ERR -> new Color[]{STATUS_ERR_BG, STATUS_ERR_FG, STATUS_ERR_BORDER};
            default -> new Color[]{STATUS_IDLE_BG, STATUS_IDLE_FG, STATUS_IDLE_BORDER};
        };
    }

    /** Badge arredondado do cabeçalho, colorido conforme o estado. */
    public static void estilizarBadgeStatus(JLabel badge, TipoStatus tipo) {
        Color[] cores = coresStatus(tipo);
        badge.setOpaque(true);
        badge.putClientProperty(FlatClientProperties.STYLE,
                "arc: 999; background: " + hex(cores[0]) + "; border: 5,14,5,14," + hex(cores[2]) + ",1,999");
        badge.setForeground(cores[1]);
        badge.setIcon(new DotIcon(cores[1]));
        badge.setIconTextGap(8);
    }

    // ------------------------------------------------------------------ Botões

    public static void estilizarBotaoPrimario(JButton btn) {
        btn.setFont(btn.getFont().deriveFont(Font.BOLD));
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10; background: " + hex(ACCENT_PRIMARY) + "; foreground: #FFFFFF; borderWidth: 0; focusWidth: 0; "
                        + "hoverBackground: " + hex(ACCENT_HOVER) + "; pressedBackground: " + hex(ACCENT_PRESSED) + "; "
                        + "disabledBackground: #263149; disabledText: #7D8799; margin: 8,18,8,18");
    }

    public static void estilizarBotaoSecundario(JButton btn) {
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10; background: " + hex(BG_HOVER) + "; foreground: " + hex(TEXT_PRIMARY) + "; "
                        + "borderColor: " + hex(BORDER_STRONG) + "; focusWidth: 0; "
                        + "hoverBackground: #303541; pressedBackground: #353B48; margin: 8,16,8,16");
    }

    /** Botão secundário compacto (barras de ferramentas). */
    public static void estilizarBotaoCompacto(JButton btn) {
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 8; background: " + hex(BG_HOVER) + "; foreground: " + hex(TEXT_SECONDARY) + "; "
                        + "borderColor: " + hex(BORDER_STRONG) + "; focusWidth: 0; "
                        + "hoverBackground: #303541; pressedBackground: #353B48; "
                        + "disabledBackground: " + hex(BG_CARD) + "; disabledText: #5C6370; disabledBorderColor: " + hex(BORDER_SUBTLE) + "; "
                        + "margin: 5,11,5,11");
    }

    /** Botão "fantasma": sem fundo, só texto/ícone (ações discretas como Copiar/Limpar). */
    public static void estilizarBotaoFantasma(JButton btn) {
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 8; foreground: " + hex(TEXT_SECONDARY) + "; hoverBackground: " + hex(BG_HOVER) + "; margin: 4,8,4,8");
    }

    public static void estilizarBotaoPerigo(JButton btn) {
        btn.setFont(btn.getFont().deriveFont(Font.BOLD));
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10; background: " + hex(STATUS_ERR_BG) + "; foreground: " + hex(STATUS_ERR_FG) + "; "
                        + "borderColor: " + hex(STATUS_ERR_BORDER) + "; focusWidth: 0; "
                        + "hoverBackground: #4A2025; disabledBackground: " + hex(BG_CARD) + "; margin: 8,18,8,18");
    }

    /** Abas de navegação do cabeçalho no estilo "sublinhado". */
    public static void estilizarNavTab(JToggleButton btn) {
        btn.setFocusPainted(false);
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TAB);
        btn.putClientProperty(FlatClientProperties.STYLE,
                "focusWidth: 0; margin: 14,14,12,14; "
                        + "background: " + hex(BG_HEADER) + "; foreground: " + hex(TEXT_MUTED) + "; "
                        + "hoverBackground: #181B21; selectedBackground: " + hex(BG_HEADER) + "; "
                        + "selectedForeground: #FFFFFF; tab.underlineColor: " + hex(ACCENT_PRIMARY) + "; "
                        + "tab.underlineHeight: 2; tab.selectedBackground: " + hex(BG_HEADER));
    }

    /** Botão que abre um menu suspenso (agrupa ações relacionadas para limpar a barra de ferramentas). */
    public static JButton criarBotaoMenu(String texto, Icon icone, JMenuItem... itens) {
        JButton btn = new JButton(texto + "  ▾");
        estilizarBotaoCompacto(btn);
        if (icone != null) btn.setIcon(icone);
        JPopupMenu menu = new JPopupMenu();
        for (JMenuItem it : itens) {
            if (it == null) menu.addSeparator();
            else menu.add(it);
        }
        btn.addActionListener(e -> menu.show(btn, 0, btn.getHeight() + 4));
        return btn;
    }

    // ------------------------------------------------------------------ Campos

    public static void configurarPlaceholder(JTextField field, String placeholder) {
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
    }

    public static void configurarCampoSenhaComOlho(JPasswordField field, String placeholder) {
        field.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true");
        if (placeholder != null && !placeholder.isBlank()) {
            field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        }
    }

    /**
     * Destaca um campo com contorno vermelho e foca nele; o destaque some assim que o usuário digita.
     */
    public static void marcarCampoComErro(JTextComponent campo) {
        campo.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
        if (campo.getClientProperty("tp.erroListener") == null) {
            DocumentListener dl = new DocumentListener() {
                private void limpar() {
                    campo.putClientProperty(FlatClientProperties.OUTLINE, null);
                }

                @Override public void insertUpdate(DocumentEvent e) { limpar(); }
                @Override public void removeUpdate(DocumentEvent e) { limpar(); }
                @Override public void changedUpdate(DocumentEvent e) { limpar(); }
            };
            campo.getDocument().addDocumentListener(dl);
            campo.putClientProperty("tp.erroListener", dl);
        }
        campo.requestFocusInWindow();
    }

    public static Font fonteMonoespacada() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String nome = os.contains("win") ? "Consolas" : (os.contains("mac") ? "Menlo" : Font.MONOSPACED);
        return new Font(nome, Font.PLAIN, 12);
    }

    public static Icon criarIcone(IconKind kind, Color cor) {
        return new VectorIcon(kind, cor, 14);
    }

    public static Icon criarIcone(IconKind kind, Color cor, int tamanho) {
        return new VectorIcon(kind, cor, tamanho);
    }

    // ------------------------------------------------------------------ Sistema

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

    // ------------------------------------------------------------------ Ícones

    private record DotIcon(Color color) implements Icon {
        @Override public int getIconWidth() { return 8; }
        @Override public int getIconHeight() { return 8; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y, 8, 8);
            g2.dispose();
        }
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
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.translate(x, y);
                double esc = size / 14.0;
                g2.scale(esc, esc);
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
                        tri.moveTo(3.5, 2);
                        tri.lineTo(12, 7);
                        tri.lineTo(3.5, 12);
                        tri.closePath();
                        g2.fill(tri);
                    }
                    case STOP -> g2.fillRoundRect(3, 3, 8, 8, 3, 3);
                    case REFRESH -> {
                        g2.drawArc(2, 2, 10, 10, 40, 280);
                        g2.drawLine(10, 2, 12, 5);
                        g2.drawLine(12, 5, 9, 5);
                    }
                    case PDF, FILE -> {
                        Path2D p = new Path2D.Double();
                        p.moveTo(3, 1.5);
                        p.lineTo(8.5, 1.5);
                        p.lineTo(11.5, 4.5);
                        p.lineTo(11.5, 12.5);
                        p.lineTo(3, 12.5);
                        p.closePath();
                        g2.draw(p);
                        g2.drawLine(5, 7, 9, 7);
                        g2.drawLine(5, 10, 9, 10);
                    }
                    case FOLDER -> {
                        Path2D p = new Path2D.Double();
                        p.moveTo(1.5, 3);
                        p.lineTo(5, 3);
                        p.lineTo(6.5, 4.5);
                        p.lineTo(12.5, 4.5);
                        p.lineTo(12.5, 11.5);
                        p.lineTo(1.5, 11.5);
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
                        tri.moveTo(7, 1.5);
                        tri.lineTo(13, 12.5);
                        tri.lineTo(1, 12.5);
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
                        g2.drawRoundRect(5, 1, 4, 4, 1, 1);
                        g2.drawRoundRect(1, 9, 4, 4, 1, 1);
                        g2.drawRoundRect(9, 9, 4, 4, 1, 1);
                        g2.drawLine(7, 5, 7, 7);
                        g2.drawLine(3, 7, 11, 7);
                        g2.drawLine(3, 7, 3, 9);
                        g2.drawLine(11, 7, 11, 9);
                    }
                    case LIST -> {
                        g2.drawLine(5, 3, 12, 3);
                        g2.drawLine(5, 7, 12, 7);
                        g2.drawLine(5, 11, 12, 11);
                        g2.fillOval(1, 2, 2, 2);
                        g2.fillOval(1, 6, 2, 2);
                        g2.fillOval(1, 10, 2, 2);
                    }
                    case SLIDERS -> {
                        g2.drawLine(2, 4, 12, 4);
                        g2.drawLine(2, 10, 12, 10);
                        g2.fillOval(3, 2, 4, 4);
                        g2.fillOval(8, 8, 4, 4);
                    }
                    case COPY -> {
                        g2.drawRoundRect(4, 4, 8, 8, 2, 2);
                        g2.drawLine(2, 9, 2, 3);
                        g2.drawLine(2, 2, 9, 2);
                    }
                    case KEY -> {
                        g2.drawOval(1, 4, 6, 6);
                        g2.drawLine(7, 7, 13, 7);
                        g2.drawLine(11, 7, 11, 10);
                        g2.drawLine(13, 7, 13, 9);
                    }
                    case CHECKBOX -> {
                        g2.drawRoundRect(1, 1, 12, 12, 3, 3);
                        g2.drawLine(4, 7, 6, 10);
                        g2.drawLine(6, 10, 10, 4);
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

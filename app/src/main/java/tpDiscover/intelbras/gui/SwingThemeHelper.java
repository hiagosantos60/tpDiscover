package tpDiscover.intelbras.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLightLaf;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
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
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import tpDiscover.intelbras.util.Compat;
/**
 * Tema visual do tpDiscover no padrão visual do STR (telas de produto Intelbras): fundo cinza-claro
 * (canvas) com painéis brancos sem borda, navegação em ardósia e o verde da marca como ponto focal
 * (item ativo, barra dos títulos de painel e botão primário). Âmbar só em pendente/atenção e
 * vermelho só em erro, exclusão e cancelamento. Todas as telas usam estes tokens (nenhuma define
 * cores soltas).
 */
public final class SwingThemeHelper {

    // ---- Superfícies (tokens do STR) ----
    /** --canvas: fundo da janela, visível só entre os painéis. */
    public static final Color BG_WINDOW = new Color(0xEEF1F3);
    /** --nav-bg: barra superior (navegação). */
    public static final Color NAV_BG = new Color(0x3E5055);
    public static final Color NAV_FG = Color.WHITE;
    public static final Color NAV_FG2 = new Color(0xD3DCE0);
    /** --nav-hover: branco a 8% sobre a ardósia. */
    public static final Color NAV_HOVER = new Color(0x4D5E63);
    /** Mantido por compatibilidade: a barra superior usa a cor da navegação. */
    public static final Color BG_HEADER = NAV_BG;
    /** --bg: painel, modal, campo, botão de contorno. */
    public static final Color BG_CARD = Color.WHITE;
    public static final Color BG_INPUT = Color.WHITE;
    /** --bg3: cabeçalho de tabela, hover de linha, campo desabilitado. */
    public static final Color BG_SUBTLE = new Color(0xF5F7F8);
    public static final Color BG_CONSOLE = BG_SUBTLE;
    public static final Color BG_TABLE_ALT = BG_SUBTLE;
    public static final Color BG_HOVER = BG_SUBTLE;
    /** --rule: divisórias de tabela, modal e abas. */
    public static final Color BORDER_SUBTLE = new Color(0xE2E9ED);
    /** --border2: contorno de campos e botões. */
    public static final Color BORDER_STRONG = new Color(0xBBCAD2);

    // ---- Texto ----
    /** --ink / --text: texto principal e títulos (ardósia). */
    public static final Color TEXT_PRIMARY = new Color(0x3E5055);
    /** --text2: rótulos e legendas. */
    public static final Color TEXT_SECONDARY = new Color(0x5E6D75);
    /** --text3: placeholder e texto apagado. */
    public static final Color TEXT_MUTED = new Color(0x5B6970);

    // ---- Marca / destaque ----
    /** --accent: botão primário, item ativo, links, foco (sempre este verde sob texto branco). */
    public static final Color ACCENT_PRIMARY = new Color(0x00863F);
    /** --accent-press: hover do primário (escurece, não clareia). */
    public static final Color ACCENT_HOVER = new Color(0x006F34);
    /** --brand-deep. */
    public static final Color ACCENT_PRESSED = new Color(0x045C2F);
    public static final Color ACCENT_PRIMARY_TEXT = Color.WHITE;
    /** --accent-dim (verde a 10% sobre branco). */
    public static final Color ACCENT_DIM = new Color(0xE6F3EC);
    /** --brand: preenchimentos sem texto (barra do título, progresso). */
    public static final Color BRAND = new Color(0x00A335);
    public static final Color BRAND_LIGHT = new Color(0x87C984);
    /** --slate-dark: segundo indicador e filtro ativo. */
    public static final Color SLATE_DARK = new Color(0x31586C);
    public static final Color SLATE = new Color(0x50788A);
    public static final Color SLATE_LIGHT = new Color(0x6990A7);
    public static final Color SLATE_SOFT = new Color(0xE7EFF3);

    // ---- Estados (etiqueta: fundo translúcido da própria cor e texto na cor cheia) ----
    public static final Color STATUS_OK_BG = ACCENT_DIM;
    public static final Color STATUS_OK_FG = ACCENT_PRIMARY;
    public static final Color STATUS_OK_BORDER = new Color(0xC7E4D5);
    /** Âmbar: só pendente/atenção (--warn / --warn-dim). */
    public static final Color STATUS_WARN_BG = new Color(0xF7EED8);
    public static final Color STATUS_WARN_FG = new Color(0x765A0B);
    public static final Color STATUS_WARN_BORDER = new Color(0xF0DFB4);
    /** --warn-hi: preenchimento de pendente (sem texto). */
    public static final Color WARN_HI = new Color(0xD4A327);
    /** Vermelho: só erro, exclusão e cancelamento (--danger / --danger-dim). */
    public static final Color STATUS_ERR_BG = new Color(0xFAEEEF);
    public static final Color STATUS_ERR_FG = new Color(0xC62533);
    public static final Color STATUS_ERR_BORDER = new Color(0xF4D3D6);
    /** Em andamento: azul-ardósia (--slate-soft / --slate-dark). */
    public static final Color STATUS_RUN_BG = SLATE_SOFT;
    public static final Color STATUS_RUN_FG = SLATE_DARK;
    public static final Color STATUS_RUN_BORDER = new Color(0xC9DCE6);
    /** Neutro (--info-dim / --text2). */
    public static final Color STATUS_IDLE_BG = new Color(0xEFF0F1);
    public static final Color STATUS_IDLE_FG = TEXT_SECONDARY;
    public static final Color STATUS_IDLE_BORDER = new Color(0xDDE3E6);

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
        }
        try {
            FlatLightLaf.setup();

            Font base = UIManager.getFont("defaultFont");
            if (base != null) {
                UIManager.put("defaultFont", base.deriveFont(Math.max(13.5f, base.getSize2D())));
            }

            // Cantos do STR: 3px botão/etiqueta, 4px campo, 6px painel, 8px modal
            UIManager.put("Component.arc", 4);
            UIManager.put("Button.arc", 3);
            UIManager.put("TextComponent.arc", 4);
            UIManager.put("ProgressBar.arc", 999);
            UIManager.put("CheckBox.arc", 3);
            // Foco: borda verde com halo de 3px a 10%
            UIManager.put("Component.focusWidth", 3);
            UIManager.put("Component.innerFocusWidth", 0);
            UIManager.put("Component.focusColor", new Color(0, 134, 63, 26));
            UIManager.put("Component.focusedBorderColor", ACCENT_PRIMARY);
            UIManager.put("Component.borderColor", BORDER_STRONG);
            UIManager.put("Component.disabledBorderColor", BORDER_SUBTLE);
            UIManager.put("Component.accentColor", ACCENT_PRIMARY);
            UIManager.put("Component.error.focusedBorderColor", STATUS_ERR_FG);
            UIManager.put("Component.error.borderColor", STATUS_ERR_FG);

            UIManager.put("Panel.background", BG_WINDOW);
            UIManager.put("Label.foreground", TEXT_PRIMARY);
            UIManager.put("TextField.background", BG_INPUT);
            UIManager.put("PasswordField.background", BG_INPUT);
            UIManager.put("FormattedTextField.background", BG_INPUT);
            UIManager.put("Spinner.background", BG_INPUT);
            UIManager.put("ComboBox.background", BG_INPUT);
            UIManager.put("ComboBox.buttonBackground", BG_INPUT);
            UIManager.put("ComboBox.buttonEditableBackground", BG_INPUT);
            UIManager.put("TextField.inactiveBackground", BG_SUBTLE);
            UIManager.put("TextField.disabledBackground", BG_SUBTLE);
            UIManager.put("PasswordField.disabledBackground", BG_SUBTLE);
            UIManager.put("ComboBox.disabledBackground", BG_SUBTLE);
            UIManager.put("Spinner.disabledBackground", BG_SUBTLE);
            UIManager.put("TextComponent.placeholderForeground", TEXT_MUTED);
            UIManager.put("TextField.foreground", TEXT_PRIMARY);
            UIManager.put("TextArea.foreground", TEXT_PRIMARY);
            UIManager.put("TextField.margin", new Insets(6, 12, 6, 12));
            UIManager.put("PasswordField.margin", new Insets(6, 12, 6, 12));
            UIManager.put("ComboBox.padding", new Insets(5, 10, 5, 10));
            UIManager.put("TextField.selectionBackground", ACCENT_DIM);
            UIManager.put("TextField.selectionForeground", TEXT_PRIMARY);
            UIManager.put("PasswordField.selectionBackground", ACCENT_DIM);
            UIManager.put("PasswordField.selectionForeground", TEXT_PRIMARY);
            UIManager.put("TextArea.selectionBackground", ACCENT_DIM);
            UIManager.put("TextArea.selectionForeground", TEXT_PRIMARY);
            UIManager.put("ComboBox.selectionBackground", ACCENT_DIM);
            UIManager.put("ComboBox.selectionForeground", TEXT_PRIMARY);
            UIManager.put("List.selectionBackground", ACCENT_DIM);
            UIManager.put("List.selectionForeground", TEXT_PRIMARY);

            UIManager.put("Button.background", BG_CARD);
            UIManager.put("Button.foreground", TEXT_PRIMARY);
            UIManager.put("Button.borderColor", BORDER_STRONG);
            UIManager.put("Button.hoverBorderColor", ACCENT_PRIMARY);
            UIManager.put("Button.default.background", ACCENT_PRIMARY);
            UIManager.put("Button.default.foreground", Color.WHITE);
            UIManager.put("Button.default.hoverBackground", ACCENT_HOVER);
            UIManager.put("Button.default.pressedBackground", ACCENT_PRESSED);
            UIManager.put("Button.default.borderWidth", 0);
            // Botão padrão (OK dos diálogos) com foco continua verde com texto branco; antes ficava
            // azul-claro com texto branco (ilegível), calculado pelo FlatLaf a partir da cor de foco.
            UIManager.put("Button.default.focusedBackground", ACCENT_PRIMARY);
            UIManager.put("Button.default.focusedBorderColor", ACCENT_PRIMARY);
            UIManager.put("Button.default.borderColor", ACCENT_PRIMARY);
            UIManager.put("Button.default.hoverBorderColor", ACCENT_HOVER);
            UIManager.put("Button.default.focusColor", new Color(0x00, 0x86, 0x3F, 0x59));
            UIManager.put("Button.focusedBackground", BG_CARD);
            UIManager.put("Button.focusedBorderColor", ACCENT_PRIMARY);
            UIManager.put("Button.hoverBackground", BG_CARD);
            UIManager.put("Button.pressedBackground", ACCENT_DIM);
            UIManager.put("Button.selectedBackground", SLATE_DARK);
            UIManager.put("Button.selectedForeground", Color.WHITE);
            UIManager.put("Button.disabledBackground", BG_SUBTLE);
            UIManager.put("Button.disabledText", new Color(0x8A9BA3));
            UIManager.put("Button.disabledBorderColor", BORDER_SUBTLE);
            UIManager.put("Button.toolbar.hoverBackground", ACCENT_DIM);
            UIManager.put("Button.toolbar.pressedBackground", new Color(0xD3EADD));
            UIManager.put("Button.toolbar.selectedBackground", ACCENT_DIM);
            UIManager.put("ToggleButton.selectedBackground", SLATE_DARK);
            UIManager.put("ToggleButton.selectedForeground", Color.WHITE);
            UIManager.put("ToggleButton.toolbar.selectedBackground", ACCENT_DIM);
            // Enter também aciona o botão que está com foco (além da barra de espaço), como no Windows
            Object[] teclasBotao = {"SPACE", "pressed", "released SPACE", "released",
                    "ENTER", "pressed", "released ENTER", "released"};
            UIManager.put("Button.focusInputMap", new javax.swing.UIDefaults.LazyInputMap(teclasBotao));
            UIManager.put("ToggleButton.focusInputMap", new javax.swing.UIDefaults.LazyInputMap(teclasBotao));
            UIManager.put("CheckBox.icon.checkmarkColor", Color.WHITE);
            UIManager.put("CheckBox.icon.selectedBackground", ACCENT_PRIMARY);
            UIManager.put("CheckBox.icon.selectedBorderColor", ACCENT_PRIMARY);
            UIManager.put("CheckBox.icon.borderColor", BORDER_STRONG);
            UIManager.put("CheckBox.icon.focusedBorderColor", ACCENT_PRIMARY);
            UIManager.put("CheckBox.icon.hoverBorderColor", ACCENT_PRIMARY);

            UIManager.put("ProgressBar.foreground", BRAND);
            UIManager.put("ProgressBar.background", BORDER_SUBTLE);
            UIManager.put("ProgressBar.selectionForeground", TEXT_PRIMARY);
            UIManager.put("ProgressBar.selectionBackground", TEXT_PRIMARY);

            UIManager.put("ScrollBar.width", 10);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("ScrollBar.track", BG_CARD);
            UIManager.put("ScrollBar.thumb", BORDER_STRONG);
            UIManager.put("ScrollBar.hoverThumbColor", SLATE_LIGHT);
            UIManager.put("ScrollPane.background", BG_CARD);

            // Tabela: cabeçalho --bg3 com texto --text2 em peso normal, divisória --rule
            UIManager.put("Table.background", BG_CARD);
            UIManager.put("Table.foreground", TEXT_PRIMARY);
            UIManager.put("Table.gridColor", BORDER_SUBTLE);
            UIManager.put("Table.selectionBackground", ACCENT_DIM);
            UIManager.put("Table.selectionForeground", TEXT_PRIMARY);
            UIManager.put("Table.selectionInactiveBackground", BG_SUBTLE);
            UIManager.put("Table.selectionInactiveForeground", TEXT_PRIMARY);
            UIManager.put("Table.cellMargins", new Insets(2, 14, 2, 14));
            UIManager.put("TableHeader.background", BG_SUBTLE);
            UIManager.put("TableHeader.foreground", TEXT_SECONDARY);
            UIManager.put("TableHeader.separatorColor", BG_SUBTLE);
            UIManager.put("TableHeader.bottomSeparatorColor", BORDER_SUBTLE);
            UIManager.put("TableHeader.height", 36);
            UIManager.put("TableHeader.cellMargins", new Insets(4, 14, 4, 14));

            // Abas internas: sublinhado verde, divisória --rule
            UIManager.put("TabbedPane.showTabSeparators", false);
            UIManager.put("TabbedPane.tabHeight", 38);
            UIManager.put("TabbedPane.underlineColor", ACCENT_PRIMARY);
            UIManager.put("TabbedPane.inactiveUnderlineColor", ACCENT_PRIMARY);
            UIManager.put("TabbedPane.tabSelectionHeight", 2);
            UIManager.put("TabbedPane.background", BG_CARD);
            UIManager.put("TabbedPane.foreground", TEXT_SECONDARY);
            UIManager.put("TabbedPane.selectedForeground", ACCENT_PRIMARY);
            UIManager.put("TabbedPane.selectedBackground", BG_CARD);
            UIManager.put("TabbedPane.hoverColor", BG_SUBTLE);
            UIManager.put("TabbedPane.focusColor", BG_CARD);
            UIManager.put("TabbedPane.contentAreaColor", BORDER_SUBTLE);

            UIManager.put("PopupMenu.background", BG_CARD);
            UIManager.put("PopupMenu.borderColor", BORDER_SUBTLE);
            UIManager.put("MenuItem.background", BG_CARD);
            UIManager.put("MenuItem.foreground", TEXT_PRIMARY);
            UIManager.put("MenuItem.selectionBackground", ACCENT_DIM);
            UIManager.put("MenuItem.selectionForeground", ACCENT_PRIMARY);
            UIManager.put("MenuItem.margin", new Insets(6, 10, 6, 14));
            UIManager.put("Separator.foreground", BORDER_SUBTLE);
            UIManager.put("ToolTip.background", TEXT_PRIMARY);
            UIManager.put("ToolTip.foreground", Color.WHITE);
            UIManager.put("OptionPane.background", BG_CARD);
            UIManager.put("OptionPane.messageForeground", TEXT_PRIMARY);
            UIManager.put("RootPane.background", BG_WINDOW);
            // Barra de título em ardósia, emendada com a navegação (sem o fundo "unificado" claro da janela)
            UIManager.put("TitlePane.unifiedBackground", false);
            UIManager.put("TitlePane.background", NAV_BG);
            UIManager.put("TitlePane.foreground", NAV_FG);
            UIManager.put("TitlePane.inactiveBackground", NAV_BG);
            UIManager.put("TitlePane.inactiveForeground", NAV_FG2);
            UIManager.put("TitlePane.embeddedForeground", NAV_FG2);
            UIManager.put("TitlePane.buttonHoverBackground", NAV_HOVER);
            UIManager.put("TitlePane.buttonPressedBackground", new Color(0x5B6B70));
            UIManager.put("TitlePane.closeHoverBackground", STATUS_ERR_FG);
            UIManager.put("TitlePane.closePressedBackground", new Color(0xA61E2A));
            UIManager.put("TitlePane.closeHoverForeground", Color.WHITE);
            UIManager.put("TitlePane.closePressedForeground", Color.WHITE);
            UIManager.put("Spinner.arrowButtonStyle", "chevron");

            // Reaplica o LaF para que a fonte base maior e os ajustes acima valham em todos os componentes.
            FlatLightLaf.setup();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ Fontes

    /**
     * Fonte de peso médio (Segoe UI Semibold no Windows) para títulos, item ativo, botões e etiquetas:
     * o padrão STR não usa negrito 700. Sem a variante no sistema, cai para negrito.
     */
    public static Font fonteSemibold(float tamanho) {
        Font f = UIManager.getFont("semibold.font");
        if (f == null) {
            Font d = UIManager.getFont("defaultFont");
            return (d != null ? d : new Font(Font.SANS_SERIF, Font.PLAIN, 13)).deriveFont(Font.BOLD, tamanho);
        }
        return f.deriveFont(tamanho);
    }

    private static float tamanhoBase() {
        Font d = UIManager.getFont("defaultFont");
        return d != null ? d.getSize2D() : 13.5f;
    }

    // ------------------------------------------------------------------ Cards e textos

    /** Painel branco do STR: canto de 6px, 20px internos e sem borda (separado pelo tom do canvas). */
    public static JPanel criarCardArredondado() {
        JPanel p = new JPanel();
        p.setBackground(BG_CARD);
        p.putClientProperty(FlatClientProperties.STYLE, "arc: 6; background: " + hex(BG_CARD));
        p.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        return p;
    }

    /** Título de painel: peso médio, recuo de 12px e a barra vertical verde (--brand) de 3x18px. */
    public static JLabel criarTituloSecao(String texto) {
        JLabel lbl = new JLabel(texto) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BRAND);
                int alt = Math.min(18, getHeight());
                g2.fillRoundRect(0, (getHeight() - alt) / 2, 3, alt, 2, 2);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        lbl.setForeground(TEXT_PRIMARY);
        lbl.setFont(fonteSemibold(tamanhoBase() * 1.15f + 1f));
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        return lbl;
    }

    /** Rótulo de campo: texto --text2, acima do campo. */
    public static JLabel criarRotuloCampo(String texto) {
        JLabel lbl = new JLabel(texto);
        lbl.setForeground(TEXT_SECONDARY);
        lbl.setFont(fonteSemibold(tamanhoBase() - 0.5f));
        return lbl;
    }

    /** Texto auxiliar discreto (dicas abaixo de campos e subtítulos). */
    public static JLabel criarTextoAjuda(String texto) {
        JLabel l = new JLabel(texto);
        l.setForeground(TEXT_SECONDARY);
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 0.5f));
        return l;
    }

    /** Cabeçalho padrão de card: título (com a barra verde) + subtítulo alinhado ao texto do título. */
    public static JPanel criarCabecalho(String titulo, String subtitulo) {
        JPanel header = new JPanel(new java.awt.BorderLayout(0, 4));
        header.setOpaque(false);
        header.add(criarTituloSecao(titulo), java.awt.BorderLayout.NORTH);
        if (subtitulo != null && !Compat.isBlank(subtitulo)) {
            JLabel sub = criarTextoAjuda(subtitulo);
            sub.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
            header.add(sub, java.awt.BorderLayout.SOUTH);
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
                g2.setColor(ACCENT_PRIMARY);
                g2.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setForeground(Color.WHITE);
        l.setFont(fonteSemibold(12f));
        Dimension d = new Dimension(24, 24);
        l.setPreferredSize(d);
        l.setMinimumSize(d);
        l.setMaximumSize(d);
        return l;
    }

    /** Pinta um painel/label como "faixa" de status (fundo + borda + cor do texto). */
    public static void estilizarFaixa(JComponent c, TipoStatus tipo, int arc) {
        Color[] cores = coresStatus(tipo);
        int a = Math.min(arc, 6);
        c.putClientProperty(FlatClientProperties.STYLE,
                "arc: " + a + "; background: " + hex(cores[0]) + "; border: 1,1,1,1," + hex(cores[2]) + ",1," + a);
    }

    public static Color corTexto(TipoStatus tipo) {
        return coresStatus(tipo)[1];
    }

    /** {fundo, texto, borda} para cada estado. */
    public static Color[] coresStatus(TipoStatus tipo) {
        switch (tipo) {
            case RUN:
                return new Color[]{STATUS_RUN_BG, STATUS_RUN_FG, STATUS_RUN_BORDER};
            case OK:
                return new Color[]{STATUS_OK_BG, STATUS_OK_FG, STATUS_OK_BORDER};
            case WARN:
                return new Color[]{STATUS_WARN_BG, STATUS_WARN_FG, STATUS_WARN_BORDER};
            case ERR:
                return new Color[]{STATUS_ERR_BG, STATUS_ERR_FG, STATUS_ERR_BORDER};
            default:
                return new Color[]{STATUS_IDLE_BG, STATUS_IDLE_FG, STATUS_IDLE_BORDER};
        }
    }

    /** Etiqueta de estado do cabeçalho (canto 3px, fundo translúcido da cor e texto na cor cheia). */
    public static void estilizarBadgeStatus(JLabel badge, TipoStatus tipo) {
        Color[] cores = coresStatus(tipo);
        badge.setOpaque(true);
        badge.putClientProperty(FlatClientProperties.STYLE,
                "arc: 3; background: " + hex(cores[0]) + "; border: 4,10,4,10," + hex(cores[0]) + ",1,3");
        badge.setForeground(cores[1]);
        badge.setFont(fonteSemibold(13f));
        badge.setIcon(new DotIcon(cores[1]));
        badge.setIconTextGap(7);
    }

    // ------------------------------------------------------------------ Botões

    private static final String DESABILITADO =
            "disabledBackground: #F5F7F8; disabledText: #8A9BA3; disabledBorderColor: #E2E9ED; ";

    /**
     * Foco visível pelo teclado (Tab): halo verde de 2px em volta do botão, como o contorno --accent
     * do STR. Sem isso não dá para saber qual botão o Enter/Espaço vai acionar.
     */
    private static final String FOCO = "focusWidth: 2; innerFocusWidth: 0; focusColor: #00863F59; ";

    /** Cor que o ícone neutro assume no hover/pressionado (acompanha o texto verde). */
    static final String PROP_ICONE_HOVER = "tp.iconeHover";

    /** Botão primário: fundo --accent, texto branco, hover --accent-press (escurece). */
    public static void estilizarBotaoPrimario(JButton btn) {
        btn.setFont(fonteSemibold(btn.getFont().getSize2D()));
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 3; background: " + hex(ACCENT_PRIMARY) + "; foreground: #FFFFFF; borderWidth: 0; " + FOCO
                        + "focusedBackground: " + hex(ACCENT_PRIMARY) + "; "
                        + "hoverBackground: " + hex(ACCENT_HOVER) + "; pressedBackground: " + hex(ACCENT_PRESSED) + "; "
                        + "disabledBackground: #E2E9ED; disabledText: #8A9BA3; margin: 8,16,8,16");
    }

    private static String estiloContorno() {
        return "arc: 3; background: #FFFFFF; foreground: " + hex(TEXT_PRIMARY) + "; "
                + "borderColor: " + hex(BORDER_STRONG) + "; " + FOCO
                + "focusedBorderColor: " + hex(ACCENT_PRIMARY) + "; "
                + "hoverBackground: #FFFFFF; hoverBorderColor: " + hex(ACCENT_PRIMARY) + "; "
                + "hoverForeground: " + hex(ACCENT_PRIMARY) + "; pressedBackground: " + hex(ACCENT_DIM) + "; "
                + DESABILITADO;
    }

    /** Botão de contorno: fundo branco, borda --border2, texto ardósia; hover deixa borda e texto verdes. */
    public static void estilizarBotaoSecundario(JButton btn) {
        btn.setFont(fonteSemibold(btn.getFont().getSize2D()));
        btn.putClientProperty(PROP_ICONE_HOVER, ACCENT_PRIMARY);
        btn.putClientProperty(FlatClientProperties.STYLE, estiloContorno() + "margin: 8,16,8,16");
    }

    /** Botão de contorno compacto (barras de ferramentas). */
    public static void estilizarBotaoCompacto(JButton btn) {
        btn.putClientProperty(PROP_ICONE_HOVER, ACCENT_PRIMARY);
        btn.putClientProperty(FlatClientProperties.STYLE, estiloContorno() + "margin: 5,12,5,12");
    }

    /** Filtro (botão de contorno que liga/desliga): ativo vira bloco --slate-dark com texto branco. */
    public static void estilizarFiltro(JToggleButton btn) {
        btn.putClientProperty(PROP_ICONE_HOVER, ACCENT_PRIMARY);
        btn.putClientProperty(FlatClientProperties.STYLE, estiloContorno()
                + "selectedBackground: " + hex(SLATE_DARK) + "; selectedForeground: #FFFFFF; "
                + "margin: 5,12,5,12");
    }

    /** Botão de texto no verde de link, sem fundo nem borda (hover em --accent-dim). */
    public static void estilizarBotaoLink(JButton btn) {
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        btn.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 3; foreground: " + hex(ACCENT_PRIMARY) + "; "
                        + "toolbar.hoverBackground: " + hex(ACCENT_DIM) + "; toolbar.pressedBackground: #D3EADD; "
                        + "margin: 1,6,1,6");
    }

    /** Botão "fantasma": sem fundo, só texto/ícone (ações discretas como Copiar/Limpar). */
    public static void estilizarBotaoFantasma(JButton btn) {
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        btn.putClientProperty(PROP_ICONE_HOVER, ACCENT_PRIMARY);
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 3; foreground: " + hex(TEXT_SECONDARY) + "; hoverForeground: " + hex(ACCENT_PRIMARY) + "; "
                        + "toolbar.hoverBackground: " + hex(ACCENT_DIM) + "; toolbar.pressedBackground: #D3EADD; "
                        + "margin: 4,8,4,8");
    }

    /** Ação de cancelar/excluir: vermelho só aqui (fundo --danger-dim, texto --danger). */
    public static void estilizarBotaoPerigo(JButton btn) {
        btn.setFont(fonteSemibold(btn.getFont().getSize2D()));
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 3; background: " + hex(STATUS_ERR_BG) + "; foreground: " + hex(STATUS_ERR_FG) + "; "
                        + "borderColor: " + hex(STATUS_ERR_BORDER) + "; "
                        + "focusWidth: 2; innerFocusWidth: 0; focusColor: #C6253359; "
                        + "focusedBorderColor: " + hex(STATUS_ERR_FG) + "; focusedBackground: " + hex(STATUS_ERR_BG) + "; "
                        + "hoverBackground: #F6DFE1; hoverBorderColor: " + hex(STATUS_ERR_FG) + "; "
                        + "pressedBackground: #F2D2D5; " + DESABILITADO + "margin: 8,16,8,16");
    }

    /**
     * Item de navegação na barra em ardósia: texto claro em peso médio; o ativo é bloco --accent.
     * Fica fora do Tab (a troca de aba é por Ctrl+1 a Ctrl+4), assim o Tab percorre só o conteúdo.
     */
    public static void estilizarNavTab(JToggleButton btn) {
        btn.setFocusPainted(false);
        btn.setFocusable(false);
        btn.setFont(fonteSemibold(tamanhoBase()));
        btn.putClientProperty(FlatClientProperties.STYLE,
                "arc: 3; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; margin: 7,14,7,14; "
                        + "background: " + hex(NAV_BG) + "; foreground: " + hex(NAV_FG2) + "; "
                        + "hoverBackground: " + hex(NAV_HOVER) + "; pressedBackground: " + hex(NAV_HOVER) + "; "
                        + "selectedBackground: " + hex(ACCENT_PRIMARY) + "; selectedForeground: #FFFFFF; "
                        + "disabledBackground: " + hex(NAV_BG) + "; disabledText: #8A9BA3; "
                        + "disabledSelectedBackground: " + hex(NAV_HOVER));
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
        if (placeholder != null && !Compat.isBlank(placeholder)) {
            field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        }
    }

    /**
     * Destaca um campo com contorno vermelho e foca nele; o destaque some assim que o usuário digita.
     */
    public static void marcarCampoComErro(JTextComponent campo) {
        // Borda vermelha com halo suave (10%), como o foco verde do STR; o contorno de erro padrão do
        // FlatLaf pintava um anel vermelho sólido e grosso. O estilo anterior do campo é preservado.
        if (campo.getClientProperty(PROP_ESTILO_ANTES_ERRO) == null) {
            Object atual = campo.getClientProperty(FlatClientProperties.STYLE);
            campo.putClientProperty(PROP_ESTILO_ANTES_ERRO, atual == null ? "" : atual.toString());
        }
        String base = (String) campo.getClientProperty(PROP_ESTILO_ANTES_ERRO);
        campo.putClientProperty(FlatClientProperties.STYLE, (base.isEmpty() ? "" : base + "; ")
                + "borderColor: #C62533; focusedBorderColor: #C62533; focusColor: #C625331A");
        if (campo.getClientProperty("tp.erroListener") == null) {
            DocumentListener dl = new DocumentListener() {
                private void limpar() {
                    Object antes = campo.getClientProperty(PROP_ESTILO_ANTES_ERRO);
                    if (antes == null) return;
                    campo.putClientProperty(FlatClientProperties.STYLE, antes.toString().isEmpty() ? null : antes);
                    campo.putClientProperty(PROP_ESTILO_ANTES_ERRO, null);
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

    private static final String PROP_ESTILO_ANTES_ERRO = "tp.estiloAntesErro";

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

    private static BufferedImage logoBaseCache;
    private static List<Image> iconesJanelaCache;

    /** Carrega o logo oficial (logo.png) embutido nos recursos do aplicativo. */
    public static synchronized BufferedImage carregarLogo() {
        if (logoBaseCache != null) return logoBaseCache;
        try (InputStream in = SwingThemeHelper.class.getResourceAsStream("/tpDiscover/intelbras/gui/logo.png")) {
            if (in != null) {
                logoBaseCache = ImageIO.read(in);
            }
        } catch (Exception ignored) {
        }
        if (logoBaseCache == null) {
            BufferedImage fallback = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = fallback.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BRAND);
            g.fillRoundRect(0, 0, 64, 64, 14, 14);
            criarIcone(IconKind.NETWORK, Color.WHITE, 40).paintIcon(null, g, 12, 12);
            g.dispose();
            logoBaseCache = fallback;
        }
        return logoBaseCache;
    }

    /** Redimensiona o logo em etapas progressivas (2x) para manter nitidez e suavidade em tamanhos pequenos. */
    public static BufferedImage redimensionarLogo(int tamanho) {
        BufferedImage atual = carregarLogo();
        int alvo = Math.max(1, tamanho);
        int w = atual.getWidth();
        int h = atual.getHeight();
        while (w / 2 >= alvo && h / 2 >= alvo) {
            w = Math.max(alvo, w / 2);
            h = Math.max(alvo, h / 2);
            BufferedImage passo = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = passo.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(atual, 0, 0, w, h, null);
            g.dispose();
            atual = passo;
        }
        if (w != alvo || h != alvo) {
            BufferedImage fim = new BufferedImage(alvo, alvo, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = fim.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(atual, 0, 0, alvo, alvo, null);
            g.dispose();
            atual = fim;
        }
        return atual;
    }

    /** Ícone Swing do logo com suporte a telas HiDPI. */
    public static Icon criarIconeLogo(final int tamanho) {
        final BufferedImage img1x = redimensionarLogo(tamanho);
        final BufferedImage img2x = redimensionarLogo(tamanho * 2);
        return new Icon() {
            @Override public int getIconWidth() { return tamanho; }
            @Override public int getIconHeight() { return tamanho; }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double esc = g2.getTransform().getScaleX();
                BufferedImage src = esc > 1.25 ? img2x : img1x;
                g2.drawImage(src, x, y, tamanho, tamanho, null);
                g2.dispose();
            }
        };
    }

    /** Lista de ícones multi-resolução para a barra de título, Alt+Tab e barra de tarefas. */
    public static synchronized List<Image> iconesJanela() {
        if (iconesJanelaCache != null) return iconesJanelaCache;
        int[] tamanhos = {16, 20, 24, 32, 40, 48, 64, 128, 256};
        List<Image> lista = new ArrayList<>(tamanhos.length);
        for (int t : tamanhos) {
            lista.add(redimensionarLogo(t));
        }
        iconesJanelaCache = Collections.unmodifiableList(lista);
        return iconesJanelaCache;
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

    private static final class DotIcon implements Icon {
        private final Color color;

        private DotIcon(Color color) {
            this.color = color;
        }

        public Color color() {
            return color;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof DotIcon)) return false;
            DotIcon o = (DotIcon) obj;
            return java.util.Objects.equals(color, o.color);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(color);
        }

        @Override
        public String toString() {
            return "DotIcon[color=" + color + "]";
        }

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

    private static boolean ehNeutra(Color c) {
        return TEXT_PRIMARY.equals(c) || TEXT_SECONDARY.equals(c) || TEXT_MUTED.equals(c);
    }

    private static final class VectorIcon implements Icon {
        private final IconKind kind;
        private final Color color;
        private final int size;

        private VectorIcon(IconKind kind, Color color, int size) {
            this.kind = kind;
            this.color = color;
            this.size = size;
        }

        public IconKind kind() {
            return kind;
        }

        public Color color() {
            return color;
        }

        public int size() {
            return size;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof VectorIcon)) return false;
            VectorIcon o = (VectorIcon) obj;
            return java.util.Objects.equals(kind, o.kind)
                    && java.util.Objects.equals(color, o.color)
                    && size == o.size;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(kind, color, size);
        }

        @Override
        public String toString() {
            return "VectorIcon[kind=" + kind + ", color=" + color + ", size=" + size + "]";
        }


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
                Color drawColor = color;
                if (c != null && !c.isEnabled()) {
                    drawColor = new Color(0xA2B1B8);
                } else if (c instanceof AbstractButton && ehNeutra(color)) {
                    // ícone neutro acompanha o texto verde no hover (botões de contorno)
                    AbstractButton b = (AbstractButton) c;
                    Object hover = b.getClientProperty(PROP_ICONE_HOVER);
                    if (hover instanceof Color && !b.isSelected()
                            && (b.getModel().isRollover() || b.getModel().isPressed())) {
                        drawColor = (Color) hover;
                    }
                }
                g2.setColor(drawColor);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                switch (kind) {
                    case SEARCH: {
                        g2.drawOval(2, 2, 7, 7);
                        g2.drawLine(8, 8, 12, 12);
                        break;
                    }
                    case PLAY: {
                        Path2D tri = new Path2D.Double();
                        tri.moveTo(3.5, 2);
                        tri.lineTo(12, 7);
                        tri.lineTo(3.5, 12);
                        tri.closePath();
                        g2.fill(tri);
                        break;
                    }
                    case STOP:
                        g2.fillRoundRect(3, 3, 8, 8, 3, 3);
                        break;
                    case REFRESH: {
                        g2.drawArc(2, 2, 10, 10, 40, 280);
                        g2.drawLine(10, 2, 12, 5);
                        g2.drawLine(12, 5, 9, 5);
                        break;
                    }
                    case PDF: case FILE: {
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
                        break;
                    }
                    case FOLDER: {
                        Path2D p = new Path2D.Double();
                        p.moveTo(1.5, 3);
                        p.lineTo(5, 3);
                        p.lineTo(6.5, 4.5);
                        p.lineTo(12.5, 4.5);
                        p.lineTo(12.5, 11.5);
                        p.lineTo(1.5, 11.5);
                        p.closePath();
                        g2.draw(p);
                        break;
                    }
                    case PLUS: {
                        g2.drawLine(7, 2, 7, 12);
                        g2.drawLine(2, 7, 12, 7);
                        break;
                    }
                    case MINUS:
                        g2.drawLine(2, 7, 12, 7);
                        break;
                    case CHECK: {
                        g2.drawLine(2, 7, 5, 11);
                        g2.drawLine(5, 11, 12, 3);
                        break;
                    }
                    case ALERT: {
                        Path2D tri = new Path2D.Double();
                        tri.moveTo(7, 1.5);
                        tri.lineTo(13, 12.5);
                        tri.lineTo(1, 12.5);
                        tri.closePath();
                        g2.draw(tri);
                        g2.drawLine(7, 5, 7, 8);
                        g2.fillOval(6, 10, 2, 2);
                        break;
                    }
                    case CLEAR: {
                        g2.drawLine(3, 3, 11, 11);
                        g2.drawLine(11, 3, 3, 11);
                        break;
                    }
                    case NETWORK: {
                        g2.drawRoundRect(5, 1, 4, 4, 1, 1);
                        g2.drawRoundRect(1, 9, 4, 4, 1, 1);
                        g2.drawRoundRect(9, 9, 4, 4, 1, 1);
                        g2.drawLine(7, 5, 7, 7);
                        g2.drawLine(3, 7, 11, 7);
                        g2.drawLine(3, 7, 3, 9);
                        g2.drawLine(11, 7, 11, 9);
                        break;
                    }
                    case LIST: {
                        g2.drawLine(5, 3, 12, 3);
                        g2.drawLine(5, 7, 12, 7);
                        g2.drawLine(5, 11, 12, 11);
                        g2.fillOval(1, 2, 2, 2);
                        g2.fillOval(1, 6, 2, 2);
                        g2.fillOval(1, 10, 2, 2);
                        break;
                    }
                    case SLIDERS: {
                        g2.drawLine(2, 4, 12, 4);
                        g2.drawLine(2, 10, 12, 10);
                        g2.fillOval(3, 2, 4, 4);
                        g2.fillOval(8, 8, 4, 4);
                        break;
                    }
                    case COPY: {
                        g2.drawRoundRect(4, 4, 8, 8, 2, 2);
                        g2.drawLine(2, 9, 2, 3);
                        g2.drawLine(2, 2, 9, 2);
                        break;
                    }
                    case KEY: {
                        g2.drawOval(1, 4, 6, 6);
                        g2.drawLine(7, 7, 13, 7);
                        g2.drawLine(11, 7, 11, 10);
                        g2.drawLine(13, 7, 13, 9);
                        break;
                    }
                    case CHECKBOX: {
                        g2.drawRoundRect(1, 1, 12, 12, 3, 3);
                        g2.drawLine(4, 7, 6, 10);
                        g2.drawLine(6, 10, 10, 4);
                        break;
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

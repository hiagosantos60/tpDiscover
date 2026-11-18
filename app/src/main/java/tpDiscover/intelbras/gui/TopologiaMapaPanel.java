package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.correlacao.MapaTopologia;
import tpDiscover.intelbras.correlacao.MapaTopologia.Alerta;
import tpDiscover.intelbras.correlacao.MapaTopologia.Enlace;
import tpDiscover.intelbras.correlacao.MapaTopologia.No;
import tpDiscover.intelbras.correlacao.MapaTopologia.StatusEnlace;
import tpDiscover.intelbras.gui.SwingThemeHelper.IconKind;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mapa interativo da topologia para consulta rápida no atendimento: mesmos equipamentos, cabos e
 * posições do diagrama do PDF, com zoom (roda do mouse), arrastar para mover, busca por nome/IP e
 * painel de detalhes do equipamento selecionado (IP, MAC, portas e vizinhos).
 */
public class TopologiaMapaPanel extends JPanel {

    // Paleta do canvas (mesma linha visual do Planejador de Rede).
    private static final Color BG_CANVAS = new Color(0x141921);
    private static final Color GRID_DOT = new Color(0x222A36);
    private static final Color COR_COBRE = new Color(0x8B5CF6);
    private static final Color COR_FIBRA = new Color(0xF0A020);
    private static final Color COR_LOOP = new Color(0xDC2626);
    private static final Color COR_ATENCAO = new Color(0xD97706);
    private static final Color COR_IP = new Color(0x82B6FF);
    private static final Color COR_SELECAO = new Color(0x3B82F6);
    private static final Color COR_BUSCA = new Color(0xFBC75A);

    private final Canvas canvas = new Canvas();
    private final JTextField txtBusca = new JTextField(18);
    private final JLabel lblResumo = new JLabel(" ");
    private final JPanel painelDetalhe = new PainelDetalhe();
    private final JScrollPane scrollDetalhe;

    private MapaTopologia mapa = MapaTopologia.vazio();
    private final Map<String, No> nosPorId = new LinkedHashMap<>();
    private No selecionado;
    private String termoBusca = "";
    private java.nio.file.Path pastaRelatorio;
    private java.nio.file.Path relatorioGerado;

    public TopologiaMapaPanel() {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        add(criarBarraFerramentas(), BorderLayout.NORTH);

        JPanel centro = new JPanel(new BorderLayout(10, 0));
        centro.setOpaque(false);
        canvas.setBorder(BorderFactory.createLineBorder(SwingThemeHelper.BORDER_SUBTLE, 1, true));
        centro.add(canvas, BorderLayout.CENTER);

        painelDetalhe.setLayout(new BoxLayout(painelDetalhe, BoxLayout.Y_AXIS));
        painelDetalhe.setBackground(SwingThemeHelper.BG_CARD);
        painelDetalhe.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        scrollDetalhe = new JScrollPane(painelDetalhe);
        scrollDetalhe.setBorder(BorderFactory.createLineBorder(SwingThemeHelper.BORDER_SUBTLE, 1, true));
        scrollDetalhe.getViewport().setBackground(SwingThemeHelper.BG_CARD);
        scrollDetalhe.setPreferredSize(new Dimension(300, 10));
        scrollDetalhe.getVerticalScrollBar().setUnitIncrement(14);
        scrollDetalhe.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollDetalhe.setVisible(false);
        centro.add(scrollDetalhe, BorderLayout.EAST);
        add(centro, BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------ API

    public void exibir(MapaTopologia novoMapa) {
        this.mapa = novoMapa != null ? novoMapa : MapaTopologia.vazio();
        nosPorId.clear();
        for (No n : mapa.nos()) {
            nosPorId.put(n.id(), n);
        }
        String idAnterior = selecionado != null ? selecionado.id() : null;
        selecionado = idAnterior != null ? nosPorId.get(idAnterior) : null;
        atualizarResumo();
        atualizarDetalhe();
        canvas.ajusteAutomatico = true;
        canvas.ajustarATela();
        canvas.repaint();
    }

    public MapaTopologia getMapa() {
        return mapa;
    }

    // ---------------------------------------------------------- Barra superior

    private JPanel criarBarraFerramentas() {
        JPanel barra = new JPanel(new BorderLayout(10, 0));
        barra.setOpaque(false);

        JPanel esquerda = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        esquerda.setOpaque(false);
        txtBusca.putClientProperty("JTextField.placeholderText", "Buscar por nome, IP ou MAC");
        txtBusca.putClientProperty("JTextField.leadingIcon", SwingThemeHelper.criarIcone(IconKind.SEARCH, SwingThemeHelper.TEXT_MUTED, 14));
        txtBusca.putClientProperty("JTextField.showClearButton", true);
        txtBusca.setToolTipText("Destaca e centraliza o equipamento encontrado (Enter vai para o próximo)");
        txtBusca.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { aplicarBusca(false); }
            @Override public void removeUpdate(DocumentEvent e) { aplicarBusca(false); }
            @Override public void changedUpdate(DocumentEvent e) { aplicarBusca(false); }
        });
        txtBusca.addActionListener(e -> aplicarBusca(true));
        esquerda.add(txtBusca);

        JButton btnMenos = botaoFerramenta(IconKind.MINUS, "Diminuir zoom", () -> canvas.zoomCentro(1 / 1.25));
        JButton btnMais = botaoFerramenta(IconKind.PLUS, "Aumentar zoom", () -> canvas.zoomCentro(1.25));
        JButton btnAjustar = botaoFerramenta(IconKind.REFRESH, "Ajustar o mapa à tela (ou duplo clique no fundo)", () -> {
            canvas.ajusteAutomatico = true;
            canvas.ajustarATela();
        });
        btnAjustar.setText("Ajustar");
        JButton btnSalvar = botaoFerramenta(IconKind.FILE, "Salvar o mapa como imagem PNG", this::salvarImagem);
        btnSalvar.setText("Salvar imagem");
        JButton btnPlanejador = botaoFerramenta(IconKind.NETWORK,
                "Salva o projeto (.json) do Planejador de Rede na pasta do relatório, para editar a topologia", this::exportarPlanejador);
        btnPlanejador.setText("Exportar p/ Planejador");
        esquerda.add(btnMenos);
        esquerda.add(btnMais);
        esquerda.add(btnAjustar);
        esquerda.add(btnSalvar);
        esquerda.add(btnPlanejador);
        lblResumo.setForeground(SwingThemeHelper.TEXT_MUTED);
        lblResumo.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        esquerda.add(lblResumo);
        barra.add(esquerda, BorderLayout.CENTER);

        JPanel legenda = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        legenda.setOpaque(false);
        legenda.add(itemLegenda("Cobre", COR_COBRE, false));
        legenda.add(itemLegenda("Fibra", COR_FIBRA, false));
        legenda.add(itemLegenda("Incerto / STP", COR_ATENCAO, true));
        legenda.add(itemLegenda("Loop", COR_LOOP, false));
        barra.add(legenda, BorderLayout.EAST);
        return barra;
    }

    private static JButton botaoFerramenta(IconKind icone, String dica, Runnable acao) {
        JButton b = new JButton();
        SwingThemeHelper.estilizarBotaoCompacto(b);
        b.setIcon(SwingThemeHelper.criarIcone(icone, SwingThemeHelper.TEXT_SECONDARY));
        b.setToolTipText(dica);
        b.addActionListener(e -> acao.run());
        return b;
    }

    private static JLabel itemLegenda(String texto, Color cor, boolean tracejado) {
        JLabel l = new JLabel(texto, new IconeLinha(cor, tracejado), JLabel.LEFT);
        l.setForeground(SwingThemeHelper.TEXT_SECONDARY);
        l.setIconTextGap(6);
        return l;
    }

    private void atualizarResumo() {
        if (mapa.estaVazio()) {
            lblResumo.setText(" ");
            return;
        }
        int alertas = 0;
        for (No n : mapa.nos()) {
            if (n.alerta() == Alerta.LOOP || n.alerta() == Alerta.AUTH_ERRO) alertas++;
        }
        lblResumo.setText(mapa.nos().size() + " equipamento(s) • " + mapa.enlaces().size() + " cabo(s)"
                + (alertas > 0 ? " • " + alertas + " com alerta" : ""));
    }

    // ------------------------------------------------------------------ Busca

    private void aplicarBusca(boolean proximo) {
        termoBusca = txtBusca.getText() == null ? "" : txtBusca.getText().trim().toLowerCase(Locale.ROOT);
        if (termoBusca.isEmpty()) {
            canvas.repaint();
            return;
        }
        List<No> achados = new ArrayList<>();
        for (No n : mapa.nos()) {
            if (casaBusca(n)) achados.add(n);
        }
        if (achados.isEmpty()) {
            canvas.repaint();
            return;
        }
        No alvo = achados.get(0);
        if (proximo && selecionado != null) {
            int idx = achados.indexOf(selecionado);
            alvo = achados.get((idx + 1) % achados.size());
        } else if (!proximo && selecionado != null && achados.contains(selecionado)) {
            alvo = selecionado;
        }
        selecionar(alvo, true);
    }

    private boolean casaBusca(No n) {
        if (termoBusca.isEmpty()) return false;
        String termoMac = termoBusca.replaceAll("[^0-9a-f]", "");
        return contem(n.nome(), termoBusca) || contem(n.ip(), termoBusca) || contem(n.modelo(), termoBusca)
                || (termoMac.length() >= 4 && n.mac() != null
                && n.mac().toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f]", "").contains(termoMac));
    }

    private static boolean contem(String valor, String termo) {
        return valor != null && valor.toLowerCase(Locale.ROOT).contains(termo);
    }

    // ------------------------------------------------------------- Seleção

    private void selecionar(No n, boolean centralizar) {
        selecionado = n;
        atualizarDetalhe();
        if (n != null && centralizar) {
            canvas.centralizarEm(n);
        }
        canvas.repaint();
    }

    private void atualizarDetalhe() {
        painelDetalhe.removeAll();
        if (selecionado == null) {
            scrollDetalhe.setVisible(false);
            revalidate();
            repaint();
            return;
        }
        No n = selecionado;

        JPanel topo = new JPanel(new BorderLayout());
        topo.setOpaque(false);
        topo.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel titulo = new JLabel(valorOu(n.nome(), "Equipamento"));
        titulo.setForeground(SwingThemeHelper.TEXT_PRIMARY);
        titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, titulo.getFont().getSize2D() + 3f));
        topo.add(titulo, BorderLayout.CENTER);
        JButton fechar = new JButton(SwingThemeHelper.criarIcone(IconKind.CLEAR, SwingThemeHelper.TEXT_MUTED, 12));
        fechar.putClientProperty("JButton.buttonType", "toolBarButton");
        fechar.setToolTipText("Fechar detalhes");
        fechar.addActionListener(e -> selecionar(null, false));
        topo.add(fechar, BorderLayout.EAST);
        adicionarLinha(topo);

        adicionarLinha(rotulo(descricaoTipo(n.tipo()) + (vazio(n.modelo()) ? "" : " • " + n.modelo()),
                SwingThemeHelper.TEXT_MUTED, false));
        String situacao = descricaoAlerta(n);
        if (situacao != null) {
            adicionarLinha(Box.createVerticalStrut(6));
            adicionarLinha(rotulo(situacao, corAlerta(n.alerta()), true));
        }
        adicionarLinha(Box.createVerticalStrut(12));
        adicionarCampo("IP", n.ip());
        adicionarCampo("MAC", n.mac());
        adicionarCampo("Serial", n.serial());

        String ipSemPorta = ipSemPorta(n.ip());
        if (!vazio(ipSemPorta) && ipSemPorta.matches("[0-9.]+")) {
            JPanel acoes = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            acoes.setOpaque(false);
            acoes.setAlignmentX(Component.LEFT_ALIGNMENT);
            JButton copiar = new JButton("Copiar IP", SwingThemeHelper.criarIcone(IconKind.COPY, SwingThemeHelper.TEXT_SECONDARY));
            SwingThemeHelper.estilizarBotaoCompacto(copiar);
            copiar.addActionListener(e -> copiar(ipSemPorta));
            JButton web = new JButton("Abrir web", SwingThemeHelper.criarIcone(IconKind.NETWORK, SwingThemeHelper.TEXT_SECONDARY));
            SwingThemeHelper.estilizarBotaoCompacto(web);
            web.setToolTipText("Abre http://" + ipSemPorta + " no navegador");
            web.addActionListener(e -> abrirWeb(ipSemPorta));
            acoes.add(copiar);
            acoes.add(Box.createHorizontalStrut(6));
            acoes.add(web);
            adicionarLinha(Box.createVerticalStrut(8));
            adicionarLinha(acoes);
        }

        List<Enlace> conexoes = enlacesDe(n);
        adicionarLinha(Box.createVerticalStrut(16));
        adicionarLinha(rotulo("Conexões (" + conexoes.size() + ")", SwingThemeHelper.TEXT_SECONDARY, true));
        adicionarLinha(Box.createVerticalStrut(6));
        if (conexoes.isEmpty()) {
            adicionarLinha(rotulo("Nenhum cabo identificado.", SwingThemeHelper.TEXT_MUTED, false));
        }
        for (Enlace e : conexoes) {
            boolean ehA = e.idA().equals(n.id());
            No outro = nosPorId.get(ehA ? e.idB() : e.idA());
            String portaLocal = valorOu(ehA ? e.portaA() : e.portaB(), "?");
            String portaRemota = valorOu(ehA ? e.portaB() : e.portaA(), "?");
            adicionarLinha(linhaConexao(portaLocal, outro, portaRemota, e));
        }
        painelDetalhe.add(Box.createVerticalGlue());
        scrollDetalhe.setVisible(true);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(() -> scrollDetalhe.getVerticalScrollBar().setValue(0));
    }

    private JComponent linhaConexao(String portaLocal, No outro, String portaRemota, Enlace e) {
        JPanel linha = new JPanel(new BorderLayout(8, 0));
        linha.setOpaque(true);
        linha.setBackground(SwingThemeHelper.BG_CARD);
        linha.setAlignmentX(Component.LEFT_ALIGNMENT);
        linha.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        linha.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));

        JLabel marcador = new JLabel(new IconeLinha(corEnlace(e), e.status() == StatusEnlace.INCERTO
                || e.status() == StatusEnlace.STP_BLOQUEADO));
        linha.add(marcador, BorderLayout.WEST);

        JPanel textos = new JPanel();
        textos.setOpaque(false);
        textos.setLayout(new BoxLayout(textos, BoxLayout.Y_AXIS));
        JLabel l1 = rotulo(portaLocal + "  →  " + (outro != null ? valorOu(outro.nome(), "?") : "?"),
                SwingThemeHelper.TEXT_PRIMARY, false);
        String extra = "porta remota " + portaRemota + (e.fibra() ? " • fibra" : " • cobre");
        if (e.status() == StatusEnlace.LOOP) extra += " • LOOP";
        else if (e.status() == StatusEnlace.STP_BLOQUEADO) extra += " • bloqueada pelo STP";
        else if (e.status() == StatusEnlace.INCERTO) extra += " • ligação incerta";
        JLabel l2 = rotulo(extra, SwingThemeHelper.TEXT_MUTED, false);
        l2.setFont(l2.getFont().deriveFont(l2.getFont().getSize2D() - 1f));
        textos.add(l1);
        textos.add(l2);
        linha.add(textos, BorderLayout.CENTER);

        if (outro != null) {
            linha.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            linha.setToolTipText("Ir para " + valorOu(outro.nome(), "equipamento"));
            linha.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent ev) { linha.setBackground(SwingThemeHelper.BG_HOVER); }
                @Override public void mouseExited(MouseEvent ev) { linha.setBackground(SwingThemeHelper.BG_CARD); }
                @Override public void mouseClicked(MouseEvent ev) { selecionar(outro, true); }
            });
        }
        return linha;
    }

    private void adicionarCampo(String nome, String valor) {
        if (vazio(valor) || "N/D".equalsIgnoreCase(valor.trim())) return;
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        JLabel k = rotulo(nome, SwingThemeHelper.TEXT_MUTED, false);
        k.setPreferredSize(new Dimension(52, k.getPreferredSize().height));
        JLabel v = rotulo(valor, SwingThemeHelper.TEXT_PRIMARY, false);
        v.setFont(SwingThemeHelper.fonteMonoespacada().deriveFont(v.getFont().getSize2D()));
        p.add(k, BorderLayout.WEST);
        p.add(v, BorderLayout.CENTER);
        adicionarLinha(p);
        adicionarLinha(Box.createVerticalStrut(3));
    }

    private void adicionarLinha(Component c) {
        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            jc.setAlignmentX(Component.LEFT_ALIGNMENT);
            // altura fixa: o BoxLayout não deve esticar as linhas para preencher o painel
            jc.setMaximumSize(new Dimension(Integer.MAX_VALUE, jc.getPreferredSize().height));
        }
        painelDetalhe.add(c);
    }

    /** Painel de detalhes que acompanha a largura da área visível (textos longos são abreviados). */
    private static final class PainelDetalhe extends JPanel implements javax.swing.Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 14; }

        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return r.height - 20; }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static JLabel rotulo(String texto, Color cor, boolean negrito) {
        JLabel l = new JLabel(texto);
        l.setToolTipText(texto);
        l.setMinimumSize(new Dimension(10, l.getPreferredSize().height));
        l.setForeground(cor);
        if (negrito) l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private List<Enlace> enlacesDe(No n) {
        List<Enlace> r = new ArrayList<>();
        for (Enlace e : mapa.enlaces()) {
            if (e.idA().equals(n.id()) || e.idB().equals(n.id())) r.add(e);
        }
        return r;
    }

    private void copiar(String texto) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(texto), null);
    }

    private void abrirWeb(String ip) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://" + ip));
                return;
            }
        } catch (Exception ignored) {
            // cai na mensagem abaixo
        }
        JOptionPane.showMessageDialog(this, "Não foi possível abrir o navegador. Acesse http://" + ip,
                "Abrir interface web", JOptionPane.WARNING_MESSAGE);
    }

    private void salvarImagem() {
        if (mapa.estaVazio()) {
            JOptionPane.showMessageDialog(this, "Faça uma coleta antes de salvar o mapa.", "Salvar imagem",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Salvar mapa da topologia");
        fc.setFileFilter(new FileNameExtensionFilter("Imagem PNG", "png"));
        java.nio.file.Path pastaImagem = destinoProjetoPlanejador().toAbsolutePath().getParent();
        if (pastaImagem != null) fc.setCurrentDirectory(pastaImagem.toFile());
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), "mapa_topologia.png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File destino = fc.getSelectedFile();
        if (!destino.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
            destino = new File(destino.getParentFile(), destino.getName() + ".png");
        }
        try {
            ImageIO.write(canvas.renderizarImagem(1.5), "png", destino);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Não foi possível salvar a imagem: " + ex.getMessage(),
                    "Salvar imagem", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Pasta onde o relatório (PDF) é salvo: o projeto do Planejador e a imagem vão para o mesmo lugar. */
    public void setPastaRelatorio(java.nio.file.Path pasta, java.nio.file.Path relatorioGerado) {
        this.pastaRelatorio = pasta;
        this.relatorioGerado = relatorioGerado;
    }

    /** Destino do projeto do Planejador: mesma pasta e mesmo nome-base do PDF (ou data/hora, se ainda não houver PDF). */
    java.nio.file.Path destinoProjetoPlanejador() {
        java.nio.file.Path pasta = relatorioGerado != null && relatorioGerado.getParent() != null
                ? relatorioGerado.getParent()
                : (pastaRelatorio != null ? pastaRelatorio : java.nio.file.Paths.get("."));
        String base;
        if (relatorioGerado != null) {
            String nome = relatorioGerado.getFileName().toString();
            int ponto = nome.lastIndexOf('.');
            base = ponto > 0 ? nome.substring(0, ponto) : nome;
        } else {
            base = "topologia_" + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"));
        }
        return pasta.resolve(base + "_planejador.json");
    }

    private void exportarPlanejador() {
        if (mapa.estaVazio()) {
            JOptionPane.showMessageDialog(this, "Faça uma coleta antes de exportar a topologia.",
                    "Exportar para o Planejador", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        java.nio.file.Path destino = destinoProjetoPlanejador();
        try {
            if (destino.getParent() != null) java.nio.file.Files.createDirectories(destino.getParent());
            String nome = "Topologia " + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            tpDiscover.intelbras.correlacao.ExportadorProjetoPlanejador.salvar(mapa, nome, destino);
            Object[] opcoes = {"Abrir pasta", "OK"};
            int escolha = JOptionPane.showOptionDialog(this,
                    "Projeto salvo na pasta do relatório:\n" + destino.toAbsolutePath()
                            + "\n\nNo Planejador de Rede, use o botão Abrir e selecione este arquivo.",
                    "Exportar para o Planejador", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                    null, opcoes, opcoes[1]);
            if (escolha == 0) {
                SwingThemeHelper.abrirCaminhoSistema(this, destino.toAbsolutePath().getParent(), true);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Não foi possível salvar o projeto em " + destino.toAbsolutePath()
                            + ": " + ex.getMessage(), "Exportar para o Planejador", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -------------------------------------------------------------- Textos

    private static String descricaoTipo(String tipo) {
        if (tipo == null) return "Equipamento";
        switch (tipo) {
            case "router": return "Roteador / Gateway";
            case "olt": return "OLT";
            case "ap": return "Access Point";
            case "radio": return "Rádio";
            case "segmento": return "Segmento sem acesso";
            default: return "Switch";
        }
    }

    private static String descricaoAlerta(No n) {
        switch (n.alerta()) {
            case LOOP: return "Possível loop neste equipamento";
            case STP: return "Porta bloqueada pelo STP (redundância contida)";
            case AUTH_ERRO: return "Senha incorreta: não foi possível acessar";
            case SEM_ACESSO: return n.coletado() ? null : "Sem acesso CLI (identificado pelos vizinhos)";
            default: return n.coletado() || "router".equals(n.tipo()) ? "Coletado com sucesso" : null;
        }
    }

    private static Color corAlerta(Alerta a) {
        switch (a) {
            case LOOP: return SwingThemeHelper.STATUS_ERR_FG;
            case STP:
            case AUTH_ERRO: return SwingThemeHelper.STATUS_WARN_FG;
            case SEM_ACESSO: return SwingThemeHelper.TEXT_SECONDARY;
            default: return SwingThemeHelper.STATUS_OK_FG;
        }
    }

    private static Color corEnlace(Enlace e) {
        switch (e.status()) {
            case LOOP: return COR_LOOP;
            case STP_BLOQUEADO:
            case INCERTO: return COR_ATENCAO;
            default: return e.fibra() ? COR_FIBRA : COR_COBRE;
        }
    }

    private static String ipSemPorta(String ip) {
        if (ip == null) return "";
        String t = ip.trim();
        int idx = t.indexOf(':');
        return idx > 0 ? t.substring(0, idx) : t;
    }

    private static boolean vazio(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String valorOu(String s, String padrao) {
        return vazio(s) ? padrao : s.trim();
    }

    // ============================================================== Canvas

    private final class Canvas extends JComponent {
        private double escala = 1.0;
        private double offX = 0;
        private double offY = 0;
        boolean ajusteAutomatico = true;
        private Point arrasteInicio;
        private boolean arrastou;
        private No hover;

        Canvas() {
            setOpaque(true);
            setBackground(BG_CANVAS);
            setFocusable(true);
            ToolTipManager.sharedInstance().registerComponent(this);
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    arrasteInicio = e.getPoint();
                    arrastou = false;
                    if (e.isPopupTrigger()) abrirMenu(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        abrirMenu(e);
                    } else if (!arrastou && SwingUtilities.isLeftMouseButton(e)) {
                        No n = noEm(e.getPoint());
                        if (n != null) {
                            selecionar(n, false);
                        } else if (e.getClickCount() >= 2) {
                            ajusteAutomatico = true;
                            ajustarATela();
                        } else {
                            selecionar(null, false);
                        }
                    }
                    arrasteInicio = null;
                    setCursor(Cursor.getDefaultCursor());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (arrasteInicio == null) return;
                    int dx = e.getX() - arrasteInicio.x;
                    int dy = e.getY() - arrasteInicio.y;
                    if (!arrastou && Math.abs(dx) + Math.abs(dy) < 3) return;
                    arrastou = true;
                    ajusteAutomatico = false;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    offX += dx;
                    offY += dy;
                    arrasteInicio = e.getPoint();
                    repaint();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    No n = noEm(e.getPoint());
                    if (n != hover) {
                        hover = n;
                        setCursor(n != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                        repaint();
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    double fator = Math.pow(1.15, -e.getPreciseWheelRotation());
                    zoomEm(e.getX(), e.getY(), fator);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (ajusteAutomatico) ajustarATela();
                }
            });
        }

        private void abrirMenu(MouseEvent e) {
            No n = noEm(e.getPoint());
            if (n == null) return;
            selecionar(n, false);
            JPopupMenu menu = new JPopupMenu();
            String ip = ipSemPorta(n.ip());
            if (!vazio(ip)) {
                JMenuItem copiarIp = new JMenuItem("Copiar IP (" + ip + ")");
                copiarIp.addActionListener(ev -> copiar(ip));
                menu.add(copiarIp);
                if (ip.matches("[0-9.]+")) {
                    JMenuItem web = new JMenuItem("Abrir interface web");
                    web.addActionListener(ev -> abrirWeb(ip));
                    menu.add(web);
                }
            }
            if (!vazio(n.mac()) && !"N/D".equalsIgnoreCase(n.mac())) {
                JMenuItem copiarMac = new JMenuItem("Copiar MAC (" + n.mac() + ")");
                copiarMac.addActionListener(ev -> copiar(n.mac()));
                menu.add(copiarMac);
            }
            if (menu.getComponentCount() > 0) menu.show(this, e.getX(), e.getY());
        }

        // ---------------------------------------------------- Coordenadas

        private Rectangle2D limitesMundo() {
            if (mapa.estaVazio()) return new Rectangle2D.Double(0, 0, 1, 1);
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (No n : mapa.nos()) {
                minX = Math.min(minX, n.x() - 46);
                minY = Math.min(minY, n.y() - 22);
                maxX = Math.max(maxX, n.x() + MapaTopologia.LARGURA_ICONE + 46);
                maxY = Math.max(maxY, n.y() + 92);
            }
            return new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
        }

        void ajustarATela() {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || mapa.estaVazio()) {
                repaint();
                return;
            }
            Rectangle2D b = limitesMundo();
            double margem = 24;
            escala = Math.min((w - 2 * margem) / b.getWidth(), (h - 2 * margem) / b.getHeight());
            escala = Math.max(0.15, Math.min(escala, 1.6));
            offX = (w - b.getWidth() * escala) / 2.0 - b.getX() * escala;
            offY = (h - b.getHeight() * escala) / 2.0 - b.getY() * escala;
            repaint();
        }

        void zoomCentro(double fator) {
            zoomEm(getWidth() / 2.0, getHeight() / 2.0, fator);
        }

        void zoomEm(double sx, double sy, double fator) {
            double nova = Math.max(0.15, Math.min(4.0, escala * fator));
            double wx = (sx - offX) / escala;
            double wy = (sy - offY) / escala;
            escala = nova;
            offX = sx - wx * escala;
            offY = sy - wy * escala;
            ajusteAutomatico = false;
            repaint();
        }

        void centralizarEm(No n) {
            if (getWidth() <= 0) return;
            if (escala < 0.8) escala = 1.0;
            offX = getWidth() / 2.0 - n.centroX() * escala;
            offY = getHeight() / 2.0 - (n.centroY() + 14) * escala;
            ajusteAutomatico = false;
            repaint();
        }

        private Point2D paraMundo(Point p) {
            return new Point2D.Double((p.x - offX) / escala, (p.y - offY) / escala);
        }

        private No noEm(Point p) {
            Point2D w = paraMundo(p);
            No achado = null;
            for (No n : mapa.nos()) {
                Rectangle2D r = new Rectangle2D.Double(n.x() - 8, n.y() - 6, MapaTopologia.LARGURA_ICONE + 16, 86);
                if (r.contains(w)) achado = n;
            }
            return achado;
        }

        private Enlace enlaceEm(Point p) {
            Point2D w = paraMundo(p);
            double tolerancia = 6 / escala;
            Map<Enlace, Line2D> linhas = calcularLinhas();
            for (Map.Entry<Enlace, Line2D> e : linhas.entrySet()) {
                if (e.getValue().ptSegDist(w) <= tolerancia) return e.getKey();
            }
            return null;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            No n = noEm(e.getPoint());
            if (n != null) {
                StringBuilder sb = new StringBuilder("<html><b>").append(esc(valorOu(n.nome(), "Equipamento"))).append("</b>");
                if (!vazio(n.modelo())) sb.append("<br>").append(esc(n.modelo()));
                if (!vazio(n.ip())) sb.append("<br>IP: ").append(esc(n.ip()));
                if (!vazio(n.mac()) && !"N/D".equalsIgnoreCase(n.mac())) sb.append("<br>MAC: ").append(esc(n.mac()));
                sb.append("<br><i>Clique para ver as conexões</i></html>");
                return sb.toString();
            }
            Enlace l = enlaceEm(e.getPoint());
            if (l != null) {
                No a = nosPorId.get(l.idA());
                No b = nosPorId.get(l.idB());
                return "<html><b>" + esc(a != null ? a.nome() : "?") + "</b> [" + esc(valorOu(l.portaA(), "?")) + "]"
                        + " ⟷ [" + esc(valorOu(l.portaB(), "?")) + "] <b>" + esc(b != null ? b.nome() : "?") + "</b>"
                        + "<br>" + (l.fibra() ? "Fibra" : "Cobre") + descricaoStatus(l.status()) + "</html>";
            }
            return null;
        }

        private String descricaoStatus(StatusEnlace s) {
            switch (s) {
                case LOOP: return " • possível LOOP";
                case STP_BLOQUEADO: return " • porta bloqueada pelo STP";
                case INCERTO: return " • ligação incerta (trecho sem acesso)";
                default: return "";
            }
        }

        private String esc(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        // -------------------------------------------------------- Desenho

        /**
         * Linhas (em coordenadas do mundo) de cada cabo. Como no PDF, o cabo sai da borda do
         * equipamento (abaixo do nome/IP, acima do ícone ou pelas laterais) e os cabos que saem do
         * mesmo lado são espalhados, para que as etiquetas de porta não cubram os textos.
         */
        private Map<Enlace, Line2D> calcularLinhas() {
            final class Ponta {
                final Enlace enlace;
                final boolean ehA;
                final double ordem;

                Ponta(Enlace enlace, boolean ehA, double ordem) {
                    this.enlace = enlace;
                    this.ehA = ehA;
                    this.ordem = ordem;
                }
            }
            Map<String, List<Ponta>> porLado = new LinkedHashMap<>();
            Map<Enlace, int[]> lados = new LinkedHashMap<>();
            for (Enlace e : mapa.enlaces()) {
                No a = nosPorId.get(e.idA());
                No b = nosPorId.get(e.idB());
                if (a == null || b == null || a == b) continue;
                int ladoA = lado(a, b);
                int ladoB = lado(b, a);
                lados.put(e, new int[]{ladoA, ladoB});
                adicionarPonta(porLado, a.id() + "#" + ladoA, new Ponta(e, true, ordemNoLado(ladoA, b)));
                adicionarPonta(porLado, b.id() + "#" + ladoB, new Ponta(e, false, ordemNoLado(ladoB, a)));
            }
            Map<Enlace, double[]> pontos = new LinkedHashMap<>();
            for (Map.Entry<String, List<Ponta>> grupo : porLado.entrySet()) {
                List<Ponta> pontas = grupo.getValue();
                pontas.sort((p1, p2) -> Double.compare(p1.ordem, p2.ordem));
                int total = pontas.size();
                for (int i = 0; i < total; i++) {
                    Ponta p = pontas.get(i);
                    No n = nosPorId.get(p.ehA ? p.enlace.idA() : p.enlace.idB());
                    int lado = lados.get(p.enlace)[p.ehA ? 0 : 1];
                    double[] anc = ancora(n, lado, i, total);
                    double[] seg = pontos.get(p.enlace);
                    if (seg == null) {
                        seg = new double[4];
                        pontos.put(p.enlace, seg);
                    }
                    if (p.ehA) {
                        seg[0] = anc[0];
                        seg[1] = anc[1];
                    } else {
                        seg[2] = anc[0];
                        seg[3] = anc[1];
                    }
                }
            }
            Map<Enlace, Line2D> linhas = new LinkedHashMap<>();
            for (Map.Entry<Enlace, double[]> e : pontos.entrySet()) {
                double[] s = e.getValue();
                linhas.put(e.getKey(), new Line2D.Double(s[0], s[1], s[2], s[3]));
            }
            return linhas;
        }

        private static final int LADO_BASE = 0;
        private static final int LADO_TOPO = 1;
        private static final int LADO_DIREITA = 2;
        private static final int LADO_ESQUERDA = 3;

        private <T> void adicionarPonta(Map<String, List<T>> mapaLados, String chave, T ponta) {
            List<T> l = mapaLados.get(chave);
            if (l == null) {
                l = new ArrayList<>();
                mapaLados.put(chave, l);
            }
            l.add(ponta);
        }

        private int lado(No de, No para) {
            double dy = para.y() - de.y();
            double dx = para.x() - de.x();
            if (dy > 40) return LADO_BASE;
            if (dy < -40) return LADO_TOPO;
            if (Math.abs(dx) <= 400) return dx >= 0 ? LADO_DIREITA : LADO_ESQUERDA;
            return LADO_BASE;
        }

        private double ordemNoLado(int lado, No outro) {
            return lado == LADO_BASE || lado == LADO_TOPO ? outro.centroX() : outro.centroY();
        }

        private double[] ancora(No n, int lado, int slot, int total) {
            double centralizado = slot - (total - 1) / 2.0;
            double cx = n.centroX();
            double cy = n.centroY();
            switch (lado) {
                case LADO_TOPO:
                    return new double[]{cx + centralizado * Math.min(30, 160.0 / total),
                            n.y() - (n.alerta() != Alerta.NENHUM ? 18 : 6)};
                case LADO_DIREITA:
                    return new double[]{n.x() + MapaTopologia.LARGURA_ICONE + 10, cy + centralizado * Math.min(10, 36.0 / total)};
                case LADO_ESQUERDA:
                    return new double[]{n.x() - 10, cy + centralizado * Math.min(10, 36.0 / total)};
                default:
                    return new double[]{cx + centralizado * Math.min(30, 160.0 / total), n.y() + 84};
            }
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setColor(BG_CANVAS);
                g.fillRect(0, 0, getWidth(), getHeight());
                aplicarQualidade(g);
                if (mapa.estaVazio()) {
                    desenharVazio(g);
                    return;
                }
                desenharGrade(g);
                AffineTransform t = new AffineTransform();
                t.translate(offX, offY);
                t.scale(escala, escala);
                g.transform(t);
                desenharMapa(g, escala, true);
            } finally {
                g.dispose();
            }
        }

        BufferedImage renderizarImagem(double fator) {
            Rectangle2D b = limitesMundo();
            int w = (int) Math.ceil(b.getWidth() * fator) + 40;
            int h = (int) Math.ceil(b.getHeight() * fator) + 40;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                aplicarQualidade(g);
                g.setColor(BG_CANVAS);
                g.fillRect(0, 0, w, h);
                g.translate(20 - b.getX() * fator, 20 - b.getY() * fator);
                g.scale(fator, fator);
                desenharMapa(g, fator, false);
            } finally {
                g.dispose();
            }
            return img;
        }

        private void aplicarQualidade(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        }

        private void desenharVazio(Graphics2D g) {
            String l1 = "Nenhum mapa para exibir";
            String l2 = "Faça uma coleta na aba Coleta para ver o mapa da topologia aqui.";
            Font f = getFont();
            g.setFont(f.deriveFont(Font.BOLD, f.getSize2D() + 3f));
            FontMetrics fm = g.getFontMetrics();
            int cy = getHeight() / 2;
            g.setColor(SwingThemeHelper.TEXT_SECONDARY);
            g.drawString(l1, (getWidth() - fm.stringWidth(l1)) / 2, cy - 6);
            g.setFont(f);
            fm = g.getFontMetrics();
            g.setColor(SwingThemeHelper.TEXT_MUTED);
            g.drawString(l2, (getWidth() - fm.stringWidth(l2)) / 2, cy + 18);
        }

        private void desenharGrade(Graphics2D g) {
            double passo = 28 * escala;
            if (passo < 10) return;
            g.setColor(GRID_DOT);
            double x0 = offX % passo;
            double y0 = offY % passo;
            for (double x = x0; x < getWidth(); x += passo) {
                for (double y = y0; y < getHeight(); y += passo) {
                    g.fillRect((int) x, (int) y, 2, 2);
                }
            }
        }

        private void desenharMapa(Graphics2D g, double escalaAtual, boolean interativo) {
            Map<Enlace, Line2D> linhas = calcularLinhas();
            String idFoco = interativo && selecionado != null ? selecionado.id() : null;

            // Cabos
            for (Map.Entry<Enlace, Line2D> en : linhas.entrySet()) {
                Enlace e = en.getKey();
                boolean ligado = idFoco == null || e.idA().equals(idFoco) || e.idB().equals(idFoco);
                Composite antes = new Composite(g, ligado ? 1f : 0.18f);
                Color cor = corEnlace(e);
                float larg = (float) Math.max(2.2, 1.4 / escalaAtual);
                if (idFoco != null && ligado) larg *= 1.5f;
                boolean tracejado = e.status() == StatusEnlace.INCERTO || e.status() == StatusEnlace.STP_BLOQUEADO;
                g.setStroke(tracejado
                        ? new BasicStroke(larg, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{7f, 5f}, 0f)
                        : new BasicStroke(larg, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(cor);
                g.draw(en.getValue());
                antes.restaurar();
            }

            // Etiquetas de porta (só quando legíveis). Cada etiqueta procura um ponto livre ao longo do
            // cabo; se não houver espaço, fica oculta (aparece ao selecionar/passar o mouse no switch,
            // na dica do cabo e no painel de detalhes).
            boolean mostrarPortas = escalaAtual >= 0.5;
            if (mostrarPortas) {
                Font fPorta = getFont().deriveFont(Font.BOLD, 9.5f);
                g.setFont(fPorta);
                List<Rectangle2D> ocupadas = new ArrayList<>();
                List<Rectangle2D> etiquetas = new ArrayList<>();
                for (No n : mapa.nos()) {
                    ocupadas.add(new Rectangle2D.Double(n.x() - 6, n.y() - (n.alerta() != Alerta.NENHUM ? 16 : 4),
                            MapaTopologia.LARGURA_ICONE + 12, 86));
                }
                String idDestaque = idFoco != null ? idFoco : (interativo && hover != null ? hover.id() : null);
                List<Map.Entry<Enlace, Line2D>> ordem = new ArrayList<>(linhas.entrySet());
                if (idDestaque != null) {
                    final String d = idDestaque;
                    ordem.sort((x, y) -> Boolean.compare(!toca(x.getKey(), d), !toca(y.getKey(), d)));
                }
                for (Map.Entry<Enlace, Line2D> en : ordem) {
                    Enlace e = en.getKey();
                    boolean ligado = idFoco == null || toca(e, idFoco);
                    if (!ligado) continue;
                    boolean prioridade = idDestaque != null && toca(e, idDestaque);
                    Line2D l = en.getValue();
                    desenharEtiquetaPorta(g, l.getX1(), l.getY1(), l.getX2(), l.getY2(), e.portaA(), corEnlace(e), ocupadas, etiquetas, prioridade);
                    desenharEtiquetaPorta(g, l.getX2(), l.getY2(), l.getX1(), l.getY1(), e.portaB(), corEnlace(e), ocupadas, etiquetas, prioridade);
                }
            }

            // Equipamentos
            for (No n : mapa.nos()) {
                boolean foco = idFoco == null || n.id().equals(idFoco) || vizinhoDe(n, idFoco);
                Composite antes = new Composite(g, foco ? 1f : 0.3f);
                desenharNo(g, n, interativo);
                antes.restaurar();
            }
        }

        private boolean vizinhoDe(No n, String idFoco) {
            for (Enlace e : mapa.enlaces()) {
                if ((e.idA().equals(idFoco) && e.idB().equals(n.id())) || (e.idB().equals(idFoco) && e.idA().equals(n.id()))) {
                    return true;
                }
            }
            return false;
        }

        private boolean toca(Enlace e, String id) {
            return e.idA().equals(id) || e.idB().equals(id);
        }

        /**
         * Desenha a etiqueta da porta perto da ponta (x1,y1) do cabo, no primeiro ponto livre ao longo
         * do cabo. Etiquetas prioritárias (equipamento selecionado ou sob o mouse) podem passar por cima
         * de nomes de equipamentos, mas continuam sem sobrepor outras etiquetas.
         */
        private void desenharEtiquetaPorta(Graphics2D g, double x1, double y1, double x2, double y2, String porta,
                                           Color cor, List<Rectangle2D> ocupadas, List<Rectangle2D> etiquetas,
                                           boolean prioridade) {
            if (vazio(porta) || "?".equals(porta.trim())) return;
            double dx = x2 - x1, dy = y2 - y1;
            double len = Math.hypot(dx, dy);
            if (len < 44) return;
            String txt = abreviarPorta(porta.trim());
            FontMetrics fm = g.getFontMetrics();
            double w = fm.stringWidth(txt) + 10;
            double h = 15;
            double limite = len * 0.45;
            Rectangle2D escolhido = null;
            for (double dist = 22; dist <= limite; dist += 8) {
                double px = x1 + dx / len * dist;
                double py = y1 + dy / len * dist;
                Rectangle2D r = new Rectangle2D.Double(px - w / 2, py - h / 2, w, h);
                if (!colide(r, etiquetas) && (prioridade || !colide(r, ocupadas))) {
                    escolhido = r;
                    break;
                }
            }
            if (escolhido == null) return;
            etiquetas.add(new Rectangle2D.Double(escolhido.getX() - 2, escolhido.getY() - 2,
                    escolhido.getWidth() + 4, escolhido.getHeight() + 4));
            RoundRectangle2D pill = new RoundRectangle2D.Double(escolhido.getX(), escolhido.getY(), w, h, 7, 7);
            g.setColor(new Color(0x0F131A));
            g.fill(pill);
            g.setStroke(new BasicStroke(1f));
            g.setColor(cor);
            g.draw(pill);
            g.setColor(new Color(0xE8EBF1));
            g.drawString(txt, (float) (escolhido.getCenterX() - fm.stringWidth(txt) / 2.0),
                    (float) (escolhido.getCenterY() + fm.getAscent() / 2.0 - 1.5));
        }

        private boolean colide(Rectangle2D r, List<Rectangle2D> ocupadas) {
            for (Rectangle2D o : ocupadas) {
                if (o.intersects(r)) return true;
            }
            return false;
        }

        private String abreviarPorta(String p) {
            String r = p.replace("GigabitEthernet", "GE").replace("Ten-GigabitEthernet", "XGE")
                    .replace("Ethernet", "Eth").replace("Bridge-Aggregation", "BAGG");
            return r.length() > 16 ? r.substring(0, 15) + "…" : r;
        }

        private void desenharNo(Graphics2D g, No n, boolean interativo) {
            double x = n.x(), y = n.y();
            boolean ehSel = interativo && selecionado != null && selecionado.id().equals(n.id());
            boolean ehBusca = interativo && casaBusca(n);
            boolean ehHover = interativo && hover != null && hover.id().equals(n.id());

            double cx = n.centroX();
            if (ehSel || ehBusca || ehHover) {
                Color c = ehSel ? COR_SELECAO : (ehBusca ? COR_BUSCA : new Color(0x3A404C));
                RoundRectangle2D halo = new RoundRectangle2D.Double(x - 8, y - 6, MapaTopologia.LARGURA_ICONE + 16, 86, 14, 14);
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), ehHover && !ehSel && !ehBusca ? 60 : 45));
                g.fill(halo);
                g.setStroke(new BasicStroke(ehSel ? 2f : 1.4f));
                g.setColor(c);
                g.draw(halo);
            }

            // Anel tracejado de alerta
            if (n.alerta() == Alerta.LOOP || n.alerta() == Alerta.STP) {
                g.setColor(n.alerta() == Alerta.LOOP ? COR_LOOP : COR_ATENCAO);
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{5f, 3f}, 0f));
                g.draw(new Ellipse2D.Double(cx - 36, y + 24 - 36, 72, 72));
            }

            AffineTransform antes = g.getTransform();
            g.translate(x, y);
            desenharIcone(g, n.tipo());
            g.setTransform(antes);

            desenharBadge(g, n, cx, y);

            // Nome e IP abaixo do ícone (como no Planejador)
            Font base = getFont();
            g.setFont(base.deriveFont(Font.BOLD, 12f));
            FontMetrics fm = g.getFontMetrics();
            String nome = encurtar(valorOu(n.nome(), "?"), 26);
            g.setColor(SwingThemeHelper.TEXT_PRIMARY);
            g.drawString(nome, (float) (cx - fm.stringWidth(nome) / 2.0), (float) (y + 62));
            if (!vazio(n.ip())) {
                g.setFont(base.deriveFont(Font.PLAIN, 10.5f));
                fm = g.getFontMetrics();
                String ip = n.ip().trim();
                g.setColor(COR_IP);
                g.drawString(ip, (float) (cx - fm.stringWidth(ip) / 2.0), (float) (y + 76));
            }
        }

        private void desenharBadge(Graphics2D g, No n, double cx, double y) {
            String txt;
            Color cor;
            switch (n.alerta()) {
                case LOOP: txt = "! LOOP"; cor = COR_LOOP; break;
                case STP: txt = "STP BLK"; cor = COR_ATENCAO; break;
                case AUTH_ERRO: txt = "! SENHA"; cor = new Color(0xB45309); break;
                case SEM_ACESSO: txt = "SEM CLI"; cor = new Color(0x64748B); break;
                default: return;
            }
            g.setFont(getFont().deriveFont(Font.BOLD, 9f));
            FontMetrics fm = g.getFontMetrics();
            double w = fm.stringWidth(txt) + 10;
            RoundRectangle2D r = new RoundRectangle2D.Double(cx - w / 2, y - 14, w, 14, 6, 6);
            g.setColor(cor);
            g.fill(r);
            g.setColor(Color.WHITE);
            g.drawString(txt, (float) (cx - fm.stringWidth(txt) / 2.0), (float) (y - 14 + 10.5));
        }

        private String encurtar(String s, int max) {
            return s.length() > max ? s.substring(0, max - 1) + "…" : s;
        }
    }

    /** Ajuda a aplicar/restaurar transparência ao desenhar. */
    private static final class Composite {
        private final Graphics2D g;
        private final java.awt.Composite anterior;

        Composite(Graphics2D g, float alpha) {
            this.g = g;
            this.anterior = g.getComposite();
            if (alpha < 1f) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        }

        void restaurar() {
            g.setComposite(anterior);
        }
    }

    // ===================================================== Ícones (64 x 48)

    /** Desenha o ícone do equipamento no espaço local 64x48 (mesmos desenhos do PDF). */
    static void desenharIcone(Graphics2D g, String tipo) {
        String t = tipo == null ? "switch" : tipo;
        switch (t) {
            case "router": desenharRoteador(g); break;
            case "segmento": desenharSegmento(g); break;
            case "ap": desenharAp(g); break;
            case "radio": desenharRadio(g); break;
            case "olt": desenharOlt(g); break;
            default: desenharSwitch(g); break;
        }
    }

    private static final Color AZUL_CLARO = new Color(0x4FA3E3);
    private static final Color AZUL_ESCURO = new Color(0x1C5E9F);
    private static final Color AZUL_TOPO = new Color(0x9AD0F7);
    private static final Color AZUL_LADO_1 = new Color(0x1A5490);
    private static final Color AZUL_LADO_2 = new Color(0x0E3862);
    private static final Color AZUL_CONTORNO = new Color(0x0F3A66);

    private static void sombra(Graphics2D g, double cx, double rx) {
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new Ellipse2D.Double(cx - rx, 44 - 2.6, rx * 2, 5.2));
    }

    private static void preencher(Graphics2D g, Shape s, Color c1, Color c2, float y1, float y2, Color contorno, float larg) {
        g.setPaint(new GradientPaint(0, y1, c1, 0, y2, c2));
        g.fill(s);
        if (contorno != null) {
            g.setPaint(contorno);
            g.setStroke(new BasicStroke(larg));
            g.draw(s);
        }
    }

    private static Path2D poligono(double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        return p;
    }

    private static void linhas(Graphics2D g, Color cor, float larg, double... xy) {
        g.setColor(cor);
        g.setStroke(new BasicStroke(larg, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i + 3 < xy.length; i += 4) {
            g.draw(new Line2D.Double(xy[i], xy[i + 1], xy[i + 2], xy[i + 3]));
        }
    }

    private static void desenharSwitch(Graphics2D g) {
        sombra(g, 32, 28);
        preencher(g, new Rectangle2D.Double(4, 24, 48, 14), AZUL_CLARO, AZUL_ESCURO, 24, 38, AZUL_CONTORNO, 0.8f);
        preencher(g, poligono(4, 24, 14, 14, 62, 14, 52, 24), AZUL_TOPO, AZUL_CLARO, 14, 24, AZUL_CONTORNO, 0.8f);
        preencher(g, poligono(52, 24, 62, 14, 62, 28, 52, 38), AZUL_LADO_1, AZUL_LADO_2, 14, 38, AZUL_CONTORNO, 0.8f);
        linhas(g, Color.WHITE, 1.8f, 17, 16.5, 29, 16.5, 34, 16.5, 46, 16.5, 41, 20.5, 29, 20.5, 24, 20.5, 12, 20.5);
        // LEDs frontais
        g.setColor(new Color(0x5DF08F));
        for (int i = 0; i < 6; i++) g.fill(new Rectangle2D.Double(9 + i * 6, 30, 3.2, 2.6));
    }

    private static void desenharRoteador(Graphics2D g) {
        sombra(g, 32, 26);
        Area corpo = new Area(new Rectangle2D.Double(6, 20, 52, 12));
        corpo.add(new Area(new Ellipse2D.Double(6, 23, 52, 18)));
        preencher(g, corpo, AZUL_CLARO, AZUL_ESCURO, 20, 41, AZUL_CONTORNO, 0.8f);
        preencher(g, new Ellipse2D.Double(6, 11, 52, 18), AZUL_TOPO, AZUL_CLARO, 11, 29, AZUL_CONTORNO, 0.8f);
        linhas(g, Color.WHITE, 2.2f, 13, 15, 26, 18.6, 38, 21.4, 51, 25, 38, 18.6, 51, 15, 26, 21.4, 13, 25);
    }

    private static void desenharSegmento(Graphics2D g) {
        sombra(g, 32, 26);
        Area nuvem = new Area(new Ellipse2D.Double(10, 22, 24, 18));
        nuvem.add(new Area(new Ellipse2D.Double(19, 12, 28, 24)));
        nuvem.add(new Area(new Ellipse2D.Double(34, 18, 22, 22)));
        nuvem.add(new Area(new Rectangle2D.Double(18, 30, 30, 10)));
        g.setPaint(new GradientPaint(0, 12, Color.WHITE, 0, 40, new Color(0xC9DDF2)));
        g.fill(nuvem);
        g.setColor(COR_ATENCAO);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{4f, 2f}, 0f));
        g.draw(nuvem);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0x92400E));
        g.drawString("L2 / ?", (float) (32 - fm.stringWidth("L2 / ?") / 2.0), 32f);
    }

    private static void desenharAp(Graphics2D g) {
        sombra(g, 31, 22);
        preencher(g, new Rectangle2D.Double(10, 30, 36, 9), AZUL_CLARO, AZUL_ESCURO, 30, 39, AZUL_CONTORNO, 0.8f);
        preencher(g, poligono(10, 30, 16, 24, 52, 24, 46, 30), AZUL_TOPO, AZUL_CLARO, 24, 30, AZUL_CONTORNO, 0.8f);
        preencher(g, poligono(46, 30, 52, 24, 52, 33, 46, 39), AZUL_LADO_1, AZUL_LADO_2, 24, 39, AZUL_CONTORNO, 0.8f);
        g.setColor(new Color(0x4FA3E3));
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double r : new double[]{6, 11, 16}) {
            g.draw(new Arc2D.Double(31 - r, 21 - r, r * 2, r * 2, 45, 90, Arc2D.OPEN));
        }
        g.fill(new Ellipse2D.Double(31 - 2.4, 21 - 2.4, 4.8, 4.8));
        g.setColor(new Color(0x5DF08F));
        g.fill(new Ellipse2D.Double(40 - 1.4, 34.5 - 1.4, 2.8, 2.8));
    }

    private static void desenharRadio(Graphics2D g) {
        sombra(g, 32, 20);
        RoundRectangle2D corpo = new RoundRectangle2D.Double(20, 16, 24, 24, 8, 8);
        g.setColor(new Color(0x0E7490));
        g.fill(corpo);
        g.setColor(new Color(0x155E75));
        g.setStroke(new BasicStroke(1f));
        g.draw(corpo);
        g.setColor(new Color(0x67, 0xE8, 0xF9, 90));
        g.fill(new RoundRectangle2D.Double(24, 20, 16, 14, 4, 4));
        g.setColor(new Color(0x22D3EE));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double r : new double[]{7, 12}) {
            g.draw(new Arc2D.Double(22 - r, 28 - r, r * 2, r * 2, 150, 60, Arc2D.OPEN));
            g.draw(new Arc2D.Double(42 - r, 28 - r, r * 2, r * 2, -30, 60, Arc2D.OPEN));
        }
        g.setColor(new Color(0x475569));
        g.fill(new Rectangle2D.Double(29.5, 40, 5, 4));
    }

    private static void desenharOlt(Graphics2D g) {
        sombra(g, 32, 28);
        Color contorno = new Color(0x11161D);
        preencher(g, new Rectangle2D.Double(4, 22, 48, 15), new Color(0x3B4555), new Color(0x1F2630), 22, 37, contorno, 0.8f);
        preencher(g, poligono(4, 22, 14, 13, 62, 13, 52, 22), new Color(0x6B7686), new Color(0x55606F), 13, 22, contorno, 0.8f);
        preencher(g, poligono(52, 22, 62, 13, 62, 28, 52, 37), new Color(0x232A34), new Color(0x171C24), 13, 37, contorno, 0.8f);
        g.setColor(new Color(0x22C55E));
        for (int i = 0; i < 6; i++) g.fill(new RoundRectangle2D.Double(8 + i * 5, 27.5, 3.4, 4, 1.2, 1.2));
        g.setColor(new Color(0x3B82F6));
        g.fill(new RoundRectangle2D.Double(46, 27, 4, 5, 1.2, 1.2));
        g.setColor(new Color(0x0D9488));
        g.fill(new RoundRectangle2D.Double(10, 3, 26, 11, 4, 4));
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("OLT", (float) (23 - fm.stringWidth("OLT") / 2.0), 11.5f);
    }

    // ------------------------------------------------------------- Legenda

    private static final class IconeLinha implements Icon {
        private final Color cor;
        private final boolean tracejado;

        IconeLinha(Color cor, boolean tracejado) {
            this.cor = cor;
            this.tracejado = tracejado;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(cor);
            g.setStroke(tracejado
                    ? new BasicStroke(2.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{4f, 3f}, 0f)
                    : new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x + 1, y + getIconHeight() / 2, x + getIconWidth() - 1, y + getIconHeight() / 2);
            g.dispose();
        }

        @Override public int getIconWidth() { return 20; }

        @Override public int getIconHeight() { return 12; }
    }

    // Usado pelos testes para inspecionar o estado sem depender de pintura.
    No getSelecionado() {
        return selecionado;
    }

    void selecionarPorId(String id) {
        selecionar(nosPorId.get(id), true);
    }
}

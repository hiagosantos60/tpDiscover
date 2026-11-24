package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.gui.DiscoveredTargetRow.EstadoColeta;
import tpDiscover.intelbras.gui.SwingThemeHelper.IconKind;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.RowFilter;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import tpDiscover.intelbras.util.Compat;
/**
 * Aba "Equipamentos": lista dos alvos descobertos/adicionados, com credenciais individuais,
 * barra de ferramentas agrupada em menus e resumo dos estados no rodapé.
 */
public class EquipamentosViewPanel extends JPanel {

    private final TargetTableModel tableModel;
    private final JTable tblAlvos;

    private final JButton btnAdicionarIp = new JButton("Adicionar");
    private final JButton btnRemoverIp = new JButton("Remover");
    private final JMenuItem miMarcarTodos = new JMenuItem("Marcar todos");
    private final JMenuItem miDesmarcar = new JMenuItem("Desmarcar todos");
    private final JMenuItem miSomenteFalhos = new JMenuItem("Marcar só os que falharam");
    private final JButton btnMarcar;
    private final JButton btnDefinirSenha = new JButton("Definir senha");
    private final JMenuItem miImportarTxt = new JMenuItem("Importar lista (.txt)...");
    private final JMenuItem miExportarTxt = new JMenuItem("Exportar lista (.txt)...");
    private final JButton btnArquivo;
    private final JButton btnColetarSelecionados = new JButton("Coletar marcados");
    private final JButton btnRecoletarFalhas = new JButton("Recoletar falhas");
    private final JLabel lblResumo = SwingThemeHelper.criarTextoAjuda(" ");
    /** Filtro "Só falhas": mostra apenas os equipamentos que precisam de ajuste (senha, acesso). */
    private final JToggleButton btnSoFalhas = new JToggleButton("Só falhas");
    private final JButton btnMostrarTodos = new JButton("Mostrar todos");
    private final TableRowSorter<TargetTableModel> sorter;
    private final JPanel faixaFalhas = new JPanel(new BorderLayout(10, 0));
    private final JLabel lblFalhas = new JLabel(" ");
    /** Lembrete dos atalhos da tabela (rodapé, à direita). */
    private final JLabel lblAtalhos = SwingThemeHelper.criarTextoAjuda(
            "Atalhos: Enter define a senha  ·  F2 edita usuário/senha (Tab alterna)  ·  Espaço marca  ·  "
                    + "Delete remove  ·  F5 recoleta");

    private boolean modoIncrementalAtivo;
    private boolean executando;

    public EquipamentosViewPanel(TargetTableModel tableModel) {
        this.tableModel = tableModel;
        this.tblAlvos = new JTable(tableModel) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getRowCount() == 0) {
                    pintarEstadoVazio(g, this);
                }
            }
        };
        this.sorter = new TableRowSorter<>(tableModel);
        for (int i = 0; i < tableModel.getColumnCount(); i++) {
            sorter.setSortable(i, false); // a ordem é a do modelo (falhas primeiro, depois IP)
        }
        sorter.setSortsOnUpdates(true);
        tblAlvos.setRowSorter(sorter);
        setLayout(new BorderLayout());
        setOpaque(false);

        miMarcarTodos.setIcon(SwingThemeHelper.criarIcone(IconKind.CHECKBOX, SwingThemeHelper.TEXT_SECONDARY));
        miSomenteFalhos.setIcon(SwingThemeHelper.criarIcone(IconKind.ALERT, SwingThemeHelper.STATUS_WARN_FG));
        miImportarTxt.setIcon(SwingThemeHelper.criarIcone(IconKind.FOLDER, SwingThemeHelper.TEXT_SECONDARY));
        miExportarTxt.setIcon(SwingThemeHelper.criarIcone(IconKind.FILE, SwingThemeHelper.TEXT_SECONDARY));
        btnMarcar = SwingThemeHelper.criarBotaoMenu("Marcar",
                SwingThemeHelper.criarIcone(IconKind.CHECKBOX, SwingThemeHelper.TEXT_SECONDARY),
                miMarcarTodos, miDesmarcar, null, miSomenteFalhos);
        btnArquivo = SwingThemeHelper.criarBotaoMenu("Arquivo",
                SwingThemeHelper.criarIcone(IconKind.FILE, SwingThemeHelper.TEXT_SECONDARY),
                miImportarTxt, miExportarTxt);

        JPanel card = SwingThemeHelper.criarCardArredondado();
        card.setLayout(new BorderLayout(0, 14));

        // Cabeçalho + barra de ferramentas
        JPanel topo = new JPanel(new BorderLayout(0, 14));
        topo.setOpaque(false);
        topo.add(SwingThemeHelper.criarCabecalho(
                "Equipamentos",
                "Marque os que devem ser coletados. Usuário/senha em branco usam os da aba Coleta. "
                        + "Enter na tabela define a senha das linhas selecionadas."),
                BorderLayout.NORTH);

        SwingThemeHelper.estilizarBotaoCompacto(btnAdicionarIp);
        SwingThemeHelper.estilizarBotaoCompacto(btnRemoverIp);
        SwingThemeHelper.estilizarBotaoCompacto(btnDefinirSenha);
        btnAdicionarIp.setIcon(SwingThemeHelper.criarIcone(IconKind.PLUS, SwingThemeHelper.TEXT_SECONDARY));
        btnRemoverIp.setIcon(SwingThemeHelper.criarIcone(IconKind.MINUS, SwingThemeHelper.TEXT_SECONDARY));
        btnDefinirSenha.setIcon(SwingThemeHelper.criarIcone(IconKind.KEY, SwingThemeHelper.TEXT_SECONDARY));
        btnAdicionarIp.setToolTipText("Adicionar um equipamento pelo IP");
        btnRemoverIp.setToolTipText("Remover as linhas selecionadas");
        btnDefinirSenha.setToolTipText("Definir usuário/senha para as linhas selecionadas (Enter na tabela), "
                + "ou marcá-las como sem senha");

        JPanel esquerda = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        esquerda.setOpaque(false);
        esquerda.add(btnAdicionarIp);
        esquerda.add(btnRemoverIp);
        esquerda.add(divisor());
        esquerda.add(btnMarcar);
        esquerda.add(btnDefinirSenha);
        esquerda.add(divisor());
        esquerda.add(btnArquivo);

        SwingThemeHelper.estilizarBotaoPrimario(btnColetarSelecionados);
        btnColetarSelecionados.setIcon(SwingThemeHelper.criarIcone(IconKind.PLAY, Color.WHITE));
        btnColetarSelecionados.setEnabled(false);

        SwingThemeHelper.estilizarBotaoCompacto(btnRecoletarFalhas);
        btnRecoletarFalhas.setIcon(SwingThemeHelper.criarIcone(IconKind.REFRESH, SwingThemeHelper.TEXT_SECONDARY));
        btnRecoletarFalhas.setToolTipText("Coleta de novo os que falharam e os que tiveram a senha corrigida (F5)");
        btnRecoletarFalhas.setVisible(false);
        btnRecoletarFalhas.addActionListener(e -> {
            tableModel.selecionarApenasFalhos();
            btnColetarSelecionados.doClick();
        });
        SwingThemeHelper.estilizarFiltro(btnSoFalhas);
        btnSoFalhas.setIcon(SwingThemeHelper.criarIcone(IconKind.ALERT, SwingThemeHelper.STATUS_ERR_FG));
        btnSoFalhas.setSelectedIcon(SwingThemeHelper.criarIcone(IconKind.ALERT, Color.WHITE));
        btnSoFalhas.setToolTipText("Mostra só os equipamentos que falharam e precisam de ajuste (senha, acesso)");
        btnSoFalhas.addActionListener(e -> aplicarFiltro());
        JPanel direita = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        direita.setOpaque(false);
        direita.add(btnColetarSelecionados);

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setOpaque(false);
        toolbar.add(esquerda, BorderLayout.CENTER);
        toolbar.add(direita, BorderLayout.EAST);
        topo.add(toolbar, BorderLayout.SOUTH);
        card.add(topo, BorderLayout.NORTH);

        // Tabela
        TargetTableModel.configurarRenderersEColunas(tblAlvos);
        configurarTeclado();
        JScrollPane scroll = new JScrollPane(tblAlvos);
        scroll.setBorder(BorderFactory.createLineBorder(SwingThemeHelper.BORDER_SUBTLE, 1, true));
        scroll.getViewport().setBackground(SwingThemeHelper.BG_CARD);

        // Faixa de falhas (só aparece quando algo falhou): filtro + recoleta, acima da tabela
        faixaFalhas.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE,
                "arc: 6; background: " + SwingThemeHelper.hex(SwingThemeHelper.STATUS_ERR_BG)
                        + "; border: 7,12,7,8," + SwingThemeHelper.hex(SwingThemeHelper.STATUS_ERR_BORDER) + ",1,6");
        lblFalhas.setIcon(SwingThemeHelper.criarIcone(IconKind.ALERT, SwingThemeHelper.STATUS_ERR_FG));
        lblFalhas.setIconTextGap(8);
        lblFalhas.setForeground(SwingThemeHelper.STATUS_ERR_FG);
        JPanel acoesFalhas = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        acoesFalhas.setOpaque(false);
        acoesFalhas.add(btnSoFalhas);
        acoesFalhas.add(btnRecoletarFalhas);
        faixaFalhas.add(lblFalhas, BorderLayout.CENTER);
        faixaFalhas.add(acoesFalhas, BorderLayout.EAST);
        faixaFalhas.setVisible(false);

        JPanel centro = new JPanel(new BorderLayout(0, 10));
        centro.setOpaque(false);
        centro.add(faixaFalhas, BorderLayout.NORTH);
        centro.add(scroll, BorderLayout.CENTER);
        card.add(centro, BorderLayout.CENTER);

        // Rodapé
        SwingThemeHelper.estilizarBotaoLink(btnMostrarTodos);
        btnMostrarTodos.setVisible(false);
        btnMostrarTodos.addActionListener(e -> setSoFalhas(false));
        JPanel resumo = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        resumo.setOpaque(false);
        resumo.add(lblResumo);
        resumo.add(btnMostrarTodos);
        // resumo e atalhos em linhas separadas: assim nenhum dos dois é cortado em janelas estreitas
        JPanel rodape = new JPanel(new BorderLayout(0, 4));
        rodape.setOpaque(false);
        rodape.add(resumo, BorderLayout.NORTH);
        rodape.add(lblAtalhos, BorderLayout.SOUTH);
        card.add(rodape, BorderLayout.SOUTH);

        add(card, BorderLayout.CENTER);

        btnAdicionarIp.addActionListener(e -> abrirDialogAdicionarIp());
        btnRemoverIp.addActionListener(e -> removerLinhasSelecionadas());
        miMarcarTodos.addActionListener(e -> tableModel.selecionarTodos(true));
        miDesmarcar.addActionListener(e -> tableModel.selecionarTodos(false));
        miSomenteFalhos.addActionListener(e -> tableModel.selecionarApenasFalhos());
        btnDefinirSenha.addActionListener(e -> abrirDialogDefinirSenhaEmLote());

        tblAlvos.getSelectionModel().addListSelectionListener(e -> atualizarBotoes());
        tableModel.addTableModelListener(e -> {
            // filtro ligado e nenhuma falha restante (senha corrigida e recoletada): volta para a lista toda
            if (btnSoFalhas.isSelected() && !executando && tableModel.contarPrecisamAtencao() == 0) {
                javax.swing.SwingUtilities.invokeLater(() -> setSoFalhas(false));
            }
            atualizarResumo();
            atualizarBotoes();
        });
        atualizarResumo();
        atualizarBotoes();
    }

    private static JPanel divisor() {
        JPanel d = new JPanel();
        d.setBackground(SwingThemeHelper.BORDER_STRONG);
        d.setPreferredSize(new java.awt.Dimension(1, 22));
        return d;
    }

    private static void pintarEstadoVazio(Graphics g, JTable t) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font base = t.getFont();
        if (t.getModel().getRowCount() > 0) {
            g2.dispose();
            return; // filtro ativo sem resultados: o rodapé já explica
        }
        String l1 = "Nenhum equipamento na lista ainda";
        String l2 = "Inicie uma coleta na aba Coleta, clique em Adicionar ou importe uma lista em Arquivo.";
        g2.setFont(SwingThemeHelper.fonteSemibold(base.getSize2D() + 1.5f));
        FontMetrics fm1 = g2.getFontMetrics();
        int cy = Math.max(60, t.getVisibleRect().height / 2 - 10);
        g2.setColor(SwingThemeHelper.TEXT_SECONDARY);
        g2.drawString(l1, (t.getWidth() - fm1.stringWidth(l1)) / 2, cy);
        g2.setFont(base);
        FontMetrics fm2 = g2.getFontMetrics();
        g2.setColor(SwingThemeHelper.TEXT_MUTED);
        g2.drawString(l2, (t.getWidth() - fm2.stringWidth(l2)) / 2, cy + fm1.getHeight() + 2);
        g2.dispose();
    }

    private void atualizarResumo() {
        int total = tableModel.getRowCount();
        if (total == 0) {
            lblResumo.setText(" ");
            return;
        }
        int marcados = tableModel.contarSelecionados();
        int ok = tableModel.contarPorEstado(EstadoColeta.OK);
        int falhas = tableModel.contarPorEstado(EstadoColeta.FALHA);
        StringBuilder sb = new StringBuilder("<html>");
        if (btnSoFalhas.isSelected()) {
            int pendentes = tableModel.contarPrecisamAtencao();
            sb.append("<font color='").append(SwingThemeHelper.hex(SwingThemeHelper.STATUS_ERR_FG)).append("'>")
                    .append("Mostrando só os ").append(pendentes).append(" que precisam de ajuste")
                    .append("</font> (de ").append(total).append(")");
            sb.append("</html>");
            lblResumo.setText(sb.toString());
            return;
        }
        sb.append(total).append(total == 1 ? " equipamento" : " equipamentos")
                .append("  ·  ").append(marcados).append(marcados == 1 ? " marcado" : " marcados");
        if (ok > 0) {
            sb.append("  ·  <font color='").append(SwingThemeHelper.hex(SwingThemeHelper.STATUS_OK_FG)).append("'>")
                    .append(ok).append(" coletado(s)</font>");
        }
        if (falhas > 0) {
            sb.append("  ·  <font color='").append(SwingThemeHelper.hex(SwingThemeHelper.STATUS_ERR_FG)).append("'>")
                    .append(falhas).append(" com falha</font>");
        }
        sb.append("</html>");
        lblResumo.setText(sb.toString());
    }

    private void atualizarBotoes() {
        boolean temLinhas = tableModel.getRowCount() > 0;
        btnAdicionarIp.setEnabled(!executando);
        btnRemoverIp.setEnabled(!executando && tblAlvos.getSelectedRowCount() > 0);
        btnMarcar.setEnabled(!executando && temLinhas);
        btnDefinirSenha.setEnabled(!executando && temLinhas);
        btnArquivo.setEnabled(!executando);
        miExportarTxt.setEnabled(temLinhas);
        int falhas = tableModel.contarPorEstado(EstadoColeta.FALHA);
        int corrigidos = tableModel.contarPendentesRecoleta();
        int pendentes = falhas + corrigidos;
        btnRecoletarFalhas.setText("Recoletar falhas (" + pendentes + ")");
        btnRecoletarFalhas.setVisible(pendentes > 0);
        btnRecoletarFalhas.setEnabled(!executando);
        btnSoFalhas.setText("Só falhas (" + pendentes + ")");
        btnSoFalhas.setEnabled(!executando);
        faixaFalhas.setVisible(pendentes > 0);
        atualizarFaixaFalhas(falhas, corrigidos);
        lblAtalhos.setVisible(temLinhas && !executando);
        btnMostrarTodos.setVisible(btnSoFalhas.isSelected());
        btnMostrarTodos.setText("Mostrar todos (" + tableModel.getRowCount() + ")");
        if (executando) {
            btnColetarSelecionados.setEnabled(false);
        } else {
            atualizarBotaoColeta();
        }
    }

    public void finalizarEdicaoTabela() {
        if (tblAlvos.isEditing() && tblAlvos.getCellEditor() != null) {
            tblAlvos.getCellEditor().stopCellEditing();
        }
    }

    public void setModoIncremental(boolean incremental) {
        this.modoIncrementalAtivo = incremental;
        btnColetarSelecionados.setText(incremental ? "Recoletar marcados" : "Coletar marcados");
        btnColetarSelecionados.setToolTipText(incremental
                ? "Coleta só os marcados e mantém os dados dos que já foram coletados com sucesso"
                : "Coleta os equipamentos marcados na lista");
        atualizarBotaoColeta();
    }

    public void setEstadoExecucao(boolean executando) {
        boolean terminou = this.executando && !executando;
        this.executando = executando;
        if (executando) {
            // durante a coleta a lista inteira fica visível (o status de cada um muda ao vivo)
            setSoFalhas(false);
        } else if (terminou) {
            // fim da coleta: se algo falhou, mostra só o que precisa ser ajustado
            setSoFalhas(tableModel.contarPrecisamAtencao() > 0);
        }
        atualizarBotoes();
    }

    /** Liga/desliga o filtro que mostra só os equipamentos com falha. */
    public void setSoFalhas(boolean soFalhas) {
        finalizarEdicaoTabela();
        btnSoFalhas.setSelected(soFalhas);
        aplicarFiltro();
    }

    public boolean isSoFalhas() {
        return btnSoFalhas.isSelected();
    }

    JTable getTabela() {
        return tblAlvos;
    }

    /** Quantidade de linhas visíveis na tabela (depois do filtro). */
    int linhasVisiveis() {
        return tblAlvos.getRowCount();
    }

    private void aplicarFiltro() {
        if (btnSoFalhas.isSelected()) {
            sorter.setRowFilter(new RowFilter<TargetTableModel, Integer>() {
                @Override
                public boolean include(Entry<? extends TargetTableModel, ? extends Integer> entry) {
                    int i = entry.getIdentifier();
                    List<DiscoveredTargetRow> linhas = entry.getModel().getLinhas();
                    return i < linhas.size() && TargetTableModel.precisaAtencao(linhas.get(i));
                }
            });
        } else {
            sorter.setRowFilter(null);
        }
        atualizarResumo();
        atualizarBotoes();
    }

    /** Texto e cor da faixa: vermelha enquanto há falha; âmbar quando só faltam recoletar os corrigidos. */
    private void atualizarFaixaFalhas(int falhas, int corrigidos) {
        String texto;
        if (falhas > 0 && corrigidos > 0) {
            texto = falhas + " com falha e " + corrigidos + " com senha corrigida. Enter define a senha; F5 recoleta todos.";
        } else if (falhas > 0) {
            texto = (falhas == 1 ? "1 equipamento falhou" : falhas + " equipamentos falharam")
                    + ". Enter define a senha da linha selecionada; F5 recoleta.";
        } else {
            texto = (corrigidos == 1 ? "1 equipamento com senha corrigida" : corrigidos + " equipamentos com senha corrigida")
                    + ", pronto(s) para recoletar (F5).";
        }
        SwingThemeHelper.TipoStatus tipo = falhas > 0 ? SwingThemeHelper.TipoStatus.ERR : SwingThemeHelper.TipoStatus.WARN;
        Color[] cores = SwingThemeHelper.coresStatus(tipo);
        faixaFalhas.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE,
                "arc: 6; background: " + SwingThemeHelper.hex(cores[0])
                        + "; border: 7,12,7,8," + SwingThemeHelper.hex(cores[2]) + ",1,6");
        lblFalhas.setForeground(cores[1]);
        lblFalhas.setIcon(SwingThemeHelper.criarIcone(falhas > 0 ? IconKind.ALERT : IconKind.REFRESH, cores[1]));
        lblFalhas.setText(texto);
    }

    // ------------------------------------------------------------------ Teclado

    /**
     * Atalhos da tabela, para corrigir falhas sem o mouse:
     * Enter = definir senha das linhas selecionadas; Espaço = marcar/desmarcar; Delete = remover.
     * Tab/Shift+Tab saem da tabela para o próximo botão (em vez de andar de célula em célula).
     */
    private void configurarTeclado() {
        tblAlvos.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        tblAlvos.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        tblAlvos.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        InputMap im = tblAlvos.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = tblAlvos.getActionMap();
        vincular(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "tp.definirSenha", this::abrirDialogDefinirSenhaEmLote);
        vincular(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "tp.marcar", this::alternarMarcacaoSelecionados);
        // o espaço digitado não deve abrir a edição da célula
        vincular(im, am, KeyStroke.getKeyStroke(' '), "tp.ignorarEspaco", () -> { });
        vincular(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "tp.remover", this::removerLinhasSelecionadas);
        // F2 edita direto o Usuário da linha selecionada (e o Tab segue para a Senha)
        vincular(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "tp.editarUsuario", () -> {
            int v = tblAlvos.getSelectedRow();
            if (v >= 0) editarCelula(v, tblAlvos.convertColumnIndexToView(TargetTableModel.COL_USUARIO));
        });
        configurarEditoresCredencial();
    }

    // ------------------------------------------------------------- Edição na célula

    /**
     * Editores de Usuário e Senha com o visual do tema e navegação de planilha: Tab salva a célula e
     * abre a próxima (Usuário → Senha → Usuário da linha de baixo ...), Shift+Tab volta. Na última
     * Senha (ou no primeiro Usuário, com Shift+Tab) o cursor sai da tabela.
     */
    private void configurarEditoresCredencial() {
        // digitar numa célula já põe o cursor dentro do editor (assim o Tab é tratado por ele)
        tblAlvos.setSurrendersFocusOnKeystroke(true);
        JTextField campoUsuario = new JTextField();
        javax.swing.DefaultCellEditor editorUsuario = new javax.swing.DefaultCellEditor(campoUsuario);
        editorUsuario.setClickCountToStart(2);
        tblAlvos.getColumnModel().getColumn(TargetTableModel.COL_USUARIO).setCellEditor(editorUsuario);
        prepararEditor(campoUsuario);
        javax.swing.table.TableCellEditor editorSenha =
                tblAlvos.getColumnModel().getColumn(TargetTableModel.COL_SENHA).getCellEditor();
        if (editorSenha instanceof javax.swing.DefaultCellEditor) {
            prepararEditor((JComponent) ((javax.swing.DefaultCellEditor) editorSenha).getComponent());
        }
    }

    private void prepararEditor(JComponent campo) {
        campo.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(SwingThemeHelper.ACCENT_PRIMARY, 1),
                BorderFactory.createEmptyBorder(0, 13, 0, 8)));
        campo.setBackground(SwingThemeHelper.BG_CARD);
        campo.setForeground(SwingThemeHelper.TEXT_PRIMARY);
        // o Tab deixa de trocar o foco: ele navega entre as células
        campo.setFocusTraversalKeysEnabled(false);
        InputMap im = campo.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = campo.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "tp.proximaCelula");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK), "tp.celulaAnterior");
        am.put("tp.proximaCelula", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moverEdicao(true);
            }
        });
        am.put("tp.celulaAnterior", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moverEdicao(false);
            }
        });
    }

    /** Salva a célula em edição e abre a próxima (ou a anterior) entre Usuário e Senha. */
    void moverEdicao(boolean frente) {
        int linha = tblAlvos.getEditingRow();
        int coluna = tblAlvos.getEditingColumn();
        if (linha < 0 || coluna < 0) return;
        int colUsuario = tblAlvos.convertColumnIndexToView(TargetTableModel.COL_USUARIO);
        int colSenha = tblAlvos.convertColumnIndexToView(TargetTableModel.COL_SENHA);
        int modelo = tblAlvos.convertRowIndexToModel(linha);
        finalizarEdicaoTabela();
        // depois de salvar, a linha pode ter mudado de posição (filtro): reencontra pelo modelo
        int atual = modelo < tableModel.getRowCount() ? tblAlvos.convertRowIndexToView(modelo) : -1;
        if (atual < 0) atual = Math.min(linha, tblAlvos.getRowCount() - 1);
        int alvoLinha;
        int alvoColuna;
        if (frente) {
            if (coluna == colUsuario) { alvoLinha = atual; alvoColuna = colSenha; }
            else { alvoLinha = atual + 1; alvoColuna = colUsuario; }
        } else {
            if (coluna == colSenha) { alvoLinha = atual; alvoColuna = colUsuario; }
            else { alvoLinha = atual - 1; alvoColuna = colSenha; }
        }
        if (alvoLinha < 0 || alvoLinha >= tblAlvos.getRowCount()) {
            // fim da lista: sai da tabela, como um Tab normal
            if (atual >= 0) tblAlvos.setRowSelectionInterval(atual, atual);
            if (frente) tblAlvos.transferFocus();
            else tblAlvos.transferFocusBackward();
            return;
        }
        editarCelula(alvoLinha, alvoColuna);
    }

    /** Abre a edição da célula com o cursor dentro e o texto selecionado (digitar substitui). */
    private void editarCelula(int linha, int coluna) {
        tblAlvos.setRowSelectionInterval(linha, linha);
        tblAlvos.scrollRectToVisible(tblAlvos.getCellRect(linha, coluna, true));
        if (tblAlvos.editCellAt(linha, coluna)) {
            java.awt.Component ed = tblAlvos.getEditorComponent();
            if (ed != null) {
                ed.requestFocusInWindow();
                if (ed instanceof javax.swing.text.JTextComponent) ((javax.swing.text.JTextComponent) ed).selectAll();
            }
        }
    }

    /** Ação de teclado da tabela; fica inativa durante a edição de uma célula ou da coleta. */
    private void vincular(InputMap im, ActionMap am, KeyStroke tecla, String nome, Runnable acao) {
        im.put(tecla, nome);
        am.put(nome, new AbstractAction() {
            @Override
            public boolean isEnabled() {
                return !tblAlvos.isEditing() && !executando;
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                acao.run();
            }
        });
    }

    /** Espaço: marca todas as linhas selecionadas (ou desmarca, se já estavam todas marcadas). */
    private void alternarMarcacaoSelecionados() {
        int[] sel = tblAlvos.getSelectedRows();
        if (sel.length == 0) return;
        List<Integer> modelo = new ArrayList<>();
        boolean algumDesmarcado = false;
        for (int v : sel) {
            int m = tblAlvos.convertRowIndexToModel(v);
            modelo.add(m);
            if (!tableModel.getLinhas().get(m).isSelecionado()) algumDesmarcado = true;
        }
        for (int m : modelo) {
            tableModel.setValueAt(algumDesmarcado, m, TargetTableModel.COL_SELECIONADO);
        }
    }

    /** F5 na aba Equipamentos: recoleta falhas e corrigidos; sem pendências, coleta os marcados. */
    public void acionarRecoleta() {
        if (executando) return;
        finalizarEdicaoTabela();
        if (btnRecoletarFalhas.isVisible() && btnRecoletarFalhas.isEnabled()) {
            btnRecoletarFalhas.doClick();
        } else if (btnColetarSelecionados.isEnabled()) {
            btnColetarSelecionados.doClick();
        }
    }

    /**
     * Põe o cursor na tabela para usar o teclado (setas, Enter, Espaço).
     *
     * @param primeiraLinha seleciona a primeira linha visível (ex.: ao abrir a correção de falhas)
     */
    public void focarTabela(boolean primeiraLinha) {
        finalizarEdicaoTabela();
        if (tblAlvos.getRowCount() > 0 && (primeiraLinha || tblAlvos.getSelectedRow() < 0)) {
            tblAlvos.setRowSelectionInterval(0, 0);
            tblAlvos.scrollRectToVisible(tblAlvos.getCellRect(0, 0, true));
        }
        tblAlvos.requestFocusInWindow();
    }

    /** Ordem visual atual (índices do modelo) para achar a próxima linha depois de uma alteração. */
    private List<Integer> ordemVisivel() {
        List<Integer> r = new ArrayList<>();
        for (int v = 0; v < tblAlvos.getRowCount(); v++) r.add(tblAlvos.convertRowIndexToModel(v));
        return r;
    }

    /** Seleciona a primeira linha ainda visível depois da última alterada e devolve o foco à tabela. */
    private void selecionarProxima(List<Integer> ordemAntes, List<Integer> alterados) {
        int ultimaPos = -1;
        for (int m : alterados) ultimaPos = Math.max(ultimaPos, ordemAntes.indexOf(m));
        int alvoView = -1;
        for (int p = ultimaPos + 1; p < ordemAntes.size() && alvoView < 0; p++) {
            int m = ordemAntes.get(p);
            if (m < tableModel.getRowCount()) alvoView = tblAlvos.convertRowIndexToView(m);
        }
        if (alvoView < 0 && !alterados.isEmpty()) {
            // era a última: fica nela (se ainda aparece) ou na última visível
            int m = alterados.get(alterados.size() - 1);
            alvoView = m < tableModel.getRowCount() ? tblAlvos.convertRowIndexToView(m) : -1;
            if (alvoView < 0) alvoView = tblAlvos.getRowCount() - 1;
        }
        if (alvoView >= 0 && alvoView < tblAlvos.getRowCount()) {
            tblAlvos.setRowSelectionInterval(alvoView, alvoView);
            tblAlvos.scrollRectToVisible(tblAlvos.getCellRect(alvoView, 0, true));
        }
        tblAlvos.requestFocusInWindow();
    }

    private void atualizarBotaoColeta() {
        boolean temMarcados = tableModel.contarSelecionados() > 0;
        boolean podeFinalizarComPassivo = modoIncrementalAtivo
                && (tableModel.contarPorEstado(EstadoColeta.SEM_SENHA) > 0);
        btnColetarSelecionados.setEnabled(temMarcados || podeFinalizarComPassivo);
    }

    public AbstractButton getBtnImportarTxt() {
        return miImportarTxt;
    }

    public AbstractButton getBtnExportarTxt() {
        return miExportarTxt;
    }

    public JButton getBtnColetarSelecionados() {
        return btnColetarSelecionados;
    }

    private void abrirDialogAdicionarIp() {
        JTextField fIp = new JTextField(18);
        JTextField fUser = new JTextField(16);
        JPasswordField fPass = new JPasswordField(16);
        SwingThemeHelper.configurarPlaceholder(fIp, "10.1.40.203 ou 10.1.31.19:65019");
        SwingThemeHelper.configurarPlaceholder(fUser, "Deixe vazio para usar o padrão");
        SwingThemeHelper.configurarCampoSenhaComOlho(fPass, "Deixe vazio para usar o padrão");

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 0, 6, 12);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0; g.gridy = 0; form.add(SwingThemeHelper.criarRotuloCampo("Endereço IP"), g);
        g.gridx = 1; form.add(fIp, g);
        g.gridx = 0; g.gridy = 1; form.add(SwingThemeHelper.criarRotuloCampo("Usuário"), g);
        g.gridx = 1; form.add(fUser, g);
        g.gridx = 0; g.gridy = 2; form.add(SwingThemeHelper.criarRotuloCampo("Senha"), g);
        g.gridx = 1; form.add(fPass, g);
        g.gridx = 1; g.gridy = 3;
        form.add(SwingThemeHelper.criarTextoAjuda("Porta diferente de 22/23? Use IP:porta.  ·  Enter adiciona, Esc cancela."), g);

        SwitchTarget[] alvo = new SwitchTarget[1];
        String escolha = DialogoFormulario.exibir(this, "Adicionar equipamento", form, fIp,
                Compat.listOf(DialogoFormulario.Acao.cancelar(), DialogoFormulario.Acao.principal("Adicionar", "adicionar")),
                id -> {
                    try {
                        alvo[0] = TargetTableModel.parsearEnderecoTarget(fIp.getText(), fUser.getText(),
                                new String(fPass.getPassword()));
                        return null;
                    } catch (IllegalArgumentException ex) {
                        SwingThemeHelper.marcarCampoComErro(fIp);
                        return ex.getMessage();
                    }
                });
        if (escolha != null && alvo[0] != null) {
            tableModel.mesclarAlvosImportados(Compat.listOf(alvo[0]), "Manual");
        }
        tblAlvos.requestFocusInWindow();
    }

    private void removerLinhasSelecionadas() {
        int[] sel = tblAlvos.getSelectedRows();
        if (sel.length == 0) return;
        if (sel.length > 1) {
            int op = JOptionPane.showConfirmDialog(this,
                    "Remover " + sel.length + " equipamentos da lista?",
                    "Remover equipamentos", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (op != JOptionPane.OK_OPTION) {
                tblAlvos.requestFocusInWindow();
                return;
            }
        }
        int primeira = sel[0];
        tableModel.removerLinhas(sel, tblAlvos);
        int n = tblAlvos.getRowCount();
        if (n > 0) {
            int v = Math.min(primeira, n - 1);
            tblAlvos.setRowSelectionInterval(v, v);
        }
        tblAlvos.requestFocusInWindow();
    }

    /** Usuário em comum das linhas (para já vir preenchido); vazio se usam o padrão ou se diferem. */
    private String usuarioComum(List<Integer> modelIndices) {
        String comum = null;
        for (int m : modelIndices) {
            String u = tableModel.getLinhas().get(m).getUsuario();
            u = u == null ? "" : u.trim();
            if (comum == null) comum = u;
            else if (!comum.equals(u)) return "";
        }
        return comum == null ? "" : comum;
    }

    /**
     * Definir senha pelo teclado: abre com o cursor na Senha, Enter aplica e passa para a próxima linha,
     * Alt+R aplica e já recoleta, Esc cancela.
     */
    private void abrirDialogDefinirSenhaEmLote() {
        finalizarEdicaoTabela();
        List<Integer> alvosModel = resolverLinhasAlvoLote();
        if (alvosModel.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Selecione linhas na tabela (ou marque as caixas) dos equipamentos que deseja atualizar.",
                    "Nenhum equipamento escolhido",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JRadioButton rbCredencial = new JRadioButton("Usar este usuário e senha", true);
        JRadioButton rbSemSenha = new JRadioButton("Não tenho a senha (mostrar na topologia sem coletar)");
        rbCredencial.setMnemonic(KeyEvent.VK_U);
        rbSemSenha.setMnemonic(KeyEvent.VK_N);
        ButtonGroup grp = new ButtonGroup();
        grp.add(rbCredencial);
        grp.add(rbSemSenha);

        JTextField fUser = new JTextField(16);
        JPasswordField fPass = new JPasswordField(16);
        fUser.setText(usuarioComum(alvosModel));
        SwingThemeHelper.configurarPlaceholder(fUser, "Padrão da aba Coleta");
        SwingThemeHelper.configurarCampoSenhaComOlho(fPass, "Nova senha");

        rbCredencial.addActionListener(e -> {
            fUser.setEnabled(true);
            fPass.setEnabled(true);
            fPass.requestFocusInWindow();
        });
        rbSemSenha.addActionListener(e -> {
            fUser.setEnabled(false);
            fPass.setEnabled(false);
        });

        String alvoTexto;
        if (alvosModel.size() == 1) {
            DiscoveredTargetRow r = tableModel.getLinhas().get(alvosModel.get(0));
            alvoTexto = r.getIpPorta() + (Compat.isBlank(r.getProdutoModelo()) ? "" : "  ·  " + r.getProdutoModelo());
        } else {
            alvoTexto = "Aplicar em " + alvosModel.size() + " equipamentos";
        }
        JLabel lblAlvo = SwingThemeHelper.criarRotuloCampo(alvoTexto);
        lblAlvo.setForeground(SwingThemeHelper.TEXT_PRIMARY);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 0, 4, 0);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridwidth = 2;
        g.gridx = 0; g.gridy = 0;
        form.add(lblAlvo, g);
        g.gridy = 1; g.insets = new Insets(10, 0, 4, 0);
        form.add(rbCredencial, g);
        g.gridwidth = 1;
        g.insets = new Insets(5, 28, 5, 12);
        g.gridy = 2; g.gridx = 0; form.add(SwingThemeHelper.criarRotuloCampo("Usuário"), g);
        g.insets = new Insets(5, 0, 5, 0);
        g.gridx = 1; form.add(fUser, g);
        g.insets = new Insets(5, 28, 5, 12);
        g.gridy = 3; g.gridx = 0; form.add(SwingThemeHelper.criarRotuloCampo("Senha"), g);
        g.insets = new Insets(5, 0, 5, 0);
        g.gridx = 1; form.add(fPass, g);
        g.gridwidth = 2;
        g.gridy = 4; g.gridx = 0;
        g.insets = new Insets(10, 0, 4, 0);
        form.add(rbSemSenha, g);
        g.gridy = 5; g.insets = new Insets(10, 0, 0, 0);
        form.add(SwingThemeHelper.criarTextoAjuda("Enter aplica e vai para a próxima  ·  Alt+R aplica e recoleta  ·  Esc cancela"), g);

        List<Integer> ordemAntes = ordemVisivel();
        String escolha = DialogoFormulario.exibir(this, "Definir senha", form, fPass,
                Compat.listOf(
                        DialogoFormulario.Acao.cancelar(),
                        DialogoFormulario.Acao.secundaria("Aplicar e recoletar", "recoletar", KeyEvent.VK_R),
                        DialogoFormulario.Acao.principal("Aplicar", "aplicar")),
                id -> {
                    if (rbSemSenha.isSelected()) return null;
                    if (Compat.isBlank(fUser.getText()) && fPass.getPassword().length == 0) {
                        SwingThemeHelper.marcarCampoComErro(fPass);
                        return "Digite a senha (ou o usuário) para aplicar.";
                    }
                    return null;
                });
        if (escolha == null) {
            tblAlvos.requestFocusInWindow();
            return;
        }

        if (rbSemSenha.isSelected()) {
            tableModel.marcarComoSemSenha(alvosModel);
        } else {
            tableModel.aplicarCredenciaisEmLinhas(alvosModel, fUser.getText().trim(), new String(fPass.getPassword()));
        }
        if ("recoletar".equals(escolha)) {
            acionarRecoleta();
            return;
        }
        selecionarProxima(ordemAntes, alvosModel);
    }

    private List<Integer> resolverLinhasAlvoLote() {
        List<Integer> indices = new ArrayList<>();
        int[] sel = tblAlvos.getSelectedRows();
        if (sel != null && sel.length > 0) {
            for (int v : sel) {
                int m = tblAlvos.convertRowIndexToModel(v);
                if (m >= 0) indices.add(m);
            }
            return indices;
        }
        List<DiscoveredTargetRow> linhas = tableModel.getLinhas();
        for (int i = 0; i < linhas.size(); i++) {
            // com o filtro "Só falhas" ligado, vale só o que está visível
            if (linhas.get(i).isSelecionado() && tblAlvos.convertRowIndexToView(i) >= 0) {
                indices.add(i);
            }
        }
        return indices;
    }
}

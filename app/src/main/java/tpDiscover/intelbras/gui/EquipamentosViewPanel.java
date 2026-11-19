package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.gui.DiscoveredTargetRow.EstadoColeta;
import tpDiscover.intelbras.gui.SwingThemeHelper.IconKind;

import javax.swing.AbstractButton;
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
                "Marque os que devem ser coletados. Usuário/senha em branco usam os da aba Coleta (clique duas vezes para editar)."),
                BorderLayout.NORTH);

        SwingThemeHelper.estilizarBotaoCompacto(btnAdicionarIp);
        SwingThemeHelper.estilizarBotaoCompacto(btnRemoverIp);
        SwingThemeHelper.estilizarBotaoCompacto(btnDefinirSenha);
        btnAdicionarIp.setIcon(SwingThemeHelper.criarIcone(IconKind.PLUS, SwingThemeHelper.TEXT_SECONDARY));
        btnRemoverIp.setIcon(SwingThemeHelper.criarIcone(IconKind.MINUS, SwingThemeHelper.TEXT_SECONDARY));
        btnDefinirSenha.setIcon(SwingThemeHelper.criarIcone(IconKind.KEY, SwingThemeHelper.TEXT_SECONDARY));
        btnAdicionarIp.setToolTipText("Adicionar um equipamento pelo IP");
        btnRemoverIp.setToolTipText("Remover as linhas selecionadas");
        btnDefinirSenha.setToolTipText("Definir usuário/senha para as linhas selecionadas (ou marcadas), ou marcá-las como sem senha");

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
        btnRecoletarFalhas.setToolTipText("Marca só os equipamentos que falharam e coleta de novo (depois de corrigir as senhas)");
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
        JPanel rodape = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rodape.setOpaque(false);
        rodape.add(lblResumo);
        rodape.add(btnMostrarTodos);
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
            if (btnSoFalhas.isSelected() && !executando && tableModel.contarPorEstado(EstadoColeta.FALHA) == 0) {
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
            sb.append("<font color='").append(SwingThemeHelper.hex(SwingThemeHelper.STATUS_ERR_FG)).append("'>")
                    .append("Mostrando só os ").append(falhas).append(falhas == 1 ? " que falhou" : " que falharam")
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
        btnRecoletarFalhas.setText("Recoletar falhas (" + falhas + ")");
        btnRecoletarFalhas.setVisible(falhas > 0);
        btnRecoletarFalhas.setEnabled(!executando);
        btnSoFalhas.setText("Só falhas (" + falhas + ")");
        btnSoFalhas.setEnabled(!executando);
        faixaFalhas.setVisible(falhas > 0);
        lblFalhas.setText(falhas == 1
                ? "1 equipamento falhou e precisa de ajuste: corrija a senha e recolete."
                : falhas + " equipamentos falharam e precisam de ajuste: corrija a senha e recolete.");
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
            setSoFalhas(tableModel.contarPorEstado(EstadoColeta.FALHA) > 0);
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
                    return i < linhas.size() && linhas.get(i).getEstado() == EstadoColeta.FALHA;
                }
            });
        } else {
            sorter.setRowFilter(null);
        }
        atualizarResumo();
        atualizarBotoes();
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
        g.insets = new Insets(6, 4, 6, 8);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0; g.gridy = 0; form.add(new JLabel("Endereço IP"), g);
        g.gridx = 1; form.add(fIp, g);
        g.gridx = 0; g.gridy = 1; form.add(new JLabel("Usuário"), g);
        g.gridx = 1; form.add(fUser, g);
        g.gridx = 0; g.gridy = 2; form.add(new JLabel("Senha"), g);
        g.gridx = 1; form.add(fPass, g);
        g.gridx = 1; g.gridy = 3;
        form.add(SwingThemeHelper.criarTextoAjuda("Porta diferente de 22/23? Use IP:porta."), g);

        while (true) {
            int res = JOptionPane.showConfirmDialog(this, form, "Adicionar equipamento",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) return;
            try {
                SwitchTarget alvo = TargetTableModel.parsearEnderecoTarget(fIp.getText(), fUser.getText(), new String(fPass.getPassword()));
                tableModel.mesclarAlvosImportados(Compat.listOf(alvo), "Manual");
                return;
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Verifique o endereço", JOptionPane.WARNING_MESSAGE);
                SwingThemeHelper.marcarCampoComErro(fIp);
            }
        }
    }

    private void removerLinhasSelecionadas() {
        int[] sel = tblAlvos.getSelectedRows();
        if (sel.length == 0) return;
        if (sel.length > 1) {
            int op = JOptionPane.showConfirmDialog(this,
                    "Remover " + sel.length + " equipamentos da lista?",
                    "Remover equipamentos", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (op != JOptionPane.OK_OPTION) return;
        }
        tableModel.removerLinhas(sel, tblAlvos);
    }

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
        JRadioButton rbSemSenha = new JRadioButton("Não tenho a senha (mostrar sem coletar na topologia)");
        ButtonGroup grp = new ButtonGroup();
        grp.add(rbCredencial);
        grp.add(rbSemSenha);

        JTextField fUser = new JTextField(16);
        JPasswordField fPass = new JPasswordField(16);
        SwingThemeHelper.configurarPlaceholder(fUser, "Manter o atual");
        SwingThemeHelper.configurarCampoSenhaComOlho(fPass, "Nova senha");

        rbCredencial.addActionListener(e -> {
            fUser.setEnabled(true);
            fPass.setEnabled(true);
        });
        rbSemSenha.addActionListener(e -> {
            fUser.setEnabled(false);
            fPass.setEnabled(false);
        });

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 4, 5, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridwidth = 2;
        g.gridx = 0; g.gridy = 0;
        form.add(SwingThemeHelper.criarTextoAjuda("Aplicar em " + alvosModel.size() + " equipamento(s):"), g);
        g.gridy = 1; form.add(rbCredencial, g);
        g.gridwidth = 1;
        g.insets = new Insets(5, 28, 5, 8);
        g.gridy = 2; g.gridx = 0; form.add(new JLabel("Usuário"), g);
        g.insets = new Insets(5, 4, 5, 4);
        g.gridx = 1; form.add(fUser, g);
        g.insets = new Insets(5, 28, 5, 8);
        g.gridy = 3; g.gridx = 0; form.add(new JLabel("Senha"), g);
        g.insets = new Insets(5, 4, 5, 4);
        g.gridx = 1; form.add(fPass, g);
        g.gridwidth = 2;
        g.gridy = 4; g.gridx = 0;
        g.insets = new Insets(12, 4, 5, 4);
        form.add(rbSemSenha, g);

        int res = JOptionPane.showConfirmDialog(
                this, form, "Definir senha",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) {
            return;
        }

        if (rbSemSenha.isSelected()) {
            tableModel.marcarComoSemSenha(alvosModel);
            return;
        }
        String novoUser = fUser.getText().trim();
        String novaPass = new String(fPass.getPassword());
        if (Compat.isBlank(novoUser) && novaPass.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Informe um usuário ou uma senha para aplicar.", "Atenção", JOptionPane.WARNING_MESSAGE);
            return;
        }
        tableModel.aplicarCredenciaisEmLinhas(alvosModel, novoUser, novaPass);
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

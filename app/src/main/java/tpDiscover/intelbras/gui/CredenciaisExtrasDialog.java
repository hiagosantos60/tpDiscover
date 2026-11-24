package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.coleta.CredencialAcesso;
import tpDiscover.intelbras.coleta.CredencialAcesso.Escopo;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

import tpDiscover.intelbras.util.Compat;
/**
 * Diálogo modal limpo para configurar credenciais extras (fallback automático)
 * usadas quando um equipamento (ex.: OLT com senha alterada ou switch de outro setor)
 * recusa a credencial padrão da coleta.
 */
public class CredenciaisExtrasDialog extends JDialog {

    private final CredencialTableModel tableModel;
    private final JTable table;
    private List<CredencialAcesso> resultado;

    private CredenciaisExtrasDialog(Window owner, List<CredencialAcesso> atuais) {
        super(owner, "Credenciais Extras (Fallback Automático)", ModalityType.APPLICATION_MODAL);
        this.tableModel = new CredencialTableModel(atuais);
        this.table = new JTable(tableModel);

        setMinimumSize(new Dimension(720, 360));
        setSize(760, 400);
        setLocationRelativeTo(owner);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(SwingThemeHelper.BG_CARD);
        root.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));

        JLabel dica = new JLabel("<html>Tentadas automaticamente nesta ordem caso a credencial padrão falhe na autenticação.<br>"
                + "Você pode ajustar as senhas do cliente ou adicionar combinações extras. "
                + "Deixe a senha em branco para equipamentos que pedem só o usuário.</html>");
        dica.setForeground(SwingThemeHelper.TEXT_SECONDARY);
        root.add(dica, BorderLayout.NORTH);

        configurarTabela();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(SwingThemeHelper.BORDER_SUBTLE, 1, true));
        root.add(scroll, BorderLayout.CENTER);

        // Uma única linha: ações da tabela à esquerda, Cancelar/Salvar à direita (sem sobreposição)
        javax.swing.Box rodape = javax.swing.Box.createHorizontalBox();
        JButton btnAdicionar = new JButton("Adicionar");
        JButton btnRemover = new JButton("Remover");
        JButton btnRestaurar = new JButton("Restaurar Padrão");
        SwingThemeHelper.estilizarBotaoSecundario(btnAdicionar);
        SwingThemeHelper.estilizarBotaoSecundario(btnRemover);
        SwingThemeHelper.estilizarBotaoSecundario(btnRestaurar);
        btnAdicionar.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.PLUS, SwingThemeHelper.TEXT_PRIMARY));
        btnRemover.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.MINUS, SwingThemeHelper.TEXT_PRIMARY));
        btnRestaurar.setToolTipText("Volta às credenciais padrão: GEPON/GEPON e admin/admin para OLTs, admin/admin para switches e APs");
        rodape.add(btnAdicionar);
        rodape.add(javax.swing.Box.createHorizontalStrut(6));
        rodape.add(btnRemover);
        rodape.add(javax.swing.Box.createHorizontalStrut(6));
        rodape.add(btnRestaurar);
        rodape.add(javax.swing.Box.createHorizontalGlue());
        rodape.add(javax.swing.Box.createHorizontalStrut(16));
        JButton btnCancelar = new JButton("Cancelar");
        JButton btnSalvar = new JButton("Salvar");
        SwingThemeHelper.estilizarBotaoSecundario(btnCancelar);
        SwingThemeHelper.estilizarBotaoPrimario(btnSalvar);
        rodape.add(btnCancelar);
        rodape.add(javax.swing.Box.createHorizontalStrut(8));
        rodape.add(btnSalvar);
        root.add(rodape, BorderLayout.SOUTH);

        btnAdicionar.addActionListener(e -> {
            finalizarEdicao();
            tableModel.adicionarVazia();
            int ultima = tableModel.getRowCount() - 1;
            if (ultima >= 0) {
                table.setRowSelectionInterval(ultima, ultima);
                // já entra digitando o usuário; Tab vai para a senha, Enter confirma a célula
                if (table.editCellAt(ultima, 1) && table.getEditorComponent() != null) {
                    table.getEditorComponent().requestFocusInWindow();
                }
            }
        });
        btnRemover.addActionListener(e -> {
            finalizarEdicao();
            int[] sel = table.getSelectedRows();
            if (sel.length == 0) {
                JOptionPane.showMessageDialog(this, "Selecione uma linha para remover.", "Atenção", JOptionPane.WARNING_MESSAGE);
                return;
            }
            tableModel.removerIndices(sel);
        });
        btnRestaurar.addActionListener(e -> {
            finalizarEdicao();
            tableModel.definirCredenciais(CredencialAcesso.presetsPadrao());
        });
        btnCancelar.addActionListener(e -> {
            resultado = null;
            dispose();
        });
        btnSalvar.addActionListener(e -> {
            finalizarEdicao();
            resultado = tableModel.extrairValidas();
            dispose();
        });

        setContentPane(root);
        getRootPane().setDefaultButton(btnSalvar);
        // Esc fecha sem salvar (na tabela, o primeiro Esc só cancela a edição da célula)
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "tp.cancelar");
        getRootPane().getActionMap().put("tp.cancelar", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                btnCancelar.doClick();
            }
        });
    }

    public static List<CredencialAcesso> exibirDialogo(Component parent, List<CredencialAcesso> atuais) {
        Window owner = parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
        CredenciaisExtrasDialog dialog = new CredenciaisExtrasDialog(owner, atuais);
        dialog.setVisible(true);
        return dialog.resultado;
    }

    private void configurarTabela() {
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        TableColumn colEscopo = table.getColumnModel().getColumn(0);
        colEscopo.setPreferredWidth(180);
        JComboBox<Escopo> cmbEscopo = new JComboBox<>(Escopo.values());
        colEscopo.setCellEditor(new DefaultCellEditor(cmbEscopo));

        TableColumn colUser = table.getColumnModel().getColumn(1);
        colUser.setPreferredWidth(150);

        TableColumn colPass = table.getColumnModel().getColumn(2);
        colPass.setPreferredWidth(180);
        JPasswordField pf = new JPasswordField();
        colPass.setCellEditor(new DefaultCellEditor(pf) {
            @Override
            public Component getTableCellEditorComponent(JTable tbl, Object value, boolean isSelected, int row, int column) {
                pf.setText(value != null ? value.toString() : "");
                return pf;
            }

            @Override
            public Object getCellEditorValue() {
                return new String(pf.getPassword());
            }
        });
        colPass.setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                String s = value != null ? value.toString() : "";
                setText(s.isEmpty() ? "(sem senha)" : Compat.repeat("•", Math.min(12, s.length())));
                setForeground(s.isEmpty() ? SwingThemeHelper.TEXT_MUTED : SwingThemeHelper.TEXT_PRIMARY);
            }
        });
    }

    private void finalizarEdicao() {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private static final class CredencialTableModel extends AbstractTableModel {
        private static final String[] COLUNAS = {"Aplicar em", "Usuário", "Senha"};
        private final List<CredRow> linhas = new ArrayList<>();

        CredencialTableModel(List<CredencialAcesso> iniciais) {
            definirCredenciais(iniciais);
        }

        void definirCredenciais(List<CredencialAcesso> lista) {
            linhas.clear();
            if (lista != null) {
                for (CredencialAcesso c : lista) {
                    if (c != null) {
                        linhas.add(new CredRow(c.ativa(), c.nomePerfil(), c.escopo(), c.usuario(), c.senha()));
                    }
                }
            }
            fireTableDataChanged();
        }

        void adicionarVazia() {
            linhas.add(new CredRow(true, "Credencial extra", Escopo.TODOS, "", ""));
            int idx = linhas.size() - 1;
            fireTableRowsInserted(idx, idx);
        }

        void removerIndices(int[] indices) {
            List<Integer> list = new ArrayList<>();
            for (int i : indices) list.add(i);
            list.sort((a, b) -> Integer.compare(b, a));
            for (int i : list) {
                if (i >= 0 && i < linhas.size()) {
                    linhas.remove(i);
                }
            }
            fireTableDataChanged();
        }

        List<CredencialAcesso> extrairValidas() {
            List<CredencialAcesso> out = new ArrayList<>();
            for (CredRow r : linhas) {
                if (!Compat.isBlank(r.usuario)) {
                    out.add(new CredencialAcesso(r.ativa, r.nomePerfil, r.usuario.trim(), r.senha, r.escopo));
                }
            }
            return Compat.listCopyOf(out);
        }

        @Override
        public int getRowCount() {
            return linhas.size();
        }

        @Override
        public int getColumnCount() {
            return COLUNAS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUNAS[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CredRow r = linhas.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return r.escopo;
                case 1:
                    return r.usuario;
                case 2:
                    return r.senha;
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            CredRow r = linhas.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    if (aValue instanceof Escopo) {
                        r.escopo = (Escopo) aValue;
                    }
                    break;
                case 1:
                    r.usuario = aValue != null ? aValue.toString().trim() : "";
                    break;
                case 2:
                    r.senha = aValue != null ? aValue.toString() : "";
                    break;
                default: {
                    break;
                }
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }

        private static final class CredRow {
            boolean ativa;
            String nomePerfil;
            Escopo escopo;
            String usuario;
            String senha;

            CredRow(boolean ativa, String nomePerfil, Escopo escopo, String usuario, String senha) {
                this.ativa = ativa;
                this.nomePerfil = (nomePerfil != null && !Compat.isBlank(nomePerfil)) ? nomePerfil : "Credencial extra";
                this.escopo = escopo != null ? escopo : Escopo.TODOS;
                this.usuario = usuario != null ? usuario : "";
                this.senha = senha != null ? senha : "";
            }
        }
    }
}

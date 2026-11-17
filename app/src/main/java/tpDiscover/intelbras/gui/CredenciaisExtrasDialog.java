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
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

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

        setMinimumSize(new Dimension(560, 340));
        setSize(600, 380);
        setLocationRelativeTo(owner);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(SwingThemeHelper.BG_WINDOW);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel dica = new JLabel("<html>Tentadas automaticamente nesta ordem caso a credencial padrão falhe na autenticação.<br>"
                + "Você pode ajustar as senhas das OLTs do cliente ou adicionar combinações extras.</html>");
        dica.setForeground(SwingThemeHelper.TEXT_MUTED);
        root.add(dica, BorderLayout.NORTH);

        configurarTabela();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(SwingThemeHelper.BORDER_SUBTLE, 1, true));
        root.add(scroll, BorderLayout.CENTER);

        JPanel rodape = new JPanel(new BorderLayout(8, 0));
        rodape.setOpaque(false);

        JPanel esquerda = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        esquerda.setOpaque(false);
        JButton btnAdicionar = new JButton("Adicionar");
        JButton btnRemover = new JButton("Remover");
        JButton btnRestaurar = new JButton("Restaurar Padrão");
        SwingThemeHelper.estilizarBotaoSecundario(btnAdicionar);
        SwingThemeHelper.estilizarBotaoSecundario(btnRemover);
        SwingThemeHelper.estilizarBotaoSecundario(btnRestaurar);
        btnAdicionar.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.PLUS, SwingThemeHelper.TEXT_PRIMARY));
        btnRemover.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.MINUS, SwingThemeHelper.TEXT_PRIMARY));
        esquerda.add(btnAdicionar);
        esquerda.add(btnRemover);
        esquerda.add(btnRestaurar);

        JPanel direita = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        direita.setOpaque(false);
        JButton btnCancelar = new JButton("Cancelar");
        JButton btnSalvar = new JButton("Salvar");
        SwingThemeHelper.estilizarBotaoSecundario(btnCancelar);
        SwingThemeHelper.estilizarBotaoPrimario(btnSalvar);
        direita.add(btnCancelar);
        direita.add(btnSalvar);

        rodape.add(esquerda, BorderLayout.WEST);
        rodape.add(direita, BorderLayout.EAST);
        root.add(rodape, BorderLayout.SOUTH);

        btnAdicionar.addActionListener(e -> {
            finalizarEdicao();
            tableModel.adicionarVazia();
            int ultima = tableModel.getRowCount() - 1;
            if (ultima >= 0) {
                table.setRowSelectionInterval(ultima, ultima);
                table.editCellAt(ultima, 1);
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
                setText(s.isEmpty() ? "" : "•".repeat(Math.min(12, s.length())));
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
                if (!r.usuario.isBlank()) {
                    out.add(new CredencialAcesso(r.ativa, r.nomePerfil, r.usuario.trim(), r.senha, r.escopo));
                }
            }
            return List.copyOf(out);
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
            return switch (columnIndex) {
                case 0 -> r.escopo;
                case 1 -> r.usuario;
                case 2 -> r.senha;
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            CredRow r = linhas.get(rowIndex);
            switch (columnIndex) {
                case 0 -> {
                    if (aValue instanceof Escopo esc) {
                        r.escopo = esc;
                    }
                }
                case 1 -> r.usuario = aValue != null ? aValue.toString().trim() : "";
                case 2 -> r.senha = aValue != null ? aValue.toString() : "";
                default -> {
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
                this.nomePerfil = (nomePerfil != null && !nomePerfil.isBlank()) ? nomePerfil : "Credencial extra";
                this.escopo = escopo != null ? escopo : Escopo.TODOS;
                this.usuario = usuario != null ? usuario : "";
                this.senha = senha != null ? senha : "";
            }
        }
    }
}

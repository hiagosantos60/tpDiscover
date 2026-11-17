package tpDiscover.intelbras.gui;

import com.formdev.flatlaf.FlatClientProperties;
import tpDiscover.intelbras.coleta.SwitchTarget;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * Aba "Equipamentos": tabela editável para revisar alvos descobertos,
 * ajustar usuário/senha/porta individuais e importar/exportar listas .txt.
 */
public class EquipamentosViewPanel extends JPanel {

    private final TargetTableModel tableModel;
    private final JTable tblAlvos;

    private final JButton btnAdicionarIp = new JButton("Adicionar");
    private final JButton btnRemoverIp = new JButton("Remover");
    private final JButton btnMarcarTodos = new JButton("Todos");
    private final JButton btnDesmarcar = new JButton("Desmarcar");
    private final JButton btnSomenteFalhos = new JButton("Falhos");
    private final JButton btnDefinirSenha = new JButton("Definir Senha");
    private final JButton btnImportarTxt = new JButton("Importar .txt");
    private final JButton btnExportarTxt = new JButton("Exportar .txt");
    private final JButton btnColetarSelecionados = new JButton("Coletar Selecionados");

    private boolean modoIncrementalAtivo;

    public EquipamentosViewPanel(TargetTableModel tableModel) {
        this.tableModel = tableModel;
        this.tblAlvos = new JTable(tableModel);
        setLayout(new BorderLayout(0, 10));
        setOpaque(false);

        JPanel card = SwingThemeHelper.criarCardArredondado();
        card.setLayout(new BorderLayout(0, 10));

        JPanel topo = new JPanel(new BorderLayout(8, 8));
        topo.setOpaque(false);

        JPanel textos = new JPanel(new BorderLayout(0, 2));
        textos.setOpaque(false);
        textos.add(SwingThemeHelper.criarTituloSecao("Equipamentos Descobertos e Credenciais Individuais"), BorderLayout.NORTH);
        JLabel dica = new JLabel("Células de Usuário/Senha em branco usam o padrão da aba Coleta. Use 'Definir Senha' para ajustar em lote ou marcar como passivo.");
        dica.setForeground(SwingThemeHelper.TEXT_MUTED);
        textos.add(dica, BorderLayout.SOUTH);
        topo.add(textos, BorderLayout.NORTH);

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setOpaque(false);

        JPanel esquerda = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        esquerda.setOpaque(false);
        for (JButton b : new JButton[]{btnAdicionarIp, btnRemoverIp, btnMarcarTodos, btnDesmarcar, btnSomenteFalhos, btnDefinirSenha, btnImportarTxt, btnExportarTxt}) {
            SwingThemeHelper.estilizarBotaoSecundario(b);
            b.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 8; background: #353942; foreground: #D5DBE5; borderColor: #464B56; margin: 5,9,5,9");
            esquerda.add(b);
        }
        btnAdicionarIp.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.PLUS, SwingThemeHelper.TEXT_PRIMARY));
        btnRemoverIp.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.MINUS, SwingThemeHelper.TEXT_PRIMARY));
        btnDefinirSenha.setToolTipText("Definir usuário/senha em lote nas linhas selecionadas (ou marcadas), ou marcar como Sem Senha (passivo)");

        SwingThemeHelper.estilizarBotaoPrimario(btnColetarSelecionados);
        btnColetarSelecionados.setIcon(SwingThemeHelper.criarIcone(SwingThemeHelper.IconKind.REFRESH, Color.WHITE));
        btnColetarSelecionados.setEnabled(false);

        toolbar.add(esquerda, BorderLayout.CENTER);
        toolbar.add(btnColetarSelecionados, BorderLayout.EAST);
        topo.add(toolbar, BorderLayout.SOUTH);

        card.add(topo, BorderLayout.NORTH);

        TargetTableModel.configurarRenderersEColunas(tblAlvos);
        JScrollPane scroll = new JScrollPane(tblAlvos);
        scroll.setBorder(BorderFactory.createLineBorder(SwingThemeHelper.BORDER_SUBTLE, 1, true));
        card.add(scroll, BorderLayout.CENTER);

        add(card, BorderLayout.CENTER);

        btnAdicionarIp.addActionListener(e -> abrirDialogAdicionarIp());
        btnRemoverIp.addActionListener(e -> removerLinhasSelecionadas());
        btnMarcarTodos.addActionListener(e -> tableModel.selecionarTodos(true));
        btnDesmarcar.addActionListener(e -> tableModel.selecionarTodos(false));
        btnSomenteFalhos.addActionListener(e -> tableModel.selecionarApenasFalhos());
        btnDefinirSenha.addActionListener(e -> abrirDialogDefinirSenhaEmLote());
    }

    public void finalizarEdicaoTabela() {
        if (tblAlvos.isEditing() && tblAlvos.getCellEditor() != null) {
            tblAlvos.getCellEditor().stopCellEditing();
        }
    }

    public void setModoIncremental(boolean incremental) {
        this.modoIncrementalAtivo = incremental;
        btnColetarSelecionados.setText(incremental ? "Completar / Recoletar" : "Coletar Selecionados");
        btnColetarSelecionados.setToolTipText(incremental
                ? "Coleta apenas os equipamentos marcados e preserva a telemetria dos já coletados (OK) na topologia"
                : "Coleta os equipamentos marcados na tabela");
        atualizarBotaoColeta();
    }

    public void setEstadoExecucao(boolean executando) {
        btnAdicionarIp.setEnabled(!executando);
        btnRemoverIp.setEnabled(!executando);
        btnMarcarTodos.setEnabled(!executando);
        btnDesmarcar.setEnabled(!executando);
        btnSomenteFalhos.setEnabled(!executando);
        btnDefinirSenha.setEnabled(!executando && tableModel.getRowCount() > 0);
        btnImportarTxt.setEnabled(!executando);
        btnExportarTxt.setEnabled(!executando);
        if (executando) {
            btnColetarSelecionados.setEnabled(false);
        } else {
            atualizarBotaoColeta();
        }
    }

    private void atualizarBotaoColeta() {
        boolean temMarcados = tableModel.contarSelecionados() > 0;
        boolean podeFinalizarComPassivo = modoIncrementalAtivo
                && (tableModel.contarPorEstado(DiscoveredTargetRow.EstadoColeta.SEM_SENHA) > 0);
        btnColetarSelecionados.setEnabled(temMarcados || podeFinalizarComPassivo);
    }

    public JButton getBtnImportarTxt() {
        return btnImportarTxt;
    }

    public JButton getBtnExportarTxt() {
        return btnExportarTxt;
    }

    public JButton getBtnColetarSelecionados() {
        return btnColetarSelecionados;
    }

    private void abrirDialogAdicionarIp() {
        JTextField fIp = new JTextField(16);
        JTextField fUser = new JTextField(14);
        JPasswordField fPass = new JPasswordField(14);
        SwingThemeHelper.configurarPlaceholder(fIp, "10.1.40.203 ou 10.1.31.19:65019");
        SwingThemeHelper.configurarPlaceholder(fUser, "(usar padrão)");
        SwingThemeHelper.configurarCampoSenhaComOlho(fPass, "(usar padrão)");

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 5, 5, 5);
        g.anchor = GridBagConstraints.WEST;
        g.gridx = 0; g.gridy = 0; form.add(new JLabel("IP[:porta] *:"), g);
        g.gridx = 1; form.add(fIp, g);
        g.gridx = 0; g.gridy = 1; form.add(new JLabel("Usuário:"), g);
        g.gridx = 1; form.add(fUser, g);
        g.gridx = 0; g.gridy = 2; form.add(new JLabel("Senha:"), g);
        g.gridx = 1; form.add(fPass, g);

        int res = JOptionPane.showConfirmDialog(this, form, "Adicionar Equipamento", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            try {
                SwitchTarget alvo = TargetTableModel.parsearEnderecoTarget(fIp.getText(), fUser.getText(), new String(fPass.getPassword()));
                tableModel.mesclarAlvosImportados(java.util.List.of(alvo), "Manual");
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Atenção", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void removerLinhasSelecionadas() {
        int[] sel = tblAlvos.getSelectedRows();
        if (sel.length == 0) {
            JOptionPane.showMessageDialog(this, "Selecione uma ou mais linhas da tabela para remover.", "Atenção", JOptionPane.WARNING_MESSAGE);
            return;
        }
        tableModel.removerLinhas(sel, tblAlvos);
    }

    private void abrirDialogDefinirSenhaEmLote() {
        finalizarEdicaoTabela();
        List<Integer> alvosModel = resolverLinhasAlvoLote();
        if (alvosModel.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Selecione na tabela (ou marque na caixinha) os equipamentos para atualizar.",
                    "Nenhum equipamento selecionado",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        JRadioButton rbCredencial = new JRadioButton("Definir usuário e senha para os " + alvosModel.size() + " selecionado(s)", true);
        JRadioButton rbSemSenha = new JRadioButton("Marcar como Sem Senha (passivo na topologia)");
        ButtonGroup grp = new ButtonGroup();
        grp.add(rbCredencial);
        grp.add(rbSemSenha);

        JTextField fUser = new JTextField(16);
        JPasswordField fPass = new JPasswordField(16);
        SwingThemeHelper.configurarPlaceholder(fUser, "(manter atual / padrão)");
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
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridwidth = 2;
        g.gridx = 0; g.gridy = 0; form.add(rbCredencial, g);
        g.gridwidth = 1;
        g.gridy = 1; g.gridx = 0; form.add(new JLabel("Usuário:"), g);
        g.gridx = 1; form.add(fUser, g);
        g.gridy = 2; g.gridx = 0; form.add(new JLabel("Senha:"), g);
        g.gridx = 1; form.add(fPass, g);
        g.gridwidth = 2;
        g.gridy = 3; g.gridx = 0; form.add(rbSemSenha, g);

        int res = JOptionPane.showConfirmDialog(
                this, form, "Credenciais em Lote (" + alvosModel.size() + " item(ns))",
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
        if (novoUser.isBlank() && novaPass.isEmpty()) {
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
            if (linhas.get(i).isSelecionado()) {
                indices.add(i);
            }
        }
        return indices;
    }
}

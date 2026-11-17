package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.descoberta.DiscoveredProduct;
import tpDiscover.intelbras.descoberta.NetworkScanTarget;
import tpDiscover.intelbras.gui.DiscoveredTargetRow.EstadoColeta;

import javax.swing.DefaultCellEditor;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modelo Swing da tabela de equipamentos descobertos ou adicionados manualmente.
 * Permite editar IP/Porta, usuário e senha individuais e filtrar alvos selecionados para coleta.
 */
public class TargetTableModel extends AbstractTableModel {

    public static final int COL_SELECIONADO = 0;
    public static final int COL_IP_PORTA = 1;
    public static final int COL_MODELO = 2;
    public static final int COL_USUARIO = 3;
    public static final int COL_SENHA = 4;
    public static final int COL_STATUS = 5;

    private static final String[] COLUNAS = {
            "Coletar", "IP / Porta", "Produto / Modelo", "Usuário", "Senha indiv.", "Status"
    };

    private final List<DiscoveredTargetRow> linhas = new ArrayList<>();

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
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == COL_SELECIONADO ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= linhas.size()) return false;
        DiscoveredTargetRow r = linhas.get(rowIndex);
        return switch (columnIndex) {
            case COL_SELECIONADO -> r.isSuportaCli();
            case COL_IP_PORTA, COL_USUARIO, COL_SENHA -> true;
            default -> false;
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        DiscoveredTargetRow r = linhas.get(rowIndex);
        return switch (columnIndex) {
            case COL_SELECIONADO -> r.isSelecionado();
            case COL_IP_PORTA -> r.getIpPorta();
            case COL_MODELO -> r.getProdutoModelo();
            case COL_USUARIO -> r.getUsuario();
            case COL_SENHA -> r.getSenha();
            case COL_STATUS -> r.getStatus();
            default -> "";
        };
    }

    @Override
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= linhas.size()) return;
        DiscoveredTargetRow r = linhas.get(rowIndex);
        switch (columnIndex) {
            case COL_SELECIONADO -> r.setSelecionado(Boolean.TRUE.equals(aValue));
            case COL_IP_PORTA -> {
                String novo = aValue != null ? aValue.toString().trim() : "";
                if (!novo.isEmpty()) {
                    r.setIpPorta(novo);
                }
            }
            case COL_USUARIO -> {
                r.setUsuario(aValue != null ? aValue.toString() : "");
                if (r.isSuportaCli() && (!r.getUsuario().isBlank() || !r.getSenha().isEmpty())
                        && (r.getEstado() == EstadoColeta.SEM_SENHA || r.getEstado() == EstadoColeta.FALHA)) {
                    r.setEstado(EstadoColeta.AGUARDANDO);
                    r.setStatus("Pronto para recoleta");
                    r.setSelecionado(true);
                }
            }
            case COL_SENHA -> {
                r.setSenha(aValue != null ? aValue.toString() : "");
                if (r.isSuportaCli() && !r.getSenha().isEmpty()
                        && (r.getEstado() == EstadoColeta.SEM_SENHA || r.getEstado() == EstadoColeta.FALHA)) {
                    r.setEstado(EstadoColeta.AGUARDANDO);
                    r.setStatus("Pronto para recoleta");
                    r.setSelecionado(true);
                }
            }
            default -> {
            }
        }
        fireTableRowsUpdated(rowIndex, rowIndex);
    }

    public void limpar() {
        int tam = linhas.size();
        if (tam > 0) {
            linhas.clear();
            fireTableRowsDeleted(0, tam - 1);
        }
    }

    public List<DiscoveredTargetRow> getLinhas() {
        return List.copyOf(linhas);
    }

    public int contarSelecionados() {
        int c = 0;
        for (DiscoveredTargetRow r : linhas) {
            if (r.isSelecionado() && r.isSuportaCli()) c++;
        }
        return c;
    }

    /**
     * Atualiza ou adiciona produtos descobertos preservando credenciais customizadas já editadas na tabela.
     */
    public void mesclarProdutosDescobertos(List<DiscoveredProduct> produtos) {
        if (produtos == null) return;
        Map<String, DiscoveredTargetRow> existentes = new LinkedHashMap<>();
        for (DiscoveredTargetRow r : linhas) {
            existentes.put(extrairIpBase(r.getIpPorta()), r);
        }

        for (DiscoveredProduct p : produtos) {
            String ipChave = p.ip();
            String rotuloModelo = formatarModeloDescoberto(p);
            boolean cliOk = p.suportaColetaCli();
            String ipComPorta = (p.portaCliPreferida() != null && p.portaCliPreferida() != 22 && p.portaCliPreferida() != 23)
                    ? (p.ip() + ":" + p.portaCliPreferida())
                    : p.ip();

            DiscoveredTargetRow atual = existentes.get(ipChave);
            String categoria = categoriaProduto(p);
            boolean ehGw = p.familia() == DiscoveredProduct.FamiliaProduto.ROTEADOR_GATEWAY;
            if (atual != null) {
                atual.setProdutoModelo(rotuloModelo);
                atual.setSuportaCli(cliOk);
                atual.setCategoriaInferida(categoria);
                atual.setMacConhecido(p.macConhecido());
                atual.setEhGateway(ehGw);
                if (!cliOk) {
                    atual.setSelecionado(false);
                    atual.setEstado(EstadoColeta.NAO_SUPORTADO_CLI);
                    atual.setStatus("Referência (sem CLI)");
                }
            } else {
                EstadoColeta estado = cliOk ? EstadoColeta.AGUARDANDO : EstadoColeta.NAO_SUPORTADO_CLI;
                String status = cliOk ? "Aguardando coleta" : "Referência (sem CLI)";
                DiscoveredTargetRow novo = new DiscoveredTargetRow(
                        cliOk,
                        ipComPorta,
                        rotuloModelo,
                        "",
                        "",
                        status,
                        estado,
                        cliOk
                );
                novo.setCategoriaInferida(categoria);
                novo.setMacConhecido(p.macConhecido());
                novo.setEhGateway(ehGw);
                linhas.add(novo);
            }
        }
        fireTableDataChanged();
    }

    public void mesclarAlvosImportados(List<SwitchTarget> alvos, String origemRotulo) {
        if (alvos == null) return;
        Map<String, DiscoveredTargetRow> existentes = new LinkedHashMap<>();
        for (DiscoveredTargetRow r : linhas) {
            existentes.put(r.getIpPorta(), r);
        }

        for (SwitchTarget t : alvos) {
            String endereco = t.enderecoExibicao();
            DiscoveredTargetRow atual = existentes.get(endereco);
            if (atual != null) {
                atual.setSelecionado(true);
                atual.setSuportaCli(true);
                if (t.usuario() != null && !t.usuario().isBlank()) {
                    atual.setUsuario(t.usuario());
                }
                if (t.possuiSenha()) {
                    atual.setSenha(t.senha());
                }
                atual.setEstado(EstadoColeta.AGUARDANDO);
                atual.setStatus("Aguardando coleta");
            } else {
                linhas.add(new DiscoveredTargetRow(
                        true,
                        endereco,
                        origemRotulo != null ? origemRotulo : "Importado / Manual",
                        t.usuario() != null ? t.usuario() : "",
                        t.possuiSenha() ? t.senha() : "",
                        "Aguardando coleta",
                        EstadoColeta.AGUARDANDO,
                        true
                ));
            }
        }
        fireTableDataChanged();
    }

    /**
     * Garante que alvos adicionais (ex.: descobertos na Onda 2 LLDP) apareçam na tabela,
     * sem sobrescrever credenciais ou seleção de linhas já exibidas.
     */
    public void garantirAlvosNaTabela(List<SwitchTarget> alvos, String origemRotulo) {
        if (alvos == null || alvos.isEmpty()) return;
        Map<String, DiscoveredTargetRow> existentes = new LinkedHashMap<>();
        for (DiscoveredTargetRow r : linhas) {
            existentes.put(extrairIpBase(r.getIpPorta()), r);
        }
        boolean alterou = false;
        for (SwitchTarget t : alvos) {
            if (!existentes.containsKey(t.ip())) {
                linhas.add(new DiscoveredTargetRow(
                        true,
                        t.enderecoExibicao(),
                        origemRotulo != null ? origemRotulo : "Descoberto na coleta",
                        "",
                        "",
                        "Aguardando coleta",
                        EstadoColeta.AGUARDANDO,
                        true
                ));
                alterou = true;
            }
        }
        if (alterou) {
            fireTableDataChanged();
        }
    }

    public List<SwitchTarget> extrairOverridesManuais(String usuarioPadrao, String senhaPadrao) {
        List<SwitchTarget> overrides = new ArrayList<>();
        String userPadraoResolvido = (usuarioPadrao != null && !usuarioPadrao.isBlank()) ? usuarioPadrao.trim() : "admin";
        for (DiscoveredTargetRow r : linhas) {
            if (!r.isSuportaCli() || !r.isSelecionado() || r.getEstado() == EstadoColeta.SEM_SENHA) continue;
            String user = !r.getUsuario().isBlank() ? r.getUsuario() : "";
            String pass = !r.getSenha().isEmpty() ? r.getSenha() : "";
            if (!user.isBlank() || !pass.isEmpty() || r.getIpPorta().contains(":")) {
                try {
                    String resolvedUser = !user.isBlank() ? user : userPadraoResolvido;
                    String resolvedPass = !pass.isEmpty() ? pass : senhaPadrao;
                    overrides.add(parsearEnderecoTarget(r.getIpPorta(), resolvedUser, resolvedPass));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return overrides;
    }

    public void adicionarManual(String ipPorta, String usuario, String senha) {
        SwitchTarget parsed = parsearEnderecoTarget(ipPorta, usuario, senha);
        mesclarAlvosImportados(List.of(parsed), "Manual");
    }

    public void removerLinhas(int[] indicesView, JTable tabela) {
        if (indicesView == null || indicesView.length == 0) return;
        List<Integer> modelIndices = new ArrayList<>();
        for (int idx : indicesView) {
            int m = tabela != null ? tabela.convertRowIndexToModel(idx) : idx;
            if (m >= 0 && m < linhas.size()) {
                modelIndices.add(m);
            }
        }
        modelIndices.sort((a, b) -> Integer.compare(b, a));
        for (int idx : modelIndices) {
            linhas.remove(idx);
        }
        fireTableDataChanged();
    }

    public void selecionarTodos(boolean marcar) {
        for (DiscoveredTargetRow r : linhas) {
            if (r.isSuportaCli()) {
                r.setSelecionado(marcar && r.getEstado() != EstadoColeta.SEM_SENHA);
            }
        }
        fireTableDataChanged();
    }

    public void selecionarApenasFalhos() {
        for (DiscoveredTargetRow r : linhas) {
            if (r.isSuportaCli()) {
                r.setSelecionado(r.getEstado() == EstadoColeta.FALHA);
            }
        }
        fireTableDataChanged();
    }

    public void aplicarCredenciaisEmLinhas(List<Integer> modelIndices, String usuario, String senha) {
        if (modelIndices == null || modelIndices.isEmpty()) return;
        for (int idx : modelIndices) {
            if (idx < 0 || idx >= linhas.size()) continue;
            DiscoveredTargetRow r = linhas.get(idx);
            if (usuario != null && !usuario.isBlank()) {
                r.setUsuario(usuario.trim());
            }
            if (senha != null && !senha.isEmpty()) {
                r.setSenha(senha);
            }
            if (r.isSuportaCli()) {
                r.setEstado(EstadoColeta.AGUARDANDO);
                r.setStatus("Pronto para recoleta");
                r.setSelecionado(true);
            }
        }
        fireTableDataChanged();
    }

    public void marcarComoSemSenha(List<Integer> modelIndices) {
        if (modelIndices == null || modelIndices.isEmpty()) return;
        for (int idx : modelIndices) {
            if (idx < 0 || idx >= linhas.size()) continue;
            DiscoveredTargetRow r = linhas.get(idx);
            if (r.isEhGateway()) continue;
            r.setSelecionado(false);
            r.setSenha("");
            r.setEstado(EstadoColeta.SEM_SENHA);
            r.setStatus("Sem senha (passivo)");
        }
        fireTableDataChanged();
    }

    public int contarPorEstado(EstadoColeta estado) {
        int c = 0;
        for (DiscoveredTargetRow r : linhas) {
            if (r.getEstado() == estado) c++;
        }
        return c;
    }

    public List<TopologyEngine.EquipamentoSemAcesso> extrairEquipamentosSemAcesso() {
        List<TopologyEngine.EquipamentoSemAcesso> lista = new ArrayList<>();
        for (DiscoveredTargetRow r : linhas) {
            if (r.isEhGateway()) continue;
            String motivo = switch (r.getEstado()) {
                case SEM_SENHA -> "SEM_SENHA";
                case NAO_SUPORTADO_CLI -> "SEM_CLI";
                case FALHA -> !r.isSelecionado() ? "FALHA_AUTENTICACAO" : null;
                default -> null;
            };
            if (motivo == null) continue;
            String ipBase = extrairIpBase(r.getIpPorta());
            if (ipBase.isBlank()) continue;
            String modelo = !r.getProdutoModelo().isBlank() ? r.getProdutoModelo() : "Equipamento";
            lista.add(new TopologyEngine.EquipamentoSemAcesso(
                    ipBase, modelo, r.getCategoriaInferida(), r.getMacConhecido(), motivo));
        }
        return lista;
    }

    private static String categoriaProduto(DiscoveredProduct p) {
        if (p == null) return "SWITCH";
        return switch (p.familia()) {
            case OLT -> "OLT";
            case ACCESS_POINT -> (p.modeloInferido().toLowerCase(java.util.Locale.ROOT).contains("wom")
                    || p.modeloInferido().toLowerCase(java.util.Locale.ROOT).contains("apc"))
                    ? "RADIO" : "AP";
            case ROTEADOR_GATEWAY -> "ROUTER";
            default -> "SWITCH";
        };
    }

    public void marcarAguardandoParaSelecionados() {
        for (DiscoveredTargetRow r : linhas) {
            if (r.isSelecionado() && r.isSuportaCli()) {
                r.setEstado(EstadoColeta.AGUARDANDO);
                r.setStatus("Na fila...");
            }
        }
        fireTableDataChanged();
    }

    public void atualizarStatusPorEndereco(String enderecoExibicao, EstadoColeta estado, String mensagem) {
        String ipAlvo = extrairIpBase(enderecoExibicao);
        for (int i = 0; i < linhas.size(); i++) {
            DiscoveredTargetRow r = linhas.get(i);
            if (r.getIpPorta().equalsIgnoreCase(enderecoExibicao)
                    || extrairIpBase(r.getIpPorta()).equals(ipAlvo)) {
                r.setEstado(estado);
                r.setStatus(mensagem);
                fireTableRowsUpdated(i, i);
                return;
            }
        }
    }

    public List<SwitchTarget> extrairAlvosParaColeta(String usuarioPadrao, String senhaPadrao, boolean exigirSenha) {
        List<SwitchTarget> alvos = new ArrayList<>();
        for (DiscoveredTargetRow r : linhas) {
            if (!r.isSelecionado() || !r.isSuportaCli() || r.getEstado() == EstadoColeta.SEM_SENHA) continue;
            String user = !r.getUsuario().isBlank()
                    ? r.getUsuario()
                    : (usuarioPadrao != null && !usuarioPadrao.isBlank() ? usuarioPadrao.trim() : "admin");
            String pass = !r.getSenha().isEmpty() ? r.getSenha() : senhaPadrao;
            if (exigirSenha && (pass == null || pass.isEmpty())) {
                throw new IllegalArgumentException(
                        "Informe a senha padrão (ou uma senha específica na linha " + r.getIpPorta() + ")."
                );
            }
            alvos.add(parsearEnderecoTarget(r.getIpPorta(), user, pass));
        }
        return alvos;
    }

    public List<SwitchTarget> extrairTodosAlvosParaExportacao(String usuarioPadrao, String senhaPadrao) {
        List<SwitchTarget> alvos = new ArrayList<>();
        for (DiscoveredTargetRow r : linhas) {
            if (!r.isSuportaCli()) continue;
            String user = !r.getUsuario().isBlank()
                    ? r.getUsuario()
                    : (usuarioPadrao != null && !usuarioPadrao.isBlank() ? usuarioPadrao.trim() : "admin");
            String pass = !r.getSenha().isEmpty() ? r.getSenha() : senhaPadrao;
            alvos.add(parsearEnderecoTarget(r.getIpPorta(), user, pass));
        }
        return alvos;
    }

    public static SwitchTarget parsearEnderecoTarget(String ipPorta, String usuario, String senha) {
        if (ipPorta == null || ipPorta.isBlank()) {
            throw new IllegalArgumentException("IP do equipamento em branco.");
        }
        String limpo = ipPorta.trim();
        String ip = limpo;
        Integer porta = null;
        int idx = limpo.lastIndexOf(':');
        if (idx > 0 && limpo.indexOf(':') == idx) {
            ip = limpo.substring(0, idx).trim();
            String portaStr = limpo.substring(idx + 1).trim();
            try {
                int p = Integer.parseInt(portaStr);
                if (p < 1 || p > 65535) {
                    throw new IllegalArgumentException("Porta inválida em '" + limpo + "' (use 1..65535).");
                }
                porta = p;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Porta inválida em '" + limpo + "'.");
            }
        }
        if (!NetworkScanTarget.ehIpv4Valido(ip)) {
            throw new IllegalArgumentException("Endereço IPv4 inválido: '" + ip + "'.");
        }
        return new SwitchTarget(ip, usuario, senha, porta);
    }

    public static void configurarRenderersEColunas(JTable table) {
        table.setRowHeight(30);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new java.awt.Dimension(0, 1));

        TableColumnModel cm = table.getColumnModel();
        cm.getColumn(COL_SELECIONADO).setMaxWidth(64);
        cm.getColumn(COL_SELECIONADO).setMinWidth(56);
        cm.getColumn(COL_IP_PORTA).setPreferredWidth(130);
        cm.getColumn(COL_MODELO).setPreferredWidth(290);
        cm.getColumn(COL_USUARIO).setPreferredWidth(100);
        cm.getColumn(COL_SENHA).setPreferredWidth(115);
        cm.getColumn(COL_STATUS).setPreferredWidth(240);

        cm.getColumn(COL_USUARIO).setCellRenderer(new DefaultPlaceholderRenderer("(padrão)"));
        cm.getColumn(COL_SENHA).setCellRenderer(new PasswordCellRenderer());
        cm.getColumn(COL_SENHA).setCellEditor(new PasswordCellEditor());
        cm.getColumn(COL_STATUS).setCellRenderer(new StatusCellRenderer());
    }

    private static String extrairIpBase(String ipPorta) {
        if (ipPorta == null) return "";
        int idx = ipPorta.indexOf(':');
        return (idx > 0 ? ipPorta.substring(0, idx) : ipPorta).trim();
    }

    private static String formatarModeloDescoberto(DiscoveredProduct p) {
        String base = p.familia().rotulo();
        if (!p.modeloInferido().isBlank() && !base.contains(p.modeloInferido())) {
            base += " (" + p.modeloInferido() + ")";
        }
        if (!p.protocoloPreferido().isBlank()) {
            base += " [" + p.protocoloPreferido() + "]";
        }
        return base;
    }

    private static final class DefaultPlaceholderRenderer extends DefaultTableCellRenderer {
        private final String placeholder;

        DefaultPlaceholderRenderer(String placeholder) {
            this.placeholder = placeholder;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            String txt = value != null ? value.toString().trim() : "";
            super.getTableCellRendererComponent(table, txt.isEmpty() ? placeholder : txt, isSelected, hasFocus, row, column);
            if (!isSelected) {
                setBackground(row % 2 == 0 ? SwingThemeHelper.BG_CARD : SwingThemeHelper.BG_TABLE_ALT);
                setForeground(txt.isEmpty() ? SwingThemeHelper.TEXT_MUTED : SwingThemeHelper.TEXT_PRIMARY);
            }
            return this;
        }
    }

    private static final class PasswordCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            String senha = value != null ? value.toString() : "";
            String exibicao = senha.isEmpty() ? "(padrão)" : "••••••••";
            super.getTableCellRendererComponent(table, exibicao, isSelected, hasFocus, row, column);
            if (!isSelected) {
                setBackground(row % 2 == 0 ? SwingThemeHelper.BG_CARD : SwingThemeHelper.BG_TABLE_ALT);
                setForeground(senha.isEmpty() ? SwingThemeHelper.TEXT_MUTED : SwingThemeHelper.TEXT_PRIMARY);
            }
            return this;
        }
    }

    private static final class PasswordCellEditor extends DefaultCellEditor {
        private final JPasswordField field;

        PasswordCellEditor() {
            this(new JPasswordField());
        }

        private PasswordCellEditor(JPasswordField field) {
            super(field);
            this.field = field;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            field.setText(value != null ? value.toString() : "");
            return field;
        }

        @Override
        public Object getCellEditorValue() {
            return new String(field.getPassword());
        }
    }

    private static final class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String texto = value != null ? value.toString() : "";
            lbl.setToolTipText(texto.isBlank() ? null : texto);
            lbl.setBorder(new EmptyBorder(2, 6, 2, 6));
            lbl.setHorizontalAlignment(SwingConstants.LEFT);
            if (!isSelected && table.getModel() instanceof TargetTableModel model) {
                int modelRow = table.convertRowIndexToModel(row);
                EstadoColeta estado = model.getLinhas().get(modelRow).getEstado();
                switch (estado) {
                    case OK -> {
                        lbl.setBackground(SwingThemeHelper.STATUS_OK_BG);
                        lbl.setForeground(SwingThemeHelper.STATUS_OK_FG);
                    }
                    case FALHA -> {
                        lbl.setBackground(SwingThemeHelper.STATUS_ERR_BG);
                        lbl.setForeground(SwingThemeHelper.STATUS_ERR_FG);
                    }
                    case COLETANDO -> {
                        lbl.setBackground(SwingThemeHelper.STATUS_RUN_BG);
                        lbl.setForeground(SwingThemeHelper.STATUS_RUN_FG);
                    }
                    case NAO_SUPORTADO_CLI, SEM_SENHA -> {
                        lbl.setBackground(SwingThemeHelper.STATUS_WARN_BG);
                        lbl.setForeground(SwingThemeHelper.STATUS_WARN_FG);
                    }
                    case AGUARDANDO -> {
                        lbl.setBackground(SwingThemeHelper.STATUS_IDLE_BG);
                        lbl.setForeground(SwingThemeHelper.STATUS_IDLE_FG);
                    }
                }
                lbl.setOpaque(true);
            }
            return lbl;
        }
    }
}

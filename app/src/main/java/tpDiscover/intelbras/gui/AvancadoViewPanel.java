package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.coleta.ColetorEmLote;
import tpDiscover.intelbras.coleta.CredencialAcesso;
import tpDiscover.intelbras.gui.SwingThemeHelper.IconKind;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

import tpDiscover.intelbras.util.Compat;
/**
 * Aba "Avançado": ajustes opcionais, agrupados por assunto e com uma explicação curta em cada item.
 * Nada aqui é necessário para uma coleta normal.
 */
public class AvancadoViewPanel extends JPanel {

    private final JSpinner spnConcorrencia = new JSpinner(new SpinnerNumberModel(ColetorEmLote.CONCORRENCIA_PADRAO, 1, 64, 1));
    private final JCheckBox chkLldp = new JCheckBox("Encontrar switches vizinhos automaticamente (LLDP)", true);
    private final JCheckBox chkSnmp = new JCheckBox("Identificar modelos via SNMP (comunidade public)", true);
    private final JTextField txtGatewayIp = new JTextField(16);
    private final JTextField txtGatewayMac = new JTextField(18);
    private final JButton btnCredenciaisExtras = new JButton();
    private final List<CredencialAcesso> credenciaisAlternativas = new ArrayList<>(CredencialAcesso.presetsPadrao());

    public AvancadoViewPanel() {
        setLayout(new BorderLayout());
        setOpaque(false);

        SwingThemeHelper.configurarPlaceholder(txtGatewayIp, "Ex.: 10.1.40.1");
        SwingThemeHelper.configurarPlaceholder(txtGatewayMac, "Ex.: 0009-0F09-1E0A");
        chkLldp.setOpaque(false);
        chkSnmp.setOpaque(false);

        JPanel coluna = new JPanel();
        coluna.setOpaque(false);
        coluna.setLayout(new BoxLayout(coluna, BoxLayout.Y_AXIS));

        // Credenciais
        SwingThemeHelper.estilizarBotaoCompacto(btnCredenciaisExtras);
        btnCredenciaisExtras.setIcon(SwingThemeHelper.criarIcone(IconKind.KEY, SwingThemeHelper.TEXT_SECONDARY));
        btnCredenciaisExtras.addActionListener(e -> abrirDialogCredenciaisExtras());
        atualizarRotuloCredenciaisExtras();
        JPanel credWrap = new JPanel(new BorderLayout());
        credWrap.setOpaque(false);
        credWrap.add(btnCredenciaisExtras, BorderLayout.WEST);
        coluna.add(criarSecao("Senhas alternativas",
                "Se a senha principal for recusada, o tpDiscover tenta estas combinações automaticamente (ex.: OLTs com senha de fábrica).",
                credWrap, null));
        coluna.add(espaco());

        // Descoberta
        JPanel desc = new JPanel(new GridBagLayout());
        desc.setOpaque(false);
        adicionarOpcao(desc, 0, chkLldp, "Pergunta a cada switch quem está ligado nele e inclui os vizinhos que o scan não achou.");
        adicionarOpcao(desc, 1, chkSnmp, "Ajuda a reconhecer o modelo exato durante a varredura. Desative se a rede bloquear SNMP.");
        coluna.add(criarSecao("Descoberta", null, desc, null));
        coluna.add(espaco());

        // Desempenho
        JPanel spnWrap = new JPanel(new BorderLayout());
        spnWrap.setOpaque(false);
        spnConcorrencia.setPreferredSize(new Dimension(90, spnConcorrencia.getPreferredSize().height));
        spnWrap.add(spnConcorrencia, BorderLayout.WEST);
        coluna.add(criarSecao("Equipamentos coletados ao mesmo tempo",
                "Valores maiores terminam mais rápido; reduza se houver muitas falhas por tempo esgotado.",
                spnWrap, null));
        coluna.add(espaco());

        // Gateway
        JPanel gw = new JPanel(new GridBagLayout());
        gw.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 8, 12);
        // IP e MAC lado a lado, com largura fixa (não ocupam a tela toda)
        g.fill = GridBagConstraints.NONE;
        g.gridx = 0; g.gridy = 0;
        gw.add(rotulo("IP"), g);
        g.gridx = 1;
        txtGatewayIp.setPreferredSize(new Dimension(170, txtGatewayIp.getPreferredSize().height));
        gw.add(txtGatewayIp, g);
        g.gridx = 2; g.insets = new Insets(0, 12, 8, 12);
        gw.add(rotulo("MAC"), g);
        g.gridx = 3; g.insets = new Insets(0, 0, 8, 12);
        txtGatewayMac.setPreferredSize(new Dimension(190, txtGatewayMac.getPreferredSize().height));
        gw.add(txtGatewayMac, g);
        g.gridx = 4; g.weightx = 1;
        gw.add(javax.swing.Box.createHorizontalGlue(), g);
        coluna.add(criarSecao("Gateway (opcional)",
                "Normalmente detectado sozinho. Preencha só se a topologia não mostrar o roteador correto.",
                gw, null));
        coluna.add(espaco());

        // Sobre
        JLabel sobreTxt = new JLabel(
                "<html><div style='width:620px'>Ferramenta de mapeamento de topologia e diagnóstico de loops para redes Intelbras. "
                        + "Suporta Comware, Série 3000/Max, S2050G-A / S30-Lite / PoE Max, SG MR / MR L2+ / S-Series, "
                        + "OLTs (G08/G16/4840E, AN6000/6001/5516, 8820I/G) e APs/Rádios.<br><br>"
                        + "<b>Somente leitura:</b> nenhuma configuração de switch ou porta é alterada.<br><br>"
                        + "Versão " + tpDiscover.intelbras.App.VERSAO + " · Java " + System.getProperty("java.version")
                        + "<br>Atalhos: Ctrl+1 a Ctrl+4 trocam de aba · F5 inicia a coleta · Ctrl+F busca no mapa · "
                        + "Ctrl+P gera o PDF</div></html>");
        sobreTxt.setForeground(SwingThemeHelper.TEXT_MUTED);
        coluna.add(criarSecao("Sobre o tpDiscover", null, sobreTxt, null));

        JPanel topo = new JPanel(new BorderLayout());
        topo.setOpaque(false);
        topo.add(coluna, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(topo);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    private JPanel criarSecao(String titulo, String descricao, JComponent conteudo, JComponent extra) {
        JPanel card = SwingThemeHelper.criarCardArredondado();
        card.setLayout(new BorderLayout(0, 12));
        card.add(SwingThemeHelper.criarCabecalho(titulo, descricao), BorderLayout.NORTH);
        card.add(conteudo, BorderLayout.CENTER);
        if (extra != null) card.add(extra, BorderLayout.SOUTH);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height + 400));
        return card;
    }

    private void adicionarOpcao(JPanel p, int linha, JCheckBox chk, String ajuda) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = linha * 2;
        g.anchor = GridBagConstraints.WEST;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(linha == 0 ? 0 : 10, 0, 0, 0);
        p.add(chk, g);
        g.gridy = linha * 2 + 1;
        g.insets = new Insets(2, 28, 0, 0);
        p.add(SwingThemeHelper.criarTextoAjuda(ajuda), g);
    }

    private static JLabel rotulo(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(SwingThemeHelper.TEXT_SECONDARY);
        return l;
    }

    private static JComponent espaco() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(1, 12));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 12));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private void abrirDialogCredenciaisExtras() {
        List<CredencialAcesso> editadas = CredenciaisExtrasDialog.exibirDialogo(this, credenciaisAlternativas);
        if (editadas != null) {
            credenciaisAlternativas.clear();
            credenciaisAlternativas.addAll(editadas);
            atualizarRotuloCredenciaisExtras();
        }
    }

    private void atualizarRotuloCredenciaisExtras() {
        int n = credenciaisAlternativas.size();
        btnCredenciaisExtras.setText("Gerenciar senhas alternativas (" + n + (n == 1 ? " cadastrada)" : " cadastradas)"));
    }

    public void setEstadoExecucao(boolean executando) {
        btnCredenciaisExtras.setEnabled(!executando);
    }

    public List<CredencialAcesso> getCredenciaisAlternativas() {
        return Compat.listCopyOf(credenciaisAlternativas);
    }

    public int getConcorrencia() {
        return ((Number) spnConcorrencia.getValue()).intValue();
    }

    public boolean isLldpAtivo() {
        return chkLldp.isSelected();
    }

    public boolean isSnmpAtivo() {
        return chkSnmp.isSelected();
    }

    public void setConcorrencia(int valor) {
        spnConcorrencia.setValue(Math.max(1, Math.min(64, valor)));
    }

    public void setLldpAtivo(boolean ativo) {
        chkLldp.setSelected(ativo);
    }

    public void setSnmpAtivo(boolean ativo) {
        chkSnmp.setSelected(ativo);
    }

    public String getGatewayIp() {
        return txtGatewayIp.getText().trim();
    }

    public void setGatewayIp(String ip) {
        if (ip != null) txtGatewayIp.setText(ip);
    }

    public String getGatewayMac() {
        return txtGatewayMac.getText().trim();
    }

    public void setGatewayMac(String mac) {
        if (mac != null) txtGatewayMac.setText(mac);
    }
}

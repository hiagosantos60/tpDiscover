package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.coleta.ColetorEmLote;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Aba "Avançado": ajustes opcionais de paralelismo/protocolo, gateway manual e informações do projeto.
 */
public class AvancadoViewPanel extends JPanel {

    private final JSpinner spnConcorrencia = new JSpinner(new SpinnerNumberModel(ColetorEmLote.CONCORRENCIA_PADRAO, 1, 64, 1));
    private final JCheckBox chkLldp = new JCheckBox("Expandir switches esquecidos via LLDP (Onda 2)", true);
    private final JCheckBox chkSnmp = new JCheckBox("Usar sonda SNMPv2c (public) durante o scan", true);
    private final JTextField txtGatewayIp = new JTextField(16);
    private final JTextField txtGatewayMac = new JTextField(18);

    public AvancadoViewPanel() {
        setLayout(new BorderLayout(0, 12));
        setOpaque(false);

        SwingThemeHelper.configurarPlaceholder(txtGatewayIp, "Opcional (ex: 10.1.40.1)");
        SwingThemeHelper.configurarPlaceholder(txtGatewayMac, "Opcional (ex: 0009-0F09-1E0A)");

        JPanel cardConfig = SwingThemeHelper.criarCardArredondado();
        cardConfig.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(7, 6, 7, 10);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2; gc.weightx = 1.0;
        cardConfig.add(SwingThemeHelper.criarTituloSecao("Opções Avançadas de Descoberta e Coleta"), gc);

        gc.gridwidth = 1;
        gc.gridy = 1; gc.gridx = 0; gc.weightx = 0;
        cardConfig.add(new JLabel("Concorrência CLI (threads paralelas):"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        JPanel spnWrap = new JPanel(new BorderLayout());
        spnWrap.setOpaque(false);
        spnWrap.add(spnConcorrencia, BorderLayout.WEST);
        cardConfig.add(spnWrap, gc);

        gc.gridy = 2; gc.gridx = 0; gc.gridwidth = 2;
        chkLldp.setOpaque(false);
        cardConfig.add(chkLldp, gc);

        gc.gridy = 3;
        chkSnmp.setOpaque(false);
        cardConfig.add(chkSnmp, gc);

        gc.gridwidth = 1;
        gc.gridy = 4; gc.gridx = 0; gc.weightx = 0;
        cardConfig.add(new JLabel("Gateway manual — IP (opcional):"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        cardConfig.add(txtGatewayIp, gc);

        gc.gridy = 5; gc.gridx = 0; gc.weightx = 0;
        cardConfig.add(new JLabel("Gateway manual — MAC (opcional):"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        cardConfig.add(txtGatewayMac, gc);

        JPanel cardSobre = SwingThemeHelper.criarCardArredondado();
        cardSobre.setLayout(new BorderLayout(0, 6));
        cardSobre.add(SwingThemeHelper.criarTituloSecao("Sobre o tpDiscover Intelbras"), BorderLayout.NORTH);
        JLabel sobreTxt = new JLabel(
                "<html>Ferramenta de mapeamento topológico e diagnóstico L2 somente-leitura para redes Intelbras.<br>"
                        + "Suporta Comware, Série 3000/Max, S2050G-A / S30-Lite / PoE Max, SG MR / MR L2+ / S-Series, OLTs (G08/G16/4840E, AN6000/6001/5516, 8820I/G) e APs/Rádios.<br>"
                        + "Nenhuma configuração de switch ou porta é alterada durante o scan ou coleta.</html>"
        );
        sobreTxt.setForeground(SwingThemeHelper.TEXT_MUTED);
        cardSobre.add(sobreTxt, BorderLayout.CENTER);

        JPanel wrapper = new JPanel(new BorderLayout(0, 12));
        wrapper.setOpaque(false);
        wrapper.add(cardConfig, BorderLayout.NORTH);
        wrapper.add(cardSobre, BorderLayout.CENTER);
        add(wrapper, BorderLayout.NORTH);
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

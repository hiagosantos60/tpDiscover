package tpDiscover.intelbras.gui;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.descoberta.DiscoveredProduct;
import tpDiscover.intelbras.descoberta.DiscoveredProduct.FamiliaProduto;
import tpDiscover.intelbras.gui.DiscoveredTargetRow.EstadoColeta;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;
import java.util.List;

import tpDiscover.intelbras.util.Compat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetTableModelTest {

    @Test
    void deveMesclarProdutosDescobertosEPreservarCredenciaisCustomizadas() {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.40.120", "suporte", "SenhaEsp!");

        DiscoveredProduct gw = new DiscoveredProduct(
                "10.1.40.1", Compat.listOf(80), FamiliaProduto.ROTEADOR_GATEWAY,
                "FortiGate", "", "", "", false, "", null
        );
        DiscoveredProduct swEditado = new DiscoveredProduct(
                "10.1.40.120", Compat.listOf(22), FamiliaProduto.SWITCH_SG_MR,
                "SG 2404 MR", "", "", "", true, "SSH", 22
        );
        DiscoveredProduct swPadrao = new DiscoveredProduct(
                "10.1.40.203", Compat.listOf(22), FamiliaProduto.SWITCH_COMWARE,
                "S2328G-B", "", "", "", true, "SSH", 22
        );

        model.mesclarProdutosDescobertos(Compat.listOf(gw, swEditado, swPadrao));

        assertEquals(3, model.getRowCount());
        // Linha 0: 10.1.40.120 (adicionada antes) preservou usuário e senha customizados
        assertEquals("suporte", model.getLinhas().get(0).getUsuario());
        assertEquals("SenhaEsp!", model.getLinhas().get(0).getSenha());
        assertTrue(model.getLinhas().get(0).isSelecionado());

        // Linha 1: Gateway não coletável por CLI fica desmarcado e não editável no checkbox
        assertFalse(model.getLinhas().get(1).isSelecionado());
        assertFalse(model.isCellEditable(1, TargetTableModel.COL_SELECIONADO));

        // Linha 2: 10.1.40.203 herda usuário/senha padrão
        List<SwitchTarget> alvos = model.extrairAlvosParaColeta("admin", "Admin@1234", true);
        assertEquals(2, alvos.size());
        assertEquals("suporte", alvos.get(0).usuario());
        assertEquals("SenhaEsp!", alvos.get(0).senha());
        assertEquals("admin", alvos.get(1).usuario());
        assertEquals("Admin@1234", alvos.get(1).senha());
    }

    @Test
    void deveValidarPortaCustomizadaPermitirSenhaEmBrancoESelecionarApenasFalhos() {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.31.19:65019", "", "");
        model.adicionarManual("10.1.40.111", "", "admin");

        // Senha em branco é aceita (equipamentos que pedem só o usuário): segue vazia para o login
        List<SwitchTarget> semSenha = model.extrairAlvosParaColeta("admin", "", true);
        assertEquals("", semSenha.get(0).senha());
        assertEquals("admin", semSenha.get(0).usuario());
        assertEquals("admin", semSenha.get(1).senha());
        assertThrows(IllegalArgumentException.class, () -> model.adicionarManual("999.1.1.1", "admin", "123"));
        assertThrows(IllegalArgumentException.class, () -> model.adicionarManual("10.1.40.1:70000", "admin", "123"));

        List<SwitchTarget> alvos = model.extrairAlvosParaColeta("admin", "Admin@1234", true);
        assertEquals(2, alvos.size());
        assertEquals("10.1.31.19", alvos.get(0).ip());
        assertEquals(65019, alvos.get(0).portaCustomizada());
        assertEquals("Admin@1234", alvos.get(0).senha());
        assertNull(alvos.get(1).portaCustomizada());
        assertEquals("admin", alvos.get(1).senha());

        // Marca somente falhos e verifica extrairOverridesManuais
        model.atualizarStatusPorEndereco("10.1.31.19:65019", EstadoColeta.OK, "OK");
        model.atualizarStatusPorEndereco("10.1.40.111", EstadoColeta.FALHA, "Falha");
        model.selecionarApenasFalhos();

        assertFalse(model.getLinhas().get(0).isSelecionado());
        assertTrue(model.getLinhas().get(1).isSelecionado());
        assertEquals(1, model.contarSelecionados());

        List<SwitchTarget> overrides = model.extrairOverridesManuais("admin", "Admin@1234");
        assertEquals(1, overrides.size());
        assertEquals("10.1.40.111", overrides.get(0).ip());
        assertEquals("admin", overrides.get(0).senha());
    }

    @Test
    void deveAdicionarAlvoLldpSemSobrescreverSenhaExistente() {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.40.203", "ops", "Segredo1");

        model.garantirAlvosNaTabela(Compat.listOf(
                new SwitchTarget("10.1.40.203", "admin", "OutraSenha"),
                new SwitchTarget("10.1.40.204", "admin", "OutraSenha")
        ), "Descoberto via LLDP");

        assertEquals(2, model.getRowCount());
        assertEquals("ops", model.getLinhas().get(0).getUsuario());
        assertEquals("Segredo1", model.getLinhas().get(0).getSenha());
        assertEquals("", model.getLinhas().get(1).getUsuario());
        assertEquals("", model.getLinhas().get(1).getSenha());
    }

    @Test
    void deveExibirTooltipCompletoNaColunaStatus() {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.40.179", "admin", "admin");
        String statusLongo = "Falha: Telnet: Timeout esperando resposta do equipamento 10.1.40.179 via Telnet (45000ms).";
        model.atualizarStatusPorEndereco("10.1.40.179", EstadoColeta.FALHA, statusLongo);

        JTable table = new JTable(model);
        TargetTableModel.configurarRenderersEColunas(table);

        TableCellRenderer renderer = table.getColumnModel().getColumn(TargetTableModel.COL_STATUS).getCellRenderer();
        JComponent comp = (JComponent) renderer.getTableCellRendererComponent(
                table, statusLongo, false, false, 0, TargetTableModel.COL_STATUS);
        assertEquals(statusLongo, comp.getToolTipText());

        JComponent vazio = (JComponent) renderer.getTableCellRendererComponent(
                table, "   ", false, false, 0, TargetTableModel.COL_STATUS);
        assertNull(vazio.getToolTipText());
    }
}

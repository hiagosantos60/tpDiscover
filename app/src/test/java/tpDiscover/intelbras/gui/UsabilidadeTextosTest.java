package tpDiscover.intelbras.gui;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.gui.DiscoveredTargetRow.EstadoColeta;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsabilidadeTextosTest {

    @Test
    void motivoCurtoTraduzErrosReaisDaRede() {
        assertEquals("Senha incorreta", TargetTableModel.motivoCurto(
                "Usuário ou senha incorretos para 10.1.40.78 (SSH) — 2 credencial(is) testada(s)"));
        assertEquals("Sessão ocupada/recusada", TargetTableModel.motivoCurto(
                "Telnet: [banner] Conexão Telnet encerrada por 10.1.40.84 sem nenhuma resposta — sessão de gerência "
                        + "provavelmente ocupada | SSH: Falha SSH ao conectar em 10.1.40.84: connection is closed by foreign host"));
        assertEquals("Sem resposta", TargetTableModel.motivoCurto(
                "Timeout esperando resposta do equipamento 10.1.31.19 via SSH (30000ms)."));
        assertEquals("SSH incompatível", TargetTableModel.motivoCurto(
                "Falha SSH ao conectar: Algorithm negotiation fail: algorithmName=\"server_host_key\""));
    }

    @Test
    void duracaoSemCasasDecimais() {
        assertEquals("34 s", DiscoverySwingWorker.duracaoLegivel(33_790));
        assertEquals("1 min 05 s", DiscoverySwingWorker.duracaoLegivel(65_200));
    }

    @Test
    void falhasVaoParaOTopoDaLista() {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.40.10", "", "");
        model.adicionarManual("10.1.40.20", "", "");
        model.adicionarManual("10.1.40.3", "", "");
        model.atualizarStatusPorEndereco("10.1.40.20", EstadoColeta.FALHA, "Falha: Senha incorreta", "detalhe técnico");
        model.atualizarStatusPorEndereco("10.1.40.10", EstadoColeta.OK, "OK");
        model.atualizarStatusPorEndereco("10.1.40.3", EstadoColeta.OK, "OK");

        model.ordenarFalhasPrimeiro();

        assertEquals("10.1.40.20", model.getLinhas().get(0).getIpPorta());
        assertEquals("detalhe técnico", model.getLinhas().get(0).getDetalhe());
        assertEquals("10.1.40.3", model.getLinhas().get(1).getIpPorta(), "o restante segue a ordem numérica do IP");
        assertEquals("10.1.40.10", model.getLinhas().get(2).getIpPorta());
    }

    @Test
    void aoTerminarAColetaComFalhasMostraSoOsQuePrecisamAjuste() throws Exception {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.40.10", "", "");
        model.adicionarManual("10.1.40.20", "", "");
        model.adicionarManual("10.1.40.30", "", "");
        EquipamentosViewPanel[] painel = new EquipamentosViewPanel[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            painel[0] = new EquipamentosViewPanel(model);
            painel[0].setEstadoExecucao(true);
            model.atualizarStatusPorEndereco("10.1.40.10", EstadoColeta.OK, "OK");
            model.atualizarStatusPorEndereco("10.1.40.20", EstadoColeta.FALHA, "Falha: Senha incorreta", "x");
            model.atualizarStatusPorEndereco("10.1.40.30", EstadoColeta.OK, "OK");
            assertEquals(3, painel[0].linhasVisiveis(), "durante a coleta a lista fica completa");
            painel[0].setEstadoExecucao(false);
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            org.junit.jupiter.api.Assertions.assertTrue(painel[0].isSoFalhas());
            assertEquals(1, painel[0].linhasVisiveis(), "só o equipamento com falha aparece");
            // senha corrigida e recoletada com sucesso: volta para a lista completa
            model.atualizarStatusPorEndereco("10.1.40.20", EstadoColeta.OK, "OK");
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            org.junit.jupiter.api.Assertions.assertFalse(painel[0].isSoFalhas());
            assertEquals(3, painel[0].linhasVisiveis());
        });
    }
}

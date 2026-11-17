package tpDiscover.intelbras.coleta;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.coleta.CredencialAcesso.Escopo;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.descoberta.DiscoveredProduct;
import tpDiscover.intelbras.descoberta.DiscoveredProduct.FamiliaProduto;
import tpDiscover.intelbras.gui.DiscoveredTargetRow.EstadoColeta;
import tpDiscover.intelbras.gui.TargetTableModel;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CredenciaisEIncrementalTest {

    @Test
    void deveFiltrarCredenciaisAlternativasPorEscopoESemDuplicarPrincipal() {
        SwitchTarget alvo = new SwitchTarget("10.1.40.140", "admin", "MinhaSenha@1", null);
        List<CredencialAcesso> extras = List.of(
                new CredencialAcesso(true, "Repetida", "admin", "MinhaSenha@1", Escopo.TODOS),
                new CredencialAcesso(true, "OLT AN", "GEPON", "GEPON", Escopo.OLT),
                new CredencialAcesso(true, "OLT Padrao", "admin", "admin", Escopo.OLT),
                new CredencialAcesso(false, "Desativada", "root", "root", Escopo.TODOS),
                new CredencialAcesso(true, "Legado Switch", "suporte", "123456", Escopo.SWITCH_E_AP),
                new CredencialAcesso(true, "Global", "operador", "Op@2026", Escopo.TODOS)
        );

        List<CredencialAcesso> paraOlt = ColetorEmLote.selecionarCredenciaisAplicaveis(
                alvo, FamiliaProduto.OLT, extras);
        assertEquals(3, paraOlt.size());
        assertEquals(List.of("GEPON", "admin", "operador"),
                paraOlt.stream().map(CredencialAcesso::usuario).toList());

        List<CredencialAcesso> paraSwitch = ColetorEmLote.selecionarCredenciaisAplicaveis(
                alvo, FamiliaProduto.SWITCH_COMWARE, extras);
        assertEquals(2, paraSwitch.size());
        assertEquals(List.of("suporte", "operador"),
                paraSwitch.stream().map(CredencialAcesso::usuario).toList());
    }

    @Test
    void deveConsolidarSnapshotsPreservandoEquipamentosOkESubstituindoRecoletados() {
        ColetorEmLote.ColetaDispositivoSnapshot snapOk1 = criarSnapshot("10.1.40.111", "S1", "001A-3FC1-0001");
        ColetorEmLote.ColetaDispositivoSnapshot snapOk2Antigo = criarSnapshot("10.1.40.203", "S2_OLD", "001A-3FC1-0002");
        ColetorEmLote.ColetaDispositivoSnapshot snapOk2Novo = criarSnapshot("10.1.40.203", "S2_NEW", "001A-3FC1-0002");
        ColetorEmLote.ColetaDispositivoSnapshot snapOk3Novo = criarSnapshot("10.1.40.204", "S3_NEW", "001A-3FC1-0003");

        List<ColetorEmLote.ColetaDispositivoSnapshot> consolidado = DiscoverySessionService.consolidarSnapshots(
                List.of(snapOk1, snapOk2Antigo),
                List.of(snapOk2Novo, snapOk3Novo),
                Set.of("10.1.40.203", "10.1.40.204")
        );

        assertEquals(3, consolidado.size());
        assertEquals("S1", consolidado.get(0).identidade().deviceName());
        assertEquals("S2_NEW", consolidado.get(1).identidade().deviceName());
        assertEquals("S3_NEW", consolidado.get(2).identidade().deviceName());
    }

    @Test
    void deveAplicarSenhaEmLoteEMarcarSemSenhaNaTabelaDeEquipamentos() {
        TargetTableModel model = new TargetTableModel();
        model.mesclarProdutosDescobertos(List.of(
                new DiscoveredProduct("10.1.40.1", List.of(80), FamiliaProduto.ROTEADOR_GATEWAY,
                        "FortiGate", "", "00090f091e0a", "Fortinet", false, "", null),
                new DiscoveredProduct("10.1.40.140", List.of(23), FamiliaProduto.OLT,
                        "AN6000-2", "", "30e1f1101005", "Intelbras", true, "TELNET", 23),
                new DiscoveredProduct("10.1.40.50", List.of(80), FamiliaProduto.ACCESS_POINT,
                        "AP 1250", "", "aabbccdd0011", "Intelbras", true, "SSH", 22)
        ));

        model.atualizarStatusPorEndereco("10.1.40.140", EstadoColeta.FALHA, "Falha: FALHA_AUTENTICACAO");
        model.aplicarCredenciaisEmLinhas(List.of(1), "GEPON", "NovaSenhaOlt");

        assertEquals("GEPON", model.getLinhas().get(1).getUsuario());
        assertEquals("NovaSenhaOlt", model.getLinhas().get(1).getSenha());
        assertEquals(EstadoColeta.AGUARDANDO, model.getLinhas().get(1).getEstado());
        assertTrue(model.getLinhas().get(1).isSelecionado());

        // Marca AP 1250 como Sem Senha (passivo) e garante que não entra na coleta CLI
        model.marcarComoSemSenha(List.of(2));
        assertEquals(EstadoColeta.SEM_SENHA, model.getLinhas().get(2).getEstado());
        assertFalse(model.getLinhas().get(2).isSelecionado());

        List<SwitchTarget> alvosColeta = model.extrairAlvosParaColeta("admin", "Admin@123", true);
        assertEquals(1, alvosColeta.size());
        assertEquals("10.1.40.140", alvosColeta.get(0).ip());

        List<TopologyEngine.EquipamentoSemAcesso> pendencias = model.extrairEquipamentosSemAcesso();
        assertEquals(1, pendencias.size());
        assertEquals("10.1.40.50", pendencias.get(0).ipBase());
        assertEquals("SEM_SENHA", pendencias.get(0).motivo());
        assertEquals("AP", pendencias.get(0).categoria());
        assertEquals("aabbccdd0011", pendencias.get(0).macNormalizado());
    }

    private static ColetorEmLote.ColetaDispositivoSnapshot criarSnapshot(String ip, String nome, String mac) {
        SwitchTarget target = new SwitchTarget(ip, "admin", "123", null);
        SwitchIdentity id = new SwitchIdentity(nome, "SN-" + nome, mac, nome, ip);
        return new ColetorEmLote.ColetaDispositivoSnapshot(
                target, id, "Comware", List.of(), List.of(), "10.1.40.1",
                List.of(), List.of(), List.of(), Map.of(), null);
    }
}

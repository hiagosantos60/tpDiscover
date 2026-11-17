package tpDiscover.intelbras.correlacao;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.LldpInterestFilter;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResilienciaTopologiaTest {

    @Test
    void naoDeveFabricarGatewayLanENemLigarOltsEntreSiQuandoCoresFalharemAutenticacao() {
        TopologyEngine engine = new TopologyEngine();

        // 6 folhas que responderam com sucesso (mesmo cenário real da foto à esquerda)
        SwitchIdentity swSg = new SwitchIdentity("SG 1002 MR", "SN1", "001A-3FC1-1F7C", "SG 1002 MR", "10.1.40.111");
        SwitchIdentity swS30 = new SwitchIdentity("S3028G-PB Max", "SN2", "D836-5F10-2001", "S3028G-PB Max", "10.1.40.129");
        SwitchIdentity olt8820i = new SwitchIdentity("OLT-8820I", "SN3", "180D-2C08-D510", "OLT 8820I", "10.1.40.133");
        SwitchIdentity oltG16 = new SwitchIdentity("G116", "SN4", "30E1-F110-1004", "OLT G16", "10.1.40.134");
        SwitchIdentity oltAn6000 = new SwitchIdentity("OLT-AN6000-2", "SN5", "30E1-F110-1005", "AN6000-2", "10.1.40.140");
        SwitchIdentity oltAn6001 = new SwitchIdentity("OLT-AN6001-G16", "SN6", "30E1-F110-1006", "AN6001-G16", "10.1.40.142");

        for (SwitchIdentity sw : List.of(swSg, swS30, olt8820i, oltG16, oltAn6000, oltAn6001)) {
            engine.registrarSwitch(sw);
            engine.registrarRotaPadrao(sw.macNormalizado(), "10.1.40.1");
        }
        engine.registrarEntradasArp(swSg.macNormalizado(), List.of(
                new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1")
        ));

        // Os 3 cores que falharam autenticação (não foram coletados)
        engine.registrarEquipamentoSemAcesso(new TopologyEngine.EquipamentoSemAcesso(
                "10.1.40.202", "S2310G-A", "SWITCH", "7844-7601-0202", "FALHA_AUTENTICACAO"));
        engine.registrarEquipamentoSemAcesso(new TopologyEngine.EquipamentoSemAcesso(
                "10.1.40.203", "S2328G-B", "SWITCH", "7844-7601-0203", "FALHA_AUTENTICACAO"));
        engine.registrarEquipamentoSemAcesso(new TopologyEngine.EquipamentoSemAcesso(
                "10.1.40.204", "S2328G-A", "SWITCH", "7844-7601-0204", "FALHA_AUTENTICACAO"));

        // Cada folha enxerga o MAC do Gateway + irmãos + cores na sua porta de uplink
        registrarUplinkComum(engine, swSg, "GE1", List.of("0009-0f09-1e0a", "d836-5f10-2001", "180d-2c08-d510", "7844-7601-0203"));
        registrarUplinkComum(engine, swS30, "GE0/2", List.of("0009-0f09-1e0a", "001a-3fc1-1f7c", "180d-2c08-d510", "7844-7601-0203"));
        // OLT-8820I aprende o MAC da OLT-AN6001-G16 no ETH1, mas OLT-AN6001-G16 não tem tabela de volta para OLT-8820I
        registrarUplinkComum(engine, olt8820i, "ETH1", List.of("0009-0f09-1e0a", "30e1-f110-1006"));
        registrarUplinkComum(engine, oltG16, "E1/1", List.of("0009-0f09-1e0a", "001a-3fc1-1f7c", "30e1-f110-1005"));
        registrarUplinkComum(engine, oltAn6000, "4/5", List.of("0009-0f09-1e0a", "001a-3fc1-1f7c", "30e1-f110-1004"));
        registrarUplinkComum(engine, oltAn6001, "19/5", List.of("0009-0f09-1e0a", "001a-3fc1-1f7c", "7844-7601-0204"));

        TopologyEngine.PlanoGateway plano = engine.analisarPlanoGateway();
        assertTrue(plano.possuiSegmentoIncerto(), "Deve acusar segmento L2 incerto");
        assertTrue(plano.conexoesDiretas().isEmpty(), "Não deve ligar as 6 folhas direto em Gateway LAN1..LAN6");
        assertEquals(6, plano.conexoesViaSegmento().size());
        assertEquals(3, plano.switchesIntermediariosSuspeitos().size());

        RelatorioTopologia.ResultadoRelatorio rel = RelatorioTopologia.gerarResultado(engine, null, false);
        assertTrue(rel.topologiaParcial());
        assertEquals(3, rel.pendenciasAcesso().size());
        assertTrue(rel.textoCompleto().contains("TOPOLOGIA PARCIAL"));
        assertTrue(rel.textoCompleto().contains("EQUIPAMENTOS SEM ACESSO CLI"));

        boolean temEnlaceFalsoEntreOlts = rel.enlaces().stream().anyMatch(e ->
                (e.origem().contains("OLT-8820I") && e.destino().contains("OLT-AN6001-G16"))
                        || (e.origem().contains("OLT-AN6001-G16") && e.destino().contains("OLT-8820I")));
        assertFalse(temEnlaceFalsoEntreOlts, "Não deve criar ligação OLT-8820I <-> OLT-AN6001-G16 com porta '?'");

        String html = ExportadorPlanejadorRede.gerarHtmlParaInspecao(engine);
        assertFalse(html.contains(">LAN1<"), "Não deve gerar porta falsa LAN1");
        assertFalse(html.contains(">LAN6<"), "Não deve gerar porta falsa LAN6");
        assertTrue(html.contains("ATENÇÃO: Topologia Parcial"));
        assertTrue(html.contains("Segmento L2"));
        assertTrue(html.contains("Incerto (Sem CLI)"));
        assertTrue(html.contains("Equipamentos Sem Acesso CLI / Pendências de Coleta"));
        assertTrue(html.contains("S2310G-A"));
    }

    @Test
    void deveReconhecerSwitchExternoViaLldpSemBypassNaFase2() {
        TopologyEngine engine = new TopologyEngine();

        SwitchIdentity sw1 = new SwitchIdentity("SG 1002 MR", "SN1", "001A-3FC1-1F7C", "SG 1002 MR", "10.1.40.111");
        SwitchIdentity sw2 = new SwitchIdentity("S3028G-PB Max", "SN2", "D836-5F10-2001", "S3028G-PB Max", "10.1.40.129");
        engine.registrarSwitch(sw1);
        engine.registrarSwitch(sw2);
        engine.registrarRotaPadrao(sw1.macNormalizado(), "10.1.40.1");
        engine.registrarEntradasArp(sw1.macNormalizado(), List.of(
                new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1")
        ));

        registrarUplinkComum(engine, sw1, "GE1", List.of("0009-0f09-1e0a", "d836-5f10-2001", "7844-7601-0203"));
        registrarUplinkComum(engine, sw2, "GE0/2", List.of("0009-0f09-1e0a", "001a-3fc1-1f7c", "7844-7601-0203"));

        LldpNeighbor coreNaoColetado1 = new LldpNeighbor(
                sw1.macNormalizado(), "GE1", "7844-7601-0203", "784476010203",
                "GE1/0/10", "GigabitEthernet1/0/10", "S2328G-B", "Intelbras S2328G-B Comware",
                "10.1.40.203", Set.of("Bridge"));
        LldpNeighbor coreNaoColetado2 = new LldpNeighbor(
                sw2.macNormalizado(), "GE0/2", "7844-7601-0203", "784476010203",
                "GE1/0/22", "GigabitEthernet1/0/22", "S2328G-B", "Intelbras S2328G-B Comware",
                "10.1.40.203", Set.of("Bridge"));

        assertEquals(LldpNeighbor.CategoriaInteresse.SWITCH_OU_OLT_EXTERNO,
                LldpInterestFilter.classificar(coreNaoColetado1, engine.switchesRegistrados(), engine.gatewayIdentificado()));

        engine.registrarLldp(sw1.macNormalizado(), List.of(coreNaoColetado1));
        engine.registrarLldp(sw2.macNormalizado(), List.of(coreNaoColetado2));

        TopologyEngine.PlanoGateway plano = engine.analisarPlanoGateway();
        assertEquals(2, plano.conexoesViaLldpExterno().size());
        assertTrue(plano.conexoesDiretas().isEmpty());

        RelatorioTopologia.ResultadoRelatorio rel = RelatorioTopologia.gerarResultado(engine, null, false);
        assertTrue(rel.topologiaParcial());
        boolean ligouFolhasDireto = rel.enlaces().stream().anyMatch(e ->
                e.origem().contains("SG 1002 MR") && e.destino().contains("S3028G-PB Max"));
        assertFalse(ligouFolhasDireto, "Fase 2 não pode atravessar switch externo LLDP e ligar folhas entre si");

        String html = ExportadorPlanejadorRede.gerarHtmlParaInspecao(engine);
        assertTrue(html.contains("S2328G-B"));
        assertTrue(html.contains("SEM CLI"));
    }

    @Test
    void devePosicionarPassivamenteEquipamentoSemSenhaOuSemCliPorMacEArpPreservandoCoreDireto() {
        TopologyEngine engine = new TopologyEngine();

        SwitchIdentity core = new SwitchIdentity("S2328G-B", "SNC", "7844-7601-0203", "LS-2328G-B", "10.1.40.203");
        engine.registrarSwitch(core);
        engine.registrarRotaPadrao(core.macNormalizado(), "10.1.40.1");
        engine.registrarEntradas(core.macNormalizado(), List.of(
                new MacEntry(core.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE1/0/1"),
                new MacEntry(core.macNormalizado(), "aabb-ccdd-0011", 1, "Learned", "GE1/0/5"),
                new MacEntry(core.macNormalizado(), "aabb-ccdd-0022", 1, "Learned", "GE1/0/8"),
                new MacEntry(core.macNormalizado(), "aabb-ccdd-9999", 1, "Learned", "GE1/0/8")
        ));
        engine.registrarEntradasArp(core.macNormalizado(), List.of(
                new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1/0/1"),
                // MAC do switch com falha de senha descoberto apenas via tabela ARP
                new ArpEntry("10.1.40.99", "aabb-ccdd-0022", "GE1/0/8")
        ));

        engine.registrarEquipamentoSemAcesso(new TopologyEngine.EquipamentoSemAcesso(
                "10.1.40.50", "AP 1250 AC", "AP", "aabb-ccdd-0011", "SEM_SENHA"));
        engine.registrarEquipamentoSemAcesso(new TopologyEngine.EquipamentoSemAcesso(
                "10.1.40.99", "Switch Filial", "SWITCH", "", "FALHA_AUTENTICACAO"));

        List<TopologyEngine.ConexaoPassiva> passivas = engine.resolverConexoesPassivasSemAcesso();
        assertEquals(2, passivas.size());
        assertTrue(passivas.stream().anyMatch(cp -> cp.portaPai().equals("GE1/0/5") && cp.equipamento().ipBase().equals("10.1.40.50")));
        assertTrue(passivas.stream().anyMatch(cp -> cp.portaPai().equals("GE1/0/8") && cp.equipamento().ipBase().equals("10.1.40.99")));

        TopologyEngine.PlanoGateway plano = engine.analisarPlanoGateway();
        assertFalse(plano.possuiSegmentoIncerto(), "Passivo em porta de acesso não torna incerto o uplink limpo do Core");
        assertEquals(1, plano.conexoesDiretas().size());

        String html = ExportadorPlanejadorRede.gerarHtmlParaInspecao(engine);
        assertTrue(html.contains("AP 1250 AC"));
        assertTrue(html.contains("Switch Filial"));
        assertTrue(html.contains("! AUTH"));
        assertTrue(html.contains("SEM CLI"));
    }

    @Test
    void deveSinalizarSegmentoIncertoQuandoTresOuMaisFolhasCompartilhamUplinkDoGatewaySemCoreColetado() {
        TopologyEngine engine = new TopologyEngine();

        SwitchIdentity swA = new SwitchIdentity("S3028G-A", "SNA", "001A-3FC1-0001", "S3028G", "10.1.40.11");
        SwitchIdentity swB = new SwitchIdentity("S3028G-B", "SNB", "001A-3FC1-0002", "S3028G", "10.1.40.12");
        SwitchIdentity swC = new SwitchIdentity("S3028G-C", "SNC", "001A-3FC1-0003", "S3028G", "10.1.40.13");
        for (SwitchIdentity sw : List.of(swA, swB, swC)) {
            engine.registrarSwitch(sw);
            engine.registrarRotaPadrao(sw.macNormalizado(), "10.1.40.1");
        }
        engine.registrarEntradasArp(swA.macNormalizado(), List.of(
                new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1/0/24")));

        registrarUplinkComum(engine, swA, "GE1/0/24", List.of("0009-0f09-1e0a", "001a-3fc1-0002", "001a-3fc1-0003"));
        registrarUplinkComum(engine, swB, "GE1/0/24", List.of("0009-0f09-1e0a", "001a-3fc1-0001", "001a-3fc1-0003"));
        registrarUplinkComum(engine, swC, "GE1/0/24", List.of("0009-0f09-1e0a", "001a-3fc1-0001", "001a-3fc1-0002"));

        TopologyEngine.PlanoGateway plano = engine.analisarPlanoGateway();
        assertTrue(plano.possuiSegmentoIncerto());
        assertTrue(plano.conexoesDiretas().isEmpty());
        assertEquals(3, plano.conexoesViaSegmento().size());
    }

    @Test
    void deveSuportarVizinhosLldpComCamposNulosNoRelatorioENoHtml() {
        TopologyEngine engine = new TopologyEngine();
        SwitchIdentity core = new SwitchIdentity("S2328G-B", "SNCORE", "001A-3FC1-0100", "S2328G", "10.1.40.203");
        engine.registrarSwitch(core);
        engine.registrarRotaPadrao(core.macNormalizado(), "10.1.40.1");
        engine.registrarEntradasArp(core.macNormalizado(), List.of(
                new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1/0/1")));

        // Simula LLDP conciso/parcial (Comware brief list ou AP legado) sem managementIp/portDesc/sysDesc
        engine.registrarLldp(core.macNormalizado(), List.of(
                new LldpNeighbor(
                        core.macNormalizado(),
                        "GE1/0/5",
                        "aabb-ccdd-0055",
                        "aabbccdd0055",
                        null,
                        null,
                        "AP-1250-AC",
                        null,
                        null,
                        null
                ),
                new LldpNeighbor(
                        core.macNormalizado(),
                        null,
                        "001a-3fc1-9988",
                        "001a3fc19988",
                        null,
                        null,
                        "S3028G-EXT",
                        null,
                        null,
                        Set.of("Bridge")
                )
        ));

        RelatorioTopologia.ResultadoRelatorio rel = RelatorioTopologia.gerarResultado(engine, null, false);
        assertNotNull(rel);
        assertTrue(rel.textoCompleto().contains("AP-1250-AC"));
        assertTrue(rel.textoCompleto().contains("S3028G-EXT"));

        String html = ExportadorPlanejadorRede.gerarHtmlParaInspecao(engine);
        assertTrue(html.contains("AP-1250-AC"));
        assertTrue(html.contains("S3028G-EXT"));
    }

    private static void registrarUplinkComum(TopologyEngine engine, SwitchIdentity sw, String porta, List<String> macs) {
        engine.registrarEntradas(sw.macNormalizado(), macs.stream()
                .map(m -> new MacEntry(sw.macNormalizado(), m, 1, "Learned", porta))
                .toList());
    }
}

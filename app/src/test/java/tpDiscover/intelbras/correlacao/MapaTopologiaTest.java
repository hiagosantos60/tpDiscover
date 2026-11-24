package tpDiscover.intelbras.correlacao;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.correlacao.MapaTopologia.Alerta;
import tpDiscover.intelbras.correlacao.MapaTopologia.Enlace;
import tpDiscover.intelbras.correlacao.MapaTopologia.No;
import tpDiscover.intelbras.correlacao.MapaTopologia.StatusEnlace;
import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.util.Compat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MapaTopologiaTest {

    static TopologyEngine cenarioGatewayCoreEAcessos() {
        TopologyEngine engine = new TopologyEngine();
        SwitchIdentity swAcesso1 = new SwitchIdentity("S2328G-PA", "SN203", "30E1-F1FF-661F", "LS-2328G-PA", "10.1.40.203");
        SwitchIdentity swCore = new SwitchIdentity("S3028G-PB Max", "H3TK3900068UN", "D836-5F42-5C7B", "S3028G-PB Max", "10.1.40.130");
        SwitchIdentity swAcesso2 = new SwitchIdentity("S2352G-PB", "SN204", "8085-4401-E384", "LS-2352G-PB", "10.1.40.204");
        for (SwitchIdentity sw : Compat.listOf(swAcesso1, swCore, swAcesso2)) {
            engine.registrarSwitch(sw);
        }
        engine.registrarEntradas(swAcesso1.macNormalizado(), Compat.listOf(
                new MacEntry(swAcesso1.macNormalizado(), "d836-5f42-5c7b", 1, "Learned", "GE1/0/1"),
                new MacEntry(swAcesso1.macNormalizado(), "8085-4401-e384", 1, "Learned", "GE1/0/1"),
                new MacEntry(swAcesso1.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE1/0/1")));
        engine.registrarEntradas(swCore.macNormalizado(), Compat.listOf(
                new MacEntry(swCore.macNormalizado(), "0009-0f09-1e0a", 1, "DYNAMIC", "GE1/0/24"),
                new MacEntry(swCore.macNormalizado(), "30e1-f1ff-661f", 1, "DYNAMIC", "GE1/0/1"),
                new MacEntry(swCore.macNormalizado(), "8085-4401-e384", 1, "DYNAMIC", "GE1/0/2")));
        engine.registrarEntradas(swAcesso2.macNormalizado(), Compat.listOf(
                new MacEntry(swAcesso2.macNormalizado(), "d836-5f42-5c7b", 1, "Learned", "GE1/0/1"),
                new MacEntry(swAcesso2.macNormalizado(), "30e1-f1ff-661f", 1, "Learned", "GE1/0/1"),
                new MacEntry(swAcesso2.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE1/0/1")));
        engine.registrarRotaPadrao(swCore.macNormalizado(), "10.1.40.1");
        engine.registrarEntradasArp(swCore.macNormalizado(), Compat.listOf(
                new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1/0/24")));
        return engine;
    }

    @Test
    void mapaTrazGatewaySwitchesECabosComPortas() {
        MapaTopologia mapa = ExportadorPlanejadorRede.montarMapa(cenarioGatewayCoreEAcessos());
        Map<String, No> porId = indexar(mapa);

        assertEquals(4, mapa.nos().size(), "Gateway + 3 switches");
        No gw = porId.get("dev-gw");
        assertNotNull(gw);
        assertEquals("router", gw.tipo());
        assertEquals("10.1.40.1", gw.ip());

        No core = buscarPorIp(mapa, "10.1.40.130");
        assertTrue(core.coletado());
        assertEquals(Alerta.NENHUM, core.alerta());

        assertTrue(possuiCabo(mapa, core.id(), gw.id(), "GE1/0/24"), "Gateway ligado na GE1/0/24 do core");
        assertTrue(possuiCabo(mapa, core.id(), buscarPorIp(mapa, "10.1.40.203").id(), "GE1/0/1"));
        assertTrue(possuiCabo(mapa, core.id(), buscarPorIp(mapa, "10.1.40.204").id(), "GE1/0/2"));

        // Gateway acima do core e core acima dos acessos (layout hierárquico do PDF)
        assertTrue(gw.y() < core.y());
        assertTrue(core.y() < buscarPorIp(mapa, "10.1.40.203").y());
    }

    @Test
    void cabosSoApontamParaEquipamentosDoMapa() {
        MapaTopologia mapa = ExportadorPlanejadorRede.montarMapa(cenarioGatewayCoreEAcessos());
        Map<String, No> porId = indexar(mapa);
        for (Enlace e : mapa.enlaces()) {
            assertTrue(porId.containsKey(e.idA()), "origem existe: " + e.idA());
            assertTrue(porId.containsKey(e.idB()), "destino existe: " + e.idB());
        }
        for (No n : mapa.nos()) {
            assertFalse(Double.isNaN(n.x()) || Double.isNaN(n.y()));
        }
    }

    @Test
    void topologiaParcialMostraSegmentoIncertoComAlertaDeSenha() {
        TopologyEngine engine = new TopologyEngine();
        SwitchIdentity swSg = new SwitchIdentity("SG 1002 MR", "SN1", "001A-3FC1-1F7C", "SG 1002 MR", "10.1.40.111");
        SwitchIdentity swS30 = new SwitchIdentity("S3028G-PB Max", "SN2", "D836-5F10-2001", "S3028G-PB Max", "10.1.40.129");
        for (SwitchIdentity sw : Compat.listOf(swSg, swS30)) {
            engine.registrarSwitch(sw);
            engine.registrarRotaPadrao(sw.macNormalizado(), "10.1.40.1");
        }
        engine.registrarEntradasArp(swSg.macNormalizado(), Compat.listOf(new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1")));
        engine.registrarEquipamentoSemAcesso(new TopologyEngine.EquipamentoSemAcesso(
                "10.1.40.203", "S2328G-B", "SWITCH", "7844-7601-0203", "FALHA_AUTENTICACAO"));
        engine.registrarEntradas(swSg.macNormalizado(), Compat.listOf(
                new MacEntry(swSg.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE1"),
                new MacEntry(swSg.macNormalizado(), "d836-5f10-2001", 1, "Learned", "GE1"),
                new MacEntry(swSg.macNormalizado(), "7844-7601-0203", 1, "Learned", "GE1")));
        engine.registrarEntradas(swS30.macNormalizado(), Compat.listOf(
                new MacEntry(swS30.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE0/2"),
                new MacEntry(swS30.macNormalizado(), "001a-3fc1-1f7c", 1, "Learned", "GE0/2"),
                new MacEntry(swS30.macNormalizado(), "7844-7601-0203", 1, "Learned", "GE0/2")));

        RelatorioTopologia.ResultadoRelatorio rel = RelatorioTopologia.gerarResultado(engine, null, false);
        MapaTopologia mapa = rel.mapa();
        assertFalse(mapa.estaVazio(), "O resultado da coleta deve trazer o mapa para a interface");

        No segmento = null;
        for (No n : mapa.nos()) {
            if ("segmento".equals(n.tipo())) segmento = n;
        }
        assertNotNull(segmento, "Trecho sem acesso aparece como segmento no mapa");
        assertEquals(Alerta.AUTH_ERRO, segmento.alerta());
        boolean temIncerto = false;
        for (Enlace e : mapa.enlaces()) {
            if (e.status() == StatusEnlace.INCERTO) temIncerto = true;
        }
        assertTrue(temIncerto, "Ligações via segmento sem acesso ficam como incertas");
    }

    @Test
    void exportaProjetoQueOPlanejadorDeRedeAbre() throws Exception {
        MapaTopologia mapa = ExportadorPlanejadorRede.montarMapa(cenarioGatewayCoreEAcessos());
        String json = ExportadorProjetoPlanejador.gerarJson(mapa, "Topologia teste");

        assertTrue(json.contains("\"name\": \"Topologia teste\""));
        assertTrue(json.contains("\"type\": \"router\""), "Gateway vira roteador");
        assertTrue(json.contains("\"type\": \"switch\""));
        // GE1/0/24 do core vira a porta "24" do Planejador, com o nome real na descrição
        assertTrue(json.contains("\"24\": {\"desc\": \"GE1/0/24\"}"), json);
        assertTrue(json.contains("\"port\": \"24\""));
        assertTrue(json.contains("\"port\": \"LAN1\""), "Porta LAN1 do roteador existe no Planejador");
        assertTrue(json.contains("\"cable\": \"cat6\""));
        assertTrue(json.contains("\"ip\": \"10.1.40.130\""));
        assertEquals(mapa.enlaces().size(), contar(json, "\"a\": {"), "Um cabo por enlace");
        assertEquals(mapa.nos().size(), contar(json, "\"portData\""), "Um equipamento por nó");

        java.nio.file.Path saida = java.nio.file.Paths.get("build", "planejador-exemplo.json");
        java.nio.file.Files.createDirectories(saida.getParent());
        ExportadorProjetoPlanejador.salvar(mapa, "Topologia teste", saida);
        assertTrue(java.nio.file.Files.size(saida) > 100);
    }

    @Test
    void doisCabosNaMesmaPortaViramPortasDistintasNoPlanejador() {
        // Caso real (rede 10.1.40.0/24): GE1/0/24 do S2328G-B enxerga dois switches atrás
        List<No> nos = Compat.listOf(
                new No("sw", "switch", "S2328G-B", "S2328G-B", "10.1.40.203", "N/D", "N/D", true, Alerta.NENHUM, 0, 0),
                new No("a", "switch", "S2050G-A", "S2050G-A", "10.1.40.100", "N/D", "N/D", true, Alerta.NENHUM, 0, 200),
                new No("b", "switch", "SG 1002 MR", "SG 1002 MR", "10.1.40.111", "N/D", "N/D", true, Alerta.NENHUM, 200, 200));
        List<Enlace> cabos = Compat.listOf(
                new Enlace("sw", "GE1/0/24", "a", "GE30", false, StatusEnlace.NORMAL),
                new Enlace("sw", "GE1/0/24", "b", "GE1", false, StatusEnlace.NORMAL),
                new Enlace("sw", "GE1/0/1", "b", "GE2", false, StatusEnlace.NORMAL));
        String json = ExportadorProjetoPlanejador.gerarJson(new MapaTopologia(nos, cabos), "Teste");

        assertEquals(1, contar(json, "\"a\": {\"dev\": \"sw\", \"port\": \"24\"}"), "Só um cabo fica na porta 24");
        assertTrue(json.contains("\"a\": {\"dev\": \"sw\", \"port\": \"1\"}"), "GE1/0/1 mantém a porta 1");
        assertTrue(json.contains("GE1/0/24 (cabo compartilhado"), json);
        assertEquals(3, contar(json, "\"a\": {"), "Nenhum cabo é perdido");
    }

    @Test
    void jsonEscapaTextoVindoDosEquipamentos() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u003cx\\u003e\"", ExportadorProjetoPlanejador.str("a\"b\\c\nd<x>"));
    }

    private static int contar(String texto, String trecho) {
        int n = 0;
        for (int i = texto.indexOf(trecho); i >= 0; i = texto.indexOf(trecho, i + 1)) n++;
        return n;
    }

    @Test
    void semEngineRetornaMapaVazio() {
        assertTrue(ExportadorPlanejadorRede.montarMapa(null).estaVazio());
        assertTrue(RelatorioTopologia.gerarResultado(null, null, false).mapa().estaVazio());
    }

    private static Map<String, No> indexar(MapaTopologia mapa) {
        Map<String, No> r = new HashMap<>();
        for (No n : mapa.nos()) r.put(n.id(), n);
        return r;
    }

    private static No buscarPorIp(MapaTopologia mapa, String ip) {
        for (No n : mapa.nos()) {
            if (ip.equals(n.ip())) return n;
        }
        fail("Equipamento " + ip + " não está no mapa");
        return null;
    }

    private static boolean possuiCabo(MapaTopologia mapa, String idA, String idB, String portaEmA) {
        List<Enlace> enlaces = mapa.enlaces();
        for (Enlace e : enlaces) {
            if (e.idA().equals(idA) && e.idB().equals(idB) && portaEmA.equals(e.portaA())) return true;
            if (e.idB().equals(idA) && e.idA().equals(idB) && portaEmA.equals(e.portaB())) return true;
        }
        return false;
    }
}

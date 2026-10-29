package tpDiscover.intelbras;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.correlacao.RelatorioTopologia;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.ComwareParser;
import tpDiscover.intelbras.parser.Serie3000Parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTest {

    @Test
    void testComparadorPortasNatural() {
        assertTrue(RelatorioTopologia.compararPortas("GE1/0/1", "GE1/0/2") < 0);
        assertTrue(RelatorioTopologia.compararPortas("GE1/0/2", "GE1/0/10") < 0);
        assertTrue(RelatorioTopologia.compararPortas("GE1/0/10", "GE1/0/24") < 0);
        assertTrue(RelatorioTopologia.compararPortas("GE1/0/24", "XGE1/0/25") < 0);
        assertTrue(RelatorioTopologia.compararPortas("BAGG1", "BAGG2") < 0);
    }

    @Test
    void testMacUtilsOffsetECarryNoQuintoOcteto() {
        // Offset normal dentro do mesmo 5º octeto (+11 e +26)
        assertTrue(MacUtils.pertenceAoSwitch("80854407f634", "80854407f63f"));
        assertTrue(MacUtils.pertenceAoSwitch("80854407f634", "80854407f64e"));

        // Carry para o 5º octeto (ex: base termina em 0xF8 e porta soma +16 -> 0x08 do próximo bloco)
        assertTrue(MacUtils.pertenceAoSwitch("80854407f6f8", "80854407f708"));

        // Outro equipamento do mesmo lote no mesmo 5º octeto, mas fora da janela (+128)
        assertFalse(MacUtils.pertenceAoSwitch("80854407f600", "80854407f680"));
    }

    @Test
    void testSerie3000ParserComSaidaReal() {
        Serie3000Parser parser = new Serie3000Parser();
        String rawOutput = """
                                         Mac Address Table
        ----------------------------------------------------------------------------------

         FLAG Codes:
         C -- config by CLI , NC -- not config by CLI ,
         V -- dynamic mac address for vlan mapping , M -- multi-chassis link aggregation group

        ----------------------------------------------------------------------------------

         VLAN          MAC           TYPE          INTERFACE             STATE       FLAG
         ----     --------------    -------     --------------------    --------    ------
         1        48F9.7CEA.064A    STATIC      gi1/0/2                   FWD        [NC]
         1        0009.0F09.1E0A    STATIC      gi1/0/2                   FWD        [NC]
         1        DC4E.F40F.2ED7    DYNAMIC     gi1/0/2                   FWD        [NC]
         1        4851.CFCC.8BF4    DYNAMIC     gi1/0/2                   FWD        [NC]
         1        001A.3F4A.97FD    DYNAMIC     gi1/0/2                   FWD        [NC]
         1        5810.8C68.DAF0    DYNAMIC     gi1/0/2                   FWD        [NC]
         1        808F.E89C.C0FA    DYNAMIC     gi1/0/2                   FWD        [NC]
         1        D836.5FDF.1D2B    DYNAMIC     gi1/0/2                   FWD        [NC]
         1        BC24.1113.6D16    DYNAMIC     gi1/0/2                   FWD        [NC]
         1        D877.8BB2.9397    DYNAMIC     gi1/0/2                   FWD        [NC]
         1        D836.5F89.9B40    DYNAMIC     gi1/0/2                   FWD        [NC]
         1        50D4.F724.2C0D    DYNAMIC     gi1/0/2                   FWD        [NC]
        """;

        List<MacEntry> entries = parser.parseMacAddressTable("d8365f9a5468", rawOutput);
        assertEquals(12, entries.size());

        MacEntry primeiro = entries.get(0);
        assertEquals("48f9-7cea-064a", primeiro.mac());
        assertEquals("GE1/0/2", primeiro.port());
        assertEquals(1, primeiro.vlanId());
        assertEquals("STATIC", primeiro.state());
    }

    @Test
    void testRelatorioTopologiaComDoisSwitchesEArquivo() throws IOException {
        TopologyEngine engine = new TopologyEngine();

        // Switch A (S23 Comware)
        SwitchIdentity swA = new SwitchIdentity(
                "S2328G-A",
                "210150A2MK923BQ000F4",
                "30E1-F1FF-661F",
                "LS-2328G-A",
                "10.1.40.204"
        );
        // Switch B (S30 Série 3000)
        SwitchIdentity swB = new SwitchIdentity(
                "S3028G-B",
                "210150A2MK923BQ000F5",
                "D836-5F9A-49BC",
                "S3028G-B",
                "10.1.40.130"
        );

        engine.registrarSwitch(swA);
        engine.registrarSwitch(swB);

        // Switch A vê Switch B na GE1/0/20, um host na GE1/0/1 e 2 hosts na GE1/0/24
        engine.registrarEntradas(swA.macNormalizado(), List.of(
                new MacEntry(swA.macNormalizado(), "d836-5f9a-49bd", 1, "Learned", "GE1/0/20"),
                new MacEntry(swA.macNormalizado(), "0014-2201-0203", 1, "Learned", "GE1/0/24"),
                new MacEntry(swA.macNormalizado(), "001a-a055-6677", 1, "Learned", "GE1/0/24"),
                new MacEntry(swA.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE1/0/1")
        ));

        // Switch B (S30) vê Switch A na porta normalizada GE1/0/23
        engine.registrarEntradas(swB.macNormalizado(), List.of(
                new MacEntry(swB.macNormalizado(), "30e1-f1ff-6620", 1, "STATIC", "GE1/0/23")
        ));

        RelatorioTopologia.imprimir(engine);

        String nomeEsperado = "topologia_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + ".txt";
        Path arquivoGerado = Path.of(nomeEsperado);
        assertTrue(Files.exists(arquivoGerado), "O arquivo TXT de topologia deve ter sido criado em disco");

        String relatorio = Files.readString(arquivoGerado);
        Files.deleteIfExists(arquivoGerado);

        // Valida cabeçalho compacto
        assertTrue(relatorio.contains("S2328G-A | 10.1.40.204 | 30E1-F1FF-661F | LS-2328G-A | 210150A2MK923BQ000F4"));
        assertTrue(relatorio.contains("S3028G-B | 10.1.40.130 | D836-5F9A-49BC | S3028G-B | 210150A2MK923BQ000F5"));

        // Valida ordenação natural
        int idxPorta1 = relatorio.indexOf("GE1/0/1");
        int idxPorta20 = relatorio.indexOf("GE1/0/20");
        int idxPorta24 = relatorio.indexOf("GE1/0/24");
        assertTrue(idxPorta1 < idxPorta20);
        assertTrue(idxPorta20 < idxPorta24);

        // Valida enlace cruzado entre Comware e S30
        assertTrue(relatorio.contains("-> S3028G-B (10.1.40.130) porta GE1/0/23"));
        assertTrue(relatorio.contains("-> S2328G-A (10.1.40.204) porta GE1/0/20"));

        // Valida árvore ASCII
        assertTrue(relatorio.contains("GE1/0/20 <-> GE1/0/23 ─ S3028G-B (10.1.40.130)"));
    }

    @Test
    void testCorrelacaoCadeiaTresSwitchesComwareES30() {
        TopologyEngine engine = new TopologyEngine();

        SwitchIdentity swA = new SwitchIdentity("S2328G-A", "SN-A", "8085-4401-E384", "S2328G-A", "10.1.40.204");
        SwitchIdentity swB = new SwitchIdentity("S2328G-B", "SN-B", "30E1-F1FF-661F", "S2328G-B", "10.1.40.203");
        SwitchIdentity swC = new SwitchIdentity("INTELBRAS", "SN-C", "D836-5F42-5C7D", "S3028G-PB", "10.1.40.130");

        engine.registrarSwitch(swA);
        engine.registrarSwitch(swB);
        engine.registrarSwitch(swC);

        // Mesmo que A só tenha aprendido B na GE1/0/20 (MAC de C expirou na tabela de A),
        // o motor infere transitivamente que C está atrás de B!
        engine.registrarEntradas(swA.macNormalizado(), List.of(
                new MacEntry(swA.macNormalizado(), "30e1-f1ff-661f", 1, "Learned", "GE1/0/20")
        ));

        // B vê A na GE1/0/23 e C na BAGG2
        engine.registrarEntradas(swB.macNormalizado(), List.of(
                new MacEntry(swB.macNormalizado(), "8085-4401-e384", 1, "Learned", "GE1/0/23"),
                new MacEntry(swB.macNormalizado(), "d836-5f42-5c7b", 1, "Learned", "BAGG2")
        ));

        // C (S30) vê B e A na GE1/0/2
        engine.registrarEntradas(swC.macNormalizado(), List.of(
                new MacEntry(swC.macNormalizado(), "30e1-f1ff-661f", 1, "DYNAMIC", "GE1/0/2"),
                new MacEntry(swC.macNormalizado(), "8085-4401-e384", 1, "DYNAMIC", "GE1/0/2")
        ));

        String relatorio = RelatorioTopologia.gerarRelatorioTexto(engine);

        // A deve identificar B como vizinho direto na GE1/0/20 e C como "Switch atrás"
        assertTrue(relatorio.contains("-> S2328G-B (10.1.40.203) porta GE1/0/23"));
        assertTrue(relatorio.contains("Switch atrás: INTELBRAS (10.1.40.130)"));

        // C (S30) deve identificar B como vizinho direto na GE1/0/2 e A como "Switch atrás"
        assertTrue(relatorio.contains("-> S2328G-B (10.1.40.203) porta BAGG2"));
        assertTrue(relatorio.contains("Switch atrás: S2328G-A (10.1.40.204)"));

        // B deve ser raiz da árvore conectando A e C sem ambiguidade
        assertTrue(relatorio.contains("GE1/0/23 <-> GE1/0/20 ─ S2328G-A (10.1.40.204)"));
        assertTrue(relatorio.contains("BAGG2 <-> GE1/0/2 ─ INTELBRAS (10.1.40.130)"));
    }

    @Test
    void testInferenciaIntermediarioOmitidoPorAgingCenarioTeste() {
        TopologyEngine engine = new TopologyEngine();

        SwitchIdentity sw24 = new SwitchIdentity("SWT-24", "SN-24", "D836-5F9A-5468", "S3054G-B", "10.0.0.24");
        SwitchIdentity sw10 = new SwitchIdentity("SWT-10", "SN-10", "8085-4407-F634", "S3352G-B", "10.0.0.10");
        SwitchIdentity sw18 = new SwitchIdentity("SWT-18", "SN-18", "8085-4403-C045", "S2328G-PB", "10.0.0.18");
        SwitchIdentity sw21 = new SwitchIdentity("SWT-21", "SN-21", "8085-4407-B35C", "S3328G-B", "10.0.0.21");

        engine.registrarSwitch(sw24);
        engine.registrarSwitch(sw10);
        engine.registrarSwitch(sw18);
        engine.registrarSwitch(sw21);

        // SWT .24 vê .10 e .18 na GE1/0/11, e vê .21 na GE1/0/35
        engine.registrarEntradas(sw24.macNormalizado(), List.of(
                new MacEntry(sw24.macNormalizado(), "8085-4407-f634", 1, "Learned", "GE1/0/11"),
                new MacEntry(sw24.macNormalizado(), "8085-4403-c045", 1, "Learned", "GE1/0/11"),
                new MacEntry(sw24.macNormalizado(), "8085-4407-b35c", 1, "Learned", "GE1/0/35")
        ));

        // SWT .10 vê .24 e .21 na GE1/0/1, e vê .18 na GE1/0/26
        engine.registrarEntradas(sw10.macNormalizado(), List.of(
                new MacEntry(sw10.macNormalizado(), "d836-5f9a-5469", 1, "Learned", "GE1/0/1"),
                new MacEntry(sw10.macNormalizado(), "8085-4407-b367", 1, "Learned", "GE1/0/1"),
                new MacEntry(sw10.macNormalizado(), "8085-4403-c050", 1, "Learned", "GE1/0/26"),
                new MacEntry(sw10.macNormalizado(), "8085-4403-c05f", 1, "Learned", "GE1/0/26")
        ));

        // SWT .18 (borda ligada na GE1/0/26 do .10) aprendeu .24 e .21 na GE1/0/13,
        // mas NÃO tem o MAC de .10 na tabela (caso real do cenario_teste.txt)
        engine.registrarEntradas(sw18.macNormalizado(), List.of(
                new MacEntry(sw18.macNormalizado(), "d836-5f9a-5469", 1, "Learned", "GE1/0/13"),
                new MacEntry(sw18.macNormalizado(), "8085-4407-b367", 1, "Learned", "GE1/0/13")
        ));

        // SWT .21 vê .24, .10 e .18 na GE1/0/3
        engine.registrarEntradas(sw21.macNormalizado(), List.of(
                new MacEntry(sw21.macNormalizado(), "d836-5f9a-5469", 1, "Learned", "GE1/0/3"),
                new MacEntry(sw21.macNormalizado(), "8085-4407-f63f", 1, "Learned", "GE1/0/3"),
                new MacEntry(sw21.macNormalizado(), "8085-4403-c050", 1, "Learned", "GE1/0/3")
        ));

        String relatorio = RelatorioTopologia.gerarRelatorioTexto(engine);

        // SWT .10 deve saber que a porta remota de SWT .18 é GE1/0/13 (e não '?')
        assertTrue(relatorio.contains("-> SWT-18 (10.0.0.18) porta GE1/0/13"));

        // SWT .18 deve identificar SWT .10 (porta GE1/0/26) como seu vizinho direto na GE1/0/13
        assertTrue(relatorio.contains("-> SWT-10 (10.0.0.10) porta GE1/0/26"));
    }

    @Test
    void testExpansaoPortasAgregadasComware() {
        ComwareParser parser = new ComwareParser();
        String lagOutput = """
                Aggregate Interface: Bridge-Aggregation2
                Aggregation Mode: Dynamic
                Loadsharing Type: Shar
                System ID: 0x8000, 30e1-f1ff-661f
                Local:
                  Port             Status  Priority Oper-Key  Flag
                --------------------------------------------------------------------------------
                  GE1/0/1          S       32768    2         {ACDEF}
                  GE1/0/2          S       32768    2         {ACDEF}
                Remote:
                  Actor            Partner Priority Oper-Key  SystemID               Flag
                --------------------------------------------------------------------------------
                  GE1/0/1          2       32768    1         0x8000, d836-5f42-5c7b {ACDEF}
                """;

        Map<String, List<String>> membros = parser.parseLinkAggregation(lagOutput);
        assertEquals(List.of("GE1/0/1", "GE1/0/2"), membros.get("BAGG2"));

        List<MacEntry> originais = List.of(
                new MacEntry("30e1f1ff661f", "d836-5f42-5c7b", 1, "Learned", "BAGG2")
        );
        List<MacEntry> expandidas = parser.expandirPortasAgregadas(originais, membros);
        assertEquals("BAGG2 (GE1/0/1, GE1/0/2)", expandidas.get(0).port());
    }
}

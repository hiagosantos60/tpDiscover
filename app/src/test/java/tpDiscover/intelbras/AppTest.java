package tpDiscover.intelbras;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tpDiscover.intelbras.coleta.AlvosLoader;
import tpDiscover.intelbras.coleta.ColetorEmLote;
import tpDiscover.intelbras.coleta.LocalGatewayDetector;
import tpDiscover.intelbras.correlacao.RelatorioTopologia;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.ComwareParser;
import tpDiscover.intelbras.parser.OltParser;
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

        // MAC zerado nunca deve casar como switch
        assertFalse(MacUtils.pertenceAoSwitch("000000000000", "000000000000"));
    }

    @Test
    void testOltParserComSaidaReal() {
        OltParser parser = new OltParser();
        String rawInfo = """
                software platform   :Broadband Network Platform Software
                software version    :OLT-04P V100R001B01D001P007SP6
                MAC address         :58:10:8c:68:da:f0
                product serial no.  :1
                system configuration
                system description     :EPON
                system name            :OLT4880E
                product name           :OLT-04P EPON Product
                """;

        assertTrue(parser.ehDispositivoOlt(rawInfo));
        SwitchIdentity id = parser.parseDeviceInfo("10.1.40.131", "OLT", rawInfo);
        assertEquals("OLT4880E", id.deviceName());
        assertEquals("5810-8C68-DAF0", id.macAddress());
        assertEquals("OLT-04P EPON", id.productId());
        assertEquals("1", id.serialNumber());

        String rawMacTable = """
                MAC Address           VLAN ID  port     status
                00:09:0f:09:1e:0a     1        e0/2     dynamic
                80:85:44:01:e3:84     1        e0/2     dynamic
                58:10:8c:68:da:f0     1        cpu      static
                30:e1:f1:ff:66:1f     1        e0/2     dynamic
                Total entries: 4 .
                """;

        List<MacEntry> entries = parser.parseMacAddressTable(id.macNormalizado(), rawMacTable);
        // A entrada "cpu" deve ser ignorada
        assertEquals(3, entries.size());
        assertEquals("0009-0f09-1e0a", entries.get(0).mac());
        assertEquals("E0/2", entries.get(0).port());
        assertEquals(1, entries.get(0).vlanId());
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
    void testRelatorioTopologiaComDoisSwitchesEArquivo(@TempDir Path tempDir) throws IOException {
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

        RelatorioTopologia.imprimir(engine, tempDir);

        String nomeEsperado = "topologia_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + ".txt";
        Path arquivoGerado = tempDir.resolve(nomeEsperado);
        assertTrue(Files.exists(arquivoGerado), "O arquivo TXT de topologia deve ter sido criado em disco");

        String relatorio = Files.readString(arquivoGerado);

        // Garante que apenas os dois blocos de topologia (Enlaces Diretos + Árvore) são exibidos, sem tabelas por switch
        assertFalse(relatorio.contains("Qtd MACs"));
        assertTrue(relatorio.startsWith("====================================================================================================\nENLACES DIRETOS (PAR A PAR)"));
        assertTrue(relatorio.contains("S2328G-A (10.1.40.204) [GE1/0/20] <-> [GE1/0/23] S3028G-B (10.1.40.130)"));
        assertTrue(relatorio.contains("ÁRVORE DA TOPOLOGIA\n====================================================================================================\n\n└─ S2328G-A (10.1.40.204)"));
        assertTrue(relatorio.contains("   └─ GE1/0/20 <-> GE1/0/23 ─ S3028G-B (10.1.40.130)"));
    }

    @Test
    void testCorrelacaoCadeiaTresSwitchesComwareES30SemIntelbrasNoNome() {
        TopologyEngine engine = new TopologyEngine();

        SwitchIdentity swA = new SwitchIdentity("S2328G-A", "SN-A", "8085-4401-E384", "S2328G-A", "10.1.40.204");
        SwitchIdentity swB = new SwitchIdentity("S2328G-B", "SN-B", "30E1-F1FF-661F", "S2328G-B", "10.1.40.203");
        // Mesmo que deviceName venha "INTELBRAS" legado, o relatório substitui pelo modelo S3028G-PB
        SwitchIdentity swC = new SwitchIdentity("INTELBRAS", "SN-C", "D836-5F42-5C7D", "S3028G-PB", "10.1.40.130");

        engine.registrarSwitch(swA);
        engine.registrarSwitch(swB);
        engine.registrarSwitch(swC);

        engine.registrarEntradas(swA.macNormalizado(), List.of(
                new MacEntry(swA.macNormalizado(), "30e1-f1ff-661f", 1, "Learned", "GE1/0/20")
        ));

        engine.registrarEntradas(swB.macNormalizado(), List.of(
                new MacEntry(swB.macNormalizado(), "8085-4401-e384", 1, "Learned", "GE1/0/23"),
                new MacEntry(swB.macNormalizado(), "d836-5f42-5c7b", 1, "Learned", "BAGG2")
        ));

        engine.registrarEntradas(swC.macNormalizado(), List.of(
                new MacEntry(swC.macNormalizado(), "30e1-f1ff-661f", 1, "DYNAMIC", "GE1/0/2"),
                new MacEntry(swC.macNormalizado(), "8085-4401-e384", 1, "DYNAMIC", "GE1/0/2")
        ));

        String relatorio = RelatorioTopologia.gerarRelatorioTexto(engine);

        assertFalse(relatorio.contains("INTELBRAS"), "Não deve exibir o prefixo genérico INTELBRAS no relatório");
        assertTrue(relatorio.contains("S2328G-A (10.1.40.204) [GE1/0/20] <-> [GE1/0/23] S2328G-B (10.1.40.203)"));
        assertTrue(relatorio.contains("S2328G-B (10.1.40.203) [BAGG2]    <-> [GE1/0/2]  S3028G-PB (10.1.40.130)"));

        String arvore = relatorio.substring(relatorio.indexOf("ÁRVORE DA TOPOLOGIA"));
        int idxNoA = arvore.indexOf("└─ S2328G-A (10.1.40.204)");
        int idxNoB = arvore.indexOf("GE1/0/20 <-> GE1/0/23 ─ S2328G-B (10.1.40.203)");
        int idxNoC = arvore.indexOf("BAGG2 <-> GE1/0/2 ─ S3028G-PB (10.1.40.130)");
        assertTrue(idxNoA > 0 && idxNoA < idxNoB && idxNoB < idxNoC);
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

        engine.registrarEntradas(sw24.macNormalizado(), List.of(
                new MacEntry(sw24.macNormalizado(), "8085-4407-f634", 1, "Learned", "GE1/0/11"),
                new MacEntry(sw24.macNormalizado(), "8085-4403-c045", 1, "Learned", "GE1/0/11"),
                new MacEntry(sw24.macNormalizado(), "8085-4407-b35c", 1, "Learned", "GE1/0/35")
        ));

        engine.registrarEntradas(sw10.macNormalizado(), List.of(
                new MacEntry(sw10.macNormalizado(), "d836-5f9a-5469", 1, "Learned", "GE1/0/1"),
                new MacEntry(sw10.macNormalizado(), "8085-4407-b367", 1, "Learned", "GE1/0/1"),
                new MacEntry(sw10.macNormalizado(), "8085-4403-c050", 1, "Learned", "GE1/0/26"),
                new MacEntry(sw10.macNormalizado(), "8085-4403-c05f", 1, "Learned", "GE1/0/26")
        ));

        engine.registrarEntradas(sw18.macNormalizado(), List.of(
                new MacEntry(sw18.macNormalizado(), "d836-5f9a-5469", 1, "Learned", "GE1/0/13"),
                new MacEntry(sw18.macNormalizado(), "8085-4407-b367", 1, "Learned", "GE1/0/13")
        ));

        engine.registrarEntradas(sw21.macNormalizado(), List.of(
                new MacEntry(sw21.macNormalizado(), "d836-5f9a-5469", 1, "Learned", "GE1/0/3"),
                new MacEntry(sw21.macNormalizado(), "8085-4407-f63f", 1, "Learned", "GE1/0/3"),
                new MacEntry(sw21.macNormalizado(), "8085-4403-c050", 1, "Learned", "GE1/0/3")
        ));

        String relatorio = RelatorioTopologia.gerarRelatorioTexto(engine);

        assertTrue(relatorio.contains("SWT-10 (10.0.0.10) [GE1/0/26] <-> [GE1/0/13] SWT-18 (10.0.0.18)"));
        assertTrue(relatorio.contains("SWT-24 (10.0.0.24) [GE1/0/11] <-> [GE1/0/1]  SWT-10 (10.0.0.10)"));
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

    @Test
    void testSerie3000IdentidadeRealS30SemIntelbrasERotaArpReais() {
        String showInterfaceVlan1 = """
                vlan1:
                     line protocol is up
                     Internet address: 10.1.40.130/24
                     Ethernet address is d836.5f42.5c7b
                     5 minutes input rate 459 bits/sec, 0 packet/sec, bandwidth utilization 0.00%
                     Unknown protocol 5168065
                INTELBRAS#
                """;
        assertFalse(ColetorEmLote.ehComandoInvalido(showInterfaceVlan1));
        assertTrue(ColetorEmLote.ehComandoInvalido("% Invalid input detected at '^' marker"));
        assertTrue(ColetorEmLote.ehComandoInvalido("% Unknown command"));

        String saidaCombinadaS30 = """
                               Intelbras (R) Operating System Software
                Intelbras S3028G-PB Max system image file (flash0: /flash/sp35-g-9.7.400.1(R).pck), version 9.7.400.1
                Intelbras S3028G-PB Max Version Information
                \tSystem ID           : d8365f9a4cbc
                \tHardware Model\t    : Intelbras S3028G-PB Max
                \tSoftware Version    : 9.7.400.1
                INTELBRAS#
                system mac information:
                                ap mac: d836.5f42.5c7c
                                ag mac: d836.5f42.5c7d
                              intf mac: d836.5f42.5c7b
                               dc0 mac: d836.5f42.5c7a
                INTELBRAS#
                 System Card Information(Device:1 - Mpu 0 - ONLINE)
                                    Type:   Intelbras S3028G-PB Max
                              Serial No.:   H3TK3900068UN
                             Core-Utilization:  9%
                INTELBRAS#
                """;

        Serie3000Parser parser = new Serie3000Parser();
        SwitchIdentity id = parser.parseDeviceVersion("10.1.40.130", null, saidaCombinadaS30);
        // Com prompt padrão INTELBRAS#, o deviceName adota diretamente o modelo S3028G-PB Max
        assertEquals("S3028G-PB Max", id.deviceName());
        assertEquals("D836-5F42-5C7B", id.macAddress());
        assertEquals("S3028G-PB Max", id.productId());
        assertEquals("H3TK3900068UN", id.serialNumber());

        // Se o cliente configurou um hostname customizado (ex: SW-RECEPCAO#), preserva o hostname
        String saidaComHostnameCustomizado = saidaCombinadaS30.replace("INTELBRAS#", "SW-RECEPCAO#");
        SwitchIdentity idCustom = parser.parseDeviceVersion("10.1.40.130", null, saidaComHostnameCustomizado);
        assertEquals("SW-RECEPCAO", idCustom.deviceName());
        assertEquals("S3028G-PB Max", idCustom.productId());

        // Valida parse de "show ip route" e "show arp" capturados do S30 real 10.1.40.130
        String showIpRouteReal = """
                Codes: C - Connected, L - Local, S - static, R - RIP,  B - BGP, i-ISIS
                S   0.0.0.0/0 [1/0] via 10.1.40.1, 9w5d:03:58:13, vlan1
                C   10.1.40.0/24 is directly connected, 9w5d:03:58:13, vlan1
                L   10.1.40.130/32 is directly connected, 9w5d:03:58:13, vlan1
                INTELBRAS#
                """;
        assertEquals("10.1.40.1", parser.parseDefaultGatewayIp(showIpRouteReal).orElseThrow());

        String showArpReal = """
                Protocol  Address             Age (min) Hardware Addr   Type    Interface                          Switchport
                Internet  10.1.40.1           0         0009.0f09.1e0a  ARPA    vlan1                              gi1/0/2
                Internet  10.1.40.130         -         d836.5f42.5c7b  ARPA    vlan1                              ---
                Internet  10.1.40.142         0         48f9.7cea.064a  ARPA    vlan1                              gi1/0/2
                INTELBRAS#
                """;
        List<ArpEntry> arps = parser.parseArpTable(showArpReal);
        assertEquals(3, arps.size());
        assertEquals("10.1.40.1", arps.get(0).ip());
        assertEquals("0009-0f09-1e0a", arps.get(0).mac());
        assertEquals("GE1/0/2", arps.get(0).port());
        assertEquals(null, arps.get(1).port());
    }

    @Test
    void testDeteccaoRoteadorCoreNaArvoreEDestaqueGatewayEmTudo(@TempDir Path tempDir) throws IOException {
        // Valida também diretiva opcional '# gateway=...' no switches.txt
        Path arqSwitches = tempDir.resolve("switches.txt");
        Files.writeString(arqSwitches, """
                # Lista do cliente
                # gateway=10.1.40.1
                10.1.40.203;admin;Admin@1234
                10.1.40.130;admin;Admin@1234
                10.1.40.204;admin;Admin@1234
                """);
        AlvosLoader.ConfiguracaoAlvos cfg = AlvosLoader.carregarConfiguracao(arqSwitches);
        assertEquals(3, cfg.alvos().size());
        assertEquals("10.1.40.1", cfg.gatewayIpManual());

        // Valida parser de rota/ARP Comware e ARP local Windows
        ComwareParser comwareParser = new ComwareParser();
        String comwareRoute = """
                Destinations : 6        Routes : 6
                Destination/Mask    Proto   Pre  Cost        NextHop         Interface
                0.0.0.0/0           Static  60   0           10.1.40.1       Vlan-interface1
                10.1.40.0/24        Direct  0    0           10.1.40.203     Vlan-interface1
                """;
        assertEquals("10.1.40.1", comwareParser.parseDefaultGatewayIp(comwareRoute).orElseThrow());

        String comwareArp = """
                Type: S-Static   D-Dynamic   O-Openflow   R-Rule   I-Invalid
                IP Address       MAC Address    VLAN ID  Interface              Aging Type
                10.1.40.1        0009-0f09-1e0a 1        GE1/0/1                1180  D
                """;
        List<ArpEntry> arpsComware = comwareParser.parseArpTable(comwareArp);
        assertEquals(1, arpsComware.size());
        assertEquals("GE1/0/1", arpsComware.get(0).port());

        String saidaArpWindows = """
                Interface: 10.1.40.142 --- 0x17
                  Endereço IP           Endereço físico       Tipo
                  10.1.40.1             00-09-0f-09-1e-0a     dinâmico
                  10.1.40.255           ff-ff-ff-ff-ff-ff     estático
                """;
        assertEquals("0009-0f09-1e0a", LocalGatewayDetector.extrairMacDeSaidaArp("10.1.40.1", saidaArpWindows).orElseThrow());

        // Monta topologia onde swAcesso1 (.203) vem ANTES de swCore (.130) no switches.txt,
        // mas o Roteador Fortinet (10.1.40.1 / 0009-0f09-1e0a) entra fisicamente na GE1/0/24 do swCore (.130)!
        TopologyEngine engine = new TopologyEngine();
        SwitchIdentity swAcesso1 = new SwitchIdentity("S2328G-PA", "SN203", "30E1-F1FF-661F", "LS-2328G-PA", "10.1.40.203");
        SwitchIdentity swCore    = new SwitchIdentity("S3028G-PB Max", "H3TK3900068UN", "D836-5F42-5C7B", "S3028G-PB Max", "10.1.40.130");
        SwitchIdentity swAcesso2 = new SwitchIdentity("S2352G-PB", "SN204", "8085-4401-E384", "LS-2352G-PB", "10.1.40.204");

        List.of(swAcesso1, swCore, swAcesso2).forEach(engine::registrarSwitch);

        // Todos os switches aprendem o MAC do Fortinet (0009-0f09-1e0a), porém nos switches de acesso ele aparece
        // junto com o uplink para o swCore, enquanto no swCore aparece isolado na porta de entrada GE1/0/24!
        engine.registrarEntradas(swAcesso1.macNormalizado(), List.of(
                new MacEntry(swAcesso1.macNormalizado(), "d836-5f42-5c7b", 1, "Learned", "GE1/0/1"),
                new MacEntry(swAcesso1.macNormalizado(), "8085-4401-e384", 1, "Learned", "GE1/0/1"),
                new MacEntry(swAcesso1.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE1/0/1")
        ));
        engine.registrarEntradas(swCore.macNormalizado(), List.of(
                new MacEntry(swCore.macNormalizado(), "0009-0f09-1e0a", 1, "DYNAMIC", "GE1/0/24"),
                new MacEntry(swCore.macNormalizado(), "30e1-f1ff-661f", 1, "DYNAMIC", "GE1/0/1"),
                new MacEntry(swCore.macNormalizado(), "8085-4401-e384", 1, "DYNAMIC", "GE1/0/2")
        ));
        engine.registrarEntradas(swAcesso2.macNormalizado(), List.of(
                new MacEntry(swAcesso2.macNormalizado(), "d836-5f42-5c7b", 1, "Learned", "GE1/0/1"),
                new MacEntry(swAcesso2.macNormalizado(), "30e1-f1ff-661f", 1, "Learned", "GE1/0/1"),
                new MacEntry(swAcesso2.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE1/0/1")
        ));

        engine.registrarRotaPadrao(swCore.macNormalizado(), "10.1.40.1");
        engine.registrarEntradasArp(swCore.macNormalizado(), List.of(
                new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1/0/24")
        ));

        String relatorio = RelatorioTopologia.gerarRelatorioTexto(engine);

        // 1) Porta de entrada do Gateway reconhecida apenas na GE1/0/24 do swCore (sem exibir tabelas individuais)
        assertTrue(engine.ehPortaDeEntradaGateway(swCore.macNormalizado(), "GE1/0/24"));
        assertFalse(engine.ehPortaDeEntradaGateway(swAcesso1.macNormalizado(), "GE1/0/1"));
        assertFalse(relatorio.contains("Qtd MACs"));

        // 2) Ligação do Roteador no topo de ENLACES DIRETOS (PAR A PAR)
        String secaoEnlaces = relatorio.substring(
                relatorio.indexOf("ENLACES DIRETOS (PAR A PAR)"),
                relatorio.indexOf("ÁRVORE DA TOPOLOGIA")
        );
        assertTrue(secaoEnlaces.contains("Roteador/Gateway 10.1.40.1 [0009-0f09-1e0a (Fortinet)] <-> [GE1/0/24] S3028G-PB Max (10.1.40.130)"));

        // 3) ÁRVORE DA TOPOLOGIA iniciada no Roteador -> Core (.130) derivando para baixo para .203 e .204
        String secaoArvore = relatorio.substring(relatorio.indexOf("ÁRVORE DA TOPOLOGIA"));
        String arvoreEsperada = """
                Roteador/Gateway 10.1.40.1 [0009-0f09-1e0a (Fortinet)]
                └─ <-> GE1/0/24 ─ S3028G-PB Max (10.1.40.130)
                   ├─ GE1/0/1 <-> GE1/0/1 ─ S2328G-PA (10.1.40.203)
                   └─ GE1/0/2 <-> GE1/0/1 ─ S2352G-PB (10.1.40.204)
                """;
        assertTrue(secaoArvore.contains(arvoreEsperada));
    }

    @Test
    void testDoisSwitchesCoreLigadosDiretoEmPortasEmBridgeDoRoteador() {
        TopologyEngine engine = new TopologyEngine();
        SwitchIdentity swCore1 = new SwitchIdentity("S2328G-PA", "SN1", "30E1-F1FF-661F", "LS-2328G-PA", "10.1.40.10");
        SwitchIdentity swCore2 = new SwitchIdentity("S3028G-B", "SN2", "D836-5F42-5C7B", "S3028G-B", "10.1.40.20");
        engine.registrarSwitch(swCore1);
        engine.registrarSwitch(swCore2);

        // Ambos estão plugados diretamente em 2 portas LAN em bridge do mesmo Fortigate (0009-0f09-1e0a):
        // logo, swCore1 vê Fortigate + swCore2 na GE1/0/24, e swCore2 vê Fortigate + swCore1 na GE0/24.
        engine.registrarEntradas(swCore1.macNormalizado(), List.of(
                new MacEntry(swCore1.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE1/0/24"),
                new MacEntry(swCore1.macNormalizado(), "d836-5f42-5c7b", 1, "Learned", "GE1/0/24")
        ));
        engine.registrarEntradas(swCore2.macNormalizado(), List.of(
                new MacEntry(swCore2.macNormalizado(), "0009-0f09-1e0a", 1, "DYNAMIC", "GE0/24"),
                new MacEntry(swCore2.macNormalizado(), "30e1-f1ff-661f", 1, "DYNAMIC", "GE0/24")
        ));
        engine.registrarRotaPadrao(swCore1.macNormalizado(), "10.1.40.1");
        engine.registrarEntradasArp(swCore1.macNormalizado(), List.of(new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1/0/24")));

        String relatorio = RelatorioTopologia.gerarRelatorioTexto(engine);
        String secaoArvore = relatorio.substring(relatorio.indexOf("ÁRVORE DA TOPOLOGIA"));

        // Ambos devem aparecer como cores derivados diretamente do Roteador/Gateway (sem cabo falso direto entre eles)
        String esperado = """
                Roteador/Gateway 10.1.40.1 [0009-0f09-1e0a (Fortinet)]
                ├─ <-> GE1/0/24 ─ S2328G-PA (10.1.40.10)
                └─ <-> GE0/24 ─ S3028G-B (10.1.40.20)
                """;
        assertTrue(secaoArvore.contains(esperado));
    }

    @Test
    void testVisual1SemRoteadorEmBancadaCenarios2E3E4E6() {
        SwitchIdentity sw10 = new SwitchIdentity("S2328G-PA", "SN10", "30E1-F1FC-90C7", "LS-2328G-PA", "10.0.0.10");
        SwitchIdentity sw11 = new SwitchIdentity("S3028G-B", null, "D836-5F9A-49BB", "S3028G-B", "10.0.0.11");
        SwitchIdentity sw12 = new SwitchIdentity("S2328G-PB", "SN12", "8085-4403-C045", "LS-2328G-PB", "10.0.0.12");
        SwitchIdentity sw13 = new SwitchIdentity("S2352G-PB", "SN13", "8085-4406-B4A5", "LS-2352G-PB", "10.0.0.13");
        SwitchIdentity sw14 = new SwitchIdentity("S2352G-A", "SN14", "8085-4405-71C5", "LS-2352G-A", "10.0.0.14");

        // 1) Cenário 4 (Cascata pura: .10 -> .11 -> .12 -> .13 -> .14)
        TopologyEngine engine4 = new TopologyEngine();
        List.of(sw10, sw11, sw12, sw13, sw14).forEach(engine4::registrarSwitch);
        engine4.registrarEntradas(sw10.macNormalizado(), List.of(new MacEntry(sw10.macNormalizado(), "d836-5f9a-49bd", 1, "Learned", "GE1/0/2")));
        engine4.registrarEntradas(sw11.macNormalizado(), List.of(
                new MacEntry(sw11.macNormalizado(), "30e1-f1fc-90c7", 1, "DYNAMIC", "GE0/2"),
                new MacEntry(sw11.macNormalizado(), "8085-4403-c045", 1, "DYNAMIC", "GE0/3")
        ));
        engine4.registrarEntradas(sw12.macNormalizado(), List.of(
                new MacEntry(sw12.macNormalizado(), "d836-5f9a-49bd", 1, "Learned", "GE1/0/3"),
                new MacEntry(sw12.macNormalizado(), "8085-4406-b4a5", 1, "Learned", "GE1/0/6")
        ));
        engine4.registrarEntradas(sw13.macNormalizado(), List.of(
                new MacEntry(sw13.macNormalizado(), "8085-4403-c045", 1, "Learned", "GE1/0/5"),
                new MacEntry(sw13.macNormalizado(), "8085-4405-71c5", 1, "Learned", "GE1/0/8")
        ));
        engine4.registrarEntradas(sw14.macNormalizado(), List.of(new MacEntry(sw14.macNormalizado(), "8085-4406-b4a5", 1, "Learned", "GE1/0/9")));

        String rel4 = RelatorioTopologia.gerarRelatorioTexto(engine4);
        String arvore4 = rel4.substring(rel4.indexOf("ÁRVORE DA TOPOLOGIA"));
        String esperadoCenario4 = """
                ÁRVORE DA TOPOLOGIA
                ====================================================================================================

                └─ S2328G-PA (10.0.0.10)
                   └─ GE1/0/2 <-> GE0/2 ─ S3028G-B (10.0.0.11)
                      └─ GE0/3 <-> GE1/0/3 ─ S2328G-PB (10.0.0.12)
                         └─ GE1/0/6 <-> GE1/0/5 ─ S2352G-PB (10.0.0.13)
                            └─ GE1/0/8 <-> GE1/0/9 ─ S2352G-A (10.0.0.14)
                """;
        assertTrue(arvore4.contains(esperadoCenario4));

        // 2) Cenário 2 (Derivação no .11 sem colocar .11 como principal; topo vazio descendo em └─ .10)
        TopologyEngine engine2 = new TopologyEngine();
        List.of(sw10, sw11, sw12, sw13, sw14).forEach(engine2::registrarSwitch);
        engine2.registrarEntradas(sw10.macNormalizado(), List.of(new MacEntry(sw10.macNormalizado(), "d836-5f9a-49bd", 1, "Learned", "GE1/0/1")));
        engine2.registrarEntradas(sw11.macNormalizado(), List.of(
                new MacEntry(sw11.macNormalizado(), "30e1-f1fc-90c7", 1, "DYNAMIC", "GE0/2"),
                new MacEntry(sw11.macNormalizado(), "8085-4403-c045", 1, "DYNAMIC", "GE0/9"),
                new MacEntry(sw11.macNormalizado(), "8085-4406-b4a5", 1, "DYNAMIC", "GE0/16")
        ));
        engine2.registrarEntradas(sw12.macNormalizado(), List.of(new MacEntry(sw12.macNormalizado(), "d836-5f9a-49bd", 1, "Learned", "GE1/0/7")));
        engine2.registrarEntradas(sw13.macNormalizado(), List.of(
                new MacEntry(sw13.macNormalizado(), "d836-5f9a-49bd", 1, "Learned", "GE1/0/15"),
                new MacEntry(sw13.macNormalizado(), "8085-4405-71c5", 1, "Learned", "GE1/0/24")
        ));
        engine2.registrarEntradas(sw14.macNormalizado(), List.of(new MacEntry(sw14.macNormalizado(), "8085-4406-b4a5", 1, "Learned", "GE1/0/25")));

        String rel2 = RelatorioTopologia.gerarRelatorioTexto(engine2);
        String arvore2 = rel2.substring(rel2.indexOf("ÁRVORE DA TOPOLOGIA"));
        String esperadoCenario2 = """
                ÁRVORE DA TOPOLOGIA
                ====================================================================================================

                └─ S2328G-PA (10.0.0.10)
                   └─ GE1/0/1 <-> GE0/2 ─ S3028G-B (10.0.0.11)
                      ├─ GE0/9 <-> GE1/0/7 ─ S2328G-PB (10.0.0.12)
                      └─ GE0/16 <-> GE1/0/15 ─ S2352G-PB (10.0.0.13)
                         └─ GE1/0/24 <-> GE1/0/25 ─ S2352G-A (10.0.0.14)
                """;
        assertTrue(arvore2.contains(esperadoCenario2));

        // 3) Cenário 6 (Estrela sem gateway: linha do topo vazia e desce em └─ S2328G-PA (10.0.0.10))
        TopologyEngine engine6 = new TopologyEngine();
        List.of(sw10, sw11, sw12, sw13, sw14).forEach(engine6::registrarSwitch);
        engine6.registrarEntradas(sw11.macNormalizado(), List.of(
                new MacEntry(sw11.macNormalizado(), "30e1-f1fc-90c7", 1, "DYNAMIC", "GE0/1"),
                new MacEntry(sw11.macNormalizado(), "8085-4406-b4a5", 1, "DYNAMIC", "GE0/10"),
                new MacEntry(sw11.macNormalizado(), "8085-4405-71c5", 1, "DYNAMIC", "GE0/13"),
                new MacEntry(sw11.macNormalizado(), "8085-4403-c045", 1, "DYNAMIC", "GE0/23")
        ));
        engine6.registrarEntradas(sw10.macNormalizado(), List.of(new MacEntry(sw10.macNormalizado(), "d836-5f9a-49bd", 1, "Learned", "GE1/0/2")));
        engine6.registrarEntradas(sw13.macNormalizado(), List.of(new MacEntry(sw13.macNormalizado(), "d836-5f9a-49bd", 1, "Learned", "GE1/0/5")));
        engine6.registrarEntradas(sw14.macNormalizado(), List.of(new MacEntry(sw14.macNormalizado(), "d836-5f9a-49bd", 1, "Learned", "GE1/0/12")));
        engine6.registrarEntradas(sw12.macNormalizado(), List.of(new MacEntry(sw12.macNormalizado(), "d836-5f9a-49bd", 1, "Learned", "GE1/0/23")));

        String rel6 = RelatorioTopologia.gerarRelatorioTexto(engine6);
        String arvore6 = rel6.substring(rel6.indexOf("ÁRVORE DA TOPOLOGIA"));
        String esperadoCenario6 = """
                ÁRVORE DA TOPOLOGIA
                ====================================================================================================

                └─ S2328G-PA (10.0.0.10)
                   └─ GE1/0/2 <-> GE0/1 ─ S3028G-B (10.0.0.11)
                      ├─ GE0/10 <-> GE1/0/5 ─ S2352G-PB (10.0.0.13)
                      ├─ GE0/13 <-> GE1/0/12 ─ S2352G-A (10.0.0.14)
                      └─ GE0/23 <-> GE1/0/23 ─ S2328G-PB (10.0.0.12)
                """;
        assertTrue(arvore6.contains(esperadoCenario6));
    }

    @Test
    void testSerie3000ParserFormatosAlternativosEControlePaginacaoTelnet() {
        Serie3000Parser parser = new Serie3000Parser();

        // Simula paginação Telnet com backspaces (\b), NUL (\0), ANSI CSI, notação curta g0/2 e diferentes ordens de coluna
        String rawMisto = "\u001B[0m--More--\b\b\b\b\b\b\b\b        \b\b\b\b\b\b\b\b 1   0009.0f09.1e0a   DYNAMIC   g0/2\u0000\r\n"
                + "\b\b\b\b    \b\b\b\b 1   8085.4401.e384   gi0/2     dynamic\r\n"
                + "00:1a:3f:4a:97:fd   1   Learned   ge0/11   Y\r\n"
                + "1   30e1-f1ff-661f   1   g0/24   dynamic\r\n"
                + "1   d836.5f9a.4c3d   STATIC    cpu\r\n";

        List<MacEntry> entries = parser.parseMacAddressTable("d8365f9a4c3d", rawMisto);
        assertEquals(4, entries.size(), "Deve extrair as 4 entradas físicas e ignorar cpu");
        assertEquals("0009-0f09-1e0a", entries.get(0).mac());
        assertEquals("GE0/2", entries.get(0).port());
        assertEquals("8085-4401-e384", entries.get(1).mac());
        assertEquals("GE0/2", entries.get(1).port());
        assertEquals("001a-3f4a-97fd", entries.get(2).mac());
        assertEquals("GE0/11", entries.get(2).port());
        assertEquals("30e1-f1ff-661f", entries.get(3).mac());
        assertEquals("GE0/24", entries.get(3).port());

        assertTrue(ColetorEmLote.ehComandoInvalido("%% Too many parameters"));
        assertTrue(ColetorEmLote.ehComandoInvalido("Error input in the position market by '^'."));
        assertTrue(ColetorEmLote.ehComandoInvalido("Command not found"));
    }

    @Test
    void testCenarioRealRede10_1_40ComSwitchS30SemTabelaMacEDoisUplinksParaSegmentoGateway() {
        TopologyEngine engine = new TopologyEngine();

        SwitchIdentity sw203 = new SwitchIdentity("S2328G-B", "210150A2MK923BQ000F4", "30E1-F1FF-661F", "LS-2328G-B", "10.1.40.203");
        SwitchIdentity sw204 = new SwitchIdentity("S2328G-A", "210150A2MP723CK101P3", "8085-4401-E384", "LS-2328G-A", "10.1.40.204");
        SwitchIdentity sw129 = new SwitchIdentity("S3028G-PB Max", null, "D836-5F9A-4C3D", "S3028G-PB Max", "10.1.40.129");
        SwitchIdentity sw130 = new SwitchIdentity("S3028G-PB Max", "H3TK3900068UN", "D836-5F42-5C7B", "S3028G-PB Max", "10.1.40.130");
        SwitchIdentity sw240 = new SwitchIdentity("S3052G-B", "356K39000773P", "D836-5F9A-54C1", "S3054G-B", "10.1.40.240");

        List.of(sw203, sw204, sw129, sw130, sw240).forEach(engine::registrarSwitch);

        // .203 vê .240 na GE1/0/16, .129 sozinho na GE1/0/22, .130 na BAGG2 (GE1/0/19) e Fortinet + .204 na GE1/0/23
        engine.registrarEntradas(sw203.macNormalizado(), List.of(
                new MacEntry(sw203.macNormalizado(), "d836-5f9a-54c1", 1, "Learned", "GE1/0/16"),
                new MacEntry(sw203.macNormalizado(), "d836-5f9a-4c3d", 1, "Learned", "GE1/0/22"),
                new MacEntry(sw203.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE1/0/23"),
                new MacEntry(sw203.macNormalizado(), "8085-4401-e384", 1, "Learned", "GE1/0/23"),
                new MacEntry(sw203.macNormalizado(), "5810-8c68-daf0", 1, "Learned", "GE1/0/23"),
                new MacEntry(sw203.macNormalizado(), "d836-5f42-5c7b", 1, "Learned", "BAGG2 (GE1/0/19)")
        ));

        // .204 vê Fortinet + .203 + switches atrás de .203 na GE1/0/20, e OLT/hosts nas portas locais
        engine.registrarEntradas(sw204.macNormalizado(), List.of(
                new MacEntry(sw204.macNormalizado(), "0009-0f09-1e0a", 1, "Learned", "GE1/0/20"),
                new MacEntry(sw204.macNormalizado(), "30e1-f1ff-661f", 1, "Learned", "GE1/0/20"),
                new MacEntry(sw204.macNormalizado(), "d836-5f9a-4c3d", 1, "Learned", "GE1/0/20"),
                new MacEntry(sw204.macNormalizado(), "d836-5f42-5c7b", 1, "Learned", "GE1/0/20"),
                new MacEntry(sw204.macNormalizado(), "d836-5f9a-54c1", 1, "Learned", "GE1/0/20"),
                new MacEntry(sw204.macNormalizado(), "5810-8c68-daf0", 1, "Learned", "GE1/0/21")
        ));

        // .129 veio com 0 entradas na tabela MAC L2, mas possui rota padrão e ARP do Fortinet na porta GE0/2
        engine.registrarEntradas(sw129.macNormalizado(), List.of());

        // .130 e .240 são switches de ponta ligados ao .203
        engine.registrarEntradas(sw130.macNormalizado(), List.of(
                new MacEntry(sw130.macNormalizado(), "0009-0f09-1e0a", 1, "DYNAMIC", "GE1/0/2"),
                new MacEntry(sw130.macNormalizado(), "30e1-f1ff-661f", 1, "DYNAMIC", "GE1/0/2"),
                new MacEntry(sw130.macNormalizado(), "8085-4401-e384", 1, "DYNAMIC", "GE1/0/2"),
                new MacEntry(sw130.macNormalizado(), "d836-5f9a-4c3d", 1, "DYNAMIC", "GE1/0/2"),
                new MacEntry(sw130.macNormalizado(), "d836-5f9a-54c1", 1, "DYNAMIC", "GE1/0/2")
        ));
        engine.registrarEntradas(sw240.macNormalizado(), List.of(
                new MacEntry(sw240.macNormalizado(), "0009-0f09-1e0a", 1, "DYNAMIC", "GE0/11"),
                new MacEntry(sw240.macNormalizado(), "30e1-f1ff-661f", 1, "DYNAMIC", "GE0/11"),
                new MacEntry(sw240.macNormalizado(), "8085-4401-e384", 1, "DYNAMIC", "GE0/11"),
                new MacEntry(sw240.macNormalizado(), "d836-5f9a-4c3d", 1, "DYNAMIC", "GE0/11"),
                new MacEntry(sw240.macNormalizado(), "d836-5f42-5c7b", 1, "DYNAMIC", "GE0/11")
        ));

        for (SwitchIdentity sw : List.of(sw203, sw204, sw129, sw130, sw240)) {
            engine.registrarRotaPadrao(sw.macNormalizado(), "10.1.40.1");
        }
        engine.registrarEntradasArp(sw203.macNormalizado(), List.of(new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1/0/23")));
        engine.registrarEntradasArp(sw204.macNormalizado(), List.of(new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1/0/20")));
        engine.registrarEntradasArp(sw129.macNormalizado(), List.of(new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE0/2")));
        engine.registrarEntradasArp(sw130.macNormalizado(), List.of(new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE1/0/2")));
        engine.registrarEntradasArp(sw240.macNormalizado(), List.of(new ArpEntry("10.1.40.1", "0009-0f09-1e0a", "GE0/11")));

        String relatorio = RelatorioTopologia.gerarRelatorioTexto(engine);

        // .129 não pode aparecer como falso 2º Gateway nem como falso loop; sua porta GE0/2 deve resolver como uplink para .203 GE1/0/22
        assertFalse(relatorio.contains("enlace redundante/loop"), "Não deve gerar falso loop para o switch .129");
        assertFalse(relatorio.contains("Qtd MACs"), "Relatório deve conter apenas Enlaces Diretos e Árvore da Topologia");
        assertTrue(relatorio.contains("S2328G-B (10.1.40.203) [GE1/0/22]                      <-> [GE0/2]    S3028G-PB Max (10.1.40.129)"));

        // Tanto .203 (GE1/0/23) quanto .204 (GE1/0/20) sobem para o segmento do Fortinet (sem duplicar GE1/0/23)
        String secaoArvore = relatorio.substring(relatorio.indexOf("ÁRVORE DA TOPOLOGIA"));
        String arvoreEsperada = """
                Roteador/Gateway 10.1.40.1 [0009-0f09-1e0a (Fortinet)]
                ├─ <-> GE1/0/23 ─ S2328G-B (10.1.40.203)
                │  ├─ GE1/0/16 <-> GE0/11 ─ S3052G-B (10.1.40.240)
                │  ├─ GE1/0/22 <-> GE0/2 ─ S3028G-PB Max (10.1.40.129)
                │  └─ BAGG2 (GE1/0/19) <-> GE1/0/2 ─ S3028G-PB Max (10.1.40.130)
                └─ <-> GE1/0/20 ─ S2328G-A (10.1.40.204)
                """;
        assertTrue(secaoArvore.contains(arvoreEsperada), "Árvore esperada não encontrada:\n" + secaoArvore);
    }
}

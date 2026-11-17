package tpDiscover.intelbras;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tpDiscover.intelbras.coleta.AlvosLoader;
import tpDiscover.intelbras.correlacao.ExportadorPlanejadorRede;
import tpDiscover.intelbras.correlacao.RelatorioTopologia;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.descoberta.DiscoveredProduct;
import tpDiscover.intelbras.descoberta.DiscoveredProduct.FamiliaProduto;
import tpDiscover.intelbras.descoberta.NetworkScanTarget;
import tpDiscover.intelbras.descoberta.ProductFingerprinter;
import tpDiscover.intelbras.modelos.GatewayInfo;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.LldpNeighbor.CategoriaInteresse;
import tpDiscover.intelbras.modelos.LoopDiagnostic;
import tpDiscover.intelbras.modelos.LoopDiagnostic.Severidade;
import tpDiscover.intelbras.modelos.LoopDiagnostic.TipoLoop;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.StpPortState;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.ApParser;
import tpDiscover.intelbras.parser.ComwareParser;
import tpDiscover.intelbras.parser.LldpInterestFilter;
import tpDiscover.intelbras.parser.Serie3000Parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TpDiscoverEvolucaoTest {

    @Test
    void deveParsearLldpStpELoopbackComwareReais() {
        ComwareParser parser = new ComwareParser();
        String lldpVerbose = """
                LLDP neighbor-information of port 1[GigabitEthernet1/0/1]:
                  Neighbor index   : 1
                  Chassis type     : MAC address
                  Chassis ID       : d836-5f11-2233
                  Port ID type     : Interface name
                  Port ID          : GigabitEthernet1/0/28
                  Port description : UPLINK-CORE
                  System name        : S3328G-B
                  System description : Intelbras S3328G-B Comware Software
                  System capabilities supported : Bridge, Router
                  System capabilities enabled   : Bridge, Router
                  Management address : 10.1.40.253

                LLDP neighbor-information of port 24[GigabitEthernet1/0/24]:
                  Neighbor index   : 1
                  Chassis type     : MAC address
                  Chassis ID       : 0009-0f09-1e0a
                  Port ID type     : Interface name
                  Port ID          : port1
                  System name        : FG-UPLINK
                  System description : FortiGate Firewall
                  System capabilities supported : Router
                  System capabilities enabled   : Router
                  Management address : 10.1.40.1
                """;

        List<LldpNeighbor> lldp = parser.parseLldpNeighborsVerbose("aabbccddeeff", lldpVerbose);
        assertEquals(2, lldp.size());
        assertEquals("GE1/0/1", lldp.get(0).localPort());
        assertEquals("d8365f112233", lldp.get(0).chassisMacNormalizado());
        assertEquals("GE1/0/28", lldp.get(0).remotePort());
        assertEquals("10.1.40.253", lldp.get(0).managementIp());
        assertEquals("GE1/0/24", lldp.get(1).localPort());
        assertEquals("10.1.40.1", lldp.get(1).managementIp());

        String stpBrief = """
                 MST ID   Port                        Role  State          Protection
                 0        GigabitEthernet1/0/1        ROOT  FORWARDING     NONE
                 0        GigabitEthernet1/0/2        ALTE  DISCARDING     NONE
                """;
        List<StpPortState> stp = parser.parseStpBrief("aabbccddeeff", stpBrief);
        assertEquals(2, stp.size());
        assertTrue(stp.get(0).estaEncaminhando());
        assertTrue(stp.get(1).estaBloqueada());

        String lb = """
                Loopback-detection is enabled.
                GigabitEthernet1/0/5: Loopback exists, shutdown!
                """;
        List<LoopDiagnostic> diags = parser.parseLoopbackDetection("aabbccddeeff", lb);
        assertEquals(1, diags.size());
        assertEquals(TipoLoop.LOOPBACK_HARDWARE, diags.get(0).tipo());
        assertEquals(Severidade.CRITICO, diags.get(0).severidade());
        assertEquals("GE1/0/5", diags.get(0).portaOrigem());
    }

    @Test
    void deveParsearS2050GAeSgMrL2PlusReais() {
        Serie3000Parser s30 = new Serie3000Parser();

        String s2050ShowSystem = """
                Product Name:"S2050G-A"
                System Name:"SW-LAB-2050"
                Hardware Version:"V1.0"
                """;
        String s2050MacTable = """
                100     00:09:0f:09:1e:0a   Dynamic   ge23
                1       bc:24:11:aa:bb:cc   Local     local vlan1.1
                1       d8:36:5f:99:88:77   Dynamic   ge30
                """;
        String macLocal = Serie3000Parser.extrairMacLocalVlan(s2050MacTable);
        assertEquals("BC24-11AA-BBCC", macLocal);

        SwitchIdentity id2050 = s30.parseDeviceVersion(
                "10.1.40.100", null, s2050ShowSystem + "\nSystem MAC address: " + macLocal);
        assertEquals("bc2411aabbcc", id2050.macNormalizado());
        assertEquals("S2050G-A", id2050.productId());

        List<MacEntry> macs2050 = s30.parseMacAddressTable(id2050.macNormalizado(), s2050MacTable);
        assertEquals(2, macs2050.size());
        assertEquals("GE23", macs2050.get(0).port());
        assertEquals("GE30", macs2050.get(1).port());

        String s2050Arp = """
                10.1.40.1       00:09:0f:09:1e:0a   1190 s   Dynamic
                """;
        assertEquals(1, s30.parseArpTable(s2050Arp).size());

        String s2050Lldp = """
                Local Port: ge30
                Chassis ID: d8:36:5f:44:55:66
                Port ID: ge1
                System Name: SW02-NOID
                Management Address: 10.1.40.137
                """;
        List<LldpNeighbor> lldp2050 = s30.parseLldpS2050Lite(id2050.macNormalizado(), s2050Lldp);
        assertEquals(1, lldp2050.size());
        assertEquals("GE30", lldp2050.get(0).localPort());
        assertEquals("10.1.40.137", lldp2050.get(0).managementIp());

        String s2050RealHeader = """
                >>> Interface ge30 neighbor 1
                Chassis ID          : d8:36:5f:44:55:66
                Port ID             : ge1
                System Name         : SW02-NOID
                Management Address  : 10.1.40.137
                MAC Bridge          : Enable
                """;
        List<LldpNeighbor> lldp2050Real = s30.parseLldpS2050Lite(id2050.macNormalizado(), s2050RealHeader);
        assertEquals(1, lldp2050Real.size());
        assertEquals("GE30", lldp2050Real.get(0).localPort());
        assertTrue(lldp2050Real.get(0).possuiCapability("Bridge"));

        String sgVersion = """
                Device: SG 1002 MR L2+
                Serial num:G34H3100152TQ
                MAC: bc:24:11:10:20:30
                """;
        SwitchIdentity idSg = s30.parseDeviceVersion("10.1.40.91", null, sgVersion);
        assertEquals("G34H3100152TQ", idSg.serialNumber());

        String sgArp = "IP 10.1.40.1 0 00:09:0f:09:1e:0a ARPA v1(g0/7)";
        var arpsSg = s30.parseArpTable(sgArp);
        assertEquals(1, arpsSg.size());
        assertEquals("GE0/7", arpsSg.get(0).port());

        String sgStp = """
                Spanning Tree Enabled
                g0/7    Root     FWD     128.7
                g0/8    Altn     BLK     128.8
                """;
        List<StpPortState> stpSg = s30.parseSpanningTree(idSg.macNormalizado(), sgStp);
        assertEquals(2, stpSg.size());
        assertTrue(stpSg.get(0).estaEncaminhando());
        assertTrue(stpSg.get(1).estaBloqueada());
    }

    @Test
    void deveFiltrarVizinhosLldpApenasParaDispositivosDeInteresse() {
        SwitchIdentity swCore = new SwitchIdentity("SW-CORE", "SN1", "d8:36:5f:00:01:00", "S2328G-B", "10.1.40.203");
        GatewayInfo gw = new GatewayInfo("10.1.40.1", "00:09:0f:09:1e:0a");

        LldpNeighbor switchNaoColetadoBridgeRouter = new LldpNeighbor(
                swCore.macNormalizado(), "GE1/0/2", "aa:bb:cc:11:22:33", "aabbcc112233",
                "GE1/0/1", "", "S2310G-A_BORDA_LAB_REDES", "Intelbras S2310G-A Comware",
                "10.1.40.253", Set.of("Bridge", "Router"));
        assertNotEquals(
                CategoriaInteresse.ROTEADOR_UPLINK,
                LldpInterestFilter.classificar(switchNaoColetadoBridgeRouter, List.of(swCore), Optional.of(gw)),
                "Switch L3 Comware anunciando Bridge+Router nunca deve ser rotulado como Roteador de Uplink"
        );

        LldpNeighbor roteadorUplink = new LldpNeighbor(
                swCore.macNormalizado(), "GE1/0/24", "00:09:0f:09:1e:0a", "00090f091e0a",
                "wan1", "", "FortiGate-Uplink", "Router", "10.1.40.1", Set.of("Router"));
        assertEquals(
                CategoriaInteresse.ROTEADOR_UPLINK,
                LldpInterestFilter.classificar(roteadorUplink, List.of(swCore), Optional.of(gw))
        );

        LldpNeighbor accessPoint = new LldpNeighbor(
                swCore.macNormalizado(), "GE1/0/10", "bc:24:11:22:33:44", "bc2411223344",
                "eth0", "", "AP-RECEPCAO", "Intelbras AP 1350 AC-S", "10.1.40.50", Set.of("Bridge", "WLAN"));
        assertEquals(
                CategoriaInteresse.ACCESS_POINT,
                LldpInterestFilter.classificar(accessPoint, List.of(swCore), Optional.of(gw))
        );

        LldpNeighbor radioIntelbras = new LldpNeighbor(
                swCore.macNormalizado(), "GE1/0/12", "e0:05:c5:aa:bb:cc", "e005c5aabbcc",
                "eth0", "", "PTP-TORRE-WOM5A", "Intelbras WOM 5A Mimo Radio", "10.1.40.60", Set.of("Bridge"));
        assertEquals(
                CategoriaInteresse.RADIO_INTELBRAS,
                LldpInterestFilter.classificar(radioIntelbras, List.of(swCore), Optional.of(gw))
        );

        LldpNeighbor telefoneIp = new LldpNeighbor(
                swCore.macNormalizado(), "GE1/0/15", "11:22:33:44:55:66", "112233445566",
                "LAN", "", "Yealink-SIP-T21", "IP Phone", "10.1.40.180", Set.of("Telephone"));
        assertEquals(
                CategoriaInteresse.OUTRO_IGNORAR,
                LldpInterestFilter.classificar(telefoneIp, List.of(swCore), Optional.of(gw))
        );

        LldpNeighbor notebook = new LldpNeighbor(
                swCore.macNormalizado(), "GE1/0/16", "40:b0:34:11:22:33", "40b034112233",
                "eth0", "", "PC-FINANCEIRO", "Windows Workstation", "", Set.of("Station Only"));
        assertEquals(
                CategoriaInteresse.OUTRO_IGNORAR,
                LldpInterestFilter.classificar(notebook, List.of(swCore), Optional.of(gw))
        );
    }

    @Test
    void deveDetectarLoopsSemGerarFalsoPositivoEmBaggEMulticast(@TempDir Path tempDir) {
        TopologyEngine engine = new TopologyEngine();

        SwitchIdentity swA = new SwitchIdentity("SW-CORE", "SNA", "d8:36:5f:00:01:00", "S2328G-B", "10.1.40.203");
        SwitchIdentity swB = new SwitchIdentity("SW-ACC1", "SNB", "d8:36:5f:00:02:00", "S3028G-PB", "10.1.40.130");
        SwitchIdentity swC = new SwitchIdentity("SW-ACC2", "SNC", "bc:24:11:00:03:00", "S2050G-A", "10.1.40.100");
        engine.registrarSwitch(swA);
        engine.registrarSwitch(swB);
        engine.registrarSwitch(swC);

        // Link agregado BAGG1 em A <-> B: NÃO deve gerar MAC flapping nem anel!
        engine.registrarAggregacoes(swA.macNormalizado(), Map.of("BAGG1", List.of("GE1/0/25", "GE1/0/26")));
        engine.registrarEntradas(swA.macNormalizado(), List.of(
                new MacEntry(swA.macNormalizado(), "d8:36:5f:00:02:00", 1, "Learned", "GE1/0/25"),
                new MacEntry(swA.macNormalizado(), "d8:36:5f:00:02:00", 1, "Learned", "GE1/0/26"),
                // Multicast em várias portas deve ser ignorado
                new MacEntry(swA.macNormalizado(), "01:80:c2:00:00:00", 1, "Static", "GE1/0/3"),
                new MacEntry(swA.macNormalizado(), "01:80:c2:00:00:00", 1, "Static", "GE1/0/4"),
                // MAC próprio reaparecendo em porta física externa = SELF_MAC_PORTA (CRÍTICO)
                new MacEntry(swA.macNormalizado(), "d8:36:5f:00:01:00", 1, "Learned", "GE1/0/9"),
                // Flapping real fora de BAGG entre GE1/0/3 e GE1/0/8 = MAC_FLAPPING (CRÍTICO)
                new MacEntry(swA.macNormalizado(), "00:11:22:33:44:55", 10, "Learned", "GE1/0/3"),
                new MacEntry(swA.macNormalizado(), "00:11:22:33:44:55", 10, "Learned", "GE1/0/8")
        ));

        // LLDP formando triângulo A-B-C-A + AP externo + estação ignorada
        engine.registrarLldp(swA.macNormalizado(), List.of(
                new LldpNeighbor(swA.macNormalizado(), "GE1/0/25", swB.macAddress(), swB.macNormalizado(),
                        "GE1/0/1", "", swB.deviceName(), "Switch", swB.ip(), Set.of("Bridge")),
                new LldpNeighbor(swA.macNormalizado(), "GE1/0/26", swB.macAddress(), swB.macNormalizado(),
                        "GE1/0/2", "", swB.deviceName(), "Switch", swB.ip(), Set.of("Bridge")),
                new LldpNeighbor(swA.macNormalizado(), "GE1/0/24", swC.macAddress(), swC.macNormalizado(),
                        "GE30", "", swC.deviceName(), "Switch", swC.ip(), Set.of("Bridge")),
                new LldpNeighbor(swA.macNormalizado(), "GE1/0/11", "bc:24:11:99:88:77", "bc2411998877",
                        "eth0", "", "AP-PATIO", "Intelbras AP 3000 AX", "10.1.40.77", Set.of("Bridge", "WLAN")),
                new LldpNeighbor(swA.macNormalizado(), "GE1/0/12", "44:55:66:77:88:99", "445566778899",
                        "eth0", "", "DESKTOP-RH", "Station", "", Set.of("Station Only"))
        ));
        engine.registrarLldp(swB.macNormalizado(), List.of(
                new LldpNeighbor(swB.macNormalizado(), "GE1/0/1", swA.macAddress(), swA.macNormalizado(),
                        "GE1/0/25", "", swA.deviceName(), "Switch", swA.ip(), Set.of("Bridge")),
                new LldpNeighbor(swB.macNormalizado(), "GE1/0/2", swA.macAddress(), swA.macNormalizado(),
                        "GE1/0/26", "", swA.deviceName(), "Switch", swA.ip(), Set.of("Bridge")),
                new LldpNeighbor(swB.macNormalizado(), "GE1/0/10", swC.macAddress(), swC.macNormalizado(),
                        "GE29", "", swC.deviceName(), "Switch", swC.ip(), Set.of("Bridge"))
        ));
        engine.registrarAggregacoes(swB.macNormalizado(), Map.of("BAGG1", List.of("GE1/0/1", "GE1/0/2")));

        // Anel fechado com STP desabilitado em C -> ANEL_SEM_STP crítico
        engine.registrarStp(swC.macNormalizado(), List.of(new StpPortState(swC.macNormalizado(), "", "", "", false, "")));

        List<LoopDiagnostic> diags = engine.diagnosticarLoops();
        assertTrue(diags.stream().anyMatch(d -> d.tipo() == TipoLoop.SELF_MAC_PORTA && d.ehLoopAtivo()));
        assertTrue(diags.stream().anyMatch(d -> d.tipo() == TipoLoop.MAC_FLAPPING && d.ehLoopAtivo()));
        assertTrue(diags.stream().anyMatch(d -> d.tipo() == TipoLoop.ANEL_SEM_STP && d.ehLoopAtivo()));

        String relatorio = RelatorioTopologia.gerarRelatorioTexto(engine);
        assertTrue(relatorio.contains("AP-PATIO"));
        assertFalse(relatorio.contains("DESKTOP-RH"), "Estação final não deve poluir topologia LLDP");
        assertTrue(relatorio.contains("DIAGNÓSTICO DE LOOPS"));

        Path saida = ExportadorPlanejadorRede.exportarPdf(engine, tempDir);
        assertNotNull(saida);
        assertTrue(Files.exists(saida));
    }

    @Test
    void deveReconhecerRedundanciaBloqueadaPorStpComoAviso() {
        TopologyEngine engine = new TopologyEngine();
        SwitchIdentity sw1 = new SwitchIdentity("SW1", "S1", "d8:36:5f:00:01:00", "S2328G-B", "10.1.40.11");
        SwitchIdentity sw2 = new SwitchIdentity("SW2", "S2", "d8:36:5f:00:02:00", "S2328G-B", "10.1.40.12");
        SwitchIdentity sw3 = new SwitchIdentity("SW3", "S3", "d8:36:5f:00:03:00", "S2328G-B", "10.1.40.13");
        engine.registrarSwitch(sw1);
        engine.registrarSwitch(sw2);
        engine.registrarSwitch(sw3);

        engine.registrarLldp(sw1.macNormalizado(), List.of(
                new LldpNeighbor(sw1.macNormalizado(), "GE1/0/1", sw2.macAddress(), sw2.macNormalizado(),
                        "GE1/0/1", "", "SW2", "Switch", sw2.ip(), Set.of("Bridge")),
                new LldpNeighbor(sw1.macNormalizado(), "GE1/0/2", sw3.macAddress(), sw3.macNormalizado(),
                        "GE1/0/1", "", "SW3", "Switch", sw3.ip(), Set.of("Bridge"))
        ));
        engine.registrarLldp(sw2.macNormalizado(), List.of(
                new LldpNeighbor(sw2.macNormalizado(), "GE1/0/2", sw3.macAddress(), sw3.macNormalizado(),
                        "GE1/0/2", "", "SW3", "Switch", sw3.ip(), Set.of("Bridge"))
        ));
        engine.registrarStp(sw3.macNormalizado(), List.of(
                new StpPortState(sw3.macNormalizado(), "GE1/0/2", "ALTE", "DISCARDING", true, "")
        ));

        List<LoopDiagnostic> diags = engine.diagnosticarLoops();
        assertTrue(diags.stream().anyMatch(d -> d.tipo() == TipoLoop.REDUNDANCIA_STP && d.severidade() == Severidade.AVISO));
        assertTrue(diags.stream().noneMatch(LoopDiagnostic::ehLoopAtivo));
    }

    @Test
    void deveClassificarFingerprintsERejeitarServidorUbuntu() {
        DiscoveredProduct ubuntu = ProductFingerprinter.classificarSinais(
                "10.1.40.243", List.of(22), "SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.18", "", "", "", "", "");
        assertEquals(FamiliaProduto.DESCONHECIDO_IGNORAR, ubuntu.familia());
        assertFalse(ubuntu.ehProdutoRedeInteresse());
        assertFalse(ubuntu.suportaColetaCli());

        DiscoveredProduct comware = ProductFingerprinter.classificarSinais(
                "10.1.40.203", List.of(22, 23, 80), "SSH-2.0-OS-7.1.045",
                "HTTP/1.1 200 OK\r\nServer: HTTPD\r\n\r\n<title>Web managerment Home</title>",
                "", "", "", "");
        assertEquals(FamiliaProduto.SWITCH_COMWARE, comware.familia());
        assertTrue(comware.suportaColetaCli());
        assertEquals("SSH", comware.protocoloPreferido());

        DiscoveredProduct s2050 = ProductFingerprinter.classificarSinais(
                "10.1.40.100", List.of(23, 80), "",
                "HTTP/1.0 200 OK\r\nCache-Control: no-store\r\n\r\n<title>INTELBRAS | Login</title>",
                "", "", "", "");
        assertEquals(FamiliaProduto.SWITCH_S2050_LITE, s2050.familia());
        assertEquals("TELNET", s2050.protocoloPreferido());

        DiscoveredProduct sgMrL2 = ProductFingerprinter.classificarSinais(
                "10.1.40.91", List.of(23, 80), "",
                "HTTP/1.0 200 OK\r\nServer: GoAhead-Webs\r\n\r\n<title>Switch Intelbras</title>",
                "", "", "", "");
        assertEquals(FamiliaProduto.SWITCH_SG_MR_L2PLUS, sgMrL2.familia());

        DiscoveredProduct sgMr = ProductFingerprinter.classificarSinais(
                "10.1.40.120", List.of(22, 80), "SSH-1.99-IPSSH-6.6.0",
                "HTTP/1.0 200 OK\r\nServer: Web Switch\r\n\r\nIntelbras",
                "", "SG 2404 MR", "SG2404", "");
        assertEquals(FamiliaProduto.SWITCH_SG_MR, sgMr.familia());
        assertEquals("SG 2404 MR", sgMr.modeloInferido());

        DiscoveredProduct router = ProductFingerprinter.classificarSinais(
                "10.1.31.19", List.of(80), "",
                "HTTP/1.1 200 OK\r\nServer: HTTPD_gw 1.0\r\n",
                "", "", "", "");
        assertEquals(FamiliaProduto.ROTEADOR_GATEWAY, router.familia());
        assertFalse(router.suportaColetaCli());
    }

    @Test
    void deveExpandirCidrFaixasEDiretivasAlvosLoader(@TempDir Path tempDir) throws Exception {
        NetworkScanTarget cidr = NetworkScanTarget.parsearUnico("10.1.40.0/24");
        assertEquals(254, cidr.ips().size());
        assertEquals("10.1.40.1", cidr.ips().get(0));
        assertEquals("10.1.40.254", cidr.ips().get(253));

        NetworkScanTarget faixa = NetworkScanTarget.parsearUnico("10.1.40.80-85");
        assertEquals(List.of("10.1.40.80", "10.1.40.81", "10.1.40.82", "10.1.40.83", "10.1.40.84", "10.1.40.85"), faixa.ips());

        assertThrows(IllegalArgumentException.class, () -> NetworkScanTarget.parsearUnico("10.1.0.0/16"));

        Path cfg = tempDir.resolve("switches_scan.txt");
        Files.writeString(cfg, """
                # gateway=10.1.40.1
                # scan=10.1.40.0/24
                # credencial=admin;Admin@1234
                10.1.31.19:65019;admin;Admin@1234
                """);
        AlvosLoader.ConfiguracaoAlvos parsed = AlvosLoader.carregarConfiguracao(cfg);
        assertEquals(List.of("10.1.40.0/24"), parsed.scanSpecs());
        assertEquals("admin", parsed.usuarioPadrao());
        assertEquals("Admin@1234", parsed.senhaPadrao());
        assertEquals(1, parsed.alvos().size());
        assertEquals(65019, parsed.alvos().get(0).portaCustomizada());

        App.CliOptions cli = App.parseArgs(new String[]{
                "--scan", "10.1.40.0/24", "-u", "admin", "-p", "Admin@1234", "--concorrencia", "12", "--sem-expansao-lldp"
        });
        assertEquals(List.of("10.1.40.0/24"), cli.scanSpecs());
        assertEquals("admin", cli.usuario());
        assertEquals(12, cli.concorrencia());
        assertTrue(cli.semExpansaoLldp());

        List<LldpNeighbor> apLldp = ApParser.parseLldpNeighbors("aabbccddeeff", """
                Interface:    eth0.4071, via: LLDP, RID: 1
                  Chassis:
                    ChassisID:    mac d8:36:5f:00:01:00
                    SysName:      SW-CORE
                    MgmtIP:       10.1.40.203
                    Capability:   Bridge, on
                    Capability:   Router, off
                  Port:
                    PortID:       ifname GigabitEthernet1/0/10
                """);
        assertEquals(1, apLldp.size());
        assertEquals("ETH0", apLldp.get(0).localPort());
        assertEquals("SW-CORE", apLldp.get(0).systemName());
        assertTrue(apLldp.get(0).possuiCapability("Bridge"));
        assertFalse(apLldp.get(0).possuiCapability("Router"));
    }

    @Test
    void deveParsearSintaxeSSeriesELegadoSgMrEStepBrief() {
        Serie3000Parser s30 = new Serie3000Parser();

        // 1) S-Series (SG 1002/1602/2404 PoE): "Interfaces | State | Result" em show loopback-detection
        String lbSSeriesNormal = """
                Loopback detection configuration
                Loopback detection : enabled
                Interfaces   State     Result
                gi0/1        enabled   NORMAL
                gi0/2        enabled   NORMAL
                """;
        assertTrue(s30.parseLoopbackDetection("aabbccddeeff", lbSSeriesNormal).isEmpty());

        String lbSSeriesLoop = """
                Loopback detection configuration
                Loopback detection : enabled
                Interfaces   State     Result
                gi0/1        enabled   NORMAL
                gi0/2        enabled   LOOP
                gi0/3        enabled   BLOCKED
                """;
        List<LoopDiagnostic> diagsSSeries = s30.parseLoopbackDetection("aabbccddeeff", lbSSeriesLoop);
        assertEquals(2, diagsSSeries.size());
        assertEquals("GE0/2", diagsSSeries.get(0).portaOrigem());
        assertEquals("GE0/3", diagsSSeries.get(1).portaOrigem());

        // 2) SG MR L2+: "Control = BLOCK" com Status = "no-loop" NAO deve gerar falso positivo
        String lbSgMrL2 = """
                Interface state information
                Port     Status  dest MacAddress Control  VLAN
                -------- ------- --------------- -------- -------------------------------
                    g0/1 no-loop 0180.c200.000a  BLOCK
                    g0/2 loop    0180.c200.000a  BLOCK
                """;
        List<LoopDiagnostic> diagsSgMrL2 = s30.parseLoopbackDetection("180d2cec4b59", lbSgMrL2);
        assertEquals(1, diagsSgMrL2.size());
        assertEquals("GE0/2", diagsSgMrL2.get(0).portaOrigem());

        // 3) SG 1002 MR (2013): show system-info + show ip address + show bridge address all + show interface link-aggregation
        String sg2013Identity = """
                System Description   - Switch Gerenciavel 8 portas GE + 2 Mini-Gbic
                System Name          - SG 1002 MR
                Hardware Version     - SG 1002 MR 1.0
                MAC address:        00:1a:3f:c1:1f:7c
                IP address:         10.1.40.111
                Gateway:            10.1.40.1
                """;
        SwitchIdentity id2013 = s30.parseDeviceVersion("10.1.40.111", null, sg2013Identity);
        assertEquals("001a3fc11f7c", id2013.macNormalizado());
        assertEquals("SG 1002 MR", id2013.productId());
        assertEquals("10.1.40.1", s30.parseDefaultGatewayIp(sg2013Identity).orElse(null));

        String sg2013Macs = """
                MAC                vlan-id port type    aging
                ---                ------- ---- ----    -----
                18:0d:2c:08:d5:10  1       1    dynamic aging
                30:e1:f1:ff:66:44  1       9    static  --
                """;
        List<MacEntry> macs2013 = s30.parseMacAddressTable(id2013.macNormalizado(), sg2013Macs);
        assertEquals(2, macs2013.size());
        assertEquals("GE1", macs2013.get(0).port());
        assertEquals("GE9", macs2013.get(1).port());

        String sg2013Lag = """
                Group     Members                  Descript
                -----     -------                  --------
                1         1-2,5                    UPLINK
                """;
        Map<String, List<String>> baggs2013 = s30.parseLinkAggregation(sg2013Lag);
        assertEquals(List.of("GE1", "GE2", "GE5"), baggs2013.get("BAGG1"));

        String sg2013StpDisabled = """
                Status: Disable
                port state   pri  expath inpath  edge    ptop          mode    role      status
                1    Disable 128  auto   auto    Disable Auto          Invalid Invalid   Invalid
                """;
        List<StpPortState> stpDis = s30.parseSpanningTree(id2013.macNormalizado(), sg2013StpDisabled);
        assertEquals(1, stpDis.size());
        assertFalse(stpDis.get(0).stpHabilitado());

        String sg2013StpEnabled = """
                Status: Enable
                port state   pri  expath inpath  edge    ptop          mode    role      status
                1    Enable  128  auto   auto    Disable Auto          RSTP    Alte      Discarding
                """;
        List<StpPortState> stpEn = s30.parseSpanningTree(id2013.macNormalizado(), sg2013StpEnabled);
        assertEquals(1, stpEn.size());
        assertEquals("GE1", stpEn.get(0).port());
        assertTrue(stpEn.get(0).estaBloqueada());

        // 4) SG MR L2+: show aggregator-group summary
        String sgL2Agg = """
                Group Port-aggregator  Ports
                -----+-----------+-------------------------------------------------------------
                1     Po1(SU)          g0/1(U) g0/2(U)
                """;
        assertEquals(List.of("GE0/1", "GE0/2"), s30.parseLinkAggregation(sgL2Agg).get("BAGG1"));
    }
}

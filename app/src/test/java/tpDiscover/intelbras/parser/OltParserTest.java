package tpDiscover.intelbras.parser;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.StpPortState;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OltParserTest {

    private final OltParser parser = new OltParser();

    @Test
    void deveParsearG16ShowVersionSystemMacArpAllLldpInterfaceEStpBrief() {
        String infoCombinada = """
                Broadband Network Platform Software
                MAC address          : 00:0a:5a:a1:0b:da
                Product serial no.   : srm
                System name          : G116
                System object id     : 1.3.6.1.4.1.13464.1.10.7.1
                Product name         : Switch CASSETTE OLT GPON
                """;
        assertTrue(parser.ehDispositivoOlt(infoCombinada));
        SwitchIdentity id = parser.parseDeviceInfo("10.1.40.134", "OLT", infoCombinada);
        assertEquals("G116", id.deviceName());
        assertEquals("000A-5AA1-0BDA", id.macAddress());
        assertEquals("Switch CASSETTE OLT GPON", id.productId());

        List<MacEntry> macs = parser.parseMacAddressTable(id.macNormalizado(), """
                00:09:0f:09:1e:0a     1000     e1/1     dynamic
                00:0a:5a:a1:0b:da     1000     cpu      static
                """);
        assertEquals(1, macs.size());
        assertEquals("0009-0f09-1e0a", macs.get(0).mac());
        assertEquals("E1/1", macs.get(0).port());
        assertEquals(1000, macs.get(0).vlanId());

        List<ArpEntry> arps = parser.parseArpTable("""
                10.1.40.1  00:09:0f:09:1e:0a  1000  e1/1  0  dynamic  19m58s  valid
                """);
        assertEquals(1, arps.size());
        assertEquals("10.1.40.1", arps.get(0).ip());
        assertEquals("0009-0f09-1e0a", arps.get(0).mac());
        assertEquals("E1/1", arps.get(0).port());

        List<LldpNeighbor> lldp = OltParser.parseLldpNeighbors(id.macNormalizado(), """
                Interface Ethernet 0/1:
                  Neighbor (1):
                    Chassis ID: 30:e1:f1:ff:66:1f
                    Port ID: port e0/1
                    Port Description: GigabitEthernet1/0/4
                    System Name: S2310G-A
                    Management Address: 10.1.40.253
                """);
        assertEquals(1, lldp.size());
        assertEquals("E0/1", lldp.get(0).localPort());
        assertEquals("30e1-f1ff-661f", lldp.get(0).chassisMacNormalizado());
        assertEquals("S2310G-A", lldp.get(0).systemName());
        assertEquals("10.1.40.253", lldp.get(0).managementIp());

        List<StpPortState> stp = parser.parseSpanningTree(id.macNormalizado(), """
                Root Bridge has priority 0, MAC address: 30e1.f1ff.661f
                e1/1 enabled 20000 128 RootPort Forwarding
                g0/1 enabled 20000 128 Disabled DOWN
                """);
        assertEquals(2, stp.size());
        assertEquals("E1/1", stp.get(0).port());
        assertEquals("ROOT", stp.get(0).role());
        assertEquals("FORWARDING", stp.get(0).state());
        assertEquals("30e1-f1ff-661f", stp.get(0).rootBridgeMac());
    }

    @Test
    void deveParsearOltsAn6000EAn6001ComMacsArpRotaDuplaEStpDesabilitado() {
        String showCard6000 = """
                ---------------------AN6000-2-(M:M)-------------------
                1  HSUA  --  Normal
                4  HU2A  --  Normal
                """;
        String showManageVlan = """
                Interface: vlanif1000
                Ethernet address: 48:f9:7c:f0:02:94
                Inet : 10.1.40.140
                """;
        SwitchIdentity id6000 = parser.parseDeviceInfoAn(
                "10.1.40.140", showCard6000, "hostname:Admin\n", showManageVlan, "Master\nUser>");
        assertEquals("OLT-AN6000-2", id6000.deviceName());
        assertEquals("OLT AN6000-2", id6000.productId());
        assertEquals("48F9-7CF0-0294", id6000.macAddress());

        String showCard6001 = "---------------------AN6001-G16---------------------\n1 GPON -- Normal\n19 HU2A -- Normal\n";
        SwitchIdentity id6001 = parser.parseDeviceInfoAn(
                "10.1.40.142", showCard6001, "hostname:OLT-CENTRO\n", "Ethernet address: 48:f9:7c:ea:06:4a\nInet : 10.1.40.142\n", "");
        assertEquals("OLT-CENTRO", id6001.deviceName());
        assertEquals("OLT AN6001-G16", id6001.productId());
        assertEquals(List.of(1, 19), parser.extrairSlotsAtivosAn(showCard6001));

        List<MacEntry> macs = parser.parseMacTableAn(id6000.macNormalizado(), """
                SLOT  PORT  ONU  MAC                VLAN
                4     5     --   00:1a:3f:a0:03:32  1000
                19    5     --   DC-9F-DB-EE-63-51  1000
                1     3     12   aa:bb:cc:dd:ee:ff  500  500  --
                """);
        assertEquals(3, macs.size());
        assertEquals("4/5", macs.get(0).port());
        assertEquals("001a-3fa0-0332", macs.get(0).mac());
        assertEquals("19/5", macs.get(1).port());
        assertEquals("dc9f-dbee-6351", macs.get(1).mac());
        assertEquals("1/3", macs.get(2).port());

        List<ArpEntry> arps = parser.parseArpTableAn("""
                10.1.40.1 MAC(00:09:0f:09:1e:0a),VLAN:1000
                log_port:4/5, dynamic, age:300
                """);
        assertEquals(1, arps.size());
        assertEquals("10.1.40.1", arps.get(0).ip());
        assertEquals("0009-0f09-1e0a", arps.get(0).mac());
        assertEquals("4/5", arps.get(0).port());

        String rotaDupla = """
                S* 0.0.0.0/0 [1/0] via 10.1.43.1, vlanif4093
                             [1/0] via 10.1.40.1, vlanif1000
                """;
        Optional<String> gw = parser.parseDefaultGatewayAn(rotaDupla, "10.1.40.140", arps);
        assertTrue(gw.isPresent());
        assertEquals("10.1.40.1", gw.get());

        assertTrue(OltParser.parseLldpNeighbors(id6000.macNormalizado(), "the lldp protocol is disable!").isEmpty());
        List<StpPortState> stpDesab = parser.parseSpanningTree(id6000.macNormalizado(), "Protocol Status :Disabled");
        assertEquals(1, stpDesab.size());
        assertFalse(stpDesab.get(0).stpHabilitado());
    }

    @Test
    void deveParsearOlt8820IInterfaceFirmwareBridgeMacERoute() {
        String banner = """
                Intelbras S.A.
                OLT 8820i
                intelbras-olt>
                """;
        assertTrue(parser.ehBannerOlt8820(banner));
        SwitchIdentity id = parser.parseDeviceInfo8820(
                "10.1.40.133",
                "OLT",
                "inband 10.1.40.133/24 1000 18:0d:2c:16:46:d1 block\n",
                "Firmware version: 2.112\n",
                banner
        );
        assertEquals("OLT-8820I", id.deviceName());
        assertEquals("OLT 8820I", id.productId());
        assertEquals("180D-2C16-46D1", id.macAddress());

        List<MacEntry> macs = parser.parseMacTable8820(id.macNormalizado(),
                "Loading [##   ] 10%\rLoading [#####] 100%\r\n"
                        + "bc:24:11:50:0d:c2  eth 1 vlan 1000\n"
                        + "dc:9f:db:11:22:33  xeth 2 vlan 100\n"
                        + "00:11:22:33:44:55  gpon 3 vlan 200 static\n");
        assertEquals(3, macs.size());
        assertEquals("ETH1", macs.get(0).port());
        assertEquals(1000, macs.get(0).vlanId());
        assertEquals("XETH2", macs.get(1).port());
        assertEquals("GPON3", macs.get(2).port());
        assertEquals("static", macs.get(2).state());

        Optional<String> gw = parser.parseDefaultGatewayIp("default 10.1.40.1\n");
        assertTrue(gw.isPresent());
        assertEquals("10.1.40.1", gw.get());
    }
}

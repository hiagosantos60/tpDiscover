package tpDiscover.intelbras.descoberta;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.descoberta.DiscoveredProduct.FamiliaProduto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import tpDiscover.intelbras.util.Compat;
import static org.junit.jupiter.api.Assertions.*;

class ProductFingerprinterTest {

    @Test
    void deveExtrairModeloApcESgMrL2PlusEIgnorarSysNameGenerico() {
        DiscoveredProduct radioApc = ProductFingerprinter.classificarSinais(
                "10.1.40.78",
                Compat.listOf(80, 443),
                "",
                "HTTP/1.1 200 OK\r\n<title>APC 5M-18</title>",
                "",
                "Intelbras APC 5M-18 Firmware v7.51",
                "PTP-Torre",
                ""
        );
        assertEquals(FamiliaProduto.ACCESS_POINT, radioApc.familia());
        assertEquals("APC 5M-18", radioApc.modeloInferido());

        DiscoveredProduct sgMrL2Plus = ProductFingerprinter.classificarSinais(
                "10.1.40.179",
                Compat.listOf(22, 23, 80),
                "",
                "HTTP/1.1 200 OK\r\nServer: GoAhead-Webs\r\n<title>SG 5204 MR L2+</title>",
                "",
                "Switch Gerenciavel L2+ SG 5204 MR L2+",
                "ID do Equipamento",
                ""
        );
        assertEquals(FamiliaProduto.SWITCH_SG_MR_L2PLUS, sgMrL2Plus.familia());
        assertEquals("SG 5204 MR L2+", sgMrL2Plus.modeloInferido());
        assertNotEquals("ID do Equipamento", sgMrL2Plus.modeloInferido());
        assertTrue(sgMrL2Plus.suportaColetaCli());
        assertEquals("SSH", sgMrL2Plus.protocoloPreferido());

        DiscoveredProduct sgMrSemModeloNoSysDescr = ProductFingerprinter.classificarSinais(
                "10.1.40.120",
                Compat.listOf(22, 23, 80),
                "SSH-1.99-IPSSH-6.6.0",
                "HTTP/1.1 200 OK\r\n<title>Web Switch</title>",
                "",
                "24-Port Gigabit Switch",
                "ID do Equipamento",
                ""
        );
        assertEquals(FamiliaProduto.SWITCH_SG_MR, sgMrSemModeloNoSysDescr.familia());
        assertEquals("", sgMrSemModeloNoSysDescr.modeloInferido());
        assertEquals("TELNET", sgMrSemModeloNoSysDescr.protocoloPreferido());

        DiscoveredProduct servidorUbuntu = ProductFingerprinter.classificarSinais(
                "10.1.40.243",
                Compat.listOf(22, 80),
                "SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.10",
                "HTTP/1.1 200 OK\r\nServer: nginx",
                "",
                "",
                "",
                ""
        );
        assertEquals(FamiliaProduto.DESCONHECIDO_IGNORAR, servidorUbuntu.familia());
        assertFalse(servidorUbuntu.ehProdutoRedeInteresse());
    }

    @Test
    void deveReconhecerRedirectHttpMultiHopParaLoginIntelbrasS2050Lite() {
        String httpAcumulado = String.join("\n",
                "HTTP/1.1 302 Found\r\nLocation: /index.html\r\n\r\n",
                "HTTP/1.1 302 Found\r\nLocation: /login.html\r\n\r\n",
                "HTTP/1.1 200 OK\r\n<html><head><title>INTELBRAS | Login</title></head></html>"
        );
        DiscoveredProduct prod = ProductFingerprinter.classificarSinais(
                "10.1.40.92",
                Compat.listOf(22, 23, 80),
                "",
                httpAcumulado,
                "",
                "",
                "",
                ""
        );
        assertEquals(FamiliaProduto.SWITCH_S2050_LITE, prod.familia());
        assertTrue(prod.suportaColetaCli());
        assertEquals("SSH", prod.protocoloPreferido());
    }

    @Test
    void deveCodificarGetRequestEDecodificarSnmpV2cGetResponse() {
        byte[] req = ProductFingerprinter.montarSnmpV2GetRequest(
                "public",
                42,
                new int[]{1, 3, 6, 1, 2, 1, 1, 1, 0},
                new int[]{1, 3, 6, 1, 2, 1, 1, 5, 0}
        );
        assertTrue(req.length > 20);
        assertEquals(0x30, req[0] & 0xFF);

        // Constrói GetResponse (tag 0xA2) com OIDs sysDescr e sysName válidos
        byte[] oidDescr = new byte[]{0x06, 0x08, 0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00};
        byte[] valDescr = berOctetString("Intelbras S3028G-PB");
        byte[] vb1 = berSequence(concat(oidDescr, valDescr));

        byte[] oidName = new byte[]{0x06, 0x08, 0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x05, 0x00};
        byte[] valName = berOctetString("SW-S3028-LAB");
        byte[] vb2 = berSequence(concat(oidName, valName));

        byte[] vbl = berSequence(concat(vb1, vb2));
        byte[] reqId = new byte[]{0x02, 0x01, 0x2a};
        byte[] err = new byte[]{0x02, 0x01, 0x00};
        byte[] pdu = berTlv(0xA2, concat(reqId, err, err, vbl));
        byte[] msg = berSequence(concat(
                new byte[]{0x02, 0x01, 0x01},
                berOctetString("public"),
                pdu
        ));

        Map<String, String> decoded = ProductFingerprinter.decodificarSnmpResponse(msg, msg.length);
        assertEquals("Intelbras S3028G-PB", decoded.get("1.3.6.1.2.1.1.1.0"));
        assertEquals("SW-S3028-LAB", decoded.get("1.3.6.1.2.1.1.5.0"));
    }

    @Test
    void deveIdentificarTodasAsFamiliasDeOltIntelbrasPelosSinaisReais() {
        // 1) G16 / G08 / 4840E: SSH-2.0-PRIVATE + Telnet Username(1-64 chars):
        DiscoveredProduct g16 = ProductFingerprinter.classificarSinais(
                "10.1.40.134",
                Compat.listOf(22, 23),
                "SSH-2.0-PRIVATE",
                "",
                "Username(1-64 chars):",
                "",
                "",
                ""
        );
        assertEquals(FamiliaProduto.OLT, g16.familia());
        assertTrue(g16.suportaColetaCli());
        assertEquals("SSH", g16.protocoloPreferido());

        // 2) AN6000-2: apenas Telnet com banner ASCII 'z' + Login e OUI Intelbras
        DiscoveredProduct an6000 = ProductFingerprinter.classificarSinais(
                "10.1.40.140",
                Compat.listOf(23),
                "",
                "",
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz Master Login:",
                "",
                "",
                "48:f9:7c:f0:02:94"
        );
        assertEquals(FamiliaProduto.OLT, an6000.familia());
        assertTrue(an6000.suportaColetaCli());
        assertEquals("TELNET", an6000.protocoloPreferido());
        assertFalse(an6000.bannerOuTitulo().contains("zzzzzzzzzzzz"));

        // 3) AN6001-G16: Telnet + HTTP 80 vazio
        DiscoveredProduct an6001 = ProductFingerprinter.classificarSinais(
                "10.1.40.142",
                Compat.listOf(23, 80),
                "",
                "",
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz Login:",
                "",
                "",
                "48:f9:7c:ea:06:4a"
        );
        assertEquals(FamiliaProduto.OLT, an6001.familia());
        assertEquals("TELNET", an6001.protocoloPreferido());

        // 4) 8820I com OpenSSH_7.3 + SNMP olt8820i: deve preferir TELNET
        DiscoveredProduct olt8820i = ProductFingerprinter.classificarSinais(
                "10.1.40.133",
                Compat.listOf(22, 23),
                "SSH-2.0-OpenSSH_7.3",
                "",
                "",
                "",
                "olt8820i",
                "18:0d:2c:16:46:d1"
        );
        assertEquals(FamiliaProduto.OLT, olt8820i.familia());
        assertEquals("TELNET", olt8820i.protocoloPreferido());
        assertEquals(23, olt8820i.portaCliPreferida());
    }

    private static byte[] berOctetString(String value) {
        return berTlv(0x04, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] berSequence(byte[] content) {
        return berTlv(0x30, content);
    }

    private static byte[] berTlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 3);
        out.write(tag);
        out.write(content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}

package tpDiscover.intelbras.modelos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Consulta de fabricante por OUI (3 primeiros bytes do MAC),
 * sem dependência de bibliotecas externas.
 */
public final class OuiLookup {

    private static final Map<String, String> VENDORS = new HashMap<>();

    static {
        // Intelbras (blocos OUI oficiais e observados em campo)
        registrar("001A3F", "Intelbras");
        registrar("14EBB6", "Intelbras");
        registrar("180D2C", "Intelbras");
        registrar("24FD0D", "Intelbras");
        registrar("2824FF", "Intelbras");
        registrar("2C8074", "Intelbras");
        registrar("30E1F1", "Intelbras");
        registrar("443B32", "Intelbras");
        registrar("4851CF", "Intelbras");
        registrar("488AD2", "Intelbras");
        registrar("48F97C", "Intelbras");
        registrar("50D4F7", "Intelbras");
        registrar("54BAD9", "Intelbras");
        registrar("58108C", "Intelbras");
        registrar("60A44C", "Intelbras");
        registrar("60B9C0", "Intelbras");
        registrar("704F57", "Intelbras");
        registrar("808544", "Intelbras");
        registrar("808FE8", "Intelbras");
        registrar("909A4A", "Intelbras");
        registrar("982A0A", "Intelbras");
        registrar("A09351", "Intelbras");
        registrar("AC84C6", "Intelbras");
        registrar("B0C554", "Intelbras");
        registrar("B44C3B", "Intelbras");
        registrar("BC2411", "Intelbras");
        registrar("BCA511", "Intelbras");
        registrar("C83A35", "Intelbras");
        registrar("D8365F", "Intelbras");
        registrar("D8778B", "Intelbras");
        registrar("DC4EF4", "Intelbras");
        registrar("DC9FDB", "Intelbras");
        registrar("E005C5", "Intelbras");
        registrar("FC253F", "Intelbras");

        // HP / HPE / Aruba / H3C
        registrar("000A5A", "HP");
        registrar("000E7F", "HP");
        registrar("000FE2", "H3C");
        registrar("000FE8", "H3C");
        registrar("3C5282", "HP");
        registrar("70106F", "HP");
        registrar("9CB654", "HP");
        registrar("204C03", "Aruba");

        // Dahua (CFTV / Câmeras IP)
        registrar("38AF29", "Dahua");
        registrar("4C11BF", "Dahua");
        registrar("9002A9", "Dahua");
        registrar("A41437", "Dahua");
        registrar("BC3253", "Dahua");
        registrar("E0508B", "Dahua");

        // Hikvision
        registrar("2857BE", "Hikvision");
        registrar("40B4CD", "Hikvision");
        registrar("4419B6", "Hikvision");
        registrar("B4A382", "Hikvision");
        registrar("C056E3", "Hikvision");

        // Dell
        registrar("001422", "Dell");
        registrar("180373", "Dell");
        registrar("B82A72", "Dell");
        registrar("D481D7", "Dell");
        registrar("F8BC12", "Dell");

        // Cisco / Meraki
        registrar("00000C", "Cisco");
        registrar("000142", "Cisco");
        registrar("00107B", "Cisco");
        registrar("00180A", "Cisco Meraki");
        registrar("78D6B2", "Cisco Meraki");

        // Mikrotik
        registrar("488F5A", "Mikrotik");
        registrar("64D154", "Mikrotik");
        registrar("B869F4", "Mikrotik");
        registrar("CC2DE0", "Mikrotik");
        registrar("D4CA6D", "Mikrotik");
        registrar("E48D8C", "Mikrotik");

        // TP-Link
        registrar("0019E0", "TP-Link");
        registrar("0022A1", "TP-Link");
        registrar("101B54", "TP-Link");
        registrar("707BE8", "TP-Link");
        registrar("E848B8", "TP-Link");

        // Ubiquiti
        registrar("001AA0", "Ubiquiti");
        registrar("0418D6", "Ubiquiti");
        registrar("24A43C", "Ubiquiti");
        registrar("68D79A", "Ubiquiti");
        registrar("704CA5", "Ubiquiti");
        registrar("B4FBE4", "Ubiquiti");
        registrar("F09FC2", "Ubiquiti");

        // Telefonia IP (VoIP)
        registrar("0004F2", "Polycom");
        registrar("000B82", "Grandstream");
        registrar("001565", "Yealink");
        registrar("805E0C", "Yealink");

        // Segurança / Firewall
        registrar("00090F", "Fortinet");
        registrar("085B0E", "Fortinet");

        // Placas de Rede / Servidores / PCs (Intel / Realtek / Broadcom)
        registrar("00070E", "Realtek");
        registrar("001A2B", "Realtek");
        registrar("00E04C", "Realtek");
        registrar("001B21", "Intel");
        registrar("001E67", "Intel");
        registrar("3CD92B", "Intel");
        registrar("A4BF01", "Intel");
        registrar("001018", "Broadcom");

        // Virtualização
        registrar("005056", "VMware");
        registrar("000C29", "VMware");
        registrar("00155D", "Microsoft Hyper-V");
        registrar("080027", "VirtualBox");
        registrar("525400", "QEMU/KVM");

        // Dispositivos Gerais / IoT / Outros
        registrar("B827EB", "Raspberry Pi");
        registrar("DCA632", "Raspberry Pi");
        registrar("E45F01", "Raspberry Pi");
        registrar("001882", "Huawei");
        registrar("001788", "Philips Hue");
    }

    private OuiLookup() {
    }

    private static void registrar(String ouiHex, String vendor) {
        VENDORS.put(ouiHex.toUpperCase(), vendor);
    }

    /**
     * Identifica o fabricante a partir do MAC (qualquer formato).
     */
    public static Optional<String> identificarFabricante(String mac) {
        if (mac == null) return Optional.empty();
        String clean = mac.replace("-", "").replace(":", "").replace(".", "").toUpperCase();
        if (clean.length() < 6) return Optional.empty();
        String oui = clean.substring(0, 6);
        return Optional.ofNullable(VENDORS.get(oui));
    }

    /**
     * Retorna o MAC com o nome do fabricante entre parênteses, se conhecido.
     * Exemplo: "0009-0f09-1e0a (Fortinet)" ou apenas "0009-0f09-1e0a".
     */
    public static String formatar(String mac) {
        return identificarFabricante(mac)
                .map(fabricante -> mac + " (" + fabricante + ")")
                .orElse(mac);
    }
}

package tpDiscover.intelbras.parser;

import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser para a saída de CLI dos switches Intelbras linha S23/S33 (firmware Comware).
 *
 * Cobre dois comandos:
 *  - display device manuinfo   -> identidade do switch
 *  - display mac-address       -> tabela MAC
 */
public class ComwareParser {

    // Reconhece uma linha de dado da tabela MAC, ex:
    // "0009-0f09-1e0a   1          Learned          GE1/0/23                 Y"
    private static final Pattern MAC_LINE = Pattern.compile(
            "^([0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4})\\s+" + // MAC
            "(\\d+)\\s+" +                                          // VLAN ID
            "(\\S+)\\s+" +                                          // State
            "(\\S+)\\s+" +                                          // Port/Nickname
            "(\\S+)\\s*$"                                           // Aging (Y/N) - não usamos, mas valida a linha
    );

    /**
     * Faz o parse da saída de "display mac-address".
     *
     * @param switchId  identificador do switch de origem (ex: MAC-base ou IP),
     *                  usado para marcar de onde veio cada entrada.
     * @param rawOutput texto bruto retornado pelo terminal.
     */
    public List<MacEntry> parseMacAddressTable(String switchId, String rawOutput) {
        List<MacEntry> entries = new ArrayList<>();

        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher m = MAC_LINE.matcher(trimmed);
            if (!m.matches()) {
                // Ignora silenciosamente cabeçalho ("MAC Address VLAN ID..."),
                // prompts (ex: "[S2328G-B]display mac-address") e linhas de rodapé/total.
                continue;
            }

            String mac = m.group(1);
            int vlan = Integer.parseInt(m.group(2));
            String state = m.group(3);
            String port = m.group(4);

            entries.add(new MacEntry(switchId, mac, vlan, state, port));
        }

        return entries;
    }

    /**
     * Faz o parse da saída de "display device manuinfo".
     *
     * @param ip IP usado na conexão (não vem no comando, é injetado pelo coletor).
     */
    public SwitchIdentity parseDeviceManuInfo(String ip, String rawOutput) {
        String deviceName = extrairCampo(rawOutput, "DEVICE_NAME");
        String serial = extrairCampo(rawOutput, "DEVICE_SERIAL_NUMBER");
        String mac = extrairCampo(rawOutput, "MAC_ADDRESS");
        String productId = extrairCampo(rawOutput, "PRODUCT_ID");

        return new SwitchIdentity(deviceName, serial, mac, productId, ip);
    }

    private String extrairCampo(String rawOutput, String campo) {
        // Formato: "CAMPO          : valor"
        Pattern p = Pattern.compile("^\\s*" + Pattern.quote(campo) + "\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);
        Matcher m = p.matcher(rawOutput);
        return m.find() ? m.group(1) : null;
    }
}

package tpDiscover.intelbras.parser;

import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser para a saída de CLI dos switches Intelbras linha S23/S33 (firmware Comware).
 *
 * Cobre os comandos:
 *  - display device manuinfo            -> identidade do switch
 *  - display mac-address                -> tabela MAC
 *  - display link-aggregation verbose   -> portas físicas que compõem cada BAGG
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

    // Ex: "Aggregate Interface: Bridge-Aggregation2"
    private static final Pattern BAGG_HEADER = Pattern.compile(
            "(?i)^\\s*Aggregate\\s+Interface\\s*:\\s*(?:Bridge-Aggregation|Route-Aggregation|BAGG)(\\d+)\\b"
    );

    // Ex: "  GE1/0/1          S       32768    1"
    private static final Pattern BAGG_MEMBER_PORT = Pattern.compile(
            "^\\s*((?:X?GE|GigabitEthernet|Ten-GigabitEthernet|Ethernet)\\d+(?:/\\d+)+)\\s+([SUI])\\b",
            Pattern.CASE_INSENSITIVE
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
     * Faz o parse da saída de "display link-aggregation verbose" para descobrir
     * quais portas físicas compõem cada interface lógica BAGG (ex: "BAGG2" -> ["GE1/0/1", "GE1/0/2"]).
     */
    public Map<String, List<String>> parseLinkAggregation(String rawOutput) {
        Map<String, Set<String>> membrosPorBagg = new LinkedHashMap<>();
        if (rawOutput == null || rawOutput.isBlank()) {
            return Map.of();
        }

        String baggAtual = null;
        boolean emSecaoRemota = false;

        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher mHeader = BAGG_HEADER.matcher(trimmed);
            if (mHeader.find()) {
                baggAtual = "BAGG" + mHeader.group(1);
                emSecaoRemota = false;
                membrosPorBagg.computeIfAbsent(baggAtual, k -> new LinkedHashSet<>());
                continue;
            }

            if (baggAtual == null) continue;

            if (trimmed.regionMatches(true, 0, "Remote:", 0, 7)) {
                emSecaoRemota = true;
                continue;
            }
            if (trimmed.regionMatches(true, 0, "Local:", 0, 6)) {
                emSecaoRemota = false;
                continue;
            }

            if (!emSecaoRemota) {
                Matcher mPort = BAGG_MEMBER_PORT.matcher(line);
                if (mPort.find()) {
                    String portaFisica = normalizarNomePortaComware(mPort.group(1));
                    membrosPorBagg.get(baggAtual).add(portaFisica);
                }
            }
        }

        Map<String, List<String>> resultado = new LinkedHashMap<>();
        membrosPorBagg.forEach((bagg, portas) -> {
            if (!portas.isEmpty()) {
                resultado.put(bagg, List.copyOf(portas));
            }
        });
        return resultado;
    }

    /**
     * Substitui nomes de interface de agregação (ex: "BAGG2") pelo formato enriquecido
     * com suas portas físicas membro (ex: "BAGG2 (GE1/0/1, GE1/0/2)").
     */
    public List<MacEntry> expandirPortasAgregadas(List<MacEntry> entries, Map<String, List<String>> membrosPorBagg) {
        if (membrosPorBagg == null || membrosPorBagg.isEmpty()) {
            return entries;
        }
        List<MacEntry> resultado = new ArrayList<>(entries.size());
        for (MacEntry e : entries) {
            List<String> membros = membrosPorBagg.get(e.port().toUpperCase());
            if (membros != null && !membros.isEmpty()) {
                String portaExpandida = e.port() + " (" + String.join(", ", membros) + ")";
                resultado.add(new MacEntry(e.switchId(), e.mac(), e.vlanId(), e.state(), portaExpandida));
            } else {
                resultado.add(e);
            }
        }
        return resultado;
    }

    private static String normalizarNomePortaComware(String porta) {
        String p = porta.trim();
        if (p.regionMatches(true, 0, "GigabitEthernet", 0, 15)) {
            return "GE" + p.substring(15);
        }
        if (p.regionMatches(true, 0, "Ten-GigabitEthernet", 0, 19)) {
            return "XGE" + p.substring(19);
        }
        return p.toUpperCase();
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

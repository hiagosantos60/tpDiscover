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
 * Parser para a CLI dos switches Intelbras Série 3000 (ex: S3028G-PB, S3054G-B).
 *
 * Cobre:
 *  - show mac-address all      -> tabela MAC
 *  - show version              -> identidade do switch
 */
public class Serie3000Parser {

    // Reconhece uma linha da tabela MAC no S30:
    // " 1        48F9.7CEA.064A    STATIC      gi1/0/2                   FWD        [NC]"
    private static final Pattern MAC_LINE = Pattern.compile(
            "^\\s*(\\d+)\\s+" +                                                   // VLAN
            "([0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4})\\s+" +   // MAC
            "(\\S+)\\s+" +                                                        // TYPE
            "(\\S+)\\s+" +                                                        // INTERFACE
            "(\\S+)\\s+" +                                                        // STATE
            "(\\[?\\S+\\]?)\\s*$"                                                 // FLAG
    );

    /**
     * Faz o parse da saída de "show mac-address all" (ou "show mac-address-table").
     */
    public List<MacEntry> parseMacAddressTable(String switchId, String rawOutput) {
        List<MacEntry> entries = new ArrayList<>();
        if (rawOutput == null) return entries;

        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher m = MAC_LINE.matcher(trimmed);
            if (!m.matches()) {
                continue; // Ignora cabeçalhos, banners e legendas de flag
            }

            int vlan = Integer.parseInt(m.group(1));
            String rawMac = m.group(2);
            String state = m.group(3); // STATIC ou DYNAMIC
            String port = normalizarPorta(m.group(4));

            String macPadrao = formatarMacPadrao(rawMac);

            entries.add(new MacEntry(switchId, macPadrao, vlan, state, port));
        }

        return entries;
    }

    /**
     * Faz o parse da saída de "show link-aggregation" / "show etherchannel summary" no S30
     * para descobrir quais portas físicas (gi1/0/x, te1/0/x) compõem cada interface agg (BAGG).
     */
    public Map<String, List<String>> parseLinkAggregation(String rawOutput) {
        Map<String, Set<String>> membrosPorBagg = new LinkedHashMap<>();
        if (rawOutput == null || rawOutput.isBlank()) {
            return Map.of();
        }

        Pattern aggHeader = Pattern.compile("(?i)(?:\\bagg(?:regateport)?|\\bpo(?:rt-channel)?|\\bgroup|\\bchannel-group)\\s*[:=]?\\s*(\\d+)\\b");
        Pattern memberPattern = Pattern.compile("(?i)\\b((?:gi|te|fa|ge|xge)\\d+(?:/\\d+)+)\\b");

        String baggAtual = null;
        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher mHeader = aggHeader.matcher(trimmed);
            if (mHeader.find()) {
                baggAtual = "BAGG" + mHeader.group(1);
                membrosPorBagg.computeIfAbsent(baggAtual, k -> new LinkedHashSet<>());
            }

            if (baggAtual != null) {
                Matcher mPort = memberPattern.matcher(trimmed);
                while (mPort.find()) {
                    membrosPorBagg.get(baggAtual).add(normalizarPorta(mPort.group(1)));
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
     * Substitui nomes de interface de agregação (ex: "BAGG1") pelo formato enriquecido
     * com suas portas físicas membro (ex: "BAGG1 (GE1/0/1, GE1/0/2)").
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

    /**
     * Faz o parse da saída de "show version" para extrair a identidade do switch Série 3000.
     */
    public SwitchIdentity parseDeviceVersion(String ip, String hostnamePadrao, String rawOutput) {
        String deviceName = hostnamePadrao;
        String serial = null;
        String mac = null;
        String productId = null;

        if (rawOutput != null) {
            // Tenta encontrar MAC base / System MAC - vários formatos possíveis no S30
            Pattern macPattern = Pattern.compile(
                    "(?i)(?:system\\s+mac(?:\\s+address)?|base\\s+(?:ethernet\\s+)?mac(?:\\s+address)?|burned[\\s\\-]in\\s+mac|mac\\s+address|hardware\\s+address|address\\s+is)\\s*[:=,]?\\s*([0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}|[0-9a-fA-F]{2}(?:[:\\-][0-9a-fA-F]{2}){5})",
                    Pattern.MULTILINE
            );
            Matcher macMatcher = macPattern.matcher(rawOutput);
            if (macMatcher.find()) {
                mac = formatarMacPadrao(macMatcher.group(1)).toUpperCase();
            }

            // Tenta encontrar Modelo / Produto específico da linha S30 (ex: S3028G-PB, S3054G-B)
            Matcher s30Matcher = Pattern.compile("\\b(S30\\d{2}[A-Z0-9]*(?:-[A-Z0-9]+)*)\\b", Pattern.CASE_INSENSITIVE).matcher(rawOutput);
            if (s30Matcher.find()) {
                productId = s30Matcher.group(1).toUpperCase();
            } else {
                Pattern modelPattern = Pattern.compile(
                        "(?i)^\\s*(?:hardware\\s+model|model(?:\\s+name)?|device\\s+type|product(?:\\s+name|\\s+id)?|system\\s+type|system\\s+description)\\s*[:=]\\s*(.+?)\\s*$",
                        Pattern.MULTILINE
                );
                Matcher modelMatcher = modelPattern.matcher(rawOutput);
                while (modelMatcher.find()) {
                    String candidate = modelMatcher.group(1).trim();
                    String semFabricante = candidate.replaceFirst("(?i)^intelbras\\s*(?:switch\\s*)?", "").trim();
                    if (!semFabricante.isEmpty() && !semFabricante.equalsIgnoreCase("Intelbras")) {
                        productId = semFabricante;
                        break;
                    }
                    if (productId == null && !candidate.isEmpty()) {
                        productId = candidate;
                    }
                }
            }

            // Tenta encontrar Serial Number (exige ':' ou '=' para não capturar palavras soltas)
            Pattern snPattern = Pattern.compile(
                    "(?i)^\\s*(?:device\\s+)?(?:serial\\s+number|serial\\s+no\\.?|s/n|sn)\\s*[:=]\\s*(\\S+)",
                    Pattern.MULTILINE
            );
            Matcher snMatcher = snPattern.matcher(rawOutput);
            if (snMatcher.find()) {
                serial = snMatcher.group(1);
            }

            // Tenta encontrar Hostname / System Name se informado no show system / show version
            Pattern namePattern = Pattern.compile(
                    "(?i)^\\s*(?:device\\s+name|system\\s+name|hostname|sysname)\\s*[:=]\\s*(\\S+)",
                    Pattern.MULTILINE
            );
            Matcher nameMatcher = namePattern.matcher(rawOutput);
            if (nameMatcher.find()) {
                deviceName = nameMatcher.group(1);
            } else {
                // Tenta extrair o hostname a partir do prompt CLI (ex: "INTELBRAS#" ou "SW-S30#")
                Matcher promptMatcher = Pattern.compile("(?m)^([a-zA-Z0-9_\\-.]+)[>#]\\s*$").matcher(rawOutput);
                if (promptMatcher.find()) {
                    deviceName = promptMatcher.group(1);
                }
            }
        }

        if (deviceName == null || deviceName.isBlank()) {
            deviceName = "S30-" + ip;
        }
        if (productId == null || productId.isBlank() || productId.equalsIgnoreCase("Intelbras")) {
            productId = "Série 3000";
        }
        if (mac == null || mac.isBlank()) {
            mac = "0000-0000-0000";
        }

        return new SwitchIdentity(deviceName, serial, mac, productId, ip);
    }

    /**
     * Converte notação Cisco/S30 (gi1/0/1, te1/0/2, agg1) para notação unificada (GE1/0/1, XGE1/0/2, BAGG1).
     */
    public static String normalizarPorta(String porta) {
        if (porta == null) return "";
        String p = porta.trim();
        String lower = p.toLowerCase();
        if (lower.startsWith("gi")) {
            return "GE" + p.substring(2);
        } else if (lower.startsWith("te")) {
            return "XGE" + p.substring(2);
        } else if (lower.startsWith("agg")) {
            return "BAGG" + p.substring(3);
        }
        return p.toUpperCase();
    }

    /**
     * Converte qualquer formato de MAC (48F9.7CEA.064A, 48-F9-7C-EA-06-4A, 48:f9...) para o formato Comware (48f9-7cea-064a).
     */
    public static String formatarMacPadrao(String mac) {
        if (mac == null) return "";
        String clean = mac.toLowerCase().replace("-", "").replace(":", "").replace(".", "");
        if (clean.length() == 12) {
            return clean.substring(0, 4) + "-" + clean.substring(4, 8) + "-" + clean.substring(8, 12);
        }
        return mac.toLowerCase();
    }
}

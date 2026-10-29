package tpDiscover.intelbras.parser;

import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser para a CLI das OLTs Intelbras:
 *  - Linha EPON (ex: OLT 4840E / OLT 4880E)
 *  - Linha GPON (ex: G16 / G08)
 *
 * Cobre os comandos:
 *  - show mac-address-table   -> tabela MAC (formato: MAC  VLAN  port  status)
 *  - show version             -> MAC base, serial, versão de hardware/software
 *  - show system              -> hostname (system name) e modelo (product name)
 */
public class OltParser {

    // Formato padrão OLT 4840E / G16 / G08:
    // "00:09:0f:09:1e:0a     1        e0/2     dynamic"
    private static final Pattern MAC_FIRST_LINE = Pattern.compile(
            "^\\s*([0-9a-fA-F]{2}(?:[:\\-][0-9a-fA-F]{2}){5}|[0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4})\\s+" +
            "(\\d+)\\s+" +
            "(\\S+)\\s+" +
            "(\\S+)\\s*$"
    );

    // Formato alternativo caso alguma versão exiba VLAN antes do MAC:
    // "1     00:09:0f:09:1e:0a     e0/2     dynamic"
    private static final Pattern VLAN_FIRST_LINE = Pattern.compile(
            "^\\s*(\\d+)\\s+" +
            "([0-9a-fA-F]{2}(?:[:\\-][0-9a-fA-F]{2}){5}|[0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4})\\s+" +
            "(\\S+)\\s+" +
            "(\\S+)\\s*$"
    );

    /**
     * Detecta se a saída de "show version" / "show system" pertence a uma OLT Intelbras (4840E, G16, G08, etc.).
     */
    public boolean ehDispositivoOlt(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return false;
        }
        String lower = rawOutput.toLowerCase();
        return lower.contains("broadband network platform")
                || lower.contains("epon product")
                || lower.contains("gpon product")
                || lower.contains("system description     :epon")
                || lower.contains("system description     :gpon")
                || lower.contains("olt-04p")
                || lower.contains("olt-08p")
                || lower.contains("olt-16p")
                || lower.contains("olt4840")
                || lower.contains("olt4880")
                || Pattern.compile("(?i)\\b(olt\\s*4840e?|olt\\s*4880e?|olt\\s*g16|olt\\s*g08|g16|g08)\\b").matcher(rawOutput).find();
    }

    /**
     * Faz o parse da saída de "show mac-address-table" nas OLTs.
     * Ignora entradas internas da própria CPU (porta "cpu").
     */
    public List<MacEntry> parseMacAddressTable(String switchId, String rawOutput) {
        List<MacEntry> entries = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) {
            return entries;
        }

        // Remove eventuais resíduos de paginação colados na linha
        String limpo = rawOutput.replaceAll("(?i)[.]{2,}\\s*press\\s+enter\\s+to\\s+next\\s+line[^.\\r\\n]*[.]{2,}", "\n");

        for (String line : limpo.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher m1 = MAC_FIRST_LINE.matcher(trimmed);
            if (m1.matches()) {
                String rawMac = m1.group(1);
                int vlan = Integer.parseInt(m1.group(2));
                String rawPort = m1.group(3);
                String status = m1.group(4);

                if (ehPortaInternaCpu(rawPort)) {
                    continue;
                }

                entries.add(new MacEntry(
                        switchId,
                        Serie3000Parser.formatarMacPadrao(rawMac),
                        vlan,
                        status,
                        normalizarPorta(rawPort)
                ));
                continue;
            }

            Matcher m2 = VLAN_FIRST_LINE.matcher(trimmed);
            if (m2.matches()) {
                int vlan = Integer.parseInt(m2.group(1));
                String rawMac = m2.group(2);
                String col3 = m2.group(3);
                String col4 = m2.group(4);

                // Descobre qual coluna é porta e qual é status
                boolean col3EhStatus = col3.equalsIgnoreCase("dynamic") || col3.equalsIgnoreCase("static");
                String rawPort = col3EhStatus ? col4 : col3;
                String status = col3EhStatus ? col3 : col4;

                if (ehPortaInternaCpu(rawPort)) {
                    continue;
                }

                entries.add(new MacEntry(
                        switchId,
                        Serie3000Parser.formatarMacPadrao(rawMac),
                        vlan,
                        status,
                        normalizarPorta(rawPort)
                ));
            }
        }

        return entries;
    }

    private static boolean ehPortaInternaCpu(String porta) {
        if (porta == null) return true;
        String p = porta.trim().toLowerCase();
        return p.equals("cpu") || p.equals("self") || p.equals("system");
    }

    /**
     * Extrai a identidade da OLT a partir da saída combinada de "show version" e "show system".
     */
    public SwitchIdentity parseDeviceInfo(String ip, String hostnamePadrao, String rawOutput) {
        String deviceName = hostnamePadrao;
        String serial = null;
        String mac = null;
        String productId = null;

        if (rawOutput != null) {
            // MAC base (ex: "MAC address         :58:10:8c:68:da:f0")
            Matcher macMatcher = Pattern.compile(
                    "(?i)(?:base\\s+mac(?:\\s+address)?|system\\s+mac(?:\\s+address)?|mac\\s+address)\\s*[:=]\\s*([0-9a-fA-F]{2}(?:[:\\-][0-9a-fA-F]{2}){5}|[0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4})",
                    Pattern.MULTILINE
            ).matcher(rawOutput);
            if (macMatcher.find()) {
                mac = Serie3000Parser.formatarMacPadrao(macMatcher.group(1)).toUpperCase();
            }

            // Serial Number (ex: "product serial no.  :1" ou "serial number : ...")
            Matcher snMatcher = Pattern.compile(
                    "(?i)^\\s*(?:product\\s+serial\\s+no\\.?|device\\s+serial\\s+number|serial\\s+number|serial\\s+no\\.?|s/n)\\s*[:=]\\s*(\\S+)",
                    Pattern.MULTILINE
            ).matcher(rawOutput);
            if (snMatcher.find()) {
                serial = snMatcher.group(1).trim();
            }

            // System Name / Hostname (ex: "system name            :OLT4880E")
            Matcher nameMatcher = Pattern.compile(
                    "(?i)^\\s*(?:system\\s+name|device\\s+name|hostname|sysname)\\s*[:=]\\s*(\\S+)",
                    Pattern.MULTILINE
            ).matcher(rawOutput);
            if (nameMatcher.find()) {
                deviceName = nameMatcher.group(1).trim();
            } else {
                Matcher promptMatcher = Pattern.compile("(?m)^([a-zA-Z0-9_\\-.]+)[>#]\\s*$").matcher(rawOutput);
                if (promptMatcher.find()) {
                    deviceName = promptMatcher.group(1).trim();
                }
            }

            // Product Name / Model (ex: "product name           :OLT-04P EPON Product" ou G16 / G08 / OLT 4840E)
            Matcher prodMatcher = Pattern.compile(
                    "(?i)^\\s*(?:product\\s+name|hardware\\s+model|model(?:\\s+name)?|product\\s+id)\\s*[:=]\\s*(.+?)\\s*$",
                    Pattern.MULTILINE
            ).matcher(rawOutput);
            if (prodMatcher.find()) {
                productId = prodMatcher.group(1).trim().replaceFirst("(?i)\\s+Product$", "").trim();
            } else {
                Matcher swVerMatcher = Pattern.compile(
                        "(?i)^\\s*software\\s+version\\s*[:=]\\s*(\\S+)",
                        Pattern.MULTILINE
                ).matcher(rawOutput);
                if (swVerMatcher.find()) {
                    productId = swVerMatcher.group(1).trim();
                }
            }
        }

        if (deviceName == null || deviceName.isBlank()) {
            deviceName = "OLT-" + ip;
        }
        if (productId == null || productId.isBlank()) {
            productId = "OLT Intelbras";
        }
        if (mac == null || mac.isBlank()) {
            mac = "0000-0000-0000";
        }

        return new SwitchIdentity(deviceName, serial, mac, productId, ip);
    }

    /**
     * Padroniza nomes de portas de OLT (ex: "e0/2" -> "E0/2", "p0/1" -> "P0/1", "gpon0/1" -> "GPON0/1").
     */
    public static String normalizarPorta(String porta) {
        if (porta == null) return "";
        return porta.trim().toUpperCase();
    }
}

package tpDiscover.intelbras.parser;

import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser para a CLI dos switches Intelbras Série 3000 (ex: S3028G-B, S3028G-PB Max, S3054G-B).
 *
 * Cobre:
 *  - show mac-address all      -> tabela MAC
 *  - show version              -> modelo / versão do switch
 *  - show system mac           -> MACs base/interface/STP do sistema (intf mac, ag mac, ap mac, dc0 mac)
 *  - show system mpu           -> Serial No. e tipo do cartão principal (quando suportado)
 *  - show spanning-tree / show interface vlan X -> fallbacks de MAC do switch
 *  - show ip route / show arp  -> descoberta automática do roteador/gateway da rede
 */
public class Serie3000Parser {

    private static final String MAC_HEX_REGEX =
            "([0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}|[0-9a-fA-F]{2}(?:[:\\-][0-9a-fA-F]{2}){5})";

    private static final String IPV4_REGEX =
            "((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})";

    // Prioridade 1: MAC da interface L3/VLAN ou MAC base explícito do sistema
    private static final Pattern MAC_PRIORITARIO_PATTERN = Pattern.compile(
            "(?i)(?:intf\\s+mac|mac\\s+address\\s+of\\s+l3|system\\s+mac\\s+address|base\\s+(?:ethernet\\s+)?mac(?:\\s+address)?|burned[\\s\\-]in\\s+mac|(?:ethernet|hardware)\\s+address(?:\\s+is)?)\\s*[:=,]?\\s*" + MAC_HEX_REGEX,
            Pattern.MULTILINE
    );

    // Prioridade 2: MAC de agregação/bridge STP (ag mac, ap mac, dc0 mac, Bridge address)
    private static final Pattern MAC_FALLBACK_PATTERN = Pattern.compile(
            "(?i)(?:ag\\s+mac|ap\\s+mac|dc0\\s+mac|mac\\s+address\\s+of\\s+(?:ag|ap)|\\bbridge\\s+address|mac\\s+address|address\\s+is)\\s*[:=,]?\\s*" + MAC_HEX_REGEX,
            Pattern.MULTILINE
    );

    // Reconhece uma linha da tabela MAC no S30 com VLAN antes do MAC (com ou sem coluna inicial de índice e colunas finais STATE/FLAG):
    // Ex 1: " 1        48F9.7CEA.064A    STATIC      gi1/0/2                   FWD        [NC]"
    // Ex 2: " 1        0009.0f09.1e0a    DYNAMIC     g0/2"
    // Ex 3: " 1        0009.0f09.1e0a    gi0/2       dynamic"
    private static final Pattern MAC_LINE_VLAN_FIRST = Pattern.compile(
            "^\\s*(?:\\d+\\s+)?" +                                                // Índice opcional
            "(\\d{1,4})\\s+" +                                                    // VLAN (1..4094)
            MAC_HEX_REGEX + "\\s+" +                                              // MAC
            "(\\S+)\\s+" +                                                        // TYPE ou INTERFACE
            "(\\S+)" +                                                            // INTERFACE ou TYPE
            "(?:\\s+\\S+){0,3}\\s*$"                                              // STATE / FLAG opcionais
    );

    // Reconhece variantes de firmware S30 com MAC antes da VLAN:
    // Ex 1: "0009.0f09.1e0a   1   Dynamic   gi0/2"
    // Ex 2: "00:09:0f:09:1e:0a  1  gi0/2  dynamic"
    private static final Pattern MAC_LINE_MAC_FIRST = Pattern.compile(
            "^\\s*(?:\\d+\\s+)?" +                                                // Índice opcional
            MAC_HEX_REGEX + "\\s+" +                                              // MAC
            "(\\d{1,4})\\s+" +                                                    // VLAN (1..4094)
            "(\\S+)\\s+" +                                                        // TYPE ou INTERFACE
            "(\\S+)" +                                                            // INTERFACE ou TYPE
            "(?:\\s+\\S+){0,3}\\s*$"                                              // Colunas extras opcionais
    );

    private static final Pattern PORTA_FISICA_OU_LAG_TOKEN = Pattern.compile(
            "(?i)^(?:(?:gigabitethernet|ten-?gigabitethernet|fastethernet|ethernet|eth|xge|ge|gi|te|fa|fe|g|t|f|e)\\d+(?:/\\d+)+"
            + "|(?:bridge-aggregation|route-aggregation|port-channel|bagg|agg|po|lag|trunk)\\d+)$"
    );

    // Rota padrão em "show ip route":
    // "S   0.0.0.0/0 [1/0] via 10.1.40.1, 9w5d:03:58:13, vlan1"
    // ou "Gateway of last resort is 10.1.40.1 to network 0.0.0.0"
    private static final Pattern DEFAULT_ROUTE_LINE = Pattern.compile(
            "(?i)^.*?(?:0\\.0\\.0\\.0(?:/0|\\s+0\\.0\\.0\\.0)|gateway\\s+of\\s+last\\s+resort\\s+is)\\b.*?\\b" + IPV4_REGEX + "\\b"
    );

    // Linha de "show arp" no S30:
    // "Internet  10.1.40.1           0         0009.0f09.1e0a  ARPA    vlan1                              gi1/0/2"
    private static final Pattern ARP_LINE = Pattern.compile(
            "^\\s*(?:Internet\\s+)?" + IPV4_REGEX + "\\s+\\S+\\s+" + MAC_HEX_REGEX + "(?:\\s+\\S+)?(?:\\s+(\\S+))?(?:\\s+(\\S+))?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Faz o parse da saída de "show mac-address all" (ou "show mac-address-table" / "show mac address-table").
     */
    public List<MacEntry> parseMacAddressTable(String switchId, String rawOutput) {
        List<MacEntry> entries = new ArrayList<>();
        if (rawOutput == null) return entries;

        String limpo = limparControleTerminal(rawOutput);
        for (String line : limpo.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            int vlan;
            String rawMac;
            String colA;
            String colB;

            Matcher mVlanFirst = MAC_LINE_VLAN_FIRST.matcher(trimmed);
            if (mVlanFirst.matches()) {
                vlan = Integer.parseInt(mVlanFirst.group(1));
                rawMac = mVlanFirst.group(2);
                colA = mVlanFirst.group(3);
                colB = mVlanFirst.group(4);
            } else {
                Matcher mMacFirst = MAC_LINE_MAC_FIRST.matcher(trimmed);
                if (!mMacFirst.matches()) {
                    continue; // Ignora cabeçalhos, banners e legendas de flag
                }
                rawMac = mMacFirst.group(1);
                vlan = Integer.parseInt(mMacFirst.group(2));
                colA = mMacFirst.group(3);
                colB = mMacFirst.group(4);
            }

            if (vlan < 1 || vlan > 4094) {
                continue;
            }

            String state;
            String rawPort;
            if (ehTokenDePortaOuInterna(colB) && !ehTokenDePortaOuInterna(colA)) {
                state = colA;
                rawPort = colB;
            } else if (ehTokenDePortaOuInterna(colA) && !ehTokenDePortaOuInterna(colB)) {
                rawPort = colA;
                state = colB;
            } else {
                continue;
            }

            if (ehPortaInterna(rawPort)) {
                continue;
            }
            String port = normalizarPorta(rawPort);
            String macPadrao = formatarMacPadrao(rawMac);

            entries.add(new MacEntry(switchId, macPadrao, vlan, state, port));
        }

        return entries;
    }

    /**
     * Extrai o IP do gateway padrão (0.0.0.0/0) da saída de "show ip route".
     */
    public Optional<String> parseDefaultGatewayIp(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return Optional.empty();
        }
        Pattern ipv4 = Pattern.compile("\\b" + IPV4_REGEX + "\\b");
        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (DEFAULT_ROUTE_LINE.matcher(trimmed).find()) {
                Matcher mIp = ipv4.matcher(trimmed);
                while (mIp.find()) {
                    String cand = mIp.group(1);
                    if (!"0.0.0.0".equals(cand)) {
                        return Optional.of(cand);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Faz o parse da saída de "show arp", extraindo IP, MAC e porta física (Switchport, quando presente).
     */
    public List<ArpEntry> parseArpTable(String rawOutput) {
        List<ArpEntry> entries = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) {
            return entries;
        }

        String limpo = limparControleTerminal(rawOutput);
        for (String line : limpo.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher m = ARP_LINE.matcher(trimmed);
            if (!m.matches()) continue;

            String ip = m.group(1);
            String mac = formatarMacPadrao(m.group(2));
            String colA = m.group(3);
            String colB = m.group(4);

            String portaFisica = extrairPortaFisicaArp(colB != null ? colB : colA);
            if (portaFisica == null && colB != null) {
                portaFisica = extrairPortaFisicaArp(colA);
            }

            entries.add(new ArpEntry(ip, mac, portaFisica));
        }
        return entries;
    }

    public List<ArpEntry> expandirPortasArp(List<ArpEntry> entries, Map<String, List<String>> membrosPorBagg) {
        if (membrosPorBagg == null || membrosPorBagg.isEmpty()) {
            return entries;
        }
        List<ArpEntry> resultado = new ArrayList<>(entries.size());
        for (ArpEntry e : entries) {
            if (e.port() != null) {
                List<String> membros = membrosPorBagg.get(e.port().toUpperCase());
                if (membros != null && !membros.isEmpty()) {
                    resultado.add(new ArpEntry(e.ip(), e.mac(), e.port() + " (" + String.join(", ", membros) + ")"));
                    continue;
                }
            }
            resultado.add(e);
        }
        return resultado;
    }

    private static String extrairPortaFisicaArp(String token) {
        if (token == null || token.isBlank()) return null;
        String t = token.trim();
        String lower = t.toLowerCase();
        if (t.equals("-") || t.startsWith("--") || lower.startsWith("vlan")
                || lower.startsWith("inloopback") || lower.startsWith("loopback")
                || ehPortaInterna(t)) {
            return null;
        }
        if (PORTA_FISICA_OU_LAG_TOKEN.matcher(t).matches()) {
            return normalizarPorta(t);
        }
        return null;
    }

    private static boolean ehTokenDePortaOuInterna(String token) {
        if (token == null || token.isBlank()) return false;
        return ehPortaInterna(token) || PORTA_FISICA_OU_LAG_TOKEN.matcher(token.trim()).matches();
    }

    private static boolean ehPortaInterna(String porta) {
        if (porta == null) return true;
        String p = porta.trim().toLowerCase();
        return p.equals("cpu") || p.equals("self") || p.equals("system") || p.equals("drop") || p.startsWith("null");
    }

    /**
     * Remove sequências ANSI, NULs do protocolo Telnet (\0) e resolve backspaces (\b) gerados
     * ao apagar prompts de paginação (--More-- / ---MORE---).
     */
    public static String limparControleTerminal(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) return "";
        String semAnsi = rawOutput
                .replaceAll("\\u001B\\[[0-9;?]*[A-Za-z]", "")
                .replace("\u0000", "");
        if (semAnsi.indexOf('\b') < 0) {
            return semAnsi;
        }
        StringBuilder sb = new StringBuilder(semAnsi.length());
        for (int i = 0; i < semAnsi.length(); i++) {
            char c = semAnsi.charAt(i);
            if (c == '\b') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) != '\n' && sb.charAt(len - 1) != '\r') {
                    sb.deleteCharAt(len - 1);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Verifica se o texto coletado já contém ao menos um MAC válido extraível do switch.
     */
    public boolean contemMacExtraivel(String rawOutput) {
        return extrairMelhorMac(rawOutput) != null;
    }

    private static String extrairMelhorMac(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) return null;
        Matcher m1 = MAC_PRIORITARIO_PATTERN.matcher(rawOutput);
        while (m1.find()) {
            String formatado = formatarMacPadrao(m1.group(1)).toUpperCase();
            if (!"0000-0000-0000".equals(formatado)) {
                return formatado;
            }
        }
        Matcher m2 = MAC_FALLBACK_PATTERN.matcher(rawOutput);
        while (m2.find()) {
            String formatado = formatarMacPadrao(m2.group(1)).toUpperCase();
            if (!"0000-0000-0000".equals(formatado)) {
                return formatado;
            }
        }
        return null;
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
        Pattern memberPattern = Pattern.compile("(?i)\\b((?:gigabitethernet|tengigabitethernet|fastethernet|gi|te|fa|ge|xge)\\d+(?:/\\d+)+)\\b");

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
     * Faz o parse da saída combinada ("show version", "show system mac", "show system mpu", etc.)
     * para extrair a identidade do switch Série 3000.
     *
     * Quando o switch está com hostname padrão de fábrica ("INTELBRAS" ou "Switch"),
     * utiliza diretamente o modelo do equipamento (ex: "S3028G-B", "S3028G-PB Max") como deviceName.
     */
    public SwitchIdentity parseDeviceVersion(String ip, String hostnamePadrao, String rawOutput) {
        String deviceName = hostnamePadrao;
        String serial = null;
        String mac = null;
        String productId = null;

        if (rawOutput != null) {
            mac = extrairMelhorMac(rawOutput);

            // 1) Tenta encontrar Modelo completo em campos explícitos (ex: "Hardware Model : Intelbras S3028G-PB Max")
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
            }

            // 2) Fallback: código S30 no banner/imagem (ex: S3028G-B, S3028G-PB Max, S3054G-B)
            if (productId == null) {
                Matcher s30Matcher = Pattern.compile("\\b(S30\\d{2}[A-Z0-9]*(?:-[A-Z0-9]+)*(?:\\s+Max)?)\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(rawOutput);
                if (s30Matcher.find()) {
                    productId = s30Matcher.group(1).trim();
                }
            }

            // Tenta encontrar Serial Number (ex: "Serial No.:   H3TK3900068UN" no show system mpu)
            Pattern snPattern = Pattern.compile(
                    "(?i)^\\s*(?:device\\s+|product\\s+|system\\s+)?(?:serial\\s+number|serial\\s+no\\.?|s/n|sn)\\s*[:=]\\s*(\\S+)",
                    Pattern.MULTILINE
            );
            Matcher snMatcher = snPattern.matcher(rawOutput);
            while (snMatcher.find()) {
                String cand = snMatcher.group(1).trim();
                if (!cand.equalsIgnoreCase("N/A") && !cand.equalsIgnoreCase("NULL")
                        && !cand.equalsIgnoreCase("NONE") && !cand.equals("-") && !cand.equals("--")) {
                    serial = cand;
                    break;
                }
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

        if (productId == null || productId.isBlank() || productId.equalsIgnoreCase("Intelbras")) {
            productId = "Série 3000";
        }

        // Remove hostname genérico de fábrica ("INTELBRAS" / "Switch"), adotando direto o modelo (ex: S3028G-B)
        if (deviceName == null || deviceName.isBlank()
                || deviceName.equalsIgnoreCase("INTELBRAS")
                || deviceName.equalsIgnoreCase("Switch")) {
            deviceName = !"Série 3000".equals(productId) ? productId : ("S30-" + ip);
        }

        if (mac == null || mac.isBlank()) {
            mac = "0000-0000-0000";
        }

        return new SwitchIdentity(deviceName, serial, mac, productId, ip);
    }

    /**
     * Converte notação Cisco/S30 (gi1/0/1, gigabitethernet1/0/1, te1/0/2, agg1) para notação unificada (GE1/0/1, XGE1/0/2, BAGG1).
     */
    public static String normalizarPorta(String porta) {
        if (porta == null) return "";
        String p = porta.trim();
        String lower = p.toLowerCase();
        if (lower.startsWith("gigabitethernet")) {
            return "GE" + p.substring(15);
        } else if (lower.startsWith("tengigabitethernet")) {
            return "XGE" + p.substring(18);
        } else if (lower.startsWith("ten-gigabitethernet")) {
            return "XGE" + p.substring(19);
        } else if (lower.startsWith("fastethernet")) {
            return "FE" + p.substring(12);
        } else if (lower.startsWith("gi")) {
            return "GE" + p.substring(2);
        } else if (lower.matches("^g\\d+/.*")) {
            return "GE" + p.substring(1);
        } else if (lower.startsWith("te")) {
            return "XGE" + p.substring(2);
        } else if (lower.matches("^t\\d+/.*")) {
            return "XGE" + p.substring(1);
        } else if (lower.startsWith("fa")) {
            return "FE" + p.substring(2);
        } else if (lower.matches("^f\\d+/.*")) {
            return "FE" + p.substring(1);
        } else if (lower.startsWith("bridge-aggregation")) {
            return "BAGG" + p.substring(18);
        } else if (lower.startsWith("route-aggregation")) {
            return "BAGG" + p.substring(17);
        } else if (lower.startsWith("agg")) {
            return "BAGG" + p.substring(3);
        } else if (lower.startsWith("port-channel")) {
            return "BAGG" + p.substring(12);
        } else if (lower.startsWith("po") && lower.length() > 2 && Character.isDigit(lower.charAt(2))) {
            return "BAGG" + p.substring(2);
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

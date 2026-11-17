package tpDiscover.intelbras.parser;

import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.LoopDiagnostic;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.StpPortState;
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
 * Parser para a CLI dos switches Intelbras Série 3000, Série 2000 (S2050G-A),
 * S30-Lite (S3028G-PB/B Lite) e linha SG MR L2+ (SG 1002/5204 MR L2+).
 */
public class Serie3000Parser {

    private static final String MAC_HEX_REGEX =
            "([0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}|[0-9a-fA-F]{2}(?:[:\\-][0-9a-fA-F]{2}){5})";

    private static final String IPV4_REGEX =
            "((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})";

    private static final Pattern MAC_PRIORITARIO_PATTERN = Pattern.compile(
            "(?i)(?:intf\\s+mac|mac\\s+address\\s+of\\s+l3|system\\s+mac\\s+address|base\\s+(?:ethernet\\s+)?mac(?:\\s+address)?|burned[\\s\\-]in\\s+mac|(?:ethernet|hardware)\\s+address(?:\\s+is)?|address\\s+is)\\s*[:=,]?\\s*" + MAC_HEX_REGEX,
            Pattern.MULTILINE
    );

    private static final Pattern MAC_FALLBACK_PATTERN = Pattern.compile(
            "(?i)(?:ag\\s+mac|ap\\s+mac|dc0\\s+mac|mac\\s+address\\s+of\\s+(?:ag|ap)|\\bbridge\\s+address|mac\\s+address|system\\s+id)\\s*[:=,]?\\s*" + MAC_HEX_REGEX,
            Pattern.MULTILINE
    );

    private static final Pattern MAC_LOCAL_VLAN_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:\\d+\\s+)?\\d{1,4}\\s+" + MAC_HEX_REGEX + "\\s+(?:(?:static|local|self|dynamic|--|cpu)\\s+)?local\\s+vlan\\d+(?:\\.\\d+)?(?:\\s+\\S+)*\\s*$",
            Pattern.MULTILINE
    );

    private static final Pattern MAC_LINE_VLAN_FIRST = Pattern.compile(
            "^\\s*(?:\\d+\\s+)?" +
            "(\\d{1,4})\\s+" +
            MAC_HEX_REGEX + "\\s+" +
            "(\\S+)\\s+" +
            "(\\S+)" +
            "(?:\\s+\\S+){0,3}\\s*$"
    );

    private static final Pattern MAC_LINE_MAC_FIRST = Pattern.compile(
            "^\\s*(?:\\d+\\s+)?" +
            MAC_HEX_REGEX + "\\s+" +
            "(\\d{1,4})\\s+" +
            "(\\S+)\\s+" +
            "(\\S+)" +
            "(?:\\s+\\S+){0,3}\\s*$"
    );

    private static final Pattern PORTA_FISICA_OU_LAG_TOKEN = Pattern.compile(
            "(?i)^(?:(?:gigabitethernet|tengigabitethernet|ten-?gigabitethernet|fastethernet|ethernet|eth|xge|ge|gi|te|fa|fe|g|t|f|e)\\d+(?:/\\d+)*"
            + "|(?:bridge-aggregation|route-aggregation|port-channel|bagg|agg|po|lag|trunk)\\d+)$"
    );

    private static final Pattern PORTA_NUMERICA = Pattern.compile("^\\d{1,3}$");

    private static final Pattern ESTADO_MAC_TABELA = Pattern.compile(
            "(?i)^(?:dynamic|static|learned|self|local|secure|multicast|filter|mgmt|blackhole|aged)$"
    );

    private static final Pattern DEFAULT_ROUTE_LINE = Pattern.compile(
            "(?i)^.*?(?:0\\.0\\.0\\.0(?:/0|\\s+0\\.0\\.0\\.0)|gateway\\s+of\\s+last\\s+resort\\s+is)\\b.*?\\b" + IPV4_REGEX + "\\b"
    );

    private static final Pattern GATEWAY_LABEL_LINE = Pattern.compile(
            "(?i)^\\s*(?:default[-\\s]*)?gateway\\s*(?:[:=]|\\s+-\\s+)\\s*" + IPV4_REGEX + "\\b"
    );

    private static final Pattern ARP_LINE = Pattern.compile(
            "^\\s*(?:(?:Internet|IP)\\s+)?" + IPV4_REGEX + "(?:\\s+(?:\\d+(?:\\s*(?:s|sec|min))?|-))?\\s+" + MAC_HEX_REGEX + "(?:\\s+(.*))?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WRAPPED_VLAN_PORT = Pattern.compile(
            "(?i)^v\\d+\\(([^)]+)\\)$"
    );

    private static final Pattern S2050_LLDP_HEADER = Pattern.compile(
            "(?i)^>>>\\s*Interface\\s+(\\S+)\\s+neighbor\\b"
    );

    private static final Pattern S2050_LLDP_KV = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9\\s/()-]{1,42})\\s*:\\s*(.*?)\\s*$"
    );

    private static final Pattern S30_LLDP_TABLE_LINE = Pattern.compile(
            "^\\s*(?:\\d+\\s+)?((?:gigabitethernet|x?ge|gi|te|fa|fe|g|t|f)\\d+(?:/\\d+)*)\\s+\\d+\\s+(\\S+)\\s+(\\S+)\\s+(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STP_ROOT_MAC = Pattern.compile(
            "(?i)Root\\s+ID.*?Address\\s+" + MAC_HEX_REGEX,
            Pattern.DOTALL
    );

    private static final Pattern STP_PORT_TABLE_LINE = Pattern.compile(
            "(?i)^\\s*([a-z]+\\d+(?:/\\d+)*)\\s+(Root|Desg|Desi|Alte|Altn|Back|Mast|Disa|Disabled|\\S+)\\s+(FWD|BLK|DISCARDING|FORWARDING|BLOCKING|LEARNING|LISTENING|DISABLED)\\b"
    );

    private static final Pattern S2050_STP_PORT_LINE = Pattern.compile(
            "(?i)^%\\s*([a-z]+\\d+(?:/\\d+)*):\\s*Port\\s+\\d+.*?Role\\s+(\\S+)\\s*-\\s*State\\s*\\(([^)]+)\\)"
    );

    // SG 1002/2404 MR (2013): "1    Enable  128  auto   auto    Disable Auto  RSTP  Desg  Forwarding"
    private static final Pattern SG_MR_STP_PORT_LINE = Pattern.compile(
            "(?i)^\\s*(\\d{1,3})\\s+(Enable|Disable)\\s+\\d+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+(Root|Desg|Desi|Alte|Altn|Back|Mast|Disa|Disabled|Invalid)\\s+(Forwarding|Discarding|Blocking|Learning|Listening|Disabled|FWD|BLK|Invalid)\\s*$"
    );

    private static final Pattern STP_STATUS_DISABLE_LINE = Pattern.compile(
            "(?im)^\\s*status\\s*:\\s*disab(?:le|led)\\b"
    );

    private static final Pattern LOOPBACK_LINE_PORT = Pattern.compile(
            "(?i)^\\s*([a-z]+\\d+(?:/\\d+)*)\\b[:\\s]+(.+)$"
    );

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
                    continue;
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
            boolean portaNumerica = false;
            if (ehTokenDePortaOuInterna(colB) && !ehTokenDePortaOuInterna(colA)) {
                state = colA;
                rawPort = colB;
            } else if (ehTokenDePortaOuInterna(colA) && !ehTokenDePortaOuInterna(colB)) {
                rawPort = colA;
                state = colB;
            } else if (PORTA_NUMERICA.matcher(colA).matches() && ESTADO_MAC_TABELA.matcher(colB).matches()) {
                rawPort = colA;
                state = colB;
                portaNumerica = true;
            } else if (PORTA_NUMERICA.matcher(colB).matches() && ESTADO_MAC_TABELA.matcher(colA).matches()) {
                state = colA;
                rawPort = colB;
                portaNumerica = true;
            } else {
                continue;
            }

            if (ehPortaInterna(rawPort)) {
                continue;
            }
            String port = portaNumerica ? normalizarPortaTabela(rawPort) : normalizarPorta(rawPort);
            String macPadrao = formatarMacPadrao(rawMac);

            entries.add(new MacEntry(switchId, macPadrao, vlan, state, port));
        }

        return entries;
    }

    public Optional<String> parseDefaultGatewayIp(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return Optional.empty();
        }
        String[] linhas = rawOutput.split("\\R");
        Pattern ipv4 = Pattern.compile("\\b" + IPV4_REGEX + "\\b");
        for (String line : linhas) {
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
        for (String line : linhas) {
            Matcher mGw = GATEWAY_LABEL_LINE.matcher(line);
            if (mGw.find()) {
                String cand = mGw.group(1);
                if (!"0.0.0.0".equals(cand)) {
                    return Optional.of(cand);
                }
            }
        }
        return Optional.empty();
    }

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
            String resto = m.group(3);

            String portaFisica = null;
            if (resto != null && !resto.isBlank()) {
                String[] tokens = resto.trim().split("\\s+");
                for (int i = tokens.length - 1; i >= 0; i--) {
                    portaFisica = extrairPortaFisicaArp(tokens[i]);
                    if (portaFisica != null) {
                        break;
                    }
                }
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

        Matcher mWrapped = WRAPPED_VLAN_PORT.matcher(t);
        if (mWrapped.matches()) {
            t = mWrapped.group(1).trim();
        }

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
        return p.equals("cpu")
                || p.equals("self")
                || p.equals("system")
                || p.equals("drop")
                || p.startsWith("null")
                || p.startsWith("vlan");
    }

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

    public boolean contemMacExtraivel(String rawOutput) {
        return extrairMelhorMac(rawOutput) != null;
    }

    public static String extrairMacLocalVlan(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) return null;
        Matcher m = MAC_LOCAL_VLAN_PATTERN.matcher(limparControleTerminal(rawOutput));
        if (m.find()) {
            String fmt = formatarMacPadrao(m.group(1)).toUpperCase();
            if (!"0000-0000-0000".equals(fmt)) {
                return fmt;
            }
        }
        return null;
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
        return extrairMacLocalVlan(rawOutput);
    }

    public Map<String, List<String>> parseLinkAggregation(String rawOutput) {
        Map<String, Set<String>> membrosPorBagg = new LinkedHashMap<>();
        if (rawOutput == null || rawOutput.isBlank()) {
            return Map.of();
        }

        Pattern aggHeader = Pattern.compile(
                "(?i)(?:\\bagg(?:regateport|regator)?|\\bport-aggregator|\\bpo(?:rt-channel)?|\\bbagg|\\blag|\\btrunk|\\btrk|\\bgroup|\\bchannel-group|\\betherchannel)\\s*[:=]?\\s*(\\d+)\\b"
        );
        Pattern tableRowGroup = Pattern.compile("^\\s*(\\d{1,2})\\s+(\\S+)(?:\\s+(.*))?$");
        Pattern memberPattern = Pattern.compile(
                "(?i)\\b((?:gigabitethernet|tengigabitethernet|ten-?gigabitethernet|fastethernet|ethernet|xge|ge|gi|te|fa|fe|g)\\s*\\d+(?:/\\d+)*)\\b"
        );

        boolean tabelaGroupMembers = false;
        String baggAtual = null;
        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            String lowerLine = trimmed.toLowerCase();
            if ((lowerLine.contains("group") && lowerLine.contains("members"))
                    || (lowerLine.contains("group") && lowerLine.contains("port-aggregator"))) {
                tabelaGroupMembers = true;
                continue;
            }
            if (trimmed.startsWith("---")) continue;

            Matcher mHeader = aggHeader.matcher(trimmed);
            if (mHeader.find()) {
                baggAtual = "BAGG" + mHeader.group(1);
                membrosPorBagg.computeIfAbsent(baggAtual, k -> new LinkedHashSet<>());
            } else if (tabelaGroupMembers) {
                Matcher mRow = tableRowGroup.matcher(trimmed);
                if (mRow.matches()) {
                    baggAtual = "BAGG" + mRow.group(1);
                    membrosPorBagg.computeIfAbsent(baggAtual, k -> new LinkedHashSet<>());
                    String restante = mRow.group(2) + (mRow.group(3) != null ? " " + mRow.group(3) : "");
                    adicionarMembrosNumericosLag(membrosPorBagg.get(baggAtual), restante, memberPattern);
                    continue;
                }
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

    private static void adicionarMembrosNumericosLag(Set<String> destino, String texto, Pattern memberPattern) {
        boolean achouPrefixado = false;
        Matcher mPort = memberPattern.matcher(texto);
        while (mPort.find()) {
            destino.add(normalizarPorta(mPort.group(1)));
            achouPrefixado = true;
        }
        if (achouPrefixado) return;

        // Formato "1   1-2,5" de show interface link-aggregation (SG 1002/2404 MR)
        for (String tok : texto.split("[,\\s]+")) {
            String t = tok.trim();
            if (t.matches("^\\d{1,3}$")) {
                destino.add(normalizarPortaTabela(t));
            } else if (t.matches("^(\\d{1,3})-(\\d{1,3})$")) {
                String[] partes = t.split("-");
                int inicio = Integer.parseInt(partes[0]);
                int fim = Integer.parseInt(partes[1]);
                if (inicio >= 1 && fim >= inicio && (fim - inicio) < 64) {
                    for (int p = inicio; p <= fim; p++) {
                        destino.add("GE" + p);
                    }
                }
            }
        }
    }

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
     * Faz o parse da saída de "show lldp neighbor" (S2050G-A / S3028G-PB Lite / S3028G-B Lite).
     */
    public List<LldpNeighbor> parseLldpS2050Lite(String switchId, String rawOutput) {
        List<LldpNeighbor> vizinhos = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) {
            return vizinhos;
        }
        String lower = rawOutput.toLowerCase();
        if (lower.contains("status: disable") || lower.contains("lldp is not enabled")
                || Pattern.compile("\\b0\\s+neighbors?\\b").matcher(lower).find()) {
            return vizinhos;
        }

        String portaLocal = null;
        String chassisId = null;
        String portId = null;
        String portDesc = null;
        String sysName = null;
        String sysDesc = null;
        String mgmtIp = null;
        Set<String> caps = new LinkedHashSet<>();

        for (String line : limparControleTerminal(rawOutput).split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.equals("---\\") || trimmed.equals("---/")) {
                continue;
            }

            Matcher mHead = S2050_LLDP_HEADER.matcher(trimmed);
            if (mHead.find()) {
                if (portaLocal != null && (chassisId != null || sysName != null)) {
                    vizinhos.add(criarNeighbor(switchId, portaLocal, chassisId, portId, portDesc, sysName, sysDesc, mgmtIp, caps));
                }
                portaLocal = normalizarPorta(mHead.group(1));
                chassisId = null;
                portId = null;
                portDesc = null;
                sysName = null;
                sysDesc = null;
                mgmtIp = null;
                caps = new LinkedHashSet<>();
                continue;
            }

            Matcher mKv = S2050_LLDP_KV.matcher(line);
            if (!mKv.matches()) continue;

            String k = mKv.group(1).trim().toLowerCase();
            String v = mKv.group(2).trim();

            if (k.equals("local port") || k.equals("local interface")) {
                if (portaLocal != null && (chassisId != null || sysName != null)) {
                    vizinhos.add(criarNeighbor(switchId, portaLocal, chassisId, portId, portDesc, sysName, sysDesc, mgmtIp, caps));
                }
                portaLocal = normalizarPorta(v);
                chassisId = null;
                portId = null;
                portDesc = null;
                sysName = null;
                sysDesc = null;
                mgmtIp = null;
                caps = new LinkedHashSet<>();
                continue;
            }

            if (portaLocal == null) continue;

            if (k.equals("chassis id")) {
                chassisId = v;
            } else if (k.equals("port id")) {
                portId = v;
            } else if (k.equals("port description")) {
                portDesc = v;
            } else if (k.equals("system name")) {
                sysName = v;
            } else if (k.equals("system description")) {
                sysDesc = v;
            } else if (k.equals("management address") && mgmtIp == null) {
                if (v.matches("^" + IPV4_REGEX + "$")) {
                    mgmtIp = v;
                }
            } else if (v.toLowerCase().contains("enable")) {
                if (k.contains("mac bridge")) caps.add("Bridge");
                else if (k.equals("router")) caps.add("Router");
                else if (k.contains("wlan")) caps.add("WLAN Access Point");
                else if (k.contains("telephone")) caps.add("Telephone");
                else if (k.contains("station")) caps.add("Station Only");
                else if (k.contains("repeater")) caps.add("Repeater");
            }
        }

        if (portaLocal != null && (chassisId != null || sysName != null)) {
            vizinhos.add(criarNeighbor(switchId, portaLocal, chassisId, portId, portDesc, sysName, sysDesc, mgmtIp, caps));
        }

        return vizinhos;
    }

    /**
     * Faz o parse da saída de "show lldp neighbors detail" ou "show lldp neighbors" (S30 / SG MR L2+).
     */
    public List<LldpNeighbor> parseLldpSerie3000(String switchId, String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return List.of();
        }
        String limpo = limparControleTerminal(rawOutput);
        String lower = limpo.toLowerCase();
        if (lower.contains("global status of lldp           : disable")
                || lower.contains("global status of lldp: disable")
                || lower.contains("lldp is not enabled")
                || Pattern.compile("\\b0\\s+neighbor\\s+entries\\b").matcher(lower).find()
                || lower.contains("total entries displayed: 0")) {
            return List.of();
        }

        if (lower.contains(">>> interface")) {
            return parseLldpS2050Lite(switchId, limpo);
        }

        List<LldpNeighbor> vizinhos = new ArrayList<>();
        // Tenta blocos detalhados (Local Intf / Port name / Chassis ID / System Name / Management Address)
        String localPort = null;
        String chassisId = null;
        String portId = null;
        String portDesc = null;
        String sysName = null;
        String sysDesc = null;
        String mgmtIp = null;
        Set<String> caps = new LinkedHashSet<>();

        for (String line : limpo.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher mTable = S30_LLDP_TABLE_LINE.matcher(trimmed);
            if (mTable.matches()) {
                String lp = normalizarPorta(mTable.group(1));
                String capRaw = mTable.group(2).trim();
                String peerPort = mTable.group(3).trim();
                String devId = mTable.group(4).trim();
                Set<String> tCaps = decodificarSiglasCapability(capRaw);
                String macNorm = ComwareParser.extrairMacNormalizadoDeChassis(devId);
                vizinhos.add(criarNeighbor(switchId, lp, devId, peerPort, null, macNorm.isEmpty() ? devId : null, null, null, tCaps));
                continue;
            }

            Matcher mKv = S2050_LLDP_KV.matcher(line);
            if (!mKv.matches()) continue;
            String k = mKv.group(1).trim().toLowerCase();
            String v = mKv.group(2).trim();

            if (k.equals("local intf") || k.equals("local interface") || k.equals("local port")) {
                if (localPort != null && (chassisId != null || sysName != null)) {
                    vizinhos.add(criarNeighbor(switchId, localPort, chassisId, portId, portDesc, sysName, sysDesc, mgmtIp, caps));
                    chassisId = null; portId = null; portDesc = null; sysName = null; sysDesc = null; mgmtIp = null; caps = new LinkedHashSet<>();
                }
                localPort = normalizarPorta(v);
            } else if (localPort != null) {
                if (k.equals("chassis id") || k.equals("device id")) chassisId = v;
                else if (k.equals("port id") || k.equals("peer intf")) portId = v;
                else if (k.equals("port description")) portDesc = v;
                else if (k.equals("system name")) sysName = v;
                else if (k.equals("system description")) sysDesc = v;
                else if ((k.equals("management address") || k.equals("ip address")) && mgmtIp == null && v.matches("^" + IPV4_REGEX + "$")) mgmtIp = v;
                else if (k.contains("capabilities")) {
                    for (String c : v.split("[,\\s]+")) {
                        if (!c.isBlank()) caps.add(c.trim());
                    }
                }
            }
        }

        if (localPort != null && (chassisId != null || sysName != null)) {
            vizinhos.add(criarNeighbor(switchId, localPort, chassisId, portId, portDesc, sysName, sysDesc, mgmtIp, caps));
        }

        return vizinhos;
    }

    private static Set<String> decodificarSiglasCapability(String raw) {
        Set<String> caps = new LinkedHashSet<>();
        if (raw == null) return caps;
        String u = raw.toUpperCase();
        if (u.contains("R")) caps.add("Router");
        if (u.contains("B")) caps.add("Bridge");
        if (u.contains("W")) caps.add("WLAN Access Point");
        if (u.contains("T")) caps.add("Telephone");
        if (u.contains("S")) caps.add("Station Only");
        if (u.contains("P")) caps.add("Repeater");
        return caps;
    }

    private static LldpNeighbor criarNeighbor(
            String switchId,
            String localPort,
            String chassisId,
            String portId,
            String portDesc,
            String sysName,
            String sysDesc,
            String mgmtIp,
            Set<String> caps
    ) {
        String macNorm = ComwareParser.extrairMacNormalizadoDeChassis(chassisId);
        String remotePort = ComwareParser.resolverMelhorPortaRemota(portId, portDesc);
        return new LldpNeighbor(switchId, localPort, chassisId, macNorm, remotePort, portDesc, sysName, sysDesc, mgmtIp, Set.copyOf(caps));
    }

    /**
     * Faz o parse de "show spanning-tree" / "show spanning-tree brief" / "show spanning-tree port-config".
     */
    public List<StpPortState> parseSpanningTree(String switchId, String rawOutput) {
        List<StpPortState> estados = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) {
            return estados;
        }
        String limpo = limparControleTerminal(rawOutput);
        String lower = limpo.toLowerCase();
        if (lower.contains("spanning tree disabled")
                || lower.contains("spanning-tree is disabled")
                || lower.contains("stp is disabled")
                || STP_STATUS_DISABLE_LINE.matcher(limpo).find()) {
            estados.add(new StpPortState(switchId, "*", "DISABLED", "DISABLED", false, null));
            return estados;
        }

        String rootMac = null;
        Matcher mRoot = STP_ROOT_MAC.matcher(limpo);
        if (mRoot.find()) {
            rootMac = formatarMacPadrao(mRoot.group(1));
        }

        for (String line : limpo.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher mTable = STP_PORT_TABLE_LINE.matcher(trimmed);
            if (mTable.find()) {
                estados.add(new StpPortState(
                        switchId,
                        normalizarPorta(mTable.group(1)),
                        mTable.group(2).toUpperCase(),
                        mTable.group(3).toUpperCase(),
                        true,
                        rootMac
                ));
                continue;
            }

            Matcher mS2050 = S2050_STP_PORT_LINE.matcher(trimmed);
            if (mS2050.find()) {
                estados.add(new StpPortState(
                        switchId,
                        normalizarPorta(mS2050.group(1)),
                        mS2050.group(2).toUpperCase(),
                        mS2050.group(3).toUpperCase(),
                        true,
                        rootMac
                ));
                continue;
            }

            Matcher mSgMr = SG_MR_STP_PORT_LINE.matcher(trimmed);
            if (mSgMr.find()) {
                boolean habilitada = "enable".equalsIgnoreCase(mSgMr.group(2));
                String role = mSgMr.group(3).toUpperCase();
                String state = mSgMr.group(4).toUpperCase();
                if (!habilitada && "INVALID".equals(state)) {
                    continue;
                }
                estados.add(new StpPortState(
                        switchId,
                        normalizarPortaTabela(mSgMr.group(1)),
                        role,
                        state,
                        habilitada,
                        rootMac
                ));
            }
        }
        return estados;
    }

    /**
     * Detecta loops ativos reportados por "show loopback-detection"
     * (SG MR L2+, Série 3000 e linha S-Series: SG 1002/1602/2404 PoE).
     */
    public List<LoopDiagnostic> parseLoopbackDetection(String switchId, String rawOutput) {
        List<LoopDiagnostic> achados = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) return achados;
        String limpo = limparControleTerminal(rawOutput);
        String lower = limpo.toLowerCase();
        if (lower.contains("loopback-detection is disable")
                || Pattern.compile("(?is)loopback[-\\s]+detection\\s*:\\s*disabl").matcher(limpo).find()) {
            return achados;
        }

        for (String line : limpo.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            Matcher m = LOOPBACK_LINE_PORT.matcher(trimmed);
            if (!m.find()) continue;

            if (linhaIndicaLoopAtivo(m.group(2))) {
                String porta = normalizarPorta(m.group(1));
                achados.add(new LoopDiagnostic(
                        LoopDiagnostic.Severidade.CRITICO,
                        LoopDiagnostic.TipoLoop.LOOPBACK_HARDWARE,
                        switchId,
                        porta,
                        switchId,
                        porta,
                        "Loopback-detection ativo na porta " + porta + " (" + trimmed + ")",
                        "Desconectar cabo em loop ou verificar switch conectado na porta " + porta
                ));
            }
        }
        return achados;
    }

    private static boolean linhaIndicaLoopAtivo(String restoLinha) {
        if (restoLinha == null || restoLinha.isBlank()) return false;
        String[] cols = restoLinha.trim().split("\\s+");
        if (cols.length == 0) return false;

        String col1 = cols[0].toLowerCase().replaceAll("[:;,!]+$", "");
        // Formato SG MR L2+ ("g0/1 no-loop 0180.c200.000a WARNING/BLOCK"): col1 == "no-loop" garante ausência de loop
        if (col1.equals("no-loop") || col1.equals("noloop") || col1.equals("normal")) {
            return false;
        }
        // Formato S-Series ("gi0/1 enabled NORMAL/LOOP"): inspeciona coluna Result (cols[1])
        if (col1.equals("enabled") || col1.equals("disabled") || col1.equals("enable") || col1.equals("disable")) {
            if (cols.length < 2) return false;
            String result = cols[1].toLowerCase();
            if (result.equals("normal") || result.equals("no-loop") || result.equals("none") || result.equals("ok")) {
                return false;
            }
            return contemPalavraDeAlertaLoop(result);
        }
        if (contemPalavraDeAlertaLoop(col1)) {
            return true;
        }
        String fullLower = restoLinha.toLowerCase();
        return !fullLower.contains("no-loop") && contemPalavraDeAlertaLoop(fullLower);
    }

    private static boolean contemPalavraDeAlertaLoop(String textoLower) {
        return textoLower.contains("loop")
                || textoLower.contains("block")
                || textoLower.contains("err")
                || textoLower.contains("shut")
                || textoLower.contains("discard")
                || textoLower.contains("detect");
    }

    public SwitchIdentity parseDeviceVersion(String ip, String hostnamePadrao, String rawOutput) {
        String deviceName = hostnamePadrao;
        String serial = null;
        String mac = null;
        String productId = null;

        if (rawOutput != null) {
            mac = extrairMelhorMac(rawOutput);

            // 0) Formato inline de "show system" (S2050G-A / S3028G-PB Lite): Product Name:"S2050G-A"
            Matcher inlineProduct = Pattern.compile("(?i)\\bProduct\\s+Name\\s*:\\s*\"([^\"]+)\"").matcher(rawOutput);
            if (inlineProduct.find()) {
                String cand = inlineProduct.group(1).replaceFirst("(?i)^intelbras\\s*(?:switch\\s*)?", "").trim();
                if (!cand.isEmpty() && !cand.equalsIgnoreCase("Intelbras")) {
                    productId = cand;
                }
            }

            // 1) Campos explícitos em linha dedicada (Hardware Model : Intelbras S3028G-PB Max)
            if (productId == null) {
                Pattern modelPattern = Pattern.compile(
                        "(?i)^\\s*(?:hardware\\s+model|model(?:\\s+name)?|device\\s+type|product(?:\\s+name|\\s+id)?|system\\s+type|system\\s+description)\\s*(?:[:=]|\\s+-\\s+)\\s*(.+?)\\s*$",
                        Pattern.MULTILINE
                );
                Matcher modelMatcher = modelPattern.matcher(rawOutput);
                while (modelMatcher.find()) {
                    String candidate = modelMatcher.group(1).replace("\"", "").trim();
                    String semFabricante = candidate.replaceFirst("(?i)^intelbras\\s*(?:switch\\s*)?", "").trim();
                    if (!semFabricante.isEmpty() && !semFabricante.equalsIgnoreCase("Intelbras")) {
                        productId = semFabricante;
                        break;
                    }
                }
            }

            // 2) Fallback por código de modelo no texto/banner (S30xx, S20xx, S21xx, SG xxxx)
            if (productId == null || productId.toLowerCase().contains("switch gerenci")) {
                Matcher modeloMatcher = Pattern.compile(
                        "\\b(SG\\s*\\d{4}[A-Z0-9]*(?:\\s+MR)?(?:\\s+L2\\+)?(?:\\s+PoE(?:\\s*Max|\\s*L2\\+)?)?|S(?:30|20|21)\\d{2}[A-Z0-9]*(?:-[A-Z0-9]+)*(?:\\s+(?:Max|Lite))?)\\b",
                        Pattern.CASE_INSENSITIVE
                ).matcher(rawOutput);
                if (modeloMatcher.find()) {
                    productId = modeloMatcher.group(1).trim().replaceAll("\\s+", " ");
                }
            }

            // Serial Number ("Serial No.: ...", "Serial num:G34H3100152TQ, ...")
            Pattern snPattern = Pattern.compile(
                    "(?i)(?:^\\s*|\\b)(?:device\\s+|product\\s+|system\\s+)?(?:serial\\s+number|serial\\s+no\\.?|serial\\s+num\\.?|s/n|sn)\\s*(?:[:=]|\\s+-\\s+)\\s*([^,\\s;]+)",
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

            Pattern namePattern = Pattern.compile(
                    "(?i)^\\s*(?:device\\s+name|system\\s+name|hostname|sysname)\\s*(?:[:=]|\\s+-\\s+)\\s*([^\\r\\n]+?)\\s*$",
                    Pattern.MULTILINE
            );
            Matcher nameMatcher = namePattern.matcher(rawOutput);
            if (nameMatcher.find()) {
                String candName = nameMatcher.group(1).replace("\"", "").trim();
                if (!candName.isEmpty()) {
                    deviceName = candName;
                }
            } else {
                Matcher promptMatcher = Pattern.compile("(?m)^([a-zA-Z0-9_\\-.]+)[>#]\\s*$").matcher(rawOutput);
                if (promptMatcher.find()) {
                    deviceName = promptMatcher.group(1);
                }
            }
        }

        if (productId == null || productId.isBlank() || productId.equalsIgnoreCase("Intelbras")) {
            productId = "Série 3000";
        }

        if (deviceName == null || deviceName.isBlank()
                || deviceName.equalsIgnoreCase("INTELBRAS")
                || deviceName.equalsIgnoreCase("Switch")) {
            deviceName = !"Série 3000".equals(productId) ? productId : ("S30-" + ip);
        }

        if (mac != null && mac.isBlank()) {
            mac = null;
        }

        return new SwitchIdentity(deviceName, serial, mac, productId, ip);
    }

    /**
     * Converte notação Cisco/S30/S2050/SG (gi1/0/1, g0/7, ge30, GigabitEthernet 1/1, agg1)
     * para notação unificada (GE1/0/1, GE0/7, GE30, GE1/1, BAGG1).
     */
    public static String normalizarPorta(String porta) {
        if (porta == null) return "";
        String p = porta.trim().replaceFirst("(?i)^(gigabitethernet|tengigabitethernet|ten-gigabitethernet|fastethernet|ethernet)\\s+", "$1");
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
        } else if (lower.matches("^g\\d+(?:/.*)?$")) {
            return "GE" + p.substring(1);
        } else if (lower.startsWith("te")) {
            return "XGE" + p.substring(2);
        } else if (lower.matches("^t\\d+(?:/.*)?$")) {
            return "XGE" + p.substring(1);
        } else if (lower.startsWith("fa")) {
            return "FE" + p.substring(2);
        } else if (lower.matches("^f\\d+(?:/.*)?$")) {
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
        } else if (lower.startsWith("lag") && lower.length() > 3 && Character.isDigit(lower.charAt(3))) {
            return "BAGG" + p.substring(3);
        } else if (lower.startsWith("trunk") && lower.length() > 5 && Character.isDigit(lower.charAt(5))) {
            return "BAGG" + p.substring(5);
        } else if (lower.startsWith("trk") && lower.length() > 3 && Character.isDigit(lower.charAt(3))) {
            return "BAGG" + p.substring(3);
        }
        return p.toUpperCase();
    }

    private static String normalizarPortaTabela(String token) {
        if (token != null && PORTA_NUMERICA.matcher(token.trim()).matches()) {
            return "GE" + token.trim();
        }
        return normalizarPorta(token);
    }

    public static String formatarMacPadrao(String mac) {
        if (mac == null) return "";
        String clean = mac.toLowerCase().replace("-", "").replace(":", "").replace(".", "");
        if (clean.length() == 12) {
            return clean.substring(0, 4) + "-" + clean.substring(4, 8) + "-" + clean.substring(8, 12);
        }
        return mac.toLowerCase();
    }
}

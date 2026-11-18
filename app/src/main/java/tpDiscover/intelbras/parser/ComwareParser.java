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

import tpDiscover.intelbras.util.Compat;
/**
 * Parser para a saída de CLI dos switches Intelbras linha S23/S33 (firmware Comware).
 *
 * Cobre os comandos:
 *  - display device manuinfo                  -> identidade do switch
 *  - display mac-address                      -> tabela MAC
 *  - display link-aggregation verbose         -> portas físicas que compõem cada BAGG
 *  - display ip routing-table / arp           -> descoberta automática do roteador/gateway da rede
 *  - display lldp neighbor-information ...    -> vizinhos LLDP
 *  - display stp brief / loopback-detection   -> diagnóstico de loops e estado STP
 */
public class ComwareParser {

    private static final String IPV4_REGEX =
            "((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})";

    private static final Pattern MAC_LINE = Pattern.compile(
            "^([0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4})\\s+" +
            "(\\d+)\\s+" +
            "(\\S+)\\s+" +
            "(\\S+)\\s+" +
            "(\\S+)\\s*$"
    );

    private static final Pattern BAGG_HEADER = Pattern.compile(
            "(?i)^\\s*Aggregate\\s+Interface\\s*:\\s*(?:Bridge-Aggregation|Route-Aggregation|BAGG)(\\d+)\\b"
    );

    private static final Pattern BAGG_MEMBER_PORT = Pattern.compile(
            "^\\s*((?:X?GE|GigabitEthernet|Ten-GigabitEthernet|Ethernet)\\d+(?:/\\d+)+)\\s+([SUI])\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DEFAULT_ROUTE_LINE = Pattern.compile(
            "^\\s*0\\.0\\.0\\.0(?:/0|\\s+0\\.0\\.0\\.0)\\b.*"
    );

    private static final Pattern ARP_LINE = Pattern.compile(
            "^\\s*" + IPV4_REGEX + "\\s+([0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4})(?:\\s+\\S+)?(?:\\s+(\\S+))?.*$"
    );

    // LLDP verbose: "LLDP neighbor-information of port 3[GigabitEthernet1/0/3]:"
    private static final Pattern LLDP_VERBOSE_PORT_HEADER = Pattern.compile(
            "(?i)^LLDP\\s+neighbor-information\\s+of\\s+port\\s+\\d+\\[([^\\]]+)\\]\\s*:"
    );
    private static final Pattern LLDP_VERBOSE_KV = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9\\s/()-]{1,45})\\s*:\\s*(.*?)\\s*$"
    );
    // LLDP list: "GE1/0/3         8085-4407-b35c  GigabitEthernet1/0/24      NOMEDOPRODUTO"
    private static final Pattern LLDP_LIST_LINE = Pattern.compile(
            "^\\s*((?:X?GE|GigabitEthernet|Ten-GigabitEthernet|FE|FastEthernet|Ethernet)\\d+(?:/\\d+)*)\\s+" +
            "(\\S+)\\s+" +
            "(\\S+)" +
            "(?:\\s+(.+?))?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    // STP brief: " 0        GigabitEthernet1/0/3                DESI  FORWARDING  NONE"
    private static final Pattern STP_BRIEF_LINE = Pattern.compile(
            "(?i)^\\s*\\d+\\s+(\\S+)\\s+(DESI|ROOT|ALTE|BACK|MAST|DISA|NONE|\\S+)\\s+(FORWARDING|DISCARDING|LEARNING|LISTENING|DISABLED)\\s+\\S+\\s*$"
    );

    private static final Pattern LOOPBACK_PORT_LINE = Pattern.compile(
            "(?i)\\b((?:X?GE|GigabitEthernet|Ten-GigabitEthernet|Ethernet|Bridge-Aggregation|BAGG)\\d+(?:/\\d+)*)\\b.*\\b(loop|detected|blocked|shutdown|discarding)\\b"
    );

    public List<MacEntry> parseMacAddressTable(String switchId, String rawOutput) {
        List<MacEntry> entries = new ArrayList<>();
        if (rawOutput == null) return entries;

        for (String line : rawOutput.split("\\R")) {
            String trimmed = Compat.strip(line);
            if (trimmed.isEmpty()) continue;

            Matcher m = MAC_LINE.matcher(trimmed);
            if (!m.matches()) {
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

    public Optional<String> parseDefaultGatewayIp(String rawOutput) {
        if (rawOutput == null || Compat.isBlank(rawOutput)) {
            return Optional.empty();
        }
        Pattern ipv4 = Pattern.compile("\\b" + IPV4_REGEX + "\\b");
        for (String line : rawOutput.split("\\R")) {
            String trimmed = Compat.strip(line);
            if (trimmed.isEmpty()) continue;
            if (DEFAULT_ROUTE_LINE.matcher(trimmed).matches()) {
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

    public List<ArpEntry> parseArpTable(String rawOutput) {
        List<ArpEntry> entries = new ArrayList<>();
        if (rawOutput == null || Compat.isBlank(rawOutput)) {
            return entries;
        }

        for (String line : rawOutput.split("\\R")) {
            String trimmed = Compat.strip(line);
            if (trimmed.isEmpty()) continue;

            Matcher m = ARP_LINE.matcher(trimmed);
            if (!m.matches()) continue;

            String ip = m.group(1);
            String mac = Serie3000Parser.formatarMacPadrao(m.group(2));
            String rawPort = m.group(3);
            String portaFisica = extrairPortaFisicaArpComware(rawPort);

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

    private static String extrairPortaFisicaArpComware(String token) {
        if (token == null || Compat.isBlank(token)) return null;
        String t = token.trim();
        String lower = t.toLowerCase();
        if (lower.startsWith("vlan") || lower.startsWith("inloop") || lower.startsWith("null") || t.equals("--") || t.equals("N/A")) {
            return null;
        }
        if (lower.matches("^(?:x?ge|gigabitethernet|ten-gigabitethernet|ethernet|bagg|bridge-aggregation|route-aggregation)\\d+.*")) {
            return normalizarNomePortaComware(t);
        }
        return null;
    }

    public Map<String, List<String>> parseLinkAggregation(String rawOutput) {
        Map<String, Set<String>> membrosPorBagg = new LinkedHashMap<>();
        if (rawOutput == null || Compat.isBlank(rawOutput)) {
            return Compat.mapOf();
        }

        String baggAtual = null;
        boolean emSecaoRemota = false;

        for (String line : rawOutput.split("\\R")) {
            String trimmed = Compat.strip(line);
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
                resultado.put(bagg, Compat.listCopyOf(portas));
            }
        });
        return resultado;
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
     * Faz o parse da saída de "display lldp neighbor-information verbose" (ou detalhada).
     */
    public List<LldpNeighbor> parseLldpNeighborsVerbose(String switchId, String rawOutput) {
        List<LldpNeighbor> vizinhos = new ArrayList<>();
        if (rawOutput == null || Compat.isBlank(rawOutput) || rawOutput.toLowerCase().contains("global status of lldp: disable")) {
            return vizinhos;
        }

        String portaLocalAtual = null;
        String chassisId = null;
        String portId = null;
        String portDesc = null;
        String sysName = null;
        StringBuilder sysDesc = new StringBuilder();
        String mgmtIp = null;
        Set<String> capabilities = new LinkedHashSet<>();
        boolean lendoSysDesc = false;
        boolean temBlocoAtivo = false;

        for (String line : rawOutput.split("\\R")) {
            String trimmed = Compat.strip(line);
            if (trimmed.isEmpty()) {
                lendoSysDesc = false;
                continue;
            }

            Matcher mHeader = LLDP_VERBOSE_PORT_HEADER.matcher(trimmed);
            if (mHeader.find()) {
                if (temBlocoAtivo && portaLocalAtual != null && (chassisId != null || sysName != null)) {
                    vizinhos.add(criarLldpNeighbor(switchId, portaLocalAtual, chassisId, portId, portDesc,
                            sysName, sysDesc.toString().trim(), mgmtIp, capabilities));
                }
                portaLocalAtual = normalizarNomePortaComware(mHeader.group(1));
                chassisId = null;
                portId = null;
                portDesc = null;
                sysName = null;
                sysDesc.setLength(0);
                mgmtIp = null;
                capabilities = new LinkedHashSet<>();
                lendoSysDesc = false;
                temBlocoAtivo = true;
                continue;
            }

            if (!temBlocoAtivo) {
                continue;
            }

            if (trimmed.toLowerCase().startsWith("lldp neighbor index")) {
                if (chassisId != null || sysName != null) {
                    vizinhos.add(criarLldpNeighbor(switchId, portaLocalAtual, chassisId, portId, portDesc,
                            sysName, sysDesc.toString().trim(), mgmtIp, capabilities));
                    chassisId = null;
                    portId = null;
                    portDesc = null;
                    sysName = null;
                    sysDesc.setLength(0);
                    mgmtIp = null;
                    capabilities = new LinkedHashSet<>();
                }
                lendoSysDesc = false;
                continue;
            }

            Matcher mKv = LLDP_VERBOSE_KV.matcher(line);
            if (mKv.matches()) {
                lendoSysDesc = false;
                String chave = mKv.group(1).trim().toLowerCase();
                String val = mKv.group(2).trim();

                if (chave.equals("chassis id")) {
                    chassisId = val;
                } else if (chave.equals("port id")) {
                    portId = val;
                } else if (chave.equals("port description")) {
                    portDesc = val;
                } else if (chave.equals("system name")) {
                    sysName = val;
                } else if (chave.equals("system description")) {
                    if (!val.isEmpty()) {
                        sysDesc.append(val);
                    }
                    lendoSysDesc = true;
                } else if (chave.equals("system capabilities enabled") || chave.equals("capabilities")) {
                    for (String cap : val.split(",")) {
                        String c = cap.trim();
                        if (!c.isEmpty()) capabilities.add(c);
                    }
                } else if (chave.equals("management address") && mgmtIp == null) {
                    if (val.matches("^" + IPV4_REGEX + "$")) {
                        mgmtIp = val;
                    }
                }
            } else if (lendoSysDesc && line.startsWith(" ")) {
                if (sysDesc.length() > 0) sysDesc.append("\n");
                sysDesc.append(trimmed);
            }
        }

        if (temBlocoAtivo && portaLocalAtual != null && (chassisId != null || sysName != null)) {
            vizinhos.add(criarLldpNeighbor(switchId, portaLocalAtual, chassisId, portId, portDesc,
                    sysName, sysDesc.toString().trim(), mgmtIp, capabilities));
        }

        return vizinhos;
    }

    /**
     * Faz o parse da tabela resumida "display lldp neighbor-information list".
     */
    public List<LldpNeighbor> parseLldpNeighborsList(String switchId, String rawOutput) {
        List<LldpNeighbor> vizinhos = new ArrayList<>();
        if (rawOutput == null || Compat.isBlank(rawOutput) || rawOutput.toLowerCase().contains("global status of lldp: disable")) {
            return vizinhos;
        }
        for (String line : rawOutput.split("\\R")) {
            String trimmed = Compat.strip(line);
            if (trimmed.isEmpty()) continue;
            Matcher m = LLDP_LIST_LINE.matcher(trimmed);
            if (!m.matches()) continue;
            String localPort = normalizarNomePortaComware(m.group(1));
            String chassis = m.group(2).trim();
            String remotePortRaw = m.group(3).trim();
            String sysName = m.group(4) != null ? m.group(4).trim() : null;
            vizinhos.add(criarLldpNeighbor(switchId, localPort, chassis, remotePortRaw, null, sysName, null, null, Compat.setOf()));
        }
        return vizinhos;
    }

    private static LldpNeighbor criarLldpNeighbor(
            String switchId,
            String localPort,
            String chassisId,
            String portId,
            String portDesc,
            String sysName,
            String sysDesc,
            String mgmtIp,
            Set<String> capabilities
    ) {
        String macNorm = extrairMacNormalizadoDeChassis(chassisId);
        String remotePort = resolverMelhorPortaRemota(portId, portDesc);
        return new LldpNeighbor(
                switchId,
                localPort,
                chassisId,
                macNorm,
                remotePort,
                portDesc,
                sysName,
                sysDesc,
                mgmtIp,
                Compat.setCopyOf(capabilities)
        );
    }

    static String extrairMacNormalizadoDeChassis(String chassisId) {
        if (chassisId == null || Compat.isBlank(chassisId)) return "";
        String norm = MacUtils.normalizar(chassisId);
        return MacUtils.ehMacValido(norm) ? norm : "";
    }

    public static String resolverMelhorPortaRemota(String portId, String portDesc) {
        String cand = (portId != null && !Compat.isBlank(portId)) ? portId.trim() : null;
        if (cand != null && !cand.matches("^\\d+$")) {
            return Serie3000Parser.normalizarPorta(cand);
        }
        if (portDesc != null && !Compat.isBlank(portDesc)) {
            return Serie3000Parser.normalizarPorta(portDesc.replaceFirst("(?i)\\s+Interface$", "").trim());
        }
        return cand != null ? cand : "?";
    }

    /**
     * Faz o parse da saída de "display stp brief".
     */
    public List<StpPortState> parseStpBrief(String switchId, String rawOutput) {
        List<StpPortState> estados = new ArrayList<>();
        if (rawOutput == null || Compat.isBlank(rawOutput)) {
            return estados;
        }
        if (rawOutput.toLowerCase().contains("stp is not enabled")
                || rawOutput.toLowerCase().contains("spanning-tree is disabled")) {
            estados.add(new StpPortState(switchId, "*", "DISABLED", "DISABLED", false, null));
            return estados;
        }
        for (String line : rawOutput.split("\\R")) {
            String trimmed = Compat.strip(line);
            if (trimmed.isEmpty()) continue;
            Matcher m = STP_BRIEF_LINE.matcher(trimmed);
            if (!m.matches()) continue;
            String porta = normalizarNomePortaComware(m.group(1));
            String role = m.group(2).toUpperCase();
            String state = m.group(3).toUpperCase();
            estados.add(new StpPortState(switchId, porta, role, state, true, null));
        }
        return estados;
    }

    /**
     * Detecta alertas ativos em "display loopback-detection".
     */
    public List<LoopDiagnostic> parseLoopbackDetection(String switchId, String rawOutput) {
        List<LoopDiagnostic> achados = new ArrayList<>();
        if (rawOutput == null || Compat.isBlank(rawOutput)) return achados;
        String lower = rawOutput.toLowerCase();
        if (lower.contains("no loop is detected") || lower.contains("not enabled")) {
            return achados;
        }
        for (String line : rawOutput.split("\\R")) {
            String trimmed = Compat.strip(line);
            if (trimmed.isEmpty()) continue;
            Matcher m = LOOPBACK_PORT_LINE.matcher(trimmed);
            if (m.find()) {
                String porta = normalizarNomePortaComware(m.group(1));
                achados.add(new LoopDiagnostic(
                        LoopDiagnostic.Severidade.CRITICO,
                        LoopDiagnostic.TipoLoop.LOOPBACK_HARDWARE,
                        switchId,
                        porta,
                        switchId,
                        porta,
                        "Hardware loopback-detection acusou loop: " + trimmed,
                        "Inspecionar cabeamento ou switch não gerenciável conectado na porta " + porta
                ));
            }
        }
        return achados;
    }

    public static String normalizarNomePortaComware(String porta) {
        String p = porta.trim();
        if (p.regionMatches(true, 0, "GigabitEthernet", 0, 15)) {
            return "GE" + p.substring(15);
        }
        if (p.regionMatches(true, 0, "Ten-GigabitEthernet", 0, 19)) {
            return "XGE" + p.substring(19);
        }
        if (p.regionMatches(true, 0, "FastEthernet", 0, 12)) {
            return "FE" + p.substring(12);
        }
        if (p.regionMatches(true, 0, "Bridge-Aggregation", 0, 18)) {
            return "BAGG" + p.substring(18);
        }
        if (p.regionMatches(true, 0, "Route-Aggregation", 0, 17)) {
            return "BAGG" + p.substring(17);
        }
        return p.toUpperCase();
    }

    public SwitchIdentity parseDeviceManuInfo(String ip, String rawOutput) {
        String deviceName = extrairCampo(rawOutput, "DEVICE_NAME");
        String serial = extrairCampo(rawOutput, "DEVICE_SERIAL_NUMBER");
        String macBruto = extrairCampo(rawOutput, "MAC_ADDRESS");
        String mac = MacUtils.ehMacValido(MacUtils.normalizar(macBruto)) ? macBruto : null;
        String productId = extrairCampo(rawOutput, "PRODUCT_ID");

        return new SwitchIdentity(deviceName, serial, mac, productId, ip);
    }

    private String extrairCampo(String rawOutput, String campo) {
        Pattern p = Pattern.compile("^\\s*" + Pattern.quote(campo) + "\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);
        Matcher m = p.matcher(rawOutput);
        return m.find() ? m.group(1) : null;
    }
}

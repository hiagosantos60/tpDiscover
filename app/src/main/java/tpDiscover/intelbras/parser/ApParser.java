package tpDiscover.intelbras.parser;

import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
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
 * Parser para Access Points corporativos da linha legado/ZeusOS/OpenWrt da Intelbras
 * (ex: AP 3000 AX, AP 1800 AX, AP 1350 AC-S, AP 1210 AC, AP 360, AP 310).
 *
 * Comandos somente-leitura utilizados na CLI (klish / BusyBox):
 *  - ubus call system board     -> modelo, hostname e versão de firmware
 *  - ifconfig                   -> MAC-base (HWaddr de br-lan / eth0)
 *  - brctl show                 -> bridges ativas (ex: br-lan, br-vlan...) e interfaces/VLANs
 *  - brctl showmacs <bridge>    -> tabela MAC L2 da bridge (filtrando is local? == no)
 *  - ip route / route           -> rota padrão (gateway)
 *  - ip neigh                   -> tabela ARP/vizinhos L3
 */
public class ApParser {

    private static final String IPV4_REGEX =
            "((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})";

    private static final String MAC_COLON_OR_DASH =
            "([0-9a-fA-F]{2}(?:[:\\-][0-9a-fA-F]{2}){5})";

    // Formato 5 colunas do brctl showmacs nos APs Intelbras:
    // "eth0          2   18:0d:2c:d2:09:29   no         10.56"
    // "eth0.4071     2   30:e1:f1:1f:e9:3b   yes         0.00"
    private static final Pattern BRCTL_MAC_5_COLS = Pattern.compile(
            "^\\s*([a-zA-Z0-9_.:\\-]+)\\s+(\\d+)\\s+" + MAC_COLON_OR_DASH + "\\s+(yes|no)\\s+[0-9.]+\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    // Formato 4 colunas padrão BusyBox/Linux (caso algum firmware antigo não exiba a coluna interface):
    // "  2   18:0d:2c:d2:09:29   no         10.56"
    private static final Pattern BRCTL_MAC_4_COLS = Pattern.compile(
            "^\\s*(\\d+)\\s+" + MAC_COLON_OR_DASH + "\\s+(yes|no)\\s+[0-9.]+\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    // Ex: "br-lan          7fff.30e1f11fe93b       no              devdh1"
    private static final Pattern BRCTL_SHOW_FIRST_LINE = Pattern.compile(
            "^([a-zA-Z0-9_.\\-]+)\\s+[0-9a-fA-F]{4}\\.([0-9a-fA-F]{12})\\s+\\S+(?:\\s+(\\S+))?\\s*$"
    );

    // Linha de continuação de interfaces no "brctl show": "                                eth0.4071"
    private static final Pattern BRCTL_SHOW_CONT_IFACE = Pattern.compile(
            "^\\s+([a-zA-Z0-9_.\\-]+)\\s*$"
    );

    // Extrai HWaddr por bloco de interface no "ifconfig"
    private static final Pattern IFCONFIG_HWADDR = Pattern.compile(
            "^([a-zA-Z0-9_.\\-]+)\\s+Link\\s+encap:Ethernet\\s+HWaddr\\s+" + MAC_COLON_OR_DASH,
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    // Ex: "default via 192.168.0.1 dev br-lan proto static src 192.168.0.70"
    // ou  "default         192.168.0.1     0.0.0.0         UG    0      0        0 br-lan"
    private static final Pattern DEFAULT_ROUTE_LINE = Pattern.compile(
            "(?i)^\\s*(?:default|0\\.0\\.0\\.0)\\b.*?\\b" + IPV4_REGEX + "\\b"
    );

    // Ex: "192.168.0.1 dev br-lan lladdr b8:69:f4:3e:b8:73 DELAY"
    private static final Pattern IP_NEIGH_LINE = Pattern.compile(
            "^\\s*" + IPV4_REGEX + "\\s+dev\\s+(\\S+)\\s+lladdr\\s+" + MAC_COLON_OR_DASH + "\\b.*$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Identifica pelo banner/prompt inicial da sessão SSH/Telnet se o equipamento apresenta
     * prompt típico de AP Intelbras Linux/klish ("#" puro, "user@host:~#", ou banner OpenWrt/BusyBox).
     */
    public boolean ehBannerOuPromptDeAp(String bannerInicial) {
        if (bannerInicial == null || bannerInicial.isBlank()) {
            return false;
        }
        String lower = bannerInicial.toLowerCase();
        if (lower.contains("openwrt") || lower.contains("busybox") || lower.contains("dropbear")) {
            return true;
        }
        String ultima = ultimaLinhaNaoVazia(bannerInicial).trim();
        // Prompt "#" ou ">" isolado (usado pela CLI klish dos APs Intelbras) ou "root@host:~#"
        return ultima.equals("#")
                || ultima.equals(">")
                || ultima.matches("^[^\\r\\n]*@[^\\r\\n]*[>#$]$")
                || ultima.matches("^[^\\r\\n]*:~[>#$]$");
    }

    /**
     * Confirma se as saídas coletadas pertencem de fato a um AP Linux/OpenWrt Intelbras.
     */
    public boolean ehDispositivoAp(String ubusBoard, String brctlShow, String ifconfig) {
        if (ubusBoard != null && (ubusBoard.contains("\"board_name\"") || ubusBoard.contains("\"model\""))) {
            return true;
        }
        if (brctlShow != null && brctlShow.toLowerCase().contains("bridge name") && brctlShow.toLowerCase().contains("bridge id")) {
            return true;
        }
        return ifconfig != null && ifconfig.contains("Link encap:Ethernet") && ifconfig.contains("HWaddr");
    }

    /**
     * Extrai a identidade do AP (modelo, hostname, serial opcional e MAC-base).
     */
    public SwitchIdentity parseDeviceInfo(String ip, String ubusBoard, String ifconfig, String brctlShow) {
        String model = extrairCampoJson(ubusBoard, "model");
        if (model != null) {
            model = model.replaceFirst("(?i)^intelbras\\s+", "").trim();
        }
        if (model == null || model.isBlank()) {
            String boardName = extrairCampoJson(ubusBoard, "board_name");
            model = (boardName != null && !boardName.isBlank()) ? ("AP (" + boardName + ")") : "AP Intelbras";
        }

        String hostname = extrairCampoJson(ubusBoard, "hostname");
        String serial = extrairCampoJson(ubusBoard, "serial");
        String mac = extrairMelhorMacAp(ifconfig, brctlShow);

        String deviceName = montarNomeExibicaoAp(model, hostname);
        return new SwitchIdentity(deviceName, serial, mac, model, ip);
    }

    private static String montarNomeExibicaoAp(String model, String hostname) {
        if (hostname == null || hostname.isBlank()
                || hostname.equalsIgnoreCase("Intelbras")
                || hostname.equalsIgnoreCase("OpenWrt")
                || hostname.equalsIgnoreCase("AP")) {
            return model;
        }
        String hLimpo = hostname.trim();
        String normHost = hLimpo.replaceAll("[\\s_\\-]+", "").toLowerCase();
        String normModel = model.replaceAll("[\\s_\\-]+", "").toLowerCase();
        if (normHost.equals(normModel)) {
            return model;
        }
        if (hLimpo.toUpperCase().startsWith("AP")) {
            return hLimpo;
        }
        return model + " - " + hLimpo;
    }

    private static String extrairMelhorMacAp(String ifconfig, String brctlShow) {
        if (ifconfig != null && !ifconfig.isBlank()) {
            Map<String, String> macPorIface = new LinkedHashMap<>();
            Matcher m = IFCONFIG_HWADDR.matcher(ifconfig);
            while (m.find()) {
                String iface = m.group(1).trim().toLowerCase();
                String fmt = Serie3000Parser.formatarMacPadrao(m.group(2)).toUpperCase();
                if (MacUtils.ehMacValido(MacUtils.normalizar(fmt)) && !ehMacVirtualInterno(fmt)) {
                    macPorIface.put(iface, fmt);
                }
            }
            for (String preferida : List.of("br-lan", "br0", "eth0", "eth1")) {
                if (macPorIface.containsKey(preferida)) {
                    return macPorIface.get(preferida);
                }
            }
            for (Map.Entry<String, String> e : macPorIface.entrySet()) {
                if (!ehInterfaceInternaIgnorada(e.getKey())) {
                    return e.getValue();
                }
            }
        }

        if (brctlShow != null && !brctlShow.isBlank()) {
            for (String line : brctlShow.split("\\R")) {
                Matcher m = BRCTL_SHOW_FIRST_LINE.matcher(line.trim());
                if (m.matches()) {
                    String fmt = Serie3000Parser.formatarMacPadrao(m.group(2)).toUpperCase();
                    if (MacUtils.ehMacValido(MacUtils.normalizar(fmt)) && !ehMacVirtualInterno(fmt)) {
                        return fmt;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Faz o parse da saída de "brctl show", retornando cada bridge ativa associada ao seu VLAN ID padrão
     * (ex.: "br-lan" -> 1, "br-vlan2dff82f" com membro "eth0.4071" -> 4071).
     */
    public Map<String, Integer> parseBridgesEVlans(String brctlShowOutput) {
        Map<String, List<String>> ifacesPorBridge = new LinkedHashMap<>();
        if (brctlShowOutput == null || brctlShowOutput.isBlank()) {
            return Map.of();
        }

        String bridgeAtual = null;
        for (String line : brctlShowOutput.split("\\R")) {
            if (line.isBlank()) continue;
            String trimmed = line.strip();
            if (trimmed.toLowerCase().startsWith("bridge name") || trimmed.startsWith("#")) {
                continue;
            }

            Matcher mFirst = BRCTL_SHOW_FIRST_LINE.matcher(trimmed);
            if (mFirst.matches()) {
                bridgeAtual = mFirst.group(1).trim();
                List<String> ifaces = ifacesPorBridge.computeIfAbsent(bridgeAtual, k -> new ArrayList<>());
                if (mFirst.group(3) != null && !mFirst.group(3).isBlank()) {
                    ifaces.add(mFirst.group(3).trim());
                }
                continue;
            }

            if (bridgeAtual != null && Character.isWhitespace(line.charAt(0))) {
                Matcher mCont = BRCTL_SHOW_CONT_IFACE.matcher(line);
                if (mCont.matches()) {
                    ifacesPorBridge.get(bridgeAtual).add(mCont.group(1).trim());
                }
            }
        }

        Map<String, Integer> vlanPorBridge = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : ifacesPorBridge.entrySet()) {
            String bridge = entry.getKey();
            int vlan = extrairVlanDeInterfacesOuBridge(bridge, entry.getValue());
            vlanPorBridge.put(bridge, vlan);
        }
        return vlanPorBridge;
    }

    private static int extrairVlanDeInterfacesOuBridge(String bridge, List<String> interfaces) {
        for (String iface : interfaces) {
            int vlanIface = extrairSufixoVlan(iface);
            if (vlanIface > 0) {
                return vlanIface;
            }
        }
        Matcher mBr = Pattern.compile("(?i)^br[-_]?vlan(\\d{1,4})$").matcher(bridge);
        if (mBr.matches()) {
            int v = Integer.parseInt(mBr.group(1));
            if (v >= 1 && v <= 4094) return v;
        }
        return 1;
    }

    /**
     * Faz o parse da saída de "brctl showmacs <bridge>", mantendo apenas MACs externos ("is local? == no")
     * e convertendo subinterfaces VLAN (ex: "eth0.4071") na porta física ("ETH0") com seu respectivo VLAN ID.
     */
    public List<MacEntry> parseBridgeMacs(String switchId, int defaultVlan, String brctlShowmacsOutput) {
        List<MacEntry> entries = new ArrayList<>();
        if (brctlShowmacsOutput == null || brctlShowmacsOutput.isBlank()) {
            return entries;
        }

        Set<String> chavesUnicas = new LinkedHashSet<>();
        for (String line : brctlShowmacsOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher m5 = BRCTL_MAC_5_COLS.matcher(trimmed);
            if (m5.matches()) {
                String rawIface = m5.group(1);
                String rawMac = m5.group(3);
                String isLocal = m5.group(4);

                if (!"no".equalsIgnoreCase(isLocal) || ehInterfaceInternaIgnorada(rawIface)) {
                    continue;
                }

                String macFmt = Serie3000Parser.formatarMacPadrao(rawMac);
                String macNorm = MacUtils.normalizar(macFmt);
                if (!MacUtils.ehMacValido(macNorm) || ehMacVirtualInterno(macFmt)) {
                    continue;
                }

                int vlanSub = extrairSufixoVlan(rawIface);
                int vlan = vlanSub > 0 ? vlanSub : defaultVlan;
                String portaFisica = normalizarPortaAp(rawIface);

                if (chavesUnicas.add(macNorm + "@" + vlan + "@" + portaFisica)) {
                    entries.add(new MacEntry(switchId, macFmt, vlan, "DYNAMIC", portaFisica));
                }
                continue;
            }

            Matcher m4 = BRCTL_MAC_4_COLS.matcher(trimmed);
            if (m4.matches()) {
                String portNo = m4.group(1);
                String rawMac = m4.group(2);
                String isLocal = m4.group(3);

                if (!"no".equalsIgnoreCase(isLocal)) {
                    continue;
                }

                String macFmt = Serie3000Parser.formatarMacPadrao(rawMac);
                String macNorm = MacUtils.normalizar(macFmt);
                if (!MacUtils.ehMacValido(macNorm) || ehMacVirtualInterno(macFmt)) {
                    continue;
                }

                String porta = "PORT" + portNo;
                if (chavesUnicas.add(macNorm + "@" + defaultVlan + "@" + porta)) {
                    entries.add(new MacEntry(switchId, macFmt, defaultVlan, "DYNAMIC", porta));
                }
            }
        }

        return entries;
    }

    /**
     * Extrai o IP do gateway padrão a partir de "ip route" ou "route".
     */
    public Optional<String> parseDefaultGatewayIp(String rawIpRoute) {
        if (rawIpRoute == null || rawIpRoute.isBlank()) {
            return Optional.empty();
        }
        for (String line : rawIpRoute.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            Matcher m = DEFAULT_ROUTE_LINE.matcher(trimmed);
            if (m.find()) {
                String cand = m.group(1);
                if (!"0.0.0.0".equals(cand) && !"255.255.255.255".equals(cand)) {
                    return Optional.of(cand);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Faz o parse de "ip neigh" e resolve a porta física correspondente (ex: "ETH0") usando a tabela MAC da bridge.
     */
    public List<ArpEntry> parseArpTable(String rawIpNeigh, List<MacEntry> entradasMacBridge) {
        List<ArpEntry> entries = new ArrayList<>();
        if (rawIpNeigh == null || rawIpNeigh.isBlank()) {
            return entries;
        }

        Map<String, String> portaPorMac = new LinkedHashMap<>();
        if (entradasMacBridge != null) {
            for (MacEntry e : entradasMacBridge) {
                portaPorMac.putIfAbsent(e.macNormalizado(), e.port());
            }
        }

        for (String line : rawIpNeigh.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher m = IP_NEIGH_LINE.matcher(trimmed);
            if (!m.matches()) continue;

            String ip = m.group(1);
            String dev = m.group(2);
            String macFmt = Serie3000Parser.formatarMacPadrao(m.group(3));
            String macNorm = MacUtils.normalizar(macFmt);

            String portaFisica;
            if (dev.toLowerCase().startsWith("br") || ehInterfaceInternaIgnorada(dev)) {
                portaFisica = portaPorMac.get(macNorm);
            } else {
                portaFisica = normalizarPortaAp(dev);
            }

            entries.add(new ArpEntry(ip, macFmt, portaFisica));
        }

        return entries;
    }

    /**
     * Normaliza interfaces de AP ("eth0.4071" -> "ETH0", "eth0" -> "ETH0", "ath0" -> "ATH0").
     */
    public static String normalizarPortaAp(String interfaceBruta) {
        if (interfaceBruta == null) return "";
        String limpa = interfaceBruta.trim();
        int idxPonto = limpa.indexOf('.');
        if (idxPonto > 0) {
            limpa = limpa.substring(0, idxPonto);
        }
        return limpa.toUpperCase();
    }

    private static int extrairSufixoVlan(String iface) {
        if (iface == null) return -1;
        int idx = iface.indexOf('.');
        if (idx > 0 && idx < iface.length() - 1) {
            try {
                int vlan = Integer.parseInt(iface.substring(idx + 1).trim());
                if (vlan >= 1 && vlan <= 4094) {
                    return vlan;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static boolean ehInterfaceInternaIgnorada(String iface) {
        if (iface == null || iface.isBlank()) return true;
        String lower = iface.trim().toLowerCase();
        return lower.equals("lo")
                || lower.equals("cpu")
                || lower.startsWith("devdh")
                || lower.startsWith("br-")
                || lower.matches("^br\\d+.*")
                || lower.startsWith("soc")
                || lower.startsWith("wifi");
    }

    private static boolean ehMacVirtualInterno(String macFormatado) {
        String norm = MacUtils.normalizar(macFormatado);
        return "feffffffffff".equalsIgnoreCase(norm);
    }

    private static String extrairCampoJson(String json, String chave) {
        if (json == null || json.isBlank()) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(chave) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String ultimaLinhaNaoVazia(String texto) {
        if (texto == null) return "";
        String[] linhas = texto.split("\\R");
        for (int i = linhas.length - 1; i >= 0; i--) {
            if (!linhas[i].isBlank()) {
                return linhas[i];
            }
        }
        return "";
    }
}

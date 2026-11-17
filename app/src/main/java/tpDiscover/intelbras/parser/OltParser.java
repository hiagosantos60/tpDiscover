package tpDiscover.intelbras.parser;

import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.StpPortState;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser para a CLI das OLTs Intelbras:
 *  - Linha EPON / GPON Cassete (4840E, 4880E, G16, G08)
 *  - Linha GPON / Titan FiberHome/Intelbras (AN6000, AN6001-G16, AN5516-04)
 *  - Linha 8820 (OLT 8820I / 8820G)
 */
public class OltParser {

    private static final String IPV4_REGEX =
            "((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})";

    private static final String MAC_ANY_REGEX =
            "([0-9a-fA-F]{2}(?:[:\\-][0-9a-fA-F]{2}){5}|[0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4})";

    // Formato padrão OLT 4840E / G16 / G08:
    // "00:09:0f:09:1e:0a     1        e0/2     dynamic"
    private static final Pattern MAC_FIRST_LINE = Pattern.compile(
            "^\\s*" + MAC_ANY_REGEX + "\\s+" +
            "(\\d+)\\s+" +
            "(\\S+)\\s+" +
            "(\\S+)\\s*$"
    );

    // Formato alternativo caso alguma versão exiba VLAN antes do MAC:
    // "1     00:09:0f:09:1e:0a     e0/2     dynamic"
    private static final Pattern VLAN_FIRST_LINE = Pattern.compile(
            "^\\s*(\\d+)\\s+" +
            MAC_ANY_REGEX + "\\s+" +
            "(\\S+)\\s+" +
            "(\\S+)\\s*$"
    );

    private static final Pattern DEFAULT_ROUTE_LINE = Pattern.compile(
            "(?i)^.*?(?:0\\.0\\.0\\.0(?:/0|\\s+0\\.0\\.0\\.0)|\\bdefault\\b|gateway\\s+of\\s+last\\s+resort\\s+is).*?\\b" + IPV4_REGEX + "\\b"
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
                || lower.contains("cassette olt")
                || lower.contains("1.3.6.1.4.1.13464.")
                || lower.contains("epon product")
                || lower.contains("gpon product")
                || lower.contains("system description     :epon")
                || lower.contains("system description     :gpon")
                || lower.contains("olt-04p")
                || lower.contains("olt-08p")
                || lower.contains("olt-16p")
                || lower.contains("olt4840")
                || lower.contains("olt4880")
                || Pattern.compile("(?i)\\b(olt\\s*4840e?|olt\\s*4880e?|olt\\s*g16|olt\\s*g08|g16|g08|g116)\\b").matcher(rawOutput).find();
    }

    /** Detecta banner/prompt de OLTs FiberHome/Intelbras (AN6000, AN6001-G16, AN5516-04). */
    public boolean ehBannerOltAn(String banner) {
        if (banner == null || banner.isBlank()) return false;
        String lower = banner.toLowerCase();
        return lower.contains("zzzzzzzzzzzz")
                || lower.contains("an6000")
                || lower.contains("an6001")
                || lower.contains("an5516")
                || lower.contains("fiberhome")
                || Pattern.compile("(?is)\\bmaster\\b[\\s\\r\\n]+user>").matcher(banner).find();
    }

    /** Detecta banner/prompt de OLTs 8820I / 8820G. */
    public boolean ehBannerOlt8820(String banner) {
        if (banner == null || banner.isBlank()) return false;
        String lower = banner.toLowerCase();
        return lower.contains("olt 8820")
                || lower.contains("8820i")
                || lower.contains("8820g")
                || lower.contains("intelbras-olt");
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

    /**
     * Extrai o IP do gateway padrão (0.0.0.0/0) da saída de "show ip route" na OLT.
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
                    if (!"0.0.0.0".equals(cand) && !"255.255.255.255".equals(cand)) {
                        return Optional.of(cand);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Faz o parse da saída de "show arp" / "show arp all" / "show ip fdb" na OLT.
     */
    public List<ArpEntry> parseArpTable(String rawOutput) {
        List<ArpEntry> entries = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) {
            return entries;
        }
        Pattern macFinder = Pattern.compile(MAC_ANY_REGEX);
        Pattern ipFinder = Pattern.compile("^\\s*(?:[a-z]+\\s+)?" + IPV4_REGEX + "\\b");
        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher mIp = ipFinder.matcher(trimmed);
            if (!mIp.find()) continue;
            String resto = trimmed.substring(mIp.end());
            Matcher mMac = macFinder.matcher(resto);
            if (!mMac.find()) continue;

            String ip = mIp.group(1);
            String mac = Serie3000Parser.formatarMacPadrao(mMac.group(1));
            String porta = null;
            String depoisMac = resto.substring(mMac.end()).trim();
            if (!depoisMac.isEmpty()) {
                for (String token : depoisMac.split("\\s+")) {
                    String cand = extrairPortaFisicaOlt(token);
                    if (cand != null) {
                        porta = cand;
                        break;
                    }
                }
            }
            entries.add(new ArpEntry(ip, mac, porta));
        }
        return entries;
    }

    private static String extrairPortaFisicaOlt(String token) {
        if (token == null || token.isBlank()) return null;
        String t = token.trim().replaceFirst("[,;]$", "");
        String lower = t.toLowerCase();
        if (ehPortaInternaCpu(t) || lower.startsWith("vlan") || t.equals("-") || t.startsWith("--")) {
            return null;
        }
        if (lower.matches("^(?:(?:e|eth|xeth|ge|xge|te|tg|xe|g|p|pon|epon|gpon)\\d+.*|\\d{1,2}/\\d{1,2}(?:/\\d{1,2})?)$")) {
            return normalizarPorta(t);
        }
        return null;
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
        if (mac != null && mac.isBlank()) {
            mac = null;
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

    /**
     * Faz o parsing best-effort da saída LLDP de OLTs (estilo BDCOM/Cisco ou Comware).
     * Retorna lista vazia caso o equipamento não suporte LLDP ou esteja desabilitado.
     */
    public static List<LldpNeighbor> parseLldpNeighbors(String switchId, String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return List.of();
        }
        String lower = rawOutput.toLowerCase();
        if (lower.contains("not enabled") || lower.contains("invalid input")
                || lower.contains("unknown command") || lower.contains("unrecognized command")
                || lower.contains("protocol is disable")) {
            return List.of();
        }

        List<LldpNeighbor> parsed = parseLldpInterfaceBdcom(switchId, rawOutput);
        Serie3000Parser s30 = new Serie3000Parser();
        if (parsed.isEmpty()) {
            parsed = s30.parseLldpSerie3000(switchId, rawOutput);
        }
        if (parsed.isEmpty()) {
            parsed = new ComwareParser().parseLldpNeighborsVerbose(switchId, rawOutput);
        }
        if (parsed.isEmpty()) {
            parsed = s30.parseLldpS2050Lite(switchId, rawOutput);
        }
        if (parsed.isEmpty()) {
            return List.of();
        }

        List<LldpNeighbor> normalizados = new ArrayList<>(parsed.size());
        for (LldpNeighbor n : parsed) {
            String portaLocal = n.localPort() != null && !n.localPort().isBlank()
                    ? normalizarPorta(n.localPort())
                    : "";
            normalizados.add(new LldpNeighbor(
                    n.localSwitchId(),
                    portaLocal,
                    n.chassisIdRaw(),
                    n.chassisMacNormalizado(),
                    n.remotePort(),
                    n.remotePortDescription(),
                    n.systemName(),
                    n.systemDescription(),
                    n.managementIp(),
                    n.capabilities()
            ));
        }
        return normalizados;
    }

    /** Parser para saída de "show lldp interface" das OLTs BDCOM / 4840E / G16 / G08. */
    private static List<LldpNeighbor> parseLldpInterfaceBdcom(String switchId, String rawOutput) {
        List<LldpNeighbor> neighbors = new ArrayList<>();
        if (rawOutput == null || !rawOutput.toLowerCase().contains("interface")) {
            return neighbors;
        }
        Pattern ifaceHeader = Pattern.compile("(?i)^\\s*Interface\\s+([a-zA-Z]+(?:\\s+\\d+(?:/\\d+){1,2}|\\d+(?:/\\d+){0,2}))\\b.*$");
        Pattern neighborStart = Pattern.compile("(?i)^\\s*Neighbor\\s*\\(\\d+\\)\\s*:?\\s*$");
        Pattern keyVal = Pattern.compile("^\\s*([^:]+?)\\s*:\\s*(.*?)\\s*$");

        String portaAtual = "";
        String chassisId = "";
        String portId = "";
        String portDesc = "";
        String sysName = "";
        String sysDesc = "";
        String mgmtIp = "";
        String capabilities = "";
        boolean emNeighbor = false;

        for (String line : rawOutput.split("\\R")) {
            Matcher mIface = ifaceHeader.matcher(line);
            if (mIface.matches()) {
                adicionarSeValido(neighbors, switchId, portaAtual, chassisId, portId, portDesc, sysName, sysDesc, mgmtIp, capabilities);
                portaAtual = normalizarPortaLldpBdcom(mIface.group(1));
                chassisId = ""; portId = ""; portDesc = ""; sysName = ""; sysDesc = ""; mgmtIp = ""; capabilities = "";
                emNeighbor = false;
                continue;
            }
            if (neighborStart.matcher(line).matches()) {
                if (emNeighbor) {
                    adicionarSeValido(neighbors, switchId, portaAtual, chassisId, portId, portDesc, sysName, sysDesc, mgmtIp, capabilities);
                    chassisId = ""; portId = ""; portDesc = ""; sysName = ""; sysDesc = ""; mgmtIp = ""; capabilities = "";
                }
                emNeighbor = true;
                continue;
            }
            Matcher mKv = keyVal.matcher(line);
            if (!mKv.matches() || portaAtual.isBlank()) continue;
            String k = mKv.group(1).trim().toLowerCase();
            String v = mKv.group(2).trim();
            if (k.startsWith("chassis id")) {
                chassisId = v;
                emNeighbor = true;
            } else if (k.startsWith("port id")) {
                portId = v.replaceFirst("(?i)^port\\s+", "").trim();
            } else if (k.startsWith("port description")) {
                portDesc = v;
            } else if (k.startsWith("system name")) {
                sysName = v;
            } else if (k.startsWith("system description")) {
                sysDesc = v;
            } else if (k.startsWith("management address")) {
                Matcher mIp = Pattern.compile("\\b" + IPV4_REGEX + "\\b").matcher(v);
                if (mIp.find()) mgmtIp = mIp.group(1);
            } else if (k.startsWith("system capabilities")) {
                capabilities = v;
            }
        }
        adicionarSeValido(neighbors, switchId, portaAtual, chassisId, portId, portDesc, sysName, sysDesc, mgmtIp, capabilities);
        return neighbors;
    }

    private static void adicionarSeValido(
            List<LldpNeighbor> out, String switchId, String portaLocal, String chassisId,
            String portId, String portDesc, String sysName, String sysDesc, String mgmtIp, String capabilitiesRaw) {
        if (portaLocal.isBlank() || (chassisId.isBlank() && sysName.isBlank())) return;
        String chassisMac = chassisId.matches(MAC_ANY_REGEX) ? Serie3000Parser.formatarMacPadrao(chassisId) : null;
        Set<String> caps = new LinkedHashSet<>();
        if (capabilitiesRaw != null && !capabilitiesRaw.isBlank()) {
            for (String token : capabilitiesRaw.split("[,\\s]+")) {
                String t = token.trim();
                if (!t.isEmpty()) caps.add(t);
            }
        }
        out.add(new LldpNeighbor(switchId, portaLocal, chassisId, chassisMac, portId, portDesc, sysName, sysDesc, mgmtIp, caps));
    }

    private static String normalizarPortaLldpBdcom(String raw) {
        String s = raw.trim()
                .replaceFirst("(?i)^TenGigabitEthernet\\s*", "TG")
                .replaceFirst("(?i)^GigabitEthernet\\s*", "G")
                .replaceFirst("(?i)^Ethernet\\s*", "E")
                .replaceAll("\\s+", "");
        return normalizarPorta(s);
    }

    /**
     * Faz o parsing do estado Spanning Tree em OLTs BDCOM ("show spanning-tree interface brief")
     * ou FiberHome/Comware ("show stp brief").
     */
    public List<StpPortState> parseSpanningTree(String switchId, String rawOutput) {
        List<StpPortState> estados = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) return estados;

        String lower = rawOutput.toLowerCase();
        if (lower.contains("protocol status :disabled")
                || lower.contains("stp is disabled")
                || lower.contains("spanning tree disabled")
                || lower.contains("spanning-tree is disabled")
                || lower.contains("not enabled")) {
            estados.add(new StpPortState(switchId, "*", "DISABLED", "DISABLED", false, null));
            return estados;
        }

        String rootMac = null;
        Matcher mRoot = Pattern.compile("(?i)root\\s+bridge[^\\r\\n]*?mac\\s+address\\s*[:=]?\\s*" + MAC_ANY_REGEX).matcher(rawOutput);
        if (mRoot.find()) {
            rootMac = Serie3000Parser.formatarMacPadrao(mRoot.group(1));
        }

        Pattern stpBrief = Pattern.compile("(?i)^\\s*([a-z]+\\d+(?:/\\d+){0,2})\\s+(enabled|disabled)\\s+\\d+\\s+\\d+\\s+(\\S+)\\s+(\\S+)\\s*$");
        for (String line : rawOutput.split("\\R")) {
            Matcher m = stpBrief.matcher(line.strip());
            if (!m.matches()) continue;
            boolean hab = !m.group(2).equalsIgnoreCase("disabled");
            String roleRaw = m.group(3).toUpperCase().replace("PORT", "");
            String stateRaw = m.group(4).toUpperCase();
            estados.add(new StpPortState(switchId, normalizarPorta(m.group(1)), roleRaw, stateRaw, hab, rootMac));
        }
        if (!estados.isEmpty()) return estados;
        return new Serie3000Parser().parseSpanningTree(switchId, rawOutput);
    }

    /** Extrai slots ativos de "show card" nas OLTs AN5516/AN6000/AN6001. */
    public List<Integer> extrairSlotsAtivosAn(String showCardOut) {
        List<Integer> slots = new ArrayList<>();
        if (showCardOut == null || showCardOut.isBlank()) return slots;
        Pattern linhaSlot = Pattern.compile("^\\s*(\\d{1,2})\\s+\\S+.*$");
        for (String line : showCardOut.split("\\R")) {
            Matcher m = linhaSlot.matcher(line);
            if (!m.matches()) continue;
            int slot = Integer.parseInt(m.group(1));
            if (slot > 0 && slot <= 24 && !slots.contains(slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** Extrai identidade de OLTs FiberHome/Intelbras (AN6000, AN6001-G16, AN5516-04). */
    public SwitchIdentity parseDeviceInfoAn(
            String ip, String showCardOut, String showHostnameOut, String showManageVlanOut, String bannerInicial) {
        String combinadoModelo = (showCardOut != null ? showCardOut : "") + "\n" + (bannerInicial != null ? bannerInicial : "");
        String productId = null;
        Matcher mModelo = Pattern.compile("(?i)\\b(AN(?:6000|6001|5516)(?:-[A-Z]?\\d+)?)\\b").matcher(combinadoModelo);
        if (mModelo.find()) {
            productId = "OLT " + mModelo.group(1).toUpperCase();
        }
        if (productId == null || productId.isBlank()) {
            productId = "OLT AN";
        }

        String deviceName = null;
        if (showHostnameOut != null) {
            Matcher mHost = Pattern.compile("(?im)^\\s*(?:hostname|sysname)\\s*[:=]\\s*(\\S+)").matcher(showHostnameOut);
            if (mHost.find()) {
                String cand = mHost.group(1).trim();
                if (!cand.equalsIgnoreCase("Admin") && !cand.equalsIgnoreCase("Master") && !cand.equalsIgnoreCase("User")) {
                    deviceName = cand;
                }
            }
        }
        if (deviceName == null || deviceName.isBlank()) {
            deviceName = productId.replace(' ', '-');
        }

        String mac = extrairMacManageVlanAn(ip, showManageVlanOut);
        return new SwitchIdentity(deviceName, null, mac, productId, ip);
    }

    private static String extrairMacManageVlanAn(String ip, String showManageVlanOut) {
        if (showManageVlanOut == null || showManageVlanOut.isBlank()) return null;
        Pattern macProp = Pattern.compile("(?i)ethernet\\s+address\\s*[:=]\\s*" + MAC_ANY_REGEX);
        Pattern inetProp = Pattern.compile("(?i)\\binet\\s*[:=]\\s*" + IPV4_REGEX);

        String primeiroMac = null;
        String macBlocoAtual = null;
        for (String line : showManageVlanOut.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                macBlocoAtual = null;
                continue;
            }
            Matcher mMac = macProp.matcher(trimmed);
            if (mMac.find()) {
                String fmt = Serie3000Parser.formatarMacPadrao(mMac.group(1)).toUpperCase();
                if (!"00:00:00:00:00:00".equals(fmt)) {
                    macBlocoAtual = fmt;
                    if (primeiroMac == null) primeiroMac = fmt;
                }
            }
            Matcher mIp = inetProp.matcher(trimmed);
            if (mIp.find() && ip != null && ip.equals(mIp.group(1)) && macBlocoAtual != null) {
                return macBlocoAtual;
            }
        }
        return primeiroMac;
    }

    /** Faz o parse da saída "show mac-address all" (AN6000/AN6001) e "show fdb slot" (AN5516). */
    public List<MacEntry> parseMacTableAn(String switchId, String rawOutput) {
        List<MacEntry> entries = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) return entries;

        // Ex AN6000/AN6001 UPLINK/PON: "4  5  --  00:1a:3f:a0:03:32  1000" ou "19  5  --  DC-9F-DB-EE-63-51  1000"
        Pattern linhaSlotPortOnu = Pattern.compile(
                "^\\s*(\\d{1,2})\\s+(\\d{1,2})\\s+(?:--|\\d+)\\s+" + MAC_ANY_REGEX + "\\s+(\\d{1,4})(?:\\s.*)?$");
        // Fallback AN5516 / alternativo sem coluna ONU: "4  5  00:1a:3f:a0:03:32  1000"
        Pattern linhaSlotPort = Pattern.compile(
                "^\\s*(\\d{1,2})\\s+(\\d{1,2})\\s+" + MAC_ANY_REGEX + "\\s+(\\d{1,4})(?:\\s.*)?$");
        // Fallback slash: "4/5  00:1a:3f:a0:03:32  1000"
        Pattern linhaSlash = Pattern.compile(
                "^\\s*(\\d{1,2}/\\d{1,2})\\s+" + MAC_ANY_REGEX + "\\s+(\\d{1,4})(?:\\s.*)?$");

        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            Matcher m1 = linhaSlotPortOnu.matcher(trimmed);
            if (m1.matches()) {
                entries.add(new MacEntry(switchId, Serie3000Parser.formatarMacPadrao(m1.group(3)),
                        Integer.parseInt(m1.group(4)), "dynamic", m1.group(1) + "/" + m1.group(2)));
                continue;
            }
            Matcher m2 = linhaSlotPort.matcher(trimmed);
            if (m2.matches()) {
                entries.add(new MacEntry(switchId, Serie3000Parser.formatarMacPadrao(m2.group(3)),
                        Integer.parseInt(m2.group(4)), "dynamic", m2.group(1) + "/" + m2.group(2)));
                continue;
            }
            Matcher m3 = linhaSlash.matcher(trimmed);
            if (m3.matches()) {
                entries.add(new MacEntry(switchId, Serie3000Parser.formatarMacPadrao(m3.group(2)),
                        Integer.parseInt(m3.group(3)), "dynamic", normalizarPorta(m3.group(1))));
                continue;
            }
            List<MacEntry> fallback = parseMacAddressTable(switchId, trimmed);
            if (!fallback.isEmpty()) {
                entries.addAll(fallback);
            }
        }
        return entries;
    }

    /** Faz o parse da saída "show arp-table all" nas OLTs AN6000 / AN6001. */
    public List<ArpEntry> parseArpTableAn(String rawOutput) {
        List<ArpEntry> entries = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) return entries;

        Pattern linhaIpMac = Pattern.compile("(?i)^\\s*" + IPV4_REGEX + "\\s+MAC\\s*\\(\\s*" + MAC_ANY_REGEX + "\\s*\\).*");
        Pattern logPortProp = Pattern.compile("(?i)\\blog_port\\s*[:=]\\s*([^,\\s]+)");

        String pendingIp = null;
        String pendingMac = null;
        String pendingPorta = null;

        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                if (pendingIp != null) {
                    entries.add(new ArpEntry(pendingIp, pendingMac, pendingPorta));
                    pendingIp = null; pendingMac = null; pendingPorta = null;
                }
                continue;
            }
            Matcher mHead = linhaIpMac.matcher(trimmed);
            if (mHead.matches()) {
                if (pendingIp != null) {
                    entries.add(new ArpEntry(pendingIp, pendingMac, pendingPorta));
                }
                pendingIp = mHead.group(1);
                pendingMac = Serie3000Parser.formatarMacPadrao(mHead.group(2));
                pendingPorta = null;
                continue;
            }
            if (pendingIp != null) {
                Matcher mPort = logPortProp.matcher(trimmed);
                if (mPort.find()) {
                    String cand = mPort.group(1).trim();
                    if (!ehPortaInternaCpu(cand) && !cand.equals("--") && !cand.equals("0/0")) {
                        pendingPorta = normalizarPorta(cand);
                    }
                }
            } else {
                entries.addAll(parseArpTable(trimmed));
            }
        }
        if (pendingIp != null) {
            entries.add(new ArpEntry(pendingIp, pendingMac, pendingPorta));
        }
        return entries;
    }

    /** Extrai gateway padrão de "show ipv4 route" / "show static-route running-config" priorizando o próximo salto na sub-rede ARP local. */
    public Optional<String> parseDefaultGatewayAn(String rawOutput, String mgmtIp, List<ArpEntry> entradasArp) {
        if (rawOutput == null || rawOutput.isBlank()) return Optional.empty();
        Set<String> candidatos = new LinkedHashSet<>();
        Pattern viaPattern = Pattern.compile("(?i)\\b(?:via|0\\.0\\.0\\.0\\s+0\\.0\\.0\\.0)\\s+" + IPV4_REGEX + "\\b");
        Pattern outraRotaPrefixo = Pattern.compile("^\\s*(?:[A-Za-z*]+\\s+)?" + IPV4_REGEX + "/\\d+");

        boolean emRotaPadrao = false;
        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                emRotaPadrao = false;
                continue;
            }
            if (trimmed.contains("0.0.0.0/0") || trimmed.toLowerCase().contains("0.0.0.0 0.0.0.0")) {
                emRotaPadrao = true;
            } else {
                Matcher mOutro = outraRotaPrefixo.matcher(trimmed);
                if (mOutro.find() && !"0.0.0.0".equals(mOutro.group(1))) {
                    emRotaPadrao = false;
                }
            }
            if (emRotaPadrao) {
                Matcher mVia = viaPattern.matcher(trimmed);
                while (mVia.find()) {
                    String gw = mVia.group(1);
                    if (!"0.0.0.0".equals(gw) && !"255.255.255.255".equals(gw)) {
                        candidatos.add(gw);
                    }
                }
            }
        }
        if (candidatos.isEmpty()) {
            return parseDefaultGatewayIp(rawOutput);
        }
        if (entradasArp != null) {
            for (String cand : candidatos) {
                if (entradasArp.stream().anyMatch(a -> cand.equals(a.ip()))) {
                    return Optional.of(cand);
                }
            }
        }
        if (mgmtIp != null && mgmtIp.contains(".")) {
            String prefix24 = mgmtIp.substring(0, mgmtIp.lastIndexOf('.') + 1);
            for (String cand : candidatos) {
                if (cand.startsWith(prefix24)) {
                    return Optional.of(cand);
                }
            }
        }
        return Optional.of(candidatos.iterator().next());
    }

    /** Extrai identidade de OLTs 8820I / 8820G ("interface show" + "firmware show" + banner). */
    public SwitchIdentity parseDeviceInfo8820(
            String ip, String hostnamePadrao, String interfaceOut, String firmwareOut, String bannerInicial) {
        String combinado = (bannerInicial != null ? bannerInicial : "") + "\n" + (firmwareOut != null ? firmwareOut : "");
        String lower = combinado.toLowerCase();
        boolean ascii8820i = bannerInicial != null
                && bannerInicial.contains("| |__| | |____| | | (_) | (_) / /_| |_| | |");
        String productId = (lower.contains("8820i") || ascii8820i) ? "OLT 8820I"
                : lower.contains("8820g") ? "OLT 8820G"
                : "OLT 8820";

        String deviceName = null;
        if (bannerInicial != null) {
            Matcher mPrompt = Pattern.compile("(?m)^([a-zA-Z0-9_\\-.]+)[>#]\\s*$").matcher(bannerInicial);
            while (mPrompt.find()) {
                String cand = mPrompt.group(1).trim();
                if (!cand.equalsIgnoreCase("intelbras-olt") && !cand.equalsIgnoreCase("User")) {
                    deviceName = cand;
                }
            }
        }
        if (deviceName == null || deviceName.isBlank()) {
            if (hostnamePadrao != null && !hostnamePadrao.isBlank() && !"OLT".equalsIgnoreCase(hostnamePadrao)
                    && !"intelbras-olt".equalsIgnoreCase(hostnamePadrao)) {
                deviceName = hostnamePadrao;
            } else {
                deviceName = productId.replace(' ', '-');
            }
        }

        String mac = null;
        if (interfaceOut != null && !interfaceOut.isBlank()) {
            Pattern pMac = Pattern.compile(MAC_ANY_REGEX);
            String fallbackMac = null;
            for (String line : interfaceOut.split("\\R")) {
                Matcher m = pMac.matcher(line);
                if (!m.find()) continue;
                String fmt = Serie3000Parser.formatarMacPadrao(m.group(1)).toUpperCase();
                if ("00:00:00:00:00:00".equals(fmt)) continue;
                if (fallbackMac == null) fallbackMac = fmt;
                if ((ip != null && line.contains(ip)) || line.toLowerCase().contains("inband")) {
                    mac = fmt;
                    break;
                }
            }
            if (mac == null) mac = fallbackMac;
        }
        return new SwitchIdentity(deviceName, null, mac, productId, ip);
    }

    /** Faz o parse da saída "bridge show mac all" das OLTs 8820I / 8820G. */
    public List<MacEntry> parseMacTable8820(String switchId, String rawOutput) {
        List<MacEntry> entries = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) return entries;

        Pattern mac8820 = Pattern.compile(
                "(?i)^.*?" + MAC_ANY_REGEX + "\\s+(x?eth|gpon|epon|pon|ge|xe)\\s*(\\d+(?:/\\d+)?)\\s+vlan\\s+(\\d{1,4})(?:\\s+(static|dynamic))?.*$");
        for (String line : rawOutput.split("[\\r\\n]+")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("Loading")) continue;
            Matcher m = mac8820.matcher(trimmed);
            if (!m.matches()) continue;
            String mac = Serie3000Parser.formatarMacPadrao(m.group(1));
            String porta = (m.group(2) + m.group(3)).toUpperCase();
            int vlan = Integer.parseInt(m.group(4));
            String status = m.group(5) != null ? m.group(5).toLowerCase() : "dynamic";
            entries.add(new MacEntry(switchId, mac, vlan, status, porta));
        }
        return entries;
    }
}

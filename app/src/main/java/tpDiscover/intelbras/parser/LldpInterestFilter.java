package tpDiscover.intelbras.parser;

import tpDiscover.intelbras.modelos.GatewayInfo;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.OuiLookup;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Filtra vizinhos LLDP para reter somente os dispositivos de interesse da topologia:
 *  1) Equipamentos já coletados (switches, OLTs, APs) para confirmação exata de enlaces;
 *  2) Roteador de uplink / Gateway;
 *  3) Access Points (APs);
 *  4) Rádios Intelbras (PTP/PtMP/WOM/APC/WOG/CPE).
 *
 * Estações finais, telefones IP, impressoras e outros hosts de acesso são descartados.
 */
public final class LldpInterestFilter {

    private static final Pattern AP_MODELO_PATTERN = Pattern.compile(
            "(?i)\\b(ap[-\\s]?\\d{3,4}|access\\s*point|zeusos|hotspot\\s*\\d+|unifi|uap|eap\\d+)\\b"
    );
    private static final Pattern RADIO_INTELBRAS_PATTERN = Pattern.compile(
            "(?i)\\b(wom\\s*\\d*|apc\\s*\\d*|wog\\s*\\d*|ptp\\s*\\d*|cpe|ipoll|mimo\\s*\\d*|5a\\-?\\d+|5m\\-?\\d+|radio\\s*intelbras|wireless\\s*bridge)\\b"
    );
    private static final Pattern ROTEADOR_EXPLICITO_PATTERN = Pattern.compile(
            "(?i)\\b(r300\\d+g?|routeros|mikrotik|fortigate|fortinet|pfsense|opnsense|edgerouter|vyos|gateway|firewall|roteador)\\b"
    );
    private static final Pattern SWITCH_GENERICO_PATTERN = Pattern.compile(
            "(?i)\\b(s23\\d{2}|s33\\d{2}|s30\\d{2}|s20\\d{2}|s21\\d{2}|sg\\s*\\d{3,4}|olt|an600\\d|an5516|8820[ig]?|4840e?|4880e?|broadband\\s+network\\s+platform|comware)\\b"
    );

    private LldpInterestFilter() {
    }

    public static Optional<SwitchIdentity> resolverSwitchColetado(
            LldpNeighbor vizinho,
            Collection<SwitchIdentity> switchesColetados
    ) {
        if (vizinho == null || switchesColetados == null || switchesColetados.isEmpty()) {
            return Optional.empty();
        }

        // 1) Match por Chassis MAC (mais confiável)
        if (vizinho.possuiChassisMacValido()) {
            SwitchIdentity melhor = null;
            long menorDist = Long.MAX_VALUE;
            for (SwitchIdentity sw : switchesColetados) {
                if (MacUtils.pertenceAoSwitch(sw.macNormalizado(), vizinho.chassisMacNormalizado())) {
                    long dist = MacUtils.distancia(sw.macNormalizado(), vizinho.chassisMacNormalizado());
                    if (melhor == null || dist < menorDist) {
                        melhor = sw;
                        menorDist = dist;
                    }
                }
            }
            if (melhor != null) {
                return Optional.of(melhor);
            }
        }

        // 2) Match por Management IP
        if (vizinho.managementIp() != null && !vizinho.managementIp().isBlank()) {
            String mgmtIp = vizinho.managementIp().trim();
            for (SwitchIdentity sw : switchesColetados) {
                if (mgmtIp.equals(extrairIpSemPorta(sw.ip()))) {
                    return Optional.of(sw);
                }
            }
        }

        // 3) Match por System Name único e não-genérico
        if (vizinho.systemName() != null && !vizinho.systemName().isBlank()) {
            String sysName = vizinho.systemName().trim();
            if (!ehNomeGenerico(sysName)) {
                SwitchIdentity unico = null;
                int matches = 0;
                for (SwitchIdentity sw : switchesColetados) {
                    if (sw.deviceName() != null && sysName.equalsIgnoreCase(sw.deviceName().trim())) {
                        unico = sw;
                        matches++;
                    }
                }
                if (matches == 1) {
                    return Optional.of(unico);
                }
            }
        }

        return Optional.empty();
    }

    public static LldpNeighbor.CategoriaInteresse classificar(
            LldpNeighbor vizinho,
            Collection<SwitchIdentity> switchesColetados,
            Optional<GatewayInfo> gatewayOpt
    ) {
        if (vizinho == null) {
            return LldpNeighbor.CategoriaInteresse.OUTRO_IGNORAR;
        }

        if (resolverSwitchColetado(vizinho, switchesColetados).isPresent()) {
            return LldpNeighbor.CategoriaInteresse.EQUIPAMENTO_COLETADO;
        }

        // Descarta telefones IP e estações finais explicitamente
        if (vizinho.possuiCapability("Telephone") || vizinho.possuiCapability("Station Only")) {
            return LldpNeighbor.CategoriaInteresse.OUTRO_IGNORAR;
        }

        String texto = ((vizinho.systemName() != null ? vizinho.systemName() : "") + " "
                + (vizinho.systemDescription() != null ? vizinho.systemDescription() : "")).trim();

        // 1) Roteador de uplink / Gateway
        if (ehRoteadorDeUplink(vizinho, texto, gatewayOpt)) {
            return LldpNeighbor.CategoriaInteresse.ROTEADOR_UPLINK;
        }

        // 2) Access Point
        if (vizinho.possuiCapability("WLAN") || AP_MODELO_PATTERN.matcher(texto).find()) {
            return LldpNeighbor.CategoriaInteresse.ACCESS_POINT;
        }

        // 3) Rádios Intelbras (PTP/PtMP/WOM/APC/WOG/CPE)
        boolean ouiIntelbras = vizinho.possuiChassisMacValido()
                && OuiLookup.ehIntelbras(vizinho.chassisMacNormalizado());
        if (RADIO_INTELBRAS_PATTERN.matcher(texto).find()
                || (ouiIntelbras && !SWITCH_GENERICO_PATTERN.matcher(texto).find()
                    && (vizinho.possuiCapability("WLAN") || vizinho.possuiCapability("Repeater")
                        || texto.toLowerCase().contains("bridge") || texto.toLowerCase().contains("cpe")))) {
            return LldpNeighbor.CategoriaInteresse.RADIO_INTELBRAS;
        }

        // 4) Switch gerenciável ou OLT externo não coletado (sem senha / falha auth / CLI fechada)
        if (SWITCH_GENERICO_PATTERN.matcher(texto).find()
                || (ouiIntelbras && vizinho.possuiCapability("Bridge"))
                || texto.toLowerCase().matches(".*\\b(switch|olt|catalyst|aruba|edgeswitch|crs\\d+|css\\d+)\\b.*")) {
            return LldpNeighbor.CategoriaInteresse.SWITCH_OU_OLT_EXTERNO;
        }

        return LldpNeighbor.CategoriaInteresse.OUTRO_IGNORAR;
    }

    private static boolean ehRoteadorDeUplink(
            LldpNeighbor vizinho,
            String texto,
            Optional<GatewayInfo> gatewayOpt
    ) {
        if (gatewayOpt != null && gatewayOpt.isPresent()) {
            GatewayInfo gw = gatewayOpt.get();
            if (vizinho.possuiChassisMacValido()
                    && MacUtils.pertenceAoSwitch(gw.macNormalizado(), vizinho.chassisMacNormalizado())) {
                return true;
            }
            if (gw.ip() != null && !gw.ip().isBlank()
                    && gw.ip().equals(vizinho.managementIp())) {
                return true;
            }
        }

        if (ROTEADOR_EXPLICITO_PATTERN.matcher(texto).find()) {
            return true;
        }

        // Switches L2/L3 costumam anunciar "Bridge, Router"; roteador dedicado anuncia "Router" sem "Bridge"
        boolean capRouter = vizinho.possuiCapability("Router");
        boolean capBridge = vizinho.possuiCapability("Bridge");
        return capRouter && !capBridge && !SWITCH_GENERICO_PATTERN.matcher(texto).find();
    }

    private static boolean ehNomeGenerico(String nome) {
        String n = nome.trim();
        return n.equalsIgnoreCase("INTELBRAS")
                || n.equalsIgnoreCase("Switch")
                || n.equalsIgnoreCase("OpenWrt")
                || n.equalsIgnoreCase("Router")
                || n.equalsIgnoreCase("NOMEDOPRODUTO");
    }

    private static String extrairIpSemPorta(String ip) {
        if (ip == null) return "";
        String s = ip.trim();
        int idx = s.indexOf(':');
        return idx > 0 ? s.substring(0, idx) : s;
    }
}

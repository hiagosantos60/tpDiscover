package tpDiscover.intelbras.modelos;

import tpDiscover.intelbras.util.Compat;

/**
 * Representa o Roteador/Gateway identificado na rede do cliente (IP opcional + endereço MAC).
 */
public final class GatewayInfo {
    private final String ip;
    private final String mac;

    public GatewayInfo(String ip, String mac) {
        this.ip = ip;
        this.mac = mac;
    }

    public String ip() {
        return ip;
    }

    public String mac() {
        return mac;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof GatewayInfo)) return false;
        GatewayInfo o = (GatewayInfo) obj;
        return java.util.Objects.equals(ip, o.ip)
                && java.util.Objects.equals(mac, o.mac);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(ip, mac);
    }

    @Override
    public String toString() {
        return "GatewayInfo[ip=" + ip + ", mac=" + mac + "]";
    }

    public String macNormalizado() {
        return MacUtils.normalizar(mac);
    }

    /**
     * Formata o rótulo completo do roteador/gateway com identificação de fabricante (OUI), ex:
     * "Roteador/Gateway 10.1.40.1 [0009-0f09-1e0a (Fortinet)]"
     */
    /** Rótulo curto para tabelas: "Gateway 10.1.40.1 (Fortinet)". */
    public String rotuloCurto() {
        String fabricante = mac != null ? OuiLookup.identificarFabricante(mac).orElse("") : "";
        String base = (ip != null && !Compat.isBlank(ip)) ? "Gateway " + ip : "Gateway";
        return Compat.isBlank(fabricante) ? base : base + " (" + fabricante + ")";
    }

    public String rotuloVisual() {
        String macExibicao = mac != null ? OuiLookup.formatar(mac.toLowerCase()) : "N/D";
        if (ip != null && !Compat.isBlank(ip)) {
            return "Roteador/Gateway " + ip + " [" + macExibicao + "]";
        }
        return "Roteador/Gateway [" + macExibicao + "]";
    }
}

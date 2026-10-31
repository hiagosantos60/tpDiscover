package tpDiscover.intelbras.modelos;

/**
 * Representa o Roteador/Gateway identificado na rede do cliente (IP opcional + endereço MAC).
 */
public record GatewayInfo(String ip, String mac) {

    public String macNormalizado() {
        return MacUtils.normalizar(mac);
    }

    /**
     * Formata o rótulo completo do roteador/gateway com identificação de fabricante (OUI), ex:
     * "Roteador/Gateway 10.1.40.1 [0009-0f09-1e0a (Fortinet)]"
     */
    public String rotuloVisual() {
        String macExibicao = mac != null ? OuiLookup.formatar(mac.toLowerCase()) : "N/D";
        if (ip != null && !ip.isBlank()) {
            return "Roteador/Gateway " + ip + " [" + macExibicao + "]";
        }
        return "Roteador/Gateway [" + macExibicao + "]";
    }
}

package tpDiscover.intelbras.modelos;

/**
 * Entrada da tabela ARP coletada de um equipamento (IP -> MAC + porta física opcional).
 */
public record ArpEntry(String ip, String mac, String port) {

    public String macNormalizado() {
        return MacUtils.normalizar(mac);
    }
}

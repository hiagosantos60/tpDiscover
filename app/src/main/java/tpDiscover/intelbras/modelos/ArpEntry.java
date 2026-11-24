package tpDiscover.intelbras.modelos;

/**
 * Entrada da tabela ARP coletada de um equipamento (IP -> MAC + porta física opcional).
 */
public final class ArpEntry {
    private final String ip;
    private final String mac;
    private final String port;

    public ArpEntry(String ip, String mac, String port) {
        this.ip = ip;
        this.mac = mac;
        this.port = port;
    }

    public String ip() {
        return ip;
    }

    public String mac() {
        return mac;
    }

    public String port() {
        return port;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ArpEntry)) return false;
        ArpEntry o = (ArpEntry) obj;
        return java.util.Objects.equals(ip, o.ip)
                && java.util.Objects.equals(mac, o.mac)
                && java.util.Objects.equals(port, o.port);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(ip, mac, port);
    }

    @Override
    public String toString() {
        return "ArpEntry[ip=" + ip + ", mac=" + mac + ", port=" + port + "]";
    }

    public String macNormalizado() {
        return MacUtils.normalizar(mac);
    }
}

package tpDiscover.intelbras.modelos;

/**
 * Representa uma linha da tabela MAC de um switch (saída de "display mac-address").
 *
 * Exemplo de linha original:
 *   0009-0f09-1e0a   1          Learned          GE1/0/23                 Y
 */
public final class MacEntry {
    private final String switchId;
    private final String mac;
    private final int vlanId;
    private final String state;
    private final String port;

    public MacEntry(String switchId, String mac, int vlanId, String state, String port) {
        this.switchId = switchId;
        this.mac = mac;
        this.vlanId = vlanId;
        this.state = state;
        this.port = port;
    }

    public String switchId() {
        return switchId;
    }

    public String mac() {
        return mac;
    }

    public int vlanId() {
        return vlanId;
    }

    public String state() {
        return state;
    }

    public String port() {
        return port;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MacEntry)) return false;
        MacEntry o = (MacEntry) obj;
        return java.util.Objects.equals(switchId, o.switchId)
                && java.util.Objects.equals(mac, o.mac)
                && vlanId == o.vlanId
                && java.util.Objects.equals(state, o.state)
                && java.util.Objects.equals(port, o.port);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(switchId, mac, vlanId, state, port);
    }

    @Override
    public String toString() {
        return "MacEntry[switchId=" + switchId + ", mac=" + mac + ", vlanId=" + vlanId + ", state=" + state + ", port=" + port + "]";
    }

    /**
     * Normaliza o MAC para um formato único (minúsculo, sem separadores),
     * útil como chave de comparação entre switches diferentes.
     */
    public String macNormalizado() {
        return mac.toLowerCase().replace("-", "").replace(":", "").replace(".", "");
    }
}

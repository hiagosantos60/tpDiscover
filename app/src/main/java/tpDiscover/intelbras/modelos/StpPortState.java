package tpDiscover.intelbras.modelos;

/**
 * Estado STP (Spanning Tree Protocol) de uma porta ou do switch coletado.
 */
public final class StpPortState {
    private final String switchId;
    private final String port;
    private final String role;
    private final String state;
    private final boolean stpHabilitado;
    private final String rootBridgeMac;

    public StpPortState(String switchId, String port, String role, String state, boolean stpHabilitado, String rootBridgeMac) {
        this.switchId = switchId;
        this.port = port;
        this.role = role;
        this.state = state;
        this.stpHabilitado = stpHabilitado;
        this.rootBridgeMac = rootBridgeMac;
    }

    public String switchId() {
        return switchId;
    }

    public String port() {
        return port;
    }

    public String role() {
        return role;
    }

    public String state() {
        return state;
    }

    public boolean stpHabilitado() {
        return stpHabilitado;
    }

    public String rootBridgeMac() {
        return rootBridgeMac;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof StpPortState)) return false;
        StpPortState o = (StpPortState) obj;
        return java.util.Objects.equals(switchId, o.switchId)
                && java.util.Objects.equals(port, o.port)
                && java.util.Objects.equals(role, o.role)
                && java.util.Objects.equals(state, o.state)
                && stpHabilitado == o.stpHabilitado
                && java.util.Objects.equals(rootBridgeMac, o.rootBridgeMac);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(switchId, port, role, state, stpHabilitado, rootBridgeMac);
    }

    @Override
    public String toString() {
        return "StpPortState[switchId=" + switchId + ", port=" + port + ", role=" + role + ", state=" + state + ", stpHabilitado=" + stpHabilitado + ", rootBridgeMac=" + rootBridgeMac + "]";
    }

    public boolean estaBloqueada() {
        if (state == null) return false;
        String s = state.trim().toUpperCase();
        return s.contains("DISCARD") || s.contains("BLK") || s.contains("BLOCK") || s.contains("LISTEN");
    }

    public boolean estaEncaminhando() {
        if (state == null) return false;
        String s = state.trim().toUpperCase();
        return s.contains("FORWARD") || s.equals("FWD");
    }
}

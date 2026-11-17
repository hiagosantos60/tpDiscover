package tpDiscover.intelbras.modelos;

/**
 * Estado STP (Spanning Tree Protocol) de uma porta ou do switch coletado.
 */
public record StpPortState(
        String switchId,
        String port,
        String role,
        String state,
        boolean stpHabilitado,
        String rootBridgeMac
) {
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

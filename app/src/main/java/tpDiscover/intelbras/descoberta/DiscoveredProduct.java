package tpDiscover.intelbras.descoberta;

import java.util.List;

/**
 * Representa um host respondente identificado durante o scan de rede e classificado por fingerprint.
 */
public record DiscoveredProduct(
        String ip,
        List<Integer> portasAbertas,
        FamiliaProduto familia,
        String modeloInferido,
        String bannerOuTitulo,
        String macConhecido,
        String fabricanteOui,
        boolean suportaColetaCli,
        String protocoloPreferido,
        Integer portaCliPreferida
) {

    public enum FamiliaProduto {
        SWITCH_COMWARE("Switch Comware", true),
        SWITCH_S30("Switch Série 3000/Max", true),
        SWITCH_S2050_LITE("Switch S2050/S30-Lite/PoE-Max", true),
        SWITCH_SG_MR_L2PLUS("Switch SG MR L2+", true),
        SWITCH_SG_MR("Switch SG MR", true),
        OLT("OLT Intelbras", true),
        ACCESS_POINT("Access Point / Rádio Intelbras", true),
        ROTEADOR_GATEWAY("Roteador / Gateway", false),
        DESCONHECIDO_IGNORAR("Outro dispositivo (ignorado)", false);

        private final String rotulo;
        private final boolean coletaCliSuportada;

        FamiliaProduto(String rotulo, boolean coletaCliSuportada) {
            this.rotulo = rotulo;
            this.coletaCliSuportada = coletaCliSuportada;
        }

        public String rotulo() {
            return rotulo;
        }

        public boolean coletaCliSuportada() {
            return coletaCliSuportada;
        }
    }

    public DiscoveredProduct {
        ip = ip != null ? ip.trim() : "";
        portasAbertas = portasAbertas != null ? List.copyOf(portasAbertas) : List.of();
        familia = familia != null ? familia : FamiliaProduto.DESCONHECIDO_IGNORAR;
        modeloInferido = modeloInferido != null ? modeloInferido.trim() : "";
        bannerOuTitulo = bannerOuTitulo != null ? bannerOuTitulo.trim() : "";
        macConhecido = macConhecido != null ? macConhecido.trim() : "";
        fabricanteOui = fabricanteOui != null ? fabricanteOui.trim() : "";
        protocoloPreferido = protocoloPreferido != null ? protocoloPreferido.trim() : "";
    }

    public boolean ehProdutoRedeInteresse() {
        return familia != FamiliaProduto.DESCONHECIDO_IGNORAR;
    }

    public String resumoCurto() {
        StringBuilder sb = new StringBuilder();
        sb.append(ip).append(" -> ").append(familia.rotulo());
        if (!modeloInferido.isBlank()) {
            sb.append(" [").append(modeloInferido).append("]");
        }
        sb.append(" | portas=").append(portasAbertas);
        if (!protocoloPreferido.isBlank()) {
            sb.append(" | pref=").append(protocoloPreferido);
        }
        if (!bannerOuTitulo.isBlank()) {
            sb.append(" | ").append(bannerOuTitulo);
        }
        return sb.toString();
    }
}

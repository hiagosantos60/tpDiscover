package tpDiscover.intelbras.descoberta;

import java.util.List;

import tpDiscover.intelbras.util.Compat;
/**
 * Representa um host respondente identificado durante o scan de rede e classificado por fingerprint.
 */
public final class DiscoveredProduct {
    private final String ip;
    private final List<Integer> portasAbertas;
    private final FamiliaProduto familia;
    private final String modeloInferido;
    private final String bannerOuTitulo;
    private final String macConhecido;
    private final String fabricanteOui;
    private final boolean suportaColetaCli;
    private final String protocoloPreferido;
    private final Integer portaCliPreferida;

    public DiscoveredProduct(String ip, List<Integer> portasAbertas, FamiliaProduto familia, String modeloInferido, String bannerOuTitulo, String macConhecido, String fabricanteOui, boolean suportaColetaCli, String protocoloPreferido, Integer portaCliPreferida) {
        ip = ip != null ? ip.trim() : "";
        portasAbertas = portasAbertas != null ? Compat.listCopyOf(portasAbertas) : Compat.listOf();
        familia = familia != null ? familia : FamiliaProduto.DESCONHECIDO_IGNORAR;
        modeloInferido = modeloInferido != null ? modeloInferido.trim() : "";
        bannerOuTitulo = bannerOuTitulo != null ? bannerOuTitulo.trim() : "";
        macConhecido = macConhecido != null ? macConhecido.trim() : "";
        fabricanteOui = fabricanteOui != null ? fabricanteOui.trim() : "";
        protocoloPreferido = protocoloPreferido != null ? protocoloPreferido.trim() : "";
        this.ip = ip;
        this.portasAbertas = portasAbertas;
        this.familia = familia;
        this.modeloInferido = modeloInferido;
        this.bannerOuTitulo = bannerOuTitulo;
        this.macConhecido = macConhecido;
        this.fabricanteOui = fabricanteOui;
        this.suportaColetaCli = suportaColetaCli;
        this.protocoloPreferido = protocoloPreferido;
        this.portaCliPreferida = portaCliPreferida;
    }

    public String ip() {
        return ip;
    }

    public List<Integer> portasAbertas() {
        return portasAbertas;
    }

    public FamiliaProduto familia() {
        return familia;
    }

    public String modeloInferido() {
        return modeloInferido;
    }

    public String bannerOuTitulo() {
        return bannerOuTitulo;
    }

    public String macConhecido() {
        return macConhecido;
    }

    public String fabricanteOui() {
        return fabricanteOui;
    }

    public boolean suportaColetaCli() {
        return suportaColetaCli;
    }

    public String protocoloPreferido() {
        return protocoloPreferido;
    }

    public Integer portaCliPreferida() {
        return portaCliPreferida;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DiscoveredProduct)) return false;
        DiscoveredProduct o = (DiscoveredProduct) obj;
        return java.util.Objects.equals(ip, o.ip)
                && java.util.Objects.equals(portasAbertas, o.portasAbertas)
                && java.util.Objects.equals(familia, o.familia)
                && java.util.Objects.equals(modeloInferido, o.modeloInferido)
                && java.util.Objects.equals(bannerOuTitulo, o.bannerOuTitulo)
                && java.util.Objects.equals(macConhecido, o.macConhecido)
                && java.util.Objects.equals(fabricanteOui, o.fabricanteOui)
                && suportaColetaCli == o.suportaColetaCli
                && java.util.Objects.equals(protocoloPreferido, o.protocoloPreferido)
                && java.util.Objects.equals(portaCliPreferida, o.portaCliPreferida);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(ip, portasAbertas, familia, modeloInferido, bannerOuTitulo, macConhecido, fabricanteOui, suportaColetaCli, protocoloPreferido, portaCliPreferida);
    }

    @Override
    public String toString() {
        return "DiscoveredProduct[ip=" + ip + ", portasAbertas=" + portasAbertas + ", familia=" + familia + ", modeloInferido=" + modeloInferido + ", bannerOuTitulo=" + bannerOuTitulo + ", macConhecido=" + macConhecido + ", fabricanteOui=" + fabricanteOui + ", suportaColetaCli=" + suportaColetaCli + ", protocoloPreferido=" + protocoloPreferido + ", portaCliPreferida=" + portaCliPreferida + "]";
    }

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

    public boolean ehProdutoRedeInteresse() {
        return familia != FamiliaProduto.DESCONHECIDO_IGNORAR;
    }

    public String resumoCurto() {
        StringBuilder sb = new StringBuilder();
        sb.append(ip).append(" -> ").append(familia.rotulo());
        if (!Compat.isBlank(modeloInferido)) {
            sb.append(" [").append(modeloInferido).append("]");
        }
        sb.append(" | portas=").append(portasAbertas);
        if (!Compat.isBlank(protocoloPreferido)) {
            sb.append(" | pref=").append(protocoloPreferido);
        }
        if (!Compat.isBlank(bannerOuTitulo)) {
            sb.append(" | ").append(bannerOuTitulo);
        }
        return sb.toString();
    }
}

package tpDiscover.intelbras.modelos;

/**
 * Diagnóstico somente-leitura de loop L2 ou redundância controlada por STP.
 */
public final class LoopDiagnostic {
    private final Severidade severidade;
    private final TipoLoop tipo;
    private final String switchOrigemId;
    private final String portaOrigem;
    private final String switchDestinoId;
    private final String portaDestino;
    private final String evidencia;
    private final String acaoRecomendada;

    public LoopDiagnostic(Severidade severidade, TipoLoop tipo, String switchOrigemId, String portaOrigem, String switchDestinoId, String portaDestino, String evidencia, String acaoRecomendada) {
        this.severidade = severidade;
        this.tipo = tipo;
        this.switchOrigemId = switchOrigemId;
        this.portaOrigem = portaOrigem;
        this.switchDestinoId = switchDestinoId;
        this.portaDestino = portaDestino;
        this.evidencia = evidencia;
        this.acaoRecomendada = acaoRecomendada;
    }

    public Severidade severidade() {
        return severidade;
    }

    public TipoLoop tipo() {
        return tipo;
    }

    public String switchOrigemId() {
        return switchOrigemId;
    }

    public String portaOrigem() {
        return portaOrigem;
    }

    public String switchDestinoId() {
        return switchDestinoId;
    }

    public String portaDestino() {
        return portaDestino;
    }

    public String evidencia() {
        return evidencia;
    }

    public String acaoRecomendada() {
        return acaoRecomendada;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LoopDiagnostic)) return false;
        LoopDiagnostic o = (LoopDiagnostic) obj;
        return java.util.Objects.equals(severidade, o.severidade)
                && java.util.Objects.equals(tipo, o.tipo)
                && java.util.Objects.equals(switchOrigemId, o.switchOrigemId)
                && java.util.Objects.equals(portaOrigem, o.portaOrigem)
                && java.util.Objects.equals(switchDestinoId, o.switchDestinoId)
                && java.util.Objects.equals(portaDestino, o.portaDestino)
                && java.util.Objects.equals(evidencia, o.evidencia)
                && java.util.Objects.equals(acaoRecomendada, o.acaoRecomendada);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(severidade, tipo, switchOrigemId, portaOrigem, switchDestinoId, portaDestino, evidencia, acaoRecomendada);
    }

    @Override
    public String toString() {
        return "LoopDiagnostic[severidade=" + severidade + ", tipo=" + tipo + ", switchOrigemId=" + switchOrigemId + ", portaOrigem=" + portaOrigem + ", switchDestinoId=" + switchDestinoId + ", portaDestino=" + portaDestino + ", evidencia=" + evidencia + ", acaoRecomendada=" + acaoRecomendada + "]";
    }

    public enum Severidade {
        CRITICO,
        AVISO
    }

    public enum TipoLoop {
        SELF_LOOP_LLDP("Auto-loop direto (LLDP)"),
        SELF_MAC_PORTA("Auto-MAC em porta física"),
        LOOPBACK_HARDWARE("Loopback-Detection do switch"),
        MAC_FLAPPING("MAC duplicado / flapping em portas"),
        ANEL_SEM_STP("Anel L2 sem bloqueio STP"),
        REDUNDANCIA_STP("Enlace redundante bloqueado por STP");

        private final String descricao;

        TipoLoop(String descricao) {
            this.descricao = descricao;
        }

        public String descricao() {
            return descricao;
        }
    }

    public boolean ehLoopAtivo() {
        return severidade == Severidade.CRITICO;
    }

    public boolean ehSelfLoop() {
        return switchOrigemId != null && switchOrigemId.equalsIgnoreCase(switchDestinoId);
    }
}

package tpDiscover.intelbras.modelos;

/**
 * Diagnóstico somente-leitura de loop L2 ou redundância controlada por STP.
 */
public record LoopDiagnostic(
        Severidade severidade,
        TipoLoop tipo,
        String switchOrigemId,
        String portaOrigem,
        String switchDestinoId,
        String portaDestino,
        String evidencia,
        String acaoRecomendada
) {
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

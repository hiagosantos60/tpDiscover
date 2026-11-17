package tpDiscover.intelbras.connection;

/**
 * O equipamento encerrou a sessão no meio da sequência de login (ex.: switches que derrubam a conexão
 * após 1–3 senhas erradas). Informa quantas credenciais da cadeia já foram consumidas para que o cliente
 * reconecte e continue a partir da próxima, sem repetir as que falharam.
 */
class LoginInterrompidoException extends SwitchConnectionException {

    private final int credenciaisConsumidas;

    LoginInterrompidoException(String mensagem, int credenciaisConsumidas, Throwable causa) {
        super(Tipo.FALHA_AUTENTICACAO, mensagem, causa);
        this.credenciaisConsumidas = Math.max(1, credenciaisConsumidas);
    }

    int credenciaisConsumidas() {
        return credenciaisConsumidas;
    }
}

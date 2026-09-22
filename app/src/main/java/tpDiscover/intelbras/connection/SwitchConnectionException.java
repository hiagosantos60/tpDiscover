package tpDiscover.intelbras.connection;

/**
 * Erro ao conectar ou se comunicar com um switch.
 *
 * O campo "tipo" permite que a camada de orquestração (quem chama o cliente)
 * decida o que fazer sem precisar fazer parsing de mensagem de erro:
 *  - FALHA_AUTENTICACAO -> credenciais erradas, provavelmente não adianta tentar de novo
 *  - TIMEOUT            -> switch demorou demais pra responder (rede lenta, dispositivo travado)
 *  - INALCANCAVEL       -> não deu nem pra abrir a conexão (IP errado, porta fechada, cabo desconectado)
 *  - ERRO_PROTOCOLO     -> algo inesperado no diálogo com o terminal (saída não reconhecida, etc.)
 */
public class SwitchConnectionException extends Exception {

    public enum Tipo {
        FALHA_AUTENTICACAO,
        TIMEOUT,
        INALCANCAVEL,
        ERRO_PROTOCOLO
    }

    private final Tipo tipo;

    public SwitchConnectionException(Tipo tipo, String mensagem, Throwable causa) {
        super(mensagem, causa);
        this.tipo = tipo;
    }

    public Tipo getTipo() {
        return tipo;
    }
}

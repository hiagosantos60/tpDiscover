package tpDiscover.intelbras.connection;

/**
 * Contrato comum para acessar um switch via CLI, independente do protocolo
 * (SSH ou Telnet). O resto do sistema (parser, repositório) trabalha só com
 * essa interface e não precisa saber qual implementação está por trás.
 */
public interface SwitchClient {

    /** Abre a conexão e autentica. Lança exceção tipada em caso de falha. */
    void connect() throws SwitchConnectionException;

    /**
     * Envia um comando e retorna a saída bruta do terminal (até o próximo prompt).
     * Pressupõe que connect() já foi chamado com sucesso.
     */
    String executarComando(String comando) throws SwitchConnectionException;

    /** Encerra a conexão. Não lança exceção — é chamado tipicamente em bloco finally. */
    void desconectar();
}

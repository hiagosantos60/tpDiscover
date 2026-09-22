package tpDiscover.intelbras.connection;

public record ConnectionConfig(
        String ip,
        String usuario,
        String senha,
        int portaSsh,
        int portaTelnet,
        long timeoutMillis
) {
    /** Valores padrão razoáveis: SSH 22, Telnet 23, timeout de 8s. */
    public static ConnectionConfig padrao(String ip, String usuario, String senha) {
        return new ConnectionConfig(ip, usuario, senha, 22, 23, 8_000);
    }
}

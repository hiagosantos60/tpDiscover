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

    /** Usa porta customizada para SSH/Telnet quando informada (ex: ip:65019), ou padrão 22/23. */
    public static ConnectionConfig comPorta(String ip, String usuario, String senha, Integer portaCustomizada) {
        if (portaCustomizada != null) {
            return new ConnectionConfig(ip, usuario, senha, portaCustomizada, portaCustomizada, 8_000);
        }
        return padrao(ip, usuario, senha);
    }
}

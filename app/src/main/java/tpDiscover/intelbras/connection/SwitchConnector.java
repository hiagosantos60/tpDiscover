package tpDiscover.intelbras.connection;

/**
 * Ponto de entrada único para conectar num switch: tenta SSH primeiro
 * (mais seguro e geralmente mais estável) e, se falhar, tenta Telnet
 * (comum em switches de cliente com configuração padrão de fábrica).
 *
 * Se AMBOS falharem, propaga a exceção do SSH (geralmente mais informativa,
 * já que SSH costuma ser a porta preferencial de gerência).
 */
public class SwitchConnector {

    public static SwitchClient conectar(ConnectionConfig config) throws SwitchConnectionException {
        SwitchClient ssh = new SshSwitchClient(config);
        try {
            ssh.connect();
            System.out.println("[INFO] Conectado via SSH em " + config.ip());
            return ssh;
        } catch (SwitchConnectionException erroSsh) {
            System.out.println("[AVISO] SSH falhou em " + config.ip()
                    + " (" + erroSsh.getTipo() + "): " + erroSsh.getMessage());

            // Se a falha foi de autenticação, Telnet provavelmente vai falhar do mesmo jeito
            // (a menos que as credenciais sejam diferentes por protocolo, o que é raro).
            // Mesmo assim tentamos, mas isso é um bom lugar para, no futuro, pular direto
            // se você tiver certeza de que as credenciais são as mesmas.
            System.out.println("[INFO] Tentando Telnet como alternativa em " + config.ip() + "...");

            SwitchClient telnet = new TelnetSwitchClient(config);
            try {
                telnet.connect();
                System.out.println("[INFO] Conectado via Telnet em " + config.ip());
                return telnet;
            } catch (SwitchConnectionException erroTelnet) {
                System.out.println("[AVISO] Telnet também falhou em " + config.ip()
                        + " (" + erroTelnet.getTipo() + "): " + erroTelnet.getMessage());
                // Propaga o erro do SSH, que costuma ser a via preferencial de gerência
                throw erroSsh;
            }
        }
    }
}

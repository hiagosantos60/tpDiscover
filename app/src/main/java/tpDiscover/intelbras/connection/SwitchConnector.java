package tpDiscover.intelbras.connection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import tpDiscover.intelbras.util.Compat;
/**
 * Ponto de entrada único para conectar em um dispositivo via CLI:
 *  1) Evita abrir e fechar conexão descartada na porta Telnet imediatamente antes do login
 *     (comportamento que trava/derruba VTYs únicos em switches SG antigos).
 *  2) Respeita {@code preferirTelnet} quando o fingerprint identificou SSH ausente/incompatível.
 *  3) Evita reenviar as mesmas credenciais no protocolo alternativo após falha explícita de senha.
 */
public class SwitchConnector {

    public static SwitchClient conectar(ConnectionConfig config) throws SwitchConnectionException {
        int probeMs = config.probeTimeoutMillis() > 0 ? config.probeTimeoutMillis() : 1_200;

        if (config.portaSsh() == config.portaTelnet()) {
            if (!portaTcpAberta(config.ip(), config.portaSsh(), probeMs)) {
                throw new SwitchConnectionException(
                        SwitchConnectionException.Tipo.INALCANCAVEL,
                        "Porta " + config.portaSsh() + " fechada ou inacessível em " + config.ip(),
                        null
                );
            }
            return conectarPortaUnica(config);
        }

        if (config.preferirTelnet()) {
            return conectarTelnetPrimeiro(config, probeMs);
        }

        boolean sshAberta = portaTcpAberta(config.ip(), config.portaSsh(), probeMs);
        if (sshAberta) {
            SwitchClient ssh = new SshSwitchClient(config);
            try {
                ssh.connect();
                return ssh;
            } catch (SwitchConnectionException erroSsh) {
                ssh.desconectar();
                if (erroSsh.getTipo() == SwitchConnectionException.Tipo.FALHA_AUTENTICACAO
                        && !config.tentarTelnetAposFalhaAuth()) {
                    throw erroSsh;
                }
                // Breve respiro para switches SG legados encerrarem o socket SSH antes do fallback Telnet
                TerminalStreamHelper.dormir(350);
                SwitchClient telnet = new TelnetSwitchClient(config);
                try {
                    telnet.connect();
                    return telnet;
                } catch (SwitchConnectionException erroTelnet) {
                    telnet.desconectar();
                    if (erroTelnet.getTipo() == SwitchConnectionException.Tipo.INALCANCAVEL) {
                        throw erroSsh;
                    }
                    if (erroTelnet.getTipo() == SwitchConnectionException.Tipo.FALHA_AUTENTICACAO) {
                        throw new SwitchConnectionException(
                                SwitchConnectionException.Tipo.FALHA_AUTENTICACAO,
                                "Telnet: " + erroTelnet.getMessage() + " | SSH: " + erroSsh.getMessage(),
                                erroTelnet);
                    }
                    throw new SwitchConnectionException(
                            erroSsh.getTipo(),
                            "SSH: " + erroSsh.getMessage() + " | Telnet: " + erroTelnet.getMessage(),
                            erroSsh);
                }
            }
        }

        SwitchClient telnet = new TelnetSwitchClient(config);
        try {
            telnet.connect();
            return telnet;
        } catch (SwitchConnectionException erroTelnet) {
            telnet.desconectar();
            if (erroTelnet.getTipo() == SwitchConnectionException.Tipo.INALCANCAVEL) {
                throw new SwitchConnectionException(
                        SwitchConnectionException.Tipo.INALCANCAVEL,
                        "Portas SSH (" + config.portaSsh() + ") e Telnet (" + config.portaTelnet()
                                + ") fechadas ou inacessíveis em " + config.ip(),
                        erroTelnet
                );
            }
            throw erroTelnet;
        }
    }

    private static SwitchClient conectarTelnetPrimeiro(ConnectionConfig config, int probeMs)
            throws SwitchConnectionException {
        SwitchClient telnet = new TelnetSwitchClient(config);
        try {
            telnet.connect();
            return telnet;
        } catch (SwitchConnectionException erroTelnet) {
            telnet.desconectar();
            if (erroTelnet.getTipo() == SwitchConnectionException.Tipo.FALHA_AUTENTICACAO
                    && !config.tentarTelnetAposFalhaAuth()) {
                throw erroTelnet;
            }
            if (!portaTcpAberta(config.ip(), config.portaSsh(), probeMs)) {
                throw erroTelnet;
            }
            SwitchClient ssh = new SshSwitchClient(config);
            try {
                ssh.connect();
                return ssh;
            } catch (SwitchConnectionException erroSsh) {
                ssh.desconectar();
                if (erroSsh.getTipo() == SwitchConnectionException.Tipo.FALHA_AUTENTICACAO) {
                    throw new SwitchConnectionException(
                            SwitchConnectionException.Tipo.FALHA_AUTENTICACAO,
                            "SSH: " + erroSsh.getMessage() + " | Telnet: " + erroTelnet.getMessage(),
                            erroSsh);
                }
                throw new SwitchConnectionException(
                        erroTelnet.getTipo(),
                        "Telnet: " + erroTelnet.getMessage() + " | SSH: " + erroSsh.getMessage(),
                        erroTelnet);
            }
        }
    }

    private static SwitchClient conectarPortaUnica(ConnectionConfig config) throws SwitchConnectionException {
        SwitchClient ssh = new SshSwitchClient(config);
        try {
            ssh.connect();
            return ssh;
        } catch (SwitchConnectionException erroSsh) {
            ssh.desconectar();
            if (erroSsh.getTipo() == SwitchConnectionException.Tipo.FALHA_AUTENTICACAO
                    && !config.tentarTelnetAposFalhaAuth()) {
                throw erroSsh;
            }
            SwitchClient telnet = new TelnetSwitchClient(config);
            try {
                telnet.connect();
                return telnet;
            } catch (SwitchConnectionException erroTelnet) {
                telnet.desconectar();
                throw erroSsh;
            }
        }
    }

    /** Verifica rapidamente via TCP connect se a porta está aberta no host alvo. */
    public static boolean portaTcpAberta(String ip, int porta, int timeoutMs) {
        if (ip == null || Compat.isBlank(ip) || porta < 1 || porta > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip.trim(), porta), Math.max(150, timeoutMs));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

package tpDiscover.intelbras.connection;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cliente SSH para switches, OLTs e APs Intelbras.
 * Percorre a cadeia de credenciais (nova sessão a cada "Auth fail"), aceita password e keyboard-interactive,
 * trata login interativo dentro do shell (OLTs), elevação via 'enable', paginação e CLI restrita klish (APs).
 * Negocia tanto algoritmos modernos (ed25519, curve25519, rsa-sha2, aes-gcm) quanto legados
 * (ssh-dss, diffie-hellman-group1-sha1, aes-cbc/3des-cbc) de switches SG/IPSSH antigos.
 */
public class SshSwitchClient implements SwitchClient {

    private static final long TIMEOUT_ESPERA_PROMPT_MS = 30_000;

    static final String KEX = String.join(",",
            "curve25519-sha256", "curve25519-sha256@libssh.org",
            "ecdh-sha2-nistp256", "ecdh-sha2-nistp384", "ecdh-sha2-nistp521",
            "diffie-hellman-group-exchange-sha256", "diffie-hellman-group16-sha512",
            "diffie-hellman-group14-sha256", "diffie-hellman-group14-sha1",
            "diffie-hellman-group-exchange-sha1", "diffie-hellman-group1-sha1");
    static final String HOST_KEYS = String.join(",",
            "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
            "rsa-sha2-512", "rsa-sha2-256", "ssh-rsa", "ssh-dss");
    static final String CIPHERS = String.join(",",
            "aes128-ctr", "aes192-ctr", "aes256-ctr",
            "aes128-gcm@openssh.com", "aes256-gcm@openssh.com",
            "aes128-cbc", "aes192-cbc", "aes256-cbc", "3des-cbc");
    static final String MACS = String.join(",",
            "hmac-sha2-256-etm@openssh.com", "hmac-sha2-512-etm@openssh.com",
            "hmac-sha2-256", "hmac-sha2-512", "hmac-sha1", "hmac-sha1-96", "hmac-md5");

    private final ConnectionConfig config;
    private Session session;
    private ChannelShell channel;
    private InputStream in;
    private OutputStream out;
    private String bannerInicial = "";
    private CredencialLogin credencialEfetiva;
    private String lineEnding = "\n";

    public SshSwitchClient(ConnectionConfig config) {
        this.config = config;
    }

    /**
     * Terminador de linha conforme o servidor SSH: o IPSSH-6.x dos switches SG MR legados (ex.: SG 2404 MR,
     * "SSH-1.99-IPSSH-6.6.0") só executa comandos com CR, ecoando-os sem resposta quando recebem LF.
     * Os demais (Comware, Série 3000 IPSSH_5, OLTs, APs) seguem com LF, comportamento já validado.
     */
    static String terminadorParaServidor(String versaoServidor) {
        String v = versaoServidor != null ? versaoServidor.toLowerCase() : "";
        return v.contains("ipssh-6") ? "\r" : "\n";
    }

    @Override
    public void connect() throws SwitchConnectionException {
        List<CredencialLogin> cadeia = config.cadeiaCredenciais();
        int idx = 0;
        SwitchConnectionException ultimaFalhaAuth = null;

        while (idx < cadeia.size()) {
            CredencialLogin cred = cadeia.get(idx);
            try {
                abrirSessaoEShell(cred);
            } catch (JSchException e) {
                desconectar();
                SwitchConnectionException.Tipo tipo = classificarErroJsch(e);
                if (tipo == SwitchConnectionException.Tipo.FALHA_AUTENTICACAO) {
                    ultimaFalhaAuth = new SwitchConnectionException(tipo, mensagemAmigavel(e), e);
                    idx++;
                    continue;
                }
                throw new SwitchConnectionException(tipo, mensagemAmigavel(e), e);
            } catch (IOException e) {
                desconectar();
                throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                        "Erro de I/O ao abrir canal SSH com " + config.ip() + ": " + e.getMessage(), e);
            }

            List<CredencialLogin> restantes = cadeia.subList(idx, cadeia.size());
            try {
                long waitLogin = Math.max(8_000, config.timeoutMillis());
                String saidaInicial = lerAte(waitLogin,
                        TerminalStreamHelper.PROMPT,
                        TerminalStreamHelper.LOGIN_PROMPT,
                        TerminalStreamHelper.SENHA_PROMPT);
                TerminalStreamHelper.AuthResult auth = TerminalStreamHelper.autenticar(
                        in, out, config, restantes, "SSH", lineEnding,
                        TerminalStreamHelper.ModoTerminador.FIXO, saidaInicial, this::canalEncerrado);
                this.bannerInicial = auth.bannerCompleto();
                this.credencialEfetiva = auth.credencial();

                TerminalStreamHelper.entrarModoPrivilegiado(
                        in, out, config, "SSH", lineEnding, auth.ultimaSaida(),
                        TelnetSwitchClient.senhasParaEnable(auth), this::canalEncerrado);
                return;
            } catch (LoginInterrompidoException e) {
                // Login interno do shell (OLTs) derrubou a sessão: reabre com as credenciais seguintes
                desconectar();
                idx += e.credenciaisConsumidas();
                ultimaFalhaAuth = e;
            } catch (SwitchConnectionException e) {
                desconectar();
                throw e;
            } catch (IOException e) {
                desconectar();
                throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                        "Erro de I/O no shell SSH de " + config.ip() + ": " + e.getMessage(), e);
            }
        }

        throw new SwitchConnectionException(SwitchConnectionException.Tipo.FALHA_AUTENTICACAO,
                "Usuário ou senha incorretos para " + config.ip() + " (SSH) — "
                        + cadeia.size() + " credencial(is) testada(s)",
                ultimaFalhaAuth);
    }

    private void abrirSessaoEShell(CredencialLogin cred) throws JSchException, IOException {
        JSch jsch = new JSch();
        session = jsch.getSession(cred.usuario(), config.ip(), config.portaSsh());
        session.setPassword(cred.senha());
        session.setUserInfo(new SenhaUserInfo(cred.senha()));
        aplicarConfiguracaoAlgoritmos(session);

        int timeout = (int) Math.min(Integer.MAX_VALUE, config.timeoutMillis());
        session.setTimeout(timeout);
        session.connect(timeout);
        this.lineEnding = terminadorParaServidor(session.getServerVersion());

        channel = (ChannelShell) session.openChannel("shell");
        channel.setPtyType("vt100");
        in = channel.getInputStream();
        out = channel.getOutputStream();
        channel.connect(timeout);
    }

    static void aplicarConfiguracaoAlgoritmos(Session s) {
        s.setConfig("StrictHostKeyChecking", "no");
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        s.setConfig("kex", KEX);
        s.setConfig("server_host_key", HOST_KEYS);
        s.setConfig("cipher.s2c", CIPHERS);
        s.setConfig("cipher.c2s", CIPHERS);
        s.setConfig("mac.s2c", MACS);
        s.setConfig("mac.c2s", MACS);
        // Switches legados oferecem grupos DH-GEX de 1024 bits; o padrão moderno exige 2048
        s.setConfig("dhgex_min", "1024");
    }

    private void enviarLinha(String texto) throws IOException {
        out.write((texto + lineEnding).getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private SwitchConnectionException.Tipo classificarErroJsch(JSchException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("auth fail") || msg.contains("auth cancel") || msg.contains("authentication")) {
            return SwitchConnectionException.Tipo.FALHA_AUTENTICACAO;
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return SwitchConnectionException.Tipo.TIMEOUT;
        }
        if (msg.contains("connection refused") || msg.contains("unreachable")
                || msg.contains("no route") || msg.contains("unknownhost")) {
            return SwitchConnectionException.Tipo.INALCANCAVEL;
        }
        return SwitchConnectionException.Tipo.ERRO_PROTOCOLO;
    }

    private String mensagemAmigavel(JSchException e) {
        switch (classificarErroJsch(e)) {
            case FALHA_AUTENTICACAO:
                return "Usuário ou senha incorretos para " + config.ip() + " (SSH)";
            case TIMEOUT:
                return "Timeout ao conectar em " + config.ip() + " - verifique se o IP está acessível";
            case INALCANCAVEL:
                return "Não foi possível alcançar " + config.ip() + ":" + config.portaSsh()
                    + " - SSH pode estar desabilitado no equipamento, ou IP/rede incorretos";
            case ERRO_PROTOCOLO:
                return "Falha SSH ao conectar em " + config.ip() + ": " + e.getMessage();
            default:
                throw new IllegalStateException("Valor inesperado: " + (classificarErroJsch(e)));
        }
    }

    @Override
    public String executarComando(String comando) throws SwitchConnectionException {
        if (channel == null || !channel.isConnected()) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Tentativa de executar comando sem conexão ativa: " + comando, null);
        }
        try {
            enviarLinha(comando);
            return lerAte(Math.max(TIMEOUT_ESPERA_PROMPT_MS, config.timeoutMillis()), TerminalStreamHelper.PROMPT);
        } catch (IOException e) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Erro ao enviar/ler comando '" + comando + "' em " + config.ip(), e);
        }
    }

    private String lerAte(long timeoutMs, Pattern... padroes) throws SwitchConnectionException, IOException {
        return TerminalStreamHelper.lerAte(
                in, out, config.ip(), "SSH", timeoutMs, lineEnding, this::canalEncerrado, null, padroes);
    }

    private boolean canalEncerrado() {
        return channel != null && (channel.isClosed() || channel.isEOF());
    }

    @Override
    public String bannerInicial() {
        return bannerInicial;
    }

    @Override
    public String protocolo() {
        return "SSH";
    }

    @Override
    public CredencialLogin credencialEfetiva() {
        return credencialEfetiva;
    }

    @Override
    public void desconectar() {
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
        if (session != null) {
            session.disconnect();
            session = null;
        }
        in = null;
        out = null;
    }

    /** Responde password e keyboard-interactive (qualquer prompt "Password"/"Senha") com a senha da credencial. */
    private static final class SenhaUserInfo implements UserInfo, UIKeyboardInteractive {
        private final String senha;

        SenhaUserInfo(String senha) {
            this.senha = senha != null ? senha : "";
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return senha;
        }

        @Override
        public boolean promptPassword(String message) {
            return true;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            return true;
        }

        @Override
        public void showMessage(String message) {
        }

        @Override
        public String[] promptKeyboardInteractive(
                String destination, String name, String instruction, String[] prompt, boolean[] echo) {
            if (prompt == null) {
                return new String[0];
            }
            List<String> respostas = new ArrayList<>(prompt.length);
            for (int i = 0; i < prompt.length; i++) {
                respostas.add(senha);
            }
            return respostas.toArray(new String[0]);
        }
    }
}

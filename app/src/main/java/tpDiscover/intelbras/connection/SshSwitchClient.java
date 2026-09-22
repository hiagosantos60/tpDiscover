package tpDiscover.intelbras.connection;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente SSH para switches Comware (Intelbras S23/S33).
 *
 * Estratégia de leitura: como é uma sessão de shell interativa (não exec por
 * comando), lemos o stream continuamente até detectar uma linha que "parece"
 * um prompt Comware (ex: "&lt;S2328G-B&gt;" ou "[S2328G-B]") e não chega mais
 * nenhum byte por um pequeno período — isso evita cortar a saída no meio.
 */
public class SshSwitchClient implements SwitchClient {

    // Prompt Comware: "<Nome>" (visualização de usuário) ou "[Nome]" (visualização de sistema/sub-view)
    private static final Pattern PROMPT = Pattern.compile("(?m)^[<\\[][^>\\]\\r\\n]+[>\\]]\\s*$");
    private static final long TIMEOUT_ESPERA_PROMPT_MS = 8_000;
    private static final long PERIODO_QUIETO_MS = 250;

    private final ConnectionConfig config;
    private Session session;
    private ChannelShell channel;
    private InputStream in;
    private OutputStream out;

    public SshSwitchClient(ConnectionConfig config) {
        this.config = config;
    }

    @Override
    public void connect() throws SwitchConnectionException {
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(config.usuario(), config.ip(), config.portaSsh());
            session.setPassword(config.senha());

            // Switches de rede raramente têm host key cadastrada no known_hosts do cliente;
            // desabilitar a checagem é o padrão pragmático pra ferramentas de suporte de campo.
            session.setConfig("StrictHostKeyChecking", "no");

            // Alguns firmwares Comware mais antigos só suportam algoritmos de troca de
            // chave/cifra legados. Se a conexão falhar com "Algorithm negotiation fail",
            // pode ser necessário liberar algoritmos antigos aqui, por exemplo:
            // session.setConfig("kex", session.getConfig("kex") + ",diffie-hellman-group1-sha1");
            // session.setConfig("server_host_key", session.getConfig("server_host_key") + ",ssh-rsa");

            session.setTimeout((int) config.timeoutMillis());
            session.connect((int) config.timeoutMillis());

            channel = (ChannelShell) session.openChannel("shell");
            in = channel.getInputStream();
            out = channel.getOutputStream();
            channel.connect((int) config.timeoutMillis());

            // Consome o banner inicial (copyright, linhas em branco) até aparecer o primeiro prompt
            lerAteEncontrarPrompt();

        } catch (JSchException e) {
            throw new SwitchConnectionException(classificarErroJsch(e), mensagemAmigavel(e), e);
        } catch (IOException e) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Erro de I/O ao abrir canal SSH com " + config.ip() + ": " + e.getMessage(), e);
        }
    }

    private SwitchConnectionException.Tipo classificarErroJsch(JSchException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("auth fail") || msg.contains("authentication")) {
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
        return switch (classificarErroJsch(e)) {
            case FALHA_AUTENTICACAO -> "Usuário ou senha incorretos para " + config.ip() + " (SSH)";
            case TIMEOUT -> "Timeout ao conectar em " + config.ip() + " — verifique se o IP está acessível";
            case INALCANCAVEL -> "Não foi possível alcançar " + config.ip() + ":" + config.portaSsh()
                    + " — SSH pode estar desabilitado no switch, ou IP/rede incorretos";
            case ERRO_PROTOCOLO -> "Falha SSH ao conectar em " + config.ip() + ": " + e.getMessage();
        };
    }

    @Override
    public String executarComando(String comando) throws SwitchConnectionException {
        if (channel == null || !channel.isConnected()) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Tentativa de executar comando sem conexão ativa: " + comando, null);
        }
        try {
            out.write((comando + "\n").getBytes());
            out.flush();
            return lerAteEncontrarPrompt();
        } catch (IOException e) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Erro ao enviar/ler comando '" + comando + "' em " + config.ip(), e);
        }
    }

    private String lerAteEncontrarPrompt() throws SwitchConnectionException, IOException {
        StringBuilder buffer = new StringBuilder();
        byte[] chunk = new byte[4096];
        long inicio = System.currentTimeMillis();

        while (true) {
            if (in.available() > 0) {
                int lidos = in.read(chunk);
                if (lidos > 0) {
                    buffer.append(new String(chunk, 0, lidos));

                    Matcher m = PROMPT.matcher(buffer.toString());
                    if (m.find()) {
                        // achou algo que parece prompt; espera um pouco pra confirmar
                        // que não é só uma linha parcial e que não vem mais nada atrás
                        dormir(PERIODO_QUIETO_MS);
                        if (in.available() == 0) {
                            return buffer.toString();
                        }
                    }
                }
            } else {
                dormir(50);
            }

            if (System.currentTimeMillis() - inicio > TIMEOUT_ESPERA_PROMPT_MS) {
                throw new SwitchConnectionException(SwitchConnectionException.Tipo.TIMEOUT,
                        "Timeout esperando resposta do switch " + config.ip()
                                + " (prompt não encontrado em " + TIMEOUT_ESPERA_PROMPT_MS + "ms). "
                                + "Saída parcial recebida: " + buffer, null);
            }
        }
    }

    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void desconectar() {
        if (channel != null) channel.disconnect();
        if (session != null) session.disconnect();
    }
}

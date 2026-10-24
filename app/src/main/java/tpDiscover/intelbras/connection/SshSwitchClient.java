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
 * Cliente SSH para switches Intelbras (suporta tanto Comware S23/S33 quanto Série 3000 S30).
 *
 * Estratégia de leitura: como é uma sessão de shell interativa, lemos o stream
 * continuamente até detectar uma linha que coincide com um prompt reconhecido
 * (Comware "<Nome>"/"[Nome]" ou Série 3000 "INTELBRAS>"/"INTELBRAS#") e não chega
 * mais nenhum byte por um pequeno período.
 */
public class SshSwitchClient implements SwitchClient {

    // Prompt Comware: "<Nome>" ou "[Nome]" / S30: "INTELBRAS>" ou "INTELBRAS#"
    private static final Pattern PROMPT = Pattern.compile("(?m)(^[<\\[][^>\\]\\r\\n]+[>\\]]\\s*$|^[a-zA-Z0-9_\\-\\.]+[>#]\\s*$)");
    private static final Pattern SENHA_PROMPT = Pattern.compile("(?i)password\\s*:\\s*$");
    // Paginação S30/Cisco: "---MORE---" ou "--More--"
    private static final Pattern MAIS = Pattern.compile("(?i)--+\\s*more\\s*--+");
    private static final long TIMEOUT_ESPERA_PROMPT_MS = 30_000;
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

            session.setConfig("StrictHostKeyChecking", "no");

            session.setTimeout((int) config.timeoutMillis());
            session.connect((int) config.timeoutMillis());

            channel = (ChannelShell) session.openChannel("shell");
            in = channel.getInputStream();
            out = channel.getOutputStream();
            channel.connect((int) config.timeoutMillis());

            // Consome o banner inicial até aparecer o primeiro prompt
            String banner = lerAteEncontrarPrompt();

            // Se cair em prompt não privilegiado da Série 3000 (ex: "INTELBRAS>"), eleva para "#" com 'en'
            entrarModoPrivilegiadoSeNecessario(banner);

        } catch (JSchException e) {
            throw new SwitchConnectionException(classificarErroJsch(e), mensagemAmigavel(e), e);
        } catch (IOException e) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Erro de I/O ao abrir canal SSH com " + config.ip() + ": " + e.getMessage(), e);
        }
    }

    private void entrarModoPrivilegiadoSeNecessario(String saidaAtual) throws SwitchConnectionException, IOException {
        String linhaPrompt = ultimaLinha(saidaAtual).trim();
        // Se termina com '>' mas não começa com '<' (ex: "INTELBRAS>"), está no modo usuário do S30
        if (linhaPrompt.endsWith(">") && !linhaPrompt.startsWith("<")) {
            out.write("en\n".getBytes());
            out.flush();
            dormir(PERIODO_QUIETO_MS);

            String resp = lerAteEncontrarPromptOuSenha();
            if (SENHA_PROMPT.matcher(ultimaLinha(resp).trim()).find()) {
                out.write((config.senha() + "\n").getBytes());
                out.flush();
                lerAteEncontrarPrompt();
            }
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
                    String texto = buffer.toString();

                    // Se o switch exibiu ---MORE---, envia espaço para continuar
                    if (MAIS.matcher(texto).find()) {
                        // Apaga o "---MORE---" do buffer acumulado para não poluir a saída
                        buffer = new StringBuilder(MAIS.matcher(texto).replaceAll(""));
                        out.write(" ".getBytes());
                        out.flush();
                        inicio = System.currentTimeMillis(); // reinicia timeout após cada página
                        continue;
                    }

                    Matcher m = PROMPT.matcher(texto);
                    if (m.find()) {
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

    private String lerAteEncontrarPromptOuSenha() throws SwitchConnectionException, IOException {
        StringBuilder buffer = new StringBuilder();
        byte[] chunk = new byte[4096];
        long inicio = System.currentTimeMillis();

        while (true) {
            if (in.available() > 0) {
                int lidos = in.read(chunk);
                if (lidos > 0) {
                    buffer.append(new String(chunk, 0, lidos));
                    String texto = buffer.toString();

                    if (MAIS.matcher(texto).find()) {
                        buffer = new StringBuilder(MAIS.matcher(texto).replaceAll(""));
                        out.write(" ".getBytes());
                        out.flush();
                        inicio = System.currentTimeMillis();
                        continue;
                    }

                    Matcher mPrompt = PROMPT.matcher(texto);
                    Matcher mSenha = SENHA_PROMPT.matcher(ultimaLinha(texto).trim());
                    if (mPrompt.find() || mSenha.find()) {
                        dormir(PERIODO_QUIETO_MS);
                        if (in.available() == 0) {
                            return texto;
                        }
                    }
                }
            } else {
                dormir(50);
            }

            if (System.currentTimeMillis() - inicio > TIMEOUT_ESPERA_PROMPT_MS) {
                throw new SwitchConnectionException(SwitchConnectionException.Tipo.TIMEOUT,
                        "Timeout esperando resposta do switch " + config.ip() + ": " + buffer, null);
            }
        }
    }

    private static String ultimaLinha(String texto) {
        if (texto == null) return "";
        String[] linhas = texto.split("\\R");
        for (int i = linhas.length - 1; i >= 0; i--) {
            if (!linhas[i].isBlank()) return linhas[i];
        }
        return "";
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

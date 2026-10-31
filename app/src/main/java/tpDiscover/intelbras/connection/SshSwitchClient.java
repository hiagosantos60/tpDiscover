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
 * Cliente SSH para switches e OLTs Intelbras (Comware S23/S33, Série 3000 S30 e OLTs 4840E/G16/G08).
 *
 * Estratégia de leitura: como é uma sessão de shell interativa, lemos o stream
 * continuamente até detectar uma linha que coincide com um prompt reconhecido
 * (Comware "<Nome>"/"[Nome]", Série 3000 "INTELBRAS>"/"INTELBRAS#" ou OLT "OLT4880E>"/"OLT4880E#")
 * e não chega mais nenhum byte por um pequeno período.
 * Também trata login interativo dentro do shell (comum nas OLTs 4840E/G16/G08) e paginação.
 */
public class SshSwitchClient implements SwitchClient {

    // Prompt Comware: "<Nome>" ou "[Nome]" / S30 e OLT: "INTELBRAS>", "OLT4880E#" etc.
    private static final Pattern PROMPT = Pattern.compile("(?m)(^[<\\[][^>\\]\\r\\n]+[>\\]]\\s*$|^[a-zA-Z0-9_\\-\\.]+[>#]\\s*$)");
    private static final Pattern LOGIN_PROMPT = Pattern.compile("(?i)(login|username|user\\s*name)(?:\\s*\\([^)]*\\))?\\s*:\\s*$");
    private static final Pattern SENHA_PROMPT = Pattern.compile("(?i)password(?:\\s*\\([^)]*\\))?\\s*:\\s*$");
    // Paginação S30/Cisco ("---MORE---") e OLT ("....press ENTER to next line, CTRL_C to quit, other key to next page....")
    private static final Pattern MAIS = Pattern.compile(
            "(?i)(--+\\s*more\\s*--+|[.]{2,}\\s*press\\s+enter\\s+to\\s+next\\s+line[^.\\r\\n]*[.]{2,})"
    );
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

            // Consome o banner inicial (e autentica no shell caso o equipamento peça Username/Password como as OLTs)
            String banner = autenticarNoShellSeNecessario();

            // Se cair em prompt não privilegiado da Série 3000 ou OLT (ex: "INTELBRAS>" / "OLT4880E>"), eleva para "#"
            entrarModoPrivilegiadoSeNecessario(banner);

        } catch (JSchException e) {
            throw new SwitchConnectionException(classificarErroJsch(e), mensagemAmigavel(e), e);
        } catch (IOException e) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Erro de I/O ao abrir canal SSH com " + config.ip() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Algumas OLTs (ex: 4840E / G16 / G08) aceitam a sessão SSH e em seguida solicitam
     * Password(1-16 chars): ou Username(1-32 chars): dentro do próprio canal shell.
     */
    private String autenticarNoShellSeNecessario() throws SwitchConnectionException, IOException {
        String saida = lerAteEncontrarPromptOuLogin();
        String ultima = ultimaLinha(saida).trim();

        if (LOGIN_PROMPT.matcher(ultima).find()) {
            enviarLinha(config.usuario());
            saida = lerAteEncontrarPromptOuLogin();
            ultima = ultimaLinha(saida).trim();
        }

        if (SENHA_PROMPT.matcher(ultima).find()) {
            enviarLinha(config.senha());
            saida = lerAteEncontrarPromptOuLogin();
            ultima = ultimaLinha(saida).trim();
        }

        // Se deu erro de usuário/senha no shell (ex: OLT com senha padrão "admin") e a senha informada era outra, tenta fallback "admin"
        if ((LOGIN_PROMPT.matcher(ultima).find() || SENHA_PROMPT.matcher(ultima).find() || contemErroAuth(saida))
                && !"admin".equals(config.senha())) {
            if (LOGIN_PROMPT.matcher(ultima).find()) {
                enviarLinha(config.usuario());
                saida = lerAteEncontrarPromptOuLogin();
                ultima = ultimaLinha(saida).trim();
            }
            if (SENHA_PROMPT.matcher(ultima).find()) {
                enviarLinha("admin");
                saida = lerAteEncontrarPromptOuLogin();
                ultima = ultimaLinha(saida).trim();
            }
        }

        if (LOGIN_PROMPT.matcher(ultima).find() || SENHA_PROMPT.matcher(ultima).find()) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.FALHA_AUTENTICACAO,
                    "Usuário ou senha incorretos no shell SSH de " + config.ip(), null);
        }

        return saida;
    }

    private boolean contemErroAuth(String texto) {
        if (texto == null) return false;
        String lower = texto.toLowerCase();
        return lower.contains("password error") || lower.contains("login failed") || lower.contains("access denied");
    }

    private void entrarModoPrivilegiadoSeNecessario(String saidaAtual) throws SwitchConnectionException, IOException {
        String linhaPrompt = ultimaLinha(saidaAtual).trim();
        // Se termina com '>' mas não começa com '<' (ex: "INTELBRAS>" ou "OLT4880E>"), eleva com 'enable'
        if (linhaPrompt.endsWith(">") && !linhaPrompt.startsWith("<")) {
            enviarLinha("enable");
            dormir(PERIODO_QUIETO_MS);

            String resp = lerAteEncontrarPromptOuLogin();
            if (SENHA_PROMPT.matcher(ultimaLinha(resp).trim()).find()) {
                enviarLinha(config.senha());
                lerAteEncontrarPrompt();
            }
        }
    }

    private void enviarLinha(String texto) throws IOException {
        out.write((texto + "\n").getBytes());
        out.flush();
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
            case TIMEOUT -> "Timeout ao conectar em " + config.ip() + " - verifique se o IP está acessível";
            case INALCANCAVEL -> "Não foi possível alcançar " + config.ip() + ":" + config.portaSsh()
                    + " - SSH pode estar desabilitado no switch, ou IP/rede incorretos";
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
            enviarLinha(comando);
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

                    // Se o equipamento exibiu paginação (---MORE--- ou ....press ENTER....), envia espaço para avançar
                    if (MAIS.matcher(texto).find()) {
                        buffer = new StringBuilder(MAIS.matcher(texto).replaceAll("\n"));
                        out.write(" ".getBytes());
                        out.flush();
                        inicio = System.currentTimeMillis();
                        continue;
                    }

                    String ultima = ultimaLinha(texto).trim();
                    Matcher m = PROMPT.matcher(ultima);
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
                        "Timeout esperando resposta do equipamento " + config.ip()
                                + " (prompt não encontrado em " + TIMEOUT_ESPERA_PROMPT_MS + "ms). "
                                + "Saída parcial recebida: " + buffer, null);
            }
        }
    }

    private String lerAteEncontrarPromptOuLogin() throws SwitchConnectionException, IOException {
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
                        buffer = new StringBuilder(MAIS.matcher(texto).replaceAll("\n"));
                        out.write(" ".getBytes());
                        out.flush();
                        inicio = System.currentTimeMillis();
                        continue;
                    }

                    String ultima = ultimaLinha(texto).trim();
                    Matcher mPrompt = PROMPT.matcher(ultima);
                    Matcher mLogin = LOGIN_PROMPT.matcher(ultima);
                    Matcher mSenha = SENHA_PROMPT.matcher(ultima);
                    if (mPrompt.find() || mLogin.find() || mSenha.find()) {
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
                        "Timeout esperando resposta do equipamento " + config.ip() + ": " + buffer, null);
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

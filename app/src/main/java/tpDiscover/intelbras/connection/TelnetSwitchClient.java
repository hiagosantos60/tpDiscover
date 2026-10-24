package tpDiscover.intelbras.connection;

import org.apache.commons.net.telnet.TelnetClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

/**
 * Cliente Telnet para switches Intelbras (Comware S23/S33 e Série 3000 S30).
 */
public class TelnetSwitchClient implements SwitchClient {

    private static final Pattern PROMPT = Pattern.compile("(?m)(^[<\\[][^>\\]\\r\\n]+[>\\]]\\s*$|^[a-zA-Z0-9_\\-\\.]+[>#]\\s*$)");
    private static final Pattern LOGIN_PROMPT = Pattern.compile("(?i)(login|username)\\s*:\\s*$");
    private static final Pattern PASSWORD_PROMPT = Pattern.compile("(?i)password\\s*:\\s*$");
    private static final Pattern MAIS = Pattern.compile("(?i)--+\\s*more\\s*--+");
    private static final long TIMEOUT_ESPERA_MS = 30_000;
    private static final long PERIODO_QUIETO_MS = 250;

    private final ConnectionConfig config;
    private TelnetClient telnet;
    private InputStream in;
    private OutputStream out;

    public TelnetSwitchClient(ConnectionConfig config) {
        this.config = config;
    }

    @Override
    public void connect() throws SwitchConnectionException {
        telnet = new TelnetClient();
        telnet.setConnectTimeout((int) config.timeoutMillis());

        try {
            telnet.connect(config.ip(), config.portaTelnet());
            in = telnet.getInputStream();
            out = telnet.getOutputStream();

            String saida = lerAte(LOGIN_PROMPT, PASSWORD_PROMPT, PROMPT);

            if (LOGIN_PROMPT.matcher(ultimaLinha(saida)).find()) {
                enviar(config.usuario());
                saida = lerAte(PASSWORD_PROMPT, PROMPT);
            }

            if (PASSWORD_PROMPT.matcher(ultimaLinha(saida)).find()) {
                enviar(config.senha());
                saida = lerAte(PROMPT, LOGIN_PROMPT);
            }

            boolean voltouParaLogin = LOGIN_PROMPT.matcher(ultimaLinha(saida)).find();
            boolean mensagemDeFalha = contemIgnoreCase(saida, "invalid")
                    || contemIgnoreCase(saida, "fail")
                    || contemIgnoreCase(saida, "denied");

            if (voltouParaLogin || mensagemDeFalha) {
                throw new SwitchConnectionException(SwitchConnectionException.Tipo.FALHA_AUTENTICACAO,
                        "Usuário ou senha incorretos para " + config.ip() + " (Telnet)", null);
            }

            // Se cair em prompt modo usuário do S30 (ex: "INTELBRAS>"), eleva para privilegiado "#"
            entrarModoPrivilegiadoSeNecessario(saida);

        } catch (IOException e) {
            throw new SwitchConnectionException(classificarErroIo(e), mensagemAmigavelIo(e), e);
        }
    }

    private void entrarModoPrivilegiadoSeNecessario(String saidaAtual) throws SwitchConnectionException, IOException {
        String linhaPrompt = ultimaLinha(saidaAtual).trim();
        if (linhaPrompt.endsWith(">") && !linhaPrompt.startsWith("<")) {
            enviar("en");
            String resp = lerAte(PROMPT, PASSWORD_PROMPT);
            if (PASSWORD_PROMPT.matcher(ultimaLinha(resp)).find()) {
                enviar(config.senha());
                lerAte(PROMPT);
            }
        }
    }

    private SwitchConnectionException.Tipo classificarErroIo(IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("timed out") || msg.contains("timeout")) {
            return SwitchConnectionException.Tipo.TIMEOUT;
        }
        if (msg.contains("connection refused") || msg.contains("unreachable") || msg.contains("no route")) {
            return SwitchConnectionException.Tipo.INALCANCAVEL;
        }
        return SwitchConnectionException.Tipo.ERRO_PROTOCOLO;
    }

    private String mensagemAmigavelIo(IOException e) {
        return switch (classificarErroIo(e)) {
            case TIMEOUT -> "Timeout na conexão Telnet com " + config.ip();
            case INALCANCAVEL -> "Não foi possível alcançar " + config.ip() + ":" + config.portaTelnet()
                    + " — Telnet pode estar desabilitado no switch, ou IP/rede incorretos";
            default -> "Erro de rede no Telnet com " + config.ip() + ": " + e.getMessage();
        };
    }

    @Override
    public String executarComando(String comando) throws SwitchConnectionException {
        if (telnet == null || !telnet.isConnected()) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Tentativa de executar comando sem conexão Telnet ativa: " + comando, null);
        }
        try {
            enviar(comando);
            return lerAte(PROMPT);
        } catch (IOException e) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Erro ao enviar comando '" + comando + "' via Telnet para " + config.ip(), e);
        }
    }

    private void enviar(String texto) throws IOException {
        out.write((texto + "\r\n").getBytes());
        out.flush();
    }

    private String lerAte(Pattern... padroesEsperados) throws SwitchConnectionException, IOException {
        StringBuilder buffer = new StringBuilder();
        byte[] chunk = new byte[4096];
        long inicio = System.currentTimeMillis();

        while (true) {
            if (in.available() > 0) {
                int lidos = in.read(chunk);
                if (lidos > 0) {
                    buffer.append(new String(chunk, 0, lidos));
                    String texto = buffer.toString();

                    // Lida com paginação ---MORE---
                    if (MAIS.matcher(texto).find()) {
                        buffer = new StringBuilder(MAIS.matcher(texto).replaceAll(""));
                        out.write(" ".getBytes());
                        out.flush();
                        inicio = System.currentTimeMillis();
                        continue;
                    }

                    String finalDoBuffer = ultimaLinha(texto);
                    for (Pattern p : padroesEsperados) {
                        if (p.matcher(finalDoBuffer).find()) {
                            dormir(PERIODO_QUIETO_MS);
                            if (in.available() == 0) {
                                return buffer.toString();
                            }
                        }
                    }
                }
            } else {
                dormir(50);
            }

            if (System.currentTimeMillis() - inicio > TIMEOUT_ESPERA_MS) {
                throw new SwitchConnectionException(SwitchConnectionException.Tipo.TIMEOUT,
                        "Timeout esperando resposta do switch " + config.ip()
                                + " via Telnet (" + TIMEOUT_ESPERA_MS + "ms). Saída parcial: " + buffer, null);
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

    private static boolean contemIgnoreCase(String texto, String termo) {
        return texto != null && texto.toLowerCase().contains(termo.toLowerCase());
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
        if (telnet != null && telnet.isConnected()) {
            try {
                telnet.disconnect();
            } catch (IOException ignored) {
            }
        }
    }
}

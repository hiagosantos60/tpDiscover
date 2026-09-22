package tpDiscover.intelbras.connection;

import org.apache.commons.net.telnet.TelnetClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

/**
 * Cliente Telnet para switches Comware. Diferente do SSH, o Telnet exige que
 * a gente conduza manualmente o diálogo de login (usuário / senha), já que
 * não há autenticação embutida no protocolo.
 */
public class TelnetSwitchClient implements SwitchClient {

    private static final Pattern PROMPT = Pattern.compile("(?m)^[<\\[][^>\\]\\r\\n]+[>\\]]\\s*$");
    private static final Pattern LOGIN_PROMPT = Pattern.compile("(?i)(login|username)\\s*:\\s*$");
    private static final Pattern PASSWORD_PROMPT = Pattern.compile("(?i)password\\s*:\\s*$");
    private static final long TIMEOUT_ESPERA_MS = 8_000;
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

            // Se depois de enviar a senha ainda caímos num prompt de login,
            // ou a saída contém as mensagens típicas de rejeição, é falha de autenticação.
            boolean voltouParaLogin = LOGIN_PROMPT.matcher(ultimaLinha(saida)).find();
            boolean mensagemDeFalha = contemIgnoreCase(saida, "invalid")
                    || contemIgnoreCase(saida, "fail")
                    || contemIgnoreCase(saida, "denied");

            if (voltouParaLogin || mensagemDeFalha) {
                throw new SwitchConnectionException(SwitchConnectionException.Tipo.FALHA_AUTENTICACAO,
                        "Usuário ou senha incorretos para " + config.ip() + " (Telnet)", null);
            }

        } catch (IOException e) {
            throw new SwitchConnectionException(classificarErroIo(e), mensagemAmigavelIo(e), e);
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
            case TIMEOUT -> "Timeout ao conectar via Telnet em " + config.ip();
            case INALCANCAVEL -> "Não foi possível alcançar " + config.ip() + ":" + config.portaTelnet()
                    + " via Telnet — verifique se o Telnet está habilitado no switch";
            default -> "Erro de I/O na conexão Telnet com " + config.ip() + ": " + e.getMessage();
        };
    }

    @Override
    public String executarComando(String comando) throws SwitchConnectionException {
        try {
            enviar(comando);
            return lerAte(PROMPT);
        } catch (IOException e) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Erro ao enviar/ler comando '" + comando + "' em " + config.ip() + " (Telnet)", e);
        }
    }

    private void enviar(String texto) throws IOException {
        out.write((texto + "\n").getBytes());
        out.flush();
    }

    private String lerAte(Pattern... padroesDeParada) throws SwitchConnectionException, IOException {
        StringBuilder buffer = new StringBuilder();
        byte[] chunk = new byte[4096];
        long inicio = System.currentTimeMillis();

        while (true) {
            if (in.available() > 0) {
                int lidos = in.read(chunk);
                if (lidos > 0) {
                    buffer.append(new String(chunk, 0, lidos));
                    String ultimaLinha = ultimaLinha(buffer.toString());

                    for (Pattern p : padroesDeParada) {
                        if (p.matcher(ultimaLinha).find()) {
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
                                + " (Telnet). Saída parcial: " + buffer, null);
            }
        }
    }

    private String ultimaLinha(String texto) {
        String[] linhas = texto.split("\\R");
        return linhas.length == 0 ? "" : linhas[linhas.length - 1].strip();
    }

    private boolean contemIgnoreCase(String texto, String trecho) {
        return texto.toLowerCase().contains(trecho.toLowerCase());
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
        try {
            if (telnet != null && telnet.isConnected()) {
                telnet.disconnect();
            }
        } catch (IOException ignored) {
            // já estamos encerrando; não há o que fazer com esse erro aqui
        }
    }
}

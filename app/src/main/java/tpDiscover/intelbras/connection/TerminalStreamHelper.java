package tpDiscover.intelbras.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tpDiscover.intelbras.util.Compat;
/**
 * Utilitário compartilhado de leitura de stream CLI para SSH e Telnet.
 * Unifica detecção de prompt, paginação (--More-- / ---MORE--- / OLT), login com cadeia de
 * credenciais, elevação via 'enable' e recuperação de erros da CLI restrita klish (APs).
 */
final class TerminalStreamHelper {

    /**
     * Prompt da CLI. A 3ª alternativa aceita hostnames com espaços (até 4 palavras, sem espaço antes do
     * '>'/'#'), como o "SG 2404 MR>" dos switches SG MR legados.
     */
    static final Pattern PROMPT = Pattern.compile(
            "(?m)(^[<\\[][^>\\]\\r\\n]+[>\\]]\\s*$|^[a-zA-Z0-9_+\\-\\.@:~/()\\\\]*[>#]\\s*$"
                    + "|^[a-zA-Z0-9_+\\-\\.@~/()]+(?: [a-zA-Z0-9_+\\-\\.@~/()]+){1,3}[>#]\\s*$)"
    );
    static final Pattern LOGIN_PROMPT = Pattern.compile(
            "(?i)(login|username|user\\s*name|user)(?:\\s*\\([^)]*\\))?\\s*:\\s*$"
    );
    static final Pattern SENHA_PROMPT = Pattern.compile(
            "(?i)password(?:\\s*\\([^)]*\\))?\\s*:\\s*$"
    );
    private static final Pattern ANSI_CSI = Pattern.compile("\\u001B\\[[0-9;?]*[A-Za-z]|\\u0000");
    private static final Pattern KLISH_SYNTAX_ERR = Pattern.compile(
            "(?i)syntax\\s+error:\\s*illegal\\s+command\\s+line"
    );
    private static final Pattern MAIS = Pattern.compile(
            "(?i)(--+\\s*more\\s*(?:\\([^)]*\\)\\s*)?--+(?:\\s*or\\s*\\(q\\)uit)?|[.]{2,}\\s*press\\s+enter\\s+to\\s+next\\s+line[^.\\r\\n]*[.]{2,}|press\\s+any\\s+key\\s+to\\s+continue(?:\\s*\\([^)]*\\))?|press\\s+enter\\s+to\\s+continue)"
    );
    private static final Pattern MAIS_EXIGE_ENTER = Pattern.compile("(?i)press\\s+enter\\s+to\\s+continue");

    private static final long POLL_INTERVAL_MS = 15;
    private static final long QUIET_WINDOW_MS = 50;
    /**
     * Janela de silêncio maior nas etapas de login: permite enxergar a reação do equipamento a um
     * Enter "extra" (CR+LF lido como dois Enters em telnetd legados) antes de enviar a próxima resposta.
     */
    static final long JANELA_SILENCIO_LOGIN_MS = 350;
    /** Fator do teto absoluto de leitura em relação ao timeout de inatividade. */
    private static final int FATOR_TETO_ABSOLUTO = 4;
    private static final int MAX_TENTATIVAS_ENABLE = 3;
    private static final String[] TERMINADORES = {"\r\n", "\r\0", "\r"};

    /** Como o login deve reagir quando o equipamento aparenta não entender o terminador de linha. */
    enum ModoTerminador {
        /** SSH: terminador fixo; rejeição é tratada como credencial errada. */
        FIXO,
        /** Telnet commons-net (sempre CRLF): sinaliza ERRO_PROTOCOLO "[usuário]" para o cliente cair no socket raw. */
        SINALIZAR,
        /** Socket raw NVT: alterna \r\n → \r\0 → \r na mesma sessão. */
        ALTERNAR
    }

    private TerminalStreamHelper() {
    }

    static String lerAte(
            InputStream in,
            OutputStream out,
            String ip,
            String protocolo,
            long timeoutMs,
            String klishLineEnding,
            Pattern... padroesEsperados
    ) throws SwitchConnectionException, IOException {
        return lerAte(in, out, ip, protocolo, timeoutMs, klishLineEnding, null, null, padroesEsperados);
    }

    static String lerAte(
            InputStream in,
            OutputStream out,
            String ip,
            String protocolo,
            long timeoutMs,
            String klishLineEnding,
            BooleanSupplier streamEncerrado,
            LongConsumer idleCallback,
            Pattern... padroesEsperados
    ) throws SwitchConnectionException, IOException {
        return lerAte(in, out, ip, protocolo, timeoutMs, klishLineEnding, streamEncerrado, idleCallback,
                QUIET_WINDOW_MS, padroesEsperados);
    }

    /**
     * Lê até a última linha casar um dos padrões e o stream ficar em silêncio por {@code janelaSilencioMs}.
     * O timeout é por inatividade (sem bytes novos), com teto absoluto de {@value #FATOR_TETO_ABSOLUTO}x,
     * para não abortar saídas longas (tabelas MAC grandes) que continuam chegando.
     */
    static String lerAte(
            InputStream in,
            OutputStream out,
            String ip,
            String protocolo,
            long timeoutMs,
            String klishLineEnding,
            BooleanSupplier streamEncerrado,
            LongConsumer idleCallback,
            long janelaSilencioMs,
            Pattern... padroesEsperados
    ) throws SwitchConnectionException, IOException {
        StringBuilder buffer = new StringBuilder();
        byte[] chunk = new byte[4096];
        long inicio = System.currentTimeMillis();
        long ultimaLeituraEm = inicio;
        String terminador = klishLineEnding != null ? klishLineEnding : "\r\n";

        while (true) {
            if (in.available() > 0) {
                int lidos = in.read(chunk);
                if (lidos > 0) {
                    ultimaLeituraEm = System.currentTimeMillis();
                    buffer.append(new String(chunk, 0, lidos, StandardCharsets.UTF_8));
                    String texto = limparAnsi(buffer.toString());

                    Matcher mMais = MAIS.matcher(texto);
                    if (mMais.find()) {
                        boolean exigeEnter = MAIS_EXIGE_ENTER.matcher(mMais.group()).find();
                        buffer = new StringBuilder(MAIS.matcher(texto).replaceAll("\n"));
                        out.write((exigeEnter ? terminador : " ").getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        inicio = System.currentTimeMillis();
                        continue;
                    }

                    if (KLISH_SYNTAX_ERR.matcher(texto).find() && ultimaLinha(texto).trim().startsWith("# ")) {
                        buffer = new StringBuilder(KLISH_SYNTAX_ERR.matcher(texto).replaceAll("Syntax error"));
                        out.write(("\u0015" + terminador).getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        inicio = System.currentTimeMillis();
                        continue;
                    }

                    String finalDoBuffer = ultimaLinha(texto).trim();
                    for (Pattern p : padroesEsperados) {
                        Matcher m = p.matcher(finalDoBuffer);
                        if (m.find()) {
                            dormir(Math.max(QUIET_WINDOW_MS, janelaSilencioMs));
                            if (in.available() == 0) {
                                return limparAnsi(buffer.toString());
                            }
                            break;
                        }
                    }
                } else if (lidos < 0) {
                    String ret = tratarStreamEncerrado(ip, protocolo, buffer.toString(), padroesEsperados);
                    if (ret != null) {
                        return ret;
                    }
                }
            } else {
                if (streamEncerrado != null && streamEncerrado.getAsBoolean()) {
                    dormir(25);
                    if (in.available() > 0) {
                        continue;
                    }
                    String ret = tratarStreamEncerrado(ip, protocolo, buffer.toString(), padroesEsperados);
                    if (ret != null) {
                        return ret;
                    }
                }
                if (idleCallback != null) {
                    idleCallback.accept(System.currentTimeMillis() - ultimaLeituraEm);
                }
                dormir(POLL_INTERVAL_MS);
            }

            long agora = System.currentTimeMillis();
            boolean ocioso = agora - Math.max(inicio, ultimaLeituraEm) > timeoutMs;
            boolean estourouTeto = agora - inicio > timeoutMs * FATOR_TETO_ABSOLUTO;
            if (ocioso || estourouTeto) {
                throw new SwitchConnectionException(
                        SwitchConnectionException.Tipo.TIMEOUT,
                        "Timeout esperando resposta do equipamento " + ip
                                + " via " + protocolo + " (" + timeoutMs + "ms). Saída parcial: " + buffer,
                        null
                );
            }
        }
    }

    private static String tratarStreamEncerrado(
            String ip,
            String protocolo,
            String conteudoBruto,
            Pattern... padroesEsperados
    ) throws SwitchConnectionException {
        String texto = limparAnsi(conteudoBruto);
        String finalDoBuffer = ultimaLinha(texto).trim();
        for (Pattern p : padroesEsperados) {
            if (p.matcher(finalDoBuffer).find()) {
                return texto;
            }
        }
        String lower = texto.toLowerCase();
        if (lower.contains("password required, but none set")) {
            throw new SwitchConnectionException(
                    SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Telnet sem senha configurada em " + ip + " (Password required, but none set)",
                    null
            );
        }
        if (contemErroAuth(texto)) {
            throw new SwitchConnectionException(
                    SwitchConnectionException.Tipo.FALHA_AUTENTICACAO,
                    "Usuário ou senha incorretos para " + ip + " (" + protocolo + ")",
                    null
            );
        }
        String trecho = texto.replaceAll("\\s+", " ").trim();
        if (trecho.isEmpty()) {
            throw new SwitchConnectionException(
                    SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Conexão " + protocolo + " encerrada por " + ip + " sem nenhuma resposta — sessão de gerência "
                            + "provavelmente ocupada (switches SG aceitam só 1 sessão Telnet/SSH: feche outros "
                            + "terminais ou aguarde o timeout de inatividade do equipamento)",
                    null
            );
        }
        throw new SwitchConnectionException(
                SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                "Conexão " + protocolo + " encerrada por " + ip + (trecho.isEmpty() ? "" : ": " + trecho),
                null
        );
    }

    static String limparAnsi(String texto) {
        if (texto == null || texto.isEmpty()) return "";
        return ANSI_CSI.matcher(texto).replaceAll("");
    }

    static String ultimaLinha(String texto) {
        if (texto == null) return "";
        String[] linhas = limparAnsi(texto).split("\\R");
        for (int i = linhas.length - 1; i >= 0; i--) {
            if (!Compat.isBlank(linhas[i])) return linhas[i];
        }
        return "";
    }

    static boolean contemErroAuth(String texto) {
        if (texto == null) return false;
        String lower = texto.toLowerCase();
        return lower.contains("password error")
                || lower.contains("login failed")
                || lower.contains("login incorrect")
                || lower.contains("login invalid")
                || lower.contains("bad username")
                || lower.contains("bad password")
                || lower.contains("authentication failed")
                || lower.contains("access denied")
                || lower.contains("invalid")
                || lower.contains("wrong");
    }

    static boolean ehBannerOltAn(String banner) {
        if (banner == null || Compat.isBlank(banner)) return false;
        String lower = banner.toLowerCase();
        return lower.contains("zzzzzzzzzzzz")
                || lower.contains("an6000")
                || lower.contains("an6001")
                || lower.contains("an5516")
                || lower.contains("fiberhome");
    }

    static boolean ehBannerOltGenerico(String banner) {
        if (banner == null || Compat.isBlank(banner)) return false;
        String lower = banner.toLowerCase();
        return ehBannerOltAn(lower)
                || lower.contains("olt")
                || lower.contains("8820")
                || lower.contains("4840e")
                || lower.contains("4880e")
                || lower.contains("broadband network platform");
    }

    /**
     * Resultado do login interativo.
     *
     * @param credencial credencial que autenticou
     * @param lineEnding terminador de linha efetivo (pode ter sido alternado no socket raw)
     * @param cadeia     cadeia de credenciais usada (inclui GEPON adicionado por banner AN), para o 'enable'
     */
    static final class AuthResult {
        private final String bannerCompleto;
        private final String ultimaSaida;
        private final String senhaEfetiva;
        private final CredencialLogin credencial;
        private final String lineEnding;
        private final List<CredencialLogin> cadeia;

        AuthResult(String bannerCompleto, String ultimaSaida, String senhaEfetiva, CredencialLogin credencial, String lineEnding, List<CredencialLogin> cadeia) {
            cadeia = cadeia != null ? Compat.listCopyOf(cadeia) : Compat.listOf();
            this.bannerCompleto = bannerCompleto;
            this.ultimaSaida = ultimaSaida;
            this.senhaEfetiva = senhaEfetiva;
            this.credencial = credencial;
            this.lineEnding = lineEnding;
            this.cadeia = cadeia;
        }

        public String bannerCompleto() {
            return bannerCompleto;
        }

        public String ultimaSaida() {
            return ultimaSaida;
        }

        public String senhaEfetiva() {
            return senhaEfetiva;
        }

        public CredencialLogin credencial() {
            return credencial;
        }

        public String lineEnding() {
            return lineEnding;
        }

        public List<CredencialLogin> cadeia() {
            return cadeia;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof AuthResult)) return false;
            AuthResult o = (AuthResult) obj;
            return java.util.Objects.equals(bannerCompleto, o.bannerCompleto)
                    && java.util.Objects.equals(ultimaSaida, o.ultimaSaida)
                    && java.util.Objects.equals(senhaEfetiva, o.senhaEfetiva)
                    && java.util.Objects.equals(credencial, o.credencial)
                    && java.util.Objects.equals(lineEnding, o.lineEnding)
                    && java.util.Objects.equals(cadeia, o.cadeia);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(bannerCompleto, ultimaSaida, senhaEfetiva, credencial, lineEnding, cadeia);
        }

        @Override
        public String toString() {
            return "AuthResult[bannerCompleto=" + bannerCompleto + ", ultimaSaida=" + ultimaSaida + ", senhaEfetiva=" + senhaEfetiva + ", credencial=" + credencial + ", lineEnding=" + lineEnding + ", cadeia=" + cadeia + "]";
        }
    }

    static AuthResult autenticarComFallback(
            InputStream in,
            OutputStream out,
            ConnectionConfig config,
            String protocolo,
            String lineEnding,
            String saidaInicial
    ) throws SwitchConnectionException, IOException {
        return autenticarComFallback(in, out, config, protocolo, lineEnding, saidaInicial, null);
    }

    static AuthResult autenticarComFallback(
            InputStream in,
            OutputStream out,
            ConnectionConfig config,
            String protocolo,
            String lineEnding,
            String saidaInicial,
            BooleanSupplier streamEncerrado
    ) throws SwitchConnectionException, IOException {
        return autenticar(in, out, config, config.cadeiaCredenciais(), protocolo, lineEnding,
                ModoTerminador.FIXO, saidaInicial, streamEncerrado);
    }

    /**
     * Login interativo percorrendo a cadeia de credenciais na mesma sessão enquanto o equipamento permitir.
     * Sucesso é decidido pelo prompt da CLI, nunca por palavras soltas na saída.
     *
     * @throws LoginInterrompidoException se o equipamento derrubar a sessão após uma tentativa de senha
     *                                    (o cliente pode reconectar e continuar com as credenciais restantes)
     */
    static AuthResult autenticar(
            InputStream in,
            OutputStream out,
            ConnectionConfig config,
            List<CredencialLogin> cadeia,
            String protocolo,
            String lineEnding,
            ModoTerminador modo,
            String saidaInicial,
            BooleanSupplier streamEncerrado
    ) throws SwitchConnectionException, IOException {
        long waitLogin = Math.max(8_000, config.timeoutMillis());
        String inicial = saidaInicial != null ? saidaInicial : "";
        StringBuilder banner = new StringBuilder(inicial);
        String saida = inicial;
        String eol = lineEnding;

        List<CredencialLogin> fila = new ArrayList<>(
                cadeia != null && !cadeia.isEmpty() ? cadeia : config.cadeiaCredenciais());
        adicionarGeponSeOltAn(fila, banner);

        String ultima = ultimaLinha(saida).trim();
        if (!LOGIN_PROMPT.matcher(ultima).find() && !SENHA_PROMPT.matcher(ultima).find()) {
            // Já autenticado pela camada de transporte (SSH) ou equipamento sem login
            CredencialLogin primeira = fila.get(0);
            return new AuthResult(banner.toString(), saida, primeira.senha(), primeira, eol, fila);
        }

        int idx = 0;
        int trocasTerminador = 0;
        while (idx < fila.size()) {
            CredencialLogin cred = fila.get(idx);
            ultima = ultimaLinha(saida).trim();
            boolean enviouSenha = false;
            try {
                if (LOGIN_PROMPT.matcher(ultima).find()) {
                    digitarLinha(out, eol, cred.usuario());
                    saida = lerAteEtapa("usuário", in, out, config.ip(), protocolo, waitLogin, eol,
                            streamEncerrado, null, JANELA_SILENCIO_LOGIN_MS, SENHA_PROMPT, PROMPT, LOGIN_PROMPT);
                    banner.append('\n').append(saida);
                    ultima = ultimaLinha(saida).trim();

                    if (LOGIN_PROMPT.matcher(ultima).find()) {
                        // Voltou ao "Username:" sem aceitar a senha: típico de CR+LF lido como 2 Enters
                        if (modo == ModoTerminador.SINALIZAR) {
                            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                                    "[usuário] " + config.ip() + " voltou ao prompt de login logo após o usuário "
                                            + "(possível incompatibilidade de terminador de linha CR/LF)", null);
                        }
                        if (modo == ModoTerminador.ALTERNAR && trocasTerminador < TERMINADORES.length - 1) {
                            eol = proximoTerminador(eol);
                            trocasTerminador++;
                            continue;
                        }
                        idx++;
                        adicionarGeponSeOltAn(fila, banner);
                        continue;
                    }
                }

                if (SENHA_PROMPT.matcher(ultima).find()) {
                    digitarLinha(out, eol, cred.senha());
                    enviouSenha = true;
                    saida = lerAteEtapa("senha", in, out, config.ip(), protocolo, waitLogin, eol,
                            streamEncerrado, null, JANELA_SILENCIO_LOGIN_MS, PROMPT, LOGIN_PROMPT, SENHA_PROMPT);
                    banner.append('\n').append(saida);
                    ultima = ultimaLinha(saida).trim();
                }
            } catch (LoginInterrompidoException e) {
                throw e;
            } catch (SwitchConnectionException e) {
                if (e.getTipo() == SwitchConnectionException.Tipo.TIMEOUT) {
                    throw e;
                }
                if (!enviouSenha && e.getTipo() == SwitchConnectionException.Tipo.ERRO_PROTOCOLO) {
                    // Caiu antes de pedir a senha: problema de protocolo/terminador, não de credencial
                    throw e;
                }
                throw new LoginInterrompidoException(
                        "Sessão " + protocolo + " encerrada por " + config.ip()
                                + " após tentativa de login com o usuário '" + cred.usuario() + "'",
                        idx + 1, e);
            }

            boolean voltouAoLogin = LOGIN_PROMPT.matcher(ultima).find() || SENHA_PROMPT.matcher(ultima).find();
            if (!voltouAoLogin && PROMPT.matcher(ultima).find()) {
                return new AuthResult(banner.toString(), saida, cred.senha(), cred, eol, fila);
            }
            idx++;
            adicionarGeponSeOltAn(fila, banner);
        }

        throw new SwitchConnectionException(SwitchConnectionException.Tipo.FALHA_AUTENTICACAO,
                "Usuário ou senha incorretos para " + config.ip() + " (" + protocolo + ") — "
                        + fila.size() + " credencial(is) testada(s)", null);
    }

    private static void adicionarGeponSeOltAn(List<CredencialLogin> fila, CharSequence banner) {
        if (!ehBannerOltAn(banner.toString())) {
            return;
        }
        CredencialLogin gepon = new CredencialLogin("GEPON", "GEPON", "OLT FiberHome/AN padrão");
        for (CredencialLogin c : fila) {
            if (c.mesmoParQue(gepon)) {
                return;
            }
        }
        fila.add(gepon);
    }

    private static String proximoTerminador(String atual) {
        for (int i = 0; i < TERMINADORES.length; i++) {
            if (TERMINADORES[i].equals(atual)) {
                return TERMINADORES[(i + 1) % TERMINADORES.length];
            }
        }
        return TERMINADORES[1];
    }

    static void entrarModoPrivilegiadoSeNecessario(
            InputStream in,
            OutputStream out,
            ConnectionConfig config,
            String protocolo,
            String lineEnding,
            String saidaAtual,
            String senhaEfetiva
    ) throws SwitchConnectionException, IOException {
        entrarModoPrivilegiadoSeNecessario(in, out, config, protocolo, lineEnding, saidaAtual, senhaEfetiva, null);
    }

    static void entrarModoPrivilegiadoSeNecessario(
            InputStream in,
            OutputStream out,
            ConnectionConfig config,
            String protocolo,
            String lineEnding,
            String saidaAtual,
            String senhaEfetiva,
            BooleanSupplier streamEncerrado
    ) throws SwitchConnectionException, IOException {
        List<String> senhas = new ArrayList<>();
        senhas.add(senhaEfetiva);
        for (CredencialLogin c : config.cadeiaCredenciais()) {
            senhas.add(c.senha());
        }
        entrarModoPrivilegiado(in, out, config, protocolo, lineEnding, saidaAtual, senhas, streamEncerrado);
    }

    /**
     * Executa 'enable' quando o prompt termina em '>' (exceto Comware "<...>" e OLT 8820),
     * tentando até {@value #MAX_TENTATIVAS_ENABLE} senhas distintas e, se ainda assim o equipamento
     * continuar pedindo senha, aborta com Ctrl+C para não deixar a sessão presa no prompt "Password:".
     */
    static void entrarModoPrivilegiado(
            InputStream in,
            OutputStream out,
            ConnectionConfig config,
            String protocolo,
            String lineEnding,
            String saidaAtual,
            List<String> senhasCandidatas,
            BooleanSupplier streamEncerrado
    ) throws SwitchConnectionException, IOException {
        String linhaPrompt = ultimaLinha(saidaAtual).trim();
        if (linhaPrompt.length() <= 1 || !linhaPrompt.endsWith(">") || linhaPrompt.startsWith("<")
                || linhaPrompt.toLowerCase().startsWith("intelbras-olt")) {
            return;
        }
        Set<String> unicas = new LinkedHashSet<>();
        if (senhasCandidatas != null) {
            for (String s : senhasCandidatas) {
                if (s != null) unicas.add(s);
            }
        }
        unicas.add("");
        List<String> senhas = new ArrayList<>(unicas);

        long wait = Math.max(6_000, config.timeoutMillis());
        // Espera menor pela resposta à senha: se travar, recupera e segue em modo usuário
        long waitSenha = Math.max(6_000, Math.min(15_000, config.timeoutMillis()));
        enviarLinha(out, lineEnding, "enable");
        String resp = lerAteEtapa("enable", in, out, config.ip(), protocolo, wait, lineEnding,
                streamEncerrado, null, PROMPT, SENHA_PROMPT);
        int tentativas = 0;
        boolean travou = false;
        while (SENHA_PROMPT.matcher(ultimaLinha(resp).trim()).find()
                && tentativas < Math.min(MAX_TENTATIVAS_ENABLE, senhas.size())) {
            digitarLinha(out, lineEnding, senhas.get(tentativas++));
            try {
                resp = lerAteEtapa("enable", in, out, config.ip(), protocolo, waitSenha, lineEnding,
                        streamEncerrado, null, PROMPT, SENHA_PROMPT);
            } catch (SwitchConnectionException e) {
                if (e.getTipo() != SwitchConnectionException.Tipo.TIMEOUT) {
                    throw e;
                }
                travou = true;
                break;
            }
        }
        if (travou || SENHA_PROMPT.matcher(ultimaLinha(resp).trim()).find()) {
            // Sem senha de enable válida (ou leitor de senha travado): aborta com Ctrl+C para não
            // deixar o VTY preso no prompt "Password:" e segue coletando em modo usuário.
            out.write(new byte[]{0x03});
            out.flush();
            enviarLinha(out, lineEnding, "");
            try {
                lerAteEtapa("enable", in, out, config.ip(), protocolo, waitSenha, lineEnding,
                        streamEncerrado, null, PROMPT);
            } catch (SwitchConnectionException e) {
                if (e.getTipo() != SwitchConnectionException.Tipo.TIMEOUT) {
                    throw e;
                }
                if (travou) {
                    throw new SwitchConnectionException(SwitchConnectionException.Tipo.TIMEOUT,
                            "[enable] " + config.ip() + " não respondeu à senha de enable nem ao Ctrl+C", e);
                }
            }
        }
    }

    static String lerAteEtapa(
            String etapa,
            InputStream in,
            OutputStream out,
            String ip,
            String protocolo,
            long timeoutMs,
            String klishLineEnding,
            BooleanSupplier streamEncerrado,
            LongConsumer idleCallback,
            Pattern... padroesEsperados
    ) throws SwitchConnectionException, IOException {
        return lerAteEtapa(etapa, in, out, ip, protocolo, timeoutMs, klishLineEnding, streamEncerrado,
                idleCallback, QUIET_WINDOW_MS, padroesEsperados);
    }

    static String lerAteEtapa(
            String etapa,
            InputStream in,
            OutputStream out,
            String ip,
            String protocolo,
            long timeoutMs,
            String klishLineEnding,
            BooleanSupplier streamEncerrado,
            LongConsumer idleCallback,
            long janelaSilencioMs,
            Pattern... padroesEsperados
    ) throws SwitchConnectionException, IOException {
        try {
            return lerAte(in, out, ip, protocolo, timeoutMs, klishLineEnding,
                    streamEncerrado, idleCallback, janelaSilencioMs, padroesEsperados);
        } catch (SwitchConnectionException e) {
            throw comEtapaFalha(etapa, e);
        }
    }

    static SwitchConnectionException comEtapaFalha(String etapa, SwitchConnectionException e) {
        if (e == null || etapa == null || Compat.isBlank(etapa)) {
            return e;
        }
        if (e.getTipo() != SwitchConnectionException.Tipo.TIMEOUT
                && e.getTipo() != SwitchConnectionException.Tipo.ERRO_PROTOCOLO) {
            return e;
        }
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.startsWith("[")) {
            return e;
        }
        return new SwitchConnectionException(e.getTipo(), "[" + etapa + "] " + msg, e);
    }

    private static void enviarLinha(OutputStream out, String lineEnding, String texto) throws IOException {
        out.write((texto + lineEnding).getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Intervalo entre caracteres ao "digitar" credenciais. */
    static final long INTERVALO_DIGITACAO_MS = 100;

    /**
     * Envia usuário/senha caractere por caractere (um segmento TCP por byte), como um terminal humano.
     * Necessário nos switches SG MR L2+ (ex.: SG 5204 MR L2+): o leitor de senha do 'enable' (e às vezes
     * o do login) processa só um caractere por pacote recebido — com a senha inteira num único pacote ele
     * ecoa um '*' e trava, gerando "[enable] Timeout" mesmo com a senha correta.
     */
    private static void digitarLinha(OutputStream out, String lineEnding, String texto) throws IOException {
        byte[] bytes = (texto + lineEnding).getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            out.write(b);
            out.flush();
            dormir(INTERVALO_DIGITACAO_MS);
        }
    }

    static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

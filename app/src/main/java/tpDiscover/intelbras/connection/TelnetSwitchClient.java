package tpDiscover.intelbras.connection;

import org.apache.commons.net.telnet.EchoOptionHandler;
import org.apache.commons.net.telnet.InvalidTelnetOptionException;
import org.apache.commons.net.telnet.SuppressGAOptionHandler;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TerminalTypeOptionHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Cliente Telnet para switches e OLTs Intelbras (Comware, Série 3000, S2050G-A, S30-Lite, SG MR/L2+ e OLTs).
 * Inclui fallback automático em socket TCP/NVT puro caso a negociação Telnet padrão trave ou caia em equipamentos legados.
 */
public class TelnetSwitchClient implements SwitchClient {

    private static final long TIMEOUT_ESPERA_MS = 45_000;
    /** Máximo de conexões TCP abertas para completar o login (commons-net + raw + reconexões após queda). */
    private static final int MAX_CONEXOES_LOGIN = 4;
    private static final int[] SILENCE_NUDGES_MS = {
            250, 500, 800, 1_100, 1_500, 2_000, 2_500, 3_500, 5_000, 7_500
    };

    private final ConnectionConfig config;
    private final Object writeLock = new Object();
    private TelnetClient telnet;
    private Socket rawSocket;
    private TelnetStreamPump inPump;
    private OutputStream out;
    private String bannerInicial = "";
    private volatile String lineEndingAtivo = "\r\n";
    private volatile boolean autenticado = false;
    private volatile CredencialLogin credencialEfetiva;

    public TelnetSwitchClient(ConnectionConfig config) {
        this.config = config;
    }

    @Override
    public void connect() throws SwitchConnectionException {
        List<CredencialLogin> pendentes = new ArrayList<>(config.cadeiaCredenciais());
        boolean usarRaw = false;
        boolean preferirCrNul = false;
        SwitchConnectionException falhaCommonsNet = null;

        for (int conexao = 0; conexao < MAX_CONEXOES_LOGIN; conexao++) {
            try {
                if (usarRaw) {
                    conectarViaSocketRawNvt(preferirCrNul, pendentes);
                } else {
                    conectarViaCommonsNet(pendentes);
                }
                return;
            } catch (LoginInterrompidoException e) {
                // Equipamento derrubou a sessão após senha(s) errada(s): reconecta e segue com as restantes
                desconectar();
                int consumidas = Math.min(e.credenciaisConsumidas(), pendentes.size());
                pendentes = new ArrayList<>(pendentes.subList(consumidas, pendentes.size()));
                if (pendentes.isEmpty()) {
                    throw new SwitchConnectionException(SwitchConnectionException.Tipo.FALHA_AUTENTICACAO,
                            "Usuário ou senha incorretos para " + config.ip() + " (Telnet) — "
                                    + config.cadeiaCredenciais().size() + " credencial(is) testada(s); "
                                    + e.getMessage(), e);
                }
                TerminalStreamHelper.dormir(1_200);
            } catch (SwitchConnectionException e) {
                desconectar();
                if (e.getTipo() == SwitchConnectionException.Tipo.INALCANCAVEL
                        || e.getTipo() == SwitchConnectionException.Tipo.FALHA_AUTENTICACAO) {
                    throw e;
                }
                if (usarRaw) {
                    String sufixo = falhaCommonsNet != null ? " | 1ª tentativa: " + falhaCommonsNet.getMessage() : "";
                    throw new SwitchConnectionException(e.getTipo(), e.getMessage() + sufixo, e);
                }
                // Fallback em socket NVT puro com burst de Enters (\r\n / \r\0) para switches SG antigos
                falhaCommonsNet = e;
                usarRaw = true;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                preferirCrNul = msg.startsWith("[usuário]") || msg.startsWith("[senha]") || msg.startsWith("[enable]");
                TerminalStreamHelper.dormir(1_800);
            }
        }
        throw new SwitchConnectionException(SwitchConnectionException.Tipo.FALHA_AUTENTICACAO,
                "Usuário ou senha incorretos para " + config.ip() + " (Telnet) — limite de "
                        + MAX_CONEXOES_LOGIN + " conexões de login atingido", null);
    }

    @Override
    public CredencialLogin credencialEfetiva() {
        return credencialEfetiva;
    }

    private void conectarViaCommonsNet(List<CredencialLogin> cadeia) throws SwitchConnectionException {
        autenticado = false;
        lineEndingAtivo = "\r\n";
        telnet = new TelnetClient("VT100");
        int tcpConnectTimeout = calcularTcpConnectTimeout();
        telnet.setConnectTimeout(tcpConnectTimeout);
        int soTimeoutMs = calcularSoTimeout();
        telnet.setDefaultTimeout(soTimeoutMs);

        try {
            registrarHandlersTelnet(telnet);
            telnet.connect(config.ip(), config.portaTelnet());
            telnet.setSoTimeout(soTimeoutMs);
            telnet.setTcpNoDelay(true);

            inPump = new TelnetStreamPump(telnet.getInputStream());
            inPump.iniciar();
            out = telnet.getOutputStream();

            long waitBanner = Math.max(15_000, Math.min(25_000, config.timeoutMillis()));
            executarHandshakePosConexao(waitBanner, false, false, null, cadeia);
        } catch (IOException e) {
            desconectar();
            throw new SwitchConnectionException(classificarErroIo(e), mensagemAmigavelIo(e), e);
        } catch (SwitchConnectionException e) {
            desconectar();
            throw e;
        }
    }

    private void conectarViaSocketRawNvt(boolean preferirCrNulInicial, List<CredencialLogin> cadeia)
            throws SwitchConnectionException {
        autenticado = false;
        lineEndingAtivo = preferirCrNulInicial ? "\r\0" : "\r\n";
        rawSocket = new Socket();
        int tcpConnectTimeout = calcularTcpConnectTimeout();
        int soTimeoutMs = calcularSoTimeout();
        RawNvtInputStream nvtIn = null;

        try {
            rawSocket.connect(new InetSocketAddress(config.ip(), config.portaTelnet()), tcpConnectTimeout);
            rawSocket.setTcpNoDelay(true);
            rawSocket.setSoTimeout(soTimeoutMs);

            out = rawSocket.getOutputStream();
            nvtIn = new RawNvtInputStream(rawSocket.getInputStream(), out, writeLock);
            inPump = new TelnetStreamPump(nvtIn);
            inPump.iniciar();

            long waitBanner = Math.max(25_000, config.timeoutMillis());
            executarHandshakePosConexao(waitBanner, true, preferirCrNulInicial, nvtIn, cadeia);
        } catch (IOException e) {
            desconectar();
            throw new SwitchConnectionException(classificarErroIo(e), "[raw] " + mensagemAmigavelIo(e), e);
        } catch (SwitchConnectionException e) {
            desconectar();
            if (e instanceof LoginInterrompidoException) {
                throw e;
            }
            if (nvtIn != null && e.getTipo() != SwitchConnectionException.Tipo.FALHA_AUTENTICACAO) {
                String iac = nvtIn.resumoIac();
                String sufixo = iac.isEmpty() ? " [raw iac=nenhum]" : " [raw iac=" + iac + "]";
                throw new SwitchConnectionException(e.getTipo(), e.getMessage() + sufixo, e);
            }
            throw e;
        }
    }

    private void executarHandshakePosConexao(
            long waitBanner,
            boolean modoRaw,
            boolean preferirCrNulInicial,
            RawNvtInputStream nvtIn,
            List<CredencialLogin> cadeia
    ) throws SwitchConnectionException, IOException {
        String saida = lerBannerComNudge(waitBanner, modoRaw, preferirCrNulInicial);
        if (modoRaw && nvtIn != null && "\r\n".equals(lineEndingAtivo) && nvtIn.serverWillEcho() && preferirCrNulInicial) {
            lineEndingAtivo = "\r\0";
        }
        TerminalStreamHelper.ModoTerminador modo = modoRaw
                ? TerminalStreamHelper.ModoTerminador.ALTERNAR
                : TerminalStreamHelper.ModoTerminador.SINALIZAR;
        TerminalStreamHelper.AuthResult auth = TerminalStreamHelper.autenticar(
                inPump, out, config, cadeia, "Telnet", lineEndingAtivo, modo, saida, inPump::isEncerrado);
        this.lineEndingAtivo = auth.lineEnding();
        this.bannerInicial = auth.bannerCompleto();
        this.credencialEfetiva = auth.credencial();

        TerminalStreamHelper.entrarModoPrivilegiado(
                inPump, out, config, "Telnet", lineEndingAtivo, auth.ultimaSaida(),
                senhasParaEnable(auth), inPump::isEncerrado);
        this.autenticado = true;
    }

    static List<String> senhasParaEnable(TerminalStreamHelper.AuthResult auth) {
        List<String> senhas = new ArrayList<>();
        senhas.add(auth.senhaEfetiva());
        for (CredencialLogin c : auth.cadeia()) {
            senhas.add(c.senha());
        }
        return senhas;
    }

    private int calcularTcpConnectTimeout() {
        int probeMs = config.probeTimeoutMillis() > 0 ? config.probeTimeoutMillis() : 1_200;
        return Math.max(3_000, Math.min(8_000, probeMs * 3));
    }

    private int calcularSoTimeout() {
        return (int) Math.max(60_000L, config.timeoutMillis());
    }

    private static void registrarHandlersTelnet(TelnetClient client) {
        try {
            client.addOptionHandler(new TerminalTypeOptionHandler("VT100", false, false, true, false));
            client.addOptionHandler(new EchoOptionHandler(false, false, false, true));
            client.addOptionHandler(new SuppressGAOptionHandler(false, false, true, true));
        } catch (InvalidTelnetOptionException | IOException ignored) {
        }
    }

    private String lerBannerComNudge(
            long waitBanner,
            boolean modoRaw,
            boolean preferirCrNulInicial
    ) throws SwitchConnectionException, IOException {
        AtomicInteger nudgesEnviados = new AtomicInteger(0);
        AtomicReference<String> ultimoTerminador = new AtomicReference<>(lineEndingAtivo);
        String saida = TerminalStreamHelper.lerAteEtapa(
                "banner",
                inPump,
                out,
                config.ip(),
                "Telnet",
                waitBanner,
                lineEndingAtivo,
                inPump::isEncerrado,
                silencioMs -> {
                    int passo = nudgesEnviados.get();
                    if (passo < SILENCE_NUDGES_MS.length && silencioMs >= SILENCE_NUDGES_MS[passo]) {
                        if (nudgesEnviados.compareAndSet(passo, passo + 1)) {
                            String terminador = escolherTerminadorNudge(passo, modoRaw, preferirCrNulInicial);
                            ultimoTerminador.set(terminador);
                            try {
                                enviarBruto(terminador);
                            } catch (IOException ignored) {
                            }
                        }
                    }
                },
                TerminalStreamHelper.LOGIN_PROMPT,
                TerminalStreamHelper.SENHA_PROMPT,
                TerminalStreamHelper.PROMPT
        );
        if (modoRaw && nudgesEnviados.get() > 0) {
            String usado = ultimoTerminador.get();
            if ("\r\0".equals(usado)) {
                lineEndingAtivo = "\r\0";
            }
        }
        return saida;
    }

    private static String escolherTerminadorNudge(int passo, boolean modoRaw, boolean preferirCrNulInicial) {
        if (!modoRaw) {
            return "\r\n";
        }
        if (preferirCrNulInicial) {
            return (passo % 2 == 0) ? "\r\0" : "\r\n";
        }
        return (passo == 3 || passo == 4 || passo == 7 || passo == 9) ? "\r\0" : "\r\n";
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
        switch (classificarErroIo(e)) {
            case TIMEOUT:
                return "Timeout na conexão Telnet com " + config.ip();
            case INALCANCAVEL:
                return "Não foi possível alcançar " + config.ip() + ":" + config.portaTelnet()
                    + " - Telnet pode estar desabilitado no equipamento, ou IP/rede incorretos";
            default:
                return "Erro de rede no Telnet com " + config.ip() + ": " + e.getMessage();
        }
    }

    @Override
    public String executarComando(String comando) throws SwitchConnectionException {
        if (!conectado() || inPump == null) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Tentativa de executar comando sem conexão Telnet ativa: " + comando, null);
        }
        try {
            enviar(comando);
            long timeoutCmd = Math.max(TIMEOUT_ESPERA_MS, config.timeoutMillis());
            return lerAte(comando, timeoutCmd, TerminalStreamHelper.PROMPT);
        } catch (IOException e) {
            throw new SwitchConnectionException(SwitchConnectionException.Tipo.ERRO_PROTOCOLO,
                    "Erro ao enviar comando '" + comando + "' via Telnet para " + config.ip(), e);
        }
    }

    private boolean conectado() {
        return (telnet != null && telnet.isConnected())
                || (rawSocket != null && rawSocket.isConnected() && !rawSocket.isClosed());
    }

    private void enviar(String texto) throws IOException {
        enviarBruto(texto + lineEndingAtivo);
    }

    private void enviarBruto(String sequencia) throws IOException {
        OutputStream target = out;
        if (target == null) {
            throw new IOException("Stream de saída Telnet fechado");
        }
        synchronized (writeLock) {
            target.write(sequencia.getBytes(StandardCharsets.UTF_8));
            target.flush();
        }
    }

    private String lerAte(String etapa, long timeoutMs, Pattern... padroesEsperados)
            throws SwitchConnectionException, IOException {
        return TerminalStreamHelper.lerAteEtapa(
                etapa, inPump, out, config.ip(), "Telnet", timeoutMs, lineEndingAtivo,
                inPump::isEncerrado, null, padroesEsperados);
    }

    @Override
    public String bannerInicial() {
        return bannerInicial;
    }

    @Override
    public String protocolo() {
        return "Telnet";
    }

    @Override
    public void desconectar() {
        boolean estavaAutenticado = autenticado;
        autenticado = false;
        if (out != null && conectado()) {
            try {
                if (estavaAutenticado) {
                    enviar("exit");
                } else {
                    // Sessão abandonada no meio do login/enable: Ctrl+C para o equipamento liberar o VTY
                    // (switches SG com sessão única ficam inacessíveis se o prompt "Password:" ficar pendurado)
                    enviarBruto("\u0003" + lineEndingAtivo);
                }
            } catch (IOException ignored) {
            }
        }
        if (inPump != null) {
            inPump.parar();
            inPump = null;
        }
        if (telnet != null) {
            if (telnet.isConnected()) {
                try {
                    telnet.disconnect();
                } catch (IOException ignored) {
                }
            }
            telnet = null;
        }
        if (rawSocket != null) {
            try {
                rawSocket.close();
            } catch (IOException ignored) {
            }
            rawSocket = null;
        }
        out = null;
    }

    /**
     * Mantém leitura ativa sobre TelnetInputStream via Virtual Thread para detectar
     * EOF ("Connection closed by foreign host") e resets imediatamente sem bloquear available().
     */
    private static final class TelnetStreamPump extends InputStream {
        private final InputStream delegate;
        private final Object lock = new Object();
        private byte[] buf = new byte[4096];
        private int head = 0;
        private int tail = 0;
        private volatile boolean eof = false;
        private volatile boolean stopped = false;

        TelnetStreamPump(InputStream delegate) {
            this.delegate = delegate;
        }

        void iniciar() {
            Thread pump = new Thread(() -> {
                byte[] temp = new byte[2048];
                try {
                    while (!stopped) {
                        int n;
                        try {
                            n = delegate.read(temp);
                        } catch (SocketTimeoutException timeout) {
                            continue;
                        }
                        if (n < 0) {
                            break;
                        }
                        if (n > 0) {
                            synchronized (lock) {
                                garantirCapacidade(n);
                                System.arraycopy(temp, 0, buf, tail, n);
                                tail += n;
                                lock.notifyAll();
                            }
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    synchronized (lock) {
                        eof = true;
                        lock.notifyAll();
                    }
                }
            }, "telnet-pump");
            pump.setDaemon(true);
            pump.start();
        }

        void parar() {
            stopped = true;
            synchronized (lock) {
                eof = true;
                lock.notifyAll();
            }
        }

        boolean isEncerrado() {
            synchronized (lock) {
                return eof && (tail - head) == 0;
            }
        }

        @Override
        public int available() {
            synchronized (lock) {
                return tail - head;
            }
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int n = read(single, 0, 1);
            return n < 0 ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            synchronized (lock) {
                while (head == tail) {
                    if (eof) {
                        return -1;
                    }
                    try {
                        lock.wait(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                int count = Math.min(len, tail - head);
                System.arraycopy(buf, head, b, off, count);
                head += count;
                if (head == tail) {
                    head = 0;
                    tail = 0;
                }
                return count;
            }
        }

        private void garantirCapacidade(int extras) {
            if (tail + extras <= buf.length) {
                return;
            }
            int disponivel = tail - head;
            if (disponivel + extras <= buf.length) {
                System.arraycopy(buf, head, buf, 0, disponivel);
                head = 0;
                tail = disponivel;
                return;
            }
            int novoTam = Math.max(buf.length * 2, disponivel + extras);
            byte[] novo = new byte[novoTam];
            System.arraycopy(buf, head, novo, 0, disponivel);
            buf = novo;
            head = 0;
            tail = disponivel;
        }
    }

    /**
     * Filtro RFC 854 sobre socket TCP puro para switches legados incompatíveis com TelnetClient:
     * responde negociações IAC sem entrar em loop, limpa sequências de controle/NUL e registra opções vistas.
     */
    private static final class RawNvtInputStream extends InputStream {
        private static final int IAC = 255;
        private static final int SE = 240;
        private static final int SB = 250;
        private static final int WILL = 251;
        private static final int WONT = 252;
        private static final int DO = 253;
        private static final int DONT = 254;
        private static final int OPT_ECHO = 1;
        private static final int OPT_SGA = 3;
        private static final int OPT_TTYPE = 24;

        private enum State { DATA, IAC, WILL, WONT, DO, DONT, SB, SB_IAC }

        private final InputStream delegate;
        private final OutputStream out;
        private final Object writeLock;
        private final byte[] rawTemp = new byte[1024];
        private final ByteArrayOutputStream subOpt = new ByteArrayOutputStream(64);
        private final boolean[] doSent = new boolean[256];
        private final boolean[] dontSent = new boolean[256];
        private final boolean[] willSent = new boolean[256];
        private final boolean[] wontSent = new boolean[256];
        private final Set<String> iacObservados = new LinkedHashSet<>();
        private State state = State.DATA;
        private volatile boolean serverWillEcho = false;

        RawNvtInputStream(InputStream delegate, OutputStream out, Object writeLock) {
            this.delegate = delegate;
            this.out = out;
            this.writeLock = writeLock;
        }

        boolean serverWillEcho() {
            return serverWillEcho;
        }

        String resumoIac() {
            synchronized (iacObservados) {
                return String.join(",", iacObservados);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int n = read(single, 0, 1);
            return n < 0 ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return 0;
            }
            while (true) {
                int maxRead = Math.min(len, rawTemp.length);
                int n = delegate.read(rawTemp, 0, maxRead);
                if (n < 0) {
                    return -1;
                }
                int written = 0;
                for (int i = 0; i < n; i++) {
                    int val = rawTemp[i] & 0xFF;
                    int textoByte = processarByte(val);
                    if (textoByte >= 0) {
                        b[off + written++] = (byte) textoByte;
                    }
                }
                if (written > 0) {
                    return written;
                }
            }
        }

        private int processarByte(int val) throws IOException {
            switch (state) {
                case DATA: {
                    if (val == IAC) {
                        state = State.IAC;
                        return -1;
                    }
                    if (val == 0) {
                        return -1;
                    }
                    return val;
                }
                case IAC: {
                    switch (val) {
                        case WILL:
                            state = State.WILL;
                            break;
                        case WONT:
                            state = State.WONT;
                            break;
                        case DO:
                            state = State.DO;
                            break;
                        case DONT:
                            state = State.DONT;
                            break;
                        case SB: {
                            subOpt.reset();
                            state = State.SB;
                            break;
                        }
                        case IAC: {
                            state = State.DATA;
                            return IAC;
                        }
                        default:
                            state = State.DATA;
                            break;
                    }
                    return -1;
                }
                case WILL: {
                    registrarIac("WILL:" + val);
                    if (val == OPT_ECHO) {
                        serverWillEcho = true;
                        enviarIacOnce(doSent, DO, val);
                    } else if (val == OPT_SGA) {
                        enviarIacOnce(doSent, DO, val);
                    } else {
                        enviarIacOnce(dontSent, DONT, val);
                    }
                    state = State.DATA;
                    return -1;
                }
                case WONT: {
                    registrarIac("WONT:" + val);
                    state = State.DATA;
                    return -1;
                }
                case DO: {
                    registrarIac("DO:" + val);
                    if (val == OPT_SGA || val == OPT_TTYPE) {
                        enviarIacOnce(willSent, WILL, val);
                    } else {
                        enviarIacOnce(wontSent, WONT, val);
                    }
                    state = State.DATA;
                    return -1;
                }
                case DONT: {
                    registrarIac("DONT:" + val);
                    state = State.DATA;
                    return -1;
                }
                case SB: {
                    if (val == IAC) {
                        state = State.SB_IAC;
                    } else if (subOpt.size() < 64) {
                        subOpt.write(val);
                    }
                    return -1;
                }
                case SB_IAC: {
                    if (val == SE) {
                        byte[] sb = subOpt.toByteArray();
                        if (sb.length >= 2 && (sb[0] & 0xFF) == OPT_TTYPE && (sb[1] & 0xFF) == 1) {
                            byte[] resp = { (byte) IAC, (byte) SB, (byte) OPT_TTYPE, 0,
                                    'V', 'T', '1', '0', '0', (byte) IAC, (byte) SE };
                            synchronized (writeLock) {
                                out.write(resp);
                                out.flush();
                            }
                        }
                        state = State.DATA;
                    } else if (val == IAC) {
                        if (subOpt.size() < 64) {
                            subOpt.write(IAC);
                        }
                        state = State.SB;
                    } else {
                        state = State.DATA;
                    }
                    return -1;
                }
            }
            return -1;
        }

        private void registrarIac(String item) {
            synchronized (iacObservados) {
                iacObservados.add(item);
            }
        }

        private void enviarIacOnce(boolean[] flag, int cmd, int opt) throws IOException {
            if (opt < 0 || opt >= flag.length || flag[opt]) {
                return;
            }
            flag[opt] = true;
            synchronized (writeLock) {
                out.write(new byte[]{ (byte) IAC, (byte) cmd, (byte) opt });
                out.flush();
            }
        }
    }
}

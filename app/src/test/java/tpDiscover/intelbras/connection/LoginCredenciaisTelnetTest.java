package tpDiscover.intelbras.connection;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import tpDiscover.intelbras.util.Compat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cenários de login Telnet com equipamentos simulados: terminador de linha de switches SG legados,
 * cadeia de credenciais na mesma sessão, reconexão após queda e senha de enable diferente.
 */
class LoginCredenciaisTelnetTest {

    /** Switch SG legado: CR+LF é interpretado como DOIS Enters (o LF vira uma senha vazia). */
    @Test
    void deveAutenticarSwitchSgQueLeCrLfComoDoisEntersViaFallbackRaw() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            AtomicInteger conexoes = new AtomicInteger();
            CompletableFuture<Void> sim = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        try (Socket s = server.accept()) {
                            conexoes.incrementAndGet();
                            if (sessaoSwitch(s, true, "admin", "Admin@1234", "SG-2404-MR>", "Admin@1234")) {
                                return;
                            }
                        } catch (java.net.SocketException fimDaSessao) {
                            // O cliente fecha a 1ª sessão (Ctrl+C + desconexão) antes de reconectar em modo
                            // raw: o "connection reset" encerra só esta sessão, como num switch real.
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            ConnectionConfig cfg = ConnectionConfig.comOpcoes(
                    "127.0.0.1", "admin", "Admin@1234", server.getLocalPort(), 6_000, true);
            TelnetSwitchClient cli = new TelnetSwitchClient(cfg);
            cli.connect();
            String saida = cli.executarComando("show version");
            cli.desconectar();
            sim.join();

            assertEquals(2, conexoes.get(), "deve cair no socket raw NVT (\\r\\0) após detectar o CR/LF duplo");
            assertTrue(saida.contains("Hardware Version"));
            assertTrue(cli.credencialEfetiva().mesmoParQue("admin", "Admin@1234"));
        }
    }

    /** Equipamento sem senha: credencial só com usuário (senha em branco) deve autenticar. */
    @Test
    void deveAutenticarSomenteComUsuarioQuandoSenhaEmBranco() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> sim = CompletableFuture.runAsync(() -> {
                try (Socket s = server.accept()) {
                    sessaoSwitch(s, false, "admin", "", "SW-SEM-SENHA>", "");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            ConnectionConfig cfg = ConnectionConfig.comOpcoes(
                    "127.0.0.1", "admin", "", server.getLocalPort(), 6_000, true);
            TelnetSwitchClient cli = new TelnetSwitchClient(cfg);
            cli.connect();
            String saida = cli.executarComando("show version");
            cli.desconectar();
            sim.join();

            assertTrue(saida.contains("Hardware Version"), saida);
            assertTrue(cli.credencialEfetiva().mesmoParQue("admin", ""));
        }
    }

    /** Senha principal errada; a alternativa admin/admin é testada na MESMA sessão. */
    @Test
    void deveTestarCredencialAlternativaNaMesmaSessaoTelnet() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            AtomicInteger conexoes = new AtomicInteger();
            List<String> senhasRecebidas = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> sim = CompletableFuture.runAsync(() -> {
                try (Socket s = server.accept()) {
                    conexoes.incrementAndGet();
                    PushbackInputStream in = new PushbackInputStream(s.getInputStream(), 4);
                    OutputStream out = s.getOutputStream();
                    escrever(out, "\r\nUsername: ");
                    while (true) {
                        String u = lerLinha(in, false);
                        escrever(out, "\r\nPassword: ");
                        String p = lerLinha(in, false);
                        senhasRecebidas.add(p);
                        if ("admin".equals(u) && "admin".equals(p)) {
                            escrever(out, "\r\nS3028G-B#");
                            break;
                        }
                        escrever(out, "\r\nLogin incorrect\r\nUsername: ");
                    }
                    assertEquals("show version", lerLinha(in, false));
                    escrever(out, "\r\nProduct Name: S3028G-B\r\nS3028G-B#");
                    lerLinha(in, false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            ConnectionConfig cfg = ConnectionConfig.comOpcoes(
                            "127.0.0.1", "admin", "Admin@1234", server.getLocalPort(), 6_000, true)
                    .comCredenciaisAlternativas(Compat.listOf(new CredencialLogin("admin", "admin", "Padrão de fábrica")));
            TelnetSwitchClient cli = new TelnetSwitchClient(cfg);
            cli.connect();
            String saida = cli.executarComando("show version");
            cli.desconectar();
            sim.join();

            assertEquals(1, conexoes.get());
            assertEquals(Compat.listOf("Admin@1234", "admin"), senhasRecebidas);
            assertTrue(saida.contains("S3028G-B"));
            assertTrue(cli.credencialEfetiva().mesmoParQue("admin", "admin"));
            assertEquals("Padrão de fábrica", cli.credencialEfetiva().rotulo());
        }
    }

    /** Equipamento derruba a sessão após 1 senha errada: reconecta e segue a cadeia SEM repetir a que falhou. */
    @Test
    void deveReconectarAposQuedaEContinuarCadeiaSemRepetirCredencial() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            List<String> paresRecebidos = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> sim = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        try (Socket s = server.accept()) {
                            PushbackInputStream in = new PushbackInputStream(s.getInputStream(), 4);
                            OutputStream out = s.getOutputStream();
                            escrever(out, "\r\nUsername: ");
                            String u = lerLinha(in, false);
                            escrever(out, "\r\nPassword: ");
                            String p = lerLinha(in, false);
                            paresRecebidos.add(u + "/" + p);
                            if ("admin".equals(u) && "admin".equals(p)) {
                                escrever(out, "\r\nINTELBRAS#");
                                lerLinha(in, false);
                                return;
                            }
                            escrever(out, "\r\n% Login failed!\r\n");
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            ConnectionConfig cfg = ConnectionConfig.comOpcoes(
                            "127.0.0.1", "admin", "Admin@1234", server.getLocalPort(), 6_000, true)
                    .comCredenciaisAlternativas(Compat.listOf(new CredencialLogin("admin", "admin")));
            TelnetSwitchClient cli = new TelnetSwitchClient(cfg);
            cli.connect();
            cli.desconectar();
            sim.join();

            assertEquals(Compat.listOf("admin/Admin@1234", "admin/admin"), paresRecebidos);
            assertTrue(cli.credencialEfetiva().mesmoParQue("admin", "admin"));
        }
    }

    /** Todas as credenciais erradas: erro de autenticação, sem cair em loop de reconexões. */
    @Test
    void deveReportarFalhaDeAutenticacaoQuandoNenhumaCredencialServe() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            AtomicInteger conexoes = new AtomicInteger();
            CompletableFuture<Void> sim = CompletableFuture.runAsync(() -> {
                try (Socket s = server.accept()) {
                    conexoes.incrementAndGet();
                    PushbackInputStream in = new PushbackInputStream(s.getInputStream(), 4);
                    OutputStream out = s.getOutputStream();
                    escrever(out, "\r\nUsername: ");
                    for (int i = 0; i < 2; i++) {
                        lerLinha(in, false);
                        escrever(out, "\r\nPassword: ");
                        lerLinha(in, false);
                        escrever(out, "\r\nLogin incorrect\r\nUsername: ");
                    }
                    s.setSoTimeout(3_000);
                    try {
                        while (in.read() >= 0) { /* drena até o cliente fechar */ }
                    } catch (IOException ignored) {
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            ConnectionConfig cfg = ConnectionConfig.comOpcoes(
                            "127.0.0.1", "admin", "errada1", server.getLocalPort(), 6_000, true)
                    .comCredenciaisAlternativas(Compat.listOf(new CredencialLogin("admin", "errada2")));
            TelnetSwitchClient cli = new TelnetSwitchClient(cfg);
            SwitchConnectionException ex = assertThrows(SwitchConnectionException.class, cli::connect);
            cli.desconectar();
            sim.join();

            assertEquals(SwitchConnectionException.Tipo.FALHA_AUTENTICACAO, ex.getTipo());
            assertEquals(1, conexoes.get());
        }
    }

    /** Senha de enable diferente da de login: tenta a próxima senha da cadeia. */
    @Test
    void deveTentarSenhasDaCadeiaNoEnable() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            List<String> senhasEnable = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> sim = CompletableFuture.runAsync(() -> {
                try (Socket s = server.accept()) {
                    PushbackInputStream in = new PushbackInputStream(s.getInputStream(), 4);
                    OutputStream out = s.getOutputStream();
                    escrever(out, "\r\nUsername: ");
                    assertEquals("admin", lerLinha(in, false));
                    escrever(out, "\r\nPassword: ");
                    assertEquals("Admin@1234", lerLinha(in, false));
                    escrever(out, "\r\nSG-5204-MR-L2+>");
                    assertEquals("enable", lerLinha(in, false));
                    escrever(out, "\r\nPassword: ");
                    while (true) {
                        String p = lerLinha(in, false);
                        senhasEnable.add(p);
                        if ("admin".equals(p)) {
                            escrever(out, "\r\nSG-5204-MR-L2+#");
                            break;
                        }
                        escrever(out, "\r\nPassword: ");
                    }
                    lerLinha(in, false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            ConnectionConfig cfg = ConnectionConfig.comOpcoes(
                            "127.0.0.1", "admin", "Admin@1234", server.getLocalPort(), 6_000, true)
                    .comCredenciaisAlternativas(Compat.listOf(new CredencialLogin("admin", "admin")));
            TelnetSwitchClient cli = new TelnetSwitchClient(cfg);
            cli.connect();
            cli.desconectar();
            sim.join();

            assertEquals(Compat.listOf("Admin@1234", "admin"), senhasEnable);
        }
    }

    @Test
    void cadeiaDeCredenciaisDeveRemoverDuplicatasEManterPrincipalPrimeiro() {
        ConnectionConfig cfg = ConnectionConfig.padrao("10.1.40.10", "admin", "Admin@1234")
                .comCredenciaisAlternativas(Compat.listOf(
                        new CredencialLogin("admin", "Admin@1234"),
                        new CredencialLogin("admin", "admin"),
                        new CredencialLogin("", "x"),
                        new CredencialLogin("admin", "admin")));
        List<CredencialLogin> cadeia = cfg.cadeiaCredenciais();
        assertEquals(2, cadeia.size());
        assertTrue(cadeia.get(0).mesmoParQue("admin", "Admin@1234"));
        assertTrue(cadeia.get(1).mesmoParQue("admin", "admin"));
        assertFalse(cadeia.get(1).toString().contains("admin\0"));
        assertNotNull(cfg.credencialPrincipal());
    }

    // ---------- simulador ----------

    /**
     * Sessão de switch com login + enable + um comando. Retorna true se autenticou.
     *
     * @param crLfDuplo se true, '\r' e '\n' encerram linhas separadamente (LF vira Enter extra)
     */
    private static boolean sessaoSwitch(Socket s, boolean crLfDuplo, String usuario, String senha,
                                        String promptUsuario, String senhaEnable) throws IOException {
        PushbackInputStream in = new PushbackInputStream(s.getInputStream(), 4);
        OutputStream out = s.getOutputStream();
        escrever(out, "\r\nUsername: ");
        String u = null;
        while (true) {
            String linha = lerLinha(in, crLfDuplo);
            if (linha == null) {
                return false;
            }
            if (u == null) {
                if (linha.isEmpty()) {
                    escrever(out, "\r\nUsername: ");
                    continue;
                }
                u = linha;
                escrever(out, "\r\nPassword: ");
                continue;
            }
            if (u.equals(usuario) && linha.equals(senha)) {
                break;
            }
            u = null;
            escrever(out, "\r\nLogin incorrect\r\nUsername: ");
        }
        escrever(out, "\r\n" + promptUsuario);
        String cmd = proximaLinhaNaoVazia(in, crLfDuplo);
        if ("enable".equals(cmd)) {
            escrever(out, "\r\nPassword: ");
            String p = lerLinha(in, crLfDuplo);
            assertEquals(senhaEnable, p);
            String privilegiado = promptUsuario.replace('>', '#');
            escrever(out, "\r\n" + privilegiado);
            cmd = proximaLinhaNaoVazia(in, crLfDuplo);
            assertEquals("show version", cmd);
            escrever(out, "\r\nHardware Version: Rev.B\r\n" + privilegiado);
            proximaLinhaNaoVazia(in, crLfDuplo);
        }
        return true;
    }

    private static String proximaLinhaNaoVazia(PushbackInputStream in, boolean crLfDuplo) throws IOException {
        while (true) {
            String l = lerLinha(in, crLfDuplo);
            if (l == null || !l.isEmpty()) {
                return l;
            }
        }
    }

    private static void escrever(OutputStream out, String texto) throws IOException {
        out.write(texto.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Lê uma linha ignorando negociação IAC; retorna null em EOF sem dados. */
    private static String lerLinha(PushbackInputStream in, boolean crLfDuplo) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return buf.size() > 0 ? new String(buf.toByteArray(), StandardCharsets.UTF_8) : null;
            }
            if (b == 255) {
                pularIac(in);
                continue;
            }
            if (b == 0) {
                continue;
            }
            if (b == '\r') {
                if (!crLfDuplo) {
                    int prox = in.read();
                    if (prox >= 0 && prox != '\n' && prox != 0) {
                        in.unread(prox);
                    }
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
            if (b == '\n') {
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
            buf.write(b);
        }
    }

    private static void pularIac(InputStream in) throws IOException {
        int cmd = in.read();
        if (cmd >= 251 && cmd <= 254) {
            in.read();
        } else if (cmd == 250) {
            int prev = -1;
            while (true) {
                int x = in.read();
                if (x < 0 || (prev == 255 && x == 240)) break;
                prev = x;
            }
        }
    }
}

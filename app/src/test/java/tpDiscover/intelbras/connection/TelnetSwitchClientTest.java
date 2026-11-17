package tpDiscover.intelbras.connection;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelnetSwitchClientTest {

    @Test
    void deveEnviarEntersAteAcordarSwitchSgLentoEAutenticarViaTelnet() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            AtomicInteger entersRecebidos = new AtomicInteger(0);
            CompletableFuture<String> sessaoSwitch = CompletableFuture.supplyAsync(() -> {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    OutputStream out = client.getOutputStream();

                    // Simula switch SG silencioso que exige pelo menos 2 Enters antes de enviar Username:
                    StringBuilder antesLogin = new StringBuilder();
                    while (entersRecebidos.get() < 2) {
                        int b = in.read();
                        if (b < 0) break;
                        antesLogin.append((char) b);
                        if (b == '\n') {
                            entersRecebidos.incrementAndGet();
                        }
                    }

                    out.write("\r\n\r\nUsername: ".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    String usuario = lerLinhaLimpa(in);
                    assertEquals("admin", usuario);

                    out.write("Password: ".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    String senha = lerLinhaLimpa(in);
                    assertEquals("Segredo123", senha);

                    out.write("\r\nSG-5204-MR-L2+>".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    String cmdEnable = lerLinhaLimpa(in);
                    assertEquals("enable", cmdEnable);

                    out.write("\r\nSG-5204-MR-L2+#".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    String cmdShow = lerLinhaLimpa(in);
                    assertEquals("show version", cmdShow);
                    out.write("\r\nHardware Version: Rev.B\r\nSG-5204-MR-L2+#".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    return lerLinhaLimpa(in);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            ConnectionConfig cfg = ConnectionConfig.comOpcoes(
                    "127.0.0.1", "admin", "Segredo123", server.getLocalPort(), 6_000, true);
            TelnetSwitchClient cli = new TelnetSwitchClient(cfg);
            cli.connect();
            String saida = cli.executarComando("show version");
            cli.desconectar();

            assertTrue(entersRecebidos.get() >= 2);
            assertTrue(saida.contains("Hardware Version: Rev.B"));
            assertEquals("exit", sessaoSwitch.join());
        }
    }

    @Test
    void deveFazerFallbackParaSocketRawNvtQuandoTelnetClientForEncerrado() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            AtomicInteger conexoes = new AtomicInteger(0);
            CompletableFuture<String> ultimaLinha = CompletableFuture.supplyAsync(() -> {
                try {
                    // 1ª tentativa (CommonsNet): fecha imediatamente sem responder
                    try (Socket s1 = server.accept()) {
                        conexoes.incrementAndGet();
                        s1.close();
                    }

                    // 2ª tentativa (Raw NVT fallback): envia IAC WILL ECHO + WILL SGA e espera \r\0
                    try (Socket s2 = server.accept()) {
                        conexoes.incrementAndGet();
                        InputStream in = s2.getInputStream();
                        OutputStream out = s2.getOutputStream();

                        // IAC WILL ECHO (255,251,1) + IAC WILL SGA (255,251,3)
                        out.write(new byte[]{ (byte) 255, (byte) 251, 1, (byte) 255, (byte) 251, 3 });
                        out.flush();

                        // Descarta negociação IAC de retorno e aguarda \r\0
                        int prev = -1;
                        while (true) {
                            int b = in.read();
                            if (b < 0) throw new IOException("EOF inesperado");
                            if (b == 255) {
                                in.read();
                                in.read();
                                prev = -1;
                                continue;
                            }
                            if (prev == '\r' && b == 0) {
                                break;
                            }
                            prev = b;
                        }

                        out.write("\r\nUsername: ".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        assertEquals("admin", lerLinhaLimpa(in));

                        out.write("Password: ".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        assertEquals("intelbras", lerLinhaLimpa(in));

                        out.write("\r\nINTELBRAS>".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        assertEquals("enable", lerLinhaLimpa(in));

                        out.write("\r\nINTELBRAS#".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        assertEquals("show system-info", lerLinhaLimpa(in));

                        out.write("\r\nSystem Name: SG-5204\r\nINTELBRAS#".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        return lerLinhaLimpa(in);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            ConnectionConfig cfg = ConnectionConfig.comOpcoes(
                    "127.0.0.1", "admin", "intelbras", server.getLocalPort(), 6_000, true);
            TelnetSwitchClient cli = new TelnetSwitchClient(cfg);
            cli.connect();
            String saida = cli.executarComando("show system-info");
            cli.desconectar();

            assertEquals(2, conexoes.get());
            assertTrue(saida.contains("System Name: SG-5204"));
            assertEquals("exit", ultimaLinha.join());
        }
    }

    private static String lerLinhaLimpa(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) break;
            if (b == 255) {
                int cmd = in.read();
                int opt = in.read();
                if (cmd == 250 && opt >= 0) {
                    while (true) {
                        int x = in.read();
                        if (x < 0 || (x == 255 && in.read() == 240)) break;
                    }
                }
                continue;
            }
            if (b == '\r') {
                in.mark(1);
                continue;
            }
            if (b == '\n' || b == 0) {
                if (buf.size() > 0) break;
                continue;
            }
            buf.write(b);
        }
        return buf.toString(StandardCharsets.UTF_8).trim();
    }
}

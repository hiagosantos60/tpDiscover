package tpDiscover.intelbras.connection;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import tpDiscover.intelbras.util.Compat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressões encontradas no lab com switches SG MR legados. */
class PromptETerminadorTest {

    @Test
    void deveReconhecerPromptComEspacosDoSg2404Mr() {
        assertTrue(TerminalStreamHelper.PROMPT.matcher("SG 2404 MR>").find());
        assertTrue(TerminalStreamHelper.PROMPT.matcher("SG 2404 MR#").find());
        assertTrue(TerminalStreamHelper.PROMPT.matcher("SG-5204-MRL-L2+-2> ").find());
        // Linhas comuns de saída não podem virar prompt
        assertFalse(TerminalStreamHelper.PROMPT.matcher("Error: Invalid parameter.").find());
        assertFalse(TerminalStreamHelper.PROMPT.matcher("Username: ").find());
        assertFalse(TerminalStreamHelper.PROMPT.matcher("Press any key to continue").find());
    }

    @Test
    void deveUsarCrNoSshIpssh6DosSgLegados() {
        assertEquals("\r", SshSwitchClient.terminadorParaServidor("SSH-1.99-IPSSH-6.6.0"));
        assertEquals("\n", SshSwitchClient.terminadorParaServidor("SSH-2.0-IPSSH_5.8.0"));
        assertEquals("\n", SshSwitchClient.terminadorParaServidor("SSH-2.0-OS-4.1"));
        assertEquals("\n", SshSwitchClient.terminadorParaServidor(null));
    }

    /**
     * SG 5204 MR L2+: o leitor de senha do 'enable' processa 1 caractere por pacote recebido.
     * A senha precisa chegar "digitada" (um write por byte); com tudo em um pacote o switch trava.
     */
    @Test
    void deveDigitarSenhaDoEnableCaracterePorCaractere() throws Exception {
        PipedOutputStream swTx = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(swTx);
        ContadorDeWrites out = new ContadorDeWrites();

        CompletableFuture<Void> sim = CompletableFuture.runAsync(() -> {
            try {
                while (!out.texto().contains("enable")) Thread.sleep(10);
                swTx.write("enable\r\nPassword: ".getBytes(StandardCharsets.US_ASCII));
                swTx.flush();
                while (!out.texto().endsWith("admin\r\n")) Thread.sleep(10);
                swTx.write("*****\r\nSG-5204-MR-L2+# ".getBytes(StandardCharsets.US_ASCII));
                swTx.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        ConnectionConfig cfg = new ConnectionConfig("10.1.40.84", "admin", "admin", 22, 23, 5_000);
        TerminalStreamHelper.entrarModoPrivilegiado(in, out, cfg, "Telnet", "\r\n",
                "SG-5204-MR-L2+> ", Compat.listOf("admin"), null);
        sim.join();

        // "enable\r\n" em 1 write + "admin\r\n" em 7 writes de 1 byte
        assertEquals(1 + 7, out.writes);
    }

    private static final class ContadorDeWrites extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        volatile int writes;

        @Override
        public synchronized void write(int b) {
            buf.write(b);
            writes++;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buf.write(b, off, len);
            writes++;
        }

        synchronized String texto() {
            return new String(buf.toByteArray(), StandardCharsets.US_ASCII);
        }
    }
}

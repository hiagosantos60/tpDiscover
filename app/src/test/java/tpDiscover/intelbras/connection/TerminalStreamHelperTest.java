package tpDiscover.intelbras.connection;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class TerminalStreamHelperTest {

    @Test
    void deveAvancarPaginacaoPressAnyKeyERetornarAtePrompt() throws Exception {
        PipedOutputStream switchTx = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(switchTx);
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream switchRx = new PipedInputStream(out);

        CompletableFuture<Void> simuladorSwitch = CompletableFuture.runAsync(() -> {
            try {
                String pagina1 = "show bridge address all\r\n"
                        + "18:0d:2c:08:d5:10  1       1    dynamic aging\r\n"
                        + "Press any key to continue (Q to quit)";
                switchTx.write(pagina1.getBytes(StandardCharsets.UTF_8));
                switchTx.flush();

                int b = switchRx.read();
                assertEquals(' ', b);

                String pagina2 = "\r\n30:e1:f1:ff:66:44  1       2    dynamic aging\r\nINTELBRAS#";
                switchTx.write(pagina2.getBytes(StandardCharsets.UTF_8));
                switchTx.flush();
                switchTx.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        String lido = TerminalStreamHelper.lerAte(
                in, out, "10.1.40.111", "Telnet", 2_000, "\r\n", TerminalStreamHelper.PROMPT
        );
        simuladorSwitch.join();

        assertTrue(lido.contains("18:0d:2c:08:d5:10"));
        assertTrue(lido.contains("30:e1:f1:ff:66:44"));
        assertFalse(lido.contains("Press any key to continue"));
    }

    @Test
    void deveDetectarPromptsDeOltsERemoverSequenciasAnsi() {
        assertTrue(TerminalStreamHelper.PROMPT.matcher("Admin(config)# ").find());
        assertTrue(TerminalStreamHelper.PROMPT.matcher("Admin\\fdb# ").find());
        assertTrue(TerminalStreamHelper.PROMPT.matcher("OLT4840E(config)#").find());
        assertTrue(TerminalStreamHelper.PROMPT.matcher("intelbras-olt>").find());
        assertTrue(TerminalStreamHelper.PROMPT.matcher("G16#").find());

        String comAnsi = "\u001B[19;05H\u001B[2JMaster\r\nUser> ";
        assertEquals("User> ", TerminalStreamHelper.ultimaLinha(comAnsi));
    }

    @Test
    void deveReautenticarComGeponEmOltAnAposFalhaDeCredencialComum() throws Exception {
        PipedOutputStream oltTx = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(oltTx);
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream oltRx = new PipedInputStream(out);

        CompletableFuture<Void> simuladorOltAn = CompletableFuture.runAsync(() -> {
            try {
                java.io.BufferedReader rx = new java.io.BufferedReader(new java.io.InputStreamReader(oltRx, StandardCharsets.UTF_8));
                assertEquals("admin", rx.readLine());
                oltTx.write("Password: ".getBytes(StandardCharsets.UTF_8));
                oltTx.flush();

                assertEquals("Admin@1234", rx.readLine());
                oltTx.write("Bad UserName or Bad Password , Login Failed.\r\nPlease retry\r\nLogin: ".getBytes(StandardCharsets.UTF_8));
                oltTx.flush();

                assertEquals("GEPON", rx.readLine());
                oltTx.write("Password: ".getBytes(StandardCharsets.UTF_8));
                oltTx.flush();

                assertEquals("GEPON", rx.readLine());
                oltTx.write("\r\nMaster\r\n\r\nUser> ".getBytes(StandardCharsets.UTF_8));
                oltTx.flush();
                oltTx.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        ConnectionConfig cfg = new ConnectionConfig("10.1.40.140", "admin", "Admin@1234", 22, 23, 5000);
        String saidaInicial = "\u001B[19;05Hzzzzzzzzzzzzzzzzzzzz\r\nLogin: ";
        TerminalStreamHelper.AuthResult res = TerminalStreamHelper.autenticarComFallback(
                in, out, cfg, "Telnet", "\r\n", saidaInicial);
        simuladorOltAn.join();

        assertEquals("GEPON", res.senhaEfetiva());
        assertTrue(res.bannerCompleto().contains("zzzzzzzzzzzz"));
        assertTrue(res.ultimaSaida().contains("User>"));
    }

    @Test
    void deveDetectarPromptsComMaisEModoPrivilegiadoDeSwitchesSgMrL2Plus() {
        assertTrue(TerminalStreamHelper.PROMPT.matcher("SG-5204-MRL-L2+-2> ").find());
        assertTrue(TerminalStreamHelper.PROMPT.matcher("SG-5204-MRL-L2+-2#").find());
        assertTrue(TerminalStreamHelper.PROMPT.matcher("SG-5204-MR-L2+>").find());
        assertTrue(TerminalStreamHelper.PROMPT.matcher("SG-5204-MR-L2+# ").find());
        assertTrue(TerminalStreamHelper.PROMPT.matcher("INTELBRAS> ").find());
    }

    @Test
    void deveDetectarEofImediatamenteESepararErroDeAuthDeTelnetSemSenha() throws Exception {
        java.io.ByteArrayInputStream semSenhaIn = new java.io.ByteArrayInputStream(
                "Password required, but none set\r\n".getBytes(StandardCharsets.UTF_8));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        SwitchConnectionException exSemSenha = assertThrows(SwitchConnectionException.class, () ->
                TerminalStreamHelper.lerAte(
                        semSenhaIn, out, "10.1.40.120", "Telnet", 5_000, "\r\n",
                        () -> semSenhaIn.available() == 0,
                        null,
                        TerminalStreamHelper.LOGIN_PROMPT,
                        TerminalStreamHelper.SENHA_PROMPT,
                        TerminalStreamHelper.PROMPT
                ));
        assertEquals(SwitchConnectionException.Tipo.ERRO_PROTOCOLO, exSemSenha.getTipo());
        assertTrue(exSemSenha.getMessage().contains("Password required"));

        java.io.ByteArrayInputStream authFailIn = new java.io.ByteArrayInputStream(
                "Username: admin\r\nPassword: \r\nAuthentication failed!\r\n".getBytes(StandardCharsets.UTF_8));
        SwitchConnectionException exAuth = assertThrows(SwitchConnectionException.class, () ->
                TerminalStreamHelper.lerAte(
                        authFailIn, out, "10.1.40.180", "Telnet", 5_000, "\r\n",
                        () -> authFailIn.available() == 0,
                        null,
                        TerminalStreamHelper.PROMPT
                ));
        assertEquals(SwitchConnectionException.Tipo.FALHA_AUTENTICACAO, exAuth.getTipo());
    }
}

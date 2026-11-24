package tpDiscover.intelbras.connection;

import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.cipher.BuiltinCiphers;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.mac.BuiltinMacs;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conecta o {@link SshSwitchClient} (JSch) a um servidor SSH real (Apache MINA) imitando a CLI
 * Comware, com os perfis de algoritmos encontrados em campo. Serve para garantir que a coleta via
 * SSH funciona no runtime Java 8 (onde curve25519/ed25519 não existem nativamente).
 */
class SshSwitchClientJava8Test {

    private SshServer servidor;

    @AfterEach
    void parar() throws IOException {
        if (servidor != null) servidor.stop(true);
    }

    @Test
    void switchModernoComEcdsaEcdhEAesCtr() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        iniciar(g.generateKeyPair(),
                BuiltinSignatures.nistp256,
                BuiltinDHFactories.ecdhp256,
                BuiltinCiphers.aes128ctr,
                BuiltinMacs.hmacsha256);
        validarColeta();
    }

    @Test
    void switchLegadoComRsaDhGroup14Sha1EAesCbc() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        iniciar(g.generateKeyPair(),
                BuiltinSignatures.rsa,
                BuiltinDHFactories.dhg14,
                BuiltinCiphers.aes128cbc,
                BuiltinMacs.hmacsha1);
        validarColeta();
    }

    @Test
    void equipamentoQueSoOfereceChaveEd25519() throws Exception {
        assertTrue(SecurityUtils.isEDDSACurveSupported(), "biblioteca EdDSA do servidor de teste");
        KeyPair kp = SecurityUtils.getKeyPairGenerator(SecurityUtils.EDDSA).generateKeyPair();
        iniciar(kp,
                BuiltinSignatures.ed25519,
                BuiltinDHFactories.ecdhp256,
                BuiltinCiphers.aes256ctr,
                BuiltinMacs.hmacsha256);
        validarColeta();
    }

    @Test
    void equipamentoQueSoNegociaCurve25519() throws Exception {
        KeyPair kp = SecurityUtils.getKeyPairGenerator(SecurityUtils.EDDSA).generateKeyPair();
        iniciar(kp,
                BuiltinSignatures.ed25519,
                BuiltinDHFactories.curve25519,
                BuiltinCiphers.aes128ctr,
                BuiltinMacs.hmacsha256);
        validarColeta();
    }

    @Test
    void equipamentoQueAceitaSomenteUsuarioSemSenha() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        iniciar(g.generateKeyPair(), BuiltinSignatures.nistp256, BuiltinDHFactories.ecdhp256,
                BuiltinCiphers.aes128ctr, BuiltinMacs.hmacsha256);
        servidor.setPasswordAuthenticator((u, p, s) -> "admin".equals(u) && p.isEmpty());
        SshSwitchClient cliente = new SshSwitchClient(new ConnectionConfig(
                "127.0.0.1", "admin", "", servidor.getPort(), 23, 10_000));
        try {
            cliente.connect();
            assertTrue(cliente.executarComando("display version").contains("Version 7.1.064"));
        } finally {
            cliente.desconectar();
        }
    }

    @Test
    void senhaErradaViraFalhaDeAutenticacao() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        iniciar(g.generateKeyPair(), BuiltinSignatures.nistp256, BuiltinDHFactories.ecdhp256,
                BuiltinCiphers.aes128ctr, BuiltinMacs.hmacsha256);
        SshSwitchClient cliente = new SshSwitchClient(new ConnectionConfig(
                "127.0.0.1", "admin", "errada", servidor.getPort(), 23, 8_000));
        SwitchConnectionException e = assertThrows(SwitchConnectionException.class, cliente::connect);
        assertEquals(SwitchConnectionException.Tipo.FALHA_AUTENTICACAO, e.getTipo());
    }

    // ------------------------------------------------------------------

    private void validarColeta() throws Exception {
        SshSwitchClient cliente = new SshSwitchClient(new ConnectionConfig(
                "127.0.0.1", "admin", "teste123", servidor.getPort(), 23, 10_000));
        try {
            cliente.connect();
            String versao = cliente.executarComando("display version");
            assertTrue(versao.contains("Comware Software, Version 7.1.064"), versao);
            String mac = cliente.executarComando("display mac-address");
            assertTrue(mac.contains("0009-0f09-1e0a"), mac);
        } finally {
            cliente.desconectar();
        }
    }

    private void iniciar(KeyPair chave, BuiltinSignatures assinatura, BuiltinDHFactories kex,
                         BuiltinCiphers cifra, BuiltinMacs mac) throws IOException {
        servidor = SshServer.setUpDefaultServer();
        servidor.setHost("127.0.0.1");
        servidor.setPort(0);
        servidor.setKeyPairProvider(KeyPairProvider.wrap(chave));
        servidor.setSignatureFactories(Collections.singletonList(assinatura));
        servidor.setKeyExchangeFactories(NamedFactory.setUpTransformedFactories(
                false, Collections.singletonList(kex), ServerBuilder.DH2KEX));
        servidor.setCipherFactories(Arrays.asList(cifra));
        servidor.setMacFactories(Arrays.asList(mac));
        servidor.setPasswordAuthenticator((u, p, s) -> "admin".equals(u) && "teste123".equals(p));
        servidor.setShellFactory(canal -> new ShellComware());
        servidor.start();
    }

    /** CLI mínima no estilo Comware: prompt &lt;SW-TESTE&gt;, eco do comando e respostas fixas. */
    private static final class ShellComware implements Command, Runnable {
        private InputStream in;
        private OutputStream out;
        private ExitCallback saida;
        private Thread thread;

        @Override public void setInputStream(InputStream in) { this.in = in; }

        @Override public void setOutputStream(OutputStream out) { this.out = out; }

        @Override public void setErrorStream(OutputStream err) { }

        @Override public void setExitCallback(ExitCallback callback) { this.saida = callback; }

        @Override
        public void start(ChannelSession channel, Environment env) {
            thread = new Thread(this, "shell-comware-teste");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (thread != null) thread.interrupt();
        }

        @Override
        public void run() {
            try {
                escrever("\r\n******************************************************************************\r\n"
                        + "* Copyright (c) Intelbras. All rights reserved.                              *\r\n"
                        + "******************************************************************************\r\n\r\n<SW-TESTE>");
                ByteArrayOutputStream linha = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) >= 0) {
                    if (b == '\r' || b == '\n') {
                        if (linha.size() == 0 && b == '\n') continue;
                        String cmd = new String(linha.toByteArray(), StandardCharsets.US_ASCII).trim();
                        linha.reset();
                        escrever(cmd + "\r\n" + responder(cmd) + "<SW-TESTE>");
                        if ("quit".equals(cmd)) break;
                    } else {
                        linha.write(b);
                    }
                }
            } catch (IOException ignored) {
                // canal fechado pelo cliente
            } finally {
                if (saida != null) saida.onExit(0);
            }
        }

        private String responder(String cmd) {
            if (cmd.startsWith("display version")) {
                return "INTELBRAS Comware Software, Version 7.1.064, Release 3208P05\r\n"
                        + "INTELBRAS S3028G-PB Max uptime is 0 weeks, 1 day, 2 hours, 3 minutes\r\n";
            }
            if (cmd.startsWith("display mac-address")) {
                return "MAC Address    VLAN ID    State            Port/Nickname            Aging\r\n"
                        + "0009-0f09-1e0a 1          Learned          GE1/0/24                 Y\r\n";
            }
            return "";
        }

        private void escrever(String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }
}

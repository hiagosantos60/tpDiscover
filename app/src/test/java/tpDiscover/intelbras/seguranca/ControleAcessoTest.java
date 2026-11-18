package tpDiscover.intelbras.seguranca;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A senha real não aparece nos testes nem no código: só o hash está no aplicativo. */
class ControleAcessoTest {

    @Test
    void recusaSenhaErradaVaziaOuNula() {
        assertFalse(ControleAcesso.senhaCorreta("admin".toCharArray()));
        assertFalse(ControleAcesso.senhaCorreta("Admin@1234".toCharArray()));
        assertFalse(ControleAcesso.senhaCorreta(new char[0]));
        assertFalse(ControleAcesso.senhaCorreta(null));
    }

    @Test
    void zeraASenhaDigitadaDepoisDeVerificar() {
        char[] senha = "qualquer-coisa".toCharArray();
        ControleAcesso.senhaCorreta(senha);
        for (char c : senha) {
            assertEquals('\0', c);
        }
    }

    @Test
    void hashEDeterministicoParaOMesmoSal() {
        byte[] sal = new byte[16];
        byte[] h1 = ControleAcesso.gerarHash("abc".toCharArray(), sal);
        byte[] h2 = ControleAcesso.gerarHash("abc".toCharArray(), sal);
        assertArrayEquals(h1, h2);
        assertEquals(32, h1.length);
    }
}

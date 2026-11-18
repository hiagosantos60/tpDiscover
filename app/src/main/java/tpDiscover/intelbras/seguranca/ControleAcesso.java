package tpDiscover.intelbras.seguranca;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Senha de acesso ao tpDiscover.
 *
 * <p>A senha não fica no código: só o hash PBKDF2-HMAC-SHA256 (120.000 iterações, sal aleatório
 * de 16 bytes). A comparação é em tempo constante e o vetor da senha digitada é zerado depois
 * da verificação.</p>
 *
 * <p>Para trocar a senha, gere um novo par sal/hash com {@link #gerarHash(char[], byte[])} e
 * substitua {@link #SAL} e {@link #HASH}.</p>
 */
public final class ControleAcesso {

    public static final int MAX_TENTATIVAS = 5;

    private static final String ALGORITMO = "PBKDF2WithHmacSHA256";
    private static final int ITERACOES = 120_000;
    private static final int BITS = 256;
    private static final byte[] SAL = Base64.getDecoder().decode("VuEIYC8WYIcxUZ1YKWDPyg==");
    private static final byte[] HASH = Base64.getDecoder().decode("EkgeI/hu7GrifUJKH3CNQUEDBmKAHKIi6Mn0LHbZjVQ=");

    private ControleAcesso() {
    }

    /** Confere a senha e zera {@code senha} em seguida. */
    public static boolean senhaCorreta(char[] senha) {
        if (senha == null) {
            return false;
        }
        try {
            if (senha.length == 0) {
                return false;
            }
            return MessageDigest.isEqual(gerarHash(senha, SAL), HASH);
        } finally {
            Arrays.fill(senha, '\0');
        }
    }

    /** PBKDF2 da senha com o sal informado (usado também para gerar um novo hash). */
    public static byte[] gerarHash(char[] senha, byte[] sal) {
        PBEKeySpec spec = new PBEKeySpec(senha, sal, ITERACOES, BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITMO).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponível neste Java", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Espera crescente após senha errada, para dificultar tentativa e erro. */
    public static void aguardarAposErro(int tentativa) {
        try {
            Thread.sleep(Math.min(5000L, 700L * tentativa));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

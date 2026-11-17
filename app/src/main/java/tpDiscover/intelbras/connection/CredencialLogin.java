package tpDiscover.intelbras.connection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Par usuário/senha usado pela camada de conexão (SSH/Telnet) durante o login.
 * O {@code rotulo} é apenas descritivo (ex.: "Padrão Intelbras") para logs/relatórios; nunca expõe a senha.
 */
public record CredencialLogin(String usuario, String senha, String rotulo) {

    public CredencialLogin {
        usuario = usuario != null ? usuario.trim() : "";
        senha = senha != null ? senha : "";
        rotulo = rotulo != null && !rotulo.isBlank() ? rotulo.trim() : usuario;
    }

    public CredencialLogin(String usuario, String senha) {
        this(usuario, senha, null);
    }

    public boolean mesmoParQue(CredencialLogin outra) {
        return outra != null && usuario.equals(outra.usuario) && senha.equals(outra.senha);
    }

    public boolean mesmoParQue(String outroUsuario, String outraSenha) {
        return mesmoParQue(new CredencialLogin(outroUsuario, outraSenha));
    }

    /** Remove nulos, usuários vazios e pares repetidos, preservando a ordem de prioridade. */
    public static List<CredencialLogin> semDuplicatas(List<CredencialLogin> credenciais) {
        List<CredencialLogin> resultado = new ArrayList<>();
        if (credenciais == null) {
            return resultado;
        }
        Set<String> vistas = new LinkedHashSet<>();
        for (CredencialLogin c : credenciais) {
            if (c == null || c.usuario().isBlank()) {
                continue;
            }
            if (vistas.add(c.usuario() + "\0" + c.senha())) {
                resultado.add(c);
            }
        }
        return resultado;
    }

    @Override
    public String toString() {
        return "CredencialLogin[usuario=" + usuario + ", rotulo=" + rotulo + ", senha=***]";
    }
}

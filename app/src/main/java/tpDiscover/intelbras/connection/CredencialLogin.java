package tpDiscover.intelbras.connection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tpDiscover.intelbras.util.Compat;
/**
 * Par usuário/senha usado pela camada de conexão (SSH/Telnet) durante o login.
 * O {@code rotulo} é apenas descritivo (ex.: "Padrão Intelbras") para logs/relatórios; nunca expõe a senha.
 */
public final class CredencialLogin {
    private final String usuario;
    private final String senha;
    private final String rotulo;

    public CredencialLogin(String usuario, String senha, String rotulo) {
        usuario = usuario != null ? usuario.trim() : "";
        senha = senha != null ? senha : "";
        rotulo = rotulo != null && !Compat.isBlank(rotulo) ? rotulo.trim() : usuario;
        this.usuario = usuario;
        this.senha = senha;
        this.rotulo = rotulo;
    }

    public String usuario() {
        return usuario;
    }

    public String senha() {
        return senha;
    }

    public String rotulo() {
        return rotulo;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CredencialLogin)) return false;
        CredencialLogin o = (CredencialLogin) obj;
        return java.util.Objects.equals(usuario, o.usuario)
                && java.util.Objects.equals(senha, o.senha)
                && java.util.Objects.equals(rotulo, o.rotulo);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(usuario, senha, rotulo);
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
            if (c == null || Compat.isBlank(c.usuario())) {
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

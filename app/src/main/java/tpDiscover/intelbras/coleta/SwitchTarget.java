package tpDiscover.intelbras.coleta;

/**
 * Um switch que o técnico informou pra coletar, com suas próprias credenciais
 * (nem sempre todos os switches de um cliente usam o mesmo usuário/senha).
 */
public final class SwitchTarget {
    private final String ip;
    private final String usuario;
    private final String senha;
    private final Integer portaCustomizada;

    public SwitchTarget(String ip, String usuario, String senha, Integer portaCustomizada) {
        this.ip = ip;
        this.usuario = usuario;
        this.senha = senha;
        this.portaCustomizada = portaCustomizada;
    }

    public String ip() {
        return ip;
    }

    public String usuario() {
        return usuario;
    }

    public String senha() {
        return senha;
    }

    public Integer portaCustomizada() {
        return portaCustomizada;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SwitchTarget)) return false;
        SwitchTarget o = (SwitchTarget) obj;
        return java.util.Objects.equals(ip, o.ip)
                && java.util.Objects.equals(usuario, o.usuario)
                && java.util.Objects.equals(senha, o.senha)
                && java.util.Objects.equals(portaCustomizada, o.portaCustomizada);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(ip, usuario, senha, portaCustomizada);
    }


    public SwitchTarget(String ip, String usuario, String senha) {
        this(ip, usuario, senha, null);
    }

    public boolean possuiSenha() {
        return senha != null;
    }

    public SwitchTarget comSenha(String novaSenha) {
        return new SwitchTarget(ip, usuario, novaSenha, portaCustomizada);
    }

    public String enderecoExibicao() {
        return portaCustomizada != null ? (ip + ":" + portaCustomizada) : ip;
    }

    @Override
    public String toString() {
        return "SwitchTarget[ip=" + enderecoExibicao() + ", usuario=" + usuario + ", senha=***]";
    }
}

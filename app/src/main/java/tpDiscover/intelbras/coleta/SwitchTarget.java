package tpDiscover.intelbras.coleta;

/**
 * Um switch que o técnico informou pra coletar, com suas próprias credenciais
 * (nem sempre todos os switches de um cliente usam o mesmo usuário/senha).
 */
public record SwitchTarget(String ip, String usuario, String senha, Integer portaCustomizada) {

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

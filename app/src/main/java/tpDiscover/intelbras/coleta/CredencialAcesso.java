package tpDiscover.intelbras.coleta;

import tpDiscover.intelbras.descoberta.DiscoveredProduct.FamiliaProduto;

import java.util.List;
import java.util.Objects;

import tpDiscover.intelbras.util.Compat;
/**
 * Credencial alternativa configurável usada automaticamente quando a credencial padrão
 * (ou específica do alvo) falha por autenticação em equipamentos heterogêneos (OLTs, switches legados, APs).
 */
public final class CredencialAcesso {
    private final boolean ativa;
    private final String nomePerfil;
    private final String usuario;
    private final String senha;
    private final Escopo escopo;

    public CredencialAcesso(boolean ativa, String nomePerfil, String usuario, String senha, Escopo escopo) {
        nomePerfil = (nomePerfil != null && !Compat.isBlank(nomePerfil)) ? nomePerfil.trim() : "Credencial extra";
        usuario = usuario != null ? usuario.trim() : "";
        senha = senha != null ? senha : "";
        escopo = escopo != null ? escopo : Escopo.TODOS;
        this.ativa = ativa;
        this.nomePerfil = nomePerfil;
        this.usuario = usuario;
        this.senha = senha;
        this.escopo = escopo;
    }

    public boolean ativa() {
        return ativa;
    }

    public String nomePerfil() {
        return nomePerfil;
    }

    public String usuario() {
        return usuario;
    }

    public String senha() {
        return senha;
    }

    public Escopo escopo() {
        return escopo;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CredencialAcesso)) return false;
        CredencialAcesso o = (CredencialAcesso) obj;
        return ativa == o.ativa
                && java.util.Objects.equals(nomePerfil, o.nomePerfil)
                && java.util.Objects.equals(usuario, o.usuario)
                && java.util.Objects.equals(senha, o.senha)
                && java.util.Objects.equals(escopo, o.escopo);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(ativa, nomePerfil, usuario, senha, escopo);
    }


    public enum Escopo {
        TODOS("Todos os equipamentos"),
        OLT("Somente OLTs"),
        SWITCH_E_AP("Switches e APs");

        private final String rotulo;

        Escopo(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }

        @Override
        public String toString() {
            return rotulo;
        }
    }

    public static List<CredencialAcesso> presetsPadrao() {
        return Compat.listOf(
                new CredencialAcesso(true, "OLTs Linha AN (6000/6001)", "GEPON", "GEPON", Escopo.OLT),
                new CredencialAcesso(true, "OLTs Padrão Fábrica (G08/G16/8820)", "admin", "admin", Escopo.OLT),
                new CredencialAcesso(true, "Switches e APs Padrão Fábrica", "admin", "admin", Escopo.SWITCH_E_AP)
        );
    }

    /**
     * Credenciais de fábrica/padrão de laboratório tentadas automaticamente (por último) quando a do alvo falha.
     * Não aparecem no diálogo de credenciais extras: são uma rede de segurança fixa.
     */
    public static List<CredencialAcesso> padroesFabrica(FamiliaProduto familia) {
        CredencialAcesso intelbras = new CredencialAcesso(true, "Padrão Intelbras", "admin", "Admin@1234", Escopo.TODOS);
        CredencialAcesso fabrica = new CredencialAcesso(true, "Padrão de fábrica", "admin", "admin", Escopo.TODOS);
        if (familia == FamiliaProduto.OLT) {
            return Compat.listOf(
                    new CredencialAcesso(true, "OLT FiberHome/AN padrão", "GEPON", "GEPON", Escopo.OLT),
                    fabrica,
                    intelbras);
        }
        return Compat.listOf(intelbras, fabrica);
    }

    public boolean ehUtilizavel() {
        return ativa && !Compat.isBlank(usuario);
    }

    public boolean aplicavelPara(FamiliaProduto familia) {
        if (!ehUtilizavel()) {
            return false;
        }
        switch (escopo) {
            case TODOS:
                return true;
            case OLT:
                return familia == FamiliaProduto.OLT;
            case SWITCH_E_AP:
                return familia == null || familia != FamiliaProduto.OLT;
            default:
                throw new IllegalStateException("Valor inesperado: " + (escopo));
        }
    }

    public boolean mesmaCredencialQue(String outroUsuario, String outraSenha) {
        String u = outroUsuario != null ? outroUsuario.trim() : "";
        String s = outraSenha != null ? outraSenha : "";
        return Objects.equals(usuario, u) && Objects.equals(senha, s);
    }
}

package tpDiscover.intelbras.coleta;

import tpDiscover.intelbras.descoberta.DiscoveredProduct.FamiliaProduto;

import java.util.List;
import java.util.Objects;

/**
 * Credencial alternativa configurável usada automaticamente quando a credencial padrão
 * (ou específica do alvo) falha por autenticação em equipamentos heterogêneos (OLTs, switches legados, APs).
 */
public record CredencialAcesso(
        boolean ativa,
        String nomePerfil,
        String usuario,
        String senha,
        Escopo escopo
) {

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

    public CredencialAcesso {
        nomePerfil = (nomePerfil != null && !nomePerfil.isBlank()) ? nomePerfil.trim() : "Credencial extra";
        usuario = usuario != null ? usuario.trim() : "";
        senha = senha != null ? senha : "";
        escopo = escopo != null ? escopo : Escopo.TODOS;
    }

    public static List<CredencialAcesso> presetsPadrao() {
        return List.of(
                new CredencialAcesso(true, "OLTs Linha AN (6000/6001)", "GEPON", "GEPON", Escopo.OLT),
                new CredencialAcesso(true, "OLTs Padrão Fábrica (G08/G16/8820)", "admin", "admin", Escopo.OLT)
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
            return List.of(
                    new CredencialAcesso(true, "OLT FiberHome/AN padrão", "GEPON", "GEPON", Escopo.OLT),
                    fabrica,
                    intelbras);
        }
        return List.of(intelbras, fabrica);
    }

    public boolean ehUtilizavel() {
        return ativa && !usuario.isBlank();
    }

    public boolean aplicavelPara(FamiliaProduto familia) {
        if (!ehUtilizavel()) {
            return false;
        }
        return switch (escopo) {
            case TODOS -> true;
            case OLT -> familia == FamiliaProduto.OLT;
            case SWITCH_E_AP -> familia == null || familia != FamiliaProduto.OLT;
        };
    }

    public boolean mesmaCredencialQue(String outroUsuario, String outraSenha) {
        String u = outroUsuario != null ? outroUsuario.trim() : "";
        String s = outraSenha != null ? outraSenha : "";
        return Objects.equals(usuario, u) && Objects.equals(senha, s);
    }
}

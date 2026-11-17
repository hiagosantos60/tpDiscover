package tpDiscover.intelbras.coleta;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.coleta.CredencialAcesso.Escopo;
import tpDiscover.intelbras.connection.CredencialLogin;
import tpDiscover.intelbras.descoberta.DiscoveredProduct.FamiliaProduto;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CadeiaCredenciaisTest {

    private static List<String> pares(List<CredencialLogin> cadeia) {
        return cadeia.stream().map(c -> c.usuario() + "/" + c.senha()).toList();
    }

    @Test
    void switchDeveTentarPadroesIntelbrasEFabricaSemRepetirPrincipal() {
        SwitchTarget alvo = new SwitchTarget("10.1.40.203", "admin", "Admin@1234");
        List<CredencialLogin> cadeia = ColetorEmLote.montarCadeiaAlternativas(
                alvo, FamiliaProduto.SWITCH_SG_MR_L2PLUS, CredencialAcesso.presetsPadrao());
        assertEquals(List.of("admin/admin"), pares(cadeia));
    }

    @Test
    void oltDeveIncluirGeponEPadroes() {
        SwitchTarget alvo = new SwitchTarget("10.1.40.140", "admin", "Admin@1234");
        List<CredencialLogin> cadeia = ColetorEmLote.montarCadeiaAlternativas(
                alvo, FamiliaProduto.OLT, CredencialAcesso.presetsPadrao());
        assertEquals(List.of("GEPON/GEPON", "admin/admin"), pares(cadeia));
    }

    @Test
    void extrasDoUsuarioVemAntesDosPadroesDeFabrica() {
        SwitchTarget alvo = new SwitchTarget("10.1.40.10", "suporte", "S3nh@");
        List<CredencialLogin> cadeia = ColetorEmLote.montarCadeiaAlternativas(alvo, null, List.of(
                new CredencialAcesso(true, "Cliente", "operador", "Op@2026", Escopo.SWITCH_E_AP),
                new CredencialAcesso(true, "OLT só", "GEPON", "GEPON", Escopo.OLT)));
        assertEquals(List.of("operador/Op@2026", "admin/Admin@1234", "admin/admin"), pares(cadeia));
    }
}

package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.Map;
import java.util.Set;

/**
 * Imprime, para cada switch já registrado no TopologyEngine, a análise porta a
 * porta: quais switches vizinhos foram identificados e a inferência de qual é
 * o vizinho físico direto.
 */
public class RelatorioTopologia {

    public static void imprimir(TopologyEngine engine) {
        for (SwitchIdentity origem : engine.switchesRegistrados()) {
            imprimirSwitch(engine, origem);
        }
    }

    private static void imprimirSwitch(TopologyEngine engine, SwitchIdentity origem) {
        System.out.println();
        System.out.println("=== " + origem + " ===");

        Map<String, TopologyEngine.PortaAnalise> portas = engine.analisarPortas(origem.macNormalizado());

        if (portas.isEmpty()) {
            System.out.println("  (nenhuma entrada de tabela MAC coletada pra esse switch)");
            return;
        }

        for (var entradaPorta : portas.entrySet()) {
            String porta = entradaPorta.getKey();
            TopologyEngine.PortaAnalise analise = entradaPorta.getValue();

            if (analise.ehPortaDeAcesso()) {
                System.out.printf("  %-14s ACCESS  -> %d host(s) desconhecido(s)%n", porta, analise.hostsDesconhecidos());
                continue;
            }

            Set<SwitchIdentity> candidatos = analise.switchesVisiveis();
            System.out.printf("  %-14s ve %d switch(es) conhecido(s): %s%n",
                    porta, candidatos.size(), nomes(candidatos));

            TopologyEngine.ResultadoInferencia r = engine.inferirVizinhoDireto(candidatos);
            switch (r.tipo()) {
                case DIRETO ->
                        System.out.println("                 -> VIZINHO DIRETO: " + r.candidatos().get(0));
                case DIRETO_PARCIAL ->
                        System.out.println("                 -> VIZINHO DIRETO (parcial, nao confirmou "
                                + nomes(r.naoExplicados()) + " na tabela dele): " + r.candidatos().get(0));
                case AMBIGUO ->
                        System.out.println("                 -> AMBIGUO entre: " + nomes(Set.copyOf(r.candidatos())));
                case INDETERMINADO ->
                        System.out.println("                 -> INDETERMINADO (falta coletar os candidatos)");
                case SEM_CANDIDATOS -> {
                    // não deveria acontecer aqui, pois já tratamos ehPortaDeAcesso() acima
                }
            }
        }
    }

    private static String nomes(Set<SwitchIdentity> s) {
        return s.stream().map(SwitchIdentity::deviceName).sorted().toList().toString();
    }
}

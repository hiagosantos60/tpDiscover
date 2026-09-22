package tpDiscover.intelbras;

import tpDiscover.intelbras.coleta.AlvosLoader;
import tpDiscover.intelbras.coleta.ColetorEmLote;
import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.correlacao.RelatorioTopologia;
import tpDiscover.intelbras.correlacao.TopologyEngine;

import java.nio.file.Path;
import java.util.List;

public class App {

    public static void main(String[] args) {
        // Caminho do arquivo de alvos: passa como argumento, ou usa "switches.txt" na pasta atual.
        Path arquivoAlvos = Path.of(args.length > 0 ? args[0] : "switches.txt");

        List<SwitchTarget> alvos;
        try {
            alvos = AlvosLoader.carregarDeArquivo(arquivoAlvos);
        } catch (Exception e) {
            System.err.println("[ERRO] Não foi possível carregar a lista de switches: " + e.getMessage());
            return;
        }

        if (alvos.isEmpty()) {
            System.err.println("[ERRO] Nenhum switch válido encontrado em " + arquivoAlvos.toAbsolutePath());
            return;
        }

        System.out.println(alvos.size() + " switch(es) para coletar.");

        TopologyEngine engine = new TopologyEngine();
        ColetorEmLote coletor = new ColetorEmLote();
        ColetorEmLote.ResultadoColeta resultado = coletor.coletarTudo(alvos, engine);

        System.out.println();
        System.out.println("============================================");
        System.out.printf("Resumo da coleta: %d OK, %d falha(s), de %d total%n",
                resultado.sucesso().size(), resultado.falhas().size(), resultado.total());

        if (!resultado.falhas().isEmpty()) {
            System.out.println();
            System.out.println("Switches que falharam:");
            for (var falha : resultado.falhas()) {
                System.out.println("  " + falha.alvo().ip() + " -> " + falha.tipo() + ": " + falha.mensagem());
            }
        }

        if (resultado.sucesso().isEmpty()) {
            System.out.println("\nNenhum switch coletado com sucesso — nada para correlacionar.");
            return;
        }

        System.out.println();
        System.out.println("============================================");
        System.out.println("Topologia inferida:");
        System.out.println("============================================");
        RelatorioTopologia.imprimir(engine);
    }
}

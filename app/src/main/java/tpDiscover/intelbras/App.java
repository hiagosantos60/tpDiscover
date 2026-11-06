package tpDiscover.intelbras;

import tpDiscover.intelbras.coleta.AlvosLoader;
import tpDiscover.intelbras.coleta.ColetorEmLote;
import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.correlacao.RelatorioTopologia;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class App {

    public static void main(String[] args) {
        // Caminho do arquivo de alvos: passa como argumento, ou usa "switches.txt" na pasta atual (ou raiz do projeto).
        String caminhoArg = args.length > 0 ? args[0] : "switches.txt";
        Path arquivoAlvos = Path.of(caminhoArg);
        if (!Files.exists(arquivoAlvos) && Files.exists(Path.of("..").resolve(caminhoArg))) {
            arquivoAlvos = Path.of("..").resolve(caminhoArg).normalize();
        }

        AlvosLoader.ConfiguracaoAlvos configAlvos;
        try {
            configAlvos = AlvosLoader.carregarConfiguracao(arquivoAlvos);
        } catch (Exception e) {
            System.err.println("[ERRO] Não foi possível carregar a lista de switches: " + e.getMessage());
            return;
        }

        List<SwitchTarget> alvos = configAlvos.alvos();
        if (alvos.isEmpty()) {
            System.err.println("[ERRO] Nenhum switch válido encontrado em " + arquivoAlvos.toAbsolutePath());
            return;
        }

        if (alvos.stream().anyMatch(a -> !a.possuiSenha())) {
            String senhaEmMemoria = solicitarSenhaInterativa();
            if (senhaEmMemoria == null) {
                System.err.println("[ERRO] Senha não informada para os equipamentos sem senha no arquivo.");
                return;
            }
            alvos = alvos.stream()
                    .map(a -> a.possuiSenha() ? a : a.comSenha(senhaEmMemoria))
                    .toList();
        }

        System.out.println("============================================");
        System.out.println("Alvos informados em " + arquivoAlvos + " (" + alvos.size() + " equipamento(s)):");
        for (SwitchTarget alvo : alvos) {
            System.out.println("  - " + alvo.enderecoExibicao() + " (usuário: " + alvo.usuario() + ")");
        }
        if (configAlvos.possuiGatewayManual()) {
            System.out.println("  * Gateway manual informado: "
                    + (configAlvos.gatewayIpManual() != null ? configAlvos.gatewayIpManual() + " " : "")
                    + (configAlvos.gatewayMacManual() != null ? "[" + configAlvos.gatewayMacManual() + "]" : ""));
        }
        System.out.println("============================================");

        TopologyEngine engine = new TopologyEngine();
        if (configAlvos.possuiGatewayManual()) {
            engine.definirGatewayManual(configAlvos.gatewayIpManual(), configAlvos.gatewayMacManual());
        }

        ColetorEmLote coletor = new ColetorEmLote();
        ColetorEmLote.ResultadoColeta resultado = coletor.coletarTudo(alvos, engine);

        System.out.println();
        System.out.println("============================================");
        System.out.printf("Resumo da coleta: %d OK, %d falha(s), de %d total%n",
                resultado.sucesso().size(), resultado.falhas().size(), resultado.total());

        if (!resultado.sucesso().isEmpty()) {
            System.out.println("Switches coletados:");
            for (SwitchIdentity sw : resultado.sucesso()) {
                System.out.printf("  [OK] %s | %s | %s | %s | %s%n",
                        RelatorioTopologia.nomeExibicaoSwitch(sw),
                        sw.ip() != null ? sw.ip() : "N/D",
                        sw.macAddress() != null ? sw.macAddress() : "N/D",
                        sw.productId() != null ? sw.productId() : "N/D",
                        sw.serialNumber() != null ? sw.serialNumber() : "N/D");
            }
        }

        if (!resultado.falhas().isEmpty()) {
            System.out.println();
            System.out.println("Switches que falharam:");
            for (var falha : resultado.falhas()) {
                System.out.println("  [FALHA] " + falha.alvo().enderecoExibicao() + " -> " + falha.tipo() + ": " + falha.mensagem());
            }
        }

        if (resultado.sucesso().isEmpty()) {
            System.out.println("\nNenhum switch coletado com sucesso - nada para correlacionar.");
            return;
        }

        System.out.println();
        Path pastaSaida = arquivoAlvos.toAbsolutePath().normalize().getParent();
        RelatorioTopologia.imprimir(engine, pastaSaida);
    }

    private static String solicitarSenhaInterativa() {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("Digite a senha para os switches sem senha em switches.txt: ");
            if (chars == null) return null;
            String senha = new String(chars);
            java.util.Arrays.fill(chars, '\0');
            return senha;
        }
        System.out.print("Digite a senha para os switches sem senha em switches.txt: ");
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}

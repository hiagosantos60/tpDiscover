package tpDiscover.intelbras;

import tpDiscover.intelbras.coleta.AlvosLoader;
import tpDiscover.intelbras.coleta.ColetorEmLote;
import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.correlacao.RelatorioTopologia;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.descoberta.DiscoveredProduct;
import tpDiscover.intelbras.descoberta.NetworkScanTarget;
import tpDiscover.intelbras.descoberta.NetworkScanner;
import tpDiscover.intelbras.descoberta.ProductFingerprinter;
import tpDiscover.intelbras.gui.TpDiscoverFrame;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class App {

    public record CliOptions(
            String arquivoAlvos,
            boolean arquivoExplicitamenteInformado,
            List<String> scanSpecs,
            String usuario,
            String senha,
            boolean apenasScan,
            String salvarAlvosEm,
            int concorrencia,
            boolean semExpansaoLldp,
            boolean semSnmp,
            boolean ajuda,
            boolean gui,
            boolean cli
    ) {
        public CliOptions(
                String arquivoAlvos,
                boolean arquivoExplicitamenteInformado,
                List<String> scanSpecs,
                String usuario,
                String senha,
                boolean apenasScan,
                String salvarAlvosEm,
                int concorrencia,
                boolean semExpansaoLldp,
                boolean semSnmp,
                boolean ajuda
        ) {
            this(arquivoAlvos, arquivoExplicitamenteInformado, scanSpecs, usuario, senha,
                    apenasScan, salvarAlvosEm, concorrencia, semExpansaoLldp, semSnmp, ajuda, false, false);
        }
    }

    public static void main(String[] args) {
        CliOptions opts;
        try {
            opts = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[ERRO] " + e.getMessage());
            imprimirAjuda();
            return;
        }

        if (opts.ajuda()) {
            imprimirAjuda();
            return;
        }

        boolean abrirGui = opts.gui() || ((args == null || args.length == 0) && !opts.cli() && !GraphicsEnvironment.isHeadless());
        if (abrirGui) {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("[ERRO] Ambiente sem suporte gráfico (headless). Utilize as opções de terminal (--cli / --scan).");
                return;
            }
            SwingUtilities.invokeLater(TpDiscoverFrame::exibir);
            return;
        }

        Path arquivoAlvos = resolverCaminhoArquivo(opts.arquivoAlvos());
        boolean arquivoExiste = Files.exists(arquivoAlvos);

        if (opts.arquivoExplicitamenteInformado() && !arquivoExiste) {
            System.err.println("[ERRO] Arquivo de alvos não encontrado: " + arquivoAlvos.toAbsolutePath());
            return;
        }

        AlvosLoader.ConfiguracaoAlvos configAlvos = new AlvosLoader.ConfiguracaoAlvos(List.of(), null, null);
        if (arquivoExiste) {
            try {
                configAlvos = AlvosLoader.carregarConfiguracao(arquivoAlvos);
            } catch (Exception e) {
                System.err.println("[ERRO] Não foi possível carregar a lista de switches: " + e.getMessage());
                return;
            }
        } else if (opts.scanSpecs().isEmpty()) {
            System.err.println("[ERRO] Arquivo " + arquivoAlvos.toAbsolutePath()
                    + " não encontrado e nenhum --scan informado.");
            imprimirAjuda();
            return;
        }

        // Combina especificações de scan da linha de comando com diretivas # scan=... do arquivo
        List<String> specsEfetivas = new ArrayList<>();
        Set<String> specsUnicas = new LinkedHashSet<>();
        for (String s : opts.scanSpecs()) {
            if (specsUnicas.add(s)) specsEfetivas.add(s);
        }
        if (specsEfetivas.isEmpty()) {
            for (String s : configAlvos.scanSpecs()) {
                if (specsUnicas.add(s)) specsEfetivas.add(s);
            }
        }

        List<SwitchTarget> alvosManuais = configAlvos.alvos();
        String usuarioPadrao = primeiroNaoVazio(opts.usuario(), configAlvos.usuarioPadrao(), extrairPrimeiroUsuario(alvosManuais));
        String senhaPadrao = primeiroNaoVazio(opts.senha(), configAlvos.senhaPadrao(), extrairPrimeiraSenha(alvosManuais));

        List<DiscoveredProduct> produtosDescobertos = List.of();
        if (!specsEfetivas.isEmpty()) {
            List<NetworkScanTarget> blocosScan;
            try {
                blocosScan = NetworkScanTarget.interpretarEspecificacoes(String.join(",", specsEfetivas));
            } catch (IllegalArgumentException e) {
                System.err.println("[ERRO] Especificação de scan inválida: " + e.getMessage());
                return;
            }
            List<String> ipsParaVarrer = NetworkScanTarget.consolidarIps(blocosScan);
            System.out.println("============================================");
            System.out.println("Iniciando scan de rede em " + ipsParaVarrer.size() + " IP(s): " + specsEfetivas);

            ProductFingerprinter.ProbeConfig probeCfg = new ProductFingerprinter.ProbeConfig(
                    500, 900, !opts.semSnmp(), List.of("public")
            );
            NetworkScanner scanner = new NetworkScanner(probeCfg, Math.max(16, opts.concorrencia() * 2));
            NetworkScanner.ScanResult scanRes = scanner.escanear(ipsParaVarrer);
            produtosDescobertos = scanRes.produtosInteresse();

            System.out.printf(Locale.US,
                    "Scan concluído em %.2fs: %d produto(s) Intelbras/infra encontrado(s), %d outro(s) host(s) ignorado(s).%n",
                    scanRes.duracaoMillis() / 1000.0,
                    produtosDescobertos.size(),
                    scanRes.hostsIgnorados().size());
            for (DiscoveredProduct p : produtosDescobertos) {
                System.out.println("  [+] " + p.resumoCurto());
            }
            System.out.println("============================================");

            if (opts.apenasScan()) {
                if (opts.salvarAlvosEm() != null) {
                    salvarAlvosDescobertos(opts.salvarAlvosEm(), NetworkScanner.combinarComAlvosManuais(
                            produtosDescobertos, alvosManuais,
                            usuarioPadrao != null ? usuarioPadrao : "admin",
                            senhaPadrao != null ? senhaPadrao : ""));
                }
                return;
            }

            if (usuarioPadrao == null && produtosDescobertos.stream().anyMatch(DiscoveredProduct::suportaColetaCli)) {
                usuarioPadrao = solicitarTextoInterativo("Digite o usuário padrão para os equipamentos descobertos [admin]: ");
                if (usuarioPadrao == null || usuarioPadrao.isBlank()) {
                    usuarioPadrao = "admin";
                }
            }
            if (senhaPadrao == null && produtosDescobertos.stream().anyMatch(DiscoveredProduct::suportaColetaCli)) {
                senhaPadrao = solicitarSenhaInterativa("Digite a senha padrão para os equipamentos descobertos: ");
            }
        }

        List<SwitchTarget> alvos = NetworkScanner.combinarComAlvosManuais(
                produtosDescobertos, alvosManuais, usuarioPadrao, senhaPadrao);

        if (alvos.isEmpty()) {
            System.err.println("[ERRO] Nenhum switch/AP/OLT elegível para coleta encontrado.");
            return;
        }

        if (alvos.stream().anyMatch(a -> !a.possuiSenha())) {
            String senhaEmMemoria = (senhaPadrao != null)
                    ? senhaPadrao
                    : solicitarSenhaInterativa("Digite a senha para os switches sem senha: ");
            if (senhaEmMemoria == null) {
                System.err.println("[ERRO] Senha não informada para os equipamentos sem senha.");
                return;
            }
            senhaPadrao = senhaEmMemoria;
            alvos = alvos.stream()
                    .map(a -> a.possuiSenha() ? a : a.comSenha(senhaEmMemoria))
                    .toList();
        }

        if (opts.salvarAlvosEm() != null) {
            salvarAlvosDescobertos(opts.salvarAlvosEm(), alvos);
        }

        System.out.println("============================================");
        String origemDesc = arquivoExiste ? arquivoAlvos.toString() : "scan de rede";
        System.out.println("Alvos consolidados (" + origemDesc + " -> " + alvos.size()
                + " equipamento(s), concorrência=" + opts.concorrencia() + "):");
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
        for (DiscoveredProduct p : produtosDescobertos) {
            if (!p.macConhecido().isBlank()) {
                engine.registrarMacExternoParaIp(p.ip(), p.macConhecido());
            }
        }

        long inicioColeta = System.currentTimeMillis();
        ColetorEmLote coletor = new ColetorEmLote();
        ColetorEmLote.ResultadoColeta resultado = coletor.coletarTudo(
                alvos,
                engine,
                opts.concorrencia(),
                !opts.semExpansaoLldp(),
                usuarioPadrao,
                senhaPadrao
        );
        long duracaoColetaMs = System.currentTimeMillis() - inicioColeta;

        System.out.println();
        System.out.println("============================================");
        System.out.printf(Locale.US,
                "Resumo da coleta em %.2fs: %d OK, %d falha(s), de %d total%n",
                duracaoColetaMs / 1000.0,
                resultado.sucesso().size(),
                resultado.falhas().size(),
                resultado.total());

        if (!resultado.sucesso().isEmpty()) {
            System.out.println("Equipamentos coletados:");
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
            System.out.println("Equipamentos que falharam:");
            for (var falha : resultado.falhas()) {
                System.out.println("  [FALHA] " + falha.alvo().enderecoExibicao() + " -> " + falha.tipo() + ": " + falha.mensagem());
            }
        }

        if (resultado.sucesso().isEmpty()) {
            System.out.println("\nNenhum switch coletado com sucesso - nada para correlacionar.");
            return;
        }

        System.out.println();
        Path pastaSaida = arquivoExiste
                ? arquivoAlvos.toAbsolutePath().normalize().getParent()
                : Path.of("").toAbsolutePath().normalize();
        RelatorioTopologia.imprimir(engine, pastaSaida);
    }

    public static CliOptions parseArgs(String[] args) {
        String arquivoAlvos = "switches.txt";
        boolean arquivoExplicitamenteInformado = false;
        List<String> scanSpecs = new ArrayList<>();
        String usuario = null;
        String senha = null;
        boolean apenasScan = false;
        String salvarAlvosEm = null;
        int concorrencia = ColetorEmLote.CONCORRENCIA_PADRAO;
        boolean semExpansaoLldp = false;
        boolean semSnmp = false;
        boolean ajuda = false;
        boolean gui = false;
        boolean cli = false;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h", "--help" -> ajuda = true;
                    case "--gui" -> gui = true;
                    case "--cli" -> cli = true;
                    case "--scan" -> scanSpecs.add(exigirValor(args, ++i, "--scan"));
                    case "-u", "--user" -> usuario = exigirValor(args, ++i, arg);
                    case "-p", "--pass" -> senha = exigirValor(args, ++i, arg);
                    case "--apenas-scan" -> apenasScan = true;
                    case "--salvar-alvos" -> salvarAlvosEm = exigirValor(args, ++i, "--salvar-alvos");
                    case "--concorrencia" -> {
                        String val = exigirValor(args, ++i, "--concorrencia");
                        try {
                            concorrencia = Integer.parseInt(val);
                            if (concorrencia < 1) throw new NumberFormatException();
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Valor inválido para --concorrencia: " + val);
                        }
                    }
                    case "--sem-expansao-lldp" -> semExpansaoLldp = true;
                    case "--sem-snmp" -> semSnmp = true;
                    default -> {
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Opção desconhecida: " + arg);
                        }
                        arquivoAlvos = arg;
                        arquivoExplicitamenteInformado = true;
                    }
                }
            }
        }

        return new CliOptions(
                arquivoAlvos,
                arquivoExplicitamenteInformado,
                List.copyOf(scanSpecs),
                usuario,
                senha,
                apenasScan,
                salvarAlvosEm,
                concorrencia,
                semExpansaoLldp,
                semSnmp,
                ajuda,
                gui,
                cli
        );
    }

    private static String exigirValor(String[] args, int idx, String flag) {
        if (idx >= args.length || args[idx] == null || args[idx].isBlank()) {
            throw new IllegalArgumentException("A opção " + flag + " exige um argumento.");
        }
        return args[idx].trim();
    }

    private static Path resolverCaminhoArquivo(String caminhoArg) {
        Path arquivo = Path.of(caminhoArg);
        if (!Files.exists(arquivo) && Files.exists(Path.of("..").resolve(caminhoArg))) {
            arquivo = Path.of("..").resolve(caminhoArg).normalize();
        }
        return arquivo;
    }

    private static void salvarAlvosDescobertos(String caminho, List<SwitchTarget> alvos) {
        try {
            Path out = Path.of(caminho);
            AlvosLoader.salvarAlvos(out, alvos, true);
            System.out.println("[Scan] Lista de alvos salva em: " + out.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[AVISO] Falha ao salvar alvos em " + caminho + ": " + e.getMessage());
        }
    }

    private static String primeiroNaoVazio(String... candidatos) {
        for (String c : candidatos) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    private static String extrairPrimeiroUsuario(List<SwitchTarget> alvos) {
        return (alvos != null && !alvos.isEmpty()) ? alvos.get(0).usuario() : null;
    }

    private static String extrairPrimeiraSenha(List<SwitchTarget> alvos) {
        if (alvos == null) return null;
        for (SwitchTarget t : alvos) {
            if (t.possuiSenha()) return t.senha();
        }
        return null;
    }

    private static void imprimirAjuda() {
        System.out.println("""
                Uso: tpDiscover [arquivo_alvos] [opções]
                  --gui                      Abre a interface gráfica Desktop (também padrão sem argumentos)
                  --cli                      Força execução em modo terminal usando switches.txt padrão
                  --scan <CIDR|faixa|auto>   Varre a rede por produtos Intelbras (ex: 10.1.40.0/24 ou auto)
                  -u, --user <usuario>       Usuário padrão para login nos equipamentos descobertos
                  -p, --pass <senha>         Senha padrão para login/enable nos equipamentos descobertos
                  --apenas-scan              Somente identifica produtos Intelbras na rede sem coletar CLI
                  --salvar-alvos <arquivo>   Salva a lista consolidada de alvos no formato ip;usuario;senha
                  --concorrencia <N>         Número de coletas CLI simultâneas (padrão: 16)
                  --sem-expansao-lldp        Desativa descoberta automática de switches esquecidos via LLDP
                  --sem-snmp                 Desativa sonda SNMP UDP/161 durante o scan
                  -h, --help                 Exibe esta ajuda
                """);
    }

    private static String solicitarTextoInterativo(String prompt) {
        Console console = System.console();
        if (console != null) {
            return console.readLine(prompt);
        }
        System.out.print(prompt);
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String solicitarSenhaInterativa(String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword(prompt);
            if (chars == null) return null;
            String senha = new String(chars);
            java.util.Arrays.fill(chars, '\0');
            return senha;
        }
        System.out.print(prompt);
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}

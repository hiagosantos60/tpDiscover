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
import tpDiscover.intelbras.seguranca.ControleAcesso;

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

import java.nio.file.Paths;
import java.util.stream.Collectors;
import tpDiscover.intelbras.util.Compat;
public class App {

    /** Versão exibida em Avançado > Sobre. */
    public static final String VERSAO = "2.0";

    public static final class CliOptions {
        private final String arquivoAlvos;
        private final boolean arquivoExplicitamenteInformado;
        private final List<String> scanSpecs;
        private final String usuario;
        private final String senha;
        private final boolean apenasScan;
        private final String salvarAlvosEm;
        private final int concorrencia;
        private final boolean semExpansaoLldp;
        private final boolean semSnmp;
        private final boolean ajuda;
        private final boolean gui;
        private final boolean cli;

        public CliOptions(String arquivoAlvos, boolean arquivoExplicitamenteInformado, List<String> scanSpecs, String usuario, String senha, boolean apenasScan, String salvarAlvosEm, int concorrencia, boolean semExpansaoLldp, boolean semSnmp, boolean ajuda, boolean gui, boolean cli) {
            this.arquivoAlvos = arquivoAlvos;
            this.arquivoExplicitamenteInformado = arquivoExplicitamenteInformado;
            this.scanSpecs = scanSpecs;
            this.usuario = usuario;
            this.senha = senha;
            this.apenasScan = apenasScan;
            this.salvarAlvosEm = salvarAlvosEm;
            this.concorrencia = concorrencia;
            this.semExpansaoLldp = semExpansaoLldp;
            this.semSnmp = semSnmp;
            this.ajuda = ajuda;
            this.gui = gui;
            this.cli = cli;
        }

        public String arquivoAlvos() {
            return arquivoAlvos;
        }

        public boolean arquivoExplicitamenteInformado() {
            return arquivoExplicitamenteInformado;
        }

        public List<String> scanSpecs() {
            return scanSpecs;
        }

        public String usuario() {
            return usuario;
        }

        public String senha() {
            return senha;
        }

        public boolean apenasScan() {
            return apenasScan;
        }

        public String salvarAlvosEm() {
            return salvarAlvosEm;
        }

        public int concorrencia() {
            return concorrencia;
        }

        public boolean semExpansaoLldp() {
            return semExpansaoLldp;
        }

        public boolean semSnmp() {
            return semSnmp;
        }

        public boolean ajuda() {
            return ajuda;
        }

        public boolean gui() {
            return gui;
        }

        public boolean cli() {
            return cli;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CliOptions)) return false;
            CliOptions o = (CliOptions) obj;
            return java.util.Objects.equals(arquivoAlvos, o.arquivoAlvos)
                    && arquivoExplicitamenteInformado == o.arquivoExplicitamenteInformado
                    && java.util.Objects.equals(scanSpecs, o.scanSpecs)
                    && java.util.Objects.equals(usuario, o.usuario)
                    && java.util.Objects.equals(senha, o.senha)
                    && apenasScan == o.apenasScan
                    && java.util.Objects.equals(salvarAlvosEm, o.salvarAlvosEm)
                    && concorrencia == o.concorrencia
                    && semExpansaoLldp == o.semExpansaoLldp
                    && semSnmp == o.semSnmp
                    && ajuda == o.ajuda
                    && gui == o.gui
                    && cli == o.cli;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(arquivoAlvos, arquivoExplicitamenteInformado, scanSpecs, usuario, senha, apenasScan, salvarAlvosEm, concorrencia, semExpansaoLldp, semSnmp, ajuda, gui, cli);
        }

        @Override
        public String toString() {
            return "CliOptions[arquivoAlvos=" + arquivoAlvos + ", arquivoExplicitamenteInformado=" + arquivoExplicitamenteInformado + ", scanSpecs=" + scanSpecs + ", usuario=" + usuario + ", senha=" + senha + ", apenasScan=" + apenasScan + ", salvarAlvosEm=" + salvarAlvosEm + ", concorrencia=" + concorrencia + ", semExpansaoLldp=" + semExpansaoLldp + ", semSnmp=" + semSnmp + ", ajuda=" + ajuda + ", gui=" + gui + ", cli=" + cli + "]";
        }

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

        if (!autenticarNoTerminal()) {
            System.err.println("[ERRO] Acesso negado.");
            System.exit(1);
            return;
        }

        Path arquivoAlvos = resolverCaminhoArquivo(opts.arquivoAlvos());
        boolean arquivoExiste = Files.exists(arquivoAlvos);

        if (opts.arquivoExplicitamenteInformado() && !arquivoExiste) {
            System.err.println("[ERRO] Arquivo de alvos não encontrado: " + arquivoAlvos.toAbsolutePath());
            return;
        }

        AlvosLoader.ConfiguracaoAlvos configAlvos = new AlvosLoader.ConfiguracaoAlvos(Compat.listOf(), null, null);
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

        List<DiscoveredProduct> produtosDescobertos = Compat.listOf();
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
                    500, 900, !opts.semSnmp(), Compat.listOf("public")
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
                if (usuarioPadrao == null || Compat.isBlank(usuarioPadrao)) {
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
                    .collect(Collectors.toList());
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
            if (!Compat.isBlank(p.macConhecido())) {
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
            for (ColetorEmLote.FalhaColeta falha : resultado.falhas()) {
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
                : Paths.get("").toAbsolutePath().normalize();
        RelatorioTopologia.imprimir(engine, pastaSaida);
    }

    /** Pede a senha de acesso no terminal (sem eco quando há console). */
    private static boolean autenticarNoTerminal() {
        Console console = System.console();
        BufferedReader entrada = console == null
                ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                : null;
        for (int tentativa = 1; tentativa <= ControleAcesso.MAX_TENTATIVAS; tentativa++) {
            char[] senha;
            if (console != null) {
                senha = console.readPassword("Senha de acesso ao tpDiscover: ");
            } else {
                System.out.print("Senha de acesso ao tpDiscover: ");
                System.out.flush();
                try {
                    String linha = entrada.readLine();
                    senha = linha == null ? null : linha.toCharArray();
                } catch (IOException e) {
                    senha = null;
                }
            }
            if (senha == null) {
                return false;
            }
            if (ControleAcesso.senhaCorreta(senha)) {
                return true;
            }
            int restantes = ControleAcesso.MAX_TENTATIVAS - tentativa;
            System.err.println("[ERRO] Senha incorreta." + (restantes > 0 ? " Tentativas restantes: " + restantes : ""));
            if (restantes > 0) {
                ControleAcesso.aguardarAposErro(tentativa);
            }
        }
        return false;
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
                    case "-h": case "--help":
                        ajuda = true;
                        break;
                    case "--gui":
                        gui = true;
                        break;
                    case "--cli":
                        cli = true;
                        break;
                    case "--scan":
                        scanSpecs.add(exigirValor(args, ++i, "--scan"));
                        break;
                    case "-u": case "--user":
                        usuario = exigirValor(args, ++i, arg);
                        break;
                    case "-p": case "--pass":
                        senha = exigirValor(args, ++i, arg);
                        break;
                    case "--apenas-scan":
                        apenasScan = true;
                        break;
                    case "--salvar-alvos":
                        salvarAlvosEm = exigirValor(args, ++i, "--salvar-alvos");
                        break;
                    case "--concorrencia": {
                        String val = exigirValor(args, ++i, "--concorrencia");
                        try {
                            concorrencia = Integer.parseInt(val);
                            if (concorrencia < 1) throw new NumberFormatException();
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Valor inválido para --concorrencia: " + val);
                        }
                        break;
                    }
                    case "--sem-expansao-lldp":
                        semExpansaoLldp = true;
                        break;
                    case "--sem-snmp":
                        semSnmp = true;
                        break;
                    default: {
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Opção desconhecida: " + arg);
                        }
                        arquivoAlvos = arg;
                        arquivoExplicitamenteInformado = true;
                        break;
                    }
                }
            }
        }

        return new CliOptions(
                arquivoAlvos,
                arquivoExplicitamenteInformado,
                Compat.listCopyOf(scanSpecs),
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
        if (idx >= args.length || args[idx] == null || Compat.isBlank(args[idx])) {
            throw new IllegalArgumentException("A opção " + flag + " exige um argumento.");
        }
        return args[idx].trim();
    }

    private static Path resolverCaminhoArquivo(String caminhoArg) {
        Path arquivo = Paths.get(caminhoArg);
        if (!Files.exists(arquivo) && Files.exists(Paths.get("..").resolve(caminhoArg))) {
            arquivo = Paths.get("..").resolve(caminhoArg).normalize();
        }
        return arquivo;
    }

    private static void salvarAlvosDescobertos(String caminho, List<SwitchTarget> alvos) {
        try {
            Path out = Paths.get(caminho);
            AlvosLoader.salvarAlvos(out, alvos, true);
            System.out.println("[Scan] Lista de alvos salva em: " + out.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[AVISO] Falha ao salvar alvos em " + caminho + ": " + e.getMessage());
        }
    }

    private static String primeiroNaoVazio(String... candidatos) {
        for (String c : candidatos) {
            if (c != null && !Compat.isBlank(c)) {
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
        System.out.println("Uso: tpDiscover [arquivo_alvos] [opções]\n" +
                "  --gui                      Abre a interface gráfica Desktop (também padrão sem argumentos)\n" +
                "  --cli                      Força execução em modo terminal usando switches.txt padrão\n" +
                "  --scan <CIDR|faixa|auto>   Varre a rede por produtos Intelbras (ex: 10.1.40.0/24 ou auto)\n" +
                "  -u, --user <usuario>       Usuário padrão para login nos equipamentos descobertos\n" +
                "  -p, --pass <senha>         Senha padrão para login/enable nos equipamentos descobertos\n" +
                "  --apenas-scan              Somente identifica produtos Intelbras na rede sem coletar CLI\n" +
                "  --salvar-alvos <arquivo>   Salva a lista consolidada de alvos no formato ip;usuario;senha\n" +
                "  --concorrencia <N>         Número de coletas CLI simultâneas (padrão: 16)\n" +
                "  --sem-expansao-lldp        Desativa descoberta automática de switches esquecidos via LLDP\n" +
                "  --sem-snmp                 Desativa sonda SNMP UDP/161 durante o scan\n" +
                "  -h, --help                 Exibe esta ajuda\n");
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

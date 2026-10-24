package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.modelos.OuiLookup;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Formata e apresenta a saída da topologia descoberta:
 *  - Formato compacto de cada switch (nome | IP | MAC | modelo | serial)
 *  - Tabela alinhada de portas (Porta | Tipo | Conectado a | Qtd MACs) com ordenação natural
 *  - Árvore ASCII da topologia (switch raiz -> vizinhos)
 *  - Exportação em arquivo TXT (topologia_AAAA-MM-DD_HHmm.txt) sem texto técnico desnecessário
 */
public class RelatorioTopologia {

    private static final DateTimeFormatter ARQUIVO_TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

    // Constantes visuais para árvore e listas indentadas (Unicode seguro em qualquer plataforma)
    private static final String RAMO_MEIO = "\u251C\u2500 "; // ├─ 
    private static final String RAMO_FIM  = "\u2514\u2500 "; // └─ 
    private static final String TRACEJADO = " \u2500 ";     //  ─ 
    private static final String GUIA_COLUNA = "\u2502  ";    // │  

    public static void imprimir(TopologyEngine engine) {
        String relatorio = gerarRelatorioTexto(engine);
        System.out.println(relatorio);

        String nomeArquivo = "topologia_" + LocalDateTime.now().format(ARQUIVO_TIMESTAMP_FMT) + ".txt";
        try {
            Files.writeString(Path.of(nomeArquivo), relatorio, StandardCharsets.UTF_8);
            System.out.println("\n[ARQUIVO] Relatório salvo com sucesso em: " + nomeArquivo);
        } catch (IOException e) {
            System.err.println("[AVISO] Não foi possível salvar o arquivo " + nomeArquivo + ": " + e.getMessage());
        }
    }

    public static String gerarRelatorioTexto(TopologyEngine engine) {
        StringBuilder sb = new StringBuilder();

        for (SwitchIdentity origem : engine.switchesRegistrados()) {
            formatarSwitch(engine, origem, sb);
        }

        formatarArvoreTopologia(engine, sb);

        return sb.toString();
    }

    private static void formatarSwitch(TopologyEngine engine, SwitchIdentity origem, StringBuilder sb) {
        sb.append("\n");
        sb.append("====================================================================================================\n");
        sb.append(String.format("%s | %s | %s | %s | %s%n",
                origem.deviceName() != null ? origem.deviceName() : "N/D",
                origem.ip() != null ? origem.ip() : "N/D",
                origem.macAddress() != null ? origem.macAddress() : "N/D",
                origem.productId() != null ? origem.productId() : "N/D",
                origem.serialNumber() != null ? origem.serialNumber() : "N/D"
        ));
        sb.append("====================================================================================================\n");
        Map<String, TopologyEngine.PortaAnalise> portas = engine.analisarPortas(origem.macNormalizado());
        int largPorta = Math.max(16, portas.keySet().stream().mapToInt(String::length).max().orElse(16));
        String fmtCabecalho = "%-" + largPorta + "s | %-14s | %-54s | %-8s%n";
        String fmtLinha     = "%-" + largPorta + "s | %-14s | %-54s | %-8d%n";
        String recuoSubitem = " ".repeat(largPorta + 3);

        sb.append(String.format(fmtCabecalho, "Porta", "Tipo", "Conectado a", "Qtd MACs"));
        sb.append("-".repeat(largPorta + 1)).append("+----------------+--------------------------------------------------------+---------\n");

        if (portas.isEmpty()) {
            sb.append("  (nenhuma entrada de tabela MAC coletada para este switch)\n");
            return;
        }

        // Ordenação natural das portas (ex: GE1/0/1, GE1/0/2 ... GE1/0/10 ... GE1/0/24, BAGG1)
        List<String> portasOrdenadas = new ArrayList<>(portas.keySet());
        portasOrdenadas.sort(RelatorioTopologia::compararPortas);

        for (String porta : portasOrdenadas) {
            TopologyEngine.PortaAnalise analise = portas.get(porta);
            String tipo = classificarTipoPorta(porta, analise);

            if (analise.ehPortaDeAcesso()) {
                List<String> hosts = analise.macsHostsDesconhecidos();
                if (hosts.isEmpty()) {
                    sb.append(String.format(fmtLinha,
                            porta, tipo, "(sem host registrado)", analise.totalMacs()));
                } else if (hosts.size() == 1) {
                    String macComOUI = OuiLookup.formatar(hosts.get(0));
                    sb.append(String.format(fmtLinha,
                            porta, tipo, macComOUI, analise.totalMacs()));
                } else {
                    sb.append(String.format(fmtLinha,
                            porta, tipo, hosts.size() + " hosts", analise.totalMacs()));
                    for (int i = 0; i < hosts.size(); i++) {
                        String prefixoItem = (i == hosts.size() - 1)
                                ? recuoSubitem + RAMO_FIM
                                : recuoSubitem + RAMO_MEIO;
                        String macComOUI = OuiLookup.formatar(hosts.get(i));
                        sb.append(prefixoItem).append(macComOUI).append("\n");
                    }
                }
            } else {
                Set<SwitchIdentity> candidatos = analise.switchesVisiveis();
                TopologyEngine.ResultadoInferencia r = engine.inferirVizinhoDireto(origem.macNormalizado(), candidatos);

                String conectadoTexto;
                List<SwitchIdentity> switchesAtras = new ArrayList<>();

                switch (r.tipo()) {
                    case DIRETO, DIRETO_PARCIAL -> {
                        SwitchIdentity direto = r.candidatos().get(0);
                        String portaRemota = engine.portaRemota(origem.macNormalizado(), direto.macNormalizado())
                                .map(p -> "porta " + p)
                                .orElse("porta ?");
                        conectadoTexto = "-> " + direto.deviceName() + " (" + direto.ip() + ") " + portaRemota;
                        for (SwitchIdentity c : candidatos) {
                            if (!c.macNormalizado().equals(direto.macNormalizado())) {
                                switchesAtras.add(c);
                            }
                        }
                    }
                    case AMBIGUO -> {
                        conectadoTexto = "-> [AMBÍGUO] " + nomes(r.candidatos());
                    }
                    default -> {
                        conectadoTexto = "-> [INDETERMINADO] " + nomes(candidatos);
                    }
                }

                sb.append(String.format(fmtLinha,
                        porta, tipo, conectadoTexto, analise.totalMacs()));

                for (int i = 0; i < switchesAtras.size(); i++) {
                    String prefixoItem = (i == switchesAtras.size() - 1)
                            ? recuoSubitem + RAMO_FIM
                            : recuoSubitem + RAMO_MEIO;
                    SwitchIdentity extra = switchesAtras.get(i);
                    sb.append(prefixoItem)
                            .append("Switch atrás: ")
                            .append(extra.deviceName())
                            .append(" (").append(extra.ip()).append(")\n");
                }
            }
        }
    }

    private static String classificarTipoPorta(String porta, TopologyEngine.PortaAnalise analise) {
        String portaUpper = porta.toUpperCase();
        if (portaUpper.startsWith("BAGG") || portaUpper.startsWith("BRIDGE-AGGREGATION") || portaUpper.startsWith("AGG")) {
            return "AGG";
        }
        if (!analise.switchesVisiveis().isEmpty()) {
            return "UPLINK/TRUNK";
        }
        return "ACCESS";
    }

    private static void formatarArvoreTopologia(TopologyEngine engine, StringBuilder sb) {
        sb.append("\n====================================================================================================\n");
        sb.append("ÁRVORE DA TOPOLOGIA\n");
        sb.append("====================================================================================================\n");

        List<SwitchIdentity> switches = new ArrayList<>(engine.switchesRegistrados());
        if (switches.isEmpty()) {
            sb.append("(nenhum switch registrado para exibição da árvore)\n");
            return;
        }

        SwitchIdentity raizPrincipal = encontrarMelhorRaiz(engine, switches);

        Set<String> visitados = new HashSet<>();
        sb.append(raizPrincipal.deviceName()).append(" (").append(raizPrincipal.ip()).append(")\n");
        visitados.add(raizPrincipal.macNormalizado());
        desenharGalho(engine, raizPrincipal, "", visitados, sb);

        for (SwitchIdentity sw : switches) {
            if (!visitados.contains(sw.macNormalizado())) {
                sb.append("\n").append(sw.deviceName()).append(" (").append(sw.ip()).append(")\n");
                visitados.add(sw.macNormalizado());
                desenharGalho(engine, sw, "", visitados, sb);
            }
        }
    }

    private static SwitchIdentity encontrarMelhorRaiz(TopologyEngine engine, List<SwitchIdentity> switches) {
        SwitchIdentity melhor = switches.get(0);
        int maxVizinhos = -1;

        for (SwitchIdentity sw : switches) {
            int contagem = contarVizinhos(engine, sw);
            if (contagem > maxVizinhos) {
                maxVizinhos = contagem;
                melhor = sw;
            }
        }
        return melhor;
    }

    private static int contarVizinhos(TopologyEngine engine, SwitchIdentity sw) {
        int count = 0;
        Map<String, TopologyEngine.PortaAnalise> portas = engine.analisarPortas(sw.macNormalizado());
        for (TopologyEngine.PortaAnalise analise : portas.values()) {
            if (!analise.ehPortaDeAcesso()) {
                TopologyEngine.ResultadoInferencia r = engine.inferirVizinhoDireto(sw.macNormalizado(), analise.switchesVisiveis());
                if (r.tipo() == TopologyEngine.ResultadoInferencia.Tipo.DIRETO ||
                    r.tipo() == TopologyEngine.ResultadoInferencia.Tipo.DIRETO_PARCIAL) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void desenharGalho(
            TopologyEngine engine,
            SwitchIdentity pai,
            String prefixo,
            Set<String> visitados,
            StringBuilder sb
    ) {
        record Enlace(String portaLocal, String portaRemota, SwitchIdentity vizinho) {}

        Map<String, TopologyEngine.PortaAnalise> portas = engine.analisarPortas(pai.macNormalizado());
        List<String> portasOrdenadas = new ArrayList<>(portas.keySet());
        portasOrdenadas.sort(RelatorioTopologia::compararPortas);

        List<Enlace> vizinhosDiretos = new ArrayList<>();

        for (String portaLocal : portasOrdenadas) {
            TopologyEngine.PortaAnalise analise = portas.get(portaLocal);
            if (!analise.ehPortaDeAcesso()) {
                TopologyEngine.ResultadoInferencia r = engine.inferirVizinhoDireto(pai.macNormalizado(), analise.switchesVisiveis());
                if (r.tipo() == TopologyEngine.ResultadoInferencia.Tipo.DIRETO ||
                    r.tipo() == TopologyEngine.ResultadoInferencia.Tipo.DIRETO_PARCIAL) {
                    SwitchIdentity filho = r.candidatos().get(0);
                    if (!visitados.contains(filho.macNormalizado())) {
                        String portaRemota = engine.portaRemota(pai.macNormalizado(), filho.macNormalizado())
                                .orElse("?");
                        vizinhosDiretos.add(new Enlace(portaLocal, portaRemota, filho));
                    }
                }
            }
        }

        for (int i = 0; i < vizinhosDiretos.size(); i++) {
            boolean ehUltimo = (i == vizinhosDiretos.size() - 1);
            Enlace e = vizinhosDiretos.get(i);
            visitados.add(e.vizinho().macNormalizado());

            String conector = ehUltimo ? RAMO_FIM : RAMO_MEIO;
            sb.append(prefixo)
                    .append(conector)
                    .append(e.portaLocal())
                    .append(" <-> ")
                    .append(e.portaRemota())
                    .append(TRACEJADO)
                    .append(e.vizinho().deviceName())
                    .append(" (")
                    .append(e.vizinho().ip())
                    .append(")\n");

            String proximoPrefixo = prefixo + (ehUltimo ? "   " : GUIA_COLUNA);
            desenharGalho(engine, e.vizinho(), proximoPrefixo, visitados, sb);
        }
    }

    /**
     * Comparador natural para portas (ex: GE1/0/1 < GE1/0/2 < GE1/0/10 < GE1/0/24 < XGE1/0/25).
     */
    public static int compararPortas(String p1, String p2) {
        if (p1 == null) return p2 == null ? 0 : -1;
        if (p2 == null) return 1;

        int i1 = 0, i2 = 0;
        int len1 = p1.length(), len2 = p2.length();

        while (i1 < len1 && i2 < len2) {
            char c1 = p1.charAt(i1);
            char c2 = p2.charAt(i2);

            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                int j1 = i1;
                while (j1 < len1 && Character.isDigit(p1.charAt(j1))) j1++;
                long num1 = Long.parseLong(p1.substring(i1, j1));

                int j2 = i2;
                while (j2 < len2 && Character.isDigit(p2.charAt(j2))) j2++;
                long num2 = Long.parseLong(p2.substring(i2, j2));

                if (num1 != num2) {
                    return Long.compare(num1, num2);
                }
                i1 = j1;
                i2 = j2;
            } else {
                int cmp = Character.compare(Character.toUpperCase(c1), Character.toUpperCase(c2));
                if (cmp != 0) return cmp;
                i1++;
                i2++;
            }
        }
        return Integer.compare(len1, len2);
    }

    private static String nomes(Collection<SwitchIdentity> s) {
        return s.stream().map(SwitchIdentity::deviceName).sorted().toList().toString();
    }
}

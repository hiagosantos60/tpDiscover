package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.modelos.GatewayInfo;
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
 *  - Bloco de Enlaces Diretos Par a Par (Opção B)
 *  - Bloco de Árvore da Topologia (Opção A): iniciando no Roteador/Gateway quando identificado,
 *    ou com linha raiz em branco descendo pela 1ª extremidade quando não houver Roteador identificado
 *  - Exportação em arquivo TXT (topologia_AAAA-MM-DD_HHmm.txt)
 */
public class RelatorioTopologia {

    private static final DateTimeFormatter ARQUIVO_TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

    // Constantes visuais compatíveis com UTF-8, CP1252 e CP850 (console Windows PowerShell/CMD)
    private static final String RAMO_MEIO         = "\u251C\u2500 "; // ├─ 
    private static final String RAMO_FIM          = "\u2514\u2500 "; // └─ 
    private static final String CONTINUACAO_MEIO  = "\u2502  ";      // │  
    private static final String CONTINUACAO_VAZIA = "   ";
    private static final String TRACO_HORIZONTAL  = "\u2500";        // ─

    private record Enlace(String portaLocal, String portaRemota, SwitchIdentity vizinho) {}

    private record ParEnlace(SwitchIdentity swA, String portaA, String portaB, SwitchIdentity swB) {}

    private record LinhaParAPar(String colunaEsquerda, String portaDireita, String equipamentoDireito) {}

    public static void imprimir(TopologyEngine engine) {
        imprimir(engine, null);
    }

    public static void imprimir(TopologyEngine engine, Path pastaDestino) {
        String relatorio = gerarRelatorioTexto(engine);
        System.out.println(relatorio);

        String nomeArquivo = "topologia_" + LocalDateTime.now().format(ARQUIVO_TIMESTAMP_FMT) + ".txt";
        Path destino = (pastaDestino != null) ? pastaDestino.resolve(nomeArquivo) : Path.of(nomeArquivo);
        try {
            Files.writeString(destino, relatorio, StandardCharsets.UTF_8);
            System.out.println("\n[ARQUIVO] Relatório salvo com sucesso em: " + destino);
        } catch (IOException e) {
            System.err.println("[AVISO] Não foi possível salvar o arquivo " + destino + ": " + e.getMessage());
        }
    }

    public static String gerarRelatorioTexto(TopologyEngine engine) {
        StringBuilder sb = new StringBuilder();
        formatarMapaTopologia(engine, sb);
        return sb.toString();
    }

    /**
     * Formata os dois blocos complementares da topologia (Modelo 2):
     *  1) ENLACES DIRETOS (PAR A PAR) - Opção B
     *  2) ÁRVORE DA TOPOLOGIA         - Opção A (ancorada no Roteador/Gateway quando detectado,
     *                                   ou com linha raiz em branco quando não houver Roteador)
     */
    private static void formatarMapaTopologia(TopologyEngine engine, StringBuilder sb) {
        List<SwitchIdentity> switches = new ArrayList<>(engine.switchesRegistrados());
        if (switches.isEmpty()) {
            sb.append("====================================================================================================\n");
            sb.append("ÁRVORE DA TOPOLOGIA\n");
            sb.append("====================================================================================================\n");
            sb.append("(nenhum switch registrado para exibição da topologia)\n");
            return;
        }

        Optional<GatewayInfo> gwOpt = engine.gatewayIdentificado();
        List<TopologyEngine.ConexaoGateway> conexoesGw = gwOpt.isPresent()
                ? engine.identificarConexoesGateway()
                : List.of();

        Map<String, List<Enlace>> adjacencia = construirGrafoEnlacesDiretos(engine, switches);
        formatarEnlacesDiretosParAPar(switches, adjacencia, gwOpt.orElse(null), conexoesGw, sb);
        formatarArvoreTopologia(switches, adjacencia, gwOpt.orElse(null), conexoesGw, sb);
    }

    private static void formatarEnlacesDiretosParAPar(
            List<SwitchIdentity> switches,
            Map<String, List<Enlace>> adjacencia,
            GatewayInfo gateway,
            List<TopologyEngine.ConexaoGateway> conexoesGw,
            StringBuilder sb
    ) {
        sb.append("====================================================================================================\n");
        sb.append("ENLACES DIRETOS (PAR A PAR)\n");
        sb.append("====================================================================================================\n");

        List<ParEnlace> pares = extrairParesUnicos(switches, adjacencia);
        if (conexoesGw.isEmpty() && pares.isEmpty()) {
            if (switches.size() == 1) {
                sb.append("(apenas 1 equipamento coletado e nenhum roteador/gateway identificado)\n");
            } else {
                sb.append("(nenhum enlace direto identificado entre os switches coletados)\n");
            }
            return;
        }

        int largSwEsq = pares.stream()
                .mapToInt(p -> rotuloVisualSwitch(p.swA()).length())
                .max()
                .orElse(0);

        List<LinhaParAPar> linhas = new ArrayList<>();

        if (gateway != null) {
            for (TopologyEngine.ConexaoGateway cg : conexoesGw) {
                linhas.add(new LinhaParAPar(
                        gateway.rotuloVisual(),
                        "[" + cg.porta() + "]",
                        rotuloVisualSwitch(cg.switchCore())
                ));
            }
        }

        for (ParEnlace p : pares) {
            String esq = String.format("%-" + largSwEsq + "s [%s]", rotuloVisualSwitch(p.swA()), p.portaA());
            linhas.add(new LinhaParAPar(
                    esq,
                    "[" + p.portaB() + "]",
                    rotuloVisualSwitch(p.swB())
            ));
        }

        int largColEsq = linhas.stream().mapToInt(l -> l.colunaEsquerda().length()).max().orElse(20);
        int largPortaDir = linhas.stream().mapToInt(l -> l.portaDireita().length()).max().orElse(6);
        String fmt = "%-" + largColEsq + "s <-> %-" + largPortaDir + "s %s%n";

        for (LinhaParAPar l : linhas) {
            sb.append(String.format(fmt, l.colunaEsquerda(), l.portaDireita(), l.equipamentoDireito()));
        }
    }

    private static List<ParEnlace> extrairParesUnicos(
            List<SwitchIdentity> switches,
            Map<String, List<Enlace>> adjacencia
    ) {
        List<ParEnlace> pares = new ArrayList<>();
        Set<String> chavesVistas = new HashSet<>();

        for (SwitchIdentity sw : switches) {
            String idA = sw.macNormalizado();
            for (Enlace e : adjacencia.getOrDefault(idA, List.of())) {
                String idB = e.vizinho().macNormalizado();
                String chave = criarChaveAresta(idA, e.portaLocal(), idB, e.portaRemota());
                if (chavesVistas.add(chave)) {
                    pares.add(new ParEnlace(sw, e.portaLocal(), e.portaRemota(), e.vizinho()));
                }
            }
        }
        return pares;
    }

    private static void formatarArvoreTopologia(
            List<SwitchIdentity> switches,
            Map<String, List<Enlace>> adjacencia,
            GatewayInfo gateway,
            List<TopologyEngine.ConexaoGateway> conexoesGw,
            StringBuilder sb
    ) {
        sb.append("\n====================================================================================================\n");
        sb.append("ÁRVORE DA TOPOLOGIA\n");
        sb.append("====================================================================================================\n");

        Set<String> visitados = new HashSet<>();
        Set<String> arestasImpressas = new HashSet<>();

        if (gateway != null && !conexoesGw.isEmpty()) {
            // Caso 1: Roteador/Gateway identificado -> ocupa o topo da árvore e deriva para o(s) switch(es) Core
            sb.append(gateway.rotuloVisual()).append("\n");

            for (TopologyEngine.ConexaoGateway cg : conexoesGw) {
                visitados.add(cg.switchCore().macNormalizado());
            }

            Set<String> coresJaExpandidos = new HashSet<>();
            for (int i = 0; i < conexoesGw.size(); i++) {
                TopologyEngine.ConexaoGateway cg = conexoesGw.get(i);
                boolean ultimoCore = (i == conexoesGw.size() - 1);
                String conector = ultimoCore ? RAMO_FIM : RAMO_MEIO;
                String prefixoFilhos = ultimoCore ? CONTINUACAO_VAZIA : CONTINUACAO_MEIO;

                sb.append(conector)
                        .append("<-> ")
                        .append(cg.porta())
                        .append(" ").append(TRACO_HORIZONTAL).append(" ")
                        .append(rotuloVisualSwitch(cg.switchCore()))
                        .append("\n");

                if (coresJaExpandidos.add(cg.switchCore().macNormalizado())) {
                    desenharFilhosArvore(
                            cg.switchCore(),
                            prefixoFilhos,
                            visitados,
                            arestasImpressas,
                            adjacencia,
                            sb
                    );
                }
            }

            // Se houver algum switch coletado em segmento isolado que não alcança o componente do Gateway
            List<SwitchIdentity> naoVisitados = new ArrayList<>();
            for (SwitchIdentity sw : switches) {
                if (!visitados.contains(sw.macNormalizado())) {
                    naoVisitados.add(sw);
                }
            }
            if (!naoVisitados.isEmpty()) {
                List<List<SwitchIdentity>> compExtras = extrairComponentesConexas(naoVisitados, adjacencia);
                sb.append("\n");
                desenharComponentesSemGateway(compExtras, visitados, arestasImpressas, adjacencia, sb, true);
            }
            return;
        }

        // Caso 2 (Visual 1): Nenhum Roteador/Gateway identificado -> linha do topo fica em branco (vazia)
        // e desce diretamente com └─ (ou ├─ / └─ se houver segmentos desconexos) pela 1ª extremidade
        sb.append("\n");
        List<List<SwitchIdentity>> componentes = extrairComponentesConexas(switches, adjacencia);
        desenharComponentesSemGateway(componentes, visitados, arestasImpressas, adjacencia, sb, false);
    }

    private static void desenharComponentesSemGateway(
            List<List<SwitchIdentity>> componentes,
            Set<String> visitados,
            Set<String> arestasImpressas,
            Map<String, List<Enlace>> adjacencia,
            StringBuilder sb,
            boolean segmentoExtraIsolado
    ) {
        for (int i = 0; i < componentes.size(); i++) {
            List<SwitchIdentity> comp = componentes.get(i);
            boolean ultimoComponente = (i == componentes.size() - 1);
            SwitchIdentity inicio = escolherExtremidadeInicial(comp, adjacencia);

            String conector = ultimoComponente ? RAMO_FIM : RAMO_MEIO;
            String prefixoFilhos = ultimoComponente ? CONTINUACAO_VAZIA : CONTINUACAO_MEIO;

            String sufixo = "";
            if (segmentoExtraIsolado) {
                sufixo = " (segmento sem enlace com o gateway acima)";
            } else if (componentes.size() > 1 && comp.size() == 1) {
                sufixo = " (sem enlace com os demais switches)";
            }

            visitados.add(inicio.macNormalizado());
            sb.append(conector)
                    .append(rotuloVisualSwitch(inicio))
                    .append(sufixo)
                    .append("\n");

            desenharFilhosArvore(
                    inicio,
                    prefixoFilhos,
                    visitados,
                    arestasImpressas,
                    adjacencia,
                    sb
            );
        }
    }

    /**
     * Quando não há Roteador/Gateway identificado, escolhe o 1º switch de extremidade (grau <= 1)
     * seguindo a ordem do switches.txt — evitando dobrar cascatas no meio ou eleger um switch intermediário.
     */
    private static SwitchIdentity escolherExtremidadeInicial(
            List<SwitchIdentity> componente,
            Map<String, List<Enlace>> adjacencia
    ) {
        for (SwitchIdentity sw : componente) {
            int grau = adjacencia.getOrDefault(sw.macNormalizado(), List.of()).size();
            if (grau <= 1) {
                return sw;
            }
        }
        return componente.get(0);
    }

    private static void desenharFilhosArvore(
            SwitchIdentity atual,
            String prefixo,
            Set<String> visitados,
            Set<String> arestasImpressas,
            Map<String, List<Enlace>> adjacencia,
            StringBuilder sb
    ) {
        String idAtual = atual.macNormalizado();
        List<Enlace> enlaces = adjacencia.getOrDefault(idAtual, List.of());

        List<Enlace> filhos = new ArrayList<>();
        List<Enlace> loops = new ArrayList<>();

        for (Enlace e : enlaces) {
            String idViz = e.vizinho().macNormalizado();
            String chaveAresta = criarChaveAresta(idAtual, e.portaLocal(), idViz, e.portaRemota());
            if (arestasImpressas.contains(chaveAresta)) {
                continue;
            }
            if (visitados.contains(idViz)) {
                loops.add(e);
            } else {
                filhos.add(e);
            }
        }

        if (filhos.isEmpty() && loops.isEmpty()) {
            return;
        }

        // Reserva filhos como visitados e registra arestas para evitar duplicação ou loop infinito
        for (Enlace f : filhos) {
            visitados.add(f.vizinho().macNormalizado());
            arestasImpressas.add(criarChaveAresta(idAtual, f.portaLocal(), f.vizinho().macNormalizado(), f.portaRemota()));
        }
        for (Enlace l : loops) {
            arestasImpressas.add(criarChaveAresta(idAtual, l.portaLocal(), l.vizinho().macNormalizado(), l.portaRemota()));
        }

        int totalItens = filhos.size() + loops.size();
        int idx = 0;

        for (Enlace f : filhos) {
            boolean ehUltimo = (idx == totalItens - 1);
            String conector = ehUltimo ? RAMO_FIM : RAMO_MEIO;
            String proximoPrefixo = prefixo + (ehUltimo ? CONTINUACAO_VAZIA : CONTINUACAO_MEIO);

            sb.append(prefixo)
                    .append(conector)
                    .append(f.portaLocal())
                    .append(" <-> ")
                    .append(f.portaRemota())
                    .append(" ").append(TRACO_HORIZONTAL).append(" ")
                    .append(rotuloVisualSwitch(f.vizinho()))
                    .append("\n");

            desenharFilhosArvore(
                    f.vizinho(),
                    proximoPrefixo,
                    visitados,
                    arestasImpressas,
                    adjacencia,
                    sb
            );
            idx++;
        }

        for (Enlace l : loops) {
            boolean ehUltimo = (idx == totalItens - 1);
            String conector = ehUltimo ? RAMO_FIM : RAMO_MEIO;

            sb.append(prefixo)
                    .append(conector)
                    .append(l.portaLocal())
                    .append(" <-> ")
                    .append(l.portaRemota())
                    .append(" ").append(TRACO_HORIZONTAL).append(" [enlace redundante/loop -> ")
                    .append(rotuloVisualSwitch(l.vizinho()))
                    .append("]\n");
            idx++;
        }
    }

    private static List<List<SwitchIdentity>> extrairComponentesConexas(
            List<SwitchIdentity> switches,
            Map<String, List<Enlace>> adjacencia
    ) {
        Map<String, SwitchIdentity> porId = new LinkedHashMap<>();
        for (SwitchIdentity sw : switches) {
            porId.put(sw.macNormalizado(), sw);
        }

        Set<String> visitados = new HashSet<>();
        List<List<SwitchIdentity>> componentes = new ArrayList<>();

        for (SwitchIdentity sw : switches) {
            String id = sw.macNormalizado();
            if (!visitados.add(id)) continue;

            Set<String> idsNaComponente = new HashSet<>();
            Queue<String> fila = new ArrayDeque<>();
            fila.add(id);
            idsNaComponente.add(id);

            while (!fila.isEmpty()) {
                String u = fila.poll();
                for (Enlace e : adjacencia.getOrDefault(u, List.of())) {
                    String v = e.vizinho().macNormalizado();
                    if (porId.containsKey(v) && visitados.add(v)) {
                        idsNaComponente.add(v);
                        fila.add(v);
                    }
                }
            }

            List<SwitchIdentity> compOrdenada = new ArrayList<>();
            for (SwitchIdentity candidato : switches) {
                if (idsNaComponente.contains(candidato.macNormalizado())) {
                    compOrdenada.add(candidato);
                }
            }
            componentes.add(compOrdenada);
        }

        return componentes;
    }

    /**
     * Constrói o grafo não-direcionado (simétrico) de enlaces diretos entre os switches coletados.
     * Caso duas portas que se enxergam sejam ambas portas de entrada para o Roteador/Gateway
     * (ex.: dois switches core ligados em portas LAN em bridge do roteador), não cria cabo falso entre eles.
     */
    private static Map<String, List<Enlace>> construirGrafoEnlacesDiretos(
            TopologyEngine engine,
            List<SwitchIdentity> switches
    ) {
        Map<String, List<Enlace>> adj = new LinkedHashMap<>();
        for (SwitchIdentity sw : switches) {
            adj.put(sw.macNormalizado(), new ArrayList<>());
        }

        for (SwitchIdentity origem : switches) {
            String idOrigem = origem.macNormalizado();
            Map<String, TopologyEngine.PortaAnalise> portas = engine.analisarPortas(idOrigem);
            List<String> portasOrd = new ArrayList<>(portas.keySet());
            portasOrd.sort(RelatorioTopologia::compararPortas);

            for (String porta : portasOrd) {
                TopologyEngine.PortaAnalise analise = portas.get(porta);
                if (analise.ehPortaDeAcesso()) continue;

                TopologyEngine.ResultadoInferencia r = engine.inferirVizinhoDireto(idOrigem, analise.switchesVisiveis());
                if (r.tipo() == TopologyEngine.ResultadoInferencia.Tipo.DIRETO
                        || r.tipo() == TopologyEngine.ResultadoInferencia.Tipo.DIRETO_PARCIAL) {
                    SwitchIdentity direto = r.candidatos().get(0);
                    String idDireto = direto.macNormalizado();
                    Optional<String> portaRemOpt = engine.portaRemota(idOrigem, idDireto);
                    String portaRem = portaRemOpt.orElse("?");

                    // Se ambos os lados são portas de entrada do Gateway, o roteador está no meio (não há cabo direto entre os dois switches)
                    if (engine.ehPortaDeEntradaGateway(idOrigem, porta)
                            && portaRemOpt.isPresent()
                            && engine.ehPortaDeEntradaGateway(idDireto, portaRemOpt.get())) {
                        continue;
                    }

                    adicionarEnlaceSeAusente(adj.get(idOrigem), new Enlace(porta, portaRem, direto));
                    if (adj.containsKey(idDireto)) {
                        adicionarEnlaceSeAusente(adj.get(idDireto), new Enlace(portaRem, porta, origem));
                    }
                }
            }
        }

        for (List<Enlace> lista : adj.values()) {
            lista.sort((a, b) -> compararPortas(a.portaLocal(), b.portaLocal()));
        }

        return adj;
    }

    private static void adicionarEnlaceSeAusente(List<Enlace> lista, Enlace novo) {
        String idNovo = novo.vizinho().macNormalizado();
        for (int i = 0; i < lista.size(); i++) {
            Enlace existente = lista.get(i);
            if (!existente.vizinho().macNormalizado().equals(idNovo)) continue;

            boolean mesmaPortaLocal = existente.portaLocal().equalsIgnoreCase(novo.portaLocal())
                    || "?".equals(existente.portaLocal())
                    || "?".equals(novo.portaLocal());
            boolean mesmaPortaRemota = existente.portaRemota().equalsIgnoreCase(novo.portaRemota())
                    || "?".equals(existente.portaRemota())
                    || "?".equals(novo.portaRemota());

            if (mesmaPortaLocal && mesmaPortaRemota) {
                String melhorLocal = "?".equals(existente.portaLocal()) ? novo.portaLocal() : existente.portaLocal();
                String melhorRemota = "?".equals(existente.portaRemota()) ? novo.portaRemota() : existente.portaRemota();
                lista.set(i, new Enlace(melhorLocal, melhorRemota, existente.vizinho()));
                return;
            }
        }
        lista.add(novo);
    }

    private static String criarChaveAresta(String idA, String portaA, String idB, String portaB) {
        String lado1 = idA + ":" + portaA.toUpperCase();
        String lado2 = idB + ":" + portaB.toUpperCase();
        return lado1.compareTo(lado2) <= 0 ? (lado1 + "::" + lado2) : (lado2 + "::" + lado1);
    }

    /**
     * Retorna o nome limpo do equipamento, removendo "INTELBRAS" ou "Switch" padrão de fábrica
     * em favor do código do modelo (ex: "S3028G-B", "S3028G-PB Max").
     */
    public static String nomeExibicaoSwitch(SwitchIdentity sw) {
        if (sw == null) return "Switch";
        String nome = sw.deviceName() != null ? sw.deviceName().trim() : "";
        String modelo = sw.productId() != null ? sw.productId().trim() : "";

        boolean nomeGenerico = nome.isEmpty()
                || nome.equalsIgnoreCase("INTELBRAS")
                || nome.equalsIgnoreCase("Switch");

        if (nomeGenerico) {
            if (!modelo.isEmpty()
                    && !modelo.equalsIgnoreCase("INTELBRAS")
                    && !modelo.equalsIgnoreCase("Série 3000")) {
                return modelo;
            }
            if (sw.ip() != null && !sw.ip().isBlank()) {
                return "S30-" + sw.ip().trim();
            }
            return "Switch";
        }
        return nome;
    }

    private static String rotuloVisualSwitch(SwitchIdentity sw) {
        String ip = sw.ip() != null ? sw.ip() : "N/D";
        return nomeExibicaoSwitch(sw) + " (" + ip + ")";
    }

    /**
     * Comparador natural de nomes de portas (GE1/0/1 < GE1/0/2 < GE1/0/10 < XGE1/0/1 < BAGG1).
     * Divide as strings em blocos de texto e números para comparar numericamente cada segmento.
     */
    public static int compararPortas(String p1, String p2) {
        if (p1 == null && p2 == null) return 0;
        if (p1 == null) return -1;
        if (p2 == null) return 1;

        int prio1 = prioridadeFamiliaPorta(p1);
        int prio2 = prioridadeFamiliaPorta(p2);
        if (prio1 != prio2) {
            return Integer.compare(prio1, prio2);
        }

        int i = 0, j = 0;
        int n1 = p1.length(), n2 = p2.length();

        while (i < n1 && j < n2) {
            char c1 = p1.charAt(i);
            char c2 = p2.charAt(j);

            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                long num1 = 0;
                while (i < n1 && Character.isDigit(p1.charAt(i))) {
                    num1 = num1 * 10 + (p1.charAt(i) - '0');
                    i++;
                }
                long num2 = 0;
                while (j < n2 && Character.isDigit(p2.charAt(j))) {
                    num2 = num2 * 10 + (p2.charAt(j) - '0');
                    j++;
                }
                if (num1 != num2) {
                    return Long.compare(num1, num2);
                }
            } else {
                int cmp = Character.compare(Character.toUpperCase(c1), Character.toUpperCase(c2));
                if (cmp != 0) {
                    return cmp;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(n1 - i, n2 - j);
    }

    private static int prioridadeFamiliaPorta(String porta) {
        String u = porta.toUpperCase();
        if (u.startsWith("FE") || u.startsWith("E0") || u.startsWith("ETH")) return 1;
        if (u.startsWith("GE") || u.startsWith("GI") || u.startsWith("G0")) return 2;
        if (u.startsWith("XGE") || u.startsWith("TE") || u.startsWith("TEN")) return 3;
        if (u.startsWith("GPON") || u.startsWith("EPON") || u.startsWith("PON") || u.startsWith("P0")) return 4;
        if (u.startsWith("BAGG") || u.startsWith("AGG") || u.startsWith("BRIDGE")) return 5;
        return 6;
    }
}

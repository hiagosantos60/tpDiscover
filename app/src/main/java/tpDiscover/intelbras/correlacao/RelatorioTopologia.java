package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.modelos.GatewayInfo;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.LldpNeighbor.CategoriaInteresse;
import tpDiscover.intelbras.modelos.LoopDiagnostic;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.ComwareParser;
import tpDiscover.intelbras.parser.Serie3000Parser;

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
    static final String TRACO_HORIZONTAL  = "\u2500";        // ─

    record Enlace(String portaLocal, String portaRemota, SwitchIdentity vizinho) {}

    record ParEnlace(SwitchIdentity swA, String portaA, String portaB, SwitchIdentity swB) {}

    private record LinhaParAPar(String colunaEsquerda, String portaDireita, String equipamentoDireito) {}

    public record EnlaceResumo(String origem, String portaOrigem, String portaDestino, String destino) {}

    public record ResultadoRelatorio(
            String textoCompleto,
            List<EnlaceResumo> enlaces,
            List<LoopDiagnostic> loops,
            Path arquivoGerado,
            List<SwitchIdentity> switches,
            boolean topologiaParcial,
            List<TopologyEngine.EquipamentoSemAcesso> pendenciasAcesso
    ) {
        public ResultadoRelatorio(
                String textoCompleto,
                List<EnlaceResumo> enlaces,
                List<LoopDiagnostic> loops,
                Path arquivoGerado,
                List<SwitchIdentity> switches
        ) {
            this(textoCompleto, enlaces, loops, arquivoGerado, switches, false, List.of());
        }
    }

    public static void imprimir(TopologyEngine engine) {
        imprimir(engine, null);
    }

    public static void imprimir(TopologyEngine engine, Path pastaDestino) {
        ResultadoRelatorio res = gerarResultado(engine, pastaDestino, true);
        System.out.println(res.textoCompleto());
        if (res.arquivoGerado() != null) {
            System.out.println("\n[PDF] Relatório executivo da topologia gerado com sucesso em: " + res.arquivoGerado());
        }
    }

    public static ResultadoRelatorio gerarResultado(TopologyEngine engine, Path pastaDestino, boolean exportarArquivo) {
        if (engine == null) {
            return new ResultadoRelatorio("", List.of(), List.of(), null, List.of());
        }
        List<SwitchIdentity> switches = new ArrayList<>(engine.switchesRegistrados());
        StringBuilder sb = new StringBuilder();
        List<EnlaceResumo> enlacesResumo = new ArrayList<>();
        List<LoopDiagnostic> loops = List.of();

        if (switches.isEmpty()) {
            formatarMapaTopologia(engine, sb);
            List<TopologyEngine.EquipamentoSemAcesso> pendenciasVazias = engine.equipamentosSemAcesso();
            return new ResultadoRelatorio(
                    sb.toString(),
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    !pendenciasVazias.isEmpty(),
                    List.copyOf(pendenciasVazias));
        }

        TopologyEngine.PlanoGateway plano = engine.analisarPlanoGateway();
        List<TopologyEngine.ConexaoPassiva> passivas = engine.resolverConexoesPassivasSemAcesso();
        List<TopologyEngine.EquipamentoSemAcesso> pendencias = engine.equipamentosSemAcesso();
        Map<String, List<Enlace>> adjacencia = construirGrafoEnlacesDiretos(engine, switches);
        List<ParEnlace> pares = extrairParesUnicos(switches, adjacencia);

        GatewayInfo gw = plano.gateway();
        if (gw != null) {
            for (TopologyEngine.ConexaoGateway cg : plano.conexoesDiretas()) {
                enlacesResumo.add(new EnlaceResumo(gw.rotuloVisual(), "-", cg.porta(), rotuloVisualSwitch(cg.switchCore())));
            }
            for (TopologyEngine.ConexaoLldpUpstream u : plano.conexoesViaLldpExterno()) {
                enlacesResumo.add(new EnlaceResumo(
                        rotuloVizinhoLldp(u.vizinhoExterno()),
                        resolverPortaRemotaCurta(u.vizinhoExterno()),
                        u.portaLocalFilho(),
                        rotuloVisualSwitch(u.switchFilho())));
            }
            for (TopologyEngine.ConexaoGateway cg : plano.conexoesViaSegmento()) {
                enlacesResumo.add(new EnlaceResumo(
                        rotuloSegmentoIncerto(plano), "incerto", cg.porta(), rotuloVisualSwitch(cg.switchCore())));
            }
        }
        for (ParEnlace p : pares) {
            enlacesResumo.add(new EnlaceResumo(
                    rotuloVisualSwitch(p.swA()), p.portaA(), p.portaB(), rotuloVisualSwitch(p.swB())));
        }
        for (TopologyEngine.ConexaoPassiva cp : passivas) {
            enlacesResumo.add(new EnlaceResumo(
                    rotuloVisualSwitch(cp.switchPai()), cp.portaPai(), "passivo",
                    rotuloEquipamentoSemAcesso(cp.equipamento())));
        }

        loops = diagnosticarLoopsParaPares(engine, pares);
        formatarMapaTopologia(engine, switches, plano, passivas, adjacencia, loops, sb);

        boolean topologiaParcial = plano.possuiSegmentoIncerto()
                || !plano.conexoesViaLldpExterno().isEmpty()
                || !pendencias.isEmpty();

        Path arquivoGerado = null;
        if (exportarArquivo) {
            try {
                arquivoGerado = ExportadorPlanejadorRede.exportarPdf(engine, pastaDestino);
            } catch (Exception e) {
                System.err.println("[AVISO] Não foi possível gerar o PDF da topologia: " + e.getMessage());
            }
        }
        return new ResultadoRelatorio(
                sb.toString(),
                List.copyOf(enlacesResumo),
                List.copyOf(loops),
                arquivoGerado,
                List.copyOf(switches),
                topologiaParcial,
                List.copyOf(pendencias)
        );
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
            formatarPendenciasSemAcesso(engine, sb);
            return;
        }

        TopologyEngine.PlanoGateway plano = engine.analisarPlanoGateway();
        List<TopologyEngine.ConexaoPassiva> passivas = engine.resolverConexoesPassivasSemAcesso();
        Map<String, List<Enlace>> adjacencia = construirGrafoEnlacesDiretos(engine, switches);
        List<ParEnlace> pares = extrairParesUnicos(switches, adjacencia);
        List<LoopDiagnostic> loops = diagnosticarLoopsParaPares(engine, pares);
        formatarMapaTopologia(engine, switches, plano, passivas, adjacencia, loops, sb);
    }

    private static void formatarMapaTopologia(
            TopologyEngine engine,
            List<SwitchIdentity> switches,
            TopologyEngine.PlanoGateway plano,
            List<TopologyEngine.ConexaoPassiva> passivas,
            Map<String, List<Enlace>> adjacencia,
            List<LoopDiagnostic> loops,
            StringBuilder sb
    ) {
        formatarEnlacesDiretosParAPar(switches, adjacencia, plano, passivas, sb);
        formatarArvoreTopologia(switches, adjacencia, plano, sb);
        formatarVizinhosExternosLldp(engine, switches, plano.gateway(), sb);
        formatarPendenciasSemAcesso(engine, sb);
        formatarDiagnosticoLoops(switches, loops, sb);
    }

    private static void formatarEnlacesDiretosParAPar(
            List<SwitchIdentity> switches,
            Map<String, List<Enlace>> adjacencia,
            TopologyEngine.PlanoGateway plano,
            List<TopologyEngine.ConexaoPassiva> passivas,
            StringBuilder sb
    ) {
        sb.append("====================================================================================================\n");
        sb.append("ENLACES DIRETOS (PAR A PAR)\n");
        sb.append("====================================================================================================\n");

        GatewayInfo gateway = plano.gateway();
        boolean temConexaoGateway = gateway != null && (!plano.conexoesDiretas().isEmpty()
                || !plano.conexoesViaLldpExterno().isEmpty()
                || !plano.conexoesViaSegmento().isEmpty());

        List<ParEnlace> pares = extrairParesUnicos(switches, adjacencia);
        if (!temConexaoGateway && pares.isEmpty() && passivas.isEmpty()) {
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
            for (TopologyEngine.ConexaoGateway cg : plano.conexoesDiretas()) {
                linhas.add(new LinhaParAPar(
                        gateway.rotuloVisual(),
                        "[" + cg.porta() + "]",
                        rotuloVisualSwitch(cg.switchCore())
                ));
            }
            for (TopologyEngine.ConexaoLldpUpstream u : plano.conexoesViaLldpExterno()) {
                String pRem = resolverPortaRemotaCurta(u.vizinhoExterno());
                linhas.add(new LinhaParAPar(
                        rotuloVizinhoLldp(u.vizinhoExterno()) + " [" + pRem + "]",
                        "[" + u.portaLocalFilho() + "]",
                        rotuloVisualSwitch(u.switchFilho())
                ));
            }
            if (plano.possuiSegmentoIncerto()) {
                String segRotulo = rotuloSegmentoIncerto(plano);
                linhas.add(new LinhaParAPar(
                        gateway.rotuloVisual(),
                        "[incerto]",
                        segRotulo
                ));
                for (TopologyEngine.ConexaoGateway cg : plano.conexoesViaSegmento()) {
                    linhas.add(new LinhaParAPar(
                            segRotulo + " [incerto]",
                            "[" + cg.porta() + "]",
                            rotuloVisualSwitch(cg.switchCore())
                    ));
                }
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

        for (TopologyEngine.ConexaoPassiva cp : passivas) {
            linhas.add(new LinhaParAPar(
                    rotuloVisualSwitch(cp.switchPai()) + " [" + cp.portaPai() + "]",
                    "[passivo]",
                    rotuloEquipamentoSemAcesso(cp.equipamento())
            ));
        }

        int largColEsq = linhas.stream().mapToInt(l -> l.colunaEsquerda().length()).max().orElse(20);
        int largPortaDir = linhas.stream().mapToInt(l -> l.portaDireita().length()).max().orElse(6);
        String fmt = "%-" + largColEsq + "s <-> %-" + largPortaDir + "s %s%n";

        for (LinhaParAPar l : linhas) {
            sb.append(String.format(fmt, l.colunaEsquerda(), l.portaDireita(), l.equipamentoDireito()));
        }
    }

    static List<ParEnlace> extrairParesUnicos(
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
            TopologyEngine.PlanoGateway plano,
            StringBuilder sb
    ) {
        sb.append("\n====================================================================================================\n");
        sb.append("ÁRVORE DA TOPOLOGIA\n");
        sb.append("====================================================================================================\n");

        GatewayInfo gateway = plano.gateway();
        boolean temConexaoGateway = gateway != null && (!plano.conexoesDiretas().isEmpty()
                || !plano.conexoesViaLldpExterno().isEmpty()
                || !plano.conexoesViaSegmento().isEmpty());

        Set<String> visitados = new HashSet<>();
        Set<String> arestasImpressas = new HashSet<>();

        if (plano.possuiSegmentoIncerto() || !plano.conexoesViaLldpExterno().isEmpty()) {
            sb.append("[ATENÇÃO] TOPOLOGIA PARCIAL: há equipamentos/uplinks sem acesso CLI; não foram inferidos links diretos no Gateway.\n");
        }

        if (temConexaoGateway) {
            // Caso 1: Roteador/Gateway identificado -> ocupa o topo da árvore
            sb.append(gateway.rotuloVisual()).append("\n");

            for (TopologyEngine.ConexaoGateway cg : plano.conexoesDiretas()) {
                visitados.add(cg.switchCore().macNormalizado());
            }
            for (TopologyEngine.ConexaoLldpUpstream u : plano.conexoesViaLldpExterno()) {
                visitados.add(u.switchFilho().macNormalizado());
            }
            for (TopologyEngine.ConexaoGateway cg : plano.conexoesViaSegmento()) {
                visitados.add(cg.switchCore().macNormalizado());
            }

            // Agrupa enxames por switch/OLT externo LLDP e pelo bloco de segmento incerto
            Map<String, List<TopologyEngine.ConexaoLldpUpstream>> gruposLldp = new LinkedHashMap<>();
            for (TopologyEngine.ConexaoLldpUpstream u : plano.conexoesViaLldpExterno()) {
                gruposLldp.computeIfAbsent(rotuloVizinhoLldp(u.vizinhoExterno()), k -> new ArrayList<>()).add(u);
            }

            int totalRamosRaiz = plano.conexoesDiretas().size()
                    + gruposLldp.size()
                    + (plano.possuiSegmentoIncerto() ? 1 : 0);
            int idxRaiz = 0;

            Set<String> coresJaExpandidos = new HashSet<>();
            for (TopologyEngine.ConexaoGateway cg : plano.conexoesDiretas()) {
                boolean ultimoCore = (++idxRaiz == totalRamosRaiz);
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

            for (Map.Entry<String, List<TopologyEngine.ConexaoLldpUpstream>> grupo : gruposLldp.entrySet()) {
                boolean ultimoGrupo = (++idxRaiz == totalRamosRaiz);
                String conectorGrupo = ultimoGrupo ? RAMO_FIM : RAMO_MEIO;
                String prefixoGrupo = ultimoGrupo ? CONTINUACAO_VAZIA : CONTINUACAO_MEIO;

                sb.append(conectorGrupo)
                        .append("<-> incerto ")
                        .append(TRACO_HORIZONTAL).append(" ")
                        .append(grupo.getKey())
                        .append("\n");

                List<TopologyEngine.ConexaoLldpUpstream> filhosGrupo = grupo.getValue();
                for (int j = 0; j < filhosGrupo.size(); j++) {
                    TopologyEngine.ConexaoLldpUpstream u = filhosGrupo.get(j);
                    boolean ultimoFilho = (j == filhosGrupo.size() - 1);
                    String conFilho = prefixoGrupo + (ultimoFilho ? RAMO_FIM : RAMO_MEIO);
                    String prefNetos = prefixoGrupo + (ultimoFilho ? CONTINUACAO_VAZIA : CONTINUACAO_MEIO);

                    sb.append(conFilho)
                            .append("<-> ")
                            .append(u.portaLocalFilho())
                            .append(" ").append(TRACO_HORIZONTAL).append(" ")
                            .append(rotuloVisualSwitch(u.switchFilho()))
                            .append("\n");

                    if (coresJaExpandidos.add(u.switchFilho().macNormalizado())) {
                        desenharFilhosArvore(u.switchFilho(), prefNetos, visitados, arestasImpressas, adjacencia, sb);
                    }
                }
            }

            if (plano.possuiSegmentoIncerto()) {
                boolean ultimoSeg = (++idxRaiz == totalRamosRaiz);
                String conectorSeg = ultimoSeg ? RAMO_FIM : RAMO_MEIO;
                String prefixoSeg = ultimoSeg ? CONTINUACAO_VAZIA : CONTINUACAO_MEIO;

                sb.append(conectorSeg)
                        .append("<-> incerto ")
                        .append(TRACO_HORIZONTAL).append(" ")
                        .append(rotuloSegmentoIncerto(plano))
                        .append("\n");

                List<TopologyEngine.ConexaoGateway> segs = plano.conexoesViaSegmento();
                for (int j = 0; j < segs.size(); j++) {
                    TopologyEngine.ConexaoGateway cg = segs.get(j);
                    boolean ultimoFilho = (j == segs.size() - 1);
                    String conFilho = prefixoSeg + (ultimoFilho ? RAMO_FIM : RAMO_MEIO);
                    String prefNetos = prefixoSeg + (ultimoFilho ? CONTINUACAO_VAZIA : CONTINUACAO_MEIO);

                    sb.append(conFilho)
                            .append("<-> ")
                            .append(cg.porta())
                            .append(" ").append(TRACO_HORIZONTAL).append(" ")
                            .append(rotuloVisualSwitch(cg.switchCore()))
                            .append("\n");

                    if (coresJaExpandidos.add(cg.switchCore().macNormalizado())) {
                        desenharFilhosArvore(cg.switchCore(), prefNetos, visitados, arestasImpressas, adjacencia, sb);
                    }
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
    static Map<String, List<Enlace>> construirGrafoEnlacesDiretos(
            TopologyEngine engine,
            List<SwitchIdentity> switches
    ) {
        Map<String, List<Enlace>> adj = new LinkedHashMap<>();
        Map<String, Set<String>> portasConfirmadasLldp = new LinkedHashMap<>();
        for (SwitchIdentity sw : switches) {
            String id = sw.macNormalizado();
            adj.put(id, new ArrayList<>());
            portasConfirmadasLldp.put(id, new LinkedHashSet<>());
        }

        // Fase 1: Enlaces físicos diretos confirmados por LLDP entre equipamentos coletados
        for (SwitchIdentity origem : switches) {
            String idOrigem = origem.macNormalizado();
            for (TopologyEngine.VizinhoLldpClassificado viz : engine.vizinhosLldpDeInteresse(idOrigem)) {
                String portaLocal = viz.neighbor().localPort();
                if (portaLocal == null || portaLocal.isBlank()) continue;

                if (viz.categoria() == CategoriaInteresse.SWITCH_OU_OLT_EXTERNO
                        || viz.categoria() == CategoriaInteresse.ROTEADOR_UPLINK) {
                    portasConfirmadasLldp.get(idOrigem).add(portaLocal.toUpperCase());
                    continue;
                }

                if (viz.categoria() != CategoriaInteresse.EQUIPAMENTO_COLETADO || viz.switchColetado() == null) {
                    continue;
                }
                SwitchIdentity direto = viz.switchColetado();
                String idDireto = direto.macNormalizado();
                if (idDireto.equals(idOrigem)) continue;

                String portaRem = resolverPortaRemotaLldp(engine, idOrigem, portaLocal, viz.neighbor(), idDireto);

                if (engine.ehPortaDeEntradaGateway(idOrigem, portaLocal)
                        && engine.ehNoNaFronteiraGateway(idDireto)
                        && ("?".equals(portaRem) || engine.ehPortaDeEntradaGateway(idDireto, portaRem))) {
                    continue;
                }

                portasConfirmadasLldp.get(idOrigem).add(portaLocal.toUpperCase());
                adicionarEnlaceSeAusente(adj.get(idOrigem), new Enlace(portaLocal, portaRem, direto));
                if (adj.containsKey(idDireto)) {
                    if (!"?".equals(portaRem)) {
                        portasConfirmadasLldp.get(idDireto).add(portaRem.toUpperCase());
                    }
                    adicionarEnlaceSeAusente(adj.get(idDireto), new Enlace(portaRem, portaLocal, origem));
                }
            }
        }

        // Fase 2: Correlação MAC + ARP para equipamentos/portas sem vizinho LLDP coletado
        for (SwitchIdentity origem : switches) {
            String idOrigem = origem.macNormalizado();
            Set<String> confirmadas = portasConfirmadasLldp.getOrDefault(idOrigem, Set.of());
            Map<String, TopologyEngine.PortaAnalise> portas = engine.analisarPortas(idOrigem);
            List<String> portasOrd = new ArrayList<>(portas.keySet());
            portasOrd.sort(RelatorioTopologia::compararPortas);

            for (String porta : portasOrd) {
                if (confirmadas.contains(porta.toUpperCase())) continue;

                TopologyEngine.PortaAnalise analise = portas.get(porta);
                if (analise.ehPortaDeAcesso()) continue;

                TopologyEngine.ResultadoInferencia r = engine.inferirVizinhoDireto(idOrigem, analise.switchesVisiveis());
                if (r.tipo() == TopologyEngine.ResultadoInferencia.Tipo.DIRETO
                        || r.tipo() == TopologyEngine.ResultadoInferencia.Tipo.DIRETO_PARCIAL) {
                    SwitchIdentity direto = r.candidatos().get(0);
                    String idDireto = direto.macNormalizado();
                    Optional<String> portaRemOpt = engine.portaRemota(idOrigem, idDireto);
                    String portaRem = portaRemOpt.orElse("?");

                    if (engine.ehPortaDeEntradaGateway(idOrigem, porta)
                            && engine.ehNoNaFronteiraGateway(idDireto)
                            && (portaRemOpt.isEmpty() || engine.ehPortaDeEntradaGateway(idDireto, portaRemOpt.get()))) {
                        continue;
                    }

                    if (portaRemOpt.isEmpty()
                            && !engine.analisarPortas(idDireto).isEmpty()
                            && (analise.hostsDesconhecidos() >= 3
                            || (analise.hostsDesconhecidos() >= 1 && engine.possuiInfraestruturaSemAcesso()))) {
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

    static List<LoopDetector.ArestaFisica> extrairArestasFisicas(TopologyEngine engine) {
        List<SwitchIdentity> switches = new ArrayList<>(engine.switchesRegistrados());
        if (switches.size() < 2) {
            return List.of();
        }
        Map<String, List<Enlace>> adj = construirGrafoEnlacesDiretos(engine, switches);
        List<ParEnlace> pares = extrairParesUnicos(switches, adj);
        List<LoopDetector.ArestaFisica> arestas = new ArrayList<>(pares.size());
        for (ParEnlace p : pares) {
            arestas.add(new LoopDetector.ArestaFisica(
                    p.swA().macNormalizado(),
                    p.portaA(),
                    p.swB().macNormalizado(),
                    p.portaB()
            ));
        }
        return arestas;
    }

    private static String resolverPortaRemotaLldp(
            TopologyEngine engine,
            String idOrigem,
            String portaLocal,
            LldpNeighbor lldp,
            String idDireto
    ) {
        // 1. Verifica se o vizinho remoto tem anúncio LLDP recíproco apontando para esta porta
        for (LldpNeighbor reverso : engine.lldpDe(idDireto)) {
            Optional<SwitchIdentity> revAlvo = tpDiscover.intelbras.parser.LldpInterestFilter
                    .resolverSwitchColetado(reverso, engine.switchesRegistrados());
            if (revAlvo.isPresent() && revAlvo.get().macNormalizado().equalsIgnoreCase(idOrigem)) {
                String pRevRem = ComwareParser.resolverMelhorPortaRemota(reverso.remotePort(), reverso.remotePortDescription());
                if (pRevRem.isBlank() || pRevRem.equalsIgnoreCase(portaLocal)) {
                    if (reverso.localPort() != null && !reverso.localPort().isBlank()) {
                        return reverso.localPort();
                    }
                }
            }
        }
        // 2. Usa remotePort / remotePortDescription do próprio LLDP quando não for apenas um MAC cru
        String melhor = ComwareParser.resolverMelhorPortaRemota(lldp.remotePort(), lldp.remotePortDescription());
        if (!melhor.isBlank() && !MacUtils.ehMacValido(MacUtils.normalizar(melhor))) {
            return melhor;
        }
        // 3. Fallback na correlação MAC
        return engine.portaRemota(idOrigem, idDireto).orElse(!melhor.isBlank() ? melhor : "?");
    }

    private static void formatarVizinhosExternosLldp(
            TopologyEngine engine,
            List<SwitchIdentity> switches,
            GatewayInfo gateway,
            StringBuilder sb
    ) {
        List<String> linhas = new ArrayList<>();
        for (SwitchIdentity sw : switches) {
            for (TopologyEngine.VizinhoLldpClassificado v : engine.vizinhosLldpExternosDeInteresse(sw.macNormalizado())) {
                if (v.categoria() == CategoriaInteresse.ROTEADOR_UPLINK && gateway != null) {
                    boolean mesmoGwIp = gateway.ip() != null && gateway.ip().equals(v.neighbor().managementIp());
                    boolean mesmoGwMac = gateway.mac() != null
                            && v.neighbor().possuiChassisMacValido()
                            && gateway.macNormalizado().equalsIgnoreCase(v.neighbor().chassisMacNormalizado());
                    if (mesmoGwIp || mesmoGwMac) {
                        continue; // Já exibido como Gateway principal na topologia
                    }
                }
                String pLocal = v.neighbor().localPort().isBlank() ? "?" : v.neighbor().localPort();
                String pRem = ComwareParser.resolverMelhorPortaRemota(
                        v.neighbor().remotePort(), v.neighbor().remotePortDescription());
                String pRemStr = pRem.isBlank() ? "?" : pRem;
                String ipStr = !v.neighbor().managementIp().isBlank() ? " (" + v.neighbor().managementIp() + ")" : "";
                String tipo = switch (v.categoria()) {
                    case ROTEADOR_UPLINK -> "Roteador Uplink";
                    case SWITCH_OU_OLT_EXTERNO -> "Switch/OLT Externo (sem coleta CLI)";
                    case ACCESS_POINT -> "Access Point";
                    case RADIO_INTELBRAS -> "Rádio Intelbras";
                    default -> "Externo";
                };
                linhas.add(String.format("%s [%s] <-> [%s] %s%s [%s]",
                        rotuloVisualSwitch(sw), pLocal, pRemStr, v.neighbor().rotuloExibicao(), ipStr, tipo));
            }
        }
        if (linhas.isEmpty()) {
            return;
        }
        sb.append("\n====================================================================================================\n");
        sb.append("VIZINHOS EXTERNOS DE INTERESSE (LLDP: ROTEADOR / SWITCH EXTERNO / APs / RÁDIOS INTELBRAS)\n");
        sb.append("====================================================================================================\n");
        for (String linha : linhas) {
            sb.append(linha).append("\n");
        }
    }

    private static void formatarPendenciasSemAcesso(TopologyEngine engine, StringBuilder sb) {
        List<TopologyEngine.EquipamentoSemAcesso> pendencias = engine.equipamentosSemAcesso();
        if (pendencias.isEmpty()) {
            return;
        }
        sb.append("\n====================================================================================================\n");
        sb.append("EQUIPAMENTOS SEM ACESSO CLI / PENDÊNCIAS DE COLETA\n");
        sb.append("====================================================================================================\n");
        for (TopologyEngine.EquipamentoSemAcesso eq : pendencias) {
            String macStr = eq.macNormalizado().isBlank() ? "" : " (" + Serie3000Parser.formatarMacPadrao(eq.macNormalizado()) + ")";
            sb.append(String.format(" - %s [%s]%s | Categoria: %s | Situação: %s%n",
                    eq.rotuloModelo(), eq.ipBase(), macStr, eq.categoria(), eq.descricaoMotivo()));
        }
    }

    private static String rotuloSegmentoIncerto(TopologyEngine.PlanoGateway plano) {
        List<TopologyEngine.EquipamentoSemAcesso> suspeitos = plano.switchesIntermediariosSuspeitos();
        if (suspeitos == null || suspeitos.isEmpty()) {
            return "Segmento L2 / Switch(es) sem acesso CLI";
        }
        List<String> nomes = new ArrayList<>();
        int lim = Math.min(3, suspeitos.size());
        for (int i = 0; i < lim; i++) {
            TopologyEngine.EquipamentoSemAcesso eq = suspeitos.get(i);
            nomes.add(eq.rotuloModelo() + " (" + eq.ipBase() + ")");
        }
        String sufixo = suspeitos.size() > 3 ? " + " + (suspeitos.size() - 3) + " outro(s)" : "";
        return "Segmento L2 sem acesso [" + String.join(", ", nomes) + sufixo + "]";
    }

    private static String rotuloVizinhoLldp(LldpNeighbor n) {
        String ip = !n.managementIp().isBlank() ? " (" + n.managementIp() + ")" : "";
        return n.rotuloExibicao() + ip;
    }

    private static String resolverPortaRemotaCurta(LldpNeighbor n) {
        String pRem = ComwareParser.resolverMelhorPortaRemota(n.remotePort(), n.remotePortDescription());
        return pRem.isBlank() ? "?" : pRem;
    }

    private static String rotuloEquipamentoSemAcesso(TopologyEngine.EquipamentoSemAcesso eq) {
        return eq.rotuloModelo() + " (" + eq.ipBase() + ") [" + eq.motivo() + "]";
    }

    private static List<LoopDiagnostic> diagnosticarLoopsParaPares(
            TopologyEngine engine,
            List<ParEnlace> pares
    ) {
        List<LoopDetector.ArestaFisica> arestas = new ArrayList<>(pares.size());
        for (ParEnlace p : pares) {
            arestas.add(new LoopDetector.ArestaFisica(
                    p.swA().macNormalizado(), p.portaA(), p.swB().macNormalizado(), p.portaB()));
        }
        return engine.diagnosticarLoops(arestas);
    }

    private static void formatarDiagnosticoLoops(
            List<SwitchIdentity> switches,
            List<LoopDiagnostic> loops,
            StringBuilder sb
    ) {
        if (loops.isEmpty()) {
            return;
        }

        Map<String, SwitchIdentity> porId = new LinkedHashMap<>();
        for (SwitchIdentity sw : switches) {
            porId.put(sw.macNormalizado(), sw);
        }

        sb.append("\n====================================================================================================\n");
        sb.append("[!] DIAGNÓSTICO DE LOOPS E REDUNDÂNCIAS (SOMENTE LEITURA)\n");
        sb.append("====================================================================================================\n");
        for (LoopDiagnostic d : loops) {
            String tag = d.severidade() == LoopDiagnostic.Severidade.CRITICO ? "[CRÍTICO / LOOP]" : "[AVISO / STP]";
            SwitchIdentity swOrig = porId.get(d.switchOrigemId());
            String origStr = swOrig != null ? rotuloVisualSwitch(swOrig) : d.switchOrigemId();
            String portaOrig = d.portaOrigem().isBlank() ? "?" : d.portaOrigem();
            String destinoStr = "";
            if (!d.switchDestinoId().isBlank()) {
                SwitchIdentity swDest = porId.get(d.switchDestinoId());
                String destNome = swDest != null ? rotuloVisualSwitch(swDest) : d.switchDestinoId();
                String pDest = d.portaDestino().isBlank() ? "?" : d.portaDestino();
                destinoStr = " <-> " + destNome + " [" + pDest + "]";
            }
            sb.append(String.format("%s %s | %s [%s]%s%n", tag, d.tipo(), origStr, portaOrig, destinoStr));
            sb.append("   Evidência: ").append(d.evidencia()).append("\n");
            if (!d.acaoRecomendada().isBlank()) {
                sb.append("   Sugestão : ").append(d.acaoRecomendada()).append("\n");
            }
        }
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
        if (u.startsWith("GPON") || u.startsWith("EPON") || u.startsWith("PON") || u.startsWith("P0")
                || u.startsWith("ATH") || u.startsWith("WLAN") || u.startsWith("WIFI")) return 4;
        if (u.startsWith("BAGG") || u.startsWith("AGG") || u.startsWith("BRIDGE")) return 5;
        return 6;
    }
}

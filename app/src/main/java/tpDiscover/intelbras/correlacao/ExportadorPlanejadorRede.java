package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.GatewayInfo;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.LldpNeighbor.CategoriaInteresse;
import tpDiscover.intelbras.modelos.LoopDiagnostic;
import tpDiscover.intelbras.modelos.LoopDiagnostic.Severidade;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.OuiLookup;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.ComwareParser;
import tpDiscover.intelbras.parser.Serie3000Parser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Gerador exclusivo do relatório executivo em PDF da topologia de rede:
 *
 * - Consolida em um ÚNICO arquivo PDF de alta resolução todo o cenário da rede coletada:
 *   1) Cabeçalho corporativo e cards de resumo executivo (KPIs da rede).
 *   2) Diagrama visual da topologia em alta resolução (SVG com ícones Packet Tracer 3D,
 *      cabos com distinção de cobre/fibra óptica, LEDs de enlace e rótulos de portas/IPs).
 *   3) Tabela detalhada de Enlaces Físicos Par a Par (origem, destino, portas, tipo de cabo).
 *   4) Estrutura hierárquica da topologia em árvore.
 *   5) Inventário técnico de cada equipamento (modelo, serial, MAC-base, IP e portas conectadas).
 *   6) Tabela de dispositivos clientes e vizinhos descobertos via ARP com identificação OUI.
 *
 * Utiliza o navegador headless padrão do Windows (Microsoft Edge ou Chrome) para renderização
 * nativa, descartando arquivos intermediários (HTML/JSON/TXT) e deixando a pasta de saída limpa.
 */
public class ExportadorPlanejadorRede {

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");
    private static final DateTimeFormatter DATA_HORA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    public record ResultadoExportacao(Path caminhoJson, Path caminhoHtml, Path caminhoPdf) {
        public boolean possuiPdf() {
            return caminhoPdf != null && Files.exists(caminhoPdf);
        }
    }

    private record Posicao(double x, double y) {}

    private record NodeInfo(
            String id,
            String tipo,        // "router", "switch", "ap", "olt"
            String nome,
            String modelo,
            String ip,
            String macBase,
            String serial,
            int portCount,
            int sfpCount,
            SwitchIdentity switchIdentity,
            GatewayInfo gatewayInfo
    ) {}

    private record LinkInfo(
            String id,
            String devA,
            String portA,
            String devB,
            String portB,
            String cable,       // "cat6", "fibra"
            String label,
            String status       // "normal", "stp-bloqueado", "loop-critico"
    ) {}

    /**
     * Gera exclusivamente o arquivo PDF consolidado com todas as informações do cenário.
     * Arquivos intermediários são removidos automaticamente.
     */
    public static Path exportarPdf(TopologyEngine engine, Path pastaDestino) {
        if (engine == null) {
            return null;
        }
        List<SwitchIdentity> switches = new ArrayList<>(engine.switchesRegistrados());
        if (switches.isEmpty() && engine.equipamentosSemAcesso().isEmpty()) {
            return null;
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String dataFormatada = LocalDateTime.now().format(DATA_HORA_BR);
        String nomeBase = "topologia_" + timestamp;

        Path dir = (pastaDestino != null) ? pastaDestino : Path.of(".");
        Path caminhoPdf = dir.resolve(nomeBase + ".pdf");

        Path tempHtml = null;
        try {
            tempHtml = Files.createTempFile("topologia_relatorio_", ".html");
            String htmlConteudo = gerarHtmlParaInspecao(engine, nomeBase, dataFormatada);
            Files.writeString(tempHtml, htmlConteudo, StandardCharsets.UTF_8);

            // Converte para PDF via navegador headless
            Path pdfGerado = gerarPdfViaNavegador(tempHtml, caminhoPdf);
            if (pdfGerado != null && Files.exists(pdfGerado) && Files.size(pdfGerado) > 0) {
                return pdfGerado;
            } else {
                // Fallback de contingência caso não haja Edge/Chrome: salva o HTML diretamente na pasta de saída
                Path fallbackHtml = dir.resolve(nomeBase + ".html");
                Files.copy(tempHtml, fallbackHtml);
                System.out.println("[INFO] Navegador headless não detectado para PDF direto; relatório salvo em HTML: " + fallbackHtml);
                return fallbackHtml;
            }
        } catch (IOException e) {
            System.err.println("[AVISO] Falha ao gerar relatório visual em PDF: " + e.getMessage());
            return null;
        } finally {
            if (tempHtml != null) {
                try {
                    Files.deleteIfExists(tempHtml);
                } catch (IOException ignored) {}
            }
        }
    }

    static String gerarHtmlParaInspecao(TopologyEngine engine) {
        return gerarHtmlParaInspecao(
                engine,
                "topologia_" + LocalDateTime.now().format(TIMESTAMP_FMT),
                LocalDateTime.now().format(DATA_HORA_BR));
    }

    private static String gerarHtmlParaInspecao(TopologyEngine engine, String nomeBase, String dataFormatada) {
        List<SwitchIdentity> switches = new ArrayList<>(engine.switchesRegistrados());
        TopologyEngine.PlanoGateway plano = engine.analisarPlanoGateway();
        List<TopologyEngine.ConexaoPassiva> passivas = engine.resolverConexoesPassivasSemAcesso();
        List<TopologyEngine.EquipamentoSemAcesso> pendencias = engine.equipamentosSemAcesso();

        Map<String, List<RelatorioTopologia.Enlace>> adjacencia =
                RelatorioTopologia.construirGrafoEnlacesDiretos(engine, switches);
        List<RelatorioTopologia.ParEnlace> paresUnicos =
                RelatorioTopologia.extrairParesUnicos(switches, adjacencia);

        List<LoopDetector.ArestaFisica> arestas = new ArrayList<>(paresUnicos.size());
        for (RelatorioTopologia.ParEnlace p : paresUnicos) {
            arestas.add(new LoopDetector.ArestaFisica(
                    p.swA().macNormalizado(), p.portaA(), p.swB().macNormalizado(), p.portaB()));
        }
        List<LoopDiagnostic> loops = engine.diagnosticarLoops(arestas);

        // Mapeia nós da rede
        List<NodeInfo> nos = new ArrayList<>();
        NodeInfo noGateway = null;
        GatewayInfo gw = plano.gateway();
        if (gw != null) {
            String gwNome = (gw.ip() != null && !gw.ip().isBlank()) ? ("Gateway " + gw.ip()) : "Roteador / Gateway";
            String gwModelo = gw.rotuloVisual();
            String gwIp = gw.ip() != null ? gw.ip() : "";
            noGateway = new NodeInfo("dev-gw", "router", gwNome, gwModelo, gwIp, gw.mac(), "N/D", 5, 0, null, gw);
            nos.add(noGateway);
        }

        for (SwitchIdentity sw : switches) {
            String tipo = detectarTipoEquipamento(sw);
            String nome = RelatorioTopologia.nomeExibicaoSwitch(sw);
            String modelo = (sw.productId() != null && !sw.productId().isBlank()) ? sw.productId() : sw.deviceName();
            String ip = sw.ip() != null ? sw.ip() : "";
            String mac = sw.macAddress() != null ? sw.macAddress() : "N/D";
            String serial = sw.serialNumber() != null ? sw.serialNumber() : "N/D";
            int ports = estimarPortas(modelo, tipo);
            int sfp = estimarSfp(modelo, tipo);
            nos.add(new NodeInfo("dev-" + sw.macNormalizado(), tipo, nome, modelo, ip, mac, serial, ports, sfp, sw, null));
        }

        // Mapeia enlaces principais
        List<LinkInfo> links = new ArrayList<>();
        int linkSeq = 1;

        if (noGateway != null) {
            for (TopologyEngine.ConexaoGateway cg : plano.conexoesDiretas()) {
                String swId = cg.switchCore().macNormalizado();
                String devRemoto = "dev-" + swId;
                String portGw = "LAN" + (links.size() + 1);
                String statusGw = determinarStatusEnlace(noGateway.id(), portGw, swId, cg.porta(), engine, loops);
                links.add(new LinkInfo(
                        "link-" + (linkSeq++),
                        noGateway.id(),
                        portGw,
                        devRemoto,
                        cg.porta(),
                        "cat6",
                        "Uplink Gateway",
                        statusGw
                ));
            }
        }

        if (noGateway != null && plano.possuiSegmentoIncerto()) {
            String descSeg = plano.switchesIntermediariosSuspeitos().isEmpty()
                    ? "Domínio L2 intermediário sem visibilidade"
                    : plano.switchesIntermediariosSuspeitos().stream()
                            .limit(3)
                            .map(eq -> eq.rotuloModelo() + " (" + eq.ipBase() + ")")
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("Segmento sem acesso CLI");
            NodeInfo noSegmento = new NodeInfo(
                    "dev-segmento-incerto",
                    "segmento",
                    "Segmento L2",
                    descSeg,
                    "Sem acesso CLI",
                    "N/D",
                    "N/D",
                    0, 0, null, null);
            nos.add(noSegmento);
            links.add(new LinkInfo(
                    "link-" + (linkSeq++),
                    noGateway.id(),
                    "Uplink L2",
                    noSegmento.id(),
                    "Incerto",
                    "cat6",
                    "Segmento Intermediário",
                    "incerto"));
            for (TopologyEngine.ConexaoGateway cg : plano.conexoesViaSegmento()) {
                String swId = cg.switchCore().macNormalizado();
                links.add(new LinkInfo(
                        "link-" + (linkSeq++),
                        noSegmento.id(),
                        "Incerto",
                        "dev-" + swId,
                        cg.porta(),
                        ehPortaOptica(cg.porta()) ? "fibra" : "cat6",
                        "Uplink Incerto",
                        "incerto"));
            }
        }

        // Vizinhos externos filtrados por LLDP (switches externos + APs + rádios)
        Map<String, NodeInfo> externosLldpPorChave = new LinkedHashMap<>();
        Set<String> linksExternosViaPlano = new HashSet<>();

        if (noGateway != null && !plano.conexoesViaLldpExterno().isEmpty()) {
            Set<String> extConectadosAoGw = new HashSet<>();
            for (TopologyEngine.ConexaoLldpUpstream u : plano.conexoesViaLldpExterno()) {
                LldpNeighbor n = u.vizinhoExterno();
                String chaveUnica = chaveUnicaLldp(n, CategoriaInteresse.SWITCH_OU_OLT_EXTERNO);
                NodeInfo extNode = obterOuCriarNoLldpExterno(
                        externosLldpPorChave, nos, chaveUnica, n, CategoriaInteresse.SWITCH_OU_OLT_EXTERNO);
                if (extConectadosAoGw.add(extNode.id())) {
                    links.add(new LinkInfo(
                            "link-" + (linkSeq++),
                            noGateway.id(),
                            "Uplink L2",
                            extNode.id(),
                            "Uplink",
                            "cat6",
                            "Via Switch Externo",
                            "incerto"));
                }
                String pLocal = u.portaLocalFilho().isBlank() ? "?" : u.portaLocalFilho();
                String pRem = ComwareParser.resolverMelhorPortaRemota(n.remotePort(), n.remotePortDescription());
                if (pRem.isBlank()) pRem = "ETH";
                String cabo = ehPortaOptica(pLocal) || ehPortaOptica(pRem) ? "fibra" : "cat6";
                links.add(new LinkInfo(
                        "link-" + (linkSeq++),
                        extNode.id(),
                        pRem,
                        "dev-" + u.switchFilho().macNormalizado(),
                        pLocal,
                        cabo,
                        pRem + " <-> " + pLocal,
                        "normal"));
                linksExternosViaPlano.add(u.switchFilho().macNormalizado() + "|" + pLocal + "|" + chaveUnica);
            }
        }

        for (RelatorioTopologia.ParEnlace p : paresUnicos) {
            String idA = p.swA().macNormalizado();
            String idB = p.swB().macNormalizado();
            String devA = "dev-" + idA;
            String devB = "dev-" + idB;
            String cabo = ehPortaOptica(p.portaA()) || ehPortaOptica(p.portaB()) ? "fibra" : "cat6";
            String label = p.portaA() + " <-> " + p.portaB();
            String status = determinarStatusEnlace(idA, p.portaA(), idB, p.portaB(), engine, loops);
            links.add(new LinkInfo(
                    "link-" + (linkSeq++),
                    devA,
                    p.portaA(),
                    devB,
                    p.portaB(),
                    cabo,
                    label,
                    status
            ));
        }

        for (SwitchIdentity sw : switches) {
            String idOrigem = sw.macNormalizado();
            for (TopologyEngine.VizinhoLldpClassificado v : engine.vizinhosLldpExternosDeInteresse(idOrigem)) {
                LldpNeighbor n = v.neighbor();
                if (v.categoria() == CategoriaInteresse.ROTEADOR_UPLINK && noGateway != null) {
                    boolean mesmoIp = noGateway.ip() != null && !noGateway.ip().isBlank()
                            && noGateway.ip().equals(n.managementIp());
                    boolean mesmoMac = n.possuiChassisMacValido()
                            && noGateway.gatewayInfo() != null
                            && noGateway.gatewayInfo().macNormalizado().equalsIgnoreCase(n.chassisMacNormalizado());
                    if (mesmoIp || mesmoMac) {
                        continue;
                    }
                }
                String chaveUnica = chaveUnicaLldp(n, v.categoria());
                String pLocal = n.localPort().isBlank() ? "?" : n.localPort();
                if (linksExternosViaPlano.contains(idOrigem + "|" + pLocal + "|" + chaveUnica)) {
                    continue;
                }
                NodeInfo extNode = obterOuCriarNoLldpExterno(externosLldpPorChave, nos, chaveUnica, n, v.categoria());
                String pRem = ComwareParser.resolverMelhorPortaRemota(n.remotePort(), n.remotePortDescription());
                if (pRem.isBlank()) pRem = "ETH";
                String cabo = ehPortaOptica(pLocal) || ehPortaOptica(pRem) ? "fibra" : "cat6";
                String statusExt = determinarStatusEnlace(idOrigem, pLocal, extNode.id(), pRem, engine, loops);
                links.add(new LinkInfo(
                        "link-" + (linkSeq++),
                        "dev-" + idOrigem,
                        pLocal,
                        extNode.id(),
                        pRem,
                        cabo,
                        pLocal + " <-> " + pRem,
                        statusExt
                ));
            }
        }

        for (TopologyEngine.ConexaoPassiva cp : passivas) {
            TopologyEngine.EquipamentoSemAcesso eq = cp.equipamento();
            String tipoPassivo = switch (eq.categoria()) {
                case "AP" -> "ap";
                case "RADIO" -> "radio";
                case "OLT" -> "olt";
                case "ROUTER" -> "router";
                default -> "switch";
            };
            String macFmt = eq.macNormalizado().isBlank() ? "N/D" : Serie3000Parser.formatarMacPadrao(eq.macNormalizado());
            NodeInfo noPassivo = new NodeInfo(
                    "passivo-" + Integer.toHexString(eq.ipBase().hashCode()),
                    tipoPassivo,
                    eq.rotuloModelo(),
                    eq.descricaoMotivo(),
                    eq.ipBase(),
                    macFmt,
                    "Passivo",
                    estimarPortas(eq.rotuloModelo(), tipoPassivo),
                    0,
                    null,
                    null);
            nos.add(noPassivo);
            links.add(new LinkInfo(
                    "link-" + (linkSeq++),
                    "dev-" + cp.switchPai().macNormalizado(),
                    cp.portaPai(),
                    noPassivo.id(),
                    "ETH",
                    ehPortaOptica(cp.portaPai()) ? "fibra" : "cat6",
                    cp.portaPai() + " -> Passivo",
                    "normal"));
        }

        // Calcula posições visuais hierárquicas
        Map<String, Posicao> posicoes = calcularLayoutHierarquico(nos, links, noGateway);
        return gerarHtmlRelatorioCompleto(
                nomeBase, dataFormatada, nos, links, posicoes, engine, paresUnicos, plano, pendencias, noGateway, loops);
    }

    private static String chaveUnicaLldp(LldpNeighbor n, CategoriaInteresse categoria) {
        return (n.possuiChassisMacValido() ? n.chassisMacNormalizado()
                : (!n.managementIp().isBlank() ? n.managementIp() : n.rotuloExibicao()))
                + "|" + categoria;
    }

    private static NodeInfo obterOuCriarNoLldpExterno(
            Map<String, NodeInfo> externosLldpPorChave,
            List<NodeInfo> nos,
            String chaveUnica,
            LldpNeighbor n,
            CategoriaInteresse categoria
    ) {
        NodeInfo extNode = externosLldpPorChave.get(chaveUnica);
        if (extNode != null) {
            return extNode;
        }
        String tipoExt = switch (categoria) {
            case ROTEADOR_UPLINK -> "router";
            case SWITCH_OU_OLT_EXTERNO -> "switch";
            case ACCESS_POINT -> "ap";
            case RADIO_INTELBRAS -> "radio";
            default -> "ap";
        };
        String extId = "lldp-" + Integer.toHexString(chaveUnica.hashCode());
        String nomeExt = n.rotuloExibicao();
        String modeloExt = !n.systemDescription().isBlank()
                ? (n.systemDescription().length() > 48 ? n.systemDescription().substring(0, 48) + "..." : n.systemDescription())
                : categoria.name();
        String macExt = n.possuiChassisMacValido() ? n.chassisMacNormalizado() : (n.chassisIdRaw().isBlank() ? "N/D" : n.chassisIdRaw());
        extNode = new NodeInfo(extId, tipoExt, nomeExt, modeloExt, n.managementIp(), macExt, "LLDP", 1, 0, null, null);
        externosLldpPorChave.put(chaveUnica, extNode);
        nos.add(extNode);
        return extNode;
    }

    /**
     * Compatibilidade retroativa caso chamado por métodos de exportação em lote.
     */
    public static ResultadoExportacao exportar(TopologyEngine engine, Path pastaDestino) {
        Path pdf = exportarPdf(engine, pastaDestino);
        return new ResultadoExportacao(null, null, pdf);
    }

    private static String detectarTipoEquipamento(SwitchIdentity sw) {
        String texto = ((sw.productId() != null ? sw.productId() : "") + " " + (sw.deviceName() != null ? sw.deviceName() : "")).toLowerCase();
        if (texto.contains("olt") || texto.contains("gpon") || texto.contains("epon")
                || texto.contains("an6000") || texto.contains("an6001") || texto.contains("an5516")
                || texto.contains("8820") || texto.contains("4840") || texto.contains("4880")) {
            return "olt";
        }
        if (texto.matches(".*(\\bap\\b|access\\s*point|ap\\s*\\d).*")) {
            return "ap";
        }
        if (texto.contains("firewall")) {
            return "firewall";
        }
        if (texto.contains("router") || texto.contains("roteador")) {
            return "router";
        }
        return "switch";
    }

    private static int estimarPortas(String modelo, String tipo) {
        if ("ap".equals(tipo)) return 1;
        if ("olt".equals(tipo)) return 4;
        if ("router".equals(tipo)) return 5;
        if (modelo == null) return 24;
        String m = modelo.toLowerCase();
        if (m.contains("52") || m.contains("48")) return 48;
        if (m.contains("28") || m.contains("24")) return 24;
        if (m.contains("10") || m.contains("8")) return 8;
        return 24;
    }

    private static int estimarSfp(String modelo, String tipo) {
        if ("ap".equals(tipo) || "router".equals(tipo)) return 0;
        if ("olt".equals(tipo)) return 2;
        if (modelo == null) return 2;
        String m = modelo.toLowerCase();
        if (m.contains("28") || m.contains("52") || m.contains("pb") || m.contains("pa")) return 4;
        return 2;
    }

    private static boolean ehPortaOptica(String porta) {
        if (porta == null) return false;
        String p = porta.toUpperCase();
        return p.contains("SFP") || p.contains("FO") || p.contains("PON") || p.contains("OPT");
    }

    private enum LadoAnexo { TOPO, BASE, ESQUERDA, DIREITA }

    private record CaixaProtegida(double minX, double minY, double maxX, double maxY) {
        boolean colideCom(CaixaProtegida outra, double margem) {
            return !(maxX + margem <= outra.minX
                    || minX >= outra.maxX + margem
                    || maxY + margem <= outra.minY
                    || minY >= outra.maxY + margem);
        }
    }

    private record CurvaBezier(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3) {
        Posicao ponto(double t) {
            double u = 1.0 - t;
            double uu = u * u;
            double tt = t * t;
            double uuu = uu * u;
            double ttt = tt * t;
            double x = uuu * x0 + 3.0 * uu * t * x1 + 3.0 * u * tt * x2 + ttt * x3;
            double y = uuu * y0 + 3.0 * uu * t * y1 + 3.0 * u * tt * y2 + ttt * y3;
            return new Posicao(x, y);
        }

        Posicao tangente(double t) {
            double u = 1.0 - t;
            double dx = 3.0 * u * u * (x1 - x0) + 6.0 * u * t * (x2 - x1) + 3.0 * t * t * (x3 - x2);
            double dy = 3.0 * u * u * (y1 - y0) + 6.0 * u * t * (y2 - y1) + 3.0 * t * t * (y3 - y2);
            return new Posicao(dx, dy);
        }
    }

    private record PillPorta(String texto, double x, double y, double largura, double altura) {}

    private static Map<String, Posicao> calcularLayoutHierarquico(
            List<NodeInfo> nos,
            List<LinkInfo> links,
            NodeInfo noGateway
    ) {
        Map<String, Posicao> resultado = new LinkedHashMap<>();
        if (nos.isEmpty()) {
            return resultado;
        }

        Map<String, Set<String>> vizinhos = new LinkedHashMap<>();
        for (NodeInfo n : nos) {
            vizinhos.put(n.id(), new LinkedHashSet<>());
        }
        for (LinkInfo l : links) {
            if (!l.devA().equals(l.devB()) && vizinhos.containsKey(l.devA()) && vizinhos.containsKey(l.devB())) {
                vizinhos.get(l.devA()).add(l.devB());
                vizinhos.get(l.devB()).add(l.devA());
            }
        }

        String idRaizPrincipal = (noGateway != null)
                ? noGateway.id()
                : nos.stream()
                        .max(Comparator.comparingInt(n -> vizinhos.getOrDefault(n.id(), Set.of()).size()))
                        .orElse(nos.get(0))
                        .id();

        Set<String> visitados = new LinkedHashSet<>();
        Map<String, List<String>> filhosPorNo = new LinkedHashMap<>();
        List<String> raizesComponentes = new ArrayList<>();

        construirArvoreComponenteBfs(idRaizPrincipal, vizinhos, visitados, filhosPorNo);
        raizesComponentes.add(idRaizPrincipal);

        // Componentes desconectados do gateway ficam agrupados à parte, ordenados por maior conectividade
        List<NodeInfo> restantes = new ArrayList<>();
        for (NodeInfo n : nos) {
            if (!visitados.contains(n.id())) {
                restantes.add(n);
            }
        }
        restantes.sort((a, b) -> Integer.compare(
                vizinhos.getOrDefault(b.id(), Set.of()).size(),
                vizinhos.getOrDefault(a.id(), Set.of()).size()));
        for (NodeInfo cand : restantes) {
            if (!visitados.contains(cand.id())) {
                construirArvoreComponenteBfs(cand.id(), vizinhos, visitados, filhosPorNo);
                raizesComponentes.add(cand.id());
            }
        }

        int maxFilhosPorNo = filhosPorNo.values().stream().mapToInt(List::size).max().orElse(0);
        double slotW = (nos.size() > 10 || maxFilhosPorNo > 5) ? 168.0 : 196.0;
        double gapIrmaos = 16.0;
        double passoY = (maxFilhosPorNo >= 6) ? 245.0 : (maxFilhosPorNo >= 3 ? 215.0 : 200.0);
        double inicioY = 60.0;

        Map<String, Double> largurasSubarvore = new HashMap<>();
        for (String r : raizesComponentes) {
            calcularLarguraSubarvore(r, filhosPorNo, largurasSubarvore, slotW, gapIrmaos);
        }

        // Posiciona árvore principal centralizada sob seu pai
        double cursorMainX = 60.0;
        posicionarSubarvore(idRaizPrincipal, cursorMainX, 0, inicioY, passoY, gapIrmaos,
                filhosPorNo, largurasSubarvore, resultado);

        double mainMaxX = resultado.values().stream().mapToDouble(Posicao::x).max().orElse(60.0) + slotW;

        // Componentes isolados ficam organizados ao lado direito sem poluir a árvore principal
        if (raizesComponentes.size() > 1) {
            double xBaseIsolados = mainMaxX + 40.0;
            double cursorY = inicioY;
            for (int i = 1; i < raizesComponentes.size(); i++) {
                String r = raizesComponentes.get(i);
                Map<String, Posicao> posComp = new LinkedHashMap<>();
                posicionarSubarvore(r, xBaseIsolados, 0, cursorY, passoY, gapIrmaos,
                        filhosPorNo, largurasSubarvore, posComp);
                resultado.putAll(posComp);
                double compMaxY = posComp.values().stream().mapToDouble(Posicao::y).max().orElse(cursorY);
                cursorY = compMaxY + 140.0;
            }
        }

        double minX = resultado.values().stream().mapToDouble(Posicao::x).min().orElse(60.0);
        if (minX < 60.0) {
            double deltaX = 60.0 - minX;
            resultado.replaceAll((k, p) -> new Posicao(p.x() + deltaX, p.y()));
        }

        return resultado;
    }

    private static void construirArvoreComponenteBfs(
            String raizId,
            Map<String, Set<String>> vizinhos,
            Set<String> visitados,
            Map<String, List<String>> filhosPorNo
    ) {
        Queue<String> fila = new ArrayDeque<>();
        visitados.add(raizId);
        fila.add(raizId);
        while (!fila.isEmpty()) {
            String atual = fila.poll();
            List<String> filhos = filhosPorNo.computeIfAbsent(atual, k -> new ArrayList<>());
            for (String viz : vizinhos.getOrDefault(atual, Set.of())) {
                if (visitados.add(viz)) {
                    filhos.add(viz);
                    fila.add(viz);
                }
            }
        }
    }

    private static double calcularLarguraSubarvore(
            String id,
            Map<String, List<String>> filhosPorNo,
            Map<String, Double> cache,
            double slotW,
            double gapIrmaos
    ) {
        List<String> filhos = filhosPorNo.getOrDefault(id, List.of());
        if (filhos.isEmpty()) {
            cache.put(id, slotW);
            return slotW;
        }
        double soma = 0.0;
        for (int i = 0; i < filhos.size(); i++) {
            soma += calcularLarguraSubarvore(filhos.get(i), filhosPorNo, cache, slotW, gapIrmaos);
            if (i + 1 < filhos.size()) {
                soma += gapIrmaos;
            }
        }
        double total = Math.max(slotW, soma);
        cache.put(id, total);
        return total;
    }

    private static void posicionarSubarvore(
            String id,
            double leftX,
            int camada,
            double inicioY,
            double passoY,
            double gapIrmaos,
            Map<String, List<String>> filhosPorNo,
            Map<String, Double> largurasSubarvore,
            Map<String, Posicao> resultado
    ) {
        double y = inicioY + camada * passoY;
        List<String> filhos = filhosPorNo.getOrDefault(id, List.of());
        if (filhos.isEmpty()) {
            double largura = largurasSubarvore.getOrDefault(id, 180.0);
            resultado.put(id, new Posicao(leftX + (largura / 2.0) - 32.0, y));
            return;
        }
        double cursorX = leftX;
        double somaFilhos = 0.0;
        for (int i = 0; i < filhos.size(); i++) {
            somaFilhos += largurasSubarvore.getOrDefault(filhos.get(i), 180.0);
            if (i + 1 < filhos.size()) somaFilhos += gapIrmaos;
        }
        double larguraNode = largurasSubarvore.getOrDefault(id, somaFilhos);
        if (larguraNode > somaFilhos) {
            cursorX += (larguraNode - somaFilhos) / 2.0;
        }
        for (String filho : filhos) {
            double wf = largurasSubarvore.getOrDefault(filho, 180.0);
            posicionarSubarvore(filho, cursorX, camada + 1, inicioY, passoY, gapIrmaos,
                    filhosPorNo, largurasSubarvore, resultado);
            cursorX += wf + gapIrmaos;
        }
        double centroPrimeiro = resultado.get(filhos.get(0)).x() + 32.0;
        double centroUltimo = resultado.get(filhos.get(filhos.size() - 1)).x() + 32.0;
        resultado.put(id, new Posicao((centroPrimeiro + centroUltimo) / 2.0 - 32.0, y));
    }

    private record PontaConexao(int linkIdx, boolean ehA, double coordOposta, int parIdx) {}

    private record PedidoPillPorta(
            String texto,
            CurvaBezier curva,
            boolean partirDoInicio,
            int slotNoLado,
            int totalNoLado
    ) {}

    private static String construirDiagramaSvg(
            List<NodeInfo> nos,
            List<LinkInfo> links,
            Map<String, Posicao> posicoes,
            Map<String, String> alertaPorNodeDevId
    ) {
        Map<String, CaixaProtegida> caixasNos = new LinkedHashMap<>();
        List<CaixaProtegida> ocupadas = new ArrayList<>();
        for (NodeInfo n : nos) {
            Posicao p = posicoes.get(n.id());
            if (p == null) continue;
            double cx = p.x() + 32.0;
            boolean temAlertaTopo = alertaPorNodeDevId.containsKey(n.id());
            double lenNome = n.nome() != null ? n.nome().length() : 0;
            double lenIp = n.ip() != null ? n.ip().length() : 0;
            double halfText = Math.max(44.0, Math.max(lenNome * 3.8, lenIp * 3.4));
            CaixaProtegida box = new CaixaProtegida(
                    Math.min(p.x() - 4.0, cx - halfText - 4.0),
                    p.y() - (temAlertaTopo ? 16.0 : 4.0),
                    Math.max(p.x() + 68.0, cx + halfText + 4.0),
                    p.y() + 84.0
            );
            caixasNos.put(n.id(), box);
            ocupadas.add(box);
        }

        // Agrupa ligações paralelas entre o mesmo par de dispositivos e os lados de anexo de cada enlace
        Map<String, List<Integer>> gruposParalelos = new HashMap<>();
        for (int i = 0; i < links.size(); i++) {
            LinkInfo l = links.get(i);
            String k = (l.devA().compareTo(l.devB()) <= 0) ? (l.devA() + "|" + l.devB()) : (l.devB() + "|" + l.devA());
            gruposParalelos.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
        }
        int[] parIndex = new int[links.size()];
        int[] parTotal = new int[links.size()];
        for (List<Integer> grp : gruposParalelos.values()) {
            for (int k = 0; k < grp.size(); k++) {
                int idx = grp.get(k);
                parIndex[idx] = k;
                parTotal[idx] = grp.size();
            }
        }

        LadoAnexo[] ladoA = new LadoAnexo[links.size()];
        LadoAnexo[] ladoB = new LadoAnexo[links.size()];
        Map<String, List<PontaConexao>> buckets = new HashMap<>();

        for (int i = 0; i < links.size(); i++) {
            LinkInfo l = links.get(i);
            if (l.devA().equals(l.devB())) continue;
            Posicao pA = posicoes.get(l.devA());
            Posicao pB = posicoes.get(l.devB());
            if (pA == null || pB == null) continue;

            double dy = pB.y() - pA.y();
            double dx = pB.x() - pA.x();
            if (dy > 40.0) {
                ladoA[i] = LadoAnexo.BASE;
                ladoB[i] = LadoAnexo.TOPO;
            } else if (dy < -40.0) {
                ladoA[i] = LadoAnexo.TOPO;
                ladoB[i] = LadoAnexo.BASE;
            } else if (Math.abs(dx) <= 270.0) {
                ladoA[i] = dx >= 0 ? LadoAnexo.DIREITA : LadoAnexo.ESQUERDA;
                ladoB[i] = dx >= 0 ? LadoAnexo.ESQUERDA : LadoAnexo.DIREITA;
            } else {
                ladoA[i] = LadoAnexo.BASE;
                ladoB[i] = LadoAnexo.BASE;
            }

            double ordA = (ladoA[i] == LadoAnexo.BASE || ladoA[i] == LadoAnexo.TOPO) ? pB.x() : pB.y();
            double ordB = (ladoB[i] == LadoAnexo.BASE || ladoB[i] == LadoAnexo.TOPO) ? pA.x() : pA.y();
            buckets.computeIfAbsent(l.devA() + "#" + ladoA[i], x -> new ArrayList<>())
                    .add(new PontaConexao(i, true, ordA, parIndex[i]));
            buckets.computeIfAbsent(l.devB() + "#" + ladoB[i], x -> new ArrayList<>())
                    .add(new PontaConexao(i, false, ordB, parIndex[i]));
        }

        int[] slotA = new int[links.size()];
        int[] totalA = new int[links.size()];
        int[] slotB = new int[links.size()];
        int[] totalB = new int[links.size()];
        for (List<PontaConexao> bucket : buckets.values()) {
            bucket.sort(Comparator.comparingDouble(PontaConexao::coordOposta)
                    .thenComparingInt(PontaConexao::parIdx)
                    .thenComparingInt(PontaConexao::linkIdx));
            int tot = bucket.size();
            for (int s = 0; s < tot; s++) {
                PontaConexao pc = bucket.get(s);
                if (pc.ehA()) {
                    slotA[pc.linkIdx()] = s;
                    totalA[pc.linkIdx()] = tot;
                } else {
                    slotB[pc.linkIdx()] = s;
                    totalB[pc.linkIdx()] = tot;
                }
            }
        }

        double[] bounds = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (CaixaProtegida box : ocupadas) {
            expandirBounds(bounds, box);
        }

        StringBuilder svgLinks = new StringBuilder();
        StringBuilder svgBadgesCentro = new StringBuilder();
        List<PedidoPillPorta> pedidosPills = new ArrayList<>();

        for (int i = 0; i < links.size(); i++) {
            LinkInfo l = links.get(i);
            Posicao pA = posicoes.get(l.devA());
            Posicao pB = posicoes.get(l.devB());
            if (pA == null || pB == null) continue;

            boolean ehLoop = "loop-critico".equals(l.status());
            boolean ehStpBlk = "stp-bloqueado".equals(l.status());
            boolean ehIncerto = "incerto".equals(l.status());
            String corCabo = ehLoop ? "#dc2626" : ((ehStpBlk || ehIncerto) ? "#d97706" : ("fibra".equals(l.cable()) ? "#f0a020" : "#8b5cf6"));
            String dash = ehLoop ? " stroke-dasharray=\"6 3\"" : ((ehStpBlk || ehIncerto) ? " stroke-dasharray=\"5 4\""
                    : ("fibra".equals(l.cable()) ? " stroke-dasharray=\"8 4\"" : ""));
            double larguraStroke = ehLoop ? 3.2 : (ehStpBlk ? 2.6 : (ehIncerto ? 2.1 : 2.3));
            String corLed = ehLoop ? "#dc2626" : ((ehStpBlk || ehIncerto) ? "#f59e0b" : "#22c55e");

            if (l.devA().equals(l.devB())) {
                // Auto-loop visível ao lado direito do equipamento
                double xBase = pA.x() + 66.0;
                double yTop = pA.y() + 14.0;
                double yBot = pA.y() + 34.0;
                CurvaBezier selfCurva = new CurvaBezier(xBase, yTop, xBase + 46.0, yTop - 16.0, xBase + 46.0, yBot + 16.0, xBase, yBot);
                svgLinks.append(String.format(Locale.US,
                        "<path d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\" stroke-linecap=\"round\"%s/>%n",
                        selfCurva.x0(), selfCurva.y0(), selfCurva.x1(), selfCurva.y1(),
                        selfCurva.x2(), selfCurva.y2(), selfCurva.x3(), selfCurva.y3(), corCabo, larguraStroke, dash));
                svgLinks.append(String.format(Locale.US,
                        "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"3.2\" fill=\"%s\" stroke=\"#fff\" stroke-width=\"1\"/>%n",
                        xBase, yTop, corLed));
                svgLinks.append(String.format(Locale.US,
                        "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"3.2\" fill=\"%s\" stroke=\"#fff\" stroke-width=\"1\"/>%n",
                        xBase, yBot, corLed));
                expandirBoundsPonto(bounds, xBase + 52.0, yTop - 10.0);
                expandirBoundsPonto(bounds, xBase + 52.0, yBot + 10.0);
                if (l.portA() != null && !l.portA().isBlank()) {
                    pedidosPills.add(new PedidoPillPorta(l.portA(), selfCurva, true, 0, 1));
                }
                continue;
            }

            Posicao ancA = calcularAncora(pA, alertaPorNodeDevId.containsKey(l.devA()), ladoA[i], slotA[i], totalA[i]);
            Posicao ancB = calcularAncora(pB, alertaPorNodeDevId.containsKey(l.devB()), ladoB[i], slotB[i], totalB[i]);
            double shiftPar = (parTotal[i] > 1) ? ((parIndex[i] - (parTotal[i] - 1) / 2.0) * 34.0) : 0.0;

            CurvaBezier curva;
            if ((ladoA[i] == LadoAnexo.BASE && ladoB[i] == LadoAnexo.TOPO)
                    || (ladoA[i] == LadoAnexo.TOPO && ladoB[i] == LadoAnexo.BASE)) {
                double dy = Math.abs(ancB.y() - ancA.y());
                double ctrlY = Math.max(44.0, dy * 0.48);
                double sA = (ladoA[i] == LadoAnexo.BASE) ? 1.0 : -1.0;
                double sB = (ladoB[i] == LadoAnexo.BASE) ? 1.0 : -1.0;
                curva = new CurvaBezier(
                        ancA.x(), ancA.y(),
                        ancA.x() + shiftPar, ancA.y() + sA * ctrlY,
                        ancB.x() + shiftPar, ancB.y() + sB * ctrlY,
                        ancB.x(), ancB.y()
                );
            } else if (ladoA[i] == LadoAnexo.BASE && ladoB[i] == LadoAnexo.BASE) {
                double arcoY = Math.max(ancA.y(), ancB.y()) + 48.0 + Math.abs(shiftPar);
                curva = new CurvaBezier(ancA.x(), ancA.y(), ancA.x(), arcoY, ancB.x(), arcoY, ancB.x(), ancB.y());
                expandirBoundsPonto(bounds, ancA.x(), arcoY + 8.0);
                expandirBoundsPonto(bounds, ancB.x(), arcoY + 8.0);
            } else {
                double ctrlX = Math.max(36.0, Math.abs(ancB.x() - ancA.x()) * 0.42);
                double sA = (ladoA[i] == LadoAnexo.DIREITA) ? 1.0 : -1.0;
                double sB = (ladoB[i] == LadoAnexo.DIREITA) ? 1.0 : -1.0;
                curva = new CurvaBezier(
                        ancA.x(), ancA.y(),
                        ancA.x() + sA * ctrlX, ancA.y() + shiftPar,
                        ancB.x() + sB * ctrlX, ancB.y() + shiftPar,
                        ancB.x(), ancB.y()
                );
            }

            svgLinks.append(String.format(Locale.US,
                    "<path d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\" stroke-linecap=\"round\"%s/>%n",
                    curva.x0(), curva.y0(), curva.x1(), curva.y1(),
                    curva.x2(), curva.y2(), curva.x3(), curva.y3(), corCabo, larguraStroke, dash));

            svgLinks.append(String.format(Locale.US,
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"3.2\" fill=\"%s\" stroke=\"#fff\" stroke-width=\"1\"/>%n",
                    ancA.x(), ancA.y(), corLed));
            svgLinks.append(String.format(Locale.US,
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"3.2\" fill=\"%s\" stroke=\"#fff\" stroke-width=\"1\"/>%n",
                    ancB.x(), ancB.y(), corLed));

            if (ehLoop || ehStpBlk) {
                Posicao mid = curva.ponto(0.5);
                String pillCor = ehLoop ? "#dc2626" : "#d97706";
                String pillTxt = ehLoop ? "LOOP!" : "STP BLK";
                svgBadgesCentro.append(String.format(Locale.US,
                        "<rect x=\"%.1f\" y=\"%.1f\" width=\"42\" height=\"14\" rx=\"3\" fill=\"%s\"/>%n"
                                + "<text x=\"%.1f\" y=\"%.1f\" font-size=\"8.5\" font-weight=\"700\" text-anchor=\"middle\" fill=\"#ffffff\">%s</text>%n",
                        mid.x() - 21.0, mid.y() - 7.0, pillCor, mid.x(), mid.y() + 3.0, pillTxt));
                CaixaProtegida boxMid = new CaixaProtegida(mid.x() - 22.0, mid.y() - 8.0, mid.x() + 22.0, mid.y() + 8.0);
                ocupadas.add(boxMid);
                expandirBounds(bounds, boxMid);
            }

            if (l.portA() != null && !l.portA().isBlank() && !"-".equals(l.portA())) {
                pedidosPills.add(new PedidoPillPorta(l.portA(), curva, true, slotA[i], totalA[i]));
            }
            if (l.portB() != null && !l.portB().isBlank() && !"-".equals(l.portB())) {
                pedidosPills.add(new PedidoPillPorta(l.portB(), curva, false, slotB[i], totalB[i]));
            }
        }

        // Pontas unitárias (folhas/gateway) são posicionadas primeiro; hubs com leque de cabos fazem stagger depois
        pedidosPills.sort(Comparator.comparingInt(PedidoPillPorta::totalNoLado));
        StringBuilder svgPills = new StringBuilder();
        for (PedidoPillPorta req : pedidosPills) {
            PillPorta pill = posicionarPillPortaSemColisao(req, ocupadas);
            if (pill == null) continue;
            CaixaProtegida box = new CaixaProtegida(
                    pill.x() - pill.largura() / 2.0,
                    pill.y() - pill.altura() / 2.0,
                    pill.x() + pill.largura() / 2.0,
                    pill.y() + pill.altura() / 2.0
            );
            ocupadas.add(box);
            expandirBounds(bounds, box);
            svgPills.append(String.format(Locale.US,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"3.5\" fill=\"#ffffff\" fill-opacity=\"0.95\" stroke=\"#cbd5e1\" stroke-width=\"0.75\"/>%n"
                            + "<text x=\"%.1f\" y=\"%.1f\" font-size=\"8.8\" font-weight=\"700\" text-anchor=\"middle\" fill=\"#334155\">%s</text>%n",
                    box.minX(), box.minY(), pill.largura(), pill.altura(),
                    pill.x(), pill.y() + 3.0, escapeHtml(pill.texto())));
        }

        StringBuilder svgNodes = new StringBuilder();
        for (NodeInfo n : nos) {
            Posicao p = posicoes.get(n.id());
            if (p == null) continue;

            svgNodes.append(String.format(Locale.US, "<g transform=\"translate(%.1f,%.1f)\">%n", p.x(), p.y()));
            String alertaNo = alertaPorNodeDevId.get(n.id());
            if ("CRITICO".equals(alertaNo)) {
                svgNodes.append("<circle cx=\"32\" cy=\"24\" r=\"34\" fill=\"none\" stroke=\"#dc2626\" stroke-width=\"2.4\" stroke-dasharray=\"5 3\"/>\n");
            } else if ("AUTH_ERRO".equals(alertaNo)) {
                svgNodes.append("<circle cx=\"32\" cy=\"24\" r=\"33\" fill=\"none\" stroke=\"#d97706\" stroke-width=\"2.0\" stroke-dasharray=\"4 3\"/>\n");
            }
            svgNodes.append(desenharGlyphSvg(n.tipo(), n.modelo()));
            if ("CRITICO".equals(alertaNo)) {
                svgNodes.append("<rect x=\"12\" y=\"-12\" width=\"40\" height=\"13\" rx=\"3\" fill=\"#dc2626\"/>\n");
                svgNodes.append("<text x=\"32\" y=\"-3\" font-size=\"8.5\" font-weight=\"800\" text-anchor=\"middle\" fill=\"#ffffff\">! LOOP</text>\n");
            } else if ("STP".equals(alertaNo)) {
                svgNodes.append("<rect x=\"14\" y=\"-12\" width=\"36\" height=\"13\" rx=\"3\" fill=\"#d97706\"/>\n");
                svgNodes.append("<text x=\"32\" y=\"-3\" font-size=\"8.5\" font-weight=\"700\" text-anchor=\"middle\" fill=\"#ffffff\">STP BLK</text>\n");
            } else if ("AUTH_ERRO".equals(alertaNo)) {
                svgNodes.append("<rect x=\"11\" y=\"-12\" width=\"42\" height=\"13\" rx=\"3\" fill=\"#b45309\"/>\n");
                svgNodes.append("<text x=\"32\" y=\"-3\" font-size=\"8.5\" font-weight=\"800\" text-anchor=\"middle\" fill=\"#ffffff\">! AUTH</text>\n");
            } else if ("SEM_ACESSO".equals(alertaNo)) {
                svgNodes.append("<rect x=\"11\" y=\"-12\" width=\"42\" height=\"13\" rx=\"3\" fill=\"#64748b\"/>\n");
                svgNodes.append("<text x=\"32\" y=\"-3\" font-size=\"8.2\" font-weight=\"700\" text-anchor=\"middle\" fill=\"#ffffff\">SEM CLI</text>\n");
            }
            svgNodes.append(String.format(
                    "<text x=\"32\" y=\"62\" font-size=\"12\" font-weight=\"700\" text-anchor=\"middle\" fill=\"#0f172a\" stroke=\"#ffffff\" stroke-width=\"3\" paint-order=\"stroke\">%s</text>%n",
                    escapeHtml(n.nome())));
            if (n.ip() != null && !n.ip().isBlank()) {
                svgNodes.append(String.format(
                        "<text x=\"32\" y=\"75\" font-size=\"10.5\" font-weight=\"600\" text-anchor=\"middle\" fill=\"#2563eb\" stroke=\"#ffffff\" stroke-width=\"3\" paint-order=\"stroke\">%s</text>%n",
                        escapeHtml(n.ip())));
            }
            svgNodes.append("</g>\n");
        }

        if (!Double.isFinite(bounds[0])) {
            bounds[0] = 50.0; bounds[1] = 50.0; bounds[2] = 800.0; bounds[3] = 500.0;
        }
        double pad = 28.0;
        double vbX = bounds[0] - pad;
        double vbY = bounds[1] - pad;
        double w = (bounds[2] - bounds[0]) + pad * 2.0;
        double h = (bounds[3] - bounds[1]) + pad * 2.0;

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.US,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%%\" height=\"auto\" viewBox=\"%.1f %.1f %.1f %.1f\" font-family=\"Segoe UI, Arial, sans-serif\">%n",
                vbX, vbY, w, h));

        svg.append("""
          <defs>
            <linearGradient id="pt-blue" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4fa3e3"/><stop offset="1" stop-color="#1c5e9f"/></linearGradient>
            <linearGradient id="pt-blue-top" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#9ad0f7"/><stop offset="1" stop-color="#4fa3e3"/></linearGradient>
            <linearGradient id="pt-blue-side" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1a5490"/><stop offset="1" stop-color="#0e3862"/></linearGradient>
            <linearGradient id="pt-grey" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f5f7f9"/><stop offset="1" stop-color="#b4bdc8"/></linearGradient>
            <linearGradient id="pt-grey-side" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#a3adb9"/><stop offset="1" stop-color="#6c7886"/></linearGradient>
            <linearGradient id="pt-screen" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#6cc0f5"/><stop offset="1" stop-color="#1d5f9b"/></linearGradient>
            <linearGradient id="pt-cloud" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#c9ddf2"/></linearGradient>
            <linearGradient id="pt-red" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#e2604f"/><stop offset="1" stop-color="#a3291c"/></linearGradient>
            <linearGradient id="pt-dark" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#3b4555"/><stop offset="1" stop-color="#1f2630"/></linearGradient>
          </defs>
        """);

        svg.append(String.format(Locale.US,
                "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"#ffffff\" rx=\"8\"/>%n",
                vbX, vbY, w, h));
        svg.append(svgLinks);
        svg.append(svgNodes);
        svg.append(svgBadgesCentro);
        svg.append(svgPills);
        svg.append("</svg>");
        return svg.toString();
    }

    private static Posicao calcularAncora(
            Posicao posNo,
            boolean temBadgeTopo,
            LadoAnexo lado,
            int slot,
            int total
    ) {
        double cx = posNo.x() + 32.0;
        return switch (lado) {
            case BASE -> new Posicao(
                    cx + offsetDistribuido(slot, total, Math.min(13.0, 96.0 / Math.max(1, total))),
                    posNo.y() + 85.0
            );
            case TOPO -> new Posicao(
                    cx + offsetDistribuido(slot, total, Math.min(11.0, 56.0 / Math.max(1, total))),
                    posNo.y() - (temBadgeTopo ? 14.0 : 3.0)
            );
            case ESQUERDA -> new Posicao(
                    posNo.x() - 4.0,
                    posNo.y() + 24.0 + offsetDistribuido(slot, total, 9.0)
            );
            case DIREITA -> new Posicao(
                    posNo.x() + 68.0,
                    posNo.y() + 24.0 + offsetDistribuido(slot, total, 9.0)
            );
        };
    }

    private static double offsetDistribuido(int slot, int total, double passo) {
        if (total <= 1) return 0.0;
        return (slot - (total - 1) / 2.0) * passo;
    }

    private static PillPorta posicionarPillPortaSemColisao(PedidoPillPorta req, List<CaixaProtegida> ocupadas) {
        String txt = req.texto().trim();
        if (txt.isEmpty()) return null;
        double larg = Math.max(28.0, txt.length() * 5.6 + 10.0);
        double alt = 14.0;

        // Candidatos de distância ao longo da curva a partir da própria extremidade
        List<Double> dists = new ArrayList<>();
        if (req.totalNoLado() <= 1) {
            dists.add(0.16);
            dists.add(0.22);
            dists.add(0.28);
        } else {
            double[] tiers = {0.16, 0.30, 0.44};
            int tier = req.slotNoLado() % 3;
            dists.add(tiers[tier]);
            dists.add(tiers[(tier + 1) % 3]);
            dists.add(tiers[(tier + 2) % 3]);
        }
        for (double d = 0.14; d <= 0.56; d += 0.04) {
            dists.add(d);
        }

        double[] nudges = {0.0, 11.0, -11.0, 22.0, -22.0, 34.0, -34.0};
        PillPorta fallback = null;
        for (double nudge : nudges) {
            for (double d : dists) {
                double t = req.partirDoInicio() ? d : (1.0 - d);
                Posicao pt = req.curva().ponto(t);
                Posicao tg = req.curva().tangente(t);
                double norm = Math.hypot(tg.x(), tg.y());
                double nx = (norm > 1e-3) ? (-tg.y() / norm) : 0.0;
                double ny = (norm > 1e-3) ? (tg.x() / norm) : 0.0;

                double cx = pt.x() + nx * nudge;
                double cy = pt.y() + ny * nudge;
                PillPorta cand = new PillPorta(txt, cx, cy, larg, alt);
                if (fallback == null) {
                    fallback = cand;
                }
                CaixaProtegida box = new CaixaProtegida(cx - larg / 2.0, cy - alt / 2.0, cx + larg / 2.0, cy + alt / 2.0);
                boolean colide = false;
                for (CaixaProtegida occ : ocupadas) {
                    if (box.colideCom(occ, 3.0)) {
                        colide = true;
                        break;
                    }
                }
                if (!colide) {
                    return cand;
                }
            }
        }
        return fallback;
    }

    private static void expandirBounds(double[] bounds, CaixaProtegida box) {
        if (box.minX() < bounds[0]) bounds[0] = box.minX();
        if (box.minY() < bounds[1]) bounds[1] = box.minY();
        if (box.maxX() > bounds[2]) bounds[2] = box.maxX();
        if (box.maxY() > bounds[3]) bounds[3] = box.maxY();
    }

    private static void expandirBoundsPonto(double[] bounds, double x, double y) {
        if (x < bounds[0]) bounds[0] = x;
        if (y < bounds[1]) bounds[1] = y;
        if (x > bounds[2]) bounds[2] = x;
        if (y > bounds[3]) bounds[3] = y;
    }

    private static String determinarStatusEnlace(
            String idA,
            String portA,
            String idB,
            String portB,
            TopologyEngine engine,
            List<LoopDiagnostic> loops
    ) {
        if (engine.portaBloqueadaPorStp(idA, portA) || engine.portaBloqueadaPorStp(idB, portB)) {
            return "stp-bloqueado";
        }
        for (LoopDiagnostic d : loops) {
            boolean bateDireto = d.switchOrigemId().equalsIgnoreCase(idA)
                    && d.switchDestinoId().equalsIgnoreCase(idB)
                    && (d.portaOrigem().isBlank() || d.portaOrigem().equalsIgnoreCase(portA))
                    && (d.portaDestino().isBlank() || d.portaDestino().equalsIgnoreCase(portB));
            boolean bateReverso = d.switchOrigemId().equalsIgnoreCase(idB)
                    && d.switchDestinoId().equalsIgnoreCase(idA)
                    && (d.portaOrigem().isBlank() || d.portaOrigem().equalsIgnoreCase(portB))
                    && (d.portaDestino().isBlank() || d.portaDestino().equalsIgnoreCase(portA));
            if (bateDireto || bateReverso) {
                return d.severidade() == Severidade.CRITICO ? "loop-critico" : "stp-bloqueado";
            }
        }
        return "normal";
    }

    /**
     * Monta o documento HTML completo e elegante projetado especificamente para conversão em PDF A4.
     */
    private static String gerarHtmlRelatorioCompleto(
            String titulo,
            String dataHora,
            List<NodeInfo> nos,
            List<LinkInfo> links,
            Map<String, Posicao> posicoes,
            TopologyEngine engine,
            List<RelatorioTopologia.ParEnlace> paresUnicos,
            TopologyEngine.PlanoGateway plano,
            List<TopologyEngine.EquipamentoSemAcesso> pendencias,
            NodeInfo noGateway,
            List<LoopDiagnostic> loops
    ) {
        int totalSwitches = (int) nos.stream().filter(n -> "switch".equals(n.tipo()) || "olt".equals(n.tipo())).count();
        int totalAps = (int) nos.stream().filter(n -> "ap".equals(n.tipo()) || "radio".equals(n.tipo())).count();
        int totalEnlaces = links.size();
        long loopsCriticosCount = loops.stream().filter(LoopDiagnostic::ehLoopAtivo).count();
        long avisosStpCount = loops.stream().filter(d -> !d.ehLoopAtivo()).count();
        boolean topologiaParcial = plano.possuiSegmentoIncerto()
                || !plano.conexoesViaLldpExterno().isEmpty()
                || !pendencias.isEmpty();

        Map<String, String> alertaPorNodeDevId = new HashMap<>();
        for (LoopDiagnostic d : loops) {
            String devOrig = "dev-" + d.switchOrigemId();
            if (d.ehLoopAtivo()) {
                alertaPorNodeDevId.put(devOrig, "CRITICO");
                if (!d.switchDestinoId().isBlank()) {
                    alertaPorNodeDevId.put("dev-" + d.switchDestinoId(), "CRITICO");
                }
            } else {
                alertaPorNodeDevId.putIfAbsent(devOrig, "STP");
            }
        }
        for (NodeInfo n : nos) {
            if ("segmento".equals(n.tipo())) {
                boolean algumAuth = plano.switchesIntermediariosSuspeitos().stream()
                        .anyMatch(TopologyEngine.EquipamentoSemAcesso::ehFalhaAutenticacao);
                alertaPorNodeDevId.putIfAbsent(n.id(), algumAuth ? "AUTH_ERRO" : "SEM_ACESSO");
                continue;
            }
            if (n.switchIdentity() != null || n.gatewayInfo() != null) {
                continue;
            }
            Optional<TopologyEngine.EquipamentoSemAcesso> eqMatch = associarPendenciaAoNo(n, pendencias);
            if (eqMatch.isPresent()) {
                alertaPorNodeDevId.putIfAbsent(n.id(), eqMatch.get().ehFalhaAutenticacao() ? "AUTH_ERRO" : "SEM_ACESSO");
            } else if ("switch".equals(n.tipo()) || "olt".equals(n.tipo()) || n.id().startsWith("passivo-")) {
                alertaPorNodeDevId.putIfAbsent(n.id(), "SEM_ACESSO");
            }
        }

        String gwDescricao = (noGateway != null && noGateway.ip() != null && !noGateway.ip().isBlank())
                ? (noGateway.ip() + " (" + OuiLookup.identificarFabricante(noGateway.macBase()).orElse("Gateway") + ")")
                : "Não identificado";

        String svg = construirDiagramaSvg(nos, links, posicoes, alertaPorNodeDevId);

        Map<String, List<String>> conexoesPorDev = new LinkedHashMap<>();
        for (NodeInfo n : nos) {
            conexoesPorDev.put(n.id(), new ArrayList<>());
        }
        Map<String, NodeInfo> nosPorId = new HashMap<>();
        for (NodeInfo n : nos) {
            nosPorId.put(n.id(), n);
        }

        for (LinkInfo l : links) {
            NodeInfo a = nosPorId.get(l.devA());
            NodeInfo b = nosPorId.get(l.devB());
            if (a != null && b != null) {
                conexoesPorDev.get(a.id()).add(l.portA() + " &rarr; " + escapeHtml(b.nome()) + " [" + l.portB() + "]");
                conexoesPorDev.get(b.id()).add(l.portB() + " &rarr; " + escapeHtml(a.nome()) + " [" + l.portA() + "]");
            }
        }

        StringBuilder html = new StringBuilder();
        html.append(String.format("""
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <title>Relatório Executivo de Topologia - %s</title>
              <style>
                * { box-sizing: border-box; }
                body {
                  font-family: 'Segoe UI', -apple-system, BlinkMacSystemFont, Roboto, Helvetica, Arial, sans-serif;
                  color: #0f172a;
                  margin: 0;
                  padding: 16px 20px;
                  background: #ffffff;
                  -webkit-print-color-adjust: exact;
                  print-color-adjust: exact;
                }
                .topo-header {
                  display: flex;
                  justify-content: space-between;
                  align-items: center;
                  background: linear-gradient(135deg, #0f172a 0%%, #1e3a8a 100%%);
                  color: #ffffff;
                  padding: 11px 18px;
                  border-radius: 8px;
                  margin-bottom: 10px;
                }
                .topo-header h1 {
                  font-size: 18px;
                  margin: 0 0 3px;
                  font-weight: 800;
                  letter-spacing: -0.3px;
                }
                .topo-header .subtitulo {
                  font-size: 11px;
                  color: #93c5fd;
                  font-weight: 500;
                }
                .topo-header .data-box {
                  text-align: right;
                  font-size: 11px;
                  color: #cbd5e1;
                }
                .topo-header .data-box b {
                  color: #ffffff;
                  font-size: 12px;
                }

                .kpi-grid {
                  display: grid;
                  grid-template-columns: repeat(5, 1fr);
                  gap: 10px;
                  margin-bottom: 10px;
                }
                .kpi-card {
                  background: #f8fafc;
                  border: 1px solid #e2e8f0;
                  border-radius: 6px;
                  padding: 8px 12px;
                  border-left: 4px solid #2563eb;
                }
                .kpi-label {
                  font-size: 10px;
                  text-transform: uppercase;
                  color: #64748b;
                  font-weight: 700;
                  letter-spacing: 0.5px;
                }
                .kpi-val {
                  font-size: 16.5px;
                  font-weight: 800;
                  color: #0f172a;
                  margin-top: 2px;
                }

                .secao-titulo {
                  font-size: 13px;
                  font-weight: 800;
                  text-transform: uppercase;
                  letter-spacing: 0.4px;
                  color: #0f172a;
                  border-bottom: 2px solid #2563eb;
                  padding-bottom: 4px;
                  margin: 10px 0 8px;
                  display: flex;
                  justify-content: space-between;
                }

                .dg-box {
                  background: #ffffff;
                  border: 1px solid #cbd5e1;
                  border-radius: 8px;
                  padding: 6px;
                  margin-bottom: 8px;
                  box-shadow: 0 1px 3px rgba(0,0,0,0.03);
                  break-inside: avoid;
                }
                .dg-box svg {
                  display: block;
                  margin: 0 auto;
                  max-height: 505px;
                }

                table {
                  width: 100%%;
                  border-collapse: collapse;
                  font-size: 11px;
                  margin-bottom: 14px;
                }
                th {
                  background: #0f172a;
                  color: #ffffff;
                  text-align: left;
                  padding: 6px 10px;
                  font-weight: 600;
                  font-size: 10.5px;
                  text-transform: uppercase;
                  letter-spacing: 0.3px;
                }
                td {
                  padding: 6px 10px;
                  border-bottom: 1px solid #e2e8f0;
                  color: #334155;
                }
                tr:nth-child(even) td {
                  background: #f8fafc;
                }

                .badge {
                  display: inline-block;
                  padding: 2px 6px;
                  border-radius: 4px;
                  font-size: 10px;
                  font-weight: 700;
                }
                .badge-port { background: #e0e7ff; color: #3730a3; font-family: monospace; }
                .badge-cable { background: #f3e8ff; color: #6b21a8; }
                .badge-fibra { background: #ffedd5; color: #9a3412; }
                .badge-loop { background: #fee2e2; color: #b91c1c; border: 1px solid #fecaca; }
                .badge-stp { background: #fef3c7; color: #b45309; border: 1px solid #fde68a; }

                .grid-cards {
                  display: grid;
                  grid-template-columns: repeat(2, 1fr);
                  gap: 10px;
                  margin-bottom: 14px;
                }
                .card-dev {
                  background: #ffffff;
                  border: 1px solid #cbd5e1;
                  border-radius: 6px;
                  padding: 10px 12px;
                  break-inside: avoid;
                  box-shadow: 0 1px 2px rgba(0,0,0,0.02);
                }
                .card-dev-head {
                  display: flex;
                  justify-content: space-between;
                  align-items: center;
                  border-bottom: 1px solid #e2e8f0;
                  padding-bottom: 4px;
                  margin-bottom: 6px;
                }
                .card-dev-nome {
                  font-size: 12.5px;
                  font-weight: 700;
                  color: #0f172a;
                }
                .dl-grid {
                  display: grid;
                  grid-template-columns: 85px 1fr;
                  gap: 3px 6px;
                  font-size: 11px;
                }
                .dl-dt { color: #64748b; font-weight: 600; }
                .dl-dd { color: #0f172a; }
                .dl-dd-mono { font-family: monospace; font-size: 10.5px; }
                .port-list { margin: 2px 0 0; padding-left: 14px; }
                .port-list li { margin-bottom: 2px; }

                .page-break {
                  page-break-before: always;
                  break-before: page;
                }

                @page {
                  size: A4 landscape;
                  margin: 8mm 10mm;
                }
                @media print {
                  body { padding: 0; background: #fff; }
                  .dg-box { border: 1px solid #e2e8f0; }
                }
              </style>
            </head>
            <body>
              <div class="topo-header">
                <div>
                  <h1>RELATÓRIO DE TOPOLOGIA DE REDE</h1>
                  <div class="subtitulo">Mapeamento Físico e Lógico de Infraestrutura &bull; tpDiscover Intelbras</div>
                </div>
                <div class="data-box">
                  Gerado em: <b>%s</b><br/>
                  Formato: <b>Documento Executivo PDF</b>
                </div>
              </div>

              <div class="kpi-grid">
                <div class="kpi-card">
                  <div class="kpi-label">Switches / Roteadores</div>
                  <div class="kpi-val">%d ativos</div>
                </div>
                <div class="kpi-card" style="border-left-color: #8b5cf6;">
                  <div class="kpi-label">Access Points / Rádios</div>
                  <div class="kpi-val">%d ativos</div>
                </div>
                <div class="kpi-card" style="border-left-color: #10b981;">
                  <div class="kpi-label">Enlaces Mapeados</div>
                  <div class="kpi-val">%d conexões</div>
                </div>
                <div class="kpi-card" style="border-left-color: #f59e0b;">
                  <div class="kpi-label">Roteador / Gateway</div>
                  <div class="kpi-val" style="font-size: 13.5px; margin-top: 4px;">%s</div>
                </div>
                <div class="kpi-card" style="border-left-color: %s;">
                  <div class="kpi-label">Saúde L2 / Loops</div>
                  <div class="kpi-val" style="font-size: 13.5px; margin-top: 4px; color: %s;">%s</div>
                </div>
              </div>
              %s

              <div class="secao-titulo">
                <span>Diagrama da Topologia de Rede (Visão Panorâmica)</span>
                <span style="font-size: 10px; color: #64748b; font-weight: normal;">Vetorial de Alta Resolução</span>
              </div>
              <div class="dg-box">
            """,
                escapeHtml(titulo),
                dataHora,
                totalSwitches + (noGateway != null ? 1 : 0),
                totalAps,
                totalEnlaces,
                escapeHtml(gwDescricao),
                loopsCriticosCount > 0 ? "#dc2626" : (avisosStpCount > 0 ? "#d97706" : "#16a34a"),
                loopsCriticosCount > 0 ? "#b91c1c" : (avisosStpCount > 0 ? "#b45309" : "#15803d"),
                (loopsCriticosCount > 0
                        ? (loopsCriticosCount + " loop(s) crítico(s)!")
                        : (avisosStpCount > 0 ? (avisosStpCount + " redundância(s) STP") : "Sem loops ativos"))
                        + (topologiaParcial ? " (Coleta parcial)" : ""),
                montarBannerTopologiaParcialHtml(plano, pendencias)
        ));

        html.append(svg);

        html.append("""
              </div>

              <div class="page-break"></div>
            """);

        if (!loops.isEmpty()) {
            html.append("""
              <div class="secao-titulo" style="border-bottom-color: #dc2626; color: #991b1b;">
                <span>[!] Diagnóstico de Loops e Redundâncias L2 (Somente Leitura)</span>
                <span style="font-size: 10px; color: #b91c1c; font-weight: 700;">Verificação Ativa</span>
              </div>
              <table>
                <thead>
                  <tr>
                    <th style="background: #7f1d1d; width: 105px;">Severidade</th>
                    <th style="background: #7f1d1d; width: 135px;">Tipo</th>
                    <th style="background: #7f1d1d;">Local / Portas</th>
                    <th style="background: #7f1d1d;">Evidência &amp; Recomendação</th>
                  </tr>
                </thead>
                <tbody>
            """);
            for (LoopDiagnostic d : loops) {
                boolean critico = d.ehLoopAtivo();
                String badgeSev = critico
                        ? "<span class=\"badge badge-loop\">CRÍTICO / LOOP</span>"
                        : "<span class=\"badge badge-stp\">AVISO / STP</span>";
                NodeInfo noOrig = nosPorId.get("dev-" + d.switchOrigemId());
                String nomeOrig = noOrig != null ? noOrig.nome() : d.switchOrigemId();
                StringBuilder local = new StringBuilder("<b>").append(escapeHtml(nomeOrig)).append("</b>");
                if (!d.portaOrigem().isBlank()) {
                    local.append(" <span class=\"badge badge-port\">").append(escapeHtml(d.portaOrigem())).append("</span>");
                }
                if (!d.switchDestinoId().isBlank()) {
                    NodeInfo noDest = nosPorId.get("dev-" + d.switchDestinoId());
                    String nomeDest = noDest != null ? noDest.nome() : d.switchDestinoId();
                    local.append(" &harr; <b>").append(escapeHtml(nomeDest)).append("</b>");
                    if (!d.portaDestino().isBlank()) {
                        local.append(" <span class=\"badge badge-port\">").append(escapeHtml(d.portaDestino())).append("</span>");
                    }
                }
                String recom = d.acaoRecomendada().isBlank() ? ""
                        : "<br/><span style=\"color:#475569;\"><i>Sugestão:</i> " + escapeHtml(d.acaoRecomendada()) + "</span>";
                html.append(String.format("""
                  <tr>
                    <td>%s</td>
                    <td><b>%s</b></td>
                    <td>%s</td>
                    <td>%s%s</td>
                  </tr>
                """, badgeSev, escapeHtml(d.tipo().name()), local, escapeHtml(d.evidencia()), recom));
            }
            html.append("""
                </tbody>
              </table>
            """);
        }

        html.append("""
              <div class="secao-titulo">
                <span>Enlaces Físicos Par a Par (Conexões Ponto a Ponto)</span>
                <span style="font-size: 10px; color: #64748b; font-weight: normal;">Mapeamento Direto L2 / LLDP</span>
              </div>

              <table>
                <thead>
                  <tr>
                    <th style="width: 35px;">#</th>
                    <th>Equipamento Origem</th>
                    <th style="width: 120px;">Porta Origem</th>
                    <th style="width: 30px; text-align: center;">&harr;</th>
                    <th>Equipamento Destino</th>
                    <th style="width: 120px;">Porta Destino</th>
                    <th style="width: 130px;">Cabo / Estado</th>
                  </tr>
                </thead>
                <tbody>
            """);

        int seq = 1;
        for (LinkInfo l : links) {
            NodeInfo noA = nosPorId.get(l.devA());
            NodeInfo noB = nosPorId.get(l.devB());
            String nomeA = noA != null ? noA.nome() : l.devA();
            String nomeB = noB != null ? noB.nome() : l.devB();
            StringBuilder badgeCabo = new StringBuilder("fibra".equals(l.cable())
                    ? "<span class=\"badge badge-fibra\">Fibra Óptica</span>"
                    : "<span class=\"badge badge-cable\">Cat6 (Cobre)</span>");
            if ("loop-critico".equals(l.status())) {
                badgeCabo.append(" <span class=\"badge badge-loop\">LOOP</span>");
            } else if ("stp-bloqueado".equals(l.status())) {
                badgeCabo.append(" <span class=\"badge badge-stp\">STP BLK</span>");
            } else if ("incerto".equals(l.status())) {
                badgeCabo.append(" <span class=\"badge\" style=\"background:#fef3c7;color:#92400e;\">Incerto (Sem CLI)</span>");
            }

            html.append(String.format("""
              <tr>
                <td>%d</td>
                <td><b>%s</b></td>
                <td><span class="badge badge-port">%s</span></td>
                <td style="text-align: center; color: #2563eb; font-weight: bold;">&harr;</td>
                <td><b>%s</b></td>
                <td><span class="badge badge-port">%s</span></td>
                <td>%s</td>
              </tr>
            """, seq++, escapeHtml(nomeA), escapeHtml(l.portA()), escapeHtml(nomeB), escapeHtml(l.portB()), badgeCabo));
        }

        html.append("""
                </tbody>
              </table>

              <div class="secao-titulo" style="margin-top: 24px;">
                <span>Ficha Técnica e Inventário dos Dispositivos Coletados</span>
                <span style="font-size: 10px; color: #64748b; font-weight: normal;">Detalhamento por Equipamento</span>
              </div>

              <div class="grid-cards">
            """);

        for (NodeInfo n : nos) {
            String badgeTipo = switch (n.tipo()) {
                case "router" -> "<span class=\"badge\" style=\"background: #fef3c7; color: #b45309;\">ROTEADOR / GATEWAY</span>";
                case "segmento" -> "<span class=\"badge\" style=\"background: #fef3c7; color: #92400e;\">SEGMENTO L2 / SEM CLI</span>";
                case "ap" -> "<span class=\"badge\" style=\"background: #ede9fe; color: #6d28d9;\">ACCESS POINT WI-FI</span>";
                case "radio" -> "<span class=\"badge\" style=\"background: #cffafe; color: #0e7490;\">RÁDIO INTELBRAS</span>";
                case "olt" -> "<span class=\"badge\" style=\"background: #ccfbf1; color: #0f766e;\">OLT GPON / EPON</span>";
                default -> "<span class=\"badge\" style=\"background: #dbeafe; color: #1d4ed8;\">SWITCH GERENCIÁVEL</span>";
            };
            String alertaNo = alertaPorNodeDevId.get(n.id());
            if ("AUTH_ERRO".equals(alertaNo)) {
                badgeTipo += " <span class=\"badge\" style=\"background: #fee2e2; color: #b91c1c;\">FALHA AUTH</span>";
            } else if ("SEM_ACESSO".equals(alertaNo) && !"segmento".equals(n.tipo())) {
                badgeTipo += " <span class=\"badge\" style=\"background: #e2e8f0; color: #475569;\">SEM CLI</span>";
            }

            html.append(String.format("""
                <div class="card-dev">
                  <div class="card-dev-head">
                    <span class="card-dev-nome">%s</span>
                    %s
                  </div>
                  <div class="dl-grid">
                    <span class="dl-dt">Modelo:</span><span class="dl-dd"><b>%s</b></span>
                    <span class="dl-dt">Endereço IP:</span><span class="dl-dd dl-dd-mono">%s</span>
                    <span class="dl-dt">MAC-Base:</span><span class="dl-dd dl-dd-mono">%s</span>
                    <span class="dl-dt">Serial (SN):</span><span class="dl-dd dl-dd-mono">%s</span>
                    <span class="dl-dt">Uplinks / Links:</span>
                    <span class="dl-dd">
            """, escapeHtml(n.nome()), badgeTipo, escapeHtml(n.modelo()),
                    escapeHtml(n.ip() != null && !n.ip().isBlank() ? n.ip() : "N/D"),
                    escapeHtml(n.macBase()), escapeHtml(n.serial())));

            List<String> ports = conexoesPorDev.getOrDefault(n.id(), List.of());
            if (ports.isEmpty()) {
                html.append("<span style=\"color: #94a3b8;\">Nenhum enlace direto mapeado</span>");
            } else {
                html.append("<ul class=\"port-list\">");
                for (String p : ports) {
                    html.append("<li>").append(p).append("</li>");
                }
                html.append("</ul>");
            }

            html.append("""
                    </span>
                  </div>
                </div>
            """);
        }

        html.append("""
              </div>
            """);

        if (!pendencias.isEmpty()) {
            html.append("""
              <div class="secao-titulo" style="margin-top: 20px; border-bottom-color: #d97706; color: #92400e;">
                <span>Equipamentos Sem Acesso CLI / Pendências de Coleta</span>
                <span style="font-size: 10px; color: #b45309; font-weight: 700;">Revisar Senha ou Equipamento Passivo</span>
              </div>
              <table>
                <thead>
                  <tr>
                    <th>Equipamento / Modelo</th>
                    <th style="width: 120px;">Endereço IP</th>
                    <th style="width: 110px;">Categoria</th>
                    <th style="width: 140px;">MAC Identificado</th>
                    <th>Situação</th>
                  </tr>
                </thead>
                <tbody>
            """);
            for (TopologyEngine.EquipamentoSemAcesso eq : pendencias) {
                String macFmt = eq.macNormalizado().isBlank() ? "N/D" : Serie3000Parser.formatarMacPadrao(eq.macNormalizado());
                String badgeBg = eq.ehFalhaAutenticacao() ? "#fee2e2" : "#fef3c7";
                String badgeFg = eq.ehFalhaAutenticacao() ? "#b91c1c" : "#92400e";
                html.append(String.format("""
                  <tr>
                    <td><b>%s</b></td>
                    <td><span class="badge badge-port">%s</span></td>
                    <td>%s</td>
                    <td><span class="badge badge-port" style="background:#f1f5f9; color:#0f172a;">%s</span></td>
                    <td><span class="badge" style="background:%s; color:%s;">%s</span></td>
                  </tr>
                """, escapeHtml(eq.rotuloModelo()), escapeHtml(eq.ipBase()), escapeHtml(eq.categoria()),
                        escapeHtml(macFmt), badgeBg, badgeFg, escapeHtml(eq.descricaoMotivo())));
            }
            html.append("""
                </tbody>
              </table>
            """);
        }

        // Tabela consolidada de vizinhos ARP
        Map<String, String> arpConsolidado = new LinkedHashMap<>();
        for (SwitchIdentity sw : engine.switchesRegistrados()) {
            List<ArpEntry> arps = engine.arpDe(sw.macNormalizado());
            for (ArpEntry a : arps) {
                if (a.ip() != null && a.mac() != null) {
                    String fab = OuiLookup.identificarFabricante(a.macNormalizado()).orElse("Desconhecido");
                    String porta = a.port() != null ? a.port() : "-";
                    arpConsolidado.putIfAbsent(a.ip(), a.mac() + " | " + fab + " | " + RelatorioTopologia.nomeExibicaoSwitch(sw) + " [" + porta + "]");
                }
            }
        }

        if (!arpConsolidado.isEmpty()) {
            html.append("""
              <div class="secao-titulo" style="margin-top: 20px;">
                <span>Dispositivos Vizinhos e Clientes Mapeados via ARP</span>
                <span style="font-size: 10px; color: #64748b; font-weight: normal;">Identificação de Fabricante OUI</span>
              </div>
              <table>
                <thead>
                  <tr>
                    <th>Endereço IP</th>
                    <th>Endereço MAC</th>
                    <th>Fabricante Identificado (OUI)</th>
                    <th>Detectado no Switch / Porta</th>
                  </tr>
                </thead>
                <tbody>
            """);

            for (Map.Entry<String, String> e : arpConsolidado.entrySet()) {
                String[] parts = e.getValue().split(" \\| ");
                String mac = parts.length > 0 ? parts[0] : "";
                String fab = parts.length > 1 ? parts[1] : "Desconhecido";
                String swPort = parts.length > 2 ? parts[2] : "";

                html.append(String.format("""
                  <tr>
                    <td><b>%s</b></td>
                    <td><span class="badge badge-port" style="background:#f1f5f9; color:#0f172a;">%s</span></td>
                    <td><b>%s</b></td>
                    <td>%s</td>
                  </tr>
                """, escapeHtml(e.getKey()), escapeHtml(mac), escapeHtml(fab), escapeHtml(swPort)));
            }

            html.append("""
                </tbody>
              </table>
            """);
        }

        html.append("""
              <div style="margin-top: 24px; padding-top: 8px; border-top: 1px solid #e2e8f0; font-size: 10px; color: #94a3b8; display: flex; justify-content: space-between;">
                <span>tpDiscover Intelbras &bull; Auditoria de Topologia de Rede</span>
                <span>Documento confidencial gerado automaticamente em alta resolução</span>
              </div>
            </body>
            </html>
            """);

        return html.toString();
    }

    private static Optional<TopologyEngine.EquipamentoSemAcesso> associarPendenciaAoNo(
            NodeInfo n,
            List<TopologyEngine.EquipamentoSemAcesso> pendencias
    ) {
        if (pendencias == null || pendencias.isEmpty()) {
            return Optional.empty();
        }
        String ipNo = n.ip() != null ? n.ip().trim() : "";
        int idx = ipNo.indexOf(':');
        if (idx > 0) ipNo = ipNo.substring(0, idx).trim();
        String macNo = (n.macBase() != null && !"N/D".equalsIgnoreCase(n.macBase())) ? MacUtils.normalizar(n.macBase()) : "";
        for (TopologyEngine.EquipamentoSemAcesso eq : pendencias) {
            if (!ipNo.isBlank() && ipNo.equals(eq.ipBase())) {
                return Optional.of(eq);
            }
            if (!macNo.isBlank() && !eq.macNormalizado().isBlank()
                    && MacUtils.pertenceAoSwitch(macNo, eq.macNormalizado())) {
                return Optional.of(eq);
            }
        }
        return Optional.empty();
    }

    private static String montarBannerTopologiaParcialHtml(
            TopologyEngine.PlanoGateway plano,
            List<TopologyEngine.EquipamentoSemAcesso> pendencias
    ) {
        boolean parcial = plano.possuiSegmentoIncerto()
                || !plano.conexoesViaLldpExterno().isEmpty()
                || !pendencias.isEmpty();
        if (!parcial) {
            return "";
        }
        long falhasAuth = pendencias.stream().filter(TopologyEngine.EquipamentoSemAcesso::ehFalhaAutenticacao).count();
        long semCliOuSenha = pendencias.size() - falhasAuth;
        StringBuilder detalhe = new StringBuilder();
        if (plano.possuiSegmentoIncerto()) {
            detalhe.append("O núcleo/uplink até o Gateway possui switches/OLTs sem acesso CLI; links em tracejado âmbar indicam <b>segmento L2 incerto</b> e não conexões diretas em portas LAN do roteador. ");
        } else if (!plano.conexoesViaLldpExterno().isEmpty()) {
            detalhe.append("Há switch(es) externo(s) sem acesso CLI identificados por LLDP no caminho até o Gateway. ");
        }
        if (falhasAuth > 0) {
            detalhe.append(falhasAuth).append(" equipamento(s) com falha de autenticação. ");
        }
        if (semCliOuSenha > 0) {
            detalhe.append(semCliOuSenha).append(" equipamento(s) sem senha/CLI posicionado(s) ou listado(s) passivamente. ");
        }
        return """
              <div style="margin: 0 0 14px 0; padding: 10px 14px; background: #fffbeb; border: 1px solid #f59e0b; border-left: 5px solid #d97706; border-radius: 6px; color: #78350f; font-size: 11.5px; line-height: 1.45;">
                <b>ATENÇÃO: Topologia Parcial</b> &mdash; %sConfigure credenciais extras ou ajuste a senha na aba <i>Equipamentos</i> para completar a topologia.
              </div>
            """.formatted(detalhe.toString());
    }

    private static String desenharGlyphSvg(String tipo, String modelo) {
        return switch (tipo) {
            case "router" -> """
                <ellipse cx="32" cy="44" rx="26" ry="2.6" fill="#000" opacity=".18"/>
                <path d="M6 20v12a26 9 0 0 0 52 0V20z" fill="url(#pt-blue)" stroke="#0f3a66" stroke-width=".8"/>
                <ellipse cx="32" cy="20" rx="26" ry="9" fill="url(#pt-blue-top)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M13 15L26 18.6M38 21.4L51 25M38 18.6L51 15M26 21.4L13 25" fill="none" stroke="#ffffff" stroke-width="2.2" stroke-linecap="round"/>
                """;
            case "segmento" -> """
                <ellipse cx="32" cy="45" rx="26" ry="2.6" fill="#000" opacity=".14"/>
                <path d="M16 40h32a11 11 0 0 0 2-21.8A14 14 0 0 0 23 14a12 12 0 0 0-11 10.6A9.5 9.5 0 0 0 16 40z" fill="url(#pt-cloud)" stroke="#d97706" stroke-width="1.6" stroke-dasharray="4 2"/>
                <text x="32" y="31" font-size="9.5" font-weight="800" text-anchor="middle" fill="#92400e">L2 / ?</text>
                """;
            case "ap" -> """
                <ellipse cx="31" cy="44" rx="22" ry="2.6" fill="#000" opacity=".18"/>
                <path d="M10 30h36v9h-36z" fill="url(#pt-blue)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M10 30l6 -6h36l-6 6z" fill="url(#pt-blue-top)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M46 30l6 -6v9l-6 6z" fill="url(#pt-blue-side)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M26.7 17.8A6 6 0 0 1 35.3 17.8M23.1 14.3A11 11 0 0 1 38.9 14.3M19.5 10.8A16 16 0 0 1 42.5 10.8" fill="none" stroke="#1c5e9f" stroke-width="2.4" stroke-linecap="round"/>
                <circle cx="31" cy="21" r="2.4" fill="#1c5e9f"/><circle cx="40" cy="34.5" r="1.4" fill="#5df08f"/>
                """;
            case "radio" -> """
                <ellipse cx="32" cy="44" rx="20" ry="2.6" fill="#000" opacity=".18"/>
                <rect x="20" y="16" width="24" height="24" rx="4" fill="#0e7490" stroke="#155e75" stroke-width="1"/>
                <rect x="24" y="20" width="16" height="14" rx="2" fill="#67e8f9" opacity=".35"/>
                <path d="M15 24A14 14 0 0 1 15 32M11 21A19 19 0 0 1 11 35M49 24A14 14 0 0 0 49 32M53 21A19 19 0 0 0 53 35" fill="none" stroke="#0891b2" stroke-width="2" stroke-linecap="round"/>
                <rect x="29.5" y="40" width="5" height="4" fill="#475569"/>
                """;
            case "olt" -> """
                <ellipse cx="32" cy="44" rx="28" ry="2.6" fill="#000" opacity=".18"/>
                <path d="M4 22h48v15h-48z" fill="url(#pt-dark)" stroke="#11161d" stroke-width=".8"/>
                <path d="M4 22l10 -9h48l-10 9z" fill="#55606f" stroke="#11161d" stroke-width=".8"/>
                <path d="M52 22l10 -9v15l-10 9z" fill="#171c24" stroke="#11161d" stroke-width=".8"/>
                <rect x="8" y="27.5" width="3.4" height="4" rx=".6" fill="#22c55e"/><rect x="13" y="27.5" width="3.4" height="4" rx=".6" fill="#22c55e"/>
                <rect x="18" y="27.5" width="3.4" height="4" rx=".6" fill="#22c55e"/><rect x="23" y="27.5" width="3.4" height="4" rx=".6" fill="#22c55e"/>
                <rect x="28" y="27.5" width="3.4" height="4" rx=".6" fill="#22c55e"/><rect x="33" y="27.5" width="3.4" height="4" rx=".6" fill="#22c55e"/>
                <rect x="46" y="27" width="4" height="5" rx=".6" fill="#3b82f6"/>
                <rect x="10" y="6" width="26" height="11" rx="2" fill="#0d9488"/>
                <text x="23" y="14.5" font-size="8" font-weight="700" text-anchor="middle" fill="#fff">OLT</text>
                """;
            default -> """
                <ellipse cx="32" cy="44" rx="28" ry="2.6" fill="#000" opacity=".18"/>
                <path d="M4 24h48v14h-48z" fill="url(#pt-blue)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M4 24l10 -10h48l-10 10z" fill="url(#pt-blue-top)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M52 24l10 -10v14l-10 10z" fill="url(#pt-blue-side)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M17 16.5L29 16.5M34 16.5L46 16.5M41 20.5L29 20.5M24 20.5L12 20.5" fill="none" stroke="#ffffff" stroke-width="1.8" stroke-linecap="round"/>
                """;
        };
    }

    private static Path gerarPdfViaNavegador(Path htmlPath, Path pdfPath) {
        List<String> possiveisNavegadores = List.of(
                "google-chrome",
                "chromium",
                "chromium-browser",
                "microsoft-edge",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/snap/bin/chromium",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "msedge.exe",
                "chrome.exe"
        );

        boolean ehWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (String navegador : possiveisNavegadores) {
            File bin = new File(navegador);
            if (!bin.isAbsolute() || bin.exists()) {
                List<String> cmd = new ArrayList<>();
                cmd.add(navegador);
                cmd.add("--headless=new");
                cmd.add("--disable-gpu");
                if (!ehWindows) {
                    cmd.add("--no-sandbox");
                }
                cmd.add("--no-pdf-header-footer");
                cmd.add("--print-to-pdf=" + pdfPath.toAbsolutePath());
                cmd.add(htmlPath.toUri().toString());

                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    Process proc = pb.start();
                    boolean finalizou = proc.waitFor(15, TimeUnit.SECONDS);
                    if (!finalizou) {
                        proc.destroyForcibly();
                    }
                    if (finalizou && Files.exists(pdfPath) && Files.size(pdfPath) > 0) {
                        return pdfPath;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

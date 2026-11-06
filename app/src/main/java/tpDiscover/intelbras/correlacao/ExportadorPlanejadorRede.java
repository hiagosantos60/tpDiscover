package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.GatewayInfo;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.OuiLookup;
import tpDiscover.intelbras.modelos.SwitchIdentity;

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
            String label
    ) {}

    /**
     * Gera exclusivamente o arquivo PDF consolidado com todas as informações do cenário.
     * Arquivos intermediários são removidos automaticamente.
     */
    public static Path exportarPdf(TopologyEngine engine, Path pastaDestino) {
        List<SwitchIdentity> switches = new ArrayList<>(engine.switchesRegistrados());
        if (switches.isEmpty()) {
            return null;
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String dataFormatada = LocalDateTime.now().format(DATA_HORA_BR);
        String nomeBase = "topologia_" + timestamp;

        Path dir = (pastaDestino != null) ? pastaDestino : Path.of(".");
        Path caminhoPdf = dir.resolve(nomeBase + ".pdf");

        Optional<GatewayInfo> gwOpt = engine.gatewayIdentificado();
        List<TopologyEngine.ConexaoGateway> conexoesGw = gwOpt.isPresent()
                ? engine.identificarConexoesGateway()
                : List.of();

        Map<String, List<RelatorioTopologia.Enlace>> adjacencia =
                RelatorioTopologia.construirGrafoEnlacesDiretos(engine, switches);
        List<RelatorioTopologia.ParEnlace> paresUnicos =
                RelatorioTopologia.extrairParesUnicos(switches, adjacencia);

        // Mapeia nós da rede
        List<NodeInfo> nos = new ArrayList<>();
        NodeInfo noGateway = null;
        if (gwOpt.isPresent()) {
            GatewayInfo gw = gwOpt.get();
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

        // Mapeia enlaces
        List<LinkInfo> links = new ArrayList<>();
        int linkSeq = 1;

        if (noGateway != null) {
            for (TopologyEngine.ConexaoGateway cg : conexoesGw) {
                String devRemoto = "dev-" + cg.switchCore().macNormalizado();
                String portGw = "LAN" + (links.size() + 1);
                links.add(new LinkInfo(
                        "link-" + (linkSeq++),
                        noGateway.id(),
                        portGw,
                        devRemoto,
                        cg.porta(),
                        "cat6",
                        "Uplink Gateway"
                ));
            }
        }

        for (RelatorioTopologia.ParEnlace p : paresUnicos) {
            String devA = "dev-" + p.swA().macNormalizado();
            String devB = "dev-" + p.swB().macNormalizado();
            String cabo = ehPortaOptica(p.portaA()) || ehPortaOptica(p.portaB()) ? "fibra" : "cat6";
            String label = p.portaA() + " <-> " + p.portaB();
            links.add(new LinkInfo(
                    "link-" + (linkSeq++),
                    devA,
                    p.portaA(),
                    devB,
                    p.portaB(),
                    cabo,
                    label
            ));
        }

        // Calcula posições visuais hierárquicas
        Map<String, Posicao> posicoes = calcularLayoutHierarquico(nos, links, noGateway);

        // Gera HTML temporário completo
        Path tempHtml = null;
        try {
            tempHtml = Files.createTempFile("topologia_relatorio_", ".html");
            String htmlConteudo = gerarHtmlRelatorioCompleto(nomeBase, dataFormatada, nos, links, posicoes, engine, paresUnicos, conexoesGw, noGateway);
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

    /**
     * Compatibilidade retroativa caso chamado por métodos de exportação em lote.
     */
    public static ResultadoExportacao exportar(TopologyEngine engine, Path pastaDestino) {
        Path pdf = exportarPdf(engine, pastaDestino);
        return new ResultadoExportacao(null, null, pdf);
    }

    private static String detectarTipoEquipamento(SwitchIdentity sw) {
        String texto = ((sw.productId() != null ? sw.productId() : "") + " " + (sw.deviceName() != null ? sw.deviceName() : "")).toLowerCase();
        if (texto.contains("olt") || texto.contains("gpon") || texto.contains("epon")) {
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

    private static Map<String, Posicao> calcularLayoutHierarquico(
            List<NodeInfo> nos,
            List<LinkInfo> links,
            NodeInfo noGateway
    ) {
        Map<String, Posicao> resultado = new LinkedHashMap<>();
        if (nos.isEmpty()) {
            return resultado;
        }

        Map<String, Set<String>> vizinhos = new HashMap<>();
        for (NodeInfo n : nos) {
            vizinhos.put(n.id(), new LinkedHashSet<>());
        }
        for (LinkInfo l : links) {
            if (vizinhos.containsKey(l.devA()) && vizinhos.containsKey(l.devB())) {
                vizinhos.get(l.devA()).add(l.devB());
                vizinhos.get(l.devB()).add(l.devA());
            }
        }

        Map<String, Integer> camadas = new HashMap<>();
        Queue<String> fila = new ArrayDeque<>();

        if (noGateway != null) {
            camadas.put(noGateway.id(), 0);
            fila.add(noGateway.id());
        } else {
            NodeInfo raiz = nos.stream()
                    .max(Comparator.comparingInt(n -> vizinhos.getOrDefault(n.id(), Set.of()).size()))
                    .orElse(nos.get(0));
            camadas.put(raiz.id(), 0);
            fila.add(raiz.id());
        }

        while (!fila.isEmpty()) {
            String atual = fila.poll();
            int camadaAtual = camadas.get(atual);
            for (String viz : vizinhos.getOrDefault(atual, Set.of())) {
                if (!camadas.containsKey(viz)) {
                    camadas.put(viz, camadaAtual + 1);
                    fila.add(viz);
                }
            }
        }

        for (NodeInfo n : nos) {
            if (!camadas.containsKey(n.id())) {
                camadas.put(n.id(), "ap".equals(n.tipo()) ? 3 : 1);
            }
        }

        for (NodeInfo n : nos) {
            if ("ap".equals(n.tipo())) {
                int maxCamadaViz = vizinhos.getOrDefault(n.id(), Set.of()).stream()
                        .mapToInt(v -> camadas.getOrDefault(v, 0))
                        .max()
                        .orElse(0);
                if (maxCamadaViz >= camadas.get(n.id())) {
                    camadas.put(n.id(), maxCamadaViz + 1);
                }
            }
        }

        Map<Integer, List<NodeInfo>> nosPorCamada = new TreeMap<>();
        for (NodeInfo n : nos) {
            int c = camadas.get(n.id());
            nosPorCamada.computeIfAbsent(c, k -> new ArrayList<>()).add(n);
        }

        double centroX = 650.0;
        double passoY = 175.0;
        double inicioY = 80.0;

        for (Map.Entry<Integer, List<NodeInfo>> entry : nosPorCamada.entrySet()) {
            int c = entry.getKey();
            List<NodeInfo> lista = entry.getValue();
            int totalNaCamada = lista.size();
            double espacamentoX = (totalNaCamada > 4) ? 200.0 : 250.0;
            double larguraTotal = (totalNaCamada - 1) * espacamentoX;
            double inicioX = centroX - (larguraTotal / 2.0);

            for (int i = 0; i < totalNaCamada; i++) {
                double x = inicioX + (i * espacamentoX);
                double y = inicioY + (c * passoY);
                resultado.put(lista.get(i).id(), new Posicao(x, y));
            }
        }

        double minX = resultado.values().stream().mapToDouble(Posicao::x).min().orElse(60.0);
        if (minX < 70.0) {
            double deltaX = 70.0 - minX;
            resultado.replaceAll((k, p) -> new Posicao(p.x() + deltaX, p.y()));
        }

        return resultado;
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
            List<TopologyEngine.ConexaoGateway> conexoesGw,
            NodeInfo noGateway
    ) {
        int totalSwitches = (int) nos.stream().filter(n -> "switch".equals(n.tipo())).count();
        int totalAps = (int) nos.stream().filter(n -> "ap".equals(n.tipo())).count();
        int totalEnlaces = links.size();

        String gwDescricao = (noGateway != null && noGateway.ip() != null && !noGateway.ip().isBlank())
                ? (noGateway.ip() + " (" + OuiLookup.identificarFabricante(noGateway.macBase()).orElse("Gateway") + ")")
                : "Não identificado";

        double minX = posicoes.values().stream().mapToDouble(Posicao::x).min().orElse(50.0);
        double maxX = posicoes.values().stream().mapToDouble(Posicao::x).max().orElse(800.0) + 64.0;
        double minY = posicoes.values().stream().mapToDouble(Posicao::y).min().orElse(50.0);
        double maxY = posicoes.values().stream().mapToDouble(Posicao::y).max().orElse(600.0) + 48.0;

        double pad = 60.0;
        double w = (maxX - minX) + pad * 2;
        double h = (maxY - minY) + pad * 2 + 20;
        double vbX = minX - pad;
        double vbY = minY - pad;

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

        // Links
        for (LinkInfo l : links) {
            Posicao pA = posicoes.get(l.devA());
            Posicao pB = posicoes.get(l.devB());
            if (pA == null || pB == null) continue;

            double ax = pA.x() + 32.0, ay = pA.y() + 24.0;
            double bx = pB.x() + 32.0, by = pB.y() + 24.0;

            String corCabo = "fibra".equals(l.cable()) ? "#f0a020" : "#8b5cf6";
            String dash = "fibra".equals(l.cable()) ? " stroke-dasharray=\"8 4\"" : "";

            svg.append(String.format(Locale.US,
                    "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"2.5\" stroke-linecap=\"round\"%s/>%n",
                    ax, ay, bx, by, corCabo, dash));

            double dx = bx - ax, dy = by - ay;
            double len = Math.hypot(dx, dy);
            if (len > 80.0) {
                double ux = dx / len, uy = dy / len;
                double nx = -uy, ny = ux;

                svg.append(String.format(Locale.US,
                        "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"3.5\" fill=\"#22c55e\" stroke=\"#fff\" stroke-width=\"1\"/>%n",
                        ax + ux * 36.0, ay + uy * 36.0));
                svg.append(String.format(Locale.US,
                        "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"3.5\" fill=\"#22c55e\" stroke=\"#fff\" stroke-width=\"1\"/>%n",
                        bx - ux * 36.0, by - uy * 36.0));

                svg.append(String.format(Locale.US,
                        "<text x=\"%.1f\" y=\"%.1f\" font-size=\"9.5\" font-weight=\"600\" fill=\"#475569\" stroke=\"#ffffff\" stroke-width=\"2.5\" paint-order=\"stroke\">%s</text>%n",
                        ax + ux * 46.0 + nx * 8.0, ay + uy * 46.0 + ny * 8.0, escapeHtml(l.portA())));

                svg.append(String.format(Locale.US,
                        "<text x=\"%.1f\" y=\"%.1f\" font-size=\"9.5\" font-weight=\"600\" fill=\"#475569\" stroke=\"#ffffff\" stroke-width=\"2.5\" paint-order=\"stroke\">%s</text>%n",
                        bx - ux * 46.0 + nx * 8.0, by - uy * 46.0 + ny * 8.0, escapeHtml(l.portB())));
            }
        }

        // Nós
        for (NodeInfo n : nos) {
            Posicao p = posicoes.get(n.id());
            if (p == null) continue;

            svg.append(String.format(Locale.US, "<g transform=\"translate(%.1f,%.1f)\">%n", p.x(), p.y()));
            svg.append(desenharGlyphSvg(n.tipo(), n.modelo()));
            svg.append(String.format(
                    "<text x=\"32\" y=\"62\" font-size=\"12\" font-weight=\"700\" text-anchor=\"middle\" fill=\"#0f172a\" stroke=\"#ffffff\" stroke-width=\"3\" paint-order=\"stroke\">%s</text>%n",
                    escapeHtml(n.nome())));
            if (n.ip() != null && !n.ip().isBlank()) {
                svg.append(String.format(
                        "<text x=\"32\" y=\"75\" font-size=\"10.5\" font-weight=\"600\" text-anchor=\"middle\" fill=\"#2563eb\" stroke=\"#ffffff\" stroke-width=\"3\" paint-order=\"stroke\">%s</text>%n",
                        escapeHtml(n.ip())));
            }
            svg.append("</g>\n");
        }
        svg.append("</svg>");

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
                  padding: 14px 20px;
                  border-radius: 8px;
                  margin-bottom: 14px;
                }
                .topo-header h1 {
                  font-size: 19px;
                  margin: 0 0 4px;
                  font-weight: 800;
                  letter-spacing: -0.3px;
                }
                .topo-header .subtitulo {
                  font-size: 11.5px;
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
                  grid-template-columns: repeat(4, 1fr);
                  gap: 10px;
                  margin-bottom: 14px;
                }
                .kpi-card {
                  background: #f8fafc;
                  border: 1px solid #e2e8f0;
                  border-radius: 6px;
                  padding: 10px 14px;
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
                  font-size: 17px;
                  font-weight: 800;
                  color: #0f172a;
                  margin-top: 2px;
                }

                .secao-titulo {
                  font-size: 13.5px;
                  font-weight: 800;
                  text-transform: uppercase;
                  letter-spacing: 0.4px;
                  color: #0f172a;
                  border-bottom: 2px solid #2563eb;
                  padding-bottom: 4px;
                  margin: 18px 0 10px;
                  display: flex;
                  justify-content: space-between;
                }

                .dg-box {
                  background: #ffffff;
                  border: 1px solid #cbd5e1;
                  border-radius: 8px;
                  padding: 10px;
                  margin-bottom: 14px;
                  box-shadow: 0 1px 3px rgba(0,0,0,0.03);
                }
                .dg-box svg {
                  display: block;
                  margin: 0 auto;
                  max-height: 480px;
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
                  <div class="kpi-label">Access Points (Wi-Fi)</div>
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
              </div>

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
                escapeHtml(gwDescricao)
        ));

        html.append(svg);

        html.append("""
              </div>

              <div class="page-break"></div>

              <div class="secao-titulo">
                <span>Enlaces Físicos Par a Par (Conexões Ponto a Ponto)</span>
                <span style="font-size: 10px; color: #64748b; font-weight: normal;">Mapeamento Direto L2</span>
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
                    <th style="width: 100px;">Tipo de Cabo</th>
                  </tr>
                </thead>
                <tbody>
            """);

        int seq = 1;
        if (noGateway != null) {
            for (TopologyEngine.ConexaoGateway cg : conexoesGw) {
                html.append(String.format("""
                  <tr>
                    <td>%d</td>
                    <td><b>%s</b></td>
                    <td><span class="badge badge-port">%s</span></td>
                    <td style="text-align: center; color: #2563eb; font-weight: bold;">&harr;</td>
                    <td><b>%s</b></td>
                    <td><span class="badge badge-port">%s</span></td>
                    <td><span class="badge badge-cable">Cat6 (Cobre)</span></td>
                  </tr>
                """, seq++, escapeHtml(noGateway.nome()), "LAN", escapeHtml(RelatorioTopologia.nomeExibicaoSwitch(cg.switchCore())), escapeHtml(cg.porta())));
            }
        }

        for (RelatorioTopologia.ParEnlace p : paresUnicos) {
            boolean optica = ehPortaOptica(p.portaA()) || ehPortaOptica(p.portaB());
            String badgeCabo = optica ? "<span class=\"badge badge-fibra\">Fibra Óptica</span>" : "<span class=\"badge badge-cable\">Cat6 (Cobre)</span>";

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
            """, seq++, escapeHtml(RelatorioTopologia.nomeExibicaoSwitch(p.swA())), escapeHtml(p.portaA()),
                    escapeHtml(RelatorioTopologia.nomeExibicaoSwitch(p.swB())), escapeHtml(p.portaB()), badgeCabo));
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
                case "ap" -> "<span class=\"badge\" style=\"background: #ede9fe; color: #6d28d9;\">ACCESS POINT WI-FI</span>";
                case "olt" -> "<span class=\"badge\" style=\"background: #ccfbf1; color: #0f766e;\">OLT GPON / EPON</span>";
                default -> "<span class=\"badge\" style=\"background: #dbeafe; color: #1d4ed8;\">SWITCH GERENCIÁVEL</span>";
            };

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

    private static String desenharGlyphSvg(String tipo, String modelo) {
        return switch (tipo) {
            case "router" -> """
                <ellipse cx="32" cy="44" rx="26" ry="2.6" fill="#000" opacity=".18"/>
                <path d="M6 20v12a26 9 0 0 0 52 0V20z" fill="url(#pt-blue)" stroke="#0f3a66" stroke-width=".8"/>
                <ellipse cx="32" cy="20" rx="26" ry="9" fill="url(#pt-blue-top)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M13 15L26 18.6M38 21.4L51 25M38 18.6L51 15M26 21.4L13 25" fill="none" stroke="#ffffff" stroke-width="2.2" stroke-linecap="round"/>
                """;
            case "ap" -> """
                <ellipse cx="31" cy="44" rx="22" ry="2.6" fill="#000" opacity=".18"/>
                <path d="M10 30h36v9h-36z" fill="url(#pt-blue)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M10 30l6 -6h36l-6 6z" fill="url(#pt-blue-top)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M46 30l6 -6v9l-6 6z" fill="url(#pt-blue-side)" stroke="#0f3a66" stroke-width=".8"/>
                <path d="M26.7 17.8A6 6 0 0 1 35.3 17.8M23.1 14.3A11 11 0 0 1 38.9 14.3M19.5 10.8A16 16 0 0 1 42.5 10.8" fill="none" stroke="#1c5e9f" stroke-width="2.4" stroke-linecap="round"/>
                <circle cx="31" cy="21" r="2.4" fill="#1c5e9f"/><circle cx="40" cy="34.5" r="1.4" fill="#5df08f"/>
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
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "msedge.exe",
                "chrome.exe"
        );

        for (String navegador : possiveisNavegadores) {
            File bin = new File(navegador);
            if (!bin.isAbsolute() || bin.exists()) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            navegador,
                            "--headless=new",
                            "--disable-gpu",
                            "--no-pdf-header-footer",
                            "--print-to-pdf=" + pdfPath.toAbsolutePath(),
                            htmlPath.toAbsolutePath().toString()
                    );
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    boolean finalizou = proc.waitFor(12, TimeUnit.SECONDS);
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

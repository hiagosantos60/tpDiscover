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

import java.nio.file.Paths;
import tpDiscover.intelbras.util.Compat;
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

    public static final class ResultadoExportacao {
        private final Path caminhoJson;
        private final Path caminhoHtml;
        private final Path caminhoPdf;

        public ResultadoExportacao(Path caminhoJson, Path caminhoHtml, Path caminhoPdf) {
            this.caminhoJson = caminhoJson;
            this.caminhoHtml = caminhoHtml;
            this.caminhoPdf = caminhoPdf;
        }

        public Path caminhoJson() {
            return caminhoJson;
        }

        public Path caminhoHtml() {
            return caminhoHtml;
        }

        public Path caminhoPdf() {
            return caminhoPdf;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ResultadoExportacao)) return false;
            ResultadoExportacao o = (ResultadoExportacao) obj;
            return java.util.Objects.equals(caminhoJson, o.caminhoJson)
                    && java.util.Objects.equals(caminhoHtml, o.caminhoHtml)
                    && java.util.Objects.equals(caminhoPdf, o.caminhoPdf);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(caminhoJson, caminhoHtml, caminhoPdf);
        }

        @Override
        public String toString() {
            return "ResultadoExportacao[caminhoJson=" + caminhoJson + ", caminhoHtml=" + caminhoHtml + ", caminhoPdf=" + caminhoPdf + "]";
        }

        public boolean possuiPdf() {
            return caminhoPdf != null && Files.exists(caminhoPdf);
        }
    }

    private static final class Posicao {
        private final double x;
        private final double y;

        private Posicao(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Posicao)) return false;
            Posicao o = (Posicao) obj;
            return Double.compare(x, o.x) == 0
                    && Double.compare(y, o.y) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(x, y);
        }

        @Override
        public String toString() {
            return "Posicao[x=" + x + ", y=" + y + "]";
        }
    }

    private static final class NodeInfo {
        private final String id;
        private final String tipo;
        private final String nome;
        private final String modelo;
        private final String ip;
        private final String macBase;
        private final String serial;
        private final int portCount;
        private final int sfpCount;
        private final SwitchIdentity switchIdentity;
        private final GatewayInfo gatewayInfo;

        private NodeInfo(String id, String tipo, String nome, String modelo, String ip, String macBase, String serial, int portCount, int sfpCount, SwitchIdentity switchIdentity, GatewayInfo gatewayInfo) {
            this.id = id;
            this.tipo = tipo;
            this.nome = nome;
            this.modelo = modelo;
            this.ip = ip;
            this.macBase = macBase;
            this.serial = serial;
            this.portCount = portCount;
            this.sfpCount = sfpCount;
            this.switchIdentity = switchIdentity;
            this.gatewayInfo = gatewayInfo;
        }

        public String id() {
            return id;
        }

        public String tipo() {
            return tipo;
        }

        public String nome() {
            return nome;
        }

        public String modelo() {
            return modelo;
        }

        public String ip() {
            return ip;
        }

        public String macBase() {
            return macBase;
        }

        public String serial() {
            return serial;
        }

        public int portCount() {
            return portCount;
        }

        public int sfpCount() {
            return sfpCount;
        }

        public SwitchIdentity switchIdentity() {
            return switchIdentity;
        }

        public GatewayInfo gatewayInfo() {
            return gatewayInfo;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof NodeInfo)) return false;
            NodeInfo o = (NodeInfo) obj;
            return java.util.Objects.equals(id, o.id)
                    && java.util.Objects.equals(tipo, o.tipo)
                    && java.util.Objects.equals(nome, o.nome)
                    && java.util.Objects.equals(modelo, o.modelo)
                    && java.util.Objects.equals(ip, o.ip)
                    && java.util.Objects.equals(macBase, o.macBase)
                    && java.util.Objects.equals(serial, o.serial)
                    && portCount == o.portCount
                    && sfpCount == o.sfpCount
                    && java.util.Objects.equals(switchIdentity, o.switchIdentity)
                    && java.util.Objects.equals(gatewayInfo, o.gatewayInfo);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, tipo, nome, modelo, ip, macBase, serial, portCount, sfpCount, switchIdentity, gatewayInfo);
        }

        @Override
        public String toString() {
            return "NodeInfo[id=" + id + ", tipo=" + tipo + ", nome=" + nome + ", modelo=" + modelo + ", ip=" + ip + ", macBase=" + macBase + ", serial=" + serial + ", portCount=" + portCount + ", sfpCount=" + sfpCount + ", switchIdentity=" + switchIdentity + ", gatewayInfo=" + gatewayInfo + "]";
        }
    }

    private static final class LinkInfo {
        private final String id;
        private final String devA;
        private final String portA;
        private final String devB;
        private final String portB;
        private final String cable;
        private final String label;
        private final String status;

        private LinkInfo(String id, String devA, String portA, String devB, String portB, String cable, String label, String status) {
            this.id = id;
            this.devA = devA;
            this.portA = portA;
            this.devB = devB;
            this.portB = portB;
            this.cable = cable;
            this.label = label;
            this.status = status;
        }

        public String id() {
            return id;
        }

        public String devA() {
            return devA;
        }

        public String portA() {
            return portA;
        }

        public String devB() {
            return devB;
        }

        public String portB() {
            return portB;
        }

        public String cable() {
            return cable;
        }

        public String label() {
            return label;
        }

        public String status() {
            return status;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof LinkInfo)) return false;
            LinkInfo o = (LinkInfo) obj;
            return java.util.Objects.equals(id, o.id)
                    && java.util.Objects.equals(devA, o.devA)
                    && java.util.Objects.equals(portA, o.portA)
                    && java.util.Objects.equals(devB, o.devB)
                    && java.util.Objects.equals(portB, o.portB)
                    && java.util.Objects.equals(cable, o.cable)
                    && java.util.Objects.equals(label, o.label)
                    && java.util.Objects.equals(status, o.status);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, devA, portA, devB, portB, cable, label, status);
        }

        @Override
        public String toString() {
            return "LinkInfo[id=" + id + ", devA=" + devA + ", portA=" + portA + ", devB=" + devB + ", portB=" + portB + ", cable=" + cable + ", label=" + label + ", status=" + status + "]";
        }
    }

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

        Path dir = (pastaDestino != null) ? pastaDestino : Paths.get(".");
        Path caminhoPdf = dir.resolve(nomeBase + ".pdf");

        Path tempHtml = null;
        try {
            tempHtml = Files.createTempFile("topologia_relatorio_", ".html");
            String htmlConteudo = gerarHtmlParaInspecao(engine, nomeBase, dataFormatada);
            Compat.writeString(tempHtml, htmlConteudo);
            exportarProjetoPlanejador(engine, dir.resolve(nomeBase + "_planejador.json"), dataFormatada);

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
     * Salva, ao lado do PDF, o projeto que o Planejador de Rede abre para editar a topologia.
     * Falhas aqui não impedem o PDF.
     */
    private static void exportarProjetoPlanejador(TopologyEngine engine, Path destino, String dataFormatada) {
        try {
            ExportadorProjetoPlanejador.salvar(montarMapa(engine), "Topologia " + dataFormatada, destino);
            System.out.println("[INFO] Projeto para o Planejador de Rede salvo em: " + destino.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            System.err.println("[AVISO] Não foi possível salvar o projeto do Planejador de Rede: " + e.getMessage());
        }
    }

    static String gerarHtmlParaInspecao(TopologyEngine engine) {
        return gerarHtmlParaInspecao(
                engine,
                "topologia_" + LocalDateTime.now().format(TIMESTAMP_FMT),
                LocalDateTime.now().format(DATA_HORA_BR));
    }

    private static String gerarHtmlParaInspecao(TopologyEngine engine, String nomeBase, String dataFormatada) {
        ModeloDiagrama m = montarModelo(engine);
        return gerarHtmlRelatorioCompleto(
                nomeBase, dataFormatada, m.nos, m.links, m.posicoes, engine, m.paresUnicos, m.plano, m.pendencias,
                m.noGateway, m.loops);
    }

    /** Modelo intermediário do diagrama: compartilhado entre o PDF e o mapa da interface. */
    private static final class ModeloDiagrama {
        final List<NodeInfo> nos;
        final List<LinkInfo> links;
        final Map<String, Posicao> posicoes;
        final List<RelatorioTopologia.ParEnlace> paresUnicos;
        final TopologyEngine.PlanoGateway plano;
        final List<TopologyEngine.EquipamentoSemAcesso> pendencias;
        final NodeInfo noGateway;
        final List<LoopDiagnostic> loops;

        ModeloDiagrama(List<NodeInfo> nos, List<LinkInfo> links, Map<String, Posicao> posicoes,
                       List<RelatorioTopologia.ParEnlace> paresUnicos, TopologyEngine.PlanoGateway plano,
                       List<TopologyEngine.EquipamentoSemAcesso> pendencias, NodeInfo noGateway,
                       List<LoopDiagnostic> loops) {
            this.nos = nos;
            this.links = links;
            this.posicoes = posicoes;
            this.paresUnicos = paresUnicos;
            this.plano = plano;
            this.pendencias = pendencias;
            this.noGateway = noGateway;
            this.loops = loops;
        }
    }

    private static ModeloDiagrama montarModelo(TopologyEngine engine) {

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
            String gwNome = (gw.ip() != null && !Compat.isBlank(gw.ip())) ? ("Gateway " + gw.ip()) : "Roteador / Gateway";
            String gwModelo = gw.rotuloVisual();
            String gwIp = gw.ip() != null ? gw.ip() : "";
            noGateway = new NodeInfo("dev-gw", "router", gwNome, gwModelo, gwIp, gw.mac(), "N/D", 5, 0, null, gw);
            nos.add(noGateway);
        }

        for (SwitchIdentity sw : switches) {
            String tipo = detectarTipoEquipamento(sw);
            String nome = RelatorioTopologia.nomeExibicaoSwitch(sw);
            String modelo = (sw.productId() != null && !Compat.isBlank(sw.productId())) ? sw.productId() : sw.deviceName();
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
                String pLocal = Compat.isBlank(u.portaLocalFilho()) ? "?" : u.portaLocalFilho();
                String pRem = ComwareParser.resolverMelhorPortaRemota(n.remotePort(), n.remotePortDescription());
                if (Compat.isBlank(pRem)) pRem = "ETH";
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
                    boolean mesmoIp = noGateway.ip() != null && !Compat.isBlank(noGateway.ip())
                            && noGateway.ip().equals(n.managementIp());
                    boolean mesmoMac = n.possuiChassisMacValido()
                            && noGateway.gatewayInfo() != null
                            && noGateway.gatewayInfo().macNormalizado().equalsIgnoreCase(n.chassisMacNormalizado());
                    if (mesmoIp || mesmoMac) {
                        continue;
                    }
                }
                String chaveUnica = chaveUnicaLldp(n, v.categoria());
                String pLocal = Compat.isBlank(n.localPort()) ? "?" : n.localPort();
                if (linksExternosViaPlano.contains(idOrigem + "|" + pLocal + "|" + chaveUnica)) {
                    continue;
                }
                NodeInfo extNode = obterOuCriarNoLldpExterno(externosLldpPorChave, nos, chaveUnica, n, v.categoria());
                String pRem = ComwareParser.resolverMelhorPortaRemota(n.remotePort(), n.remotePortDescription());
                if (Compat.isBlank(pRem)) pRem = "ETH";
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
            String tipoPassivo;
            switch (eq.categoria()) {
                case "AP":
                    tipoPassivo = "ap";
                    break;
                case "RADIO":
                    tipoPassivo = "radio";
                    break;
                case "OLT":
                    tipoPassivo = "olt";
                    break;
                case "ROUTER":
                    tipoPassivo = "router";
                    break;
                default:
                    tipoPassivo = "switch";
                    break;
            }
            String macFmt = Compat.isBlank(eq.macNormalizado()) ? "N/D" : Serie3000Parser.formatarMacPadrao(eq.macNormalizado());
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
        return new ModeloDiagrama(nos, links, posicoes, paresUnicos, plano, pendencias, noGateway, loops);
    }

    /**
     * Monta o mapa da topologia (mesmos equipamentos, cabos e posições do diagrama do PDF) para
     * exibição na interface.
     */
    public static MapaTopologia montarMapa(TopologyEngine engine) {
        if (engine == null) {
            return MapaTopologia.vazio();
        }
        ModeloDiagrama m = montarModelo(engine);
        Map<String, String> alertas = calcularAlertas(m.nos, m.loops, m.plano, m.pendencias);
        List<MapaTopologia.No> nos = new ArrayList<>();
        for (NodeInfo n : m.nos) {
            Posicao p = m.posicoes.get(n.id());
            if (p == null) continue;
            boolean coletado = n.switchIdentity() != null;
            nos.add(new MapaTopologia.No(n.id(), n.tipo(), n.nome(), n.modelo(), n.ip(), n.macBase(),
                    n.serial(), coletado, converterAlerta(alertas.get(n.id())), p.x(), p.y()));
        }
        List<MapaTopologia.Enlace> enlaces = new ArrayList<>();
        for (LinkInfo l : m.links) {
            if (!m.posicoes.containsKey(l.devA()) || !m.posicoes.containsKey(l.devB())) continue;
            enlaces.add(new MapaTopologia.Enlace(l.devA(), l.portA(), l.devB(), l.portB(),
                    "fibra".equals(l.cable()), converterStatus(l.status())));
        }
        return new MapaTopologia(nos, enlaces);
    }

    private static MapaTopologia.Alerta converterAlerta(String alerta) {
        if (alerta == null) return MapaTopologia.Alerta.NENHUM;
        switch (alerta) {
            case "CRITICO":
                return MapaTopologia.Alerta.LOOP;
            case "STP":
                return MapaTopologia.Alerta.STP;
            case "AUTH_ERRO":
                return MapaTopologia.Alerta.AUTH_ERRO;
            case "SEM_ACESSO":
                return MapaTopologia.Alerta.SEM_ACESSO;
            default:
                return MapaTopologia.Alerta.NENHUM;
        }
    }

    private static MapaTopologia.StatusEnlace converterStatus(String status) {
        if (status == null) return MapaTopologia.StatusEnlace.NORMAL;
        switch (status) {
            case "loop-critico":
                return MapaTopologia.StatusEnlace.LOOP;
            case "stp-bloqueado":
                return MapaTopologia.StatusEnlace.STP_BLOQUEADO;
            case "incerto":
                return MapaTopologia.StatusEnlace.INCERTO;
            default:
                return MapaTopologia.StatusEnlace.NORMAL;
        }
    }


    private static String chaveUnicaLldp(LldpNeighbor n, CategoriaInteresse categoria) {
        return (n.possuiChassisMacValido() ? n.chassisMacNormalizado()
                : (!Compat.isBlank(n.managementIp()) ? n.managementIp() : n.rotuloExibicao()))
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
        String tipoExt;
        switch (categoria) {
            case ROTEADOR_UPLINK:
                tipoExt = "router";
                break;
            case SWITCH_OU_OLT_EXTERNO:
                tipoExt = "switch";
                break;
            case ACCESS_POINT:
                tipoExt = "ap";
                break;
            case RADIO_INTELBRAS:
                tipoExt = "radio";
                break;
            default:
                tipoExt = "ap";
                break;
        }
        String extId = "lldp-" + Integer.toHexString(chaveUnica.hashCode());
        String nomeExt = n.rotuloExibicao();
        String modeloExt = !Compat.isBlank(n.systemDescription())
                ? (n.systemDescription().length() > 48 ? n.systemDescription().substring(0, 48) + "..." : n.systemDescription())
                : categoria.name();
        String macExt = n.possuiChassisMacValido() ? n.chassisMacNormalizado() : (Compat.isBlank(n.chassisIdRaw()) ? "N/D" : n.chassisIdRaw());
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

    private static final class CaixaProtegida {
        private final double minX;
        private final double minY;
        private final double maxX;
        private final double maxY;

        private CaixaProtegida(double minX, double minY, double maxX, double maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        public double minX() {
            return minX;
        }

        public double minY() {
            return minY;
        }

        public double maxX() {
            return maxX;
        }

        public double maxY() {
            return maxY;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CaixaProtegida)) return false;
            CaixaProtegida o = (CaixaProtegida) obj;
            return Double.compare(minX, o.minX) == 0
                    && Double.compare(minY, o.minY) == 0
                    && Double.compare(maxX, o.maxX) == 0
                    && Double.compare(maxY, o.maxY) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(minX, minY, maxX, maxY);
        }

        @Override
        public String toString() {
            return "CaixaProtegida[minX=" + minX + ", minY=" + minY + ", maxX=" + maxX + ", maxY=" + maxY + "]";
        }

        boolean colideCom(CaixaProtegida outra, double margem) {
            return !(maxX + margem <= outra.minX
                    || minX >= outra.maxX + margem
                    || maxY + margem <= outra.minY
                    || minY >= outra.maxY + margem);
        }
    }

    private static final class CurvaBezier {
        private final double x0;
        private final double y0;
        private final double x1;
        private final double y1;
        private final double x2;
        private final double y2;
        private final double x3;
        private final double y3;

        private CurvaBezier(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.x3 = x3;
            this.y3 = y3;
        }

        public double x0() {
            return x0;
        }

        public double y0() {
            return y0;
        }

        public double x1() {
            return x1;
        }

        public double y1() {
            return y1;
        }

        public double x2() {
            return x2;
        }

        public double y2() {
            return y2;
        }

        public double x3() {
            return x3;
        }

        public double y3() {
            return y3;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CurvaBezier)) return false;
            CurvaBezier o = (CurvaBezier) obj;
            return Double.compare(x0, o.x0) == 0
                    && Double.compare(y0, o.y0) == 0
                    && Double.compare(x1, o.x1) == 0
                    && Double.compare(y1, o.y1) == 0
                    && Double.compare(x2, o.x2) == 0
                    && Double.compare(y2, o.y2) == 0
                    && Double.compare(x3, o.x3) == 0
                    && Double.compare(y3, o.y3) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(x0, y0, x1, y1, x2, y2, x3, y3);
        }

        @Override
        public String toString() {
            return "CurvaBezier[x0=" + x0 + ", y0=" + y0 + ", x1=" + x1 + ", y1=" + y1 + ", x2=" + x2 + ", y2=" + y2 + ", x3=" + x3 + ", y3=" + y3 + "]";
        }

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

    private static final class PillPorta {
        private final String texto;
        private final double x;
        private final double y;
        private final double largura;
        private final double altura;

        private PillPorta(String texto, double x, double y, double largura, double altura) {
            this.texto = texto;
            this.x = x;
            this.y = y;
            this.largura = largura;
            this.altura = altura;
        }

        public String texto() {
            return texto;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double largura() {
            return largura;
        }

        public double altura() {
            return altura;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof PillPorta)) return false;
            PillPorta o = (PillPorta) obj;
            return java.util.Objects.equals(texto, o.texto)
                    && Double.compare(x, o.x) == 0
                    && Double.compare(y, o.y) == 0
                    && Double.compare(largura, o.largura) == 0
                    && Double.compare(altura, o.altura) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(texto, x, y, largura, altura);
        }

        @Override
        public String toString() {
            return "PillPorta[texto=" + texto + ", x=" + x + ", y=" + y + ", largura=" + largura + ", altura=" + altura + "]";
        }
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
                        .max(Comparator.comparingInt(n -> vizinhos.getOrDefault(n.id(), Compat.setOf()).size()))
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
                vizinhos.getOrDefault(b.id(), Compat.setOf()).size(),
                vizinhos.getOrDefault(a.id(), Compat.setOf()).size()));
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
            for (String viz : vizinhos.getOrDefault(atual, Compat.setOf())) {
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
        List<String> filhos = filhosPorNo.getOrDefault(id, Compat.listOf());
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
        List<String> filhos = filhosPorNo.getOrDefault(id, Compat.listOf());
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

    private static final class PontaConexao {
        private final int linkIdx;
        private final boolean ehA;
        private final double coordOposta;
        private final int parIdx;

        private PontaConexao(int linkIdx, boolean ehA, double coordOposta, int parIdx) {
            this.linkIdx = linkIdx;
            this.ehA = ehA;
            this.coordOposta = coordOposta;
            this.parIdx = parIdx;
        }

        public int linkIdx() {
            return linkIdx;
        }

        public boolean ehA() {
            return ehA;
        }

        public double coordOposta() {
            return coordOposta;
        }

        public int parIdx() {
            return parIdx;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof PontaConexao)) return false;
            PontaConexao o = (PontaConexao) obj;
            return linkIdx == o.linkIdx
                    && ehA == o.ehA
                    && Double.compare(coordOposta, o.coordOposta) == 0
                    && parIdx == o.parIdx;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(linkIdx, ehA, coordOposta, parIdx);
        }

        @Override
        public String toString() {
            return "PontaConexao[linkIdx=" + linkIdx + ", ehA=" + ehA + ", coordOposta=" + coordOposta + ", parIdx=" + parIdx + "]";
        }
    }

    private static final class PedidoPillPorta {
        private final String texto;
        private final CurvaBezier curva;
        private final boolean partirDoInicio;
        private final int slotNoLado;
        private final int totalNoLado;

        private PedidoPillPorta(String texto, CurvaBezier curva, boolean partirDoInicio, int slotNoLado, int totalNoLado) {
            this.texto = texto;
            this.curva = curva;
            this.partirDoInicio = partirDoInicio;
            this.slotNoLado = slotNoLado;
            this.totalNoLado = totalNoLado;
        }

        public String texto() {
            return texto;
        }

        public CurvaBezier curva() {
            return curva;
        }

        public boolean partirDoInicio() {
            return partirDoInicio;
        }

        public int slotNoLado() {
            return slotNoLado;
        }

        public int totalNoLado() {
            return totalNoLado;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof PedidoPillPorta)) return false;
            PedidoPillPorta o = (PedidoPillPorta) obj;
            return java.util.Objects.equals(texto, o.texto)
                    && java.util.Objects.equals(curva, o.curva)
                    && partirDoInicio == o.partirDoInicio
                    && slotNoLado == o.slotNoLado
                    && totalNoLado == o.totalNoLado;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(texto, curva, partirDoInicio, slotNoLado, totalNoLado);
        }

        @Override
        public String toString() {
            return "PedidoPillPorta[texto=" + texto + ", curva=" + curva + ", partirDoInicio=" + partirDoInicio + ", slotNoLado=" + slotNoLado + ", totalNoLado=" + totalNoLado + "]";
        }
    }

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
                if (l.portA() != null && !Compat.isBlank(l.portA())) {
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

            if (l.portA() != null && !Compat.isBlank(l.portA()) && !"-".equals(l.portA())) {
                pedidosPills.add(new PedidoPillPorta(l.portA(), curva, true, slotA[i], totalA[i]));
            }
            if (l.portB() != null && !Compat.isBlank(l.portB()) && !"-".equals(l.portB())) {
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
            if (n.ip() != null && !Compat.isBlank(n.ip())) {
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

        svg.append("  <defs>\n" +
                "    <linearGradient id=\"pt-blue\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#4fa3e3\"/><stop offset=\"1\" stop-color=\"#1c5e9f\"/></linearGradient>\n" +
                "    <linearGradient id=\"pt-blue-top\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#9ad0f7\"/><stop offset=\"1\" stop-color=\"#4fa3e3\"/></linearGradient>\n" +
                "    <linearGradient id=\"pt-blue-side\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#1a5490\"/><stop offset=\"1\" stop-color=\"#0e3862\"/></linearGradient>\n" +
                "    <linearGradient id=\"pt-grey\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#f5f7f9\"/><stop offset=\"1\" stop-color=\"#b4bdc8\"/></linearGradient>\n" +
                "    <linearGradient id=\"pt-grey-side\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#a3adb9\"/><stop offset=\"1\" stop-color=\"#6c7886\"/></linearGradient>\n" +
                "    <linearGradient id=\"pt-screen\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"#6cc0f5\"/><stop offset=\"1\" stop-color=\"#1d5f9b\"/></linearGradient>\n" +
                "    <linearGradient id=\"pt-cloud\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#ffffff\"/><stop offset=\"1\" stop-color=\"#c9ddf2\"/></linearGradient>\n" +
                "    <linearGradient id=\"pt-red\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#e2604f\"/><stop offset=\"1\" stop-color=\"#a3291c\"/></linearGradient>\n" +
                "    <linearGradient id=\"pt-dark\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#3b4555\"/><stop offset=\"1\" stop-color=\"#1f2630\"/></linearGradient>\n" +
                "  </defs>\n");

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
        switch (lado) {
            case BASE:
                return new Posicao(
                    cx + offsetDistribuido(slot, total, Math.min(13.0, 96.0 / Math.max(1, total))),
                    posNo.y() + 85.0
            );
            case TOPO:
                return new Posicao(
                    cx + offsetDistribuido(slot, total, Math.min(11.0, 56.0 / Math.max(1, total))),
                    posNo.y() - (temBadgeTopo ? 14.0 : 3.0)
            );
            case ESQUERDA:
                return new Posicao(
                    posNo.x() - 4.0,
                    posNo.y() + 24.0 + offsetDistribuido(slot, total, 9.0)
            );
            case DIREITA:
                return new Posicao(
                    posNo.x() + 68.0,
                    posNo.y() + 24.0 + offsetDistribuido(slot, total, 9.0)
            );
            default:
                throw new IllegalStateException("Valor inesperado: " + (lado));
        }
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
                    && (Compat.isBlank(d.portaOrigem()) || d.portaOrigem().equalsIgnoreCase(portA))
                    && (Compat.isBlank(d.portaDestino()) || d.portaDestino().equalsIgnoreCase(portB));
            boolean bateReverso = d.switchOrigemId().equalsIgnoreCase(idB)
                    && d.switchDestinoId().equalsIgnoreCase(idA)
                    && (Compat.isBlank(d.portaOrigem()) || d.portaOrigem().equalsIgnoreCase(portB))
                    && (Compat.isBlank(d.portaDestino()) || d.portaDestino().equalsIgnoreCase(portA));
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

        Map<String, String> alertaPorNodeDevId = calcularAlertas(nos, loops, plano, pendencias);

        String gwDescricao = (noGateway != null && noGateway.ip() != null && !Compat.isBlank(noGateway.ip()))
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
        html.append(String.format("<!doctype html>\n" +
                "<html lang=\"pt-BR\">\n" +
                "<head>\n" +
                "  <meta charset=\"utf-8\">\n" +
                "  <title>Relatório Executivo de Topologia - %s</title>\n" +
                "  <style>\n" +
                "    * { box-sizing: border-box; }\n" +
                "    body {\n" +
                "      font-family: 'Segoe UI', -apple-system, BlinkMacSystemFont, Roboto, Helvetica, Arial, sans-serif;\n" +
                "      color: #0f172a;\n" +
                "      margin: 0;\n" +
                "      padding: 16px 20px;\n" +
                "      background: #ffffff;\n" +
                "      -webkit-print-color-adjust: exact;\n" +
                "      print-color-adjust: exact;\n" +
                "    }\n" +
                "    .topo-header {\n" +
                "      display: flex;\n" +
                "      justify-content: space-between;\n" +
                "      align-items: center;\n" +
                "      background: linear-gradient(135deg, #0f172a 0%%, #1e3a8a 100%%);\n" +
                "      color: #ffffff;\n" +
                "      padding: 11px 18px;\n" +
                "      border-radius: 8px;\n" +
                "      margin-bottom: 10px;\n" +
                "    }\n" +
                "    .topo-header h1 {\n" +
                "      font-size: 18px;\n" +
                "      margin: 0 0 3px;\n" +
                "      font-weight: 800;\n" +
                "      letter-spacing: -0.3px;\n" +
                "    }\n" +
                "    .topo-header .subtitulo {\n" +
                "      font-size: 11px;\n" +
                "      color: #93c5fd;\n" +
                "      font-weight: 500;\n" +
                "    }\n" +
                "    .topo-header .data-box {\n" +
                "      text-align: right;\n" +
                "      font-size: 11px;\n" +
                "      color: #cbd5e1;\n" +
                "    }\n" +
                "    .topo-header .data-box b {\n" +
                "      color: #ffffff;\n" +
                "      font-size: 12px;\n" +
                "    }\n" +
                "\n" +
                "    .kpi-grid {\n" +
                "      display: grid;\n" +
                "      grid-template-columns: repeat(5, 1fr);\n" +
                "      gap: 10px;\n" +
                "      margin-bottom: 10px;\n" +
                "    }\n" +
                "    .kpi-card {\n" +
                "      background: #f8fafc;\n" +
                "      border: 1px solid #e2e8f0;\n" +
                "      border-radius: 6px;\n" +
                "      padding: 8px 12px;\n" +
                "      border-left: 4px solid #2563eb;\n" +
                "    }\n" +
                "    .kpi-label {\n" +
                "      font-size: 10px;\n" +
                "      text-transform: uppercase;\n" +
                "      color: #64748b;\n" +
                "      font-weight: 700;\n" +
                "      letter-spacing: 0.5px;\n" +
                "    }\n" +
                "    .kpi-val {\n" +
                "      font-size: 16.5px;\n" +
                "      font-weight: 800;\n" +
                "      color: #0f172a;\n" +
                "      margin-top: 2px;\n" +
                "    }\n" +
                "\n" +
                "    .secao-titulo {\n" +
                "      font-size: 13px;\n" +
                "      font-weight: 800;\n" +
                "      text-transform: uppercase;\n" +
                "      letter-spacing: 0.4px;\n" +
                "      color: #0f172a;\n" +
                "      border-bottom: 2px solid #2563eb;\n" +
                "      padding-bottom: 4px;\n" +
                "      margin: 10px 0 8px;\n" +
                "      display: flex;\n" +
                "      justify-content: space-between;\n" +
                "    }\n" +
                "\n" +
                "    .dg-box {\n" +
                "      background: #ffffff;\n" +
                "      border: 1px solid #cbd5e1;\n" +
                "      border-radius: 8px;\n" +
                "      padding: 6px;\n" +
                "      margin-bottom: 8px;\n" +
                "      box-shadow: 0 1px 3px rgba(0,0,0,0.03);\n" +
                "      break-inside: avoid;\n" +
                "    }\n" +
                "    .dg-box svg {\n" +
                "      display: block;\n" +
                "      margin: 0 auto;\n" +
                "      max-height: 505px;\n" +
                "    }\n" +
                "\n" +
                "    table {\n" +
                "      width: 100%%;\n" +
                "      border-collapse: collapse;\n" +
                "      font-size: 11px;\n" +
                "      margin-bottom: 14px;\n" +
                "    }\n" +
                "    th {\n" +
                "      background: #0f172a;\n" +
                "      color: #ffffff;\n" +
                "      text-align: left;\n" +
                "      padding: 6px 10px;\n" +
                "      font-weight: 600;\n" +
                "      font-size: 10.5px;\n" +
                "      text-transform: uppercase;\n" +
                "      letter-spacing: 0.3px;\n" +
                "    }\n" +
                "    td {\n" +
                "      padding: 6px 10px;\n" +
                "      border-bottom: 1px solid #e2e8f0;\n" +
                "      color: #334155;\n" +
                "    }\n" +
                "    tr:nth-child(even) td {\n" +
                "      background: #f8fafc;\n" +
                "    }\n" +
                "\n" +
                "    .badge {\n" +
                "      display: inline-block;\n" +
                "      padding: 2px 6px;\n" +
                "      border-radius: 4px;\n" +
                "      font-size: 10px;\n" +
                "      font-weight: 700;\n" +
                "    }\n" +
                "    .badge-port { background: #e0e7ff; color: #3730a3; font-family: monospace; }\n" +
                "    .badge-cable { background: #f3e8ff; color: #6b21a8; }\n" +
                "    .badge-fibra { background: #ffedd5; color: #9a3412; }\n" +
                "    .badge-loop { background: #fee2e2; color: #b91c1c; border: 1px solid #fecaca; }\n" +
                "    .badge-stp { background: #fef3c7; color: #b45309; border: 1px solid #fde68a; }\n" +
                "\n" +
                "    .grid-cards {\n" +
                "      display: grid;\n" +
                "      grid-template-columns: repeat(2, 1fr);\n" +
                "      gap: 10px;\n" +
                "      margin-bottom: 14px;\n" +
                "    }\n" +
                "    .card-dev {\n" +
                "      background: #ffffff;\n" +
                "      border: 1px solid #cbd5e1;\n" +
                "      border-radius: 6px;\n" +
                "      padding: 10px 12px;\n" +
                "      break-inside: avoid;\n" +
                "      box-shadow: 0 1px 2px rgba(0,0,0,0.02);\n" +
                "    }\n" +
                "    .card-dev-head {\n" +
                "      display: flex;\n" +
                "      justify-content: space-between;\n" +
                "      align-items: center;\n" +
                "      border-bottom: 1px solid #e2e8f0;\n" +
                "      padding-bottom: 4px;\n" +
                "      margin-bottom: 6px;\n" +
                "    }\n" +
                "    .card-dev-nome {\n" +
                "      font-size: 12.5px;\n" +
                "      font-weight: 700;\n" +
                "      color: #0f172a;\n" +
                "    }\n" +
                "    .dl-grid {\n" +
                "      display: grid;\n" +
                "      grid-template-columns: 85px 1fr;\n" +
                "      gap: 3px 6px;\n" +
                "      font-size: 11px;\n" +
                "    }\n" +
                "    .dl-dt { color: #64748b; font-weight: 600; }\n" +
                "    .dl-dd { color: #0f172a; }\n" +
                "    .dl-dd-mono { font-family: monospace; font-size: 10.5px; }\n" +
                "    .port-list { margin: 2px 0 0; padding-left: 14px; }\n" +
                "    .port-list li { margin-bottom: 2px; }\n" +
                "\n" +
                "    .page-break {\n" +
                "      page-break-before: always;\n" +
                "      break-before: page;\n" +
                "    }\n" +
                "\n" +
                "    @page {\n" +
                "      size: A4 landscape;\n" +
                "      margin: 8mm 10mm;\n" +
                "    }\n" +
                "    @media print {\n" +
                "      body { padding: 0; background: #fff; }\n" +
                "      .dg-box { border: 1px solid #e2e8f0; }\n" +
                "    }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"topo-header\">\n" +
                "    <div>\n" +
                "      <h1>RELATÓRIO DE TOPOLOGIA DE REDE</h1>\n" +
                "      <div class=\"subtitulo\">Mapeamento Físico e Lógico de Infraestrutura &bull; tpDiscover Intelbras</div>\n" +
                "    </div>\n" +
                "    <div class=\"data-box\">\n" +
                "      Gerado em: <b>%s</b><br/>\n" +
                "      Formato: <b>Documento Executivo PDF</b>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "\n" +
                "  <div class=\"kpi-grid\">\n" +
                "    <div class=\"kpi-card\">\n" +
                "      <div class=\"kpi-label\">Switches / Roteadores</div>\n" +
                "      <div class=\"kpi-val\">%d ativos</div>\n" +
                "    </div>\n" +
                "    <div class=\"kpi-card\" style=\"border-left-color: #8b5cf6;\">\n" +
                "      <div class=\"kpi-label\">Access Points / Rádios</div>\n" +
                "      <div class=\"kpi-val\">%d ativos</div>\n" +
                "    </div>\n" +
                "    <div class=\"kpi-card\" style=\"border-left-color: #10b981;\">\n" +
                "      <div class=\"kpi-label\">Enlaces Mapeados</div>\n" +
                "      <div class=\"kpi-val\">%d conexões</div>\n" +
                "    </div>\n" +
                "    <div class=\"kpi-card\" style=\"border-left-color: #f59e0b;\">\n" +
                "      <div class=\"kpi-label\">Roteador / Gateway</div>\n" +
                "      <div class=\"kpi-val\" style=\"font-size: 13.5px; margin-top: 4px;\">%s</div>\n" +
                "    </div>\n" +
                "    <div class=\"kpi-card\" style=\"border-left-color: %s;\">\n" +
                "      <div class=\"kpi-label\">Saúde L2 / Loops</div>\n" +
                "      <div class=\"kpi-val\" style=\"font-size: 13.5px; margin-top: 4px; color: %s;\">%s</div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  %s\n" +
                "\n" +
                "  <div class=\"secao-titulo\">\n" +
                "    <span>Diagrama da Topologia de Rede (Visão Panorâmica)</span>\n" +
                "    <span style=\"font-size: 10px; color: #64748b; font-weight: normal;\">Vetorial de Alta Resolução</span>\n" +
                "  </div>\n" +
                "  <div class=\"dg-box\">\n",
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

        html.append("  </div>\n" +
                "\n" +
                "  <div class=\"page-break\"></div>\n");

        if (!loops.isEmpty()) {
            html.append("  <div class=\"secao-titulo\" style=\"border-bottom-color: #dc2626; color: #991b1b;\">\n" +
                    "    <span>[!] Diagnóstico de Loops e Redundâncias L2 (Somente Leitura)</span>\n" +
                    "    <span style=\"font-size: 10px; color: #b91c1c; font-weight: 700;\">Verificação Ativa</span>\n" +
                    "  </div>\n" +
                    "  <table>\n" +
                    "    <thead>\n" +
                    "      <tr>\n" +
                    "        <th style=\"background: #7f1d1d; width: 105px;\">Severidade</th>\n" +
                    "        <th style=\"background: #7f1d1d; width: 135px;\">Tipo</th>\n" +
                    "        <th style=\"background: #7f1d1d;\">Local / Portas</th>\n" +
                    "        <th style=\"background: #7f1d1d;\">Evidência &amp; Recomendação</th>\n" +
                    "      </tr>\n" +
                    "    </thead>\n" +
                    "    <tbody>\n");
            for (LoopDiagnostic d : loops) {
                boolean critico = d.ehLoopAtivo();
                String badgeSev = critico
                        ? "<span class=\"badge badge-loop\">CRÍTICO / LOOP</span>"
                        : "<span class=\"badge badge-stp\">AVISO / STP</span>";
                NodeInfo noOrig = nosPorId.get("dev-" + d.switchOrigemId());
                String nomeOrig = noOrig != null ? noOrig.nome() : d.switchOrigemId();
                StringBuilder local = new StringBuilder("<b>").append(escapeHtml(nomeOrig)).append("</b>");
                if (!Compat.isBlank(d.portaOrigem())) {
                    local.append(" <span class=\"badge badge-port\">").append(escapeHtml(d.portaOrigem())).append("</span>");
                }
                if (!Compat.isBlank(d.switchDestinoId())) {
                    NodeInfo noDest = nosPorId.get("dev-" + d.switchDestinoId());
                    String nomeDest = noDest != null ? noDest.nome() : d.switchDestinoId();
                    local.append(" &harr; <b>").append(escapeHtml(nomeDest)).append("</b>");
                    if (!Compat.isBlank(d.portaDestino())) {
                        local.append(" <span class=\"badge badge-port\">").append(escapeHtml(d.portaDestino())).append("</span>");
                    }
                }
                String recom = Compat.isBlank(d.acaoRecomendada()) ? ""
                        : "<br/><span style=\"color:#475569;\"><i>Sugestão:</i> " + escapeHtml(d.acaoRecomendada()) + "</span>";
                html.append(String.format("  <tr>\n" +
                        "    <td>%s</td>\n" +
                        "    <td><b>%s</b></td>\n" +
                        "    <td>%s</td>\n" +
                        "    <td>%s%s</td>\n" +
                        "  </tr>\n", badgeSev, escapeHtml(d.tipo().name()), local, escapeHtml(d.evidencia()), recom));
            }
            html.append("    </tbody>\n" +
                    "  </table>\n");
        }

        html.append("  <div class=\"secao-titulo\">\n" +
                "    <span>Enlaces Físicos Par a Par (Conexões Ponto a Ponto)</span>\n" +
                "    <span style=\"font-size: 10px; color: #64748b; font-weight: normal;\">Mapeamento Direto L2 / LLDP</span>\n" +
                "  </div>\n" +
                "\n" +
                "  <table>\n" +
                "    <thead>\n" +
                "      <tr>\n" +
                "        <th style=\"width: 35px;\">#</th>\n" +
                "        <th>Equipamento Origem</th>\n" +
                "        <th style=\"width: 120px;\">Porta Origem</th>\n" +
                "        <th style=\"width: 30px; text-align: center;\">&harr;</th>\n" +
                "        <th>Equipamento Destino</th>\n" +
                "        <th style=\"width: 120px;\">Porta Destino</th>\n" +
                "        <th style=\"width: 130px;\">Cabo / Estado</th>\n" +
                "      </tr>\n" +
                "    </thead>\n" +
                "    <tbody>\n");

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

            html.append(String.format("  <tr>\n" +
                    "    <td>%d</td>\n" +
                    "    <td><b>%s</b></td>\n" +
                    "    <td><span class=\"badge badge-port\">%s</span></td>\n" +
                    "    <td style=\"text-align: center; color: #2563eb; font-weight: bold;\">&harr;</td>\n" +
                    "    <td><b>%s</b></td>\n" +
                    "    <td><span class=\"badge badge-port\">%s</span></td>\n" +
                    "    <td>%s</td>\n" +
                    "  </tr>\n", seq++, escapeHtml(nomeA), escapeHtml(l.portA()), escapeHtml(nomeB), escapeHtml(l.portB()), badgeCabo));
        }

        html.append("    </tbody>\n" +
                "  </table>\n" +
                "\n" +
                "  <div class=\"secao-titulo\" style=\"margin-top: 24px;\">\n" +
                "    <span>Ficha Técnica e Inventário dos Dispositivos Coletados</span>\n" +
                "    <span style=\"font-size: 10px; color: #64748b; font-weight: normal;\">Detalhamento por Equipamento</span>\n" +
                "  </div>\n" +
                "\n" +
                "  <div class=\"grid-cards\">\n");

        for (NodeInfo n : nos) {
            String badgeTipo;
            switch (n.tipo()) {
                case "router":
                    badgeTipo = "<span class=\"badge\" style=\"background: #fef3c7; color: #b45309;\">ROTEADOR / GATEWAY</span>";
                    break;
                case "segmento":
                    badgeTipo = "<span class=\"badge\" style=\"background: #fef3c7; color: #92400e;\">SEGMENTO L2 / SEM CLI</span>";
                    break;
                case "ap":
                    badgeTipo = "<span class=\"badge\" style=\"background: #ede9fe; color: #6d28d9;\">ACCESS POINT WI-FI</span>";
                    break;
                case "radio":
                    badgeTipo = "<span class=\"badge\" style=\"background: #cffafe; color: #0e7490;\">RÁDIO INTELBRAS</span>";
                    break;
                case "olt":
                    badgeTipo = "<span class=\"badge\" style=\"background: #ccfbf1; color: #0f766e;\">OLT GPON / EPON</span>";
                    break;
                default:
                    badgeTipo = "<span class=\"badge\" style=\"background: #dbeafe; color: #1d4ed8;\">SWITCH GERENCIÁVEL</span>";
                    break;
            }
            String alertaNo = alertaPorNodeDevId.get(n.id());
            if ("AUTH_ERRO".equals(alertaNo)) {
                badgeTipo += " <span class=\"badge\" style=\"background: #fee2e2; color: #b91c1c;\">FALHA AUTH</span>";
            } else if ("SEM_ACESSO".equals(alertaNo) && !"segmento".equals(n.tipo())) {
                badgeTipo += " <span class=\"badge\" style=\"background: #e2e8f0; color: #475569;\">SEM CLI</span>";
            }

            html.append(String.format("    <div class=\"card-dev\">\n" +
                    "      <div class=\"card-dev-head\">\n" +
                    "        <span class=\"card-dev-nome\">%s</span>\n" +
                    "        %s\n" +
                    "      </div>\n" +
                    "      <div class=\"dl-grid\">\n" +
                    "        <span class=\"dl-dt\">Modelo:</span><span class=\"dl-dd\"><b>%s</b></span>\n" +
                    "        <span class=\"dl-dt\">Endereço IP:</span><span class=\"dl-dd dl-dd-mono\">%s</span>\n" +
                    "        <span class=\"dl-dt\">MAC-Base:</span><span class=\"dl-dd dl-dd-mono\">%s</span>\n" +
                    "        <span class=\"dl-dt\">Serial (SN):</span><span class=\"dl-dd dl-dd-mono\">%s</span>\n" +
                    "        <span class=\"dl-dt\">Uplinks / Links:</span>\n" +
                    "        <span class=\"dl-dd\">\n", escapeHtml(n.nome()), badgeTipo, escapeHtml(n.modelo()),
                    escapeHtml(n.ip() != null && !Compat.isBlank(n.ip()) ? n.ip() : "N/D"),
                    escapeHtml(n.macBase()), escapeHtml(n.serial())));

            List<String> ports = conexoesPorDev.getOrDefault(n.id(), Compat.listOf());
            if (ports.isEmpty()) {
                html.append("<span style=\"color: #94a3b8;\">Nenhum enlace direto mapeado</span>");
            } else {
                html.append("<ul class=\"port-list\">");
                for (String p : ports) {
                    html.append("<li>").append(p).append("</li>");
                }
                html.append("</ul>");
            }

            html.append("        </span>\n" +
                    "      </div>\n" +
                    "    </div>\n");
        }

        html.append("  </div>\n");

        if (!pendencias.isEmpty()) {
            html.append("  <div class=\"secao-titulo\" style=\"margin-top: 20px; border-bottom-color: #d97706; color: #92400e;\">\n" +
                    "    <span>Equipamentos Sem Acesso CLI / Pendências de Coleta</span>\n" +
                    "    <span style=\"font-size: 10px; color: #b45309; font-weight: 700;\">Revisar Senha ou Equipamento Passivo</span>\n" +
                    "  </div>\n" +
                    "  <table>\n" +
                    "    <thead>\n" +
                    "      <tr>\n" +
                    "        <th>Equipamento / Modelo</th>\n" +
                    "        <th style=\"width: 120px;\">Endereço IP</th>\n" +
                    "        <th style=\"width: 110px;\">Categoria</th>\n" +
                    "        <th style=\"width: 140px;\">MAC Identificado</th>\n" +
                    "        <th>Situação</th>\n" +
                    "      </tr>\n" +
                    "    </thead>\n" +
                    "    <tbody>\n");
            for (TopologyEngine.EquipamentoSemAcesso eq : pendencias) {
                String macFmt = Compat.isBlank(eq.macNormalizado()) ? "N/D" : Serie3000Parser.formatarMacPadrao(eq.macNormalizado());
                String badgeBg = eq.ehFalhaAutenticacao() ? "#fee2e2" : "#fef3c7";
                String badgeFg = eq.ehFalhaAutenticacao() ? "#b91c1c" : "#92400e";
                html.append(String.format("  <tr>\n" +
                        "    <td><b>%s</b></td>\n" +
                        "    <td><span class=\"badge badge-port\">%s</span></td>\n" +
                        "    <td>%s</td>\n" +
                        "    <td><span class=\"badge badge-port\" style=\"background:#f1f5f9; color:#0f172a;\">%s</span></td>\n" +
                        "    <td><span class=\"badge\" style=\"background:%s; color:%s;\">%s</span></td>\n" +
                        "  </tr>\n", escapeHtml(eq.rotuloModelo()), escapeHtml(eq.ipBase()), escapeHtml(eq.categoria()),
                        escapeHtml(macFmt), badgeBg, badgeFg, escapeHtml(eq.descricaoMotivo())));
            }
            html.append("    </tbody>\n" +
                    "  </table>\n");
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
            html.append("  <div class=\"secao-titulo\" style=\"margin-top: 20px;\">\n" +
                    "    <span>Dispositivos Vizinhos e Clientes Mapeados via ARP</span>\n" +
                    "    <span style=\"font-size: 10px; color: #64748b; font-weight: normal;\">Identificação de Fabricante OUI</span>\n" +
                    "  </div>\n" +
                    "  <table>\n" +
                    "    <thead>\n" +
                    "      <tr>\n" +
                    "        <th>Endereço IP</th>\n" +
                    "        <th>Endereço MAC</th>\n" +
                    "        <th>Fabricante Identificado (OUI)</th>\n" +
                    "        <th>Detectado no Switch / Porta</th>\n" +
                    "      </tr>\n" +
                    "    </thead>\n" +
                    "    <tbody>\n");

            for (Map.Entry<String, String> e : arpConsolidado.entrySet()) {
                String[] parts = e.getValue().split(" \\| ");
                String mac = parts.length > 0 ? parts[0] : "";
                String fab = parts.length > 1 ? parts[1] : "Desconhecido";
                String swPort = parts.length > 2 ? parts[2] : "";

                html.append(String.format("  <tr>\n" +
                        "    <td><b>%s</b></td>\n" +
                        "    <td><span class=\"badge badge-port\" style=\"background:#f1f5f9; color:#0f172a;\">%s</span></td>\n" +
                        "    <td><b>%s</b></td>\n" +
                        "    <td>%s</td>\n" +
                        "  </tr>\n", escapeHtml(e.getKey()), escapeHtml(mac), escapeHtml(fab), escapeHtml(swPort)));
            }

            html.append("    </tbody>\n" +
                    "  </table>\n");
        }

        html.append("  <div style=\"margin-top: 24px; padding-top: 8px; border-top: 1px solid #e2e8f0; font-size: 10px; color: #94a3b8; display: flex; justify-content: space-between;\">\n" +
                "    <span>tpDiscover Intelbras &bull; Auditoria de Topologia de Rede</span>\n" +
                "    <span>Documento confidencial gerado automaticamente em alta resolução</span>\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>\n");

        return html.toString();
    }

    /** Alerta por nó do diagrama: CRITICO (loop), STP, AUTH_ERRO ou SEM_ACESSO. */
    private static Map<String, String> calcularAlertas(
            List<NodeInfo> nos,
            List<LoopDiagnostic> loops,
            TopologyEngine.PlanoGateway plano,
            List<TopologyEngine.EquipamentoSemAcesso> pendencias
    ) {
        Map<String, String> alertaPorNodeDevId = new HashMap<>();
        for (LoopDiagnostic d : loops) {
            String devOrig = "dev-" + d.switchOrigemId();
            if (d.ehLoopAtivo()) {
                alertaPorNodeDevId.put(devOrig, "CRITICO");
                if (!Compat.isBlank(d.switchDestinoId())) {
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
        return alertaPorNodeDevId;
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
            if (!Compat.isBlank(ipNo) && ipNo.equals(eq.ipBase())) {
                return Optional.of(eq);
            }
            if (!Compat.isBlank(macNo) && !Compat.isBlank(eq.macNormalizado())
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
        return "  <div style=\"margin: 0 0 14px 0; padding: 10px 14px; background: #fffbeb; border: 1px solid #f59e0b; border-left: 5px solid #d97706; border-radius: 6px; color: #78350f; font-size: 11.5px; line-height: 1.45;\">\n" +
                "    <b>ATENÇÃO: Topologia Parcial</b> &mdash; %sConfigure credenciais extras ou ajuste a senha na aba <i>Equipamentos</i> para completar a topologia.\n" +
                String.format("  </div>\n", detalhe.toString());
    }

    private static String desenharGlyphSvg(String tipo, String modelo) {
        switch (tipo) {
            case "router":
                return "<ellipse cx=\"32\" cy=\"44\" rx=\"26\" ry=\"2.6\" fill=\"#000\" opacity=\".18\"/>\n" +
                    "<path d=\"M6 20v12a26 9 0 0 0 52 0V20z\" fill=\"url(#pt-blue)\" stroke=\"#0f3a66\" stroke-width=\".8\"/>\n" +
                    "<ellipse cx=\"32\" cy=\"20\" rx=\"26\" ry=\"9\" fill=\"url(#pt-blue-top)\" stroke=\"#0f3a66\" stroke-width=\".8\"/>\n" +
                    "<path d=\"M13 15L26 18.6M38 21.4L51 25M38 18.6L51 15M26 21.4L13 25\" fill=\"none\" stroke=\"#ffffff\" stroke-width=\"2.2\" stroke-linecap=\"round\"/>\n";
            case "segmento":
                return "<ellipse cx=\"32\" cy=\"45\" rx=\"26\" ry=\"2.6\" fill=\"#000\" opacity=\".14\"/>\n" +
                    "<path d=\"M16 40h32a11 11 0 0 0 2-21.8A14 14 0 0 0 23 14a12 12 0 0 0-11 10.6A9.5 9.5 0 0 0 16 40z\" fill=\"url(#pt-cloud)\" stroke=\"#d97706\" stroke-width=\"1.6\" stroke-dasharray=\"4 2\"/>\n" +
                    "<text x=\"32\" y=\"31\" font-size=\"9.5\" font-weight=\"800\" text-anchor=\"middle\" fill=\"#92400e\">L2 / ?</text>\n";
            case "ap":
                return "<ellipse cx=\"31\" cy=\"44\" rx=\"22\" ry=\"2.6\" fill=\"#000\" opacity=\".18\"/>\n" +
                    "<path d=\"M10 30h36v9h-36z\" fill=\"url(#pt-blue)\" stroke=\"#0f3a66\" stroke-width=\".8\"/>\n" +
                    "<path d=\"M10 30l6 -6h36l-6 6z\" fill=\"url(#pt-blue-top)\" stroke=\"#0f3a66\" stroke-width=\".8\"/>\n" +
                    "<path d=\"M46 30l6 -6v9l-6 6z\" fill=\"url(#pt-blue-side)\" stroke=\"#0f3a66\" stroke-width=\".8\"/>\n" +
                    "<path d=\"M26.7 17.8A6 6 0 0 1 35.3 17.8M23.1 14.3A11 11 0 0 1 38.9 14.3M19.5 10.8A16 16 0 0 1 42.5 10.8\" fill=\"none\" stroke=\"#1c5e9f\" stroke-width=\"2.4\" stroke-linecap=\"round\"/>\n" +
                    "<circle cx=\"31\" cy=\"21\" r=\"2.4\" fill=\"#1c5e9f\"/><circle cx=\"40\" cy=\"34.5\" r=\"1.4\" fill=\"#5df08f\"/>\n";
            case "radio":
                return "<ellipse cx=\"32\" cy=\"44\" rx=\"20\" ry=\"2.6\" fill=\"#000\" opacity=\".18\"/>\n" +
                    "<rect x=\"20\" y=\"16\" width=\"24\" height=\"24\" rx=\"4\" fill=\"#0e7490\" stroke=\"#155e75\" stroke-width=\"1\"/>\n" +
                    "<rect x=\"24\" y=\"20\" width=\"16\" height=\"14\" rx=\"2\" fill=\"#67e8f9\" opacity=\".35\"/>\n" +
                    "<path d=\"M15 24A14 14 0 0 1 15 32M11 21A19 19 0 0 1 11 35M49 24A14 14 0 0 0 49 32M53 21A19 19 0 0 0 53 35\" fill=\"none\" stroke=\"#0891b2\" stroke-width=\"2\" stroke-linecap=\"round\"/>\n" +
                    "<rect x=\"29.5\" y=\"40\" width=\"5\" height=\"4\" fill=\"#475569\"/>\n";
            case "olt":
                return "<ellipse cx=\"32\" cy=\"44\" rx=\"28\" ry=\"2.6\" fill=\"#000\" opacity=\".18\"/>\n" +
                    "<path d=\"M4 22h48v15h-48z\" fill=\"url(#pt-dark)\" stroke=\"#11161d\" stroke-width=\".8\"/>\n" +
                    "<path d=\"M4 22l10 -9h48l-10 9z\" fill=\"#55606f\" stroke=\"#11161d\" stroke-width=\".8\"/>\n" +
                    "<path d=\"M52 22l10 -9v15l-10 9z\" fill=\"#171c24\" stroke=\"#11161d\" stroke-width=\".8\"/>\n" +
                    "<rect x=\"8\" y=\"27.5\" width=\"3.4\" height=\"4\" rx=\".6\" fill=\"#22c55e\"/><rect x=\"13\" y=\"27.5\" width=\"3.4\" height=\"4\" rx=\".6\" fill=\"#22c55e\"/>\n" +
                    "<rect x=\"18\" y=\"27.5\" width=\"3.4\" height=\"4\" rx=\".6\" fill=\"#22c55e\"/><rect x=\"23\" y=\"27.5\" width=\"3.4\" height=\"4\" rx=\".6\" fill=\"#22c55e\"/>\n" +
                    "<rect x=\"28\" y=\"27.5\" width=\"3.4\" height=\"4\" rx=\".6\" fill=\"#22c55e\"/><rect x=\"33\" y=\"27.5\" width=\"3.4\" height=\"4\" rx=\".6\" fill=\"#22c55e\"/>\n" +
                    "<rect x=\"46\" y=\"27\" width=\"4\" height=\"5\" rx=\".6\" fill=\"#3b82f6\"/>\n" +
                    "<rect x=\"10\" y=\"6\" width=\"26\" height=\"11\" rx=\"2\" fill=\"#0d9488\"/>\n" +
                    "<text x=\"23\" y=\"14.5\" font-size=\"8\" font-weight=\"700\" text-anchor=\"middle\" fill=\"#fff\">OLT</text>\n";
            default:
                return "<ellipse cx=\"32\" cy=\"44\" rx=\"28\" ry=\"2.6\" fill=\"#000\" opacity=\".18\"/>\n" +
                    "<path d=\"M4 24h48v14h-48z\" fill=\"url(#pt-blue)\" stroke=\"#0f3a66\" stroke-width=\".8\"/>\n" +
                    "<path d=\"M4 24l10 -10h48l-10 10z\" fill=\"url(#pt-blue-top)\" stroke=\"#0f3a66\" stroke-width=\".8\"/>\n" +
                    "<path d=\"M52 24l10 -10v14l-10 10z\" fill=\"url(#pt-blue-side)\" stroke=\"#0f3a66\" stroke-width=\".8\"/>\n" +
                    "<path d=\"M17 16.5L29 16.5M34 16.5L46 16.5M41 20.5L29 20.5M24 20.5L12 20.5\" fill=\"none\" stroke=\"#ffffff\" stroke-width=\"1.8\" stroke-linecap=\"round\"/>\n";
        }
    }

    private static Path gerarPdfViaNavegador(Path htmlPath, Path pdfPath) {
        List<String> possiveisNavegadores = Compat.listOf(
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
                // Nome antigo do mesmo flag: Chrome/Edge 109 (última versão para Windows 7/8) só reconhece este.
                cmd.add("--print-to-pdf-no-header");
                cmd.add("--print-to-pdf=" + pdfPath.toAbsolutePath());
                cmd.add(htmlPath.toUri().toString());

                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(ProcessBuilder.Redirect.to(new java.io.File(
                            System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null")));
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

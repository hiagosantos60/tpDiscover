package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.coleta.LocalGatewayDetector;
import tpDiscover.intelbras.modelos.ArpEntry;
import tpDiscover.intelbras.modelos.GatewayInfo;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.LldpNeighbor.CategoriaInteresse;
import tpDiscover.intelbras.modelos.LoopDiagnostic;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.OuiLookup;
import tpDiscover.intelbras.modelos.StpPortState;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.LldpInterestFilter;
import tpDiscover.intelbras.parser.Serie3000Parser;

import java.util.*;

/**
 * Motor de correlação de topologia via tabela MAC (com suporte a detecção de Roteador/Gateway via Rota + ARP).
 *
 * Duas etapas principais:
 *  1) analisarPortas: para cada porta de um switch, descobre quais OUTROS switches
 *     conhecidos são "visíveis" ali (comparando por janela de offset de MAC em MacUtils)
 *     e aplica inferência transitiva em árvore para recuperar switches cujo MAC próprio
 *     tenha expirado (aging) na tabela local.
 *  2) inferirVizinhoDireto: quando uma porta vê MAIS DE UM switch conhecido, decide
 *     qual deles é o vizinho FISICAMENTE direto (os outros estariam atrás dele),
 *     checando qual candidato "explica" os demais nas portas que seguem adiante
 *     (excluindo a porta de retorno para o switch de origem - Split Horizon).
 */
public class TopologyEngine {

    private final Map<String, SwitchIdentity> switchesPorId = new LinkedHashMap<>();
    private final Map<String, List<MacEntry>> entradasPorSwitch = new HashMap<>();
    private final Map<String, String> rotaPadraoPorSwitch = new LinkedHashMap<>();
    private final Map<String, List<ArpEntry>> arpPorSwitch = new LinkedHashMap<>();
    private final Map<String, String> macExternoPorIp = new HashMap<>();
    private final Map<String, List<LldpNeighbor>> lldpPorSwitch = new LinkedHashMap<>();
    private final Map<String, List<StpPortState>> stpPorSwitch = new LinkedHashMap<>();
    private final Map<String, List<LoopDiagnostic>> loopbackHardwarePorSwitch = new LinkedHashMap<>();
    private final Map<String, Map<String, List<String>>> baggsPorSwitch = new LinkedHashMap<>();
    private final Map<String, EquipamentoSemAcesso> equipamentosSemAcessoPorIp = new LinkedHashMap<>();

    private String gatewayIpManual;
    private String gatewayMacManual;
    private GatewayInfo gatewayFallbackLocal;
    private Map<String, Map<String, Set<SwitchIdentity>>> mapaVisibilidadeCache;

    public record ConexaoGateway(SwitchIdentity switchCore, String porta) {}

    public record EquipamentoSemAcesso(
            String ip,
            String rotuloModelo,
            String categoria,
            String macNormalizado,
            String motivo
    ) {
        public EquipamentoSemAcesso {
            ip = ip != null ? ip.trim() : "";
            rotuloModelo = (rotuloModelo != null && !rotuloModelo.isBlank()) ? rotuloModelo.trim() : "Equipamento";
            categoria = (categoria != null && !categoria.isBlank()) ? categoria.trim().toUpperCase(Locale.ROOT) : "SWITCH";
            macNormalizado = macNormalizado != null ? MacUtils.normalizar(macNormalizado) : "";
            motivo = (motivo != null && !motivo.isBlank()) ? motivo.trim().toUpperCase(Locale.ROOT) : "SEM_CLI";
        }

        public String ipBase() {
            int idx = ip.indexOf(':');
            return (idx > 0 ? ip.substring(0, idx) : ip).trim();
        }

        public boolean ehComutadorInfra() {
            return "SWITCH".equals(categoria) || "OLT".equals(categoria);
        }

        public boolean ehFalhaAutenticacao() {
            return "FALHA_AUTENTICACAO".equals(motivo);
        }

        public String descricaoMotivo() {
            return switch (motivo) {
                case "FALHA_AUTENTICACAO" -> "Falha de autenticação";
                case "SEM_SENHA" -> "Sem senha informada";
                case "TIMEOUT" -> "Tempo esgotado / inacessível";
                case "CONEXAO_RECUSADA" -> "Conexão CLI recusada";
                case "ERRO_PROTOCOLO" -> "Erro de protocolo CLI";
                default -> "Somente leitura passiva (sem CLI)";
            };
        }
    }

    public record PlanoGateway(
            GatewayInfo gateway,
            List<ConexaoGateway> conexoesDiretas,
            List<ConexaoGateway> conexoesViaSegmento,
            List<ConexaoLldpUpstream> conexoesViaLldpExterno,
            List<EquipamentoSemAcesso> switchesIntermediariosSuspeitos
    ) {
        public boolean possuiSegmentoIncerto() {
            return !conexoesViaSegmento.isEmpty();
        }
    }

    public record ConexaoLldpUpstream(
            SwitchIdentity switchFilho,
            String portaLocalFilho,
            LldpNeighbor vizinhoExterno
    ) {
        public ConexaoLldpUpstream {
            portaLocalFilho = portaLocalFilho != null ? portaLocalFilho.trim() : "";
        }
    }

    public record ConexaoPassiva(
            SwitchIdentity switchPai,
            String portaPai,
            EquipamentoSemAcesso equipamento
    ) {
        public ConexaoPassiva {
            portaPai = portaPai != null ? portaPai.trim() : "";
        }
    }

    public record VizinhoLldpClassificado(
            LldpNeighbor neighbor,
            CategoriaInteresse categoria,
            SwitchIdentity switchColetado
    ) {}

    public void registrarSwitch(SwitchIdentity identidade) {
        switchesPorId.put(identidade.macNormalizado(), identidade);
        String base = ipBase(identidade.ip());
        if (!base.isEmpty()) {
            equipamentosSemAcessoPorIp.entrySet().removeIf(e -> base.equals(e.getValue().ipBase()));
        }
        mapaVisibilidadeCache = null;
    }

    public void registrarEntradas(String switchId, List<MacEntry> entradas) {
        entradasPorSwitch.computeIfAbsent(switchId, k -> new ArrayList<>()).addAll(entradas);
        mapaVisibilidadeCache = null;
    }

    public void registrarRotaPadrao(String switchId, String gatewayIp) {
        if (gatewayIp != null && !gatewayIp.isBlank() && !"0.0.0.0".equals(gatewayIp.trim())) {
            rotaPadraoPorSwitch.put(switchId, gatewayIp.trim());
            mapaVisibilidadeCache = null;
        }
    }

    public void registrarEntradasArp(String switchId, List<ArpEntry> entradasArp) {
        if (entradasArp != null && !entradasArp.isEmpty()) {
            arpPorSwitch.computeIfAbsent(switchId, k -> new ArrayList<>()).addAll(entradasArp);
            mapaVisibilidadeCache = null;
        }
    }

    public void registrarLldp(String switchId, List<LldpNeighbor> vizinhos) {
        if (switchId != null && vizinhos != null && !vizinhos.isEmpty()) {
            lldpPorSwitch.computeIfAbsent(switchId, k -> new ArrayList<>()).addAll(vizinhos);
            mapaVisibilidadeCache = null;
        }
    }

    public void registrarStp(String switchId, List<StpPortState> estadosStp) {
        if (switchId != null && estadosStp != null && !estadosStp.isEmpty()) {
            stpPorSwitch.computeIfAbsent(switchId, k -> new ArrayList<>()).addAll(estadosStp);
        }
    }

    public void registrarLoopbackHardware(String switchId, List<LoopDiagnostic> diagnosticos) {
        if (switchId != null && diagnosticos != null && !diagnosticos.isEmpty()) {
            loopbackHardwarePorSwitch.computeIfAbsent(switchId, k -> new ArrayList<>()).addAll(diagnosticos);
        }
    }

    public void registrarAggregacoes(String switchId, Map<String, List<String>> baggs) {
        if (switchId != null && baggs != null && !baggs.isEmpty()) {
            baggsPorSwitch.put(switchId, baggs);
        }
    }

    public List<MacEntry> entradasDe(String switchId) {
        return Collections.unmodifiableList(entradasPorSwitch.getOrDefault(switchId, List.of()));
    }

    public List<ArpEntry> arpDe(String switchId) {
        return Collections.unmodifiableList(arpPorSwitch.getOrDefault(switchId, List.of()));
    }

    public List<LldpNeighbor> lldpDe(String switchId) {
        return Collections.unmodifiableList(lldpPorSwitch.getOrDefault(switchId, List.of()));
    }

    public List<StpPortState> stpDe(String switchId) {
        return Collections.unmodifiableList(stpPorSwitch.getOrDefault(switchId, List.of()));
    }

    public Optional<String> rotaPadraoDe(String switchId) {
        return Optional.ofNullable(rotaPadraoPorSwitch.get(switchId));
    }

    public void definirGatewayManual(String ip, String mac) {
        this.gatewayIpManual = (ip != null && !ip.isBlank()) ? ip.trim() : null;
        this.gatewayMacManual = (mac != null && !mac.isBlank()) ? Serie3000Parser.formatarMacPadrao(mac.trim()) : null;
        mapaVisibilidadeCache = null;
    }

    public void definirGatewayFallbackLocal(GatewayInfo gateway) {
        this.gatewayFallbackLocal = gateway;
        mapaVisibilidadeCache = null;
    }

    public void registrarMacExternoParaIp(String ip, String mac) {
        if (ip != null && !ip.isBlank() && mac != null && !mac.isBlank()) {
            String fmt = Serie3000Parser.formatarMacPadrao(mac.trim());
            if (LocalGatewayDetector.ehMacUnicastValido(MacUtils.normalizar(fmt))) {
                macExternoPorIp.put(ip.trim(), fmt);
                mapaVisibilidadeCache = null;
            }
        }
    }

    /**
     * Registra um equipamento descoberto ou alvo de coleta que não pôde ser coletado via CLI
     * (ex.: falha de autenticação, marcado como "sem senha" ou produto sem suporte CLI).
     */
    public void registrarEquipamentoSemAcesso(EquipamentoSemAcesso eq) {
        if (eq == null || eq.ipBase().isBlank() || ehIpDeSwitchRegistrado(eq.ipBase())) {
            return;
        }
        equipamentosSemAcessoPorIp.put(eq.ipBase(), eq);
        if (!eq.macNormalizado().isBlank()) {
            registrarMacExternoParaIp(eq.ipBase(), Serie3000Parser.formatarMacPadrao(eq.macNormalizado()));
        }
    }

    /**
     * Lista equipamentos sem acesso CLI enriquecidos com MAC resolvido (quando encontrado em ARP/scan).
     */
    public List<EquipamentoSemAcesso> equipamentosSemAcesso() {
        List<EquipamentoSemAcesso> lista = new ArrayList<>();
        for (EquipamentoSemAcesso eq : equipamentosSemAcessoPorIp.values()) {
            if (ehIpDeSwitchRegistrado(eq.ipBase())) {
                continue;
            }
            String macResolvido = resolverMacParaEquipamentoSemAcesso(eq);
            if (!macResolvido.equals(eq.macNormalizado())) {
                lista.add(new EquipamentoSemAcesso(
                        eq.ip(), eq.rotuloModelo(), eq.categoria(), macResolvido, eq.motivo()));
            } else {
                lista.add(eq);
            }
        }
        return lista;
    }

    public boolean possuiInfraestruturaSemAcesso() {
        return equipamentosSemAcesso().stream().anyMatch(EquipamentoSemAcesso::ehComutadorInfra);
    }

    public String resolverMacParaEquipamentoSemAcesso(EquipamentoSemAcesso eq) {
        if (eq == null) {
            return "";
        }
        if (!eq.macNormalizado().isBlank()) {
            return eq.macNormalizado();
        }
        String externo = macExternoPorIp.get(eq.ipBase());
        if (externo != null && !externo.isBlank()) {
            return MacUtils.normalizar(externo);
        }
        return buscarMacPorIpNoArpDosSwitches(eq.ipBase())
                .map(MacUtils::normalizar)
                .orElse("");
    }

    private static String ipBase(String ip) {
        if (ip == null) {
            return "";
        }
        int idx = ip.indexOf(':');
        return (idx > 0 ? ip.substring(0, idx) : ip).trim();
    }

    /**
     * Retorna o IP de gateway (manual ou aprendido via rota padrão dos switches) caso ainda não haja
     * MAC correspondente nas tabelas ARP dos próprios switches, permitindo resolução no ARP do PC local.
     */
    public Optional<String> ipGatewaySemMacResolvido() {
        if (gatewayIpManual != null && (gatewayMacManual == null || gatewayMacManual.isBlank())) {
            if (buscarMacPorIpNoArpDosSwitches(gatewayIpManual).isEmpty()) {
                return Optional.of(gatewayIpManual);
            }
        }
        for (String gwIp : candidatosIpRotaPadraoOrdenados()) {
            if (!ehIpDeSwitchRegistrado(gwIp) && buscarMacPorIpNoArpDosSwitches(gwIp).isEmpty()) {
                return Optional.of(gwIp);
            }
        }
        return Optional.empty();
    }

    /**
     * Retorna o Roteador/Gateway efetivamente identificado e correlacionado a pelo menos um switch coletado.
     */
    public Optional<GatewayInfo> gatewayIdentificado() {
        Optional<GatewayInfo> cand = resolverGatewayCandidato();
        if (cand.isEmpty()) {
            return Optional.empty();
        }
        return identificarConexoesGateway().isEmpty() ? Optional.empty() : cand;
    }

    /**
     * Verifica se a porta informada de um switch é um ponto de entrada físico para o Roteador/Gateway.
     */
    public boolean ehPortaDeEntradaGateway(String switchId, String porta) {
        if (switchId == null || porta == null) return false;
        for (ConexaoGateway cg : identificarConexoesGateway()) {
            if (cg.switchCore().macNormalizado().equalsIgnoreCase(switchId)
                    && cg.porta().equalsIgnoreCase(porta)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Indica se o switch informado está na fronteira superior coletada voltada ao Roteador/Gateway
     * (ou seja, aparece em identificarConexoesGateway()).
     */
    public boolean ehNoNaFronteiraGateway(String switchId) {
        if (switchId == null) return false;
        for (ConexaoGateway cg : identificarConexoesGateway()) {
            if (cg.switchCore().macNormalizado().equalsIgnoreCase(switchId)) {
                return true;
            }
        }
        return false;
    }

    public boolean ehUplinkDeFronteiraGateway(String switchId, String porta) {
        return ehPortaDeEntradaGateway(switchId, porta);
    }

    /**
     * Analisa como o Gateway se conecta à borda superior coletada, separando conexões diretas
     * confirmadas, conexões mediadas por LLDP de switch/OLT externo não coletado e uplinks em
     * domínio L2 incerto (quando switches core falharam na coleta ou 3+ folhas compartilham a
     * mesma nuvem L2 de uplink).
     */
    public PlanoGateway analisarPlanoGateway() {
        Optional<GatewayInfo> gwOpt = gatewayIdentificado();
        if (gwOpt.isEmpty()) {
            return new PlanoGateway(null, List.of(), List.of(), List.of(), List.of());
        }

        GatewayInfo gw = gwOpt.get();
        String gwNorm = gw.macNormalizado();
        List<ConexaoGateway> todas = identificarConexoesGateway();
        if (todas.isEmpty()) {
            return new PlanoGateway(gw, List.of(), List.of(), List.of(), List.of());
        }

        List<ConexaoPassiva> passivas = resolverConexoesPassivasSemAcesso();
        Set<String> ipsPassivos = new HashSet<>();
        for (ConexaoPassiva cp : passivas) {
            ipsPassivos.add(cp.equipamento().ipBase());
        }

        List<EquipamentoSemAcesso> suspectsInfraUpstream = new ArrayList<>();
        for (EquipamentoSemAcesso eq : equipamentosSemAcesso()) {
            if (eq.ehComutadorInfra() && !ipsPassivos.contains(eq.ipBase())) {
                suspectsInfraUpstream.add(eq);
            }
        }

        List<ConexaoLldpUpstream> viaLldpExterno = new ArrayList<>();
        List<ConexaoGateway> restantes = new ArrayList<>();
        Set<String> idsLldpExterno = new HashSet<>();

        for (ConexaoGateway cg : todas) {
            String swId = cg.switchCore().macNormalizado();
            Optional<LldpNeighbor> lldpSwOpt = encontrarVizinhoLldpExternoNaPorta(swId, cg.porta());
            if (lldpSwOpt.isPresent()) {
                viaLldpExterno.add(new ConexaoLldpUpstream(cg.switchCore(), cg.porta(), lldpSwOpt.get()));
                idsLldpExterno.add(swId);
            } else {
                restantes.add(cg);
            }
        }

        Map<String, SwitchIdentity> restantesPorId = new LinkedHashMap<>();
        for (ConexaoGateway cg : restantes) {
            restantesPorId.put(cg.switchCore().macNormalizado(), cg.switchCore());
        }

        int mutuaVisibilidade = 0;
        int hostsExtrasAlemGatewayMax = 0;
        int hostsExtrasIntelbrasMax = 0;
        boolean algumCgVeMacDeSuspeito = false;
        boolean restanteVeLldpExterno = false;

        for (ConexaoGateway cg : restantes) {
            String swId = cg.switchCore().macNormalizado();
            PortaAnalise pa = analisarPortas(swId).get(cg.porta());
            if (pa == null) continue;

            boolean veOutro = false;
            for (SwitchIdentity vis : pa.switchesVisiveis()) {
                String visId = vis.macNormalizado();
                if (!visId.equals(swId) && restantesPorId.containsKey(visId)) {
                    veOutro = true;
                }
                if (idsLldpExterno.contains(visId)) {
                    restanteVeLldpExterno = true;
                }
            }
            if (veOutro) {
                mutuaVisibilidade++;
            }

            int extras = 0;
            int extrasIntelbras = 0;
            for (String macHost : pa.macsHostsDesconhecidos()) {
                String norm = MacUtils.normalizar(macHost);
                if (norm.isBlank() || MacUtils.pertenceAoSwitch(norm, gwNorm)) {
                    continue;
                }
                extras++;
                if (OuiLookup.ehIntelbras(norm)) {
                    extrasIntelbras++;
                }
                for (EquipamentoSemAcesso eq : suspectsInfraUpstream) {
                    if (!eq.macNormalizado().isBlank() && MacUtils.pertenceAoSwitch(norm, eq.macNormalizado())) {
                        algumCgVeMacDeSuspeito = true;
                    }
                }
            }
            hostsExtrasAlemGatewayMax = Math.max(hostsExtrasAlemGatewayMax, extras);
            hostsExtrasIntelbrasMax = Math.max(hostsExtrasIntelbrasMax, extrasIntelbras);
        }

        boolean nenhumSuspeitoTemMac = suspectsInfraUpstream.stream()
                .allMatch(eq -> eq.macNormalizado().isBlank());

        boolean segmentoIncerto = false;
        if (!suspectsInfraUpstream.isEmpty()) {
            if (restantes.size() >= 2) {
                segmentoIncerto = true;
            } else if (restantes.size() == 1 && (algumCgVeMacDeSuspeito
                    || (nenhumSuspeitoTemMac && hostsExtrasIntelbrasMax >= 1))) {
                segmentoIncerto = true;
            }
        }
        if (!segmentoIncerto) {
            if (restantes.size() >= 3 && mutuaVisibilidade >= 2) {
                segmentoIncerto = true;
            } else if (restantes.size() == 2 && mutuaVisibilidade >= 2 && hostsExtrasAlemGatewayMax >= 5) {
                segmentoIncerto = true;
            } else if (!viaLldpExterno.isEmpty() && restanteVeLldpExterno) {
                segmentoIncerto = true;
            }
        }

        if (segmentoIncerto) {
            return new PlanoGateway(gw, List.of(), List.copyOf(restantes), List.copyOf(viaLldpExterno),
                    List.copyOf(suspectsInfraUpstream));
        }
        return new PlanoGateway(gw, List.copyOf(restantes), List.of(), List.copyOf(viaLldpExterno),
                List.of());
    }

    private Optional<LldpNeighbor> encontrarVizinhoLldpExternoNaPorta(String switchId, String porta) {
        if (switchId == null || porta == null) {
            return Optional.empty();
        }
        boolean temRouterGatewayNaPorta = false;
        LldpNeighbor switchExterno = null;
        for (VizinhoLldpClassificado v : vizinhosLldpDeInteresse(switchId)) {
            if (v.neighbor().localPort() == null || !v.neighbor().localPort().equalsIgnoreCase(porta)) {
                continue;
            }
            if (v.categoria() == CategoriaInteresse.ROTEADOR_UPLINK) {
                temRouterGatewayNaPorta = true;
            } else if (v.categoria() == CategoriaInteresse.SWITCH_OU_OLT_EXTERNO && switchExterno == null) {
                switchExterno = v.neighbor();
            }
        }
        if (temRouterGatewayNaPorta) {
            return Optional.empty();
        }
        return Optional.ofNullable(switchExterno);
    }

    /**
     * Tenta posicionar passivamente equipamentos sem acesso CLI (ou que falharam autenticação) em
     * portas de acesso únicas de switches coletados usando MAC conhecido no scan/ARP.
     */
    public List<ConexaoPassiva> resolverConexoesPassivasSemAcesso() {
        List<EquipamentoSemAcesso> todos = equipamentosSemAcesso();
        if (todos.isEmpty() || switchesPorId.isEmpty()) {
            return List.of();
        }

        List<VizinhoLldpClassificado> todosLldp = new ArrayList<>();
        for (SwitchIdentity sw : switchesPorId.values()) {
            todosLldp.addAll(vizinhosLldpDeInteresse(sw.macNormalizado()));
        }

        Map<String, Map<String, PortaAnalise>> analisesPorSwitch = new LinkedHashMap<>();
        for (SwitchIdentity sw : switchesPorId.values()) {
            analisesPorSwitch.put(sw.macNormalizado(), analisarPortas(sw.macNormalizado()));
        }

        List<ConexaoPassiva> resultado = new ArrayList<>();
        for (EquipamentoSemAcesso eq : todos) {
            if (jaRepresentadoPorLldp(eq, todosLldp)) {
                continue;
            }
            String macEq = eq.macNormalizado();
            if (macEq.isBlank()) {
                continue;
            }

            List<ConexaoPassiva> candidatosExatos = new ArrayList<>();
            List<ConexaoPassiva> candidatosPorOffset = new ArrayList<>();
            for (SwitchIdentity sw : switchesPorId.values()) {
                String swId = sw.macNormalizado();
                Map<String, PortaAnalise> portas = analisesPorSwitch.getOrDefault(swId, Map.of());
                for (PortaAnalise pa : portas.values()) {
                    if (!pa.switchesVisiveis().isEmpty()) {
                        continue;
                    }
                    if (ehPortaDeEntradaGateway(swId, pa.porta())) {
                        continue;
                    }
                    boolean portaUplinkExterno = false;
                    for (VizinhoLldpClassificado v : vizinhosLldpDeInteresse(swId)) {
                        if (v.neighbor().localPort() != null
                                && v.neighbor().localPort().equalsIgnoreCase(pa.porta())
                                && (v.categoria() == CategoriaInteresse.ROTEADOR_UPLINK
                                || v.categoria() == CategoriaInteresse.SWITCH_OU_OLT_EXTERNO)) {
                            portaUplinkExterno = true;
                            break;
                        }
                    }
                    if (portaUplinkExterno) {
                        continue;
                    }
                    boolean encontrouExato = false;
                    boolean encontrouPorOffset = false;
                    for (String macHost : pa.macsHostsDesconhecidos()) {
                        String norm = MacUtils.normalizar(macHost);
                        if (norm.isBlank()) {
                            continue;
                        }
                        if (norm.equalsIgnoreCase(macEq)) {
                            encontrouExato = true;
                            break;
                        }
                        if (MacUtils.pertenceAoSwitch(macEq, norm)) {
                            encontrouPorOffset = true;
                        }
                    }
                    if (encontrouExato) {
                        candidatosExatos.add(new ConexaoPassiva(sw, pa.porta(), eq));
                    } else if (encontrouPorOffset) {
                        candidatosPorOffset.add(new ConexaoPassiva(sw, pa.porta(), eq));
                    }
                }
            }

            List<ConexaoPassiva> escolhidos = !candidatosExatos.isEmpty() ? candidatosExatos : candidatosPorOffset;
            if (escolhidos.size() == 1) {
                resultado.add(escolhidos.get(0));
            }
        }
        return resultado;
    }

    /**
     * Retorna os equipamentos sem acesso que não puderam ser conectados passivamente em nenhuma porta
     * (nem representados por LLDP), para exibição no inventário de pendências do relatório.
     */
    public List<EquipamentoSemAcesso> equipamentosSemAcessoIsolados(List<ConexaoPassiva> passivas) {
        Set<String> ipsPosicionados = new HashSet<>();
        if (passivas != null) {
            for (ConexaoPassiva cp : passivas) {
                ipsPosicionados.add(cp.equipamento().ipBase());
            }
        }
        List<VizinhoLldpClassificado> todosLldp = new ArrayList<>();
        for (SwitchIdentity sw : switchesPorId.values()) {
            todosLldp.addAll(vizinhosLldpDeInteresse(sw.macNormalizado()));
        }

        List<EquipamentoSemAcesso> isolados = new ArrayList<>();
        for (EquipamentoSemAcesso eq : equipamentosSemAcesso()) {
            if (!ipsPosicionados.contains(eq.ipBase()) && !jaRepresentadoPorLldp(eq, todosLldp)) {
                isolados.add(eq);
            }
        }
        return isolados;
    }

    private boolean jaRepresentadoPorLldp(EquipamentoSemAcesso eq, List<VizinhoLldpClassificado> todosLldp) {
        String ipEq = eq.ipBase();
        String macEq = eq.macNormalizado();
        for (VizinhoLldpClassificado v : todosLldp) {
            if (!ipEq.isBlank() && ipEq.equals(ipBase(v.neighbor().managementIp()))) {
                return true;
            }
            if (!macEq.isBlank() && v.neighbor().possuiChassisMacValido()) {
                String chNorm = v.neighbor().chassisMacNormalizado();
                if (LocalGatewayDetector.ehMacUnicastValido(chNorm) && MacUtils.pertenceAoSwitch(chNorm, macEq)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Descobre em qual(is) switch(es) e porta(s) o Roteador/Gateway entra fisicamente na topologia coletada.
     *
     * Regra de rastreamento L2:
     *  - Se um switch vê o MAC do gateway em uma porta sem outros switches coletados, essa porta liga
     *    diretamente ao Roteador/Gateway (ou segmento upstream externo).
     *  - Se vê o MAC do gateway em uma porta que também vê outros switches coletados, só descarta como
     *    porta de trânsito caso algum desses switches veja o gateway em OUTRA porta mais adiante
     *    (afastando-se do switch atual). Isso permite detectar tanto 1 core único quanto múltiplos cores
     *    ligados diretamente em portas LAN em bridge do mesmo roteador.
     */
    public List<ConexaoGateway> identificarConexoesGateway() {
        Optional<GatewayInfo> gwOpt = resolverGatewayCandidato();
        if (gwOpt.isEmpty()) {
            return List.of();
        }

        String gwMac = gwOpt.get().macNormalizado();
        Map<String, String> portaOndeVeGw = mapearPortaGatewayPorSwitch(gwMac);
        if (portaOndeVeGw.isEmpty()) {
            return List.of();
        }

        Map<String, Map<String, Set<SwitchIdentity>>> visiveisGlobal = construirMapaVisibilidade();
        List<ConexaoGateway> conexoes = new ArrayList<>();
        Set<String> chavesAdicionadas = new HashSet<>();

        for (SwitchIdentity swS : switchesPorId.values()) {
            String idS = swS.macNormalizado();
            String pS = portaOndeVeGw.get(idS);
            if (pS == null) continue;

            // Dominância por Split-Horizon: se QUALQUER outro switch X enxerga S em uma porta P1
            // e enxerga o Gateway em OUTRA porta P2 != P1, então X está fisicamente entre S e o Gateway
            // (mesmo que a tabela MAC de S tenha vindo vazia e S só tenha aprendido o Gateway via ARP).
            if (existeSwitchIntermediarioAteGateway(swS, portaOndeVeGw, visiveisGlobal)) {
                continue;
            }

            Set<SwitchIdentity> visiveisNaPorta = visiveisGlobal
                    .getOrDefault(idS, Map.of())
                    .getOrDefault(pS, Set.of());

            if (visiveisNaPorta.isEmpty()) {
                adicionarConexaoGatewayUnica(conexoes, chavesAdicionadas, swS, pS);
                continue;
            }

            // Verifica se algum switch visível nessa porta enxerga S em uma porta diferente daquela em que vê o Gateway
            boolean existeSwitchMaisProximoDoGateway = false;
            for (SwitchIdentity swW : visiveisNaPorta) {
                String idW = swW.macNormalizado();
                String pW = portaOndeVeGw.get(idW);
                if (pW == null) continue;

                Optional<String> portaRetornoWParaS = encontrarPortaUnicaOndeVe(visiveisGlobal, idW, swS);
                if (portaRetornoWParaS.isPresent() && !pW.equalsIgnoreCase(portaRetornoWParaS.get())) {
                    existeSwitchMaisProximoDoGateway = true;
                    break;
                }
            }

            if (existeSwitchMaisProximoDoGateway) {
                continue;
            }

            // Caso de aging: se há vizinho direto único V que não registrou o MAC do gateway na própria tabela,
            // mas o cabo S <-> V é ponto a ponto puro (sem hosts extras na porta de retorno de V), o gateway está atrás de V.
            ResultadoInferencia r = inferirVizinhoDireto(idS, visiveisNaPorta);
            if ((r.tipo() == ResultadoInferencia.Tipo.DIRETO || r.tipo() == ResultadoInferencia.Tipo.DIRETO_PARCIAL)
                    && !r.candidatos().isEmpty()) {
                SwitchIdentity vizDireto = r.candidatos().get(0);
                String idViz = vizDireto.macNormalizado();
                if (!portaOndeVeGw.containsKey(idViz)
                        && !existeSwitchIntermediarioAteGateway(vizDireto, portaOndeVeGw, visiveisGlobal)) {
                    Optional<String> pRetOpt = portaRemota(idS, idViz);
                    if (pRetOpt.isPresent() && contarHostsDesconhecidosNaPorta(idViz, pRetOpt.get()) == 0) {
                        adicionarConexaoGatewayUnica(conexoes, chavesAdicionadas, vizDireto, "?");
                        continue;
                    }
                }
            }

            adicionarConexaoGatewayUnica(conexoes, chavesAdicionadas, swS, pS);
        }

        return conexoes;
    }

    private boolean existeSwitchIntermediarioAteGateway(
            SwitchIdentity swAlvo,
            Map<String, String> portaOndeVeGw,
            Map<String, Map<String, Set<SwitchIdentity>>> visiveisGlobal
    ) {
        String idAlvo = swAlvo.macNormalizado();
        for (SwitchIdentity swX : switchesPorId.values()) {
            String idX = swX.macNormalizado();
            if (idX.equals(idAlvo)) continue;

            String pGwEmX = portaOndeVeGw.get(idX);
            if (pGwEmX == null) continue;

            Map<String, Set<SwitchIdentity>> portasX = visiveisGlobal.getOrDefault(idX, Map.of());
            for (Map.Entry<String, Set<SwitchIdentity>> entry : portasX.entrySet()) {
                if (!entry.getKey().equalsIgnoreCase(pGwEmX) && entry.getValue().contains(swAlvo)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void adicionarConexaoGatewayUnica(
            List<ConexaoGateway> lista,
            Set<String> chaves,
            SwitchIdentity sw,
            String porta
    ) {
        String chave = sw.macNormalizado() + "::" + porta.toUpperCase();
        if (chaves.add(chave)) {
            lista.add(new ConexaoGateway(sw, porta));
        }
    }

    private int contarHostsDesconhecidosNaPorta(String switchId, String porta) {
        int count = 0;
        for (MacEntry e : entradasPorSwitch.getOrDefault(switchId, List.of())) {
            if (e.port().equalsIgnoreCase(porta) && identificarSwitchPorMac(e.macNormalizado()).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private Map<String, String> mapearPortaGatewayPorSwitch(String gwMacNormalizado) {
        Map<String, String> resultado = new LinkedHashMap<>();
        for (String idS : switchesPorId.keySet()) {
            String portaEncontrada = null;
            for (LldpNeighbor n : lldpPorSwitch.getOrDefault(idS, List.of())) {
                if (n.possuiChassisMacValido()
                        && (n.chassisMacNormalizado().equalsIgnoreCase(gwMacNormalizado)
                        || MacUtils.pertenceAoSwitch(n.chassisMacNormalizado(), gwMacNormalizado))
                        && n.localPort() != null && !n.localPort().isBlank()) {
                    portaEncontrada = n.localPort();
                    break;
                }
            }
            if (portaEncontrada == null) {
                for (MacEntry e : entradasPorSwitch.getOrDefault(idS, List.of())) {
                    if (e.macNormalizado().equalsIgnoreCase(gwMacNormalizado)) {
                        portaEncontrada = e.port();
                        break;
                    }
                }
            }
            if (portaEncontrada == null) {
                for (ArpEntry a : arpPorSwitch.getOrDefault(idS, List.of())) {
                    if (a.macNormalizado().equalsIgnoreCase(gwMacNormalizado)
                            && a.port() != null && !a.port().isBlank()) {
                        portaEncontrada = alinharNomePortaComTabelaMac(idS, a.port());
                        break;
                    }
                }
            }
            if (portaEncontrada != null) {
                resultado.put(idS, portaEncontrada);
            }
        }
        return resultado;
    }

    private String alinharNomePortaComTabelaMac(String switchId, String portaArp) {
        String prefixo = portaArp.trim().toUpperCase() + " (";
        for (MacEntry e : entradasPorSwitch.getOrDefault(switchId, List.of())) {
            if (e.port().equalsIgnoreCase(portaArp) || e.port().toUpperCase().startsWith(prefixo)) {
                return e.port();
            }
        }
        return portaArp.trim();
    }

    private Optional<GatewayInfo> resolverGatewayCandidato() {
        // 1) Prioridade máxima: diretiva manual (# gateway=...)
        if ((gatewayIpManual != null && !gatewayIpManual.isBlank())
                || (gatewayMacManual != null && !gatewayMacManual.isBlank())) {
            String ip = gatewayIpManual;
            String mac = gatewayMacManual;

            if ((mac == null || mac.isBlank()) && ip != null) {
                mac = buscarMacPorIpNoArpDosSwitches(ip).orElse(macExternoPorIp.get(ip));
            }
            if (mac != null && LocalGatewayDetector.ehMacUnicastValido(MacUtils.normalizar(mac))) {
                if (ip == null || ip.isBlank()) {
                    ip = buscarIpPorMacNoArpDosSwitches(MacUtils.normalizar(mac)).orElse(null);
                }
                return Optional.of(new GatewayInfo(ip, Serie3000Parser.formatarMacPadrao(mac)));
            }
        }

        // 2) Descoberta automática via CLI dos switches (rota 0.0.0.0/0 + tabela ARP)
        for (String gwIp : candidatosIpRotaPadraoOrdenados()) {
            if (ehIpDeSwitchRegistrado(gwIp)) {
                continue;
            }
            String mac = buscarMacPorIpNoArpDosSwitches(gwIp).orElse(macExternoPorIp.get(gwIp));
            if (mac != null) {
                String norm = MacUtils.normalizar(mac);
                if (LocalGatewayDetector.ehMacUnicastValido(norm) && identificarSwitchPorMac(norm).isEmpty()) {
                    return Optional.of(new GatewayInfo(gwIp, Serie3000Parser.formatarMacPadrao(mac)));
                }
            }
        }

        // 3) Fallback automático: gateway padrão do PC local (quando na mesma sub-rede dos switches)
        if (gatewayFallbackLocal != null && gatewayFallbackLocal.mac() != null) {
            String norm = gatewayFallbackLocal.macNormalizado();
            if (LocalGatewayDetector.ehMacUnicastValido(norm)
                    && !ehIpDeSwitchRegistrado(gatewayFallbackLocal.ip())
                    && identificarSwitchPorMac(norm).isEmpty()) {
                return Optional.of(new GatewayInfo(
                        gatewayFallbackLocal.ip(),
                        Serie3000Parser.formatarMacPadrao(gatewayFallbackLocal.mac())
                ));
            }
        }

        // 4) Fallback via LLDP: roteador de uplink anunciado diretamente pelos switches coletados
        for (List<LldpNeighbor> vizinhos : lldpPorSwitch.values()) {
            for (LldpNeighbor n : vizinhos) {
                if (LldpInterestFilter.classificar(n, switchesPorId.values(), Optional.empty())
                        == CategoriaInteresse.ROTEADOR_UPLINK && n.possuiChassisMacValido()) {
                    String macFmt = Serie3000Parser.formatarMacPadrao(n.chassisMacNormalizado());
                    String ipGw = (n.managementIp() != null && !n.managementIp().isBlank())
                            ? n.managementIp()
                            : buscarIpPorMacNoArpDosSwitches(n.chassisMacNormalizado()).orElse(null);
                    return Optional.of(new GatewayInfo(ipGw, macFmt));
                }
            }
        }

        return Optional.empty();
    }

    private List<String> candidatosIpRotaPadraoOrdenados() {
        Map<String, Integer> contagem = new LinkedHashMap<>();
        for (String ip : rotaPadraoPorSwitch.values()) {
            contagem.merge(ip, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entradas = new ArrayList<>(contagem.entrySet());
        entradas.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> resultado = new ArrayList<>(entradas.size());
        for (Map.Entry<String, Integer> e : entradas) {
            resultado.add(e.getKey());
        }
        return resultado;
    }

    private boolean ehIpDeSwitchRegistrado(String ip) {
        if (ip == null) return false;
        for (SwitchIdentity sw : switchesPorId.values()) {
            if (sw.ip() == null) continue;
            String swIp = sw.ip();
            int idxPorta = swIp.indexOf(':');
            if (idxPorta > 0) {
                swIp = swIp.substring(0, idxPorta);
            }
            if (ip.equals(swIp)) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> buscarMacPorIpNoArpDosSwitches(String ipAlvo) {
        if (ipAlvo == null) return Optional.empty();
        for (List<ArpEntry> lista : arpPorSwitch.values()) {
            for (ArpEntry a : lista) {
                if (ipAlvo.equals(a.ip()) && LocalGatewayDetector.ehMacUnicastValido(a.macNormalizado())) {
                    return Optional.of(Serie3000Parser.formatarMacPadrao(a.mac()));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> buscarIpPorMacNoArpDosSwitches(String macNormalizado) {
        if (macNormalizado == null) return Optional.empty();
        for (List<ArpEntry> lista : arpPorSwitch.values()) {
            for (ArpEntry a : lista) {
                if (macNormalizado.equalsIgnoreCase(a.macNormalizado()) && a.ip() != null && !a.ip().isBlank()) {
                    return Optional.of(a.ip());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Identifica se um MAC normalizado pertence a algum dos switches registrados
     * (usando comparação por distância/offset numérico de 48 bits em MacUtils).
     */
    public Optional<SwitchIdentity> identificarSwitchPorMac(String macNormalizado) {
        SwitchIdentity melhor = null;
        long menorDistancia = Long.MAX_VALUE;

        for (SwitchIdentity candidato : switchesPorId.values()) {
            if (MacUtils.pertenceAoSwitch(candidato.macNormalizado(), macNormalizado)) {
                long dist = MacUtils.distancia(candidato.macNormalizado(), macNormalizado);
                if (melhor == null || dist < menorDistancia) {
                    melhor = candidato;
                    menorDistancia = dist;
                }
            }
        }
        return Optional.ofNullable(melhor);
    }

    /** Todos os switches registrados até agora (via registrarSwitch), na ordem em que foram adicionados. */
    public Collection<SwitchIdentity> switchesRegistrados() {
        return switchesPorId.values();
    }

    /**
     * Constrói o mapa (switchId -> porta -> switches visíveis), iniciando pelos MACs
     * aprendidos diretamente na tabela MAC e enriquecendo com inferência de árvore L2:
     *
     *  - Regra 1 (Intermediário omitido por aging): Se X vê Z na porta Px, e um switch Y
     *    vê X na porta Py mas vê Z em outra porta Py2 != Py, então Y está fisicamente no
     *    caminho entre (X, Px) e Z. Logo, Y é visível em (X, Px).
     *  - Regra 2 (Switch distante omitido por aging): Se X vê Y na porta Px, e Y vê X na
     *    porta Py e vê Z em outra porta Py2 != Py (sem que X veja Z em outra porta), então
     *    Z está atrás de Y a partir de (X, Px).
     */
    private Map<String, Map<String, Set<SwitchIdentity>>> construirMapaVisibilidade() {
        if (mapaVisibilidadeCache != null) {
            return mapaVisibilidadeCache;
        }
        Map<String, Map<String, Set<SwitchIdentity>>> visiveis = new LinkedHashMap<>();

        for (String switchId : switchesPorId.keySet()) {
            Map<String, Set<SwitchIdentity>> porPorta = new LinkedHashMap<>();
            for (MacEntry entry : entradasPorSwitch.getOrDefault(switchId, List.of())) {
                Optional<SwitchIdentity> vizinho = identificarSwitchPorMac(entry.macNormalizado());
                if (vizinho.isPresent() && vizinho.get().macNormalizado().equals(switchId)) {
                    continue;
                }
                porPorta.computeIfAbsent(entry.port(), k -> new LinkedHashSet<>());
                vizinho.ifPresent(sw -> porPorta.get(entry.port()).add(sw));
            }
            // Reforça visibilidade direta nas portas onde o LLDP já confirmou outro switch coletado
            for (LldpNeighbor lldp : lldpPorSwitch.getOrDefault(switchId, List.of())) {
                if (lldp.localPort() == null || lldp.localPort().isBlank()) continue;
                Optional<SwitchIdentity> vizColetado = LldpInterestFilter.resolverSwitchColetado(lldp, switchesPorId.values());
                if (vizColetado.isPresent() && !vizColetado.get().macNormalizado().equals(switchId)) {
                    porPorta.computeIfAbsent(lldp.localPort(), k -> new LinkedHashSet<>()).add(vizColetado.get());
                }
            }
            visiveis.put(switchId, porPorta);
        }

        // Recupera visibilidade da porta de uplink quando um switch de ponta veio com 0 entradas MAC L2,
        // mas aprendeu o MAC do Gateway em uma porta física via ARP e é visto exclusivamente em outra porta por seu switch pai.
        recuperarVisibilidadeUplinkViaArp(visiveis);

        boolean alterou;
        int iteracoes = 0;
        int maxIteracoes = Math.max(2, switchesPorId.size() * 2);

        do {
            alterou = false;
            iteracoes++;

            // Regra 1: Intermediário Y entre (X, Px) e Z
            for (SwitchIdentity swX : switchesPorId.values()) {
                String idX = swX.macNormalizado();
                Map<String, Set<SwitchIdentity>> portasX = visiveis.getOrDefault(idX, Map.of());

                for (Map.Entry<String, Set<SwitchIdentity>> entryPx : portasX.entrySet()) {
                    Set<SwitchIdentity> vistosEmPx = entryPx.getValue();
                    if (vistosEmPx.isEmpty()) continue;

                    for (SwitchIdentity swY : switchesPorId.values()) {
                        String idY = swY.macNormalizado();
                        if (idY.equals(idX) || vistosEmPx.contains(swY)) continue;

                        Optional<String> pyOpt = encontrarPortaUnicaOndeVe(visiveis, idY, swX);
                        if (pyOpt.isEmpty()) continue;
                        String py = pyOpt.get();

                        Set<SwitchIdentity> vistosEmPyDeY = visiveis.getOrDefault(idY, Map.of())
                                .getOrDefault(py, Set.of());
                        Set<SwitchIdentity> vistosAlemDePyEmY = coletarForaDaPorta(visiveis, idY, py);

                        boolean compartilhaDestinoAlemDeY = false;
                        for (SwitchIdentity swZ : vistosEmPx) {
                            if (vistosAlemDePyEmY.contains(swZ) && !vistosEmPyDeY.contains(swZ)) {
                                compartilhaDestinoAlemDeY = true;
                                break;
                            }
                        }

                        if (compartilhaDestinoAlemDeY) {
                            if (vistosEmPx.add(swY)) {
                                alterou = true;
                            }
                        }
                    }
                }
            }

            // Regra 2: Propagação transitiva de Z (visto por Y além de Py) para (X, Px)
            for (SwitchIdentity swX : switchesPorId.values()) {
                String idX = swX.macNormalizado();
                Map<String, Set<SwitchIdentity>> portasX = visiveis.getOrDefault(idX, Map.of());

                for (Map.Entry<String, Set<SwitchIdentity>> entryPx : portasX.entrySet()) {
                    String px = entryPx.getKey();
                    Set<SwitchIdentity> vistosEmPx = entryPx.getValue();
                    if (vistosEmPx.isEmpty()) continue;

                    Set<SwitchIdentity> vistosNoutrasPortasDeX = coletarForaDaPorta(visiveis, idX, px);

                    for (SwitchIdentity swY : new ArrayList<>(vistosEmPx)) {
                        String idY = swY.macNormalizado();
                        Optional<String> pyOpt = encontrarPortaUnicaOndeVe(visiveis, idY, swX);
                        if (pyOpt.isEmpty()) continue;
                        String py = pyOpt.get();

                        Set<SwitchIdentity> vistosAlemDePyEmY = coletarForaDaPorta(visiveis, idY, py);
                        for (SwitchIdentity swZ : vistosAlemDePyEmY) {
                            if (!swZ.macNormalizado().equals(idX) && !vistosNoutrasPortasDeX.contains(swZ)) {
                                if (vistosEmPx.add(swZ)) {
                                    alterou = true;
                                }
                            }
                        }
                    }
                }
            }
        } while (alterou && iteracoes < maxIteracoes);

        mapaVisibilidadeCache = visiveis;
        return visiveis;
    }

    private void recuperarVisibilidadeUplinkViaArp(Map<String, Map<String, Set<SwitchIdentity>>> visiveis) {
        Optional<GatewayInfo> gwOpt = resolverGatewayCandidato();
        if (gwOpt.isEmpty()) {
            return;
        }
        Map<String, String> portaOndeVeGw = mapearPortaGatewayPorSwitch(gwOpt.get().macNormalizado());
        if (portaOndeVeGw.isEmpty()) {
            return;
        }

        for (SwitchIdentity swS : switchesPorId.values()) {
            String idS = swS.macNormalizado();
            if (contarPortasComSwitches(visiveis, idS) > 0) {
                continue;
            }
            String pS = portaOndeVeGw.get(idS);
            if (pS == null) {
                continue;
            }

            Map<SwitchIdentity, String> portaNoCandidatoPai = new LinkedHashMap<>();
            for (SwitchIdentity swX : switchesPorId.values()) {
                String idX = swX.macNormalizado();
                if (idX.equals(idS)) continue;

                String pGwEmX = portaOndeVeGw.get(idX);
                if (pGwEmX == null) continue;

                Optional<String> pXOpt = encontrarPortaUnicaOndeVe(visiveis, idX, swS);
                if (pXOpt.isPresent() && !pXOpt.get().equalsIgnoreCase(pGwEmX)) {
                    portaNoCandidatoPai.put(swX, pXOpt.get());
                }
            }

            if (portaNoCandidatoPai.isEmpty()) {
                continue;
            }

            List<SwitchIdentity> maisProximos = new ArrayList<>();
            for (Map.Entry<SwitchIdentity, String> cand : portaNoCandidatoPai.entrySet()) {
                SwitchIdentity swX = cand.getKey();
                Set<SwitchIdentity> vistosEmPx = visiveis
                        .getOrDefault(swX.macNormalizado(), Map.of())
                        .getOrDefault(cand.getValue(), Set.of());

                boolean contemOutroIntermediario = false;
                for (SwitchIdentity outro : portaNoCandidatoPai.keySet()) {
                    if (!outro.equals(swX) && vistosEmPx.contains(outro)) {
                        contemOutroIntermediario = true;
                        break;
                    }
                }
                if (!contemOutroIntermediario) {
                    maisProximos.add(swX);
                }
            }

            if (maisProximos.size() == 1) {
                visiveis.computeIfAbsent(idS, k -> new LinkedHashMap<>())
                        .computeIfAbsent(pS, k -> new LinkedHashSet<>())
                        .add(maisProximos.get(0));
            }
        }
    }

    private Optional<String> encontrarPortaUnicaOndeVe(
            Map<String, Map<String, Set<SwitchIdentity>>> visiveis,
            String switchObservadorId,
            SwitchIdentity switchAlvo
    ) {
        String portaEncontrada = null;
        Map<String, Set<SwitchIdentity>> portas = visiveis.getOrDefault(switchObservadorId, Map.of());
        for (Map.Entry<String, Set<SwitchIdentity>> e : portas.entrySet()) {
            if (e.getValue().contains(switchAlvo)) {
                if (portaEncontrada != null && !portaEncontrada.equalsIgnoreCase(e.getKey())) {
                    return Optional.empty(); // visto em mais de uma porta
                }
                portaEncontrada = e.getKey();
            }
        }
        return Optional.ofNullable(portaEncontrada);
    }

    private Set<SwitchIdentity> coletarForaDaPorta(
            Map<String, Map<String, Set<SwitchIdentity>>> visiveis,
            String switchId,
            String portaIgnorada
    ) {
        Set<SwitchIdentity> resultado = new LinkedHashSet<>();
        Map<String, Set<SwitchIdentity>> portas = visiveis.getOrDefault(switchId, Map.of());
        for (Map.Entry<String, Set<SwitchIdentity>> e : portas.entrySet()) {
            if (portaIgnorada != null && e.getKey().equalsIgnoreCase(portaIgnorada)) {
                continue;
            }
            for (SwitchIdentity sw : e.getValue()) {
                if (!sw.macNormalizado().equals(switchId)) {
                    resultado.add(sw);
                }
            }
        }
        return resultado;
    }

    /** Pra cada porta do switch informado: quais switches conhecidos aparecem, e quais hosts desconhecidos. */
    public Map<String, PortaAnalise> analisarPortas(String switchId) {
        Map<String, Map<String, Set<SwitchIdentity>>> visiveisGlobal = construirMapaVisibilidade();
        Map<String, Set<SwitchIdentity>> switchesPorPorta = new LinkedHashMap<>(
                visiveisGlobal.getOrDefault(switchId, new LinkedHashMap<>())
        );
        Map<String, List<String>> hostsPorPorta = new LinkedHashMap<>();
        Map<String, Set<String>> todosMacsPorPorta = new LinkedHashMap<>();

        for (MacEntry entry : entradasPorSwitch.getOrDefault(switchId, List.of())) {
            Optional<SwitchIdentity> vizinho = identificarSwitchPorMac(entry.macNormalizado());
            if (vizinho.isPresent() && vizinho.get().macNormalizado().equals(switchId)) {
                continue; // o switch "vendo a si mesmo" (loop de porta própria) - ignora
            }

            switchesPorPorta.computeIfAbsent(entry.port(), k -> new LinkedHashSet<>());
            hostsPorPorta.computeIfAbsent(entry.port(), k -> new ArrayList<>());
            todosMacsPorPorta.computeIfAbsent(entry.port(), k -> new LinkedHashSet<>()).add(entry.macNormalizado());

            if (vizinho.isEmpty()) {
                List<String> hosts = hostsPorPorta.get(entry.port());
                if (!hosts.contains(entry.mac())) {
                    hosts.add(entry.mac());
                }
            }
        }

        // Caso o MAC do gateway tenha expirado na tabela MAC L2 mas conste ativo na tabela ARP com Switchport físico
        resolverGatewayCandidato().ifPresent(gw -> {
            String gwNorm = gw.macNormalizado();
            for (ArpEntry a : arpPorSwitch.getOrDefault(switchId, List.of())) {
                if (gwNorm.equalsIgnoreCase(a.macNormalizado()) && a.port() != null && !a.port().isBlank()) {
                    String porta = alinharNomePortaComTabelaMac(switchId, a.port());
                    switchesPorPorta.computeIfAbsent(porta, k -> new LinkedHashSet<>());
                    List<String> hosts = hostsPorPorta.computeIfAbsent(porta, k -> new ArrayList<>());
                    Set<String> todos = todosMacsPorPorta.computeIfAbsent(porta, k -> new LinkedHashSet<>());
                    if (todos.add(gwNorm)) {
                        hosts.add(Serie3000Parser.formatarMacPadrao(a.mac()));
                    }
                    break;
                }
            }
        });

        Map<String, PortaAnalise> resultado = new LinkedHashMap<>();
        for (String porta : switchesPorPorta.keySet()) {
            resultado.put(porta, new PortaAnalise(
                    porta,
                    switchesPorPorta.get(porta),
                    hostsPorPorta.getOrDefault(porta, List.of()),
                    todosMacsPorPorta.getOrDefault(porta, Set.of()).size()
            ));
        }
        return resultado;
    }

    /**
     * Retorna a porta no switch vizinho em que o switch de origem foi aprendido
     * (diretamente na tabela MAC ou inferido por transitividade da árvore).
     */
    public Optional<String> portaRemota(String switchOrigemMacNormalizado, String switchVizinhoMacNormalizado) {
        for (MacEntry entry : entradasPorSwitch.getOrDefault(switchVizinhoMacNormalizado, List.of())) {
            Optional<SwitchIdentity> id = identificarSwitchPorMac(entry.macNormalizado());
            if (id.isPresent() && id.get().macNormalizado().equals(switchOrigemMacNormalizado)) {
                return Optional.of(entry.port());
            }
        }

        SwitchIdentity swOrigem = switchesPorId.get(switchOrigemMacNormalizado);
        if (swOrigem != null) {
            Map<String, Map<String, Set<SwitchIdentity>>> visiveis = construirMapaVisibilidade();
            return encontrarPortaUnicaOndeVe(visiveis, switchVizinhoMacNormalizado, swOrigem);
        }
        return Optional.empty();
    }

    /**
     * Conjunto de switches conhecidos que aparecem no switch informado,
     * opcionalmente ignorando a porta que aponta de volta para o switch de origem
     * (assim consideramos apenas switches que estão "além" do candidato).
     */
    private Set<SwitchIdentity> alcancaveisPor(
            Map<String, Map<String, Set<SwitchIdentity>>> visiveisGlobal,
            String switchId,
            String portaIgnorada
    ) {
        return coletarForaDaPorta(visiveisGlobal, switchId, portaIgnorada);
    }

    private int contarPortasComSwitches(
            Map<String, Map<String, Set<SwitchIdentity>>> visiveisGlobal,
            String switchId
    ) {
        int count = 0;
        for (Set<SwitchIdentity> sws : visiveisGlobal.getOrDefault(switchId, Map.of()).values()) {
            if (!sws.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public ResultadoInferencia inferirVizinhoDireto(Set<SwitchIdentity> candidatos) {
        return inferirVizinhoDireto(null, candidatos);
    }

    /**
     * Entre os candidatos vistos numa mesma porta de switchOrigemId, tenta achar qual é o vizinho direto.
     * Critério: o candidato cuja PRÓPRIA tabela MAC "explica" (nas portas que seguem adiante,
     * excluindo a porta de retorno para a origem) a MAIOR fração dos demais candidatos vence.
     */
    public ResultadoInferencia inferirVizinhoDireto(String switchOrigemId, Set<SwitchIdentity> candidatos) {
        if (candidatos.isEmpty()) return ResultadoInferencia.semCandidatos();
        if (candidatos.size() == 1) return ResultadoInferencia.direto(candidatos.iterator().next(), Set.of());

        Map<String, Map<String, Set<SwitchIdentity>>> visiveisGlobal = construirMapaVisibilidade();

        record Pontuacao(
                SwitchIdentity candidato,
                double score,
                boolean temPortaRetorno,
                Set<SwitchIdentity> naoExplicados
        ) {}

        List<Pontuacao> pontuacoes = new ArrayList<>();
        for (SwitchIdentity candidato : candidatos) {
            Set<SwitchIdentity> alcancaveis;
            boolean temPortaRetorno = false;

            if (switchOrigemId != null) {
                Optional<String> portaDeRetorno = portaRemota(switchOrigemId, candidato.macNormalizado());
                if (portaDeRetorno.isPresent()) {
                    temPortaRetorno = true;
                    alcancaveis = alcancaveisPor(visiveisGlobal, candidato.macNormalizado(), portaDeRetorno.get());
                } else if (contarPortasComSwitches(visiveisGlobal, candidato.macNormalizado()) <= 1) {
                    // Se o candidato é switch de ponta (só vê outros switches em uma única porta de uplink),
                    // ele não pode ser switch intermediário para os demais candidatos.
                    alcancaveis = Set.of();
                } else {
                    alcancaveis = alcancaveisPor(visiveisGlobal, candidato.macNormalizado(), null);
                }
            } else {
                temPortaRetorno = true;
                alcancaveis = alcancaveisPor(visiveisGlobal, candidato.macNormalizado(), null);
            }

            Set<SwitchIdentity> restante = new LinkedHashSet<>(candidatos);
            restante.remove(candidato);

            Set<SwitchIdentity> naoExplicados = new LinkedHashSet<>(restante);
            naoExplicados.removeAll(alcancaveis);

            double score = restante.isEmpty() ? 0
                    : (restante.size() - naoExplicados.size()) / (double) restante.size();

            pontuacoes.add(new Pontuacao(candidato, score, temPortaRetorno, naoExplicados));
        }

        double melhorScore = pontuacoes.stream().mapToDouble(Pontuacao::score).max().orElse(0);
        if (melhorScore == 0) {
            return ResultadoInferencia.indeterminado(candidatos);
        }

        List<Pontuacao> melhores = pontuacoes.stream().filter(p -> p.score() == melhorScore).toList();

        // Se houver empate entre quem tem porta de retorno confirmada e quem não tem, prioriza quem tem
        if (melhores.size() > 1) {
            List<Pontuacao> comRetorno = melhores.stream().filter(Pontuacao::temPortaRetorno).toList();
            if (!comRetorno.isEmpty()) {
                melhores = comRetorno;
            }
        }

        if (melhores.size() > 1 && melhorScore == 1.0) {
            List<SwitchIdentity> empatados = melhores.stream().map(Pontuacao::candidato).toList();
            return ResultadoInferencia.ambiguo(empatados);
        }

        Pontuacao vencedor = melhores.get(0);
        return vencedor.score() == 1.0
                ? ResultadoInferencia.direto(vencedor.candidato(), Set.of())
                : ResultadoInferencia.diretoParcial(vencedor.candidato(), vencedor.naoExplicados());
    }

    public record PortaAnalise(
            String porta,
            Set<SwitchIdentity> switchesVisiveis,
            List<String> macsHostsDesconhecidos,
            int totalMacs
    ) {
        public int hostsDesconhecidos() {
            return macsHostsDesconhecidos.size();
        }

        public boolean ehPortaDeAcesso() {
            return switchesVisiveis.isEmpty();
        }
    }

    public record ResultadoInferencia(Tipo tipo, List<SwitchIdentity> candidatos, Set<SwitchIdentity> naoExplicados) {
        public enum Tipo { DIRETO, DIRETO_PARCIAL, AMBIGUO, INDETERMINADO, SEM_CANDIDATOS }

        static ResultadoInferencia direto(SwitchIdentity s, Set<SwitchIdentity> naoExplicados) {
            return new ResultadoInferencia(Tipo.DIRETO, List.of(s), naoExplicados);
        }
        static ResultadoInferencia diretoParcial(SwitchIdentity s, Set<SwitchIdentity> naoExplicados) {
            return new ResultadoInferencia(Tipo.DIRETO_PARCIAL, List.of(s), naoExplicados);
        }
        static ResultadoInferencia ambiguo(List<SwitchIdentity> candidatos) {
            return new ResultadoInferencia(Tipo.AMBIGUO, candidatos, Set.of());
        }
        static ResultadoInferencia indeterminado(Set<SwitchIdentity> candidatos) {
            return new ResultadoInferencia(Tipo.INDETERMINADO, List.copyOf(candidatos), Set.of());
        }
        static ResultadoInferencia semCandidatos() {
            return new ResultadoInferencia(Tipo.SEM_CANDIDATOS, List.of(), Set.of());
        }
    }

    /**
     * Retorna os vizinhos LLDP filtrados de interesse em um equipamento coletado:
     * switches/OLTs/APs coletados, roteador de uplink, Access Points e rádios Intelbras
     * (descartando estações finais, telefones IP e equipamentos irrelevantes).
     */
    public List<VizinhoLldpClassificado> vizinhosLldpDeInteresse(String switchId) {
        List<LldpNeighbor> brutos = lldpPorSwitch.getOrDefault(switchId, List.of());
        if (brutos.isEmpty()) {
            return List.of();
        }
        Optional<GatewayInfo> gwOpt = gatewayIdentificado();
        Collection<SwitchIdentity> todos = switchesPorId.values();
        List<VizinhoLldpClassificado> resultado = new ArrayList<>();
        Set<String> chaves = new LinkedHashSet<>();

        for (LldpNeighbor n : brutos) {
            CategoriaInteresse cat = LldpInterestFilter.classificar(n, todos, gwOpt);
            if (cat == CategoriaInteresse.OUTRO_IGNORAR) {
                continue;
            }
            SwitchIdentity coletado = LldpInterestFilter.resolverSwitchColetado(n, todos).orElse(null);
            if (coletado != null && coletado.macNormalizado().equalsIgnoreCase(switchId)) {
                continue; // auto-loop tratado no LoopDetector
            }
            String chave = (n.localPort() != null ? n.localPort().toUpperCase() : "")
                    + "|" + cat
                    + "|" + (coletado != null ? coletado.macNormalizado() : n.rotuloExibicao());
            if (chaves.add(chave)) {
                resultado.add(new VizinhoLldpClassificado(n, cat, coletado));
            }
        }
        return resultado;
    }

    /**
     * Retorna apenas os vizinhos LLDP externos de interesse (Roteador de uplink, APs e rádios Intelbras)
     * que ainda não fazem parte da lista de equipamentos coletados via CLI.
     */
    public List<VizinhoLldpClassificado> vizinhosLldpExternosDeInteresse(String switchId) {
        List<VizinhoLldpClassificado> todos = vizinhosLldpDeInteresse(switchId);
        List<VizinhoLldpClassificado> externos = new ArrayList<>();
        for (VizinhoLldpClassificado v : todos) {
            if (v.categoria() != CategoriaInteresse.EQUIPAMENTO_COLETADO) {
                externos.add(v);
            }
        }
        return externos;
    }

    /**
     * Verifica se a porta informada está em estado de bloqueio STP (Alternate/Backup/Discarding).
     */
    public boolean portaBloqueadaPorStp(String switchId, String porta) {
        if (switchId == null || porta == null || porta.isBlank()) return false;
        String portaSemBagg = porta.contains("(") ? porta.substring(0, porta.indexOf('(')).trim() : porta.trim();
        for (StpPortState s : stpPorSwitch.getOrDefault(switchId, List.of())) {
            if ((s.port().equalsIgnoreCase(porta.trim()) || s.port().equalsIgnoreCase(portaSemBagg))
                    && s.estaBloqueada()) {
                return true;
            }
        }
        return false;
    }

    public Map<String, List<String>> baggsDe(String switchId) {
        return Collections.unmodifiableMap(baggsPorSwitch.getOrDefault(switchId, Map.of()));
    }

    /**
     * Executa diagnóstico somente-leitura completo de loops, auto-loops, MAC flapping e anéis L2.
     */
    public List<LoopDiagnostic> diagnosticarLoops() {
        List<LoopDetector.ArestaFisica> arestas = RelatorioTopologia.extrairArestasFisicas(this);
        return diagnosticarLoops(arestas);
    }

    public List<LoopDiagnostic> diagnosticarLoops(List<LoopDetector.ArestaFisica> arestasTopologia) {
        return LoopDetector.analisar(
                switchesPorId,
                entradasPorSwitch,
                lldpPorSwitch,
                stpPorSwitch,
                loopbackHardwarePorSwitch,
                baggsPorSwitch,
                arestasTopologia
        );
    }
}

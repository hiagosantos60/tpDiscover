package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.coleta.LocalGatewayDetector;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.LoopDiagnostic;
import tpDiscover.intelbras.modelos.LoopDiagnostic.Severidade;
import tpDiscover.intelbras.modelos.LoopDiagnostic.TipoLoop;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.StpPortState;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Motor somente-leitura para detecção e diagnóstico de loops L2, auto-loops,
 * MAC flapping e redundâncias protegidas ou não por STP.
 * Nunca altera configuração nos equipamentos.
 */
public final class LoopDetector {

    /**
     * Aresta física entre dois equipamentos já coletados usada na busca de ciclos/anéis L2.
     */
    public record ArestaFisica(
            String switchAId,
            String portaA,
            String switchBId,
            String portaB
    ) {}

    private LoopDetector() {}

    /**
     * Executa todas as verificações de loop e retorna diagnósticos ordenados (CRITICO primeiro).
     */
    public static List<LoopDiagnostic> analisar(
            Map<String, SwitchIdentity> switchesPorId,
            Map<String, List<MacEntry>> entradasPorSwitch,
            Map<String, List<LldpNeighbor>> lldpPorSwitch,
            Map<String, List<StpPortState>> stpPorSwitch,
            Map<String, List<LoopDiagnostic>> loopbackHardwarePorSwitch,
            Map<String, Map<String, List<String>>> baggsPorSwitch,
            List<ArestaFisica> arestasTopologia
    ) {
        List<LoopDiagnostic> diagnosticos = new ArrayList<>();
        Set<String> chavesDeduplicacao = new HashSet<>();

        Map<String, SwitchIdentity> switches = switchesPorId != null ? switchesPorId : Map.of();
        Map<String, List<MacEntry>> macs = entradasPorSwitch != null ? entradasPorSwitch : Map.of();
        Map<String, List<LldpNeighbor>> lldp = lldpPorSwitch != null ? lldpPorSwitch : Map.of();
        Map<String, List<StpPortState>> stp = stpPorSwitch != null ? stpPorSwitch : Map.of();
        Map<String, List<LoopDiagnostic>> hwLoops = loopbackHardwarePorSwitch != null ? loopbackHardwarePorSwitch : Map.of();
        Map<String, Map<String, List<String>>> baggs = baggsPorSwitch != null ? baggsPorSwitch : Map.of();

        // 1. Diagnósticos diretos de loopback-detection relatados pelo próprio switch
        for (List<LoopDiagnostic> lista : hwLoops.values()) {
            for (LoopDiagnostic d : lista) {
                adicionarSeUnico(diagnosticos, chavesDeduplicacao, d);
            }
        }

        // 2. Auto-loop por LLDP (switch enxergando o próprio Chassis MAC em uma porta)
        detectarSelfLoopLldp(switches, lldp, stp, diagnosticos, chavesDeduplicacao);

        // 3. Auto-loop por MAC próprio reaparecendo em porta física externa
        detectarSelfMacEmPortaFisica(switches, macs, stp, diagnosticos, chavesDeduplicacao);

        // 4. MAC Flapping: mesmo (VLAN, MAC unicast) aprendido em múltiplas portas físicas distintas fora de BAGG/LAG
        detectarMacFlapping(switches, macs, stp, baggs, diagnosticos, chavesDeduplicacao);

        // 5. Anéis L2 / múltiplas ligações entre os mesmos switches (sem BAGG) com verificação de STP
        if (arestasTopologia != null && !arestasTopologia.isEmpty()) {
            detectarAneisERedundancias(switches, arestasTopologia, stp, baggs, diagnosticos, chavesDeduplicacao);
        }

        diagnosticos.sort(Comparator
                .comparing((LoopDiagnostic d) -> d.severidade() == Severidade.CRITICO ? 0 : 1)
                .thenComparing(d -> d.tipo().ordinal())
                .thenComparing(LoopDiagnostic::switchOrigemId)
                .thenComparing(LoopDiagnostic::portaOrigem));

        return diagnosticos;
    }

    private static void detectarSelfLoopLldp(
            Map<String, SwitchIdentity> switches,
            Map<String, List<LldpNeighbor>> lldp,
            Map<String, List<StpPortState>> stp,
            List<LoopDiagnostic> out,
            Set<String> dedup
    ) {
        for (Map.Entry<String, List<LldpNeighbor>> entry : lldp.entrySet()) {
            String swId = entry.getKey();
            SwitchIdentity sw = switches.get(swId);
            if (sw == null) continue;

            String macSwNorm = MacUtils.normalizar(sw.macAddress());
            for (LldpNeighbor n : entry.getValue()) {
                if (!n.possuiChassisMacValido()) continue;
                if (!macPertenceAoProprioSwitch(switches, swId, macSwNorm, n.chassisMacNormalizado())) continue;

                String pLocal = n.localPort();
                String pRemota = n.remotePort();
                if (portaEhBloqueadaPorStp(swId, pLocal, stp) || portaEhBloqueadaPorStp(swId, pRemota, stp)) {
                    adicionarSeUnico(out, dedup, new LoopDiagnostic(
                            Severidade.AVISO,
                            TipoLoop.REDUNDANCIA_STP,
                            swId,
                            pLocal,
                            swId,
                            pRemota,
                            "LLDP local detectou ligação entre portas do próprio switch (" + pLocal + " <-> " + pRemota + "), bloqueada por STP.",
                            "Validar se o cabo conectado entre as portas do switch é intencional; considerar remover o cabo redundante."
                    ));
                } else {
                    String detRemota = (pRemota != null && !pRemota.isBlank()) ? " e porta remota " + pRemota : "";
                    adicionarSeUnico(out, dedup, new LoopDiagnostic(
                            Severidade.CRITICO,
                            TipoLoop.SELF_LOOP_LLDP,
                            swId,
                            pLocal,
                            swId,
                            pRemota,
                            "LLDP recebeu anúncio do próprio switch na porta " + pLocal + detRemota
                                    + " (Chassis " + n.chassisMacNormalizado() + ").",
                            "Inspecionar fisicamente o cabo/patch na porta " + pLocal + " do switch " + rotuloSwitch(sw) + "."
                    ));
                }
            }
        }
    }

    private static void detectarSelfMacEmPortaFisica(
            Map<String, SwitchIdentity> switches,
            Map<String, List<MacEntry>> macs,
            Map<String, List<StpPortState>> stp,
            List<LoopDiagnostic> out,
            Set<String> dedup
    ) {
        for (Map.Entry<String, List<MacEntry>> entry : macs.entrySet()) {
            String swId = entry.getKey();
            SwitchIdentity sw = switches.get(swId);
            if (sw == null) continue;
            String macSwNorm = MacUtils.normalizar(sw.macAddress());
            if (!MacUtils.ehMacValido(macSwNorm)) continue;

            for (MacEntry mac : entry.getValue()) {
                if (ehPortaInternaOuLogica(mac.port())) continue;
                if (!macPertenceAoProprioSwitch(switches, swId, macSwNorm, mac.macNormalizado())) continue;

                if (portaEhBloqueadaPorStp(swId, mac.port(), stp)) continue;

                adicionarSeUnico(out, dedup, new LoopDiagnostic(
                        Severidade.CRITICO,
                        TipoLoop.SELF_MAC_PORTA,
                        swId,
                        mac.port(),
                        swId,
                        mac.port(),
                        "MAC do próprio equipamento (" + mac.mac() + ") foi aprendido na porta física "
                                + mac.port() + " (VLAN " + mac.vlanId() + "), indicando retorno de quadro em loop L2.",
                        "Verificar cabo em loop na porta " + mac.port() + " ou no segmento conectado a ela no equipamento " + rotuloSwitch(sw) + "."
                ));
            }
        }
    }

    /**
     * Valida se o MAC observado pertence ao switch atual, respeitando a ordem
     * (base, observado) em {@link MacUtils#pertenceAoSwitch} e descartando MACs que
     * correspondem diretamente (ou com menor distância) a outro switch coletado do mesmo lote OUI.
     */
    private static boolean macPertenceAoProprioSwitch(
            Map<String, SwitchIdentity> switches,
            String swId,
            String macSwNorm,
            String macObservadoNorm
    ) {
        if (!MacUtils.ehMacValido(macObservadoNorm)) {
            return false;
        }
        if (macObservadoNorm.equalsIgnoreCase(swId)
                || (MacUtils.ehMacValido(macSwNorm) && macObservadoNorm.equalsIgnoreCase(macSwNorm))) {
            return true;
        }
        if (!MacUtils.ehMacValido(macSwNorm) || !MacUtils.pertenceAoSwitch(macSwNorm, macObservadoNorm)) {
            return false;
        }
        long distProprio = MacUtils.distancia(macSwNorm, macObservadoNorm);
        for (Map.Entry<String, SwitchIdentity> outro : switches.entrySet()) {
            String outroId = outro.getKey();
            if (outroId.equals(swId)) continue;
            SwitchIdentity outroSw = outro.getValue();
            String outroMac = outroSw != null ? MacUtils.normalizar(outroSw.macAddress()) : "";
            if (macObservadoNorm.equalsIgnoreCase(outroId)
                    || (MacUtils.ehMacValido(outroMac) && macObservadoNorm.equalsIgnoreCase(outroMac))) {
                return false;
            }
            if (MacUtils.ehMacValido(outroMac)
                    && MacUtils.pertenceAoSwitch(outroMac, macObservadoNorm)
                    && MacUtils.distancia(outroMac, macObservadoNorm) < distProprio) {
                return false;
            }
        }
        return true;
    }

    private static void detectarMacFlapping(
            Map<String, SwitchIdentity> switches,
            Map<String, List<MacEntry>> macs,
            Map<String, List<StpPortState>> stp,
            Map<String, Map<String, List<String>>> baggs,
            List<LoopDiagnostic> out,
            Set<String> dedup
    ) {
        for (Map.Entry<String, List<MacEntry>> entry : macs.entrySet()) {
            String swId = entry.getKey();
            SwitchIdentity sw = switches.get(swId);
            if (sw == null) continue;

            // Agrupa portas físicas por (vlan + "|" + macNormalizado)
            Map<String, Set<String>> portasPorVlanMac = new LinkedHashMap<>();
            for (MacEntry m : entry.getValue()) {
                if (ehPortaInternaOuLogica(m.port())) continue;
                if (!LocalGatewayDetector.ehMacUnicastValido(m.macNormalizado())) continue;
                if (portaEhBloqueadaPorStp(swId, m.port(), stp)) continue;
                String chave = m.vlanId() + "|" + m.macNormalizado();
                portasPorVlanMac.computeIfAbsent(chave, k -> new LinkedHashSet<>()).add(m.port().toUpperCase());
            }

            // Conta quantas ocorrências distintas de flapping existem no switch para não inundar 1000 linhas idênticas
            Map<String, Set<String>> paresPortasFlapping = new LinkedHashMap<>();
            Map<String, Integer> contagemMacsPorPar = new HashMap<>();
            Map<String, String> exemploMacPorPar = new HashMap<>();

            for (Map.Entry<String, Set<String>> vm : portasPorVlanMac.entrySet()) {
                Set<String> portas = vm.getValue();
                if (portas.size() < 2) continue;

                // Remove equivalências dentro do mesmo grupo BAGG / LAG
                List<String> distintasSemBagg = filtrarPortasForaDoMesmoBagg(swId, portas, baggs);
                if (distintasSemBagg.size() < 2) continue;

                distintasSemBagg.sort(String::compareTo);
                String parChave = String.join(" <-> ", distintasSemBagg);
                paresPortasFlapping.put(parChave, new LinkedHashSet<>(distintasSemBagg));
                contagemMacsPorPar.merge(parChave, 1, Integer::sum);
                exemploMacPorPar.putIfAbsent(parChave, vm.getKey().replace("|", " MAC "));
            }

            for (Map.Entry<String, Set<String>> pf : paresPortasFlapping.entrySet()) {
                String parChave = pf.getKey();
                List<String> listaPortas = new ArrayList<>(pf.getValue());
                int qtd = contagemMacsPorPar.getOrDefault(parChave, 1);
                String portaA = listaPortas.get(0);
                String portaB = listaPortas.get(1);

                adicionarSeUnico(out, dedup, new LoopDiagnostic(
                        Severidade.CRITICO,
                        TipoLoop.MAC_FLAPPING,
                        swId,
                        portaA,
                        swId,
                        portaB,
                        qtd + " MAC(s) unicast na mesma VLAN oscilaram entre portas físicas sem LAG/BAGG ("
                                + parChave + "), ex: VLAN " + exemploMacPorPar.get(parChave) + ".",
                        "Investigar possível loop L2 ou switch não gerenciado interligando as portas "
                                + parChave + " de " + rotuloSwitch(sw) + "."
                ));
            }
        }
    }

    private static void detectarAneisERedundancias(
            Map<String, SwitchIdentity> switches,
            List<ArestaFisica> arestasBrutas,
            Map<String, List<StpPortState>> stp,
            Map<String, Map<String, List<String>>> baggs,
            List<LoopDiagnostic> out,
            Set<String> dedup
    ) {
        // Colapsa apenas múltiplas portas pertencentes ao MESMO grupo BAGG nos dois switches;
        // múltiplos cabos fora de BAGG entre os mesmos dois switches permanecem como arestas distintas!
        List<ArestaFisica> arestasFisicasUnicas = deduplicarArestasFisicas(arestasBrutas, baggs);

        // Verifica primeiramente enlaces paralelos fora de BAGG entre o mesmo par (swA, swB)
        Map<String, List<ArestaFisica>> porParSwitches = new LinkedHashMap<>();
        for (ArestaFisica a : arestasFisicasUnicas) {
            if (a.switchAId().equals(a.switchBId())) continue;
            String chave = parOrdenado(a.switchAId(), a.switchBId());
            porParSwitches.computeIfAbsent(chave, k -> new ArrayList<>()).add(a);
        }

        for (List<ArestaFisica> grupo : porParSwitches.values()) {
            if (grupo.size() < 2) continue;
            // Se são >=2 cabos paralelos fora de BAGG, verifica se STP bloqueou algum deles
            boolean algumBloqueado = false;
            ArestaFisica portaBloqueada = null;
            for (ArestaFisica a : grupo) {
                if (portaEhBloqueadaPorStp(a.switchAId(), a.portaA(), stp)
                        || portaEhBloqueadaPorStp(a.switchBId(), a.portaB(), stp)) {
                    algumBloqueado = true;
                    portaBloqueada = a;
                    break;
                }
            }
            ArestaFisica refA = grupo.get(0);
            ArestaFisica refB = grupo.get(1);
            String nomeA = rotuloSwitch(switches.get(refA.switchAId()));
            String nomeB = rotuloSwitch(switches.get(refA.switchBId()));

            if (algumBloqueado) {
                adicionarSeUnico(out, dedup, new LoopDiagnostic(
                        Severidade.AVISO,
                        TipoLoop.REDUNDANCIA_STP,
                        portaBloqueada.switchAId(),
                        portaBloqueada.portaA(),
                        portaBloqueada.switchBId(),
                        portaBloqueada.portaB(),
                        "Enlaces paralelos sem LACP/BAGG entre " + nomeA + " e " + nomeB
                                + " (" + grupo.size() + " cabos); STP bloqueou uma ponta para evitar loop.",
                        "Se desejar somar banda/redundância ativa, configurar LACP/BAGG em ambos os switches."
                ));
            } else {
                adicionarSeUnico(out, dedup, new LoopDiagnostic(
                        Severidade.CRITICO,
                        TipoLoop.ANEL_SEM_STP,
                        refA.switchAId(),
                        refA.portaA(),
                        refB.switchBId(),
                        refB.portaB(),
                        grupo.size() + " cabos paralelos entre " + nomeA + " (" + refA.portaA() + ", " + refB.portaA() + ") e "
                                + nomeB + " sem agregação BAGG/LAG e sem porta bloqueada por STP.",
                        "Verificar risco imediato de tempestade de broadcast entre " + nomeA + " e " + nomeB
                                + " (ativar LACP/BAGG ou STP, ou remover cabo duplicado)."
                ));
            }
        }

        // Busca ciclos de comprimento >= 3 no grafo lógico (1 aresta lógica por par de switches)
        Map<String, List<ArestaFisica>> adjLogica = new LinkedHashMap<>();
        for (Map.Entry<String, List<ArestaFisica>> e : porParSwitches.entrySet()) {
            ArestaFisica rep = e.getValue().get(0);
            adjLogica.computeIfAbsent(rep.switchAId(), k -> new ArrayList<>()).add(rep);
            adjLogica.computeIfAbsent(rep.switchBId(), k -> new ArrayList<>())
                    .add(new ArestaFisica(rep.switchBId(), rep.portaB(), rep.switchAId(), rep.portaA()));
        }

        Set<String> ciclosProcessados = new HashSet<>();
        List<String> nosOrdenados = new ArrayList<>(adjLogica.keySet());
        nosOrdenados.sort(String::compareTo);

        for (String inicio : nosOrdenados) {
            Deque<String> caminhoNos = new ArrayDeque<>();
            Deque<ArestaFisica> caminhoArestas = new ArrayDeque<>();
            caminhoNos.addLast(inicio);
            dfsCiclos(inicio, inicio, null, adjLogica, caminhoNos, caminhoArestas, ciclosProcessados, porParSwitches, switches, stp, out, dedup);
        }
    }

    private static void dfsCiclos(
            String origem,
            String atual,
            String pai,
            Map<String, List<ArestaFisica>> adj,
            Deque<String> caminhoNos,
            Deque<ArestaFisica> caminhoArestas,
            Set<String> ciclosProcessados,
            Map<String, List<ArestaFisica>> arestasPorPar,
            Map<String, SwitchIdentity> switches,
            Map<String, List<StpPortState>> stp,
            List<LoopDiagnostic> out,
            Set<String> dedup
    ) {
        if (caminhoNos.size() > 8) return; // Evita explosão combinatória em malhas densas

        for (ArestaFisica aresta : adj.getOrDefault(atual, List.of())) {
            String prox = aresta.switchBId();
            if (prox.equals(pai)) continue;

            if (prox.equals(origem) && caminhoNos.size() >= 3) {
                List<String> nosCiclo = new ArrayList<>(caminhoNos);
                String assinatura = normalizarAssinaturaCiclo(nosCiclo);
                if (!ciclosProcessados.add(assinatura)) continue;

                List<ArestaFisica> arestasCiclo = new ArrayList<>(caminhoArestas);
                arestasCiclo.add(aresta);
                avaliarCicloEncontrado(nosCiclo, arestasCiclo, arestasPorPar, switches, stp, out, dedup);
                continue;
            }

            if (caminhoNos.contains(prox)) continue;
            if (prox.compareTo(origem) < 0) continue; // Canonicamente inicia pelo menor ID

            caminhoNos.addLast(prox);
            caminhoArestas.addLast(aresta);
            dfsCiclos(origem, prox, atual, adj, caminhoNos, caminhoArestas, ciclosProcessados, arestasPorPar, switches, stp, out, dedup);
            caminhoArestas.removeLast();
            caminhoNos.removeLast();
        }
    }

    private static void avaliarCicloEncontrado(
            List<String> nosCiclo,
            List<ArestaFisica> arestasCiclo,
            Map<String, List<ArestaFisica>> arestasPorPar,
            Map<String, SwitchIdentity> switches,
            Map<String, List<StpPortState>> stp,
            List<LoopDiagnostic> out,
            Set<String> dedup
    ) {
        StringBuilder descAnel = new StringBuilder();
        for (int i = 0; i < nosCiclo.size(); i++) {
            if (i > 0) descAnel.append(" -> ");
            descAnel.append(rotuloSwitch(switches.get(nosCiclo.get(i))));
        }
        descAnel.append(" -> ").append(rotuloSwitch(switches.get(nosCiclo.get(0))));

        // Verifica se ALGUMA porta de qualquer aresta participante está bloqueada por STP
        String swBloqId = null;
        String portaBloq = null;
        String swDestinoBloq = null;
        String portaDestinoBloq = null;
        boolean stpDesabilitadoEmAlgum = false;

        for (ArestaFisica a : arestasCiclo) {
            if (!stpEstaHabilitadoNoSwitch(a.switchAId(), stp) || !stpEstaHabilitadoNoSwitch(a.switchBId(), stp)) {
                stpDesabilitadoEmAlgum = true;
            }
            for (ArestaFisica real : arestasPorPar.getOrDefault(parOrdenado(a.switchAId(), a.switchBId()), List.of(a))) {
                if (portaEhBloqueadaPorStp(real.switchAId(), real.portaA(), stp)) {
                    swBloqId = real.switchAId();
                    portaBloq = real.portaA();
                    swDestinoBloq = real.switchBId();
                    portaDestinoBloq = real.portaB();
                    break;
                }
                if (portaEhBloqueadaPorStp(real.switchBId(), real.portaB(), stp)) {
                    swBloqId = real.switchBId();
                    portaBloq = real.portaB();
                    swDestinoBloq = real.switchAId();
                    portaDestinoBloq = real.portaA();
                    break;
                }
            }
            if (swBloqId != null) break;
        }

        if (swBloqId != null) {
            adicionarSeUnico(out, dedup, new LoopDiagnostic(
                    Severidade.AVISO,
                    TipoLoop.REDUNDANCIA_STP,
                    swBloqId,
                    portaBloq,
                    swDestinoBloq,
                    portaDestinoBloq,
                    "Anel L2 (" + descAnel + ") protegido: porta " + portaBloq + " de "
                            + rotuloSwitch(switches.get(swBloqId)) + " está bloqueada por STP.",
                    "Redundância operacional via STP; monitorar convergência em caso de queda do enlace principal."
            ));
        } else {
            ArestaFisica primeira = arestasCiclo.get(0);
            String obsStp = stpDesabilitadoEmAlgum
                    ? "com STP desabilitado em ao menos um participante"
                    : "sem nenhuma porta reportada em estado Blocking/Discarding";
            adicionarSeUnico(out, dedup, new LoopDiagnostic(
                    Severidade.CRITICO,
                    TipoLoop.ANEL_SEM_STP,
                    primeira.switchAId(),
                    primeira.portaA(),
                    primeira.switchBId(),
                    primeira.portaB(),
                    "Anel L2 fechado detectado (" + descAnel + ") " + obsStp + "!",
                    "Risco crítico de loop de rede! Habilitar RSTP/MSTP em todos os switches do anel ou romper o fechamento físico."
            ));
        }
    }

    private static List<ArestaFisica> deduplicarArestasFisicas(
            List<ArestaFisica> brutos,
            Map<String, Map<String, List<String>>> baggs
    ) {
        List<ArestaFisica> resultado = new ArrayList<>();
        Set<String> chavesPortas = new HashSet<>();
        for (ArestaFisica a : brutos) {
            String portNormA = mapearPortaParaBaggSeExistir(a.switchAId(), a.portaA(), baggs);
            String portNormB = mapearPortaParaBaggSeExistir(a.switchBId(), a.portaB(), baggs);
            String e1 = a.switchAId() + ":" + portNormA;
            String e2 = a.switchBId() + ":" + portNormB;
            String chave = e1.compareTo(e2) <= 0 ? e1 + "|" + e2 : e2 + "|" + e1;
            if (chavesPortas.add(chave)) {
                resultado.add(a);
            }
        }
        return resultado;
    }

    private static String mapearPortaParaBaggSeExistir(
            String swId,
            String porta,
            Map<String, Map<String, List<String>>> baggs
    ) {
        if (porta == null || porta.isBlank()) return "";
        String pUp = porta.trim().toUpperCase();
        Map<String, List<String>> grupos = baggs.get(swId);
        if (grupos == null || grupos.isEmpty()) return pUp;
        for (Map.Entry<String, List<String>> g : grupos.entrySet()) {
            for (String membro : g.getValue()) {
                if (membro.trim().equalsIgnoreCase(pUp)) {
                    return "BAGG:" + g.getKey().toUpperCase();
                }
            }
        }
        return pUp;
    }

    private static List<String> filtrarPortasForaDoMesmoBagg(
            String swId,
            Collection<String> portas,
            Map<String, Map<String, List<String>>> baggs
    ) {
        Map<String, String> representantesPorChaveLogica = new LinkedHashMap<>();
        for (String p : portas) {
            String logica = mapearPortaParaBaggSeExistir(swId, p, baggs);
            representantesPorChaveLogica.putIfAbsent(logica, p);
        }
        return new ArrayList<>(representantesPorChaveLogica.values());
    }

    private static boolean portaEhBloqueadaPorStp(
            String swId,
            String porta,
            Map<String, List<StpPortState>> stpPorSwitch
    ) {
        if (porta == null || porta.isBlank()) return false;
        String pUp = porta.trim().toUpperCase();
        for (StpPortState s : stpPorSwitch.getOrDefault(swId, List.of())) {
            if (s.port().trim().equalsIgnoreCase(pUp) && s.estaBloqueada()) {
                return true;
            }
        }
        return false;
    }

    private static boolean stpEstaHabilitadoNoSwitch(
            String swId,
            Map<String, List<StpPortState>> stpPorSwitch
    ) {
        List<StpPortState> lista = stpPorSwitch.get(swId);
        if (lista == null || lista.isEmpty()) return true; // Sem dado explícito de STP desabilitado: não assume desligado
        for (StpPortState s : lista) {
            if (!s.stpHabilitado()) return false;
        }
        return true;
    }

    private static boolean ehPortaInternaOuLogica(String porta) {
        if (porta == null || porta.isBlank()) return true;
        String up = porta.trim().toUpperCase();
        return up.equals("CPU")
                || up.equals("SELF")
                || up.startsWith("LOCAL")
                || up.startsWith("VLAN")
                || up.startsWith("BRIDGE")
                || up.startsWith("BAGG")
                || up.startsWith("TRK")
                || up.startsWith("LAG")
                || up.startsWith("BR-");
    }

    private static String parOrdenado(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static String normalizarAssinaturaCiclo(List<String> nos) {
        List<String> ordenados = new ArrayList<>(nos);
        ordenados.sort(String::compareTo);
        return String.join("|", ordenados);
    }

    private static String rotuloSwitch(SwitchIdentity sw) {
        if (sw == null) return "desconhecido";
        String nome = (sw.deviceName() != null && !sw.deviceName().isBlank()) ? sw.deviceName() : sw.productId();
        return nome + " (" + sw.ip() + ")";
    }

    private static void adicionarSeUnico(
            List<LoopDiagnostic> out,
            Set<String> dedup,
            LoopDiagnostic d
    ) {
        String k = d.tipo() + "|" + d.switchOrigemId() + "|" + d.portaOrigem()
                + "|" + d.switchDestinoId() + "|" + d.portaDestino();
        if (dedup.add(k)) {
            out.add(d);
        }
    }
}

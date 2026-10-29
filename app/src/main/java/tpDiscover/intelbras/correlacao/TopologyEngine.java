package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.*;

/**
 * Motor de correlação de topologia via tabela MAC.
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

    public void registrarSwitch(SwitchIdentity identidade) {
        switchesPorId.put(identidade.macNormalizado(), identidade);
    }

    public void registrarEntradas(String switchId, List<MacEntry> entradas) {
        entradasPorSwitch.computeIfAbsent(switchId, k -> new ArrayList<>()).addAll(entradas);
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
            visiveis.put(switchId, porPorta);
        }

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

        return visiveis;
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
        Map<String, Set<SwitchIdentity>> switchesPorPorta = visiveisGlobal.getOrDefault(switchId, new LinkedHashMap<>());
        Map<String, List<String>> hostsPorPorta = new LinkedHashMap<>();
        Map<String, Set<String>> todosMacsPorPorta = new LinkedHashMap<>();

        for (MacEntry entry : entradasPorSwitch.getOrDefault(switchId, List.of())) {
            Optional<SwitchIdentity> vizinho = identificarSwitchPorMac(entry.macNormalizado());
            if (vizinho.isPresent() && vizinho.get().macNormalizado().equals(switchId)) {
                continue; // o switch "vendo a si mesmo" (loop de porta própria) — ignora
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
}

package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.*;

/**
 * Motor de correlação de topologia via tabela MAC.
 *
 * Duas etapas:
 *  1) analisarPortas: pra cada porta de um switch, descobre quais OUTROS switches
 *     conhecidos são "visíveis" ali (comparando por prefixo de MAC) e quantos MACs
 *     não identificados apareceram (hosts finais).
 *  2) inferirVizinhoDireto: quando uma porta vê MAIS DE UM switch conhecido, tenta
 *     decidir qual deles é o vizinho FISICAMENTE direto (os outros estariam atrás
 *     dele). Isso é feito checando se a própria tabela MAC de um candidato "explica"
 *     os demais candidatos — quem explica todo mundo é o mais próximo.
 *
 * Importante: essa inferência é heurística. Sem LLDP, é possível haver empate
 * genuíno (quando dois switches adjacentes numa cadeia enxergam basicamente o
 * mesmo conjunto de vizinhos) — nesses casos o motor reporta AMBIGUO em vez de
 * arriscar uma resposta errada.
 */
public class TopologyEngine {

    private final Map<String, SwitchIdentity> switchesPorPrefixo = new LinkedHashMap<>();
    private final Map<String, List<MacEntry>> entradasPorSwitch = new HashMap<>();

    public void registrarSwitch(SwitchIdentity identidade) {
        switchesPorPrefixo.put(MacUtils.prefixo(identidade.macNormalizado()), identidade);
    }

    public void registrarEntradas(String switchId, List<MacEntry> entradas) {
        entradasPorSwitch.computeIfAbsent(switchId, k -> new ArrayList<>()).addAll(entradas);
    }

    public Optional<SwitchIdentity> identificarSwitchPorMac(String macNormalizado) {
        return Optional.ofNullable(switchesPorPrefixo.get(MacUtils.prefixo(macNormalizado)));
    }

    /** Todos os switches registrados até agora (via registrarSwitch), na ordem em que foram adicionados. */
    public Collection<SwitchIdentity> switchesRegistrados() {
        return switchesPorPrefixo.values();
    }

    /** Pra cada porta do switch informado: quais switches conhecidos aparecem, e quais hosts desconhecidos. */
    public Map<String, PortaAnalise> analisarPortas(String switchId) {
        Map<String, Set<SwitchIdentity>> switchesPorPorta = new LinkedHashMap<>();
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

            if (vizinho.isPresent()) {
                switchesPorPorta.get(entry.port()).add(vizinho.get());
            } else {
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
     * Retorna a porta no switch vizinho em que o switch de origem foi aprendido,
     * se essa informação estiver presente na tabela MAC do vizinho.
     */
    public Optional<String> portaRemota(String switchOrigemMacNormalizado, String switchVizinhoMacNormalizado) {
        String prefixoOrigem = MacUtils.prefixo(switchOrigemMacNormalizado);
        for (MacEntry entry : entradasPorSwitch.getOrDefault(switchVizinhoMacNormalizado, List.of())) {
            if (MacUtils.prefixo(entry.macNormalizado()).equals(prefixoOrigem)) {
                return Optional.of(entry.port());
            }
        }
        return Optional.empty();
    }

    /**
     * Conjunto de switches conhecidos que aparecem na tabela MAC do switch informado,
     * opcionalmente ignorando a porta que aponta de volta para o switch de origem
     * (assim consideramos apenas switches que estão "além" do candidato).
     */
    private Set<SwitchIdentity> alcancaveisPor(String switchId, String portaIgnorada) {
        Set<SwitchIdentity> resultado = new LinkedHashSet<>();
        for (MacEntry entry : entradasPorSwitch.getOrDefault(switchId, List.of())) {
            if (portaIgnorada != null && entry.port().equalsIgnoreCase(portaIgnorada)) {
                continue;
            }
            identificarSwitchPorMac(entry.macNormalizado())
                    .filter(s -> !s.macNormalizado().equals(switchId))
                    .ifPresent(resultado::add);
        }
        return resultado;
    }

    private int contarPortasComSwitches(String switchId) {
        Set<String> portas = new HashSet<>();
        for (MacEntry entry : entradasPorSwitch.getOrDefault(switchId, List.of())) {
            identificarSwitchPorMac(entry.macNormalizado())
                    .filter(s -> !s.macNormalizado().equals(switchId))
                    .ifPresent(s -> portas.add(entry.port().toUpperCase()));
        }
        return portas.size();
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

        record Pontuacao(SwitchIdentity candidato, double score, Set<SwitchIdentity> naoExplicados) {}

        List<Pontuacao> pontuacoes = new ArrayList<>();
        for (SwitchIdentity candidato : candidatos) {
            Set<SwitchIdentity> alcancaveis;
            if (switchOrigemId != null) {
                Optional<String> portaDeRetorno = portaRemota(switchOrigemId, candidato.macNormalizado());
                if (portaDeRetorno.isPresent()) {
                    alcancaveis = alcancaveisPor(candidato.macNormalizado(), portaDeRetorno.get());
                } else if (contarPortasComSwitches(candidato.macNormalizado()) <= 1) {
                    // Se o candidato é switch de ponta (só vê outros switches em uma única porta de uplink),
                    // ele não pode ser switch intermediário para os demais candidatos.
                    alcancaveis = Set.of();
                } else {
                    alcancaveis = alcancaveisPor(candidato.macNormalizado(), null);
                }
            } else {
                alcancaveis = alcancaveisPor(candidato.macNormalizado(), null);
            }
            Set<SwitchIdentity> restante = new LinkedHashSet<>(candidatos);
            restante.remove(candidato);

            Set<SwitchIdentity> naoExplicados = new LinkedHashSet<>(restante);
            naoExplicados.removeAll(alcancaveis);

            double score = restante.isEmpty() ? 0
                    : (restante.size() - naoExplicados.size()) / (double) restante.size();

            pontuacoes.add(new Pontuacao(candidato, score, naoExplicados));
        }

        double melhorScore = pontuacoes.stream().mapToDouble(Pontuacao::score).max().orElse(0);
        if (melhorScore == 0) {
            return ResultadoInferencia.indeterminado(candidatos);
        }

        List<Pontuacao> melhores = pontuacoes.stream().filter(p -> p.score() == melhorScore).toList();

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

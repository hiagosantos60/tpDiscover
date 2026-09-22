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

    /** Pra cada porta do switch informado: quais switches conhecidos aparecem, e quantos hosts desconhecidos. */
    public Map<String, PortaAnalise> analisarPortas(String switchId) {
        Map<String, Set<SwitchIdentity>> switchesPorPorta = new LinkedHashMap<>();
        Map<String, Integer> hostsPorPorta = new LinkedHashMap<>();

        for (MacEntry entry : entradasPorSwitch.getOrDefault(switchId, List.of())) {
            switchesPorPorta.computeIfAbsent(entry.port(), k -> new LinkedHashSet<>());

            Optional<SwitchIdentity> vizinho = identificarSwitchPorMac(entry.macNormalizado());
            if (vizinho.isPresent() && vizinho.get().macNormalizado().equals(switchId)) {
                continue; // o switch "vendo a si mesmo" (loop de porta própria) — ignora
            }
            if (vizinho.isPresent()) {
                switchesPorPorta.get(entry.port()).add(vizinho.get());
            } else {
                hostsPorPorta.merge(entry.port(), 1, Integer::sum);
            }
        }

        Map<String, PortaAnalise> resultado = new LinkedHashMap<>();
        for (String porta : switchesPorPorta.keySet()) {
            resultado.put(porta, new PortaAnalise(
                    porta, switchesPorPorta.get(porta), hostsPorPorta.getOrDefault(porta, 0)));
        }
        return resultado;
    }

    /** Conjunto de switches conhecidos que aparecem em QUALQUER porta da tabela MAC do switch informado. */
    private Set<SwitchIdentity> alcancaveisPor(String switchId) {
        Set<SwitchIdentity> resultado = new LinkedHashSet<>();
        for (MacEntry entry : entradasPorSwitch.getOrDefault(switchId, List.of())) {
            identificarSwitchPorMac(entry.macNormalizado())
                    .filter(s -> !s.macNormalizado().equals(switchId))
                    .ifPresent(resultado::add);
        }
        return resultado;
    }

    /**
     * Entre os candidatos vistos numa mesma porta, tenta achar qual é o vizinho direto.
     * Critério: o candidato cuja PRÓPRIA tabela MAC "explica" a MAIOR fração dos demais
     * candidatos vence. Se dois candidatos empatam explicando 100% dos outros -> AMBIGUO.
     * Se ninguém tem nenhum dado que explique nada -> INDETERMINADO (falta coletar mais).
     */
    public ResultadoInferencia inferirVizinhoDireto(Set<SwitchIdentity> candidatos) {
        if (candidatos.isEmpty()) return ResultadoInferencia.semCandidatos();
        if (candidatos.size() == 1) return ResultadoInferencia.direto(candidatos.iterator().next(), Set.of());

        record Pontuacao(SwitchIdentity candidato, double score, Set<SwitchIdentity> naoExplicados) {}

        List<Pontuacao> pontuacoes = new ArrayList<>();
        for (SwitchIdentity candidato : candidatos) {
            Set<SwitchIdentity> alcancaveis = alcancaveisPor(candidato.macNormalizado());
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

    public record PortaAnalise(String porta, Set<SwitchIdentity> switchesVisiveis, int hostsDesconhecidos) {
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

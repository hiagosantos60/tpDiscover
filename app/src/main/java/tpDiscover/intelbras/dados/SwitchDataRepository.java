package tpDiscover.intelbras.dados;

import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.*;

/**
 * Armazena, em memória, os dados coletados durante uma sessão de atendimento.
 *
 * Estratégia:
 *  1) Lista bruta = fonte da verdade, fácil de depurar/exportar.
 *  2) Índice por switch+porta = usado pelo motor de correlação de topologia.
 *  3) Índice global por MAC-base de switch = usado para detectar uplinks
 *     (quando um MAC aprendido em uma porta é, na verdade, outro switch conhecido).
 */
public class SwitchDataRepository {

    // 1) Fonte da verdade
    private final List<MacEntry> todasEntradas = new ArrayList<>();

    // Identidade de cada switch, indexada pelo switchId (usamos o MAC-base como chave)
    private final Map<String, SwitchIdentity> switches = new LinkedHashMap<>();

    // 2) Índice derivado: switchId -> porta -> conjunto de MACs normalizados vistos ali
    private final Map<String, Map<String, Set<String>>> macsPorPorta = new HashMap<>();

    public void registrarSwitch(SwitchIdentity identidade) {
        switches.put(identidade.macNormalizado(), identidade);
    }

    public void registrarEntradasMac(List<MacEntry> entradas) {
        for (MacEntry entry : entradas) {
            todasEntradas.add(entry);

            macsPorPorta
                    .computeIfAbsent(entry.switchId(), k -> new HashMap<>())
                    .computeIfAbsent(entry.port(), k -> new HashSet<>())
                    .add(entry.macNormalizado());
        }
    }

    /** 3) Verifica se um MAC normalizado corresponde a um switch já conhecido (candidato a uplink). */
    public Optional<SwitchIdentity> identificarSeEhSwitch(String macNormalizado) {
        for (SwitchIdentity sw : switches.values()) {
            if (MacUtils.pertenceAoSwitch(sw.macNormalizado(), macNormalizado)) {
                return Optional.of(sw);
            }
        }
        return Optional.empty();
    }

    /** Retorna, para um switch, quantos MACs distintos cada porta aprendeu. Útil para classificar portas. */
    public Map<String, Integer> contagemMacsPorPorta(String switchId) {
        Map<String, Set<String>> portas = macsPorPorta.getOrDefault(switchId, Map.of());
        Map<String, Integer> resultado = new LinkedHashMap<>();
        portas.forEach((porta, macs) -> resultado.put(porta, macs.size()));
        return resultado;
    }

    public List<MacEntry> todasAsEntradas() {
        return Collections.unmodifiableList(todasEntradas);
    }

    public Collection<SwitchIdentity> todosOsSwitches() {
        return Collections.unmodifiableCollection(switches.values());
    }

    public Map<String, Set<String>> macsPorPortaDoSwitch(String switchId) {
        return macsPorPorta.getOrDefault(switchId, Map.of());
    }
}

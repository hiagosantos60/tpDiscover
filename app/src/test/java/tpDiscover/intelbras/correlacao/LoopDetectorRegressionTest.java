package tpDiscover.intelbras.correlacao;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.modelos.LldpNeighbor;
import tpDiscover.intelbras.modelos.LoopDiagnostic;
import tpDiscover.intelbras.modelos.LoopDiagnostic.TipoLoop;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LoopDetectorRegressionTest {

    @Test
    void naoDeveGerarFalsoSelfLoopEntreSwitchesDoMesmoLoteOuiProximo() {
        // Caso real observado em laboratório:
        // S3028G-B-LITE (10.1.40.93, 30E1-F16F-8B2B) e S3028G-B-LITE-2 (10.1.40.95, 30E1-F16F-8B03)
        // diferem por apenas 40 endereços MAC (dentro da janela típica de portas de um switch).
        String sw93Id = "30e1f16f8b2b";
        String sw95Id = "30e1f16f8b03";
        SwitchIdentity sw93 = new SwitchIdentity("S3028G-B-LITE", null, "30E1-F16F-8B2B", "S3028G-B Lite", "10.1.40.93");
        SwitchIdentity sw95 = new SwitchIdentity("S3028G-B-LITE-2", null, "30E1-F16F-8B03", "S3028G-B Lite", "10.1.40.95");

        Map<String, SwitchIdentity> switches = Map.of(sw93Id, sw93, sw95Id, sw95);
        Map<String, List<MacEntry>> macs = Map.of(
                // 93 enxerga o MAC de 95 no uplink GE17; 95 enxerga o MAC de 93 (+40 acima!) no uplink GE20
                sw93Id, List.of(new MacEntry(sw93Id, "30e1-f16f-8b03", 1, "Learned", "GE17")),
                sw95Id, List.of(new MacEntry(sw95Id, "30e1-f16f-8b2b", 1, "Learned", "GE20"))
        );
        Map<String, List<LldpNeighbor>> lldp = Map.of(
                sw93Id, List.of(new LldpNeighbor(sw93Id, "GE17", sw95Id, sw95Id, "GE20", "", "S3028G-B-LITE-2", "", "10.1.40.95", Set.of("Bridge"))),
                sw95Id, List.of(new LldpNeighbor(sw95Id, "GE20", sw93Id, sw93Id, "GE17", "", "S3028G-B-LITE", "", "10.1.40.93", Set.of("Bridge")))
        );

        List<LoopDiagnostic> resultado = LoopDetector.analisar(
                switches,
                macs,
                lldp,
                Map.of(),
                Map.of(),
                Map.of(),
                List.of()
        );
        assertTrue(resultado.isEmpty(), "MAC de switch irmão do mesmo lote OUI não deve virar falso SELF_LOOP: " + resultado);
    }

    @Test
    void deveDetectarSelfLoopQuandoSwitchAprendeProprioMacOuPortaNaRede() {
        String swId = "30e1f16f8b2b";
        SwitchIdentity sw = new SwitchIdentity("S3028G-B-LITE", null, "30E1-F16F-8B2B", "S3028G-B Lite", "10.1.40.93");

        Map<String, List<MacEntry>> macs = Map.of(
                swId, List.of(
                        new MacEntry(swId, "30e1-f16f-8b2b", 1, "Learned", "GE5"),
                        new MacEntry(swId, "30e1-f16f-8b2c", 1, "Learned", "GE6")
                )
        );
        List<LoopDiagnostic> resultado = LoopDetector.analisar(
                Map.of(swId, sw),
                macs,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of()
        );
        assertEquals(2, resultado.size());
        assertTrue(resultado.stream().allMatch(d -> d.tipo() == TipoLoop.SELF_MAC_PORTA));
    }
}

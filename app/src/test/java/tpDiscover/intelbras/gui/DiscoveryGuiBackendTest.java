package tpDiscover.intelbras.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tpDiscover.intelbras.App;
import tpDiscover.intelbras.coleta.AlvosLoader;
import tpDiscover.intelbras.coleta.DiscoverySessionService;
import tpDiscover.intelbras.coleta.DiscoverySessionService.DiscoveryRequest;
import tpDiscover.intelbras.coleta.DiscoverySessionService.DiscoverySessionResult;
import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.correlacao.RelatorioTopologia;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.StpPortState;
import tpDiscover.intelbras.modelos.SwitchIdentity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import tpDiscover.intelbras.util.Compat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryGuiBackendTest {

    @Test
    void deveInterpretarFlagsGuiECliPreservandoModoPadrao() {
        App.CliOptions semArgs = App.parseArgs(new String[0]);
        assertFalse(semArgs.gui());
        assertFalse(semArgs.cli());

        App.CliOptions comGui = App.parseArgs(new String[]{"--gui"});
        assertTrue(comGui.gui());
        assertFalse(comGui.cli());

        App.CliOptions comCli = App.parseArgs(new String[]{"--cli"});
        assertFalse(comCli.gui());
        assertTrue(comCli.cli());
    }

    @Test
    void deveSalvarAlvosComESemSenha(@TempDir Path tempDir) throws Exception {
        List<SwitchTarget> alvos = Compat.listOf(
                new SwitchTarget("10.1.40.203", "admin", "Admin@1234"),
                new SwitchTarget("10.1.31.19", "ops", "Segredo", 65019)
        );

        Path comSenha = tempDir.resolve("com_senha.txt");
        AlvosLoader.salvarAlvos(comSenha, alvos, true);
        String conteudoCom = Compat.readString(comSenha);
        assertTrue(conteudoCom.contains("10.1.40.203;admin;Admin@1234"));
        assertTrue(conteudoCom.contains("10.1.31.19:65019;ops;Segredo"));

        Path semSenha = tempDir.resolve("sem_senha.txt");
        AlvosLoader.salvarAlvos(semSenha, alvos, false);
        String conteudoSem = Compat.readString(semSenha);
        assertTrue(conteudoSem.contains("10.1.40.203;admin"));
        assertTrue(conteudoSem.contains("10.1.31.19:65019;ops"));
        assertFalse(conteudoSem.contains("Admin@1234"));
        assertFalse(conteudoSem.contains("Segredo"));
    }

    @Test
    void deveRetornarErroEmEspecificacaoInvalidaECancelarLimpo(@TempDir Path tempDir) {
        DiscoverySessionService service = new DiscoverySessionService();

        DiscoveryRequest reqInvalido = new DiscoveryRequest(
                Compat.listOf("10.1.0.0/8"), Compat.listOf(), "admin", "Admin@1234",
                true, true, false, 4, null, null, tempDir, null
        );
        DiscoverySessionResult resInvalido = service.executar(reqInvalido, null, new AtomicBoolean(false));
        assertTrue(resInvalido.possuiErroFatal());

        DiscoveryRequest reqCancelado = new DiscoveryRequest(
                Compat.listOf("127.0.0.1"), Compat.listOf(), "admin", "Admin@1234",
                true, true, false, 4, null, null, tempDir, null
        );
        DiscoverySessionResult resCancelado = service.executar(reqCancelado, null, new AtomicBoolean(true));
        assertTrue(resCancelado.cancelado());
        assertFalse(resCancelado.possuiErroFatal());
    }

    @Test
    void deveGerarResultadoEstruturadoComEnlacesELoops() {
        TopologyEngine engine = new TopologyEngine();
        SwitchIdentity sw1 = new SwitchIdentity("SW-CORE", "SER1", "0011-2233-4400", "S2328G-B", "10.1.40.10");
        SwitchIdentity sw2 = new SwitchIdentity("SW-ACC", "SER2", "0011-2233-5500", "S3028G-B", "10.1.40.11");
        engine.registrarSwitch(sw1);
        engine.registrarSwitch(sw2);
        engine.registrarEntradas(sw1.macNormalizado(), Compat.listOf(
                new MacEntry(sw1.macNormalizado(), "0011-2233-5500", 1, "Learned", "GE1/0/1")
        ));
        engine.registrarEntradas(sw2.macNormalizado(), Compat.listOf(
                new MacEntry(sw2.macNormalizado(), "0011-2233-4400", 1, "Learned", "GE0/24"),
                new MacEntry(sw2.macNormalizado(), "0011-2233-5500", 1, "Learned", "GE0/5")
        ));
        engine.registrarStp(sw2.macNormalizado(), Compat.listOf(
                new StpPortState(sw2.macNormalizado(), "GE0/23", "ALTERNATE", "DISCARDING", true, "0011-2233-4400")
        ));

        RelatorioTopologia.ResultadoRelatorio rel = RelatorioTopologia.gerarResultado(engine, null, false);
        assertEquals(1, rel.enlaces().size());
        assertEquals("GE1/0/1", rel.enlaces().get(0).portaOrigem());
        assertEquals("GE0/24", rel.enlaces().get(0).portaDestino());
        assertEquals(1, rel.loops().size());
        assertNull(rel.arquivoGerado());
        assertTrue(rel.textoCompleto().contains("ENLACES DIRETOS"));
    }
}

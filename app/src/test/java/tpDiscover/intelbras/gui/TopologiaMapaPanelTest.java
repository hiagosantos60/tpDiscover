package tpDiscover.intelbras.gui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologiaMapaPanelTest {

    @Test
    void projetoDoPlanejadorVaiParaAPastaENomeDoRelatorio() {
        TopologiaMapaPanel painel = new TopologiaMapaPanel();
        Path pasta = Paths.get("C:/Clientes/Loja1");
        painel.setPastaRelatorio(pasta, pasta.resolve("topologia_2026-11-18_1553.pdf"));
        assertEquals(pasta.resolve("topologia_2026-11-18_1553_planejador.json"), painel.destinoProjetoPlanejador());
    }

    @Test
    void semPdfAindaUsaAPastaDoRelatorioComDataHora() {
        TopologiaMapaPanel painel = new TopologiaMapaPanel();
        Path pasta = Paths.get("C:/Clientes/Loja2");
        painel.setPastaRelatorio(pasta, null);
        Path destino = painel.destinoProjetoPlanejador();
        assertEquals(pasta, destino.getParent());
        assertTrue(destino.getFileName().toString().matches("topologia_[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{4}_planejador[.]json"));
    }
}

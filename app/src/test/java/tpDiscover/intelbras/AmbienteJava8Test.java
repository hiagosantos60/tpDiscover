package tpDiscover.intelbras;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Garante que a validação acontece no runtime de produção (Java 8) e com bytecode Java 8. */
class AmbienteJava8Test {

    @Test
    void suiteRodaNoJava8() {
        assertEquals("1.8", System.getProperty("java.specification.version"),
                "Os testes devem rodar no Java 8 (runtime de produção)");
    }

    @Test
    void classesDoAplicativoSaoBytecodeJava8() throws Exception {
        for (Class<?> c : new Class<?>[]{App.class, tpDiscover.intelbras.gui.TopologiaMapaPanel.class,
                tpDiscover.intelbras.seguranca.ControleAcesso.class,
                tpDiscover.intelbras.correlacao.ExportadorProjetoPlanejador.class}) {
            try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
                DataInputStream d = new DataInputStream(in);
                assertEquals(0xCAFEBABE, d.readInt());
                d.readUnsignedShort();
                int major = d.readUnsignedShort();
                assertTrue(major <= 52, c.getName() + " tem bytecode " + major + " (Java 8 = 52)");
            }
        }
    }
}

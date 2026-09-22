package tpDiscover.intelbras.coleta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê a lista de switches a coletar de um arquivo texto simples, formato:
 *
 *   # comentário (ignorado)
 *   10.1.40.230;admin;Admin@1234
 *   10.1.40.203;admin;OutraSenha
 *
 * Isso evita ter que recompilar o programa toda vez que o técnico for
 * atender um cliente diferente com uma lista de switches diferente.
 */
public class AlvosLoader {

    public static List<SwitchTarget> carregarDeArquivo(Path caminho) throws IOException {
        if (!Files.exists(caminho)) {
            throw new IOException("Arquivo de alvos não encontrado: " + caminho.toAbsolutePath()
                    + " — crie esse arquivo com uma linha por switch, no formato ip;usuario;senha");
        }

        List<SwitchTarget> alvos = new ArrayList<>();
        List<String> linhas = Files.readAllLines(caminho);

        for (int i = 0; i < linhas.size(); i++) {
            String linha = linhas.get(i).strip();
            if (linha.isEmpty() || linha.startsWith("#")) continue;

            String[] partes = linha.split(";");
            if (partes.length != 3) {
                System.err.println("[AVISO] Linha " + (i + 1) + " ignorada (esperado ip;usuario;senha): " + linha);
                continue;
            }

            alvos.add(new SwitchTarget(partes[0].strip(), partes[1].strip(), partes[2].strip()));
        }

        return alvos;
    }
}

package tpDiscover.intelbras.coleta;

import tpDiscover.intelbras.parser.Serie3000Parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê a lista de switches a coletar de um arquivo texto simples, formato:
 *
 *   # comentário (ignorado)
 *   # gateway=10.1.40.1               (opcional: IP e/ou MAC do roteador do cliente)
 *   10.1.40.230;admin;Admin@1234
 *   10.1.40.203;admin;OutraSenha
 *
 * Isso evita ter que recompilar o programa toda vez que o técnico for
 * atender um cliente diferente com uma lista de switches diferente.
 */
public class AlvosLoader {

    private static final Pattern DIRETIVA_GATEWAY = Pattern.compile(
            "(?i)^#?\\s*gateway\\s*[:=]\\s*(.+?)\\s*$"
    );
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "\\b((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})\\b"
    );
    private static final Pattern MAC_PATTERN = Pattern.compile(
            "\\b([0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}[.\\-:][0-9a-fA-F]{4}|[0-9a-fA-F]{2}(?:[:\\-][0-9a-fA-F]{2}){5}|[0-9a-fA-F]{12})\\b"
    );

    public record ConfiguracaoAlvos(
            List<SwitchTarget> alvos,
            String gatewayIpManual,
            String gatewayMacManual
    ) {
        public boolean possuiGatewayManual() {
            return (gatewayIpManual != null && !gatewayIpManual.isBlank())
                    || (gatewayMacManual != null && !gatewayMacManual.isBlank());
        }
    }

    public static List<SwitchTarget> carregarDeArquivo(Path caminho) throws IOException {
        return carregarConfiguracao(caminho).alvos();
    }

    public static ConfiguracaoAlvos carregarConfiguracao(Path caminho) throws IOException {
        if (!Files.exists(caminho)) {
            throw new IOException("Arquivo de alvos não encontrado: " + caminho.toAbsolutePath()
                    + " - crie esse arquivo com uma linha por switch, no formato ip;usuario;senha");
        }

        List<SwitchTarget> alvos = new ArrayList<>();
        String gatewayIp = null;
        String gatewayMac = null;
        List<String> linhas = Files.readAllLines(caminho);

        for (int i = 0; i < linhas.size(); i++) {
            String linha = linhas.get(i).strip();
            if (linha.isEmpty()) continue;

            Matcher mGw = DIRETIVA_GATEWAY.matcher(linha);
            if (mGw.matches()) {
                String valor = mGw.group(1).strip();
                Matcher mIp = IPV4_PATTERN.matcher(valor);
                if (mIp.find()) {
                    gatewayIp = mIp.group(1);
                }
                Matcher mMac = MAC_PATTERN.matcher(valor);
                if (mMac.find()) {
                    gatewayMac = Serie3000Parser.formatarMacPadrao(mMac.group(1));
                }
                continue;
            }

            if (linha.startsWith("#")) continue;

            String[] partes = linha.split(";");
            if (partes.length != 3) {
                System.err.println("[AVISO] Linha " + (i + 1) + " ignorada (esperado ip;usuario;senha): " + linha);
                continue;
            }

            alvos.add(new SwitchTarget(partes[0].strip(), partes[1].strip(), partes[2].strip()));
        }

        return new ConfiguracaoAlvos(alvos, gatewayIp, gatewayMac);
    }
}

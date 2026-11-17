package tpDiscover.intelbras.coleta;

import tpDiscover.intelbras.parser.Serie3000Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 *   10.1.31.19:65019;admin;Admin@1234 (opcional: porta customizada SSH/Telnet)
 *   10.1.40.203;admin                 (opcional: sem senha em disco; será solicitada na execução)
 *
 * Isso evita ter que recompilar o programa toda vez que o técnico for
 * atender um cliente diferente com uma lista de switches diferente.
 */
public class AlvosLoader {

    private static final Pattern DIRETIVA_GATEWAY = Pattern.compile(
            "(?i)^#?\\s*gateway\\s*[:=]\\s*(.+?)\\s*$"
    );
    private static final Pattern DIRETIVA_SCAN = Pattern.compile(
            "(?i)^#?\\s*scan\\s*[:=]\\s*(.+?)\\s*$"
    );
    private static final Pattern DIRETIVA_CREDENCIAL = Pattern.compile(
            "(?i)^#?\\s*credencia(?:l|is)\\s*[:=]\\s*(.+?)\\s*$"
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
            String gatewayMacManual,
            List<String> scanSpecs,
            String usuarioPadrao,
            String senhaPadrao
    ) {
        public ConfiguracaoAlvos {
            alvos = alvos != null ? List.copyOf(alvos) : List.of();
            scanSpecs = scanSpecs != null ? List.copyOf(scanSpecs) : List.of();
        }

        public ConfiguracaoAlvos(List<SwitchTarget> alvos, String gatewayIpManual, String gatewayMacManual) {
            this(alvos, gatewayIpManual, gatewayMacManual, List.of(), null, null);
        }

        public boolean possuiGatewayManual() {
            return (gatewayIpManual != null && !gatewayIpManual.isBlank())
                    || (gatewayMacManual != null && !gatewayMacManual.isBlank());
        }

        public boolean possuiScan() {
            return scanSpecs != null && !scanSpecs.isEmpty();
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
        List<String> scanSpecs = new ArrayList<>();
        String usuarioPadrao = null;
        String senhaPadrao = null;
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

            Matcher mScan = DIRETIVA_SCAN.matcher(linha);
            if (mScan.matches()) {
                for (String s : mScan.group(1).split("[,;\\s]+")) {
                    String limpo = s.trim();
                    if (!limpo.isEmpty()) {
                        scanSpecs.add(limpo);
                    }
                }
                continue;
            }

            Matcher mCred = DIRETIVA_CREDENCIAL.matcher(linha);
            if (mCred.matches()) {
                String[] cred = mCred.group(1).strip().split(";", 2);
                if (cred.length >= 1 && !cred[0].isBlank()) {
                    usuarioPadrao = cred[0].strip();
                    senhaPadrao = cred.length == 2 ? cred[1].strip() : null;
                }
                continue;
            }

            if (linha.startsWith("#")) continue;

            String[] partes = linha.split(";", 3);
            if (partes.length < 2) {
                System.err.println("[AVISO] Linha " + (i + 1) + " ignorada (esperado ip;usuario;senha ou ip;usuario).");
                continue;
            }

            String hostPorta = partes[0].strip();
            String usuario = partes[1].strip();
            if (hostPorta.isEmpty() || usuario.isEmpty()) {
                System.err.println("[AVISO] Linha " + (i + 1) + " ignorada (IP ou usuário em branco).");
                continue;
            }

            String ip = hostPorta;
            Integer portaCustomizada = null;
            int idxPorta = hostPorta.lastIndexOf(':');
            if (idxPorta > 0 && hostPorta.indexOf(':') == idxPorta) {
                ip = hostPorta.substring(0, idxPorta).strip();
                String portaStr = hostPorta.substring(idxPorta + 1).strip();
                try {
                    int p = Integer.parseInt(portaStr);
                    if (p < 1 || p > 65535 || ip.isEmpty()) {
                        System.err.println("[AVISO] Linha " + (i + 1) + " ignorada (porta fora do intervalo 1..65535).");
                        continue;
                    }
                    portaCustomizada = p;
                } catch (NumberFormatException e) {
                    System.err.println("[AVISO] Linha " + (i + 1) + " ignorada (porta inválida).");
                    continue;
                }
            }

            String senha = partes.length == 3 ? partes[2].strip() : null;
            alvos.add(new SwitchTarget(ip, usuario, senha, portaCustomizada));
        }

        return new ConfiguracaoAlvos(alvos, gatewayIp, gatewayMac, scanSpecs, usuarioPadrao, senhaPadrao);
    }

    /**
     * Persiste a lista de alvos no formato padrão {@code ip[:porta];usuario[;senha]}.
     */
    public static void salvarAlvos(Path destino, List<SwitchTarget> alvos, boolean incluirSenhas) throws IOException {
        List<String> linhas = new ArrayList<>();
        linhas.add("# Alvos salvos pelo tpDiscover");
        if (alvos != null) {
            for (SwitchTarget t : alvos) {
                String base = t.enderecoExibicao();
                String user = t.usuario() != null && !t.usuario().isBlank() ? t.usuario() : "admin";
                if (incluirSenhas && t.possuiSenha()) {
                    linhas.add(base + ";" + user + ";" + t.senha());
                } else {
                    linhas.add(base + ";" + user);
                }
            }
        }
        Path parent = destino.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(destino, linhas, StandardCharsets.UTF_8);
    }
}

package tpDiscover.intelbras.coleta;

import tpDiscover.intelbras.modelos.GatewayInfo;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.parser.Serie3000Parser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback local para descoberta do Roteador/Gateway a partir da máquina onde o tpDiscover
 * está sendo executado, útil quando os switches do cliente operam em L2 puro sem rota padrão
 * configurada na CLI.
 *
 * Só utiliza o gateway do PC local caso ele pertença à mesma sub-rede IPv4 de ao menos um
 * dos switches coletados (evitando misturar adaptadores Wi-Fi/VPN de outra rede).
 */
public final class LocalGatewayDetector {

    // Windows "route print -4 0.0.0.0":
    // "          0.0.0.0          0.0.0.0        10.1.40.1     10.1.40.142     25"
    private static final Pattern WIN_DEFAULT_ROUTE = Pattern.compile(
            "^\\s*0\\.0\\.0\\.0\\s+0\\.0\\.0\\.0\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+\\d+\\s*$"
    );

    // Linux "ip route show default":
    // "default via 10.1.40.1 dev eth0 proto dhcp src 10.1.40.142 metric 100"
    private static final Pattern LINUX_DEFAULT_ROUTE = Pattern.compile(
            "^\\s*default\\s+via\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\b(?:.*?\\bsrc\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\b)?"
    );

    // Saída numérica de "arp -a" (Windows / Linux / macOS):
    // Windows: "  10.1.40.1             00-09-0f-09-1e-0a     dinâmico"
    // Unix:    "? (10.1.40.1) at 00:09:0f:09:1e:0a [ether] on eth0"
    private static final Pattern ARP_ENTRY_PATTERN = Pattern.compile(
            "(?:^|\\()(\\d+\\.\\d+\\.\\d+\\.\\d+)(?:\\)|\\s+)[^0-9a-fA-F]*([0-9a-fA-F]{2}(?:[:\\-][0-9a-fA-F]{2}){5})\\b"
    );

    private record RotaLocal(String gatewayIp, String interfaceIp) {}

    private LocalGatewayDetector() {
    }

    /**
     * Detecta o gateway padrão do PC local apenas se estiver na mesma sub-rede de algum switch coletado,
     * resolvendo também seu endereço MAC na tabela ARP do sistema operacional.
     */
    public static Optional<GatewayInfo> detectarGatewayLocalNaMesmaSubRede(Collection<String> ipsSwitches) {
        if (ipsSwitches == null || ipsSwitches.isEmpty()) {
            return Optional.empty();
        }

        List<RotaLocal> rotas = listarRotasPadraoLocais();
        for (RotaLocal rota : rotas) {
            if (pertenceAMesmaSubRedeDeAlgumSwitch(rota.gatewayIp(), rota.interfaceIp(), ipsSwitches)) {
                Optional<String> macOpt = resolverMacNoArpLocal(rota.gatewayIp());
                if (macOpt.isPresent()) {
                    return Optional.of(new GatewayInfo(rota.gatewayIp(), macOpt.get()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Consulta o endereço MAC correspondente a um IP na tabela ARP do sistema operacional local.
     */
    public static Optional<String> resolverMacNoArpLocal(String ipAlvo) {
        if (ipAlvo == null || ipAlvo.isBlank()) {
            return Optional.empty();
        }

        // Dispara uma verificação rápida de alcance para popular o cache ARP local caso esteja expirado
        try {
            InetAddress.getByName(ipAlvo.trim()).isReachable(350);
        } catch (Exception ignored) {
        }

        String saidaArp = executarProcessoLocal("arp", "-a");
        return extrairMacDeSaidaArp(ipAlvo.trim(), saidaArp);
    }

    public static Optional<String> extrairMacDeSaidaArp(String ipAlvo, String saidaArp) {
        if (ipAlvo == null || saidaArp == null || saidaArp.isBlank()) {
            return Optional.empty();
        }

        for (String line : saidaArp.split("\\R")) {
            Matcher m = ARP_ENTRY_PATTERN.matcher(line.trim());
            if (m.find() && ipAlvo.equals(m.group(1))) {
                String macFormatado = Serie3000Parser.formatarMacPadrao(m.group(2));
                String norm = MacUtils.normalizar(macFormatado);
                if (ehMacUnicastValido(norm)) {
                    return Optional.of(macFormatado);
                }
            }
        }
        return Optional.empty();
    }

    private static List<RotaLocal> listarRotasPadraoLocais() {
        List<RotaLocal> rotas = new ArrayList<>();
        boolean ehWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        if (ehWindows) {
            String saida = executarProcessoLocal("route", "print", "-4", "0.0.0.0");
            if (saida != null) {
                for (String line : saida.split("\\R")) {
                    Matcher m = WIN_DEFAULT_ROUTE.matcher(line);
                    if (m.matches()) {
                        rotas.add(new RotaLocal(m.group(1), m.group(2)));
                    }
                }
            }
        } else {
            String saida = executarProcessoLocal("ip", "route", "show", "default");
            if (saida != null) {
                for (String line : saida.split("\\R")) {
                    Matcher m = LINUX_DEFAULT_ROUTE.matcher(line);
                    if (m.find()) {
                        rotas.add(new RotaLocal(m.group(1), m.group(2)));
                    }
                }
            }
        }
        return rotas;
    }

    static boolean pertenceAMesmaSubRedeDeAlgumSwitch(
            String gatewayIp,
            String interfaceIp,
            Collection<String> ipsSwitches
    ) {
        int prefixo = descobrirPrefixoInterfaceLocal(interfaceIp);
        String ipReferencia = (interfaceIp != null && !interfaceIp.isBlank()) ? interfaceIp : gatewayIp;

        Integer refInt = ipv4ParaInt(ipReferencia);
        Integer gwInt = ipv4ParaInt(gatewayIp);
        if (refInt == null || gwInt == null) {
            return false;
        }

        int mascara = prefixo <= 0 ? 0 : (0xFFFFFFFF << (32 - Math.min(32, prefixo)));
        for (String ipSw : ipsSwitches) {
            Integer swInt = ipv4ParaInt(ipSw);
            if (swInt != null && ((swInt & mascara) == (refInt & mascara) || (swInt & mascara) == (gwInt & mascara))) {
                return true;
            }
        }
        return false;
    }

    private static int descobrirPrefixoInterfaceLocal(String interfaceIp) {
        if (interfaceIp != null && !interfaceIp.isBlank()) {
            try {
                InetAddress addr = InetAddress.getByName(interfaceIp.trim());
                NetworkInterface nif = NetworkInterface.getByInetAddress(addr);
                if (nif != null) {
                    for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        if (ia.getAddress() instanceof Inet4Address && ia.getAddress().equals(addr)) {
                            short len = ia.getNetworkPrefixLength();
                            if (len >= 8 && len <= 30) {
                                return len;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return 24; // Fallback padrão /24
    }

    private static Integer ipv4ParaInt(String ip) {
        if (ip == null) return null;
        String[] partes = ip.trim().split("\\.");
        if (partes.length != 4) return null;
        int valor = 0;
        try {
            for (String p : partes) {
                int octeto = Integer.parseInt(p);
                if (octeto < 0 || octeto > 255) return null;
                valor = (valor << 8) | octeto;
            }
            return valor;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean ehMacUnicastValido(String macNormalizado) {
        if (macNormalizado == null || macNormalizado.length() != 12) return false;
        if ("000000000000".equals(macNormalizado) || "ffffffffffff".equalsIgnoreCase(macNormalizado)) {
            return false;
        }
        // Bit I/G (menor bit do 1º octeto) == 1 indica multicast/broadcast (ex: 01-00-5e...)
        int primeiroOcteto = Integer.parseInt(macNormalizado.substring(0, 2), 16);
        return (primeiroOcteto & 1) == 0;
    }

    private static String executarProcessoLocal(String... comando) {
        try {
            Process proc = new ProcessBuilder(comando)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String linha;
                while ((linha = br.readLine()) != null) {
                    sb.append(linha).append("\n");
                }
            }
            proc.waitFor(2, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

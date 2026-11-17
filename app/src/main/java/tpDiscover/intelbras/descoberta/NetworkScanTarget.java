package tpDiscover.intelbras.descoberta;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolve alvos de varredura de rede a partir de especificação CIDR (ex: {@code 10.1.40.0/24}),
 * faixa de IPs (ex: {@code 10.1.40.80-250} ou {@code 10.1.40.80-10.1.40.250}), IP único ou {@code auto}
 * (detectando as sub-redes IPv4 locais ativas).
 */
public record NetworkScanTarget(String especificacao, List<String> ips) {

    public static final int PREFIXO_MINIMO_PERMITIDO = 22;
    public static final int MAX_HOSTS_POR_ESPECIFICACAO = 1024;

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    );
    private static final Pattern CIDR_PATTERN = Pattern.compile(
            "^(\\d+\\.\\d+\\.\\d+\\.\\d+)/(\\d{1,2})$"
    );
    private static final Pattern FAIXA_CURTA_PATTERN = Pattern.compile(
            "^(\\d+\\.\\d+\\.\\d+)\\.(\\d{1,3})-(\\d{1,3})$"
    );
    private static final Pattern FAIXA_COMPLETA_PATTERN = Pattern.compile(
            "^(\\d+\\.\\d+\\.\\d+\\.\\d+)-(\\d+\\.\\d+\\.\\d+\\.\\d+)$"
    );

    public NetworkScanTarget {
        ips = ips != null ? List.copyOf(ips) : List.of();
    }

    /**
     * Expande uma ou mais especificações separadas por vírgula/ponto e vírgula em blocos e retorna todos os IPs únicos.
     */
    public static List<NetworkScanTarget> interpretarEspecificacoes(String specs) {
        if (specs == null || specs.isBlank()) {
            return List.of();
        }
        List<NetworkScanTarget> resultados = new ArrayList<>();
        for (String parte : specs.split("[,;\\s]+")) {
            String limpa = parte.trim();
            if (limpa.isEmpty()) continue;
            if (limpa.equalsIgnoreCase("auto")) {
                resultados.addAll(detectarSubredesLocais());
            } else {
                resultados.add(parsearUnico(limpa));
            }
        }
        return resultados;
    }

    /**
     * Agrega todos os IPs únicos a partir de uma lista de {@link NetworkScanTarget}, em ordem determinística.
     */
    public static List<String> consolidarIps(List<NetworkScanTarget> targets) {
        Set<String> unicos = new LinkedHashSet<>();
        if (targets != null) {
            for (NetworkScanTarget t : targets) {
                if (t != null && t.ips() != null) {
                    unicos.addAll(t.ips());
                }
            }
        }
        return new ArrayList<>(unicos);
    }

    public static NetworkScanTarget parsearUnico(String spec) {
        String limpa = spec != null ? spec.trim() : "";
        if (limpa.isEmpty()) {
            throw new IllegalArgumentException("Especificação de scan vazia.");
        }

        Matcher cidr = CIDR_PATTERN.matcher(limpa);
        if (cidr.matches()) {
            String ipBase = cidr.group(1);
            int prefix = Integer.parseInt(cidr.group(2));
            return new NetworkScanTarget(limpa, expandirCidr(ipBase, prefix));
        }

        Matcher faixaCurta = FAIXA_CURTA_PATTERN.matcher(limpa);
        if (faixaCurta.matches()) {
            String prefixoRede = faixaCurta.group(1);
            int ini = Integer.parseInt(faixaCurta.group(2));
            int fim = Integer.parseInt(faixaCurta.group(3));
            if (ini < 0 || fim > 255 || ini > fim) {
                throw new IllegalArgumentException("Faixa de IPs inválida: " + limpa);
            }
            return new NetworkScanTarget(limpa, expandirFaixa(prefixoRede + "." + ini, prefixoRede + "." + fim));
        }

        Matcher faixaCompleta = FAIXA_COMPLETA_PATTERN.matcher(limpa);
        if (faixaCompleta.matches()) {
            return new NetworkScanTarget(limpa, expandirFaixa(faixaCompleta.group(1), faixaCompleta.group(2)));
        }

        if (ehIpv4Valido(limpa)) {
            return new NetworkScanTarget(limpa, List.of(limpa));
        }

        throw new IllegalArgumentException(
                "Formato de scan inválido: '" + spec + "'. Use CIDR (ex: 10.1.40.0/24), faixa (ex: 10.1.40.80-250), IP ou 'auto'."
        );
    }

    /**
     * Detecta sub-redes IPv4 locais válidas nas interfaces ativas da máquina (ignorando loopback, link-local e bridges virtuais).
     */
    public static List<NetworkScanTarget> detectarSubredesLocais() {
        List<NetworkScanTarget> detectadas = new ArrayList<>();
        Set<String> specsVistos = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                String nome = nif.getName() != null ? nif.getName().toLowerCase() : "";
                if (nome.startsWith("docker") || nome.startsWith("veth") || nome.startsWith("br-")
                        || nome.startsWith("virbr") || nome.startsWith("vmnet")) {
                    continue;
                }

                for (InterfaceAddress ifAddr : nif.getInterfaceAddresses()) {
                    InetAddress addr = ifAddr.getAddress();
                    if (!(addr instanceof Inet4Address)) continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isMulticastAddress()) continue;

                    short prefix = ifAddr.getNetworkPrefixLength();
                    if (prefix < PREFIXO_MINIMO_PERMITIDO || prefix > 30) {
                        continue;
                    }
                    long ipLong = ipv4ParaLong(addr.getHostAddress());
                    long mascara = prefix >= 32 ? 0xFFFFFFFFL : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                    long rede = ipLong & mascara;
                    String cidrSpec = longParaIpv4(rede) + "/" + prefix;
                    if (specsVistos.add(cidrSpec)) {
                        detectadas.add(new NetworkScanTarget(cidrSpec + " (" + nif.getName() + ")", expandirCidr(longParaIpv4(rede), prefix)));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return detectadas;
    }

    public static List<String> expandirCidr(String ipStr, int prefix) {
        if (!ehIpv4Valido(ipStr)) {
            throw new IllegalArgumentException("IPv4 inválido no CIDR: " + ipStr);
        }
        if (prefix < PREFIXO_MINIMO_PERMITIDO || prefix > 32) {
            throw new IllegalArgumentException(
                    "Prefixo /" + prefix + " fora do limite permitido (/" + PREFIXO_MINIMO_PERMITIDO + " a /32)."
            );
        }
        long ip = ipv4ParaLong(ipStr);
        if (prefix == 32) {
            return List.of(longParaIpv4(ip));
        }
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long net = ip & mask;
        long broadcast = net | (~mask & 0xFFFFFFFFL);
        long inicio = (prefix == 31) ? net : net + 1;
        long fim = (prefix == 31) ? broadcast : broadcast - 1;
        return construirListaFaixa(inicio, fim);
    }

    public static List<String> expandirFaixa(String ipInicioStr, String ipFimStr) {
        if (!ehIpv4Valido(ipInicioStr) || !ehIpv4Valido(ipFimStr)) {
            throw new IllegalArgumentException("Faixa IPv4 inválida: " + ipInicioStr + "-" + ipFimStr);
        }
        long ini = ipv4ParaLong(ipInicioStr);
        long fim = ipv4ParaLong(ipFimStr);
        if (ini > fim) {
            throw new IllegalArgumentException("IP inicial maior que IP final na faixa: " + ipInicioStr + "-" + ipFimStr);
        }
        return construirListaFaixa(ini, fim);
    }

    private static List<String> construirListaFaixa(long inicio, long fim) {
        long quantidade = (fim - inicio) + 1;
        if (quantidade > MAX_HOSTS_POR_ESPECIFICACAO) {
            throw new IllegalArgumentException(
                    "Faixa muito grande (" + quantidade + " IPs). Limite máximo: " + MAX_HOSTS_POR_ESPECIFICACAO + " hosts por bloco."
            );
        }
        List<String> lista = new ArrayList<>((int) quantidade);
        for (long v = inicio; v <= fim; v++) {
            lista.add(longParaIpv4(v));
        }
        return lista;
    }

    public static boolean ehIpv4Valido(String ip) {
        return ip != null && IPV4_PATTERN.matcher(ip.trim()).matches();
    }

    public static long ipv4ParaLong(String ip) {
        String[] oct = ip.trim().split("\\.");
        return (Long.parseLong(oct[0]) << 24)
                | (Long.parseLong(oct[1]) << 16)
                | (Long.parseLong(oct[2]) << 8)
                | Long.parseLong(oct[3]);
    }

    public static String longParaIpv4(long val) {
        return ((val >> 24) & 0xFF) + "."
                + ((val >> 16) & 0xFF) + "."
                + ((val >> 8) & 0xFF) + "."
                + (val & 0xFF);
    }
}

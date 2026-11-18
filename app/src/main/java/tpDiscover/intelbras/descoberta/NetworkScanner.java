package tpDiscover.intelbras.descoberta;

import tpDiscover.intelbras.coleta.LocalGatewayDetector;
import tpDiscover.intelbras.coleta.SwitchTarget;
import tpDiscover.intelbras.modelos.MacUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tpDiscover.intelbras.util.Compat;
import java.util.concurrent.ExecutorService;
/**
 * Scanner paralelo de rede (Virtual Threads) que descobre produtos de rede Intelbras
 * usando TCP pre-probe, banners SSH/HTTP/Telnet, SNMP e cruzamento com cache ARP local (OUI).
 */
public class NetworkScanner {

    public static final int CONCORRENCIA_PADRAO_SCAN = 64;
    private static final Pattern IPV4_MAC_PATTERN = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d{1,3}){3}).*?([0-9a-fA-F]{2}(?:[:-][0-9a-fA-F]{2}){5})"
    );

    public static final class ScanResult {
        private final List<DiscoveredProduct> produtosInteresse;
        private final List<DiscoveredProduct> hostsIgnorados;
        private final int totalIpsVerificados;
        private final long duracaoMillis;
        private final boolean cancelado;

        public ScanResult(List<DiscoveredProduct> produtosInteresse, List<DiscoveredProduct> hostsIgnorados, int totalIpsVerificados, long duracaoMillis, boolean cancelado) {
            this.produtosInteresse = produtosInteresse;
            this.hostsIgnorados = hostsIgnorados;
            this.totalIpsVerificados = totalIpsVerificados;
            this.duracaoMillis = duracaoMillis;
            this.cancelado = cancelado;
        }

        public List<DiscoveredProduct> produtosInteresse() {
            return produtosInteresse;
        }

        public List<DiscoveredProduct> hostsIgnorados() {
            return hostsIgnorados;
        }

        public int totalIpsVerificados() {
            return totalIpsVerificados;
        }

        public long duracaoMillis() {
            return duracaoMillis;
        }

        public boolean cancelado() {
            return cancelado;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ScanResult)) return false;
            ScanResult o = (ScanResult) obj;
            return java.util.Objects.equals(produtosInteresse, o.produtosInteresse)
                    && java.util.Objects.equals(hostsIgnorados, o.hostsIgnorados)
                    && totalIpsVerificados == o.totalIpsVerificados
                    && duracaoMillis == o.duracaoMillis
                    && cancelado == o.cancelado;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(produtosInteresse, hostsIgnorados, totalIpsVerificados, duracaoMillis, cancelado);
        }

        @Override
        public String toString() {
            return "ScanResult[produtosInteresse=" + produtosInteresse + ", hostsIgnorados=" + hostsIgnorados + ", totalIpsVerificados=" + totalIpsVerificados + ", duracaoMillis=" + duracaoMillis + ", cancelado=" + cancelado + "]";
        }

        public ScanResult(
                List<DiscoveredProduct> produtosInteresse,
                List<DiscoveredProduct> hostsIgnorados,
                int totalIpsVerificados,
                long duracaoMillis
        ) {
            this(produtosInteresse, hostsIgnorados, totalIpsVerificados, duracaoMillis, false);
        }
    }

    @FunctionalInterface
    public interface ScanProgressListener {
        void onHostVerificado(int processados, int total, DiscoveredProduct encontradoOuNull);
    }

    private final ProductFingerprinter.ProbeConfig probeConfig;
    private final int maxConcorrencia;

    public NetworkScanner() {
        this(ProductFingerprinter.ProbeConfig.padrao(), CONCORRENCIA_PADRAO_SCAN);
    }

    public NetworkScanner(ProductFingerprinter.ProbeConfig probeConfig, int maxConcorrencia) {
        this.probeConfig = probeConfig != null ? probeConfig : ProductFingerprinter.ProbeConfig.padrao();
        this.maxConcorrencia = Math.max(4, maxConcorrencia);
    }

    /**
     * Varre a lista de IPs informada em paralelo e classifica os dispositivos respondentes.
     */
    public ScanResult escanear(List<String> ips) {
        return escanear(ips, null, null);
    }

    /**
     * Varre a lista de IPs informada em paralelo, emitindo progresso incremental e respeitando cancelamento.
     */
    public ScanResult escanear(List<String> ips, ScanProgressListener listener, AtomicBoolean cancelar) {
        long inicio = System.currentTimeMillis();
        if (ips == null || ips.isEmpty()) {
            return new ScanResult(Compat.listOf(), Compat.listOf(), 0, 0, false);
        }

        Map<String, String> cacheArpLocal = lerCacheArpLocal();
        Semaphore sem = new Semaphore(maxConcorrencia);
        AtomicInteger processados = new AtomicInteger(0);
        int total = ips.size();
        List<Optional<DiscoveredProduct>> brutos = new ArrayList<>();

        ExecutorService executor = Compat.novoExecutor("scan", Math.max(1, maxConcorrencia));
        try {
            List<Future<Optional<DiscoveredProduct>>> futures = new ArrayList<>(total);
            for (String ip : ips) {
                futures.add(executor.submit(() -> {
                    if (cancelar != null && cancelar.get()) {
                        return Optional.empty();
                    }
                    sem.acquire();
                    Optional<DiscoveredProduct> opt = Optional.empty();
                    try {
                        if (cancelar == null || !cancelar.get()) {
                            opt = ProductFingerprinter.sondarHost(ip, cacheArpLocal.get(ip), probeConfig);
                        }
                        return opt;
                    } finally {
                        sem.release();
                        int done = processados.incrementAndGet();
                        if (listener != null) {
                            listener.onHostVerificado(done, total, opt.orElse(null));
                        }
                    }
                }));
            }
            for (Future<Optional<DiscoveredProduct>> f : futures) {
                try {
                    brutos.add(f.get());
                } catch (Exception ignored) {
                }
            }
        } finally {
            Compat.encerrarEAguardar(executor);
        }

        Comparator<DiscoveredProduct> porIpNumerico = Comparator.comparingLong(p -> NetworkScanTarget.ipv4ParaLong(p.ip()));
        List<DiscoveredProduct> interesse = new ArrayList<>();
        List<DiscoveredProduct> ignorados = new ArrayList<>();

        for (Optional<DiscoveredProduct> opt : brutos) {
            if (!opt.isPresent()) continue;
            DiscoveredProduct p = opt.get();
            if (p.ehProdutoRedeInteresse()) {
                interesse.add(p);
            } else {
                ignorados.add(p);
            }
        }
        interesse.sort(porIpNumerico);
        ignorados.sort(porIpNumerico);

        long duracao = System.currentTimeMillis() - inicio;
        boolean foiCancelado = cancelar != null && cancelar.get();
        return new ScanResult(interesse, ignorados, ips.size(), duracao, foiCancelado);
    }

    /**
     * Converte produtos descobertos com CLI suportada em {@link SwitchTarget} usando credencial padrão.
     */
    public static List<SwitchTarget> paraSwitchTargets(
            List<DiscoveredProduct> produtos,
            String usuarioPadrao,
            String senhaPadrao
    ) {
        if (produtos == null || produtos.isEmpty() || usuarioPadrao == null || Compat.isBlank(usuarioPadrao)) {
            return Compat.listOf();
        }
        List<SwitchTarget> alvos = new ArrayList<>();
        for (DiscoveredProduct p : produtos) {
            if (!p.suportaColetaCli()) continue;
            Integer portaCustom = null;
            if (p.portaCliPreferida() != null
                    && p.portaCliPreferida() != 22
                    && p.portaCliPreferida() != 23) {
                portaCustom = p.portaCliPreferida();
            }
            alvos.add(new SwitchTarget(p.ip(), usuarioPadrao, senhaPadrao != null ? senhaPadrao : "", portaCustom));
        }
        return alvos;
    }

    /**
     * Mescla alvos manuais (ex: switches em portas customizadas ou com senha diferente) e produtos descobertos no scan,
     * preservando prioridade de configuração manual quando o IP aparece em ambos.
     */
    public static List<SwitchTarget> combinarComAlvosManuais(
            List<DiscoveredProduct> descobertos,
            List<SwitchTarget> manuais,
            String usuarioPadrao,
            String senhaPadrao
    ) {
        Map<String, SwitchTarget> porIp = new LinkedHashMap<>();
        if (manuais != null) {
            for (SwitchTarget m : manuais) {
                porIp.putIfAbsent(m.ip(), m);
            }
        }
        for (SwitchTarget s : paraSwitchTargets(descobertos, usuarioPadrao, senhaPadrao)) {
            porIp.putIfAbsent(s.ip(), s);
        }
        return new ArrayList<>(porIp.values());
    }

    /**
     * Lê o cache ARP/Neighbor local do sistema operacional para enriquecer detecção por OUI Intelbras na LAN.
     */
    public static Map<String, String> lerCacheArpLocal() {
        Map<String, String> mapa = new HashMap<>();
        for (List<String> cmd : Compat.listOf(Compat.listOf("ip", "neigh", "show"), Compat.listOf("arp", "-a"))) {
            try {
                Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                boolean terminou = proc.waitFor(1500, TimeUnit.MILLISECONDS);
                if (!terminou) {
                    proc.destroyForcibly();
                    continue;
                }
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Matcher m = IPV4_MAC_PATTERN.matcher(line);
                        if (m.find()) {
                            String ip = m.group(1);
                            String mac = MacUtils.normalizar(m.group(2));
                            if (LocalGatewayDetector.ehMacUnicastValido(mac)) {
                                mapa.putIfAbsent(ip, mac);
                            }
                        }
                    }
                }
                if (!mapa.isEmpty()) {
                    return mapa;
                }
            } catch (Exception ignored) {
            }
        }
        return mapa;
    }
}

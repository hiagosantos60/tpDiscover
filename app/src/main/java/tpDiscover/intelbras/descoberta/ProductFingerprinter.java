package tpDiscover.intelbras.descoberta;

import tpDiscover.intelbras.descoberta.DiscoveredProduct.FamiliaProduto;
import tpDiscover.intelbras.modelos.MacUtils;
import tpDiscover.intelbras.modelos.OuiLookup;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executa fingerprinting passivo/somente-leitura via TCP (SSH banner, HTTP GET /, Telnet banner)
 * e SNMPv2c ({@code sysDescr}/{@code sysName}) para identificar switches, OLTs, APs e roteadores
 * Intelbras antes de tentar autenticação CLI, descartando servidores e dispositivos irrelevantes.
 */
public final class ProductFingerprinter {

    public static final List<Integer> PORTAS_PADRAO = List.of(22, 23, 80, 443);

    private static final int MAX_HTTP_BYTES = 4096;
    private static final int[] OID_SYS_DESCR = {1, 3, 6, 1, 2, 1, 1, 1, 0};
    private static final int[] OID_SYS_NAME = {1, 3, 6, 1, 2, 1, 1, 5, 0};
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title>\\s*(.*?)\\s*</title>");
    private static final Pattern SERVER_HEADER_PATTERN = Pattern.compile("(?im)^Server:\\s*(.+?)\\s*$");
    private static final Pattern LOCATION_HEADER_PATTERN = Pattern.compile("(?im)^Location:\\s*(\\S+)\\s*$");
    private static final Pattern MODELO_INTELBRAS_PATTERN = Pattern.compile(
            "(?i)\\b((?:SG|S|SC|R|OLT)[-\\s]?\\d{3,4}[A-Z0-9+-]{0,8}(?:\\s+(?:MR|L2\\+?|MAX|LITE|POE|PB|PA)){0,3}"
                    + "|OLT[-\\s]+(?:G\\d{2}|AN\\d{4}(?:-G?\\d+)?)"
                    + "|AN(?:6000|6001|5516)(?:-[A-Z0-9]+)?|(?:4840E|4880E|8820I|8820G)"
                    + "|(?:AP|WOM|APC)[-\\s]?\\d+[A-Z0-9.+-]{0,10})(?=\\s|$|[,;])"
    );
    private static final Pattern ANSI_CSI_PATTERN = Pattern.compile("\\u001B\\[[0-9;?]*[A-Za-z]");

    public record ProbeConfig(
            int tcpTimeoutMillis,
            int bannerTimeoutMillis,
            boolean usarSnmp,
            List<String> comunidadesSnmp,
            boolean sondarTelnet
    ) {
        /** Construtor retrocompatível: com sonda de banner Telnet habilitada. */
        public ProbeConfig(int tcpTimeoutMillis, int bannerTimeoutMillis, boolean usarSnmp, List<String> comunidadesSnmp) {
            this(tcpTimeoutMillis, bannerTimeoutMillis, usarSnmp, comunidadesSnmp, true);
        }

        public static ProbeConfig padrao() {
            return new ProbeConfig(500, 900, true, List.of("public"));
        }

        /**
         * Sondagem leve feita imediatamente antes do login CLI: sem abrir a porta Telnet,
         * para não ocupar o VTY único de switches SG antigos.
         */
        public static ProbeConfig preColeta() {
            return new ProbeConfig(450, 700, true, List.of("public"), false);
        }
    }

    public record SnmpSystemInfo(String sysDescr, String sysName) {}

    private ProductFingerprinter() {}

    /**
     * Sondagem completa de um IP único: verifica portas abertas e coleta banners/SNMP somente se ativo.
     */
    public static Optional<DiscoveredProduct> sondarHost(String ip, String macArp, ProbeConfig config) {
        if (ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        ProbeConfig cfg = config != null ? config : ProbeConfig.padrao();
        List<Integer> portasAbertas = sondarPortasTcp(ip, PORTAS_PADRAO, cfg.tcpTimeoutMillis());

        boolean ouiIntelbras = macArp != null && !macArp.isBlank() && OuiLookup.ehIntelbras(macArp);
        if (portasAbertas.isEmpty() && !ouiIntelbras) {
            return Optional.empty();
        }

        String sshBanner = portasAbertas.contains(22)
                ? lerBannerTcp(ip, 22, cfg.tcpTimeoutMillis(), cfg.bannerTimeoutMillis(), false)
                : "";

        String httpSnippet = "";
        if (portasAbertas.contains(80)) {
            httpSnippet = lerHttpSnippet(ip, 80, false, cfg.tcpTimeoutMillis(), cfg.bannerTimeoutMillis());
        } else if (portasAbertas.contains(443)) {
            httpSnippet = lerHttpSnippet(ip, 443, true, cfg.tcpTimeoutMillis(), cfg.bannerTimeoutMillis());
        }

        SnmpSystemInfo snmp = cfg.usarSnmp()
                ? consultarSnmpBasico(ip, cfg.bannerTimeoutMillis(), cfg.comunidadesSnmp())
                : new SnmpSystemInfo("", "");

        boolean sshConhecido = sshBanner.toLowerCase().contains("ssh-2.0-os-")
                || sshBanner.toLowerCase().contains("ipssh");
        boolean httpVazioOuGenerico = httpSnippet.isBlank() || !httpIdentificaFamiliaConhecida(httpSnippet);
        String telnetBanner = "";
        if (cfg.sondarTelnet() && portasAbertas.contains(23) && !sshConhecido && httpVazioOuGenerico
                && snmp.sysDescr().isBlank() && snmp.sysName().isBlank()) {
            telnetBanner = lerBannerTcp(ip, 23, cfg.tcpTimeoutMillis(),
                    Math.max(450, Math.min(750, cfg.bannerTimeoutMillis())), true);
        }

        DiscoveredProduct produto = classificarSinais(
                ip,
                portasAbertas,
                sshBanner,
                httpSnippet,
                telnetBanner,
                snmp.sysDescr(),
                snmp.sysName(),
                macArp
        );
        return Optional.of(produto);
    }

    /**
     * Classificação determinística a partir dos sinais capturados (testável offline sem rede).
     */
    public static DiscoveredProduct classificarSinais(
            String ip,
            List<Integer> portasAbertas,
            String sshBanner,
            String httpSnippet,
            String telnetBanner,
            String snmpSysDescr,
            String snmpSysName,
            String macArp
    ) {
        List<Integer> portas = portasAbertas != null ? portasAbertas : List.of();
        String ssh = sshBanner != null ? sshBanner.trim() : "";
        String http = httpSnippet != null ? httpSnippet : "";
        String telnet = telnetBanner != null ? telnetBanner.trim() : "";
        String sysDescr = snmpSysDescr != null ? snmpSysDescr.trim() : "";
        String sysName = snmpSysName != null ? snmpSysName.trim() : "";
        String macNorm = macArp != null ? MacUtils.normalizar(macArp) : "";
        String fabricanteOui = MacUtils.ehMacValido(macNorm) ? OuiLookup.identificarFabricante(macNorm).orElse("") : "";
        boolean ouiIntelbras = !macNorm.isBlank() && OuiLookup.ehIntelbras(macNorm);

        String sshLower = ssh.toLowerCase();
        String httpLower = http.toLowerCase();
        String telnetLower = telnet.toLowerCase();
        String snmpCombinado = (sysDescr + " " + sysName).trim();
        String snmpLower = snmpCombinado.toLowerCase();

        FamiliaProduto familia = null;
        String modelo = "";

        // 1. Sinais SNMP são os mais específicos quando disponíveis
        if (!snmpLower.isBlank()) {
            familia = inferirFamiliaPorTexto(snmpLower);
            modelo = extrairModeloIntelbras(sysDescr);
            if (modelo.isBlank()) {
                modelo = extrairModeloIntelbras(sysName);
            }
            if (modelo.isBlank() && !sysName.isBlank() && familia != null
                    && !sysName.equalsIgnoreCase("ID do Equipamento")) {
                modelo = sysName;
            }
        }

        // 2. Assinaturas HTTP dos firmwares reais Intelbras / OpenWrt
        if (familia == null && !httpLower.isBlank()) {
            if (httpLower.contains("httpd_gw")) {
                familia = FamiliaProduto.ROTEADOR_GATEWAY;
                modelo = "Roteador Intelbras";
            } else if (httpLower.contains("web managerment home")
                    || (httpLower.contains("server: httpd") && (httpLower.contains("wnm") || httpLower.contains("comware") || httpLower.contains("intelbras")))) {
                familia = FamiliaProduto.SWITCH_COMWARE;
            } else if (httpLower.contains("intelbras | login")) {
                familia = FamiliaProduto.SWITCH_S2050_LITE;
            } else if (httpLower.contains("goahead-webs")
                    && (httpLower.contains("intelbras") || httpLower.contains("mr") || httpLower.contains("switch") || portas.contains(23) || ouiIntelbras)) {
                familia = FamiliaProduto.SWITCH_SG_MR_L2PLUS;
            } else if (httpLower.contains("web switch") && (httpLower.contains("intelbras") || httpLower.contains("sg ") || portas.contains(22) || portas.contains(23))) {
                familia = FamiliaProduto.SWITCH_SG_MR;
            } else if (httpLower.contains("x-permitted-cross-domain-policies: master-only")
                    || (httpLower.contains("sessionid=0") && (httpLower.contains("intelbras") || httpLower.contains("switch")))) {
                familia = FamiliaProduto.SWITCH_S30;
            } else if ((httpLower.contains("luci") || httpLower.contains("openwrt"))
                    && (httpLower.contains("intelbras") || httpLower.contains("ap ") || httpLower.contains("wisefi") || ouiIntelbras)) {
                familia = FamiliaProduto.ACCESS_POINT;
            } else if (httpLower.contains("olt") && httpLower.contains("intelbras")) {
                familia = FamiliaProduto.OLT;
            }
            if (modelo.isBlank()) {
                modelo = extrairModeloIntelbras(http);
            }
        }

        // 3. Assinaturas de banner SSH
        if (familia == null && !sshLower.isBlank()) {
            if (sshLower.contains("ssh-2.0-os-")) {
                familia = FamiliaProduto.SWITCH_COMWARE;
            } else if (sshLower.contains("ssh-2.0-ipssh_5")) {
                familia = FamiliaProduto.SWITCH_S30;
            } else if (sshLower.contains("ssh-1.99-ipssh-") || sshLower.contains("ipssh-6")) {
                familia = FamiliaProduto.SWITCH_SG_MR;
            } else if (sshLower.contains("ipssh")) {
                familia = FamiliaProduto.SWITCH_S30;
            } else if (sshLower.contains("dropbear") && (ouiIntelbras || httpLower.contains("openwrt") || httpLower.contains("luci"))) {
                familia = FamiliaProduto.ACCESS_POINT;
            } else if (sshLower.contains("ssh-2.0-private")
                    && (telnetLower.contains("username(1-64 chars)") || !portas.contains(23) || ouiIntelbras)) {
                familia = FamiliaProduto.OLT;
            } else if (ehSistemaOperacionalHostGenerico(sshLower) && !ouiIntelbras) {
                familia = FamiliaProduto.DESCONHECIDO_IGNORAR;
            }
        }

        // 4. Banner Telnet ou fallback por OUI Intelbras com portas de gerenciamento abertas
        if (familia == null && !telnetLower.isBlank()) {
            FamiliaProduto porTelnet = inferirFamiliaPorTexto(telnetLower);
            if (porTelnet != null) {
                familia = porTelnet;
            } else if (telnetLower.contains("intelbras") || (ouiIntelbras && (telnetLower.contains("user") || telnetLower.contains("login")))) {
                familia = FamiliaProduto.SWITCH_S30;
            }
            if (modelo.isBlank()) {
                modelo = extrairModeloIntelbras(telnet);
            }
        }

        if (familia == FamiliaProduto.OLT && modelo.isBlank()) {
            modelo = extrairModeloIntelbras(telnet);
            if (modelo.isBlank()) {
                if (telnetLower.contains("zzzzzzzzzzzz")) {
                    modelo = "OLT AN6000/AN6001";
                } else if (telnetLower.contains("username(1-64 chars)") || sshLower.contains("ssh-2.0-private")) {
                    modelo = "OLT G08/G16/4840E";
                }
            }
        }

        if (familia == null) {
            if (ouiIntelbras && (portas.contains(22) || portas.contains(23))) {
                familia = FamiliaProduto.SWITCH_S30;
            } else {
                familia = FamiliaProduto.DESCONHECIDO_IGNORAR;
            }
        }

        boolean suportaCli = familia.coletaCliSuportada() && (portas.contains(22) || portas.contains(23));
        String protocolo = determinarProtocoloPreferido(familia, portas, sshLower);
        Integer portaPreferida = determinarPortaPreferida(protocolo, portas);
        String resumoBanner = resumirSinais(ssh, http, sysDescr, sysName, telnet);

        return new DiscoveredProduct(
                ip,
                portas,
                familia,
                modelo,
                resumoBanner,
                macNorm,
                fabricanteOui,
                suportaCli,
                protocolo,
                portaPreferida
        );
    }

    private static FamiliaProduto inferirFamiliaPorTexto(String textoLower) {
        if (textoLower.matches(".*\\b(s23\\d{2}|s33\\d{2}|comware)\\b.*")) {
            return FamiliaProduto.SWITCH_COMWARE;
        }
        if (textoLower.matches(".*\\b(s2050|sg\\s*2404d)\\b.*") || (textoLower.contains("lite") && textoLower.contains("s30"))) {
            return FamiliaProduto.SWITCH_S2050_LITE;
        }
        if (textoLower.matches(".*\\b(s30\\d{2}|s3028|s3054|s2110)\\b.*")) {
            return FamiliaProduto.SWITCH_S30;
        }
        if (textoLower.contains("l2+") && textoLower.contains("sg")) {
            return FamiliaProduto.SWITCH_SG_MR_L2PLUS;
        }
        if (textoLower.matches(".*\\bsg\\s*\\d{4}\\b.*")) {
            return FamiliaProduto.SWITCH_SG_MR;
        }
        if (textoLower.contains("olt")
                || textoLower.contains("8820")
                || textoLower.contains("4840e")
                || textoLower.contains("4880e")
                || textoLower.contains("an6000")
                || textoLower.contains("an6001")
                || textoLower.contains("an5516")
                || textoLower.contains("zzzzzzzzzzzz")
                || textoLower.contains("broadband network platform")
                || textoLower.contains("username(1-64 chars)")) {
            return FamiliaProduto.OLT;
        }
        if (textoLower.matches(".*\\b(r3005|gw)\\b.*") || (textoLower.contains("intelbras") && textoLower.contains("router"))) {
            return FamiliaProduto.ROTEADOR_GATEWAY;
        }
        if (textoLower.matches(".*\\b(ap\\s*\\d{3,4}|wisefi|openwrt|wom|apc)\\b.*")) {
            return FamiliaProduto.ACCESS_POINT;
        }
        return null;
    }

    private static boolean ehSistemaOperacionalHostGenerico(String sshLower) {
        return sshLower.contains("ubuntu")
                || sshLower.contains("debian")
                || sshLower.contains("centos")
                || sshLower.contains("red hat")
                || sshLower.contains("fedora")
                || sshLower.contains("freebsd")
                || sshLower.contains("openbsd")
                || sshLower.contains("raspbian")
                || sshLower.contains("windows");
    }

    private static String determinarProtocoloPreferido(FamiliaProduto familia, List<Integer> portas, String sshLower) {
        if (familia == FamiliaProduto.DESCONHECIDO_IGNORAR) {
            return "";
        }
        if (!familia.coletaCliSuportada()) {
            if (portas.contains(80)) return "HTTP";
            if (portas.contains(443)) return "HTTPS";
            return "";
        }
        // Modelos SG MR com IPSSH-6.x antigo (ssh-dss) e OLTs OpenSSH antigos falham em JREs modernas; preferir Telnet se aberto
        boolean preferirTelnet = (sshLower.contains("ipssh-6")
                || (familia == FamiliaProduto.OLT && sshLower.contains("openssh")))
                && portas.contains(23);
        if (portas.contains(22) && !preferirTelnet) {
            return "SSH";
        }
        if (portas.contains(23)) {
            return "TELNET";
        }
        if (portas.contains(80)) return "HTTP";
        if (portas.contains(443)) return "HTTPS";
        return "";
    }

    private static Integer determinarPortaPreferida(String protocolo, List<Integer> portas) {
        return switch (protocolo) {
            case "SSH" -> portas.contains(22) ? 22 : null;
            case "TELNET" -> portas.contains(23) ? 23 : null;
            case "HTTP" -> portas.contains(80) ? 80 : null;
            case "HTTPS" -> portas.contains(443) ? 443 : null;
            default -> null;
        };
    }

    private static String resumirSinais(String ssh, String http, String sysDescr, String sysName, String telnet) {
        List<String> partes = new ArrayList<>();
        if (!sysDescr.isBlank()) {
            partes.add("SNMP=" + limitar(sysDescr, 48));
        } else if (!sysName.isBlank()) {
            partes.add("SNMP=" + limitar(sysName, 40));
        }
        if (!ssh.isBlank()) {
            partes.add("SSH=" + limitar(ssh, 40));
        }
        if (!http.isBlank()) {
            String titulo = extrairGrupo(TITLE_PATTERN, http);
            String server = extrairGrupo(SERVER_HEADER_PATTERN, http);
            if (!titulo.isBlank()) {
                partes.add("HTTP='" + limitar(titulo, 36) + "'");
            } else if (!server.isBlank()) {
                partes.add("HTTP=" + limitar(server, 36));
            }
        }
        if (partes.isEmpty() && !telnet.isBlank()) {
            String telnetResumo = telnet.replaceAll("(?i)(?:z\\s*){5,}", "[AN Banner] ").replaceAll("\\s+", " ").trim();
            partes.add("Telnet=" + limitar(telnetResumo, 40));
        }
        return String.join("; ", partes);
    }

    private static String extrairModeloIntelbras(String texto) {
        if (texto == null || texto.isBlank()) return "";
        Matcher m = MODELO_INTELBRAS_PATTERN.matcher(texto);
        while (m.find()) {
            String cand = m.group(1).replaceAll("\\s+", " ").trim();
            String upper = cand.toUpperCase();
            if (upper.startsWith("S2") || upper.startsWith("S3") || upper.startsWith("SG")
                    || upper.startsWith("SC") || upper.startsWith("R3") || upper.startsWith("AP")
                    || upper.startsWith("OLT") || upper.startsWith("WOM")
                    || upper.startsWith("AN6") || upper.startsWith("AN5")
                    || upper.startsWith("4840") || upper.startsWith("4880") || upper.startsWith("8820")) {
                return cand;
            }
        }
        return "";
    }

    private static String extrairGrupo(Pattern p, String texto) {
        Matcher m = p.matcher(texto);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").trim() : "";
    }

    private static String limitar(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static List<Integer> sondarPortasTcp(String ip, List<Integer> portas, int timeoutMillis) {
        List<Integer> abertas = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>(portas.size());
            for (int porta : portas) {
                futures.add(executor.submit(() -> portaAbertaTcp(ip, porta, timeoutMillis) ? porta : -1));
            }
            for (Future<Integer> f : futures) {
                try {
                    int res = f.get();
                    if (res > 0) abertas.add(res);
                } catch (Exception ignored) {
                }
            }
        }
        abertas.sort(Integer::compareTo);
        return abertas;
    }

    private static boolean portaAbertaTcp(String ip, int porta, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, porta), timeoutMillis);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean httpIdentificaFamiliaConhecida(String httpSnippet) {
        if (httpSnippet == null || httpSnippet.isBlank()) return false;
        String lower = httpSnippet.toLowerCase();
        return lower.contains("httpd_gw")
                || lower.contains("web managerment home")
                || lower.contains("intelbras | login")
                || lower.contains("goahead-webs")
                || lower.contains("web switch")
                || lower.contains("x-permitted-cross-domain-policies: master-only")
                || lower.contains("sessionid=0")
                || lower.contains("luci")
                || lower.contains("openwrt")
                || (lower.contains("olt") && lower.contains("intelbras"))
                || (lower.contains("server: httpd") && (lower.contains("wnm") || lower.contains("comware") || lower.contains("intelbras")));
    }

    private static String lerBannerTcp(String ip, int porta, int connectTimeoutMs, int readTimeoutMs, boolean enviarCrlf) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, porta), connectTimeoutMs);
            int sliceMs = enviarCrlf ? Math.max(120, Math.min(250, readTimeoutMs)) : readTimeoutMs;
            socket.setSoTimeout(sliceMs);
            if (enviarCrlf) {
                socket.getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            byte[] buf = new byte[enviarCrlf ? 2048 : 512];
            int total = 0;
            int processadoIac = 0;
            long deadline = System.currentTimeMillis() + readTimeoutMs;
            while (total < buf.length && System.currentTimeMillis() < deadline) {
                int lido;
                try {
                    lido = in.read(buf, total, buf.length - total);
                } catch (SocketTimeoutException e) {
                    if (total > 0) break;
                    continue;
                }
                if (lido <= 0) break;
                total += lido;

                if (enviarCrlf) {
                    processadoIac = responderNegociacaoIac(buf, processadoIac, total, out);
                    String limpoParcial = limparBytesTelnet(buf, total).toLowerCase().trim();
                    if (limpoParcial.endsWith("login:") || limpoParcial.endsWith("username:")
                            || limpoParcial.endsWith("username(1-64 chars):") || limpoParcial.endsWith("password:")) {
                        break;
                    }
                } else {
                    String atual = new String(buf, 0, total, StandardCharsets.UTF_8);
                    if (atual.contains("\n") || atual.contains("SSH-")) {
                        break;
                    }
                }
            }
            String textoLimpo = limparBytesTelnet(buf, total);
            if (enviarCrlf) {
                return textoLimpo.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
            }
            return textoLimpo.lines().findFirst().orElse("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static int responderNegociacaoIac(byte[] buf, int inicio, int total, OutputStream out) {
        int i = inicio;
        try {
            ByteArrayOutputStream resp = null;
            while (i + 2 < total) {
                if ((buf[i] & 0xFF) == 0xFF) {
                    int cmd = buf[i + 1] & 0xFF;
                    int opt = buf[i + 2] & 0xFF;
                    if (cmd == 0xFD || cmd == 0xFB) { // DO -> WONT (0xFC); WILL -> DONT (0xFE)
                        if (resp == null) resp = new ByteArrayOutputStream();
                        resp.write(0xFF);
                        resp.write(cmd == 0xFD ? 0xFC : 0xFE);
                        resp.write(opt);
                        i += 3;
                        continue;
                    } else if (cmd == 0xFE || cmd == 0xFC) {
                        i += 3;
                        continue;
                    }
                }
                i++;
            }
            if (resp != null && resp.size() > 0) {
                out.write(resp.toByteArray());
                out.flush();
            }
        } catch (Exception ignored) {
        }
        return i;
    }

    private static String limparBytesTelnet(byte[] buf, int total) {
        StringBuilder limpo = new StringBuilder(total);
        for (int i = 0; i < total; i++) {
            int b = buf[i] & 0xFF;
            if (b == 0xFF) {
                if (i + 1 < total) {
                    int cmd = buf[i + 1] & 0xFF;
                    if (cmd == 0xFA) {
                        int j = i + 2;
                        while (j + 1 < total && !((buf[j] & 0xFF) == 0xFF && (buf[j + 1] & 0xFF) == 0xF0)) {
                            j++;
                        }
                        i = (j + 1 < total) ? j + 1 : total - 1;
                    } else if (cmd >= 251 && cmd <= 254 && i + 2 < total) {
                        i += 2;
                    } else {
                        i += 1;
                    }
                }
                continue;
            }
            if ((b >= 32 && b < 127) || b == '\n' || b == '\r' || b == '\u001B') {
                limpo.append((char) b);
            }
        }
        return ANSI_CSI_PATTERN.matcher(limpo.toString()).replaceAll("");
    }

    private static String lerHttpSnippet(String ip, int porta, boolean ssl, int connectTimeoutMs, int readTimeoutMs) {
        StringBuilder acumulado = new StringBuilder();
        String currentPath = "/";
        for (int hop = 0; hop <= 3; hop++) {
            String resp = lerHttpPath(ip, porta, currentPath, ssl, connectTimeoutMs, readTimeoutMs);
            if (resp.isBlank()) {
                break;
            }
            if (!acumulado.isEmpty()) {
                acumulado.append('\n');
            }
            acumulado.append(resp);
            String lower = resp.toLowerCase();
            if (!(lower.startsWith("http/1.0 30") || lower.startsWith("http/1.1 30"))) {
                break;
            }
            Matcher mLoc = LOCATION_HEADER_PATTERN.matcher(resp);
            if (!mLoc.find()) {
                break;
            }
            String nextPath = extrairCaminhoRedirectMesmoHost(ip, mLoc.group(1));
            if (nextPath == null || nextPath.equals(currentPath)) {
                break;
            }
            currentPath = nextPath;
        }
        return acumulado.toString();
    }

    private static String extrairCaminhoRedirectMesmoHost(String ipEsperado, String location) {
        if (location == null || location.isBlank()) return null;
        String loc = location.trim();
        if (loc.startsWith("/")) {
            return loc;
        }
        try {
            java.net.URI uri = java.net.URI.create(loc);
            if (uri.getHost() != null && !uri.getHost().equalsIgnoreCase(ipEsperado)) {
                return null;
            }
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) path = "/";
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            return path;
        } catch (Exception e) {
            return null;
        }
    }

    private static String lerHttpPath(String ip, int porta, String path, boolean ssl, int connectTimeoutMs, int readTimeoutMs) {
        try (Socket socket = ssl ? criarSslSocketTrustAll(ip, porta, connectTimeoutMs) : criarTcpSocket(ip, porta, connectTimeoutMs)) {
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = socket.getOutputStream();
            String req = "GET " + path + " HTTP/1.0\r\nHost: " + ip + "\r\nUser-Agent: tpDiscover/1.0\r\nConnection: close\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] buf = new byte[1024];
            ByteArrayOutputStream baos = new ByteArrayOutputStream(MAX_HTTP_BYTES);
            int lido;
            long deadline = System.currentTimeMillis() + readTimeoutMs;
            while (baos.size() < MAX_HTTP_BYTES && System.currentTimeMillis() < deadline) {
                try {
                    lido = in.read(buf);
                } catch (Exception timeoutOuFim) {
                    break;
                }
                if (lido <= 0) break;
                int aproveitar = Math.min(lido, MAX_HTTP_BYTES - baos.size());
                baos.write(buf, 0, aproveitar);
                String parcial = baos.toString(StandardCharsets.ISO_8859_1);
                if (parcial.toLowerCase().contains("</title>")) {
                    break;
                }
            }
            return baos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static Socket criarTcpSocket(String ip, int porta, int timeoutMs) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, porta), timeoutMs);
        return s;
    }

    private static Socket criarSslSocketTrustAll(String ip, int porta, int timeoutMs) throws Exception {
        TrustManager[] trustAll = {
                new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    @Override public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        Socket base = criarTcpSocket(ip, porta, timeoutMs);
        SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(base, ip, porta, true);
        ssl.setSoTimeout(timeoutMs);
        ssl.startHandshake();
        return ssl;
    }

    /**
     * Consulta SNMPv2c UDP/161 em Java puro para {@code sysDescr} (1.3.6.1.2.1.1.1.0) e {@code sysName} (1.3.6.1.2.1.1.5.0).
     */
    public static SnmpSystemInfo consultarSnmpBasico(String ip, int timeoutMs, List<String> comunidades) {
        List<String> comms = (comunidades != null && !comunidades.isEmpty()) ? comunidades : List.of("public");
        for (String comm : comms) {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setSoTimeout(timeoutMs);
                byte[] req = montarSnmpV2GetRequest(comm, 1, OID_SYS_DESCR, OID_SYS_NAME);
                InetAddress addr = InetAddress.getByName(ip);
                ds.send(new DatagramPacket(req, req.length, addr, 161));
                byte[] respBuf = new byte[2048];
                DatagramPacket respPacket = new DatagramPacket(respBuf, respBuf.length);
                ds.receive(respPacket);
                Map<String, String> valores = decodificarSnmpResponse(respPacket.getData(), respPacket.getLength());
                String sysDescr = valores.getOrDefault("1.3.6.1.2.1.1.1.0", "");
                String sysName = valores.getOrDefault("1.3.6.1.2.1.1.5.0", "");
                if (!sysDescr.isBlank() || !sysName.isBlank()) {
                    return new SnmpSystemInfo(sysDescr, sysName);
                }
            } catch (Exception ignored) {
            }
        }
        return new SnmpSystemInfo("", "");
    }

    // ---- Codificação / Decodificação ASN.1 BER mínima para SNMPv2c ----

    static byte[] montarSnmpV2GetRequest(String community, int requestId, int[]... oids) {
        ByteArrayOutputStream varBinds = new ByteArrayOutputStream();
        for (int[] oid : oids) {
            byte[] encodedOid = tlv(0x06, encodeOid(oid));
            byte[] nullVal = new byte[]{0x05, 0x00};
            byte[] varBind = tlv(0x30, concat(encodedOid, nullVal));
            varBinds.writeBytes(varBind);
        }
        byte[] varBindList = tlv(0x30, varBinds.toByteArray());
        byte[] reqId = tlv(0x02, encodeInt(requestId));
        byte[] errStat = tlv(0x02, new byte[]{0x00});
        byte[] errIdx = tlv(0x02, new byte[]{0x00});
        byte[] pdu = tlv(0xA0, concat(reqId, errStat, errIdx, varBindList));

        byte[] version = tlv(0x02, new byte[]{0x01}); // SNMPv2c = 1
        byte[] comm = tlv(0x04, community.getBytes(StandardCharsets.UTF_8));
        return tlv(0x30, concat(version, comm, pdu));
    }

    static Map<String, String> decodificarSnmpResponse(byte[] data, int length) {
        Map<String, String> result = new LinkedHashMap<>();
        if (data == null || length < 10) return result;
        int[] cursor = {0};
        if ((data[cursor[0]++] & 0xFF) != 0x30) return result;
        int msgLen = lerComprimentoBer(data, cursor, length);
        int msgEnd = Math.min(length, cursor[0] + msgLen);

        // Pula version e community
        pularTlv(data, cursor, msgEnd);
        pularTlv(data, cursor, msgEnd);

        if (cursor[0] >= msgEnd || (data[cursor[0]++] & 0xFF) != 0xA2) return result;
        int pduLen = lerComprimentoBer(data, cursor, msgEnd);
        int pduEnd = Math.min(msgEnd, cursor[0] + pduLen);

        // Pula requestId, errorStatus, errorIndex
        pularTlv(data, cursor, pduEnd);
        pularTlv(data, cursor, pduEnd);
        pularTlv(data, cursor, pduEnd);

        if (cursor[0] >= pduEnd || (data[cursor[0]++] & 0xFF) != 0x30) return result;
        int vblLen = lerComprimentoBer(data, cursor, pduEnd);
        int vblEnd = Math.min(pduEnd, cursor[0] + vblLen);

        while (cursor[0] < vblEnd) {
            if ((data[cursor[0]++] & 0xFF) != 0x30) break;
            int vbLen = lerComprimentoBer(data, cursor, vblEnd);
            int vbEnd = Math.min(vblEnd, cursor[0] + vbLen);
            if (cursor[0] >= vbEnd || (data[cursor[0]++] & 0xFF) != 0x06) {
                cursor[0] = vbEnd;
                continue;
            }
            int oidLen = lerComprimentoBer(data, cursor, vbEnd);
            String oid = decodificarOid(data, cursor[0], oidLen);
            cursor[0] += oidLen;

            if (cursor[0] < vbEnd) {
                int valTag = data[cursor[0]++] & 0xFF;
                int valLen = lerComprimentoBer(data, cursor, vbEnd);
                if (valTag == 0x04 && valLen > 0 && cursor[0] + valLen <= vbEnd) {
                    String s = new String(data, cursor[0], valLen, StandardCharsets.UTF_8)
                            .replace('\0', ' ')
                            .replaceAll("[\\r\\n]+", " ")
                            .trim();
                    result.put(oid, s);
                }
                cursor[0] += Math.max(0, valLen);
            }
            cursor[0] = vbEnd;
        }
        return result;
    }

    private static void pularTlv(byte[] data, int[] cursor, int limit) {
        if (cursor[0] >= limit) return;
        cursor[0]++; // tag
        int len = lerComprimentoBer(data, cursor, limit);
        cursor[0] = Math.min(limit, cursor[0] + Math.max(0, len));
    }

    private static int lerComprimentoBer(byte[] data, int[] cursor, int limit) {
        if (cursor[0] >= limit) return 0;
        int b = data[cursor[0]++] & 0xFF;
        if ((b & 0x80) == 0) return b;
        int numBytes = b & 0x7F;
        if (numBytes == 0 || numBytes > 4 || cursor[0] + numBytes > limit) return 0;
        int len = 0;
        for (int i = 0; i < numBytes; i++) {
            len = (len << 8) | (data[cursor[0]++] & 0xFF);
        }
        return len;
    }

    private static String decodificarOid(byte[] data, int offset, int len) {
        if (len <= 0 || offset + len > data.length) return "";
        StringBuilder sb = new StringBuilder();
        int first = data[offset] & 0xFF;
        sb.append(first / 40).append('.').append(first % 40);
        int i = 1;
        while (i < len) {
            long val = 0;
            int b;
            do {
                b = data[offset + i++] & 0xFF;
                val = (val << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0 && i < len);
            sb.append('.').append(val);
        }
        return sb.toString();
    }

    private static byte[] encodeOid(int[] oid) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(oid[0] * 40 + oid[1]);
        for (int i = 2; i < oid.length; i++) {
            int val = oid[i];
            if (val < 128) {
                out.write(val);
            } else {
                List<Integer> bytes = new ArrayList<>();
                bytes.add(val & 0x7F);
                val >>>= 7;
                while (val > 0) {
                    bytes.add((val & 0x7F) | 0x80);
                    val >>>= 7;
                }
                for (int j = bytes.size() - 1; j >= 0; j--) {
                    out.write(bytes.get(j));
                }
            }
        }
        return out.toByteArray();
    }

    private static byte[] encodeInt(int value) {
        if (value >= 0 && value < 128) {
            return new byte[]{(byte) value};
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int shift = 24; shift >= 0; shift -= 8) {
            int b = (value >>> shift) & 0xFF;
            if (b != 0 || out.size() > 0 || shift == 0) {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        if (content.length < 128) {
            out.write(content.length);
        } else {
            byte[] lenBytes = encodeInt(content.length);
            out.write(0x80 | lenBytes.length);
            out.writeBytes(lenBytes);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}

package tpDiscover.intelbras.correlacao;

import tpDiscover.intelbras.correlacao.MapaTopologia.Alerta;
import tpDiscover.intelbras.correlacao.MapaTopologia.Enlace;
import tpDiscover.intelbras.correlacao.MapaTopologia.No;
import tpDiscover.intelbras.correlacao.MapaTopologia.StatusEnlace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gera um arquivo de projeto (.json) que o Planejador de Rede abre em "Abrir", para ajustar a
 * topologia descoberta (mover equipamentos, corrigir cabos, anotar VLANs etc.).
 *
 * <p>Formato do Planejador: {@code {name, devices[], links[], notes[], zones[], seq}}, com
 * equipamentos {@code {id, type, name, x, y, ip, model, location, notes, portData}} e cabos
 * {@code {id, a:{dev,port}, b:{dev,port}, cable, length, label}}. As portas seguem a numeração do
 * Planejador ("24", "SFP1", "LAN1", "GE1", "PON1", "ETH"); o nome real da porta (ex.: GE1/0/24)
 * fica na descrição da porta, visível ao abrir o equipamento.</p>
 */
public final class ExportadorProjetoPlanejador {

    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Pattern NUMERO_FINAL = Pattern.compile("(\\d+)\\D*$");
    private static final Pattern ID_VALIDO = Pattern.compile("[\\w-]{1,40}");

    private ExportadorProjetoPlanejador() {
    }

    /** Salva o projeto do Planejador em {@code destino} (UTF-8). */
    public static Path salvar(MapaTopologia mapa, String nomeProjeto, Path destino) throws IOException {
        Files.write(destino, gerarJson(mapa, nomeProjeto).getBytes(StandardCharsets.UTF_8));
        return destino;
    }

    public static String gerarJson(MapaTopologia mapa, String nomeProjeto) {
        MapaTopologia m = mapa != null ? mapa : MapaTopologia.vazio();
        Map<String, String> idPorNo = new HashMap<>();
        Map<String, Portas> portasPorNo = new LinkedHashMap<>();
        Set<String> idsUsados = new LinkedHashSet<>();
        for (No n : m.nos()) {
            String id = idSeguro(n.id(), idsUsados);
            idPorNo.put(n.id(), id);
            portasPorNo.put(n.id(), new Portas(tipoPlanejador(n.tipo())));
        }

        // Converte primeiro os cabos: assim cada equipamento sabe quantas portas precisa ter.
        // 1ª passada: cada porta real recebe seu número. 2ª passada: um segundo cabo na MESMA porta
        // (ex.: switch não gerenciado no meio) vai para uma porta livre, marcada como compartilhada,
        // porque no Planejador cada porta aceita um único cabo.
        List<Enlace> enlaces = m.enlaces();
        String[][] portasCabo = new String[enlaces.size()][2];
        for (int i = 0; i < enlaces.size(); i++) {
            Enlace e = enlaces.get(i);
            Portas pa = portasPorNo.get(e.idA());
            Portas pb = portasPorNo.get(e.idB());
            if (pa == null || pb == null) continue;
            if (!pa.jaMapeada(e.portaA())) portasCabo[i][0] = pa.mapear(e.portaA(), e.fibra());
            if (!pb.jaMapeada(e.portaB())) portasCabo[i][1] = pb.mapear(e.portaB(), e.fibra());
        }
        for (int i = 0; i < enlaces.size(); i++) {
            Enlace e = enlaces.get(i);
            Portas pa = portasPorNo.get(e.idA());
            Portas pb = portasPorNo.get(e.idB());
            if (pa == null || pb == null) continue;
            if (portasCabo[i][0] == null) portasCabo[i][0] = pa.mapearCompartilhada(e.portaA(), e.fibra());
            if (portasCabo[i][1] == null) portasCabo[i][1] = pb.mapearCompartilhada(e.portaB(), e.fibra());
        }

        StringBuilder links = new StringBuilder();
        int seq = 1;
        for (int i = 0; i < enlaces.size(); i++) {
            Enlace e = enlaces.get(i);
            if (portasCabo[i][0] == null || portasCabo[i][1] == null) continue;
            String portaA = portasCabo[i][0];
            String portaB = portasCabo[i][1];
            if (links.length() > 0) links.append(",\n");
            links.append("    {")
                    .append(par("id", "c" + seq)).append(", ")
                    .append("\"a\": {").append(par("dev", idPorNo.get(e.idA()))).append(", ").append(par("port", portaA)).append("}, ")
                    .append("\"b\": {").append(par("dev", idPorNo.get(e.idB()))).append(", ").append(par("port", portaB)).append("}, ")
                    .append(par("cable", e.fibra() ? "fibra" : "cat6")).append(", ")
                    .append("\"length\": null, ")
                    .append(par("label", String.format(Locale.ROOT, "C-%03d", seq) + sufixoStatus(e.status())))
                    .append("}");
            seq++;
        }

        StringBuilder devices = new StringBuilder();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        for (No n : m.nos()) {
            minX = Math.min(minX, n.x());
            minY = Math.min(minY, n.y());
            Portas p = portasPorNo.get(n.id());
            if (devices.length() > 0) devices.append(",\n");
            devices.append("    {")
                    .append(par("id", idPorNo.get(n.id()))).append(", ")
                    .append(par("type", p.tipo)).append(", ")
                    .append(par("name", nomeEquipamento(n))).append(", ")
                    .append("\"x\": ").append(numero(n.x())).append(", ")
                    .append("\"y\": ").append(numero(n.y())).append(", ")
                    .append(par("ip", ipLimpo(n))).append(", ")
                    .append(par("model", modelo(n))).append(", ")
                    .append(par("location", "")).append(", ")
                    .append(par("notes", anotacoes(n))).append(", ");
            if (n.coletado()) {
                devices.append(par("vendor", "Intelbras")).append(", ");
            }
            p.escreverContagens(devices);
            devices.append("\"portData\": {");
            boolean primeira = true;
            for (Map.Entry<String, String> pd : p.descricoes.entrySet()) {
                if (!primeira) devices.append(", ");
                primeira = false;
                devices.append(str(pd.getKey())).append(": {").append(par("desc", pd.getValue())).append("}");
            }
            devices.append("}}");
        }

        String nome = nomeProjeto == null || nomeProjeto.trim().isEmpty()
                ? "Topologia " + LocalDateTime.now().format(DATA_BR)
                : nomeProjeto.trim();
        String nota = "Gerado pelo tpDiscover em " + LocalDateTime.now().format(DATA_BR) + ".\n"
                + m.nos().size() + " equipamento(s), " + m.enlaces().size() + " cabo(s).\n"
                + "Portas: o nome real (ex.: GE1/0/24) está na descrição de cada porta.";
        double notaX = m.nos().isEmpty() ? 0 : minX - 230;
        double notaY = m.nos().isEmpty() ? 0 : minY;

        return "{\n"
                + "  " + par("name", nome) + ",\n"
                + "  \"devices\": [\n" + devices + (devices.length() > 0 ? "\n" : "") + "  ],\n"
                + "  \"links\": [\n" + links + (links.length() > 0 ? "\n" : "") + "  ],\n"
                + "  \"notes\": [\n    {" + par("id", "nota-tpdiscover") + ", \"x\": " + numero(notaX) + ", \"y\": " + numero(notaY)
                + ", \"w\": 200, \"h\": 110, " + par("text", nota) + ", " + par("color", "#bfdbfe") + "}\n  ],\n"
                + "  \"zones\": [],\n"
                + "  \"seq\": {}\n"
                + "}\n";
    }

    // ------------------------------------------------------------ Portas

    /** Numeração de portas de um equipamento no padrão do Planejador. */
    private static final class Portas {
        final String tipo;
        /** id da porta no Planejador -> nome real coletado. */
        final Map<String, String> descricoes = new LinkedHashMap<>();
        private final Map<String, String> idPorNomeReal = new HashMap<>();
        private final Set<String> idsUsados = new LinkedHashSet<>();
        private int maxCobre;
        private int maxSfp;
        private int maxPon;
        private int maxLan;
        private int usosEth;

        Portas(String tipo) {
            this.tipo = tipo;
        }

        private static String normalizar(String nomeReal) {
            String real = nomeReal == null ? "" : nomeReal.trim();
            return "?".equals(real) || "-".equals(real) ? "" : real;
        }

        /** true se esta porta real já recebeu um cabo (um segundo cabo nela é "compartilhado"). */
        boolean jaMapeada(String nomeReal) {
            String real = normalizar(nomeReal);
            return !real.isEmpty() && idPorNomeReal.containsKey(real.toUpperCase(Locale.ROOT));
        }

        /** Segundo cabo na mesma porta real: usa uma porta livre e registra o nome real na descrição. */
        String mapearCompartilhada(String nomeReal, boolean caboFibra) {
            String real = normalizar(nomeReal);
            String id = escolherId("", caboFibra);
            descricoes.put(id, real + " (cabo compartilhado: provável switch não gerenciado)");
            idsUsados.add(id);
            return id;
        }

        String mapear(String nomeReal, boolean caboFibra) {
            String real = nomeReal == null ? "" : nomeReal.trim();
            if ("?".equals(real) || "-".equals(real)) real = "";
            String chave = real.toUpperCase(Locale.ROOT);
            if (!real.isEmpty() && idPorNomeReal.containsKey(chave)) {
                return idPorNomeReal.get(chave);
            }
            String id = escolherId(real, caboFibra);
            if (!real.isEmpty() && !real.equalsIgnoreCase(id)) {
                descricoes.put(id, real);
            }
            if (!real.isEmpty()) idPorNomeReal.put(chave, id);
            idsUsados.add(id);
            return id;
        }

        private String escolherId(String real, boolean caboFibra) {
            String up = real.toUpperCase(Locale.ROOT);
            Integer num = numeroFinal(real);
            switch (tipo) {
                case "router": {
                    Matcher m = Pattern.compile("^(WAN|LAN)(\\d)$").matcher(up);
                    if (m.matches() && livre(up)) {
                        if (up.startsWith("LAN")) maxLan = Math.max(maxLan, Integer.parseInt(m.group(2)));
                        return up;
                    }
                    return proximo("LAN", ++maxLan);
                }
                case "olt": {
                    if (up.contains("PON")) return proximoLivre("PON", num);
                    if (caboFibra || ehOptica(up)) return proximoLivre("SFP", null);
                    return proximoLivre("GE", null);
                }
                case "ap":
                case "radio":
                    usosEth++;
                    return usosEth == 1 ? "ETH" : "ETH" + usosEth;
                default: { // switch
                    if (caboFibra || ehOptica(up)) return proximoLivre("SFP", null);
                    if (num != null && num > 0 && num <= 96 && livre(String.valueOf(num))) {
                        maxCobre = Math.max(maxCobre, num);
                        return String.valueOf(num);
                    }
                    return proximoLivre("", null);
                }
            }
        }

        private String proximoLivre(String prefixo, Integer preferido) {
            if (preferido != null && preferido > 0 && livre(prefixo + preferido)) {
                registrarMax(prefixo, preferido);
                return prefixo + preferido;
            }
            int n = 1;
            while (!livre(prefixo + n)) n++;
            registrarMax(prefixo, n);
            return prefixo + n;
        }

        private String proximo(String prefixo, int n) {
            int i = n;
            while (!livre(prefixo + i)) i++;
            return prefixo + i;
        }

        private void registrarMax(String prefixo, int n) {
            switch (prefixo) {
                case "SFP": maxSfp = Math.max(maxSfp, n); break;
                case "PON": maxPon = Math.max(maxPon, n); break;
                case "": maxCobre = Math.max(maxCobre, n); break;
                case "GE": maxCobre = Math.max(maxCobre, n); break;
                default: break;
            }
        }

        private boolean livre(String id) {
            return !idsUsados.contains(id);
        }

        /** Quantidade de portas no padrão do Planejador, ampliada para caber todas as portas usadas. */
        void escreverContagens(StringBuilder sb) {
            switch (tipo) {
                case "switch":
                    sb.append("\"portCount\": ").append(limitar(Math.max(24, maxCobre))).append(", ")
                            .append("\"sfpCount\": ").append(limitar(Math.max(2, maxSfp))).append(", ");
                    break;
                case "olt":
                    sb.append(par("tech", "GPON")).append(", ")
                            .append("\"ponCount\": ").append(limitar(Math.max(8, maxPon))).append(", ")
                            .append("\"portCount\": ").append(limitar(Math.max(4, maxCobre))).append(", ")
                            .append("\"sfpCount\": ").append(limitar(Math.max(2, maxSfp))).append(", ");
                    break;
                case "ap":
                    if (usosEth > 1) sb.append("\"portCount\": ").append(limitar(usosEth)).append(", ");
                    break;
                case "radio":
                    sb.append(par("radioMode", "ptp")).append(", ");
                    break;
                default:
                    break;
            }
        }

        private static int limitar(int v) {
            return Math.max(0, Math.min(96, v));
        }
    }

    private static boolean ehOptica(String portaMaiuscula) {
        return portaMaiuscula.contains("SFP") || portaMaiuscula.contains("XGE") || portaMaiuscula.contains("TEN-")
                || portaMaiuscula.contains("FO") || portaMaiuscula.contains("OPT");
    }

    private static Integer numeroFinal(String porta) {
        Matcher m = NUMERO_FINAL.matcher(porta);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------- Equipamentos

    /** Tipos do tpDiscover para os tipos do Planejador (o trecho sem acesso vira um switch anotado). */
    static String tipoPlanejador(String tipo) {
        if (tipo == null) return "switch";
        switch (tipo) {
            case "router":
            case "olt":
            case "ap":
            case "radio":
                return tipo;
            default:
                return "switch";
        }
    }

    private static String nomeEquipamento(No n) {
        String nome = texto(n.nome());
        if (nome.isEmpty()) nome = texto(n.modelo());
        if (nome.isEmpty()) nome = "Equipamento";
        return nome.length() > 60 ? nome.substring(0, 60) : nome;
    }

    /** Modelo curto: para o gateway, só o fabricante (ex.: "Fortinet"); trecho sem acesso fica sem modelo. */
    private static String modelo(No n) {
        String m = texto(n.modelo());
        if ("segmento".equals(n.tipo())) return "Desconhecido";
        if ("router".equals(n.tipo())) {
            Matcher fab = Pattern.compile("\\(([^()]+)\\)\\]?\\s*$").matcher(m);
            if (fab.find()) return fab.group(1).trim();
            if (m.startsWith("Roteador/Gateway")) return "";
        }
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private static String ipLimpo(No n) {
        String ip = texto(n.ip());
        return ip.matches("[0-9.:]+") ? ip : "";
    }

    private static String anotacoes(No n) {
        List<String> linhas = new ArrayList<>();
        if ("segmento".equals(n.tipo())) {
            linhas.add("Trecho sem acesso CLI: os switches deste ponto não foram coletados.");
            if (!texto(n.modelo()).isEmpty()) linhas.add("Suspeitos: " + texto(n.modelo()));
        }
        if (!texto(n.mac()).isEmpty() && !"N/D".equalsIgnoreCase(texto(n.mac()))) linhas.add("MAC: " + texto(n.mac()));
        String serial = texto(n.serial());
        if (!serial.isEmpty() && !"N/D".equalsIgnoreCase(serial) && !"Passivo".equalsIgnoreCase(serial)) {
            linhas.add("Serial: " + serial);
        }
        if (!ipLimpo(n).equals(texto(n.ip())) && !texto(n.ip()).isEmpty()) linhas.add(texto(n.ip()));
        switch (n.alerta()) {
            case LOOP: linhas.add("ALERTA: possível loop detectado na coleta."); break;
            case STP: linhas.add("Aviso: porta bloqueada pelo STP."); break;
            case AUTH_ERRO: linhas.add("Sem acesso: senha incorreta na coleta."); break;
            case SEM_ACESSO: linhas.add("Sem acesso CLI: identificado pelos vizinhos."); break;
            default: break;
        }
        if (n.coletado() && n.alerta() == Alerta.NENHUM) linhas.add("Coletado via CLI pelo tpDiscover.");
        return String.join("\n", linhas);
    }

    private static String sufixoStatus(StatusEnlace s) {
        switch (s) {
            case LOOP: return " (LOOP)";
            case STP_BLOQUEADO: return " (STP bloqueado)";
            case INCERTO: return " (incerto)";
            default: return "";
        }
    }

    private static String idSeguro(String original, Set<String> usados) {
        String id = original == null ? "" : original.replaceAll("[^\\w-]", "-");
        if (id.length() > 40) id = id.substring(id.length() - 40);
        if (!ID_VALIDO.matcher(id).matches()) id = "dev";
        String base = id;
        int i = 2;
        while (!usados.add(id)) {
            String suf = "-" + i++;
            id = (base.length() + suf.length() > 40 ? base.substring(0, 40 - suf.length()) : base) + suf;
        }
        return id;
    }

    // ---------------------------------------------------------------- JSON

    private static String texto(String s) {
        return s == null ? "" : s.trim();
    }

    private static String numero(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String par(String chave, String valor) {
        return str(chave) + ": " + str(valor);
    }

    static String str(String s) {
        String v = s == null ? "" : s;
        StringBuilder sb = new StringBuilder(v.length() + 2).append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}

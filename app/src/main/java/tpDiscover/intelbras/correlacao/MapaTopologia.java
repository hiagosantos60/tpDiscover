package tpDiscover.intelbras.correlacao;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Modelo visual da topologia (equipamentos, cabos e posições), o mesmo usado no diagrama do PDF.
 * A aba Topologia da interface desenha este modelo para consulta rápida no atendimento.
 *
 * <p>Coordenadas em "unidades de diagrama": {@link No#x()}/{@link No#y()} indicam o canto
 * superior esquerdo do ícone de {@link #LARGURA_ICONE} x {@link #ALTURA_ICONE}.</p>
 */
public final class MapaTopologia {

    public static final double LARGURA_ICONE = 64.0;
    public static final double ALTURA_ICONE = 48.0;

    /** Alerta exibido sobre o ícone do equipamento. */
    public enum Alerta { NENHUM, LOOP, STP, AUTH_ERRO, SEM_ACESSO }

    /** Situação do cabo entre dois equipamentos. */
    public enum StatusEnlace { NORMAL, INCERTO, STP_BLOQUEADO, LOOP }

    public static final class No {
        private final String id;
        private final String tipo;
        private final String nome;
        private final String modelo;
        private final String ip;
        private final String mac;
        private final String serial;
        private final boolean coletado;
        private final Alerta alerta;
        private final double x;
        private final double y;

        public No(String id, String tipo, String nome, String modelo, String ip, String mac, String serial,
                  boolean coletado, Alerta alerta, double x, double y) {
            this.id = id;
            this.tipo = tipo;
            this.nome = nome;
            this.modelo = modelo;
            this.ip = ip;
            this.mac = mac;
            this.serial = serial;
            this.coletado = coletado;
            this.alerta = alerta == null ? Alerta.NENHUM : alerta;
            this.x = x;
            this.y = y;
        }

        public String id() { return id; }

        /** router, switch, olt, ap, radio ou segmento. */
        public String tipo() { return tipo; }

        public String nome() { return nome; }

        public String modelo() { return modelo; }

        public String ip() { return ip; }

        public String mac() { return mac; }

        public String serial() { return serial; }

        /** true quando o equipamento foi acessado via CLI (dados completos). */
        public boolean coletado() { return coletado; }

        public Alerta alerta() { return alerta; }

        public double x() { return x; }

        public double y() { return y; }

        public double centroX() { return x + LARGURA_ICONE / 2.0; }

        public double centroY() { return y + ALTURA_ICONE / 2.0; }
    }

    public static final class Enlace {
        private final String idA;
        private final String portaA;
        private final String idB;
        private final String portaB;
        private final boolean fibra;
        private final StatusEnlace status;

        public Enlace(String idA, String portaA, String idB, String portaB, boolean fibra, StatusEnlace status) {
            this.idA = idA;
            this.portaA = portaA;
            this.idB = idB;
            this.portaB = portaB;
            this.fibra = fibra;
            this.status = status == null ? StatusEnlace.NORMAL : status;
        }

        public String idA() { return idA; }

        public String portaA() { return portaA; }

        public String idB() { return idB; }

        public String portaB() { return portaB; }

        public boolean fibra() { return fibra; }

        public StatusEnlace status() { return status; }
    }

    private final List<No> nos;
    private final List<Enlace> enlaces;

    public MapaTopologia(List<No> nos, List<Enlace> enlaces) {
        this.nos = Collections.unmodifiableList(new ArrayList<>(nos));
        this.enlaces = Collections.unmodifiableList(new ArrayList<>(enlaces));
    }

    public static MapaTopologia vazio() {
        return new MapaTopologia(Collections.<No>emptyList(), Collections.<Enlace>emptyList());
    }

    public List<No> nos() { return nos; }

    public List<Enlace> enlaces() { return enlaces; }

    public boolean estaVazio() { return nos.isEmpty(); }
}

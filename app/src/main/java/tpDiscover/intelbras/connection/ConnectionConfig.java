package tpDiscover.intelbras.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import java.util.stream.Collectors;
import tpDiscover.intelbras.util.Compat;
public final class ConnectionConfig {
    private final String ip;
    private final String usuario;
    private final String senha;
    private final int portaSsh;
    private final int portaTelnet;
    private final long timeoutMillis;
    private final int probeTimeoutMillis;
    private final boolean tentarTelnetAposFalhaAuth;
    private final boolean preferirTelnet;
    private final List<CredencialLogin> credenciaisAlternativas;

    public ConnectionConfig(String ip, String usuario, String senha, int portaSsh, int portaTelnet, long timeoutMillis, int probeTimeoutMillis, boolean tentarTelnetAposFalhaAuth, boolean preferirTelnet, List<CredencialLogin> credenciaisAlternativas) {
        credenciaisAlternativas = credenciaisAlternativas != null
                ? credenciaisAlternativas.stream().filter(Objects::nonNull).collect(Collectors.toList())
                : Compat.listOf();
        this.ip = ip;
        this.usuario = usuario;
        this.senha = senha;
        this.portaSsh = portaSsh;
        this.portaTelnet = portaTelnet;
        this.timeoutMillis = timeoutMillis;
        this.probeTimeoutMillis = probeTimeoutMillis;
        this.tentarTelnetAposFalhaAuth = tentarTelnetAposFalhaAuth;
        this.preferirTelnet = preferirTelnet;
        this.credenciaisAlternativas = credenciaisAlternativas;
    }

    public String ip() {
        return ip;
    }

    public String usuario() {
        return usuario;
    }

    public String senha() {
        return senha;
    }

    public int portaSsh() {
        return portaSsh;
    }

    public int portaTelnet() {
        return portaTelnet;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    public int probeTimeoutMillis() {
        return probeTimeoutMillis;
    }

    public boolean tentarTelnetAposFalhaAuth() {
        return tentarTelnetAposFalhaAuth;
    }

    public boolean preferirTelnet() {
        return preferirTelnet;
    }

    public List<CredencialLogin> credenciaisAlternativas() {
        return credenciaisAlternativas;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ConnectionConfig)) return false;
        ConnectionConfig o = (ConnectionConfig) obj;
        return java.util.Objects.equals(ip, o.ip)
                && java.util.Objects.equals(usuario, o.usuario)
                && java.util.Objects.equals(senha, o.senha)
                && portaSsh == o.portaSsh
                && portaTelnet == o.portaTelnet
                && timeoutMillis == o.timeoutMillis
                && probeTimeoutMillis == o.probeTimeoutMillis
                && tentarTelnetAposFalhaAuth == o.tentarTelnetAposFalhaAuth
                && preferirTelnet == o.preferirTelnet
                && java.util.Objects.equals(credenciaisAlternativas, o.credenciaisAlternativas);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(ip, usuario, senha, portaSsh, portaTelnet, timeoutMillis, probeTimeoutMillis, tentarTelnetAposFalhaAuth, preferirTelnet, credenciaisAlternativas);
    }

    @Override
    public String toString() {
        return "ConnectionConfig[ip=" + ip + ", usuario=" + usuario + ", senha=" + senha + ", portaSsh=" + portaSsh + ", portaTelnet=" + portaTelnet + ", timeoutMillis=" + timeoutMillis + ", probeTimeoutMillis=" + probeTimeoutMillis + ", tentarTelnetAposFalhaAuth=" + tentarTelnetAposFalhaAuth + ", preferirTelnet=" + preferirTelnet + ", credenciaisAlternativas=" + credenciaisAlternativas + "]";
    }

    /** Construtor retrocompatível com assinatura de 9 argumentos (sem credenciais alternativas). */
    public ConnectionConfig(
            String ip,
            String usuario,
            String senha,
            int portaSsh,
            int portaTelnet,
            long timeoutMillis,
            int probeTimeoutMillis,
            boolean tentarTelnetAposFalhaAuth,
            boolean preferirTelnet
    ) {
        this(ip, usuario, senha, portaSsh, portaTelnet, timeoutMillis, probeTimeoutMillis,
                tentarTelnetAposFalhaAuth, preferirTelnet, Compat.listOf());
    }

    /** Construtor retrocompatível com assinatura de 6 argumentos. */
    public ConnectionConfig(
            String ip,
            String usuario,
            String senha,
            int portaSsh,
            int portaTelnet,
            long timeoutMillis
    ) {
        this(ip, usuario, senha, portaSsh, portaTelnet, timeoutMillis, 1_200, false, false);
    }

    /** Construtor retrocompatível com assinatura de 8 argumentos. */
    public ConnectionConfig(
            String ip,
            String usuario,
            String senha,
            int portaSsh,
            int portaTelnet,
            long timeoutMillis,
            int probeTimeoutMillis,
            boolean tentarTelnetAposFalhaAuth
    ) {
        this(ip, usuario, senha, portaSsh, portaTelnet, timeoutMillis, probeTimeoutMillis, tentarTelnetAposFalhaAuth, false);
    }

    /** Valores padrão razoáveis: SSH 22, Telnet 23, timeout de 10s e probe TCP de 1.2s. */
    public static ConnectionConfig padrao(String ip, String usuario, String senha) {
        return new ConnectionConfig(ip, usuario, senha, 22, 23, 10_000, 1_200, false, false);
    }

    /** Usa porta customizada para SSH/Telnet quando informada (ex: ip:65019), ou padrão 22/23. */
    public static ConnectionConfig comPorta(String ip, String usuario, String senha, Integer portaCustomizada) {
        return comOpcoes(ip, usuario, senha, portaCustomizada, 10_000, false);
    }

    /** Cria configuração completa permitindo ajustar timeout (ex.: switches SG lentos) e preferência por Telnet. */
    public static ConnectionConfig comOpcoes(
            String ip,
            String usuario,
            String senha,
            Integer portaCustomizada,
            long timeoutMillis,
            boolean preferirTelnet
    ) {
        long timeoutEfetivo = Math.max(4_000, timeoutMillis);
        if (portaCustomizada != null) {
            return new ConnectionConfig(
                    ip, usuario, senha, portaCustomizada, portaCustomizada,
                    timeoutEfetivo, 1_200, false, preferirTelnet);
        }
        return new ConnectionConfig(
                ip, usuario, senha, 22, 23, timeoutEfetivo, 1_200, false, preferirTelnet);
    }

    /** Cópia com a lista de credenciais de fallback (tentadas em ordem depois da principal). */
    public ConnectionConfig comCredenciaisAlternativas(List<CredencialLogin> alternativas) {
        return new ConnectionConfig(ip, usuario, senha, portaSsh, portaTelnet, timeoutMillis,
                probeTimeoutMillis, tentarTelnetAposFalhaAuth, preferirTelnet, alternativas);
    }

    /** Cópia permitindo (ou não) tentar Telnet depois que o SSH recusar todas as credenciais. */
    public ConnectionConfig comTelnetAposFalhaAuth(boolean permitir) {
        return new ConnectionConfig(ip, usuario, senha, portaSsh, portaTelnet, timeoutMillis,
                probeTimeoutMillis, permitir, preferirTelnet, credenciaisAlternativas);
    }

    /** Credencial principal do alvo. */
    public CredencialLogin credencialPrincipal() {
        return new CredencialLogin(usuario, senha, "principal");
    }

    /** Cadeia ordenada e sem repetições: principal primeiro, depois as alternativas. */
    public List<CredencialLogin> cadeiaCredenciais() {
        List<CredencialLogin> todas = new ArrayList<>();
        todas.add(credencialPrincipal());
        todas.addAll(credenciaisAlternativas);
        List<CredencialLogin> unicas = CredencialLogin.semDuplicatas(todas);
        if (unicas.isEmpty()) {
            // Usuário em branco: mantém a principal para que a falha seja reportada normalmente
            unicas.add(credencialPrincipal());
        }
        return unicas;
    }
}

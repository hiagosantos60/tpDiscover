package tpDiscover.intelbras.modelos;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import tpDiscover.intelbras.util.Compat;
/**
 * Representa um vizinho aprendido via LLDP em uma porta local de um equipamento.
 */
public final class LldpNeighbor {
    private final String localSwitchId;
    private final String localPort;
    private final String chassisIdRaw;
    private final String chassisMacNormalizado;
    private final String remotePort;
    private final String remotePortDescription;
    private final String systemName;
    private final String systemDescription;
    private final String managementIp;
    private final Set<String> capabilities;

    public LldpNeighbor(String localSwitchId, String localPort, String chassisIdRaw, String chassisMacNormalizado, String remotePort, String remotePortDescription, String systemName, String systemDescription, String managementIp, Set<String> capabilities) {
        localSwitchId = localSwitchId != null ? localSwitchId : "";
        localPort = localPort != null ? localPort : "";
        chassisIdRaw = chassisIdRaw != null ? chassisIdRaw : "";
        chassisMacNormalizado = chassisMacNormalizado != null ? chassisMacNormalizado : "";
        remotePort = remotePort != null ? remotePort : "";
        remotePortDescription = remotePortDescription != null ? remotePortDescription : "";
        systemName = systemName != null ? systemName : "";
        systemDescription = systemDescription != null ? systemDescription : "";
        managementIp = managementIp != null ? managementIp : "";
        if (capabilities == null || capabilities.isEmpty()) {
            capabilities = Compat.setOf();
        } else {
            Set<String> capsLimpas = new LinkedHashSet<>();
            for (String cap : capabilities) {
                if (cap != null) {
                    capsLimpas.add(cap);
                }
            }
            capabilities = Collections.unmodifiableSet(capsLimpas);
        }
        this.localSwitchId = localSwitchId;
        this.localPort = localPort;
        this.chassisIdRaw = chassisIdRaw;
        this.chassisMacNormalizado = chassisMacNormalizado;
        this.remotePort = remotePort;
        this.remotePortDescription = remotePortDescription;
        this.systemName = systemName;
        this.systemDescription = systemDescription;
        this.managementIp = managementIp;
        this.capabilities = capabilities;
    }

    public String localSwitchId() {
        return localSwitchId;
    }

    public String localPort() {
        return localPort;
    }

    public String chassisIdRaw() {
        return chassisIdRaw;
    }

    public String chassisMacNormalizado() {
        return chassisMacNormalizado;
    }

    public String remotePort() {
        return remotePort;
    }

    public String remotePortDescription() {
        return remotePortDescription;
    }

    public String systemName() {
        return systemName;
    }

    public String systemDescription() {
        return systemDescription;
    }

    public String managementIp() {
        return managementIp;
    }

    public Set<String> capabilities() {
        return capabilities;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LldpNeighbor)) return false;
        LldpNeighbor o = (LldpNeighbor) obj;
        return java.util.Objects.equals(localSwitchId, o.localSwitchId)
                && java.util.Objects.equals(localPort, o.localPort)
                && java.util.Objects.equals(chassisIdRaw, o.chassisIdRaw)
                && java.util.Objects.equals(chassisMacNormalizado, o.chassisMacNormalizado)
                && java.util.Objects.equals(remotePort, o.remotePort)
                && java.util.Objects.equals(remotePortDescription, o.remotePortDescription)
                && java.util.Objects.equals(systemName, o.systemName)
                && java.util.Objects.equals(systemDescription, o.systemDescription)
                && java.util.Objects.equals(managementIp, o.managementIp)
                && java.util.Objects.equals(capabilities, o.capabilities);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(localSwitchId, localPort, chassisIdRaw, chassisMacNormalizado, remotePort, remotePortDescription, systemName, systemDescription, managementIp, capabilities);
    }

    @Override
    public String toString() {
        return "LldpNeighbor[localSwitchId=" + localSwitchId + ", localPort=" + localPort + ", chassisIdRaw=" + chassisIdRaw + ", chassisMacNormalizado=" + chassisMacNormalizado + ", remotePort=" + remotePort + ", remotePortDescription=" + remotePortDescription + ", systemName=" + systemName + ", systemDescription=" + systemDescription + ", managementIp=" + managementIp + ", capabilities=" + capabilities + "]";
    }

    public enum CategoriaInteresse {
        EQUIPAMENTO_COLETADO,
        ROTEADOR_UPLINK,
        SWITCH_OU_OLT_EXTERNO,
        ACCESS_POINT,
        RADIO_INTELBRAS,
        OUTRO_IGNORAR
    }

    public boolean possuiChassisMacValido() {
        return MacUtils.ehMacValido(chassisMacNormalizado);
    }

    public boolean possuiCapability(String termo) {
        if (capabilities == null || termo == null) {
            return false;
        }
        String t = termo.toLowerCase();
        for (String cap : capabilities) {
            if (cap != null && cap.toLowerCase().contains(t)) {
                return true;
            }
        }
        return false;
    }

    public String rotuloExibicao() {
        String nome = (systemName != null && !Compat.isBlank(systemName)) ? systemName.trim() : null;
        if (nome == null && systemDescription != null && !Compat.isBlank(systemDescription)) {
            nome = Compat.lines(systemDescription).findFirst().orElse("").trim();
        }
        if (nome == null || Compat.isBlank(nome)) {
            nome = (chassisIdRaw != null && !Compat.isBlank(chassisIdRaw)) ? chassisIdRaw.trim() : "Dispositivo LLDP";
        }
        if (managementIp != null && !Compat.isBlank(managementIp)) {
            return nome + " (" + managementIp.trim() + ")";
        }
        return nome;
    }
}

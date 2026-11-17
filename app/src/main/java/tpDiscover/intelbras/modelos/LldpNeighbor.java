package tpDiscover.intelbras.modelos;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Representa um vizinho aprendido via LLDP em uma porta local de um equipamento.
 */
public record LldpNeighbor(
        String localSwitchId,
        String localPort,
        String chassisIdRaw,
        String chassisMacNormalizado,
        String remotePort,
        String remotePortDescription,
        String systemName,
        String systemDescription,
        String managementIp,
        Set<String> capabilities
) {
    public LldpNeighbor {
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
            capabilities = Set.of();
        } else {
            Set<String> capsLimpas = new LinkedHashSet<>();
            for (String cap : capabilities) {
                if (cap != null) {
                    capsLimpas.add(cap);
                }
            }
            capabilities = Collections.unmodifiableSet(capsLimpas);
        }
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
        String nome = (systemName != null && !systemName.isBlank()) ? systemName.trim() : null;
        if (nome == null && systemDescription != null && !systemDescription.isBlank()) {
            nome = systemDescription.lines().findFirst().orElse("").trim();
        }
        if (nome == null || nome.isBlank()) {
            nome = (chassisIdRaw != null && !chassisIdRaw.isBlank()) ? chassisIdRaw.trim() : "Dispositivo LLDP";
        }
        if (managementIp != null && !managementIp.isBlank()) {
            return nome + " (" + managementIp.trim() + ")";
        }
        return nome;
    }
}

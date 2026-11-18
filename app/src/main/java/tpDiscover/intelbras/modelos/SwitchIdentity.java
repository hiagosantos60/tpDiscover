package tpDiscover.intelbras.modelos;

import tpDiscover.intelbras.util.Compat;

/**
 * Identificação do switch, extraída de "display device manuinfo".
 *
 * Exemplo de saída original:
 *   DEVICE_NAME          : S2328G-B
 *   DEVICE_SERIAL_NUMBER : 210150A2MK923BQ000F4
 *   MAC_ADDRESS          : 30E1-F1FF-661F
 *   PRODUCT_ID           : LS-2328G-B
 */
public final class SwitchIdentity {
    private final String deviceName;
    private final String serialNumber;
    private final String macAddress;
    private final String productId;
    private final String ip;

    public SwitchIdentity(String deviceName, String serialNumber, String macAddress, String productId, String ip) {
        this.deviceName = deviceName;
        this.serialNumber = serialNumber;
        this.macAddress = macAddress;
        this.productId = productId;
        this.ip = ip;
    }

    public String deviceName() {
        return deviceName;
    }

    public String serialNumber() {
        return serialNumber;
    }

    public String macAddress() {
        return macAddress;
    }

    public String productId() {
        return productId;
    }

    public String ip() {
        return ip;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SwitchIdentity)) return false;
        SwitchIdentity o = (SwitchIdentity) obj;
        return java.util.Objects.equals(deviceName, o.deviceName)
                && java.util.Objects.equals(serialNumber, o.serialNumber)
                && java.util.Objects.equals(macAddress, o.macAddress)
                && java.util.Objects.equals(productId, o.productId)
                && java.util.Objects.equals(ip, o.ip);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(deviceName, serialNumber, macAddress, productId, ip);
    }

    @Override
    public String toString() {
        return "SwitchIdentity[deviceName=" + deviceName + ", serialNumber=" + serialNumber + ", macAddress=" + macAddress + ", productId=" + productId + ", ip=" + ip + "]";
    }

    /** Indica se o equipamento possui um endereço MAC-base válido extraído da CLI. */
    public boolean possuiMacValido() {
        return MacUtils.ehMacValido(MacUtils.normalizar(macAddress));
    }

    /**
     * MAC-base normalizado (12 hex minúsculos) quando válido, ou identificador único sintético
     * baseado em IP/serial/nome quando o MAC não pôde ser obtido — evitando NullPointerException
     * e colisões entre múltiplos switches sem MAC.
     */
    public String macNormalizado() {
        String norm = MacUtils.normalizar(macAddress);
        if (MacUtils.ehMacValido(norm)) {
            return norm;
        }
        if (ip != null && !Compat.isBlank(ip)) {
            return "sem-mac@" + ip.trim().toLowerCase();
        }
        if (serialNumber != null && !Compat.isBlank(serialNumber)) {
            return "sem-mac@sn:" + serialNumber.trim().toLowerCase();
        }
        if (deviceName != null && !Compat.isBlank(deviceName)) {
            return "sem-mac@nome:" + deviceName.trim().toLowerCase();
        }
        return "sem-mac@desconhecido";
    }
}

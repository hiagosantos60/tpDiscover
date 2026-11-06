package tpDiscover.intelbras.modelos;

/**
 * Identificação do switch, extraída de "display device manuinfo".
 *
 * Exemplo de saída original:
 *   DEVICE_NAME          : S2328G-B
 *   DEVICE_SERIAL_NUMBER : 210150A2MK923BQ000F4
 *   MAC_ADDRESS          : 30E1-F1FF-661F
 *   PRODUCT_ID           : LS-2328G-B
 */
public record SwitchIdentity(
        String deviceName,     // ex: S2328G-B (nome/hostname configurado no equipamento)
        String serialNumber,   // ex: 210150A2MK923BQ000F4
        String macAddress,     // MAC-base do próprio switch (chave para correlação de topologia)
        String productId,      // ex: LS-2328G-B
        String ip              // IP usado para conectar (preenchido pelo coletor, não vem do "display")
) {
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
        if (ip != null && !ip.isBlank()) {
            return "sem-mac@" + ip.trim().toLowerCase();
        }
        if (serialNumber != null && !serialNumber.isBlank()) {
            return "sem-mac@sn:" + serialNumber.trim().toLowerCase();
        }
        if (deviceName != null && !deviceName.isBlank()) {
            return "sem-mac@nome:" + deviceName.trim().toLowerCase();
        }
        return "sem-mac@desconhecido";
    }
}

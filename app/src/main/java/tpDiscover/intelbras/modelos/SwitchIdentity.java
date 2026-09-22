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
    /** MAC-base normalizado, no mesmo formato usado em MacEntry.macNormalizado(). */
    public String macNormalizado() {
        return macAddress.toLowerCase().replace("-", "").replace(":", "").replace(".", "");
    }
}

package tpDiscover.intelbras.modelos;

/**
 * Representa uma linha da tabela MAC de um switch (saída de "display mac-address").
 *
 * Exemplo de linha original:
 *   0009-0f09-1e0a   1          Learned          GE1/0/23                 Y
 */
public record MacEntry(
        String switchId,   // identificador do switch de origem (ex: MAC_ADDRESS do "display device manuinfo")
        String mac,        // MAC normalizado, ex: "0009-0f09-1e0a" (mantemos o formato Comware)
        int vlanId,        // VLAN ID onde o MAC foi aprendido
        String state,      // Learned / Config / Blackhole etc.
        String port        // ex: "GE1/0/23"
) {
    /**
     * Normaliza o MAC para um formato único (minúsculo, sem separadores),
     * útil como chave de comparação entre switches diferentes.
     */
    public String macNormalizado() {
        return mac.toLowerCase().replace("-", "").replace(":", "").replace(".", "");
    }
}

package tpDiscover.intelbras.modelos;

/**
 * Switches Comware e Série 3000 costumam ter um MAC diferente POR PORTA FÍSICA / VLAN
 * (um pequeno offset a partir do MAC-base do sistema). Por isso, para saber se um MAC
 * visto na tabela MAC pertence a um switch conhecido, comparamos a distância numérica
 * de 48 bits em relação ao MAC-base do equipamento (suportando inclusive carry no 5º octeto
 * e evitando falsos positivos com outros equipamentos do mesmo lote).
 *
 * Exemplo real observado nos dados de teste:
 *   MAC-base do switch .10 (display device manuinfo): 80-85-44-07-F6-34
 *   Visto na tabela MAC de outro switch:               80-85-44-07-F6-3F (+11) / 80-85-44-07-F6-4E (+26)
 */
public final class MacUtils {

    /** Quantos bytes usamos como "prefixo de identidade" de um equipamento (compatibilidade). */
    public static final int OCTETOS_PREFIXO_PADRAO = 5;

    /**
     * Limites de offset entre o MAC-base do switch (display device manuinfo / show interface vlan 1)
     * e os MACs das suas portas físicas / interfaces VLAN.
     */
    public static final long OFFSET_MINIMO_PORTA = -8L;
    public static final long OFFSET_MAXIMO_PORTA = 64L;

    private MacUtils() {
    }

    /** Retorna os primeiros N bytes (2N caracteres hex) de um MAC já normalizado (12 chars, sem separador). */
    public static String prefixo(String macNormalizado, int bytes) {
        int chars = Math.min(bytes * 2, macNormalizado.length());
        return macNormalizado.substring(0, chars);
    }

    /** Atalho usando o prefixo padrão (5 bytes). */
    public static String prefixo(String macNormalizado) {
        return prefixo(macNormalizado, OCTETOS_PREFIXO_PADRAO);
    }

    /** Converte um MAC normalizado (12 caracteres hex) para inteiro de 48 bits, ou -1 se inválido. */
    public static long paraLong(String macNormalizado) {
        if (macNormalizado == null || macNormalizado.length() != 12) {
            return -1L;
        }
        try {
            return Long.parseUnsignedLong(macNormalizado, 16);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Verifica se um MAC observado na tabela MAC pertence ao bloco de portas/interfaces de um switch.
     * Compara o mesmo OUI (3 primeiros octetos) e valida se a diferença numérica de 48 bits está
     * dentro da janela de portas do equipamento.
     */
    public static boolean pertenceAoSwitch(String macBaseNormalizado, String macObservadoNormalizado) {
        if (macBaseNormalizado == null || macObservadoNormalizado == null) {
            return false;
        }
        if (macBaseNormalizado.equalsIgnoreCase(macObservadoNormalizado)) {
            return true;
        }
        if (macBaseNormalizado.length() != 12 || macObservadoNormalizado.length() != 12) {
            return prefixo(macBaseNormalizado).equalsIgnoreCase(prefixo(macObservadoNormalizado));
        }
        // Exige o mesmo OUI (3 primeiros bytes)
        if (!macBaseNormalizado.regionMatches(true, 0, macObservadoNormalizado, 0, 6)) {
            return false;
        }
        long base = paraLong(macBaseNormalizado);
        long obs = paraLong(macObservadoNormalizado);
        if (base < 0 || obs < 0) {
            return prefixo(macBaseNormalizado).equalsIgnoreCase(prefixo(macObservadoNormalizado));
        }
        long diff = obs - base;
        return diff >= OFFSET_MINIMO_PORTA && diff <= OFFSET_MAXIMO_PORTA;
    }

    /** Distância absoluta entre dois MACs normalizados (para desempate caso dois blocos fiquem próximos). */
    public static long distancia(String macBaseNormalizado, String macObservadoNormalizado) {
        long base = paraLong(macBaseNormalizado);
        long obs = paraLong(macObservadoNormalizado);
        if (base < 0 || obs < 0) {
            return Long.MAX_VALUE;
        }
        return Math.abs(obs - base);
    }
}

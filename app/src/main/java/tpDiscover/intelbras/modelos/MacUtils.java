package tpDiscover.intelbras.modelos;

/**
 * Switches Comware costumam ter um MAC diferente POR PORTA FÍSICA (um pequeno
 * offset a partir de um bloco base), não um único MAC de sistema. Por isso,
 * pra saber se um MAC visto em algum lugar "pertence" a um switch conhecido,
 * não dá pra comparar o MAC inteiro — precisamos comparar só os primeiros
 * bytes (o "bloco" alocado pro equipamento).
 *
 * Exemplo real observado nos dados de teste:
 *   MAC-base do switch .10 (display device manuinfo): 80-85-44-07-F6-34
 *   Visto na tabela MAC de outro switch:               80-85-44-07-F6-3F
 *   -> mesmos 5 primeiros bytes (80-85-44-07-F6), variação só no último byte.
 */
public final class MacUtils {

    /** Quantos bytes usamos como "prefixo de identidade" de um equipamento. */
    public static final int OCTETOS_PREFIXO_PADRAO = 5;

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
}

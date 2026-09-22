package tpDiscover.intelbras.coleta;

/**
 * Um switch que o técnico informou pra coletar, com suas próprias credenciais
 * (nem sempre todos os switches de um cliente usam o mesmo usuário/senha).
 */
public record SwitchTarget(String ip, String usuario, String senha) {
}

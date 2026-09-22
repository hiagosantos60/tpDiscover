package tpDiscover.intelbras.coleta;

import tpDiscover.intelbras.connection.ConnectionConfig;
import tpDiscover.intelbras.connection.SwitchClient;
import tpDiscover.intelbras.connection.SwitchConnectionException;
import tpDiscover.intelbras.connection.SwitchConnector;
import tpDiscover.intelbras.correlacao.TopologyEngine;
import tpDiscover.intelbras.modelos.MacEntry;
import tpDiscover.intelbras.modelos.SwitchIdentity;
import tpDiscover.intelbras.parser.ComwareParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Coleta uma lista de switches, um de cada vez. Se um switch falhar (senha
 * errada, inalcançável, timeout etc.), registra a falha e SEGUE PRO PRÓXIMO
 * — um switch problemático não pode travar a coleta dos outros 20 que estão
 * ok, especialmente em campo, com o cliente esperando.
 */
public class ColetorEmLote {

    private final ComwareParser parser = new ComwareParser();

    public record FalhaColeta(SwitchTarget alvo, SwitchConnectionException.Tipo tipo, String mensagem) {
    }

    public record ResultadoColeta(List<SwitchIdentity> sucesso, List<FalhaColeta> falhas) {
        public int total() {
            return sucesso.size() + falhas.size();
        }
    }

    /**
     * Coleta todos os alvos e alimenta o TopologyEngine informado.
     * O engine é passado de fora (não criado aqui) pra permitir juntar coletas
     * de sessões diferentes no mesmo motor de correlação, se precisar no futuro.
     */
    public ResultadoColeta coletarTudo(List<SwitchTarget> alvos, TopologyEngine engine) {
        List<SwitchIdentity> sucesso = new ArrayList<>();
        List<FalhaColeta> falhas = new ArrayList<>();

        for (SwitchTarget alvo : alvos) {
            System.out.println("\n--- Coletando " + alvo.ip() + " ---");
            try {
                SwitchIdentity identidade = coletarUm(alvo, engine);
                System.out.println("  OK: " + identidade);
                sucesso.add(identidade);
            } catch (SwitchConnectionException e) {
                System.out.println("  FALHA (" + e.getTipo() + "): " + e.getMessage());
                falhas.add(new FalhaColeta(alvo, e.getTipo(), e.getMessage()));
            }
        }

        return new ResultadoColeta(sucesso, falhas);
    }

    private SwitchIdentity coletarUm(SwitchTarget alvo, TopologyEngine engine) throws SwitchConnectionException {
        ConnectionConfig config = ConnectionConfig.padrao(alvo.ip(), alvo.usuario(), alvo.senha());
        SwitchClient cliente = null;

        try {
            cliente = SwitchConnector.conectar(config);

            cliente.executarComando("screen-length disable");
            String manuinfo = cliente.executarComando("display device manuinfo");
            String macTableBruta = cliente.executarComando("display mac-address");

            SwitchIdentity identidade = parser.parseDeviceManuInfo(alvo.ip(), manuinfo);
            engine.registrarSwitch(identidade);

            List<MacEntry> entradas = parser.parseMacAddressTable(identidade.macNormalizado(), macTableBruta);
            engine.registrarEntradas(identidade.macNormalizado(), entradas);

            System.out.println("  " + entradas.size() + " entradas de tabela MAC coletadas");

            return identidade;

        } finally {
            if (cliente != null) {
                cliente.desconectar();
            }
        }
    }
}

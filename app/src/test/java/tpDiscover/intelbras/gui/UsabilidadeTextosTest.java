package tpDiscover.intelbras.gui;

import org.junit.jupiter.api.Test;
import tpDiscover.intelbras.gui.DiscoveredTargetRow.EstadoColeta;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsabilidadeTextosTest {

    @Test
    void motivoCurtoTraduzErrosReaisDaRede() {
        assertEquals("Senha incorreta", TargetTableModel.motivoCurto(
                "Usuário ou senha incorretos para 10.1.40.78 (SSH) — 2 credencial(is) testada(s)"));
        assertEquals("Sessão ocupada/recusada", TargetTableModel.motivoCurto(
                "Telnet: [banner] Conexão Telnet encerrada por 10.1.40.84 sem nenhuma resposta — sessão de gerência "
                        + "provavelmente ocupada | SSH: Falha SSH ao conectar em 10.1.40.84: connection is closed by foreign host"));
        assertEquals("Sem resposta", TargetTableModel.motivoCurto(
                "Timeout esperando resposta do equipamento 10.1.31.19 via SSH (30000ms)."));
        assertEquals("SSH incompatível", TargetTableModel.motivoCurto(
                "Falha SSH ao conectar: Algorithm negotiation fail: algorithmName=\"server_host_key\""));
    }

    @Test
    void duracaoSemCasasDecimais() {
        assertEquals("34 s", DiscoverySwingWorker.duracaoLegivel(33_790));
        assertEquals("1 min 05 s", DiscoverySwingWorker.duracaoLegivel(65_200));
    }

    @Test
    void logoOficialCarregaDosRecursosEGeraIconesMultiResolucao() {
        org.junit.jupiter.api.Assertions.assertNotNull(
                SwingThemeHelper.class.getResource("/tpDiscover/intelbras/gui/logo.png"),
                "logo.png deve estar embutido nos recursos do aplicativo");
        java.awt.image.BufferedImage logo = SwingThemeHelper.carregarLogo();
        org.junit.jupiter.api.Assertions.assertTrue(logo.getWidth() >= 256 && logo.getHeight() >= 256);
        org.junit.jupiter.api.Assertions.assertFalse(SwingThemeHelper.iconesJanela().isEmpty());
        assertEquals(32, SwingThemeHelper.redimensionarLogo(32).getWidth());
    }

    @Test
    void falhasVaoParaOTopoDaLista() {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.40.10", "", "");
        model.adicionarManual("10.1.40.20", "", "");
        model.adicionarManual("10.1.40.3", "", "");
        model.atualizarStatusPorEndereco("10.1.40.20", EstadoColeta.FALHA, "Falha: Senha incorreta", "detalhe técnico");
        model.atualizarStatusPorEndereco("10.1.40.10", EstadoColeta.OK, "OK");
        model.atualizarStatusPorEndereco("10.1.40.3", EstadoColeta.OK, "OK");

        model.ordenarFalhasPrimeiro();

        assertEquals("10.1.40.20", model.getLinhas().get(0).getIpPorta());
        assertEquals("detalhe técnico", model.getLinhas().get(0).getDetalhe());
        assertEquals("10.1.40.3", model.getLinhas().get(1).getIpPorta(), "o restante segue a ordem numérica do IP");
        assertEquals("10.1.40.10", model.getLinhas().get(2).getIpPorta());
    }

    @Test
    void aoTerminarAColetaComFalhasMostraSoOsQuePrecisamAjuste() throws Exception {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.40.10", "", "");
        model.adicionarManual("10.1.40.20", "", "");
        model.adicionarManual("10.1.40.30", "", "");
        EquipamentosViewPanel[] painel = new EquipamentosViewPanel[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            painel[0] = new EquipamentosViewPanel(model);
            painel[0].setEstadoExecucao(true);
            model.atualizarStatusPorEndereco("10.1.40.10", EstadoColeta.OK, "OK");
            model.atualizarStatusPorEndereco("10.1.40.20", EstadoColeta.FALHA, "Falha: Senha incorreta", "x");
            model.atualizarStatusPorEndereco("10.1.40.30", EstadoColeta.OK, "OK");
            assertEquals(3, painel[0].linhasVisiveis(), "durante a coleta a lista fica completa");
            painel[0].setEstadoExecucao(false);
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            org.junit.jupiter.api.Assertions.assertTrue(painel[0].isSoFalhas());
            assertEquals(1, painel[0].linhasVisiveis(), "só o equipamento com falha aparece");
            // senha corrigida e recoletada com sucesso: volta para a lista completa
            model.atualizarStatusPorEndereco("10.1.40.20", EstadoColeta.OK, "OK");
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            org.junit.jupiter.api.Assertions.assertFalse(painel[0].isSoFalhas());
            assertEquals(3, painel[0].linhasVisiveis());
        });
    }

    @Test
    void senhaCorrigidaContinuaNaListaEEntraNaRecoleta() throws Exception {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.40.10", "", "");
        model.adicionarManual("10.1.40.20", "", "");
        model.adicionarManual("10.1.40.30", "", "");
        EquipamentosViewPanel[] painel = new EquipamentosViewPanel[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            painel[0] = new EquipamentosViewPanel(model);
            painel[0].setEstadoExecucao(true);
            model.atualizarStatusPorEndereco("10.1.40.10", EstadoColeta.OK, "OK");
            model.atualizarStatusPorEndereco("10.1.40.20", EstadoColeta.FALHA, "Falha: Senha incorreta", "x");
            model.atualizarStatusPorEndereco("10.1.40.30", EstadoColeta.FALHA, "Falha: Sessão ocupada/recusada", "x");
            painel[0].setEstadoExecucao(false);
            assertEquals(2, painel[0].linhasVisiveis());

            // corrige a senha do .20: não some da lista e entra no "Recoletar falhas"
            int linha20 = indice(model, "10.1.40.20");
            model.aplicarCredenciaisEmLinhas(java.util.Collections.singletonList(linha20), "", "NovaSenha");
            org.junit.jupiter.api.Assertions.assertTrue(model.getLinhas().get(linha20).isPendenteRecoleta());
            assertEquals(2, painel[0].linhasVisiveis(), "a linha corrigida continua visível até recoletar");
            model.selecionarApenasFalhos();
            org.junit.jupiter.api.Assertions.assertTrue(model.getLinhas().get(linha20).isSelecionado(),
                    "a corrigida é recoletada junto com as falhas");
            org.junit.jupiter.api.Assertions.assertTrue(model.getLinhas().get(indice(model, "10.1.40.30")).isSelecionado());
            org.junit.jupiter.api.Assertions.assertFalse(model.getLinhas().get(indice(model, "10.1.40.10")).isSelecionado());

            // resultado da recoleta encerra a pendência
            model.atualizarStatusPorEndereco("10.1.40.20", EstadoColeta.OK, "OK");
            org.junit.jupiter.api.Assertions.assertFalse(model.getLinhas().get(linha20).isPendenteRecoleta());
        });
    }

    @Test
    void espacoMarcaEDesmarcaAsLinhasSelecionadas() throws Exception {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.40.10", "", "");
        model.adicionarManual("10.1.40.20", "", "");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            EquipamentosViewPanel painel = new EquipamentosViewPanel(model);
            javax.swing.JTable t = painel.getTabela();
            model.selecionarTodos(false);
            t.setRowSelectionInterval(0, 1);
            javax.swing.Action marcar = t.getActionMap().get(
                    t.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(javax.swing.KeyStroke.getKeyStroke("SPACE")));
            org.junit.jupiter.api.Assertions.assertNotNull(marcar, "Espaço tem ação na tabela");
            marcar.actionPerformed(null);
            assertEquals(2, model.contarSelecionados());
            marcar.actionPerformed(null);
            assertEquals(0, model.contarSelecionados());
            org.junit.jupiter.api.Assertions.assertNotNull(t.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(javax.swing.KeyStroke.getKeyStroke("ENTER")), "Enter abre o Definir senha");
        });
    }

    @Test
    void tabAlternaEntreUsuarioESenhaSalvandoCadaCelula() throws Exception {
        TargetTableModel model = new TargetTableModel();
        model.adicionarManual("10.1.40.10", "", "");
        model.adicionarManual("10.1.40.20", "", "");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            EquipamentosViewPanel painel = new EquipamentosViewPanel(model);
            javax.swing.JTable t = painel.getTabela();
            int colU = t.convertColumnIndexToView(TargetTableModel.COL_USUARIO);
            int colS = t.convertColumnIndexToView(TargetTableModel.COL_SENHA);

            org.junit.jupiter.api.Assertions.assertTrue(t.editCellAt(0, colU));
            ((javax.swing.JTextField) t.getEditorComponent()).setText("operador");
            tab(t, false);
            assertEquals(0, t.getEditingRow());
            assertEquals(colS, t.getEditingColumn(), "Tab no Usuário vai para a Senha da mesma linha");
            assertEquals("operador", model.getLinhas().get(t.convertRowIndexToModel(0)).getUsuario(), "o usuário foi salvo");

            ((javax.swing.JPasswordField) t.getEditorComponent()).setText("S3nha");
            tab(t, false);
            assertEquals(1, t.getEditingRow(), "Tab na Senha vai para o Usuário da linha de baixo");
            assertEquals(colU, t.getEditingColumn());
            assertEquals("S3nha", model.getLinhas().get(t.convertRowIndexToModel(0)).getSenha(), "a senha foi salva");

            tab(t, true);
            assertEquals(0, t.getEditingRow(), "Shift+Tab volta para a Senha da linha de cima");
            assertEquals(colS, t.getEditingColumn());
            t.getCellEditor().cancelCellEditing();
        });
    }

    private static void tab(javax.swing.JTable t, boolean voltar) {
        javax.swing.JComponent ed = (javax.swing.JComponent) t.getEditorComponent();
        Object nome = ed.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                .get(javax.swing.KeyStroke.getKeyStroke(voltar ? "shift TAB" : "TAB"));
        org.junit.jupiter.api.Assertions.assertNotNull(nome, "o editor trata o Tab");
        ed.getActionMap().get(nome).actionPerformed(null);
    }

    private static int indice(TargetTableModel model, String ip) {
        for (int i = 0; i < model.getLinhas().size(); i++) {
            if (model.getLinhas().get(i).getIpPorta().equals(ip)) return i;
        }
        throw new AssertionError("linha não encontrada: " + ip);
    }
}

package tpDiscover.intelbras.gui;

import tpDiscover.intelbras.util.Compat;

/**
 * Linha editável na tabela de equipamentos da GUI.
 * Quando usuário ou senha específicos ficam vazios, herdam a credencial padrão informada no topo da tela.
 */
public class DiscoveredTargetRow {

    public enum EstadoColeta {
        AGUARDANDO,
        NAO_SUPORTADO_CLI,
        SEM_SENHA,
        COLETANDO,
        OK,
        FALHA
    }

    private boolean selecionado;
    private String ipPorta;
    private String produtoModelo;
    private String usuario;
    private String senha;
    private String status;
    private EstadoColeta estado;
    private boolean suportaCli;
    private String categoriaInferida = "SWITCH";
    private String macConhecido = "";
    private boolean ehGateway;

    public DiscoveredTargetRow(
            boolean selecionado,
            String ipPorta,
            String produtoModelo,
            String usuario,
            String senha,
            String status,
            EstadoColeta estado,
            boolean suportaCli
    ) {
        this.selecionado = selecionado;
        this.ipPorta = ipPorta != null ? ipPorta.trim() : "";
        this.produtoModelo = produtoModelo != null ? produtoModelo.trim() : "";
        this.usuario = usuario != null ? usuario.trim() : "";
        this.senha = senha != null ? senha : "";
        this.status = status != null ? status : "";
        this.estado = estado != null ? estado : EstadoColeta.AGUARDANDO;
        this.suportaCli = suportaCli;
    }

    public boolean isSelecionado() {
        return selecionado;
    }

    public void setSelecionado(boolean selecionado) {
        this.selecionado = selecionado && suportaCli;
    }

    public String getIpPorta() {
        return ipPorta;
    }

    public void setIpPorta(String ipPorta) {
        this.ipPorta = ipPorta != null ? ipPorta.trim() : "";
    }

    public String getProdutoModelo() {
        return produtoModelo;
    }

    public void setProdutoModelo(String produtoModelo) {
        this.produtoModelo = produtoModelo != null ? produtoModelo.trim() : "";
    }

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario != null ? usuario.trim() : "";
    }

    public String getSenha() {
        return senha;
    }

    public void setSenha(String senha) {
        this.senha = senha != null ? senha : "";
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status != null ? status : "";
        this.detalhe = "";
    }

    /** Mensagem técnica completa (exibida ao passar o mouse na coluna Situação). */
    private String detalhe = "";

    public String getDetalhe() {
        return detalhe;
    }

    public void setStatus(String status, String detalhe) {
        setStatus(status);
        this.detalhe = detalhe != null ? detalhe : "";
    }

    public EstadoColeta getEstado() {
        return estado;
    }

    public void setEstado(EstadoColeta estado) {
        this.estado = estado != null ? estado : EstadoColeta.AGUARDANDO;
    }

    public boolean isSuportaCli() {
        return suportaCli;
    }

    public void setSuportaCli(boolean suportaCli) {
        this.suportaCli = suportaCli;
        if (!suportaCli) {
            this.selecionado = false;
        }
    }

    public String getCategoriaInferida() {
        return categoriaInferida;
    }

    public void setCategoriaInferida(String categoriaInferida) {
        this.categoriaInferida = (categoriaInferida != null && !Compat.isBlank(categoriaInferida))
                ? categoriaInferida.trim() : "SWITCH";
    }

    public String getMacConhecido() {
        return macConhecido;
    }

    public void setMacConhecido(String macConhecido) {
        this.macConhecido = macConhecido != null ? macConhecido.trim() : "";
    }

    public boolean isEhGateway() {
        return ehGateway;
    }

    public void setEhGateway(boolean ehGateway) {
        this.ehGateway = ehGateway;
    }
}

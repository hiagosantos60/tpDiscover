# tpDiscover Intelbras

Ferramenta de **descoberta de topologia e diagnóstico de rede** para equipamentos Intelbras. Ela procura os equipamentos na rede, entra em cada um por SSH/Telnet (**somente leitura**), descobre quem está ligado em qual porta e entrega:

- um **mapa interativo** da rede, para consulta rápida no atendimento;
- um **relatório executivo em PDF** com diagrama, enlaces, inventário e clientes ARP;
- um **projeto para o Planejador de Rede** (`.json`), para editar a topologia depois;
- o **diagnóstico de loops e STP** e a lista de equipamentos sem acesso.

> Nenhuma configuração de switch, OLT ou AP é alterada: o tpDiscover só executa comandos de consulta (`display ...`, `show ...`, `brctl show` etc.).

---

## Sumário

- [Requisitos](#requisitos)
- [Instalação no cliente](#instalação-no-cliente)
- [Guia de uso (interface)](#guia-de-uso-interface)
  - [Acesso](#1-acesso)
  - [Aba Coleta](#2-aba-coleta)
  - [Aba Equipamentos](#3-aba-equipamentos)
  - [Aba Topologia](#4-aba-topologia)
  - [Aba Avançado](#5-aba-avançado)
  - [Atalhos de teclado](#atalhos-de-teclado)
- [Arquivos gerados](#arquivos-gerados)
- [Lista de equipamentos (.txt)](#lista-de-equipamentos-txt)
- [Uso pelo terminal (CLI)](#uso-pelo-terminal-cli)
- [Equipamentos suportados](#equipamentos-suportados)
- [Falhas comuns e o que fazer](#falhas-comuns-e-o-que-fazer)
- [Segurança](#segurança)
- [Para desenvolvedores](#para-desenvolvedores)
- [Limitações conhecidas](#limitações-conhecidas)

---

## Requisitos

| Onde | O que precisa |
|---|---|
| Máquina do técnico/cliente | Windows 7, 8, 10 ou 11 com **Java 8 (JRE 8)** — [java.com](https://www.java.com) |
| Para o PDF | **Microsoft Edge** ou **Google Chrome** instalado (no Windows 7/8, a versão 109 funciona) |
| Rede | Acesso aos equipamentos por **SSH (22)** ou **Telnet (23)**; SNMP (UDP 161) é opcional |
| Para compilar | JDK 17 ou mais novo (o código gerado continua sendo Java 8) |

---

## Instalação no cliente

1. Gere a distribuição (veja [Para desenvolvedores](#gerar-a-distribuição)) ou receba a pasta pronta.
2. Copie para a máquina os três arquivos de `app/build/distribuicao/`:

   | Arquivo | Para que serve |
   |---|---|
   | `tpDiscover.jar` | O aplicativo completo (todas as dependências incluídas) |
   | `tpDiscover.bat` | Atalho: dois cliques abrem a interface |
   | `LEIA-ME.txt` | Resumo de requisitos e uso |

3. Dê **dois cliques em `tpDiscover.bat`**.

Não leve o `switches.txt` de desenvolvimento para o cliente: ele guarda senhas em texto puro.

---

## Guia de uso (interface)

### 1. Acesso

Ao abrir, o tpDiscover pede a **senha de acesso** (fornecida pelo responsável pela ferramenta). Depois de 5 tentativas erradas o programa fecha.

### 2. Aba Coleta

É o ponto de partida. Preencha os três passos e clique em **Iniciar coleta** (ou tecle **F5**/Enter).

| Passo | O que informar |
|---|---|
| **1. Onde procurar** | **Minha rede local (automático)** — detecta a sub-rede do computador.<br>**Uma faixa de IPs** — CIDR (`10.1.40.0/24`), intervalo (`10.1.40.80-250`) ou várias faixas separadas por vírgula.<br>**Só a lista** — não faz varredura; usa os equipamentos marcados na aba Equipamentos. |
| **2. Acesso** | Usuário e senha usados em todos os equipamentos. **A senha pode ficar em branco** para equipamentos que pedem só o usuário. Senhas diferentes por equipamento: aba Equipamentos. |
| **3. Salvar relatório em** | Pasta onde o PDF e o projeto do Planejador serão gravados. |

Botões:

- **Iniciar coleta** — procura os equipamentos Intelbras, entra em cada um e monta a topologia.
- **Só procurar equipamentos** — apenas lista o que existe na rede, sem fazer login.
- **Cancelar** — interrompe a execução.

Durante e depois da coleta o formulário **recolhe numa linha** (faixa · usuário · pasta) para o **Registro de atividade** ganhar espaço. Use **Alterar** para editar os parâmetros de novo.

Ao terminar, aparece o resumo (ex.: *"Coleta concluída em 33 s: 17 equipamentos coletados, 7 sem acesso."*) com as ações:

- **Corrigir senhas** — abre a aba Equipamentos com os que falharam já marcados;
- **Ver topologia** — abre o mapa;
- **Finalizar e gerar PDF** — grava o relatório (o PDF só é gerado aqui, para você poder corrigir senhas e recoletar antes).

O botão **Copiar** do registro copia o log para enviar ao suporte.

### 3. Aba Equipamentos

Lista tudo o que foi encontrado (ou importado), com a **Situação** de cada um.

| Coluna | Descrição |
|---|---|
| Coletar | Marque os que devem ser coletados |
| Endereço IP | `ip` ou `ip:porta` (porta diferente de 22/23) |
| Produto | Família identificada (Comware, Série 3000, SG MR, OLT, AP...) |
| Usuário / Senha | Em branco = usa os da aba Coleta (duplo clique para editar) |
| Situação | `OK · ...` ou `Falha: <motivo>`. **Passe o mouse** para ver a mensagem técnica completa |

Se a coleta terminar com falhas, a lista **mostra só os equipamentos que falharam** (filtro **Só falhas (N)** ligado): são os que precisam de ajuste. Corrija a senha e use **Recoletar falhas**. Para ver a lista completa, clique em **Mostrar todos** no rodapé ou desligue o filtro. Quando não sobra nenhuma falha, a lista completa volta sozinha. Com o filtro desligado, os equipamentos com falha ficam no topo.

Barra de ferramentas:

- **Adicionar / Remover** — inclui um equipamento pelo IP ou remove os selecionados;
- **Marcar ▾** — marcar todos, desmarcar todos ou **marcar só os que falharam**;
- **Definir senha** — aplica usuário/senha às linhas selecionadas, ou marca como *"não tenho a senha"* (o equipamento aparece na topologia como passivo, sem coleta);
- **Arquivo ▾** — **importar** ou **exportar** a lista em `.txt` (formato em [Lista de equipamentos](#lista-de-equipamentos-txt));
- **Só falhas (N)** — mostra apenas os equipamentos que falharam (liga sozinho no fim de uma coleta com falhas);
- **Recoletar falhas (N)** — marca só os que falharam e coleta de novo (use depois de corrigir as senhas);
- **Recoletar marcados** — coleta os equipamentos marcados.

### 4. Aba Topologia

No topo, a **faixa de diagnóstico** resume a situação da rede:

| Faixa | Significado |
|---|---|
| Verde — *Rede saudável* | Nenhum loop detectado |
| Amarela — *Topologia parcial* | Há equipamentos sem acesso ou trechos incertos (ligações tracejadas) |
| Amarela — *Portas bloqueadas pelo STP* | Há redundância, contida pelo STP |
| Vermelha — *Loop crítico* | Possível loop ativo: veja a aba Problemas |

A faixa traz os botões **Corrigir senhas**, **Abrir pasta** e **Finalizar e gerar PDF** / **Abrir PDF**.

#### Mapa

O mesmo diagrama do PDF, interativo:

- **Equipamentos** com ícone, nome e IP; alertas sobre o ícone: `! LOOP`, `STP BLK`, `! SENHA`, `SEM CLI`.
- **Cabos** com a porta em cada ponta. Cores: azul-ardósia = cobre, verde = fibra, âmbar tracejado = incerto/bloqueado pelo STP, vermelho = loop. A legenda fica no canto inferior esquerdo; o resumo (equipamentos, cabos, sem acesso) no canto superior.
- **Navegação**: roda do mouse = zoom; arrastar = mover; duplo clique no fundo ou **Ajustar** = enquadrar. Em redes grandes o mapa abre num tamanho legível centrado no gateway/core — arraste para ver o resto.
- **Buscar** (Ctrl+F) — por nome, IP ou MAC; Enter vai para o próximo resultado.
- **Clique num equipamento** — abre o painel de detalhes: IP, MAC, serial, situação da coleta e a lista de conexões (*"GE1/0/24 → Gateway 10.1.40.1, porta remota LAN1"*). Clique numa conexão para ir até o vizinho. Botões **Copiar IP** e **Abrir web**.
- **Botão direito** num equipamento — copiar IP, copiar MAC, abrir interface web.
- **Passe o mouse** num cabo — mostra as duas portas e o tipo. Etiquetas de porta que não cabem sem sobrepor ficam ocultas e aparecem ao selecionar/passar o mouse no switch.
- **Imagem** — salva o mapa em PNG.
- **Planejador** — salva o projeto `.json` do Planejador de Rede **na pasta do relatório**.

#### Problemas

Lista **loops, portas bloqueadas pelo STP e equipamentos sem acesso**, com gravidade, evidência e o que fazer. **Duplo clique**:

- num equipamento sem acesso → abre a correção de senha na aba Equipamentos;
- num loop → mostra o equipamento no mapa.

#### Conexões

Tabela com todos os enlaces: equipamento, porta, porta remota e onde está ligado.

### 5. Aba Avançado

| Seção | Para que serve |
|---|---|
| **Senhas alternativas** | Combinações tentadas automaticamente quando a senha principal é recusada. Padrão: `GEPON/GEPON` e `admin/admin` para OLTs, `admin/admin` para switches e APs. Deixe a senha em branco para equipamentos que pedem só o usuário. **Restaurar Padrão** volta a essa lista. |
| **Descoberta** | **LLDP**: inclui vizinhos que o scan não achou. **SNMP**: ajuda a reconhecer o modelo (desative se a rede bloquear SNMP). |
| **Equipamentos coletados ao mesmo tempo** | Padrão 16. Reduza se houver muitas falhas por tempo esgotado. |
| **Gateway (opcional)** | IP e MAC do roteador, só se a topologia não mostrar o gateway correto. |
| **Sobre** | Versão, Java em uso e atalhos. |

O tpDiscover **lembra entre as aberturas**: usuário, pasta do relatório, faixa de IPs, opções avançadas e tamanho da janela. **Senhas nunca são gravadas.**

### Atalhos de teclado

| Atalho | Ação |
|---|---|
| Ctrl+1 a Ctrl+4 | Aba Coleta, Equipamentos, Topologia, Avançado |
| F5 | Iniciar coleta |
| Ctrl+F | Buscar no mapa |
| Ctrl+P | Gerar o PDF |
| Enter (na aba Coleta) | Iniciar coleta |

---

## Arquivos gerados

Ao clicar em **Finalizar e gerar PDF**, a pasta do relatório recebe:

| Arquivo | Conteúdo |
|---|---|
| `topologia_AAAA-MM-DD_HHmm.pdf` | Relatório executivo A4 paisagem: diagrama, enlaces par a par, inventário, clientes ARP com fabricante (OUI), loops e pendências |
| `topologia_AAAA-MM-DD_HHmm_planejador.json` | Projeto do **Planejador de Rede** — abra pelo botão **Abrir** do Planejador |

Sobre o projeto do Planejador:

- equipamentos nas mesmas posições do mapa, com IP, modelo, MAC e serial nas anotações;
- portas na numeração do Planejador (`24`, `SFP1`, `LAN1`, `PON1`, `ETH`), com o **nome real** (ex.: `GE1/0/24`) na descrição de cada porta;
- dois cabos na mesma porta (switch não gerenciado no meio) viram portas distintas marcadas como *"cabo compartilhado"*;
- o trecho sem acesso vira um switch anotado, para ajuste manual.

Se não houver Edge/Chrome, o relatório é salvo em HTML no lugar do PDF.

---

## Lista de equipamentos (.txt)

Usada em **Arquivo ▾ → Importar/Exportar lista** e pelo modo terminal. Uma linha por equipamento:

```
ip;usuario;senha
```

| Exemplo | Significado |
|---|---|
| `10.1.40.203;admin;Admin@1234` | IP, usuário e senha |
| `10.1.40.204;admin` | Sem senha no arquivo: usa a senha da aba Coleta |
| `10.1.31.19:65019;admin;Senha123` | Porta SSH/Telnet diferente de 22/23 |
| `10.1.40.50;suporte;abc;123` | A senha pode conter `;` (tudo após o 2º `;` é senha) |

Regras: IP e usuário são obrigatórios; linhas vazias e linhas iniciadas por `#` são ignoradas.

Linhas opcionais de configuração (com ou sem `#` na frente):

| Linha | Efeito |
|---|---|
| `scan=10.1.40.0/24` | Faixa de varredura (aceita intervalo, várias faixas separadas por vírgula ou `auto`) |
| `credencial=admin;Admin@1234` | Usuário e senha padrão |
| `gateway=10.1.40.1` | Gateway manual (aceita também o MAC: `gateway=10.1.40.1 0009-0F09-1E0A`) |

Exemplo:

```
# Cliente X - rede principal
scan=10.1.40.0/24
credencial=admin;Admin@1234

10.1.40.253;admin
10.1.40.203;admin
10.1.40.204;admin;OutraSenha
10.1.31.19:65019;admin;Senha123
```

> Salve o arquivo em **UTF-8**. No Bloco de Notas do Windows 7, use *Salvar como → Codificação: UTF-8* se houver acentos (inclusive em comentários).

Ao exportar, o tpDiscover pergunta se deve incluir as senhas — responda **Não** se o arquivo for compartilhado.

---

## Uso pelo terminal (CLI)

```bash
java -jar tpDiscover.jar [arquivo_alvos] [opções]
```

Sem argumentos abre a interface. O terminal também pede a senha de acesso.

| Opção | Descrição |
|---|---|
| `--gui` | Abre a interface gráfica |
| `--cli` | Modo terminal usando `switches.txt` da pasta atual |
| `--scan <CIDR\|faixa\|auto>` | Varre a rede por produtos Intelbras (ex.: `10.1.40.0/24`, `auto`) |
| `-u, --user <usuario>` | Usuário padrão |
| `-p, --pass <senha>` | Senha padrão (login e enable) |
| `--apenas-scan` | Só identifica os equipamentos, sem login |
| `--salvar-alvos <arquivo>` | Salva a lista consolidada no formato `ip;usuario;senha` |
| `--concorrencia <N>` | Coletas simultâneas (padrão: 16) |
| `--sem-expansao-lldp` | Não inclui vizinhos descobertos por LLDP |
| `--sem-snmp` | Não usa SNMP durante o scan |
| `-h, --help` | Ajuda |

Exemplos:

```bash
# Varre a faixa e coleta usando as credenciais do arquivo
java -jar tpDiscover.jar clientes/loja1.txt --scan 10.1.40.0/24

# Só lista os equipamentos Intelbras da rede local
java -jar tpDiscover.jar --scan auto --apenas-scan
```

No modo terminal, o PDF e o `.json` são gravados **na pasta do arquivo de alvos** (ou na pasta atual, se não houver arquivo).

---

## Equipamentos suportados

| Família | Exemplos |
|---|---|
| Switches Comware | S2310G-A, S2328G-A/B, S2352G |
| Switches Série 3000 / Max | S3028G-PB Max, S3052G-B, S3054G-B |
| Switches S2050 / S30-Lite / PoE-Max | S2050G-A, S3028G-B Lite, S3028G-PB Lite |
| Switches SG MR / MR L2+ / S-Series | SG 1002 MR, SG 2404 MR, SG 5204 MR L2+ |
| OLTs | G08/G16/4840E, AN6000/6001/5516, 8820I/G |
| Access Points e rádios | Linha corporativa (legado, ZeusOS, OpenWrt) |
| Gateway | Detectado pela rota padrão e ARP (qualquer fabricante; o fabricante sai pelo MAC) |

Equipamentos sem acesso (senha errada ou sem CLI) aparecem na topologia como **passivos**, posicionados pela tabela MAC/ARP dos vizinhos.

---

## Falhas comuns e o que fazer

| Situação mostrada | Causa provável | O que fazer |
|---|---|---|
| **Senha incorreta** | Usuário/senha recusados (inclusive as senhas alternativas) | Informe a senha certa em Equipamentos (ou em Senhas alternativas) e use **Recoletar falhas** |
| **Sessão ocupada/recusada** | Switches SG aceitam **uma** sessão Telnet/SSH: outra pessoa/terminal está conectada, ou ficou uma sessão presa | Feche outros terminais ou aguarde o tempo de inatividade do switch e recolete |
| **Sem resposta** | Equipamento lento ou rede com perda | Reduza "Equipamentos coletados ao mesmo tempo" e recolete |
| **Telnet/SSH desativado** | Porta 22/23 fechada no equipamento | Habilite o acesso remoto ou informe a porta (`ip:porta`) |
| **SSH incompatível** | Algoritmo SSH que o equipamento exige não foi negociado | Envie o log (botão Copiar) ao suporte |
| Mapa com ligações tracejadas | Trecho da rede sem acesso entre o equipamento e o gateway | Corrija o acesso aos equipamentos sem CLI e recolete |

---

## Segurança

- **Somente leitura**: só comandos de consulta são enviados aos equipamentos.
- **Senha de acesso** ao programa: guardada apenas como hash PBKDF2-SHA256 com sal (`seguranca/ControleAcesso.java`), nunca em texto puro. Para trocar, gere um novo sal/hash com `ControleAcesso.gerarHash` e substitua as constantes `SAL` e `HASH`.
- A senha de acesso impede o uso por quem não a conhece, mas **não é uma proteção forte**: quem tiver o `.jar` e conhecimento técnico pode removê-la.
- **Senhas dos equipamentos** não são gravadas nas preferências. Arquivos `.txt` exportados *com* senhas e o `switches.txt` guardam senhas em texto puro — trate-os como confidenciais.
- O arquivo `senha.txt` está no `.gitignore` e nunca deve ser versionado.

---

## Para desenvolvedores

### Gerar a distribuição

```bash
./gradlew distribuicaoWindows
```

Gera `app/build/distribuicao/` com `tpDiscover.jar`, `tpDiscover.bat` e `LEIA-ME.txt`.

Recomendado — testar antes de gerar:

```bash
./gradlew test distribuicaoWindows
```

### Java 8 como único alvo

- Compilação com `options.release = 8`: usar qualquer API do Java 9+ quebra o build.
- `gradlew test` roda **sempre num runtime Java 8 real** e falha se não houver Java 8. O build procura um JRE/JDK 8 em `C:/Program Files/Java` e pastas semelhantes, ou use `-Pjava8Home=<pasta>` / variável `JAVA8_HOME`.
- `gradlew run` também executa no Java 8.
- Equivalentes das APIs do Java 9+ (`List.of`, `isBlank`, `Files.readString`...) ficam em `util/Compat.java`.
- Bouncy Castle fornece `ssh-ed25519`/`curve25519` ao JSch no Java 8.

### Estrutura

```
app/src/main/java/tpDiscover/intelbras/
├── App.java                 entrada (interface ou terminal)
├── coleta/                  scan + coleta em lote, credenciais, gateway local
├── connection/              clientes SSH (JSch) e Telnet, login e terminal
├── parser/                  Comware, Série 3000, OLT, AP
├── correlacao/              motor de topologia, loops, relatório, PDF, mapa e exportação p/ Planejador
├── descoberta/              varredura de rede e identificação de produtos
├── gui/                     interface Swing (FlatLaf claro, cores do padrão visual STR em SwingThemeHelper)
├── modelos/                 MAC, ARP, LLDP, STP, identidade do equipamento
├── seguranca/               senha de acesso
└── util/                    Compat (APIs Java 9+ para Java 8)
```

Testes em `app/src/test/java` (inclui servidor SSH embutido — Apache MINA — e servidores Telnet simulados). Arquitetura detalhada em [`docs/ARQUITETURA_UML.md`](docs/ARQUITETURA_UML.md).

---

## Limitações conhecidas

- Validado no Windows 11 com JRE 8u421; **Windows 7/8 ainda não foram testados** em máquina real.
- A coleta de um AP específico (OpenWrt, porta SSH customizada) pode esgotar o tempo após o comando LLDP.
- Arquivos `.txt` de alvos precisam estar em UTF-8 quando contêm acentos.

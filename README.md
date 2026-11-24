# tpDiscover

Ferramenta para descoberta de topologia e diagnóstico de rede em equipamentos Intelbras (switches Comware, Série 3000, linha SG, OLTs e APs).

Conecta nos equipamentos via SSH/Telnet (somente leitura) para coletar tabelas MAC, ARP, LLDP e STP, gerando:
- Mapa interativo da topologia e diagnóstico de loops
- Relatório em PDF (`topologia_AAAA-MM-DD_HHmm.pdf`)
- Arquivo `.json` compatível com o Planejador de Rede

## Requisitos

- **Para rodar:** Java 8 (JRE 8) ou superior e Microsoft Edge / Google Chrome (usado na geração do PDF).
- **Para compilar:** JDK 17+ (o bytecode final é gerado para Java 8).

## Como usar

Para abrir a interface gráfica, execute o `tpDiscover.bat` ou rode:

```bash
java -jar tpDiscover.jar
```

### Uso via terminal (CLI)

```bash
java -jar tpDiscover.jar [arquivo_alvos.txt] [opções]
```

**Opções disponíveis:**

| Opção | Descrição |
|---|---|
| `--cli` | Roda no terminal usando o `switches.txt` da pasta atual |
| `--scan <CIDR\|faixa\|auto>` | Varre a rede buscando equipamentos (ex: `10.1.40.0/24`, `10.1.40.1-254`, `auto`) |
| `-u, --user <usuario>` | Usuário padrão para login |
| `-p, --pass <senha>` | Senha padrão para login |
| `--apenas-scan` | Apenas lista os equipamentos encontrados na rede, sem fazer login |
| `--salvar-alvos <arquivo>` | Salva a lista de equipamentos encontrados em um arquivo `.txt` |
| `--concorrencia <N>` | Número de coletas simultâneas (padrão: 16) |
| `--sem-expansao-lldp` | Ignora vizinhos descobertos via LLDP que não estavam na lista inicial |
| `--sem-snmp` | Desativa consultas SNMP durante a varredura |
| `-h, --help` | Exibe a ajuda no terminal |

**Exemplos:**

```bash
# Varre a rede local apenas para listar os equipamentos encontrados
java -jar tpDiscover.jar --scan auto --apenas-scan

# Varre uma faixa de IP e faz a coleta usando credenciais padrão
java -jar tpDiscover.jar --scan 10.1.40.0/24 -u admin -p Admin@1234

# Executa a coleta a partir de um arquivo de alvos
java -jar tpDiscover.jar alvos.txt
```

### Formato do arquivo de alvos (`.txt`)

O arquivo de alvos (usado no CLI ou importado na interface) define um equipamento por linha no formato `ip;usuario;senha` (codificação UTF-8). Também aceita parâmetros opcionais no cabeçalho:

```text
# Parâmetros opcionais
scan=10.1.40.0/24
credencial=admin;SenhaPadrao123
gateway=10.1.40.1

# Equipamentos: ip[:porta];usuario[;senha]
10.1.40.253;admin
10.1.40.203;admin;OutraSenha
10.1.31.19:65019;admin;SenhaPortaCustom
```

## Build e distribuição

Para compilar o projeto e gerar o executável atualizado:

```powershell
# Windows
.\gradlew.bat distribuicaoWindows

# Linux / macOS
./gradlew distribuicaoWindows
```

Os arquivos finais são gerados na pasta `app/build/distribuicao/`:
- `tpDiscover.jar` (JAR único com todas as dependências embutidas)
- `tpDiscover.bat`
- `LEIA-ME.txt`

### Testes

Os testes rodam obrigatoriamente em um runtime Java 8 para garantir compatibilidade:

```powershell
.\gradlew.bat test
```

Caso o Java 8 não esteja instalado em um diretório padrão (`C:\Program Files\Java`, etc.), informe o caminho via variável de ambiente `JAVA8_HOME` ou parâmetro `-Pjava8Home="C:\caminho\jre8"`.

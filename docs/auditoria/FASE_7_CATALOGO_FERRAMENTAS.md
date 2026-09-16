# Auditoria BrainCode — Fase 7: inventário de ferramentas

**Snapshot:** `ffed42a4fd83095797dfd6bf53213a82b1c8788d`

## 1. Regra de contagem

"Ferramenta" pode significar coisas diferentes no BrainCode. Para não misturar camadas, este inventário separa:

1. binários do RootFS;
2. comandos `sandbox-*`;
3. toolchains;
4. plugins opcionais;
5. comandos operacionais da UI;
6. catálogo de prompts;
7. capabilities internas.

Não contar um prompt como binário e não contar um binário como capability autorizada.

## 2. Toolchains declaradas pelo app

`BuiltInToolchains.all` contém exatamente sete perfis:

| ID | Função | Executável de detecção |
|---|---|---|
| `android` | Android SDK/NDK | `sdkmanager` |
| `java` | Java | `java` |
| `python` | Python | `python3` |
| `node` | Node.js | `node` |
| `cpp` | C/C++ | `g++` |
| `rust` | Rust | `rustc` |
| `go` | Go | `go` |

O `ToolchainManager` detecta, instala, remove e pode restaurar estado anterior. A instalação usa comandos construídos pelo perfil, não entrada shell arbitrária do usuário.

## 3. Plugins built-in opcionais

`BuiltInCatalog.plugins` possui seis componentes:

1. **Ollama** — LLM local opcional;
2. **Android NDK r27c** — toolchain nativa Android;
3. **Trivy 0.58.2** — scanner de vulnerabilidades;
4. **SOPS 3.9.4** — criptografia/configuração;
5. **grpcurl 1.9.2** — cliente gRPC;
6. **websocat 1.13.0** — cliente WebSocket.

`BuiltInCatalog.tools` está vazio porque as ferramentas gerais já vêm no RootFS. O catálogo evita duplicar binários que já estão presentes.

## 4. Ferramentas que já vêm no RootFS base

O Dockerfile 0.3.3 instala, entre outros:

### Sistema/build

bash, coreutils, build-essential, pkg-config, cmake, ninja, make, patch, diffutils, sed, gawk.

### Git

git, git-lfs, gh, openssh-client, rsync.

### Arquivos/texto

zip, unzip, jq, yq, nano, vim, less, file, tree, ripgrep, fd-find, bat.

### Python

python3, pip, venv, dev, pytest, bandit, pipx, ruff, black, mypy.

### Java/Android base

default-jdk, gradle. O Android SDK completo é acrescentado no 0.5.0.

### JavaScript

Node.js 20, npm, eslint, prettier, yarn, pnpm.

### Compiladores

Rust/cargo e Go, além de C/C++ pelo build-essential.

### Shell/debug

shellcheck, ctags, tmux, fzf, entr, procps, psmisc, htop, lsof.

### Rede

iproute2, net-tools, dnsutils, netcat-openbsd, socat, curl, wget, httpie.

### Dados/CLI

SQLite, PostgreSQL client, MySQL client, Redis tools.

### Documentos/mídia/grafos

pandoc, ImageMagick, Graphviz.

### Compactação

p7zip, xz, bzip2, zstd.

## 5. Ferramentas adicionais do RootFS 0.4.1

A camada Agent Extra adiciona:

- bats;
- valgrind;
- strace/ltrace;
- gdb/lldb;
- ping/tcpdump/traceroute/mtr;
- nmap/whois/openssl/age/binutils;
- xmlstarlet/libxml2-utils;
- ffmpeg/exiftool/poppler-utils/ghostscript/qpdf;
- rclone/parallel/btop/ncdu/watch/libarchive-tools;
- uv/pip-tools/poetry;
- coverage/tox/pre-commit/isort/flake8/pyright;
- maturin/cython/hypothesis/jsonschema/yamllint;
- build/duckdb/pandas/polars/numpy/openpyxl/python-docx/pypdf/reportlab;
- semgrep/grpcio-tools/websockets;
- TypeScript/tsx/Vite/Vitest/Playwright.

## 6. Ferramentas adicionais do RootFS 0.5.0

O perfil Android/API adiciona:

- Android SDK;
- Android build-tools;
- platform-tools;
- adb;
- command-line tools;
- plataforma Android 23;
- aapt2;
- apksigner;
- zipalign;
- sdkmanager;
- avdmanager;
- gitleaks;
- unrar-free;
- qpdf/rsync/socat.

NDK e emulador não são embutidos; NDK aparece como plugin opcional no catálogo.

## 7. Comandos estáveis `sandbox-*`

O RootFS base documenta:

- `sandbox-run`
- `sandbox-test`
- `sandbox-build`
- `sandbox-clean`
- `sandbox-info`
- `sandbox-diagnose`
- `sandbox-health`

O perfil Android/API adiciona:

- `sandbox-artifact`
- `sandbox-job`
- `sandbox-health-android`

Esses comandos são interfaces de execução do ambiente. A Policy/ActionGateway continua sendo a autoridade do BrainCode sobre ações relevantes.

## 8. Comandos operacionais da UI

O composer reconhece como operações reais:

```text
/run
/testlab
/security
/git status
/git diff
/workflow
/approval demo
/workspace new
/sqlite start
/sqlite stop
/discovery
/deliver
```

Eles não são o mesmo catálogo que os 344 prompts especializados.

## 9. Catálogo de prompts

`comandos_catalogo.json` contém 344 entradas PROMPT no estado documentado pelo próprio `SandboxViewModel`.

Essas entradas:

- orientam o Brain;
- fornecem autocomplete;
- não executam shell diretamente;
- não concedem autorização;
- não substituem os comandos operacionais reais.

Portanto "344 comandos" não significa "344 executores locais".

## 10. Capabilities internas

Além das ferramentas físicas, o Brain trabalha com capabilities como unidade autorizável. Elas podem representar:

- providers/APIs;
- Agents;
- Skills;
- Tools;
- Commands;
- Sandbox;
- plugins.

`PluginCatalogCapabilityProvider` transforma componentes do catálogo em capabilities `plugin.<id>` e preserva metadados/proveniência. Discovery encontra candidatos; Policy decide se podem ser usados.

## 11. Regra de segurança

A presença de um binário no RootFS, de um plugin no catálogo ou de uma capability no registry não significa autorização.

Fluxo correto:

```text
ferramenta existe
   ↓
capability descoberta
   ↓
policy verifica
   ↓
ação passa pelo gateway
   ↓
executor/sandbox
   ↓
evidência
```

## 12. Resultado

O sistema possui um conjunto grande de ferramentas, mas ele é organizado em camadas. O inventário não encontrou evidência de que as ferramentas base estejam duplicadas como plugins instaláveis: `BuiltInCatalog.tools` está vazio justamente para evitar essa duplicação.

O principal ponto de manutenção é manter os três RootFS imutáveis/homologados e instalar somente extensões opcionais sob demanda.

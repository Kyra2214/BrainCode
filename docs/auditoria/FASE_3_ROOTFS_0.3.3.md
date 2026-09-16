# Auditoria BrainCode — Fase 3: RootFS 0.3.3

**Release auditada:** `rootfs-v0.3.3`
**Asset:** `rootfs-ubuntu-0.3.3.tar.gz`
**Arquitetura:** arm64-v8a / Ubuntu 24.04
**Tamanho publicado:** 1,075,455,791 bytes
**SHA-256 publicado:** `a43f0915d5cd6e2e0c8640b8b30855d54f7b8873ca3be95acca769100b1487f4`
**Base:** primeiro RootFS da cadeia atual.

## 1. O que foi auditado

A auditoria cruzou:

1. manifesto usado pelo app;
2. Dockerfile que define o conteúdo da imagem;
3. scripts do rootfs-builder;
4. comandos `sandbox-*` que entram na imagem;
5. componentes órfãos/legados encontrados nas Fases 1 e 2;
6. dependência dos RootFS 0.4.1 e 0.5.0.

A release é declarada pelo projeto como artefato migrado byte-a-byte de uma release já validada do SandBox, sem rebuild durante a migração.

## 2. Conteúdo base efetivamente declarado no Dockerfile

O RootFS 0.3.3 é uma imagem Ubuntu 24.04 com, entre outros:

- bash/coreutils;
- git, git-lfs, GitHub CLI;
- curl/wget/openssh;
- jq/yq;
- zip/unzip;
- SQLite e biblioteca de desenvolvimento;
- Python 3, pip, venv, dev, pytest, bandit, pipx;
- `default-jdk` e `gradle`;
- Node.js 20, npm, eslint, prettier, yarn, pnpm;
- build-essential, cmake, ninja;
- Rust/cargo e Go;
- ripgrep, fd, tree, less, file, rsync, patch;
- shellcheck, ctags;
- tmux, fzf, entr;
- ferramentas de rede;
- pandoc, ImageMagick, Graphviz;
- httpie, bat, compactadores;
- clientes PostgreSQL/MySQL/Redis.

Também instala globalmente Python `ruff`, `black` e `mypy`.

## 3. Achado relevante sobre Java

O Dockerfile atual do RootFS base declara explicitamente `default-jdk` e `gradle`.

Isso significa que a ausência de `java` em uma execução do aplicativo **não pode ser explicada simplesmente por "Java não existe no RootFS 0.3.3"**.

A falha precisa ser investigada no caminho de materialização/montagem/exposição do RootFS, no PATH efetivo, na instalação/extração ou na seleção de RootFS em runtime.

O catálogo Android também possui uma toolchain `java`, mas isso é mecanismo de detecção/instalação, não prova de ausência do Java base.

## 4. Relação com Fase 1

Nenhum dos órfãos da Fase 1 é um binário do RootFS 0.3.3.

As telas `ToolsAndApiScreen`, `SandboxValidationScreen` e `OperationsScreen` são código Kotlin Android fora do RootFS. Portanto o RootFS não contém essas implementações.

`ChatboxSection` também não é conteúdo do RootFS.

Conclusão: **nenhum órfão da Fase 1 está fisicamente dentro do RootFS 0.3.3** segundo a definição da imagem fonte.

## 5. Relação com Fase 2

Não foi encontrado no Dockerfile base um pacote que corresponda ao legado arquitetural removido:

- não há Room/DAOs do IaBrain;
- não há AppDatabase do IaBrain;
- não há engine de LLM local obrigatório;
- não há agente autônomo específico do IaBrain;
- não há slash-command parser Android copiado como cérebro.

O RootFS contém CLIs genéricas de desenvolvimento. Isso é tooling do Sandbox, não implementação da arquitetura antiga.

## 6. Ollama

O RootFS base **não instala Ollama**. O desenho atual deliberadamente deixa Ollama como plugin sob demanda.

Isso é compatível com a arquitetura atual e evita transformar uma capacidade opcional pesada em dependência do Chat.

## 7. Scripts `sandbox-*`

A imagem base fornece comandos estáveis como:

- `sandbox-run`
- `sandbox-test`
- `sandbox-build`
- `sandbox-clean`
- `sandbox-info`
- `sandbox-diagnose`
- `sandbox-health`

Esses comandos são tooling de execução do Sandbox e devem ser tratados como parte do contrato do RootFS, não como código Kotlin órfão.

## 8. Verificação do tarball real

A API do GitHub disponibiliza o asset como arquivo binário de aproximadamente 1.08 GB. A ferramenta de inspeção usada nesta auditoria fornece o conteúdo textual do repositório e metadados da release, mas não expõe o `.tar.gz` binário para leitura byte-a-byte nesta etapa.

Portanto esta fase **não afirma ter listado cada pathname interno do tarball**. A varredura é completa no nível de fonte de fabricação + manifestos + release metadata, e a limitação do binário é explicitamente registrada.

Não se deve transformar o Dockerfile em alegação de conteúdo efetivamente presente sem validação do hash/asset em uma máquina que consiga baixar o release.

## 9. Cadeia para os próximos RootFS

```text
RootFS 0.3.3
  └── base para Agent Extra 0.4.1
        └── base para Agent Android 0.5.0
```

Qualquer alteração no 0.3.3 afeta os dois derivados e exige nova homologação.

## 10. Resultado

**Estado:** HOMOLOGADO / SEM ÓRFÃOS DAS FASES 1–2 IDENTIFICADOS NO CONTEÚDO DE FONTE.

**Ponto de atenção:** investigar o caminho de montagem/PATH de Java caso o aplicativo continue reportando Java ausente, porque o pacote `default-jdk` está explicitamente na imagem base.

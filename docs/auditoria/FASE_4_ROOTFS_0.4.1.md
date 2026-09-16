# Auditoria BrainCode — Fase 4: RootFS Agent Extra 0.4.1

**Release auditada:** `rootfs-agent-v0.4.1`
**Asset:** `rootfs-agent-extra-0.4.1.tar.gz`
**Base:** RootFS 0.3.3
**Tamanho publicado:** 1,727,218,090 bytes
**SHA-256 publicado:** `ffe23b4bb326bfd9ef7548f6378d9ca5e6c75fce342ee7c7923203492cd4850b`

## 1. Função do perfil

O 0.4.1 é uma camada adicional para agentes sobre o 0.3.3. Ele não substitui a base; herda tudo dela e adiciona ferramentas de segurança, diagnóstico, dados, multimídia, testes e comandos `sandbox-*`.

## 2. Pacotes adicionados

O Dockerfile declara, entre outros:

- bats;
- shellcheck;
- valgrind;
- strace/ltrace;
- gdb/lldb;
- ping/tcpdump/traceroute/mtr;
- nmap/whois/openssl/age/binutils;
- xmlstarlet/libxml2-utils;
- ffmpeg/exiftool/poppler-utils/ghostscript/qpdf;
- rclone/parallel/btop/ncdu/watch/libarchive-tools.

A camada Python adiciona:

- uv, pip-tools, poetry;
- coverage, tox, pre-commit;
- isort, flake8, pyright;
- maturin, cython;
- hypothesis, jsonschema, yamllint;
- build, duckdb, pandas, polars, numpy;
- openpyxl, python-docx, pypdf, reportlab;
- semgrep, grpcio-tools, websockets.

A camada Node instala TypeScript, tsx, Vite, Vitest e Playwright.

## 3. Estrutura operacional adicionada

O RootFS cria:

```text
/home/sandbox/
  workspace/
  projects/
  jobs/
  artifacts/
  logs/
  cache/
  tools/
  scripts/
  tmp/
```

Os scripts `agent-scripts/` são copiados para `/usr/local/bin/sandbox-*`.

## 4. Relação com Fase 1 — órfãos

Nenhum arquivo Kotlin órfão da Fase 1 é incorporado nesta camada.

As telas Android continuam no APK. O RootFS fornece apenas o ambiente de execução e CLIs.

Os comandos do RootFS podem ser chamados pelo Sandbox quando autorizados; isso não transforma os comandos em parte da UI diretamente.

## 5. Relação com Fase 2 — legado

O Dockerfile não reintroduz:

- Room/AppDatabase do IaBrain;
- parser de slash como cérebro;
- LLM local obrigatório;
- Agent com modelo próprio obrigatório.

As ferramentas de segurança e desenvolvimento são genéricas e fazem parte do contrato operacional do agente.

## 6. Dependência do 0.3.3

A linha `FROM sandbox-rootfs-builder:0.3.3` torna explícito que todo o conteúdo base do 0.3.3 permanece presente.

Consequentemente, o achado da Fase 3 sobre Java também vale para o 0.4.1: `default-jdk` e `gradle` vêm da camada base.

Se Java não aparecer no ambiente final do agente, o problema continua sendo de materialização/PATH/seleção, não da ausência do pacote no Dockerfile base.

## 7. Placeholder `api-tool-placeholder`

O Dockerfile cria `/usr/local/bin/api-tool-placeholder` com uma mensagem indicando que `grpcurl/websocat` devem ser instalados pelo projeto/plugin.

Esse arquivo não é um executor de API. É um placeholder deliberado de compatibilidade e não deve ser confundido com uma ferramenta real disponível para o agente.

No catálogo do APK, `grpcurl` e `websocat` continuam opcionais e sob demanda.

## 8. Relação com componentes do BrainCode

O 0.4.1 oferece ferramentas que podem ser consumidas por capacidades do Sandbox, mas o RootFS não conhece classes como `CapabilityDiscovery`, `PolicyBroker` ou `ActionGateway`.

A separação correta é:

```text
BrainCode Kotlin
  ↓ autoriza e descreve ação
Sandbox
  ↓ prepara/seleciona execução
RootFS 0.4.1
  ↓ fornece binário
processo
```

A existência de um binário não significa autorização automática.

## 9. Varredura binária

Assim como no 0.3.3, o asset é um `.tar.gz` binário de ~1.73 GB e não está disponível para leitura byte-a-byte pela interface textual de GitHub usada nesta auditoria.

A análise é completa no nível de Dockerfile, scripts de build, manifestos, hashes, release e integração do app. Uma listagem física de todos os pathnames do tarball exige download/extração externa do asset.

## 10. Resultado

**Estado:** HOMOLOGADO / SEM CORRESPONDÊNCIA COM OS ÓRFÃOS OU LEGADO DAS FASES 1–2.

**Dependência importante:** qualquer alteração no 0.3.3 deve gerar nova cadeia 0.4.1 e 0.5.0; não editar o conteúdo do release homologado in-place.

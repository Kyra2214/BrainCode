# Fase B — openai/codex: tecnologias, ferramentas e técnicas

> Reanálise (v2): foco no que existe de fato no projeto — arquitetura de
> código, protocolo, sandboxing, config, telemetria — não só o README.
> Este documento é só levantamento, sem propor mudança em nada.

## 1. Arquitetura do workspace (codex-rs)

`codex-rs/` é um workspace Cargo com ~70 crates, organizados em camadas:

- **Entry points**: `codex-cli` (dispatcher multi-tool), `codex-tui`
  (terminal interativo), `codex-exec` (modo headless/CI), `codex-app-server`
  (ponte JSON-RPC para IDEs).
- **Core engine**: `codex-core` (orquestração do agente, roteamento de
  tools, estado de sessão — dono das structs `ThreadManager`, `CodexThread`,
  `Session`), `codex-protocol` (tipos `Op`/`EventMsg`), `codex-config`
  (resolução de TOML em camadas), `codex-state` (persistência de thread).
- **Platform/integrações**: `codex-mcp-server` (host + client MCP),
  sandboxing por SO (Seatbelt no macOS, Landlock no Linux,
  RestrictedToken no Windows), `ModelClient` (abstração sobre
  OpenAI/Ollama/LM Studio).

Convenção de nomes: toda crate é prefixada com `codex-`. Regra de
manutenção documentada no `AGENTS.md` do próprio repo: **resistir a
adicionar código em `codex-core`** — ela já é a maior crate e cresce demais;
antes de adicionar algo novo lá, avaliar se cabe numa crate existente ou
se é hora de criar uma nova no workspace.

## 2. Protocolo de comunicação (JSON-RPC 2.0)

`codex-app-server` expõe o motor central via JSON-RPC 2.0, usado por
VS Code, Cursor e outras extensões de IDE. O protocolo (`codex-protocol`)
ainda não é "estável", mas já tem exports de schema TypeScript em
`app-server-protocol/schema/typescript/`, permitindo qualquer cliente
tipado (existe até uma crate Rust de terceiro, `codex-codes`, que gera
bindings tipados sobre esse protocolo, com cliente sync e async/tokio).

Conceitos do protocolo: `thread_start`, `turn_start`, streaming de eventos
(`ThreadEvent`), correlação de request/response, fluxo de aprovação
embutido no próprio protocolo (não é uma camada externa).

## 3. Sandboxing por sistema operacional

Não é um sandbox único e sim uma política (`SandboxPolicy`) mapeada para
mecanismos nativos de cada SO:
- **macOS**: `sandbox-exec` com perfil Seatbelt.
- **Linux**: `bwrap` + `seccomp` (antes: Landlock).
- **Windows**: sandbox nativo próprio, ou modo WSL2 (que reusa o sandbox
  Linux).
A política se aplica à árvore de processos inteira (processo + tudo que
ele gerar), não só ao processo raiz.

## 4. Linguagem de regras (execpolicy / Starlark)

Regras de aprovação de comando são escritas em `.rules` usando **Starlark**
(subconjunto seguro, sintaxe tipo Python, sem I/O). Uma regra
(`prefix_rule`) casa contra o prefixo de argumentos do comando
(equivalente ao que `execvp(3)` recebe), com decisão `allow` / `prompt` /
`forbidden`, mais exemplos `match`/`not_match` validados no carregamento.
Comandos compostos (`bash -lc "a && b"`) são parseados com tree-sitter e
divididos em comandos individuais quando possível — mas caem para
tratamento como bloco único se houver substituição de variável,
redirecionamento, wildcard etc.

## 5. Descoberta de instruções em camadas (AGENTS.md)

Sistema de precedência bem definido: escopo global
(`~/.codex/AGENTS.md` ou `.override.md`) → escopo de projeto (raiz até o
diretório atual, um arquivo por pasta) → merge de cima pra baixo, onde
arquivos mais próximos do diretório atual sobrescrevem os mais distantes.
Suporta nomes de fallback configuráveis (`project_doc_fallback_filenames`)
pra reconhecer convenções já existentes (`TEAM_GUIDE.md` etc.), com limite
de bytes configurável (`project_doc_max_bytes`, 32 KiB por padrão).

## 6. Telemetria opt-in (OpenTelemetry)

Desligada por padrão. Quando ligada, exporta eventos estruturados
(`codex.conversation_starts`, `codex.api_request`, `codex.tool_decision`,
`codex.tool_result`, etc.) mais métricas contador+histograma associadas.
Prompt do usuário é redigido por padrão (`log_user_prompt = false`).
Exportadores suportados: `otlp-http`, `otlp-grpc`, ou `none` (mantém
instrumentação ativa sem enviar nada).

## 7. Subagentes / orquestração multi-agente

Agentes built-in (`default`, `worker`, `explorer`) mais agentes
customizados definidos em arquivos TOML separados
(`~/.codex/agents/*.toml` ou `.codex/agents/*.toml`), cada um com seu
próprio `model`, `model_reasoning_effort`, `sandbox_mode` e
`developer_instructions`. Um agente pai spawna vários filhos em paralelo
(ex.: `pr_explorer` só lê e mapeia código, `reviewer` avalia risco,
`docs_researcher` consulta um MCP server de documentação) e consolida os
resultados numa resposta única — desenhado especificamente para evitar
"context pollution" (poluir o histórico principal com exploração/logs).

## 8. Abstração de modelo multi-provider

Suporte nativo a rodar via Amazon Bedrock como `model_provider`, com dois
caminhos de autenticação (API key Bedrock, ou cadeia de credenciais AWS
SDK) — troca de provider é só configuração (`model_provider =
"amazon-bedrock"`), sem mudar o resto do fluxo.

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- Padrão de crate-per-concern num workspace Cargo (Rust) como forma de
  evitar um "core" inchado.
- JSON-RPC 2.0 como protocolo de comunicação agente↔cliente, com schema
  tipado exportado pra outra linguagem (TypeScript).
- Sandboxing nativo por SO em vez de uma camada só (Seatbelt/bwrap+seccomp/
  RestrictedToken).
- Starlark como linguagem de regras — segura por design (sem side effects),
  com testes inline (`match`/`not_match`) no próprio arquivo de regra.
- Sistema de precedência de arquivos de instrução em camadas (global →
  projeto → subpasta), com nomes de fallback configuráveis.
- Schema de telemetria OTel com redaction por padrão.
- Padrão de agente pai + subagentes especializados rodando em paralelo com
  MCP servers próprios por especialidade.

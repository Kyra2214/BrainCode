# Fase 2 — Análise de `openai/codex`

**Repositório analisado:** [`openai/codex`](https://github.com/openai/codex)  
**Revisão fixada no checkout:** `53ff712a48379ce8df605e292afd6046ca88ae9b` (`main`, 2026-09-12; commit “Add model grouping to the agent command center”)  
**Papel no Brain:** referência de agente de programação local, runtime de ferramentas, sandbox, protocolo de servidor e SDKs.  
**Método:** inspeção estática do checkout GitHub, arquivo por arquivo nos diretórios de produto, extensão, segurança, estado, SDK, testes, exemplos, workflows e configuração; cruzamento com as páginas primárias versionadas no próprio GitHub. O snapshot contém aproximadamente **7.785 arquivos rastreados**, incluindo **4.097 Rust**, **179 Markdown**, e aproximadamente **2.800 caminhos relacionados a testes**. A contagem inclui código gerado, fixtures, snapshots e vendors; não é uma medida de cobertura. Não executei a suíte completa: o objetivo foi análise arquitetural e de absorção, não validação de build.

> **Conclusão executiva.** Codex é um monorepo Rust de agente local com uma separação madura entre orquestração de turnos (`codex-core`), execução controlada (`codex-exec`, `codex-sandboxing`, `codex-execpolicy`), interface terminal (`codex-tui`), servidor JSON-RPC (`codex-app-server`), protocolo versionado e schemas, extensões (Skills, MCP, hooks, memória, plugins) e persistência de threads/rollouts. A arquitetura é um candidato forte para o núcleo operacional da Fase 2 do Brain, mas não deve ser absorvida como um bloco monolítico. O caminho recomendado é adotar primeiro as interfaces de política, execução, eventos, persistência e extensão; deixar TUI, voz, V8, integrações proprietárias, CI Bazel e componentes de produto fora do núcleo inicial. O snapshot demonstra funcionalidades implementadas e testadas, mas também contém superfícies explicitamente experimentais, compatibilidade legada e áreas aparentemente em evolução; a incorporação deve ser pinada a commit, acompanhada de SBOM/licença e protegida por adaptadores do Brain.

## 1. Escopo, evidência e distinção entre implementado e planejado

A fonte de verdade desta análise é o checkout do repositório oficial. O README descreve o produto como um agente de programação que roda localmente, com distribuição por binário/gerenciadores e licença Apache-2.0 [1]. `AGENTS.md` funciona como contrato de engenharia do próprio projeto: estabelece convenções Rust, dupla validação Cargo/Bazel, testes de integração para mudanças no agente, limites de contexto e cautela para não aumentar `codex-core` [2]. Os contratos de servidor, SDK e segurança foram lidos nos arquivos versionados e nas árvores de diretórios do repositório [3] [4] [5].

Neste relatório, **Implementado** significa que há código, manifesto, schema, teste, fixture ou workflow no commit analisado. Isso não implica que toda configuração esteja habilitada por padrão, nem que toda superfície seja estável. **Planejado/experimental** significa que o próprio código ou documentação marca a função como experimental, possui fallback legado, tem nome de POC/canary, é condicionado por feature flag, depende de backend/host específico, ou aparece como TODO. Essa distinção é essencial: o repositório contém produto de distribuição, bibliotecas internas, protótipos, compatibilidade e infraestrutura de release no mesmo workspace.

## 2. Arquitetura geral

### 2.1 Camadas

| Camada | Implementação principal | Responsabilidade | Estado no snapshot |
|---|---|---|---|
| Entradas | `codex-rs/cli`, `codex-rs/tui`, `codex-rs/app-server`, SDKs Python/TypeScript | CLI, UI terminal, JSON-RPC e clientes programáticos | Implementado; TUI e app-server são grandes superfícies |
| Orquestração | `codex-rs/core` | Sessão, turnos, contexto, chamadas Responses API, tool loop, aprovações e eventos | Implementado; é o maior núcleo e recebe orientação explícita para não crescer mais |
| Ferramentas | `codex-rs/tools`, módulos de ferramentas em `core`, `codex-rs/codex-mcp`, `rmcp-client` | Shell, patch, busca, MCP, web/image, input do usuário, descoberta dinâmica | Implementado; catálogo e carregamento dinâmico são configuráveis |
| Segurança | `exec`, `execpolicy`, `sandboxing`, `linux-sandbox`, `windows-sandbox-rs`, `network-proxy`, `secrets` | Perfis, approvals, filesystem, rede, processos, proxy e violações | Implementado por plataforma; o modo irrestrito existe e deve ser governado |
| Extensões | `ext/skills`, `ext/memories`, `ext/mcp`, `ext/web-search`, `ext/image-generation`, `plugin`, `connectors`, `hooks` | Capacidades carregáveis, hooks externos, MCP e memória | Implementado em vários níveis; algumas integrações são experimentais/host-owned |
| Protocolo | `app-server-protocol`, schemas JSON/TypeScript, transporte | RPC v1/v2, eventos, approvals, threads, realtime e geração de tipos | v2 é a superfície ativa recomendada; v1 permanece para compatibilidade |
| Estado | `state`, `thread-store`, `history`, `rollout`, `message-history` | SQLite/migrações, threads, fila, logs, anexos, rollouts e recuperação | Implementado; schema tem numerosas migrações |
| Observabilidade | `otel`, `rollout-trace`, `state/audit.rs`, tracing | Logs, traces, métricas, eventos de uso e rastreabilidade de turnos | Implementado, porém exportação e retenção dependem de configuração/host |
| Build/release | Cargo, Bazel/Bzlmod, GitHub Actions, `third_party` | Build local/remote, cross-platform, V8, voz, artefatos e releases | Implementado; duplicidade aumenta custo e risco operacional |

O workspace Rust está em `codex-rs/Cargo.toml`; o crate raiz não é um único executável, mas dezenas de crates com dependências internas. `codex-core` concentra a orquestração, enquanto o próprio `AGENTS.md` recomenda resistir a adicionar novas funções nele e criar crates menores quando necessário [2] [3]. O repositório também possui `MODULE.bazel`, `BUILD.bazel` e BUILD files por crate. Bazel é usado para o caminho de CI e releases, enquanto Cargo continua sendo usado para desenvolvimento, SDKs e smoke tests [6] [7].

### 2.2 Fluxo operacional de um turno

1. **Inicialização.** A entrada (`codex`, TUI ou app-server) resolve `CODEX_HOME`, diretório de trabalho, perfil/configurações, provedor/modelo, instruções `AGENTS.md`, estado persistido, permissões e recursos de extensão. Configuração é em camadas e pode incluir configuração gerenciada, perfis nomeados e requisitos que restringem os perfis permitidos.
2. **Construção de contexto.** `TurnContext` em `core/src/session/turn_context.rs` reúne cwd, modelo, política de aprovação, perfil de permissões, política de sandbox, ferramentas, histórico e fragmentos contextuais. As regras do repositório exigem contexto incremental, sem reescrita de histórico, limites rígidos e fragmentos definidos como estruturas bounded [2].
3. **Amostragem.** `core/src/session/turn.rs::run_turn` prepara a requisição para a Responses API, abre spans, transmite a resposta e trata retry/stream. O modelo pode produzir mensagem, chamada de função, shell, patch, MCP ou outra ferramenta.
4. **Decisão/execução.** O dispatcher transforma o item do modelo em `ToolCall`; a ferramenta é resolvida a partir do catálogo e do ambiente. Shell e patch passam pela política de aprovação e pelo perfil de sandbox. MCP passa pelo runtime/catalogação e por elicitações. A execução emite eventos de início, saída, conclusão, erro ou solicitação de aprovação.
5. **Feedback.** A saída da ferramenta volta ao contexto como item de resposta, respeitando truncamento e orçamento. O loop continua até conclusão, interrupção, erro ou pedido de input. O app-server/TUI recebe eventos em tempo real.
6. **Persistência.** Histórico de thread, itens, turnos, estado e rollout são persistidos. O sistema possui recuperação, fila, anexos, seções, projetos, objetivos e migrações. O armazenamento de rollout preserva a sequência que pode ser retomada.
7. **Pós-turno.** Hooks de stop/session-end podem executar; telemetria registra métricas/eventos; as extensões de memória podem produzir saída de Fase 1 e, separadamente, consolidar a workspace na Fase 2.

O fluxo está documentado também em `codex-rs/docs/protocol_v1.md`, com uma sequência UI → daemon → sessão → tarefa → agente → aprovação → execução → eventos [8]. O protocolo v2 de app-server é a superfície recomendada para novas APIs; `AGENTS.md` proíbe adicionar nova área em v1 e exige schemas JSON/TypeScript coerentes [2] [5].

## 3. Inventário arquivo por arquivo dos pontos relevantes

A lista abaixo cobre os arquivos de contrato, entrada, implementação, extensão, configuração, testes e operação que definem o comportamento do agente. Arquivos repetitivos gerados e fixtures são agrupados por padrão, mas seus diretórios são registrados explicitamente; isso evita confundir um schema gerado com lógica autoritativa.

### 3.1 Raiz, governança e build

| Caminho | Conteúdo e papel | Implementado ou planejado |
|---|---|---|
| `README.md` | Posicionamento, instalação macOS/Linux/Windows, releases, login e licença | Implementado/documentado; não é especificação interna |
| `AGENTS.md` | Regras de desenvolvimento, testes, compatibilidade, contexto, API v2 e segurança operacional do checkout | Implementado como política de engenharia; também contém direções futuras/TODO |
| `LICENSE` | Apache License 2.0, copyright OpenAI 2025 e condições de redistribuição | Implementado |
| `NOTICE` | Avisos do projeto e atribuições | Implementado; deve acompanhar redistribuição |
| `SECURITY.md` | Canal Bugcrowd e link para orientação de sandbox/approvals | Implementado; não substitui threat model do Brain |
| `.bazelignore`, `.bazelrc`, `.bazelversion` | Exclusões, versões e configurações Bazel | Implementado |
| `BUILD.bazel`, `MODULE.bazel`, `MODULE.bazel.lock`, `defs.bzl`, `rbe.bzl` | Plataforma, toolchains, dependências Bzlmod e regras Rust | Implementado; integração BuildBuddy é opcional/host-dependent |
| `Cargo.toml` (ausente na raiz) | Não existe manifesto Cargo raiz; o workspace Rust está em `codex-rs/Cargo.toml` | Fato importante para absorção |
| `package.json`, `pnpm-workspace.yaml`, `pnpm-lock.yaml` | Ferramentas JS, `codex-cli`, proxy e SDK TypeScript; políticas pnpm de dependência | Implementado |
| `justfile` | Atalhos para fmt, test, Bazel, schemas, scripts e checks | Implementado |
| `ruff.toml`, `.prettierrc.toml`, `.markdownlint-cli2.yaml`, `.codespellrc`, `.codespellignore` | Qualidade Python/JS/Markdown | Implementado |
| `.github/workflows/*.yml` | 33 caminhos de workflow/action auxiliares, cobrindo CI, releases, SDKs, V8, voz e verificações | Implementado; release/canary não são o runtime do agente |
| `.github/workflows/README.md` | Estratégia: checks PR rápidos, Bazel pre-merge e Cargo full pós-merge | Implementado |
| `announcement_tip.toml` | Dados de anúncio/promoção do produto | Produto/distribuição; não absorver no núcleo |
| `workspace_root_test_launcher.*.tpl` | Launchers para testes Bazel multiplataforma | Infraestrutura |

O fato de não existir `Cargo.toml` na raiz reduz a portabilidade de ferramentas que assumem workspace Cargo no root. A absorção deve preservar `codex-rs` como subprojeto ou criar uma camada de build própria.

### 3.2 CLI, TUI e entradas

| Caminho/diretório | Papel | Estado |
|---|---|---|
| `codex-rs/cli/Cargo.toml`, `BUILD.bazel`, `src/main.rs`, `src/lib.rs` | Binário CLI, parsing Clap, login, exec, review, app-server, debug sandbox e comandos auxiliares | Implementado |
| `codex-rs/cli/src/cli.rs`, `command.rs`, `debug_sandbox.rs` | Comandos e flags. Há opções para modelo, perfil, cwd, permission profile, logging de denials, allow-unix-socket e execução em sandbox | Implementado; flags devem ser adaptadas ao Brain |
| `codex-rs/tui/` | UI terminal, chat composer, status, seleção de modelo, approvals, snapshots e slash commands | Implementado, mas enorme e acoplado a produto |
| `codex-cli/package.json`, `bin/codex.js` | Wrapper npm que encaminha para binário | Implementado/distribuição |
| `codex-rs/app-server-daemon`, `app-server-client`, `app-server-transport` | Daemon, cliente e transportes do app-server | Implementado |
| `codex-rs/exec-server`, `exec-server-protocol`, `stdio-to-uds`, `uds`, `websocket-client` | Execução remota/local e canais IPC/WS | Implementado; exige threat model de rede |

A TUI deve ser considerada uma camada de apresentação substituível. Para o Brain, a entrada preferível é app-server v2 ou uma interface interna baseada em eventos, não a reutilização de widgets de `tui`.

### 3.3 Núcleo de agente e contexto

| Caminho | Papel | Estado |
|---|---|---|
| `codex-rs/core/src/lib.rs` | Exporta módulos do núcleo | Implementado |
| `codex-rs/core/src/session/turn.rs` | Loop principal `run_turn`, retries, stream, stop hooks e tool activity | Implementado |
| `codex-rs/core/src/session/turn_context.rs` | `TurnContext`, política de execução, modelo, cwd, histórico e estado por turno | Implementado |
| `codex-rs/core/src/session/step_activation.rs` | Validação de autorização/ativação de passos e command approval | Implementado |
| `codex-rs/core/src/tools/` e `codex-rs/tools/` | Construção de specs, dispatch, output, tool search e exposição | Implementado |
| `codex-rs/core/src/context/` | Fragmentos contextuais bounded e montagem de prompt | Implementado; limites são requisito de extensão |
| `codex-rs/core/src/rollout/`, `responses_retry`, `responses_metadata` | Request/stream/retry/metadata para Responses API | Implementado |
| `codex-rs/core/src/hook_runtime/` | Integração de hooks no ciclo de turno | Implementado |
| `codex-rs/core/src/suite/` e `tests/` | Harness e integração do agente | Implementado; `AGENTS.md` prefere integração para mudanças de lógica |

`codex-core` é a principal dependência de orquestração, mas sua dimensão e acoplamento são risco de absorção. Recomenda-se extrair apenas interfaces estáveis e manter o Brain atrás de um `BrainAgentAdapter`.

### 3.4 Ferramentas

| Caminho | Capacidades observadas |
|---|---|
| `codex-rs/tools/src/tool_definition.rs`, `tool_executor.rs`, `tool_spec.rs` | Definições, exposição e execução de ferramentas; conversão para Responses API |
| `codex-rs/tools/src/tool_call.rs`, `tool_output.rs`, `tool_payload.rs` | Representação de chamada, retorno e payload |
| `codex-rs/tools/src/tool_search.rs`, `tool_discovery.rs`, `dynamic_tool.rs` | Catálogo, busca, descoberta e carregamento tardio/dinâmico |
| `codex-rs/tools/src/mcp_tool.rs` | Adaptação de ferramentas MCP para Responses API |
| `codex-rs/tools/src/code_mode.rs` | Agrupamento/definição de ferramentas em code mode |
| `codex-rs/tools/src/request_plugin_install.rs` | Solicitação de instalação de plugin/conector com elicitação/approval |
| `codex-rs/apply-patch/` | Aplicação segura de patch, parser e testes |
| `codex-rs/file-search/` | Busca de arquivos e resultados bounded |
| `codex-rs/shell-command/`, `shell-escalation/`, `exec/` | Shell, escalonamento, processo e saída |
| `codex-rs/codex-mcp/`, `rmcp-client/` | MCP runtime, catálogo, auth/OAuth, recursos, elicitation e cache |
| `codex-rs/ext/web-search/`, `image-generation/`, `connectors/` | Ferramentas externas/hosted e conectores |
| `codex-rs/agent-graph-store/`, `agent-identity/`, `agent-roles/` | Relações, identidade e papéis de agentes |
| `codex-rs/collaboration-mode-templates/` | Templates `plan.md` e `default.md` para colaboração |

### 3.5 Segurança e execução

| Caminho | Papel |
|---|---|
| `codex-rs/execpolicy/` | Regras allow/deny, prefixos, rede e composição de políticas |
| `codex-rs/sandboxing/` | Abstração multiplataforma de sandbox, transformações, violações e proxies |
| `codex-rs/linux-sandbox/` | `no_new_privs`, seccomp, Landlock, bubblewrap e proxy de rede |
| `codex-rs/windows-sandbox-rs/`, `windows-sandbox-service/` | Restricted token, ACL, firewall, serviço elevado e IPC Windows |
| `codex-rs/macos-sandbox/` ou módulos Seatbelt em CLI/sandboxing | Seatbelt e captura de denials em macOS |
| `codex-rs/network-proxy/` | Proxy, roteamento e controles de acesso de rede |
| `codex-rs/secrets/`, `keyring-store/`, `login/`, `user-verification/` | Segredos, login, credenciais e verificação nativa |
| `codex-rs/process-hardening/` | Endurecimento do processo |
| `codex-rs/guardian-context/`, `guardian-reviewer/`, `guardian-v2/` | Revisão/guardião para decisões de approval; algumas rotas são experimentais/host-dependent |

Os modos de sandbox presentes no schema v2 são `read-only`, `workspace-write` e `danger-full-access`. `workspace-write` possui raízes graváveis, controle de rede e exclusões de `/tmp`/ambiente. O código também contém perfis nomeados (`:workspace`, managed profiles etc.) e requisitos que podem rejeitar configurações perigosas. **`danger-full-access` é implementado, não deve ser tratado como padrão seguro.**

### 3.6 Configuração e protocolo

| Caminho | Papel |
|---|---|
| `codex-rs/config/` | Carregamento em camadas, TOML, perfis, requirements, network proxy, filesystem constraints e warnings |
| `codex-rs/config-schema/` | Schema/serialização da configuração |
| `codex-rs/protocol/` | Tipos de eventos, permissões, modelos, Responses items e contratos internos |
| `codex-rs/app-server-protocol/src/protocol/common.rs` | Tipos comuns e métodos compartilhados |
| `codex-rs/app-server-protocol/src/protocol/v1/` | API legada |
| `codex-rs/app-server-protocol/src/protocol/v2/` | API ativa; requests/responses/notifications, approvals, threads, config e realtime |
| `codex-rs/app-server-protocol/schema/json/` | JSON schemas gerados |
| `codex-rs/app-server-protocol/schema/typescript/v2/` | Tipos TypeScript gerados por método/tipo |
| `codex-rs/app-server-protocol/scripts/` e testes de schema | Geração e estabilidade de contrato |
| `codex-rs/docs/protocol_v1.md` | Fluxo/semântica v1 e exemplos de transporte |

O protocolo v2 exige camelCase no wire, unions tagueadas, tipos TS gerados, paginação por cursor, IDs string e marcação explícita de experimental. Config RPC é exceção e espelha chaves TOML em snake_case [2] [5].

### 3.7 Skills, plugins e hooks

| Caminho | Papel | Estado |
|---|---|---|
| `codex-rs/skills/` | Loader/instalação de skills de sistema; fingerprint de assets | Implementado |
| `codex-rs/ext/skills/src/` | Catálogo, providers host/executor/orchestrator, seleção explícita/implícita, prompts, estado e telemetria | Implementado; seleção dinâmica/shadow experiment pode ser experimental |
| `codex-rs/ext/skills/templates/`, `skills/` do checkout | Conteúdo instrucional e assets | Implementado; conteúdo deve ser revisado antes de absorver |
| `codex-rs/plugin/` | Manifestos, IDs, providers, localização, capability summary e hooks bundled | Implementado |
| `codex-rs/ext/mcp/` | Extensão MCP/plugin | Implementado |
| `codex-rs/hooks/src/types.rs` | Tipos de evento, payload, resultado e contrato | Implementado |
| `codex-rs/hooks/src/events/` | `session_start`, `user_prompt_submit`, `pre_tool_use`, `post_tool_use`, `permission_request`, `compact`, `interrupt`, `stop`, `session_end` | Implementado |
| `codex-rs/hooks/src/engine/` | Discovery, dispatcher, command runner, MCP runner, parser e schema loader | Implementado |
| `codex-rs/hooks/schema/generated/` | Schemas de entrada/saída por evento | Gerado e versionado |
| `codex-rs/hooks/src/bin/write_hooks_schema_fixtures.rs` | Regeneração das fixtures | Implementado |

A interface de hook é rica, mas executa comandos/integrações em pontos sensíveis. No Brain, hooks devem ser tratados como código não confiável, com timeout, isolamento, allowlist e trilha de auditoria; não devem herdar automaticamente permissões do agente.

### 3.8 Memória e persistência

| Caminho | Papel | Estado |
|---|---|---|
| `codex-rs/memories/read/` | Leitura, citações e métricas de memória | Implementado |
| `codex-rs/memories/write/phase1.rs` | Normalização por rollout e saída de estágio 1 | Implementado |
| `codex-rs/memories/write/phase2.rs` | Seleção bounded, lock global, sincronização da workspace, diff e agente de consolidação | Implementado no snapshot |
| `codex-rs/memories/write/control.rs`, `runtime.rs`, `storage.rs`, `workspace.rs` | Coordenação, estado, arquivos, guardas e diretórios | Implementado |
| `codex-rs/memories/write/templates/` | Prompts de estágio 1, consolidação e notas ad hoc | Implementado como templates |
| `codex-rs/ext/memories/` | Ferramentas de `read`, `search`, `list`, nota ad hoc e prompts de acesso | Implementado |
| `codex-rs/memories/README.md` | Explicação de Phase 1/Phase 2 | Implementado/documentado |
| `codex-rs/state/src/model/memories.rs`, `runtime/memories.rs` | Persistência/seleção de registros de memória | Implementado |
| `codex-rs/state/migrations/0017_phase2_selection_flag.sql`, `0018_phase2_selection_snapshot.sql`, `0035_drop_memory_tables.sql` | Evolução do schema de memória | Implementado no histórico de migração |
| `codex-rs/memories/write/src/phase2_*_tests.rs` | Segurança de sandbox, roots de workspace e invariantes da Phase 2 | Implementado/testado |

A documentação define que a Phase 2 reivindica um lock global, seleciona saídas de Phase 1 por uso/recência com limites, sincroniza `raw_memories.md` e `rollout_summaries/`, mantém `~/.codex/memories/.git`, cria `phase2_workspace_diff.md`, chama um subagente sem approvals e sem rede quando há mudanças, e só reseta o baseline após sucesso [9]. Isso é **implementado**, embora o nome “Phase 2” seja domínio de memória e não signifique que todos os demais recursos do Brain estejam prontos.

### 3.9 Estado, histórico, rollout e dados

| Caminho | Papel |
|---|---|
| `codex-rs/state/src/sqlite.rs`, `migrations.rs`, `paths.rs` | Banco SQLite, migrações e localização |
| `codex-rs/state/src/model/*.rs` | Thread, projeto, log, queue, goal, attachment, memória e grafo |
| `codex-rs/state/src/runtime/*.rs` | Operações, recovery, logs, projetos, goals, queue, threads, anexos, memória e remote control |
| `codex-rs/state/src/audit.rs`, `telemetry.rs`, `log_db.rs` | Auditoria, telemetria e persistência de logs |
| `codex-rs/thread-store/` | Armazenamento e leitura de threads persistidas |
| `codex-rs/history/`, `message-history/` | Histórico de mensagens e projeções |
| `codex-rs/rollout/`, `rollout-trace/` | Arquivos de rollout, migração, trace e resumos |
| `codex-rs/file-watcher/` | Atualizações de arquivos/configuração |

O número de migrações, incluindo filas, anexos, seções, modelos, objetivos, memórias e remote control, mostra uma base persistente real, não apenas uma cache de conversa. Para absorção, o Brain precisa de namespace, criptografia/controle de acesso e política de retenção próprios; copiar o diretório de estado sem governança pode vazar prompts, código e credenciais.

### 3.10 SDKs, exemplos e testes

| Área | Arquivos/diretórios | O que provam |
|---|---|---|
| TypeScript | `sdk/typescript/src/{codex,thread,exec,events,items,...}.ts`, `samples/*.ts`, `tests/*.test.ts`, `package.json` | Cliente streaming, execução, abort, structured output, Zod, MCP conformance e testes Jest |
| Python | `sdk/python/src/openai_codex/`, `examples/01_*` a `15_*`, `tests/test_app_server_*.py`, `pyproject.toml` | Cliente sync/async, RPC, streaming, approvals, goals, login, turn controls, contratos e integração real |
| CLI | `codex-cli/` | Instalação npm e encaminhamento ao binário |
| Core | `codex-rs/core/tests`, `core/suite`, `core/src/**/*_tests.rs` | Integração do agente e regressões de contexto/tool loop |
| App-server | `codex-rs/app-server/tests`, testes de protocolo e harness | Contrato JSON-RPC público e lifecycle |
| Segurança | `exec/tests`, `execpolicy/tests`, `linux-sandbox/tests`, `windows-sandbox-rs`, `rmcp-client/tests` | Políticas, sandbox, MCP, transportes e casos de negação |
| TUI | `tui/tests`, snapshots `.snap`, `fixtures/oss-story.jsonl` | Layout, terminal, reconnect, status, worktree e regressões visuais |
| Hooks/Skills | `hooks/src/*_tests.rs`, `hooks/schema/generated`, `skills` tests | Contrato de eventos, parsing, discovery e assets |
| Memória | `memories/read`, `memories/write/*_tests.rs`, fixtures | Seleção, sandbox, workspace roots, startup e consolidação |
| CI/SDK | `.github/workflows/sdk.yml`, workflows Python, TypeScript/Jest, MCP conformance | Regressão multi-linguagem |

A suíte é ampla, mas a distribuição não equivale à cobertura uniforme. `tui` possui muitos fixtures/snapshots; `hooks`, `state` e `codex-mcp` têm importante lógica em módulos internos e testes indiretos; integrações com serviços reais dependem de ambiente e podem ser omitidas localmente.

## 4. Skills, ferramentas, modos e permissões

### 4.1 Skills

Skills são instruções/capacidades carregáveis que alteram o contexto e podem ser selecionadas por provider (host, executor ou orchestrator). O loader de sistema usa assets embutidos e fingerprint; a extensão `ext/skills` implementa catálogo, roots, seleção explícita/implícita, prompts, estado e telemetria. O plugin model expõe `PluginCapabilitySummary`, incluindo `has_skills`, servidores MCP e connectors.

**Implementado:** descoberta de skills, instalação/expansão de assets, provider separation, prompt injection bounded, persistência de seleção, detecção de invocação implícita e telemetria de uso.  
**Experimental/condicionado:** seleção dinâmica, shadow selection e skills fornecidas por plugin/host podem depender de feature flag, servidor ou contexto do cliente.  
**Não inferir:** o repositório não fornece garantia de que toda skill presente no checkout é apropriada ao Brain; conteúdo de instrução é código de política e deve passar por revisão.

### 4.2 Ferramentas

O catálogo inclui:

- shell/exec com PTY, shell mode, ambiente e captura de saída;
- `apply_patch` e utilitários de arquivo;
- file search e operações de workspace;
- tool search/discovery e carregamento tardio;
- MCP local/HTTP, recursos, OAuth, elicitation, cache e conectores;
- web search e geração de imagem em extensões;
- code mode para agrupar ferramentas em uma superfície de código;
- `request_user_input`, approval/elicitation e instalação de plugin;
- histórico, rollout, worktree, git e revisão;
- voice/realtime/WebRTC em crates separados.

O modelo não recebe necessariamente todas as ferramentas sempre: `ToolExposure`, catálogo e `tool_search` permitem exposição seletiva. Isso é uma boa base para um Brain que precisa de capability negotiation e orçamento de contexto.

### 4.3 Modos de operação

| Modo | Evidência | Observação para o Brain |
|---|---|---|
| Interativo TUI | `codex-rs/tui` | Produto local; não absorver como core |
| CLI normal | `codex-rs/cli`/README | Entrada de usuário e automação limitada |
| Execução direta | comandos `exec`, `debug-sandbox`, landlock/bwrap/Seatbelt/Windows | Boa base de worker, mas precisa de adaptador |
| App-server JSON-RPC | `app-server`, protocolo v1/v2 | Melhor contrato de integração |
| SDK Python | `sdk/python` | Cliente programático, sync/async |
| SDK TypeScript | `sdk/typescript` | Cliente Node, streaming e structured output |
| Review/patch/worktree | CLI, core, worktree, apply-patch | Capacidades de coding específicas |
| Collaboration/agent roles | `collaboration-mode-templates`, `agent-roles`, graph store | Implementado em partes; governar delegação |
| Realtime/voice | `realtime-webrtc`, `voice-host`, websocket | Produto complementar, não requisito do Brain |
| Hosted/remote control | `cloud-tasks`, `remote-control`, connectors | Dependente de backend/identidade; não assumir offline |

### 4.4 Permissões e approvals

A política é composta por pelo menos quatro dimensões: **o que** pode ser executado (exec policy), **onde** pode ler/escrever (filesystem sandbox), **se** pode acessar rede (network policy/proxy) e **quando** precisa de confirmação (approval policy). O protocolo expõe `approval_policy`, `approvals_reviewer`, `sandbox_mode`, `sandbox_workspace_write` e configuração de web/tools. Há rotas para aprovação de comando e apply-patch, além de elicitações MCP.

Perfis visíveis no schema são `read-only`, `workspace-write` e `danger-full-access`. A configuração também possui `allowed_permission_profiles`, `default_permissions`, perfis nomeados e requisitos gerenciados. O executor injeta perfil no ambiente, resolve cwd/roots e pode aplicar regras de rede. A presença de `approval_policy = never` em alguns testes e workers internos não significa que deva ser aceitável em tarefas do usuário.

**Recomendação de política Brain:** negar por padrão; separar aprovação humana, aprovação automática por risco e revisão de subagente; nunca elevar de `workspace-write` para acesso total somente por uma string de configuração; registrar todas as decisões com comando, cwd, roots, rede, identidade do plugin e hash do contexto.

## 5. Hooks, memória, workflows e observabilidade

### 5.1 Hooks

O engine de hooks executa comandos ou MCP runners nos eventos `session_start`, `user_prompt_submit`, `pre_tool_use`, `post_tool_use`, `permission_request`, `compact`, `interrupt`, `stop` e `session_end`. Schemas JSON gerados definem input/output, e há parser de saída, spill de output, discovery e dispatcher.

**Ponto forte:** o contrato é tipado e testado; o Brain pode expor hooks como eventos versionados.  
**Risco:** `pre_tool_use`/`permission_request` podem mudar decisão de segurança; `stop`/`session_end` podem tocar memória/arquivos. Hooks precisam de sandbox próprio, timeouts, limite de saída, idempotência, isolamento de segredos e prevenção de recursão. Os testes de memória explicitamente verificam que o worker não deve disparar hooks do usuário, uma separação que o Brain deve manter.

### 5.2 Memória e “Phase 2”

A divisão é coerente:

- **Phase 1:** escala por rollout e produz registros normalizados por conversa.
- **Phase 2:** serializa a consolidação global; seleciona um conjunto bounded; atualiza a workspace de memória; gera diff; chama um consolidator isolado; só promove o baseline após sucesso.

O pipeline usa lock global, seleção por `usage_count` e última utilização/geração, watermark, diretório Git local, `raw_memories.md`, `rollout_summaries/`, `MEMORY.md`, `memory_summary.md` e `skills/`. O worker da Phase 2 roda sem approvals, sem rede e com escrita local, e desabilita colaboração para impedir delegação recursiva [9].

**Implementado:** seleção, locks, baseline, diff, reset após sucesso, testes de workspace roots/sandbox, registros em SQLite e ferramentas de leitura/search.  
**Limites:** o consolidator ainda é um agente que interpreta dados; prompt injection pode entrar via rollout/memória; o diretório `.git` pode reter conteúdo sensível; memória global precisa de tenancy, retenção, apagamento e criptografia no Brain.  
**Absorção:** adotar a semântica de lock/selection/diff como contrato, mas trocar storage e identidade pelo `BrainMemoryStore`.

### 5.3 Observabilidade

`codex-rs/otel` integra logs, traces e métricas via OpenTelemetry/OTLP, `tracing` e `tracing-opentelemetry`. `rollout-trace` e `state` dão rastreabilidade local; há `audit.rs`, `telemetry.rs`, métricas de uso para skills/memória e eventos de sandbox violations. Spans são colocados em operações assíncronas importantes, inclusive preparação de sampling, coleta pós-sampling e `run_turn`.

**Implementado:** instrumentação, exporters/configuração, logs de rollout, auditoria local e métricas por extensão.  
**Caveat:** não há, apenas pela existência do crate, garantia de que telemetria esteja sempre habilitada, completa, redigida ou com retenção adequada; SDK/host e flags controlam o comportamento. O Brain deve implementar redaction, cardinalidade, correlação por tenant/thread/turn e política de opt-in.

### 5.4 Workflows e qualidade

`.github/workflows/README.md` distingue PR de pós-merge: Bazel é o caminho principal de verificação Rust em PR; Cargo mantém fmt/shear/lint e o full matrix ocorre após merge. Há workflows para `bazel test`, `bazel clippy`, Cargo fmt/test/nextest/clippy, cross-platform Linux/macOS/Windows, Python/TypeScript SDKs, releases, V8, voz, `cargo-deny`, codespell, blob-size, CLA e checks de repositório [7]. A ação checkout é pinada por SHA em vários workflows, o que é boa prática.

**Implementado:** testes unitários, integração, snapshots, MCP conformance, schemas, multi-OS, Bazel/Cargo e releases.  
**Limite:** BuildBuddy/RBE, secrets, credenciais, hardware de voz, Windows/macOS e serviços reais não são reproduzíveis no sandbox do Brain; alguns testes explicitamente detectam limitações de sandbox. O Brain deve começar por um subset Cargo e testes de contrato, e depois adicionar testes de policy/sandbox em runners dedicados.

## 6. Pontos fortes

1. **Separação de responsabilidades.** Segurança, execução, protocolo, estado e extensões têm crates próprios; isso oferece bons seams para absorção incremental.
2. **Contrato público forte.** App-server v2, JSON schemas e SDKs gerados reduzem integração ad hoc e permitem clientes Python/TypeScript.
3. **Segurança em camadas.** Profiles, approvals, filesystem, network proxy, Landlock/seccomp/bubblewrap, Seatbelt e Windows restricted token cobrem mais de um mecanismo de defesa.
4. **Tooling extensível.** MCP, tool discovery, plugin manifests, Skills e connectors permitem capability injection sem hard-code de cada integração no núcleo.
5. **Memória com invariantes úteis.** Lock global, seleção bounded, baseline Git e promoção após sucesso são padrões adequados para consolidação concorrente.
6. **Persistência operacional.** Threads, rollouts, filas, recovery, anexos, goals, logs e migrações formam uma base para sessões longas.
7. **Disciplina de engenharia.** AGENTS exige testes de integração para mudanças de lógica, schemas alinhados, limites de contexto e compatibilidade multi-OS [2].
8. **Observabilidade nativa.** Tracing, OTEL, audit e rollout events são mais úteis do que logs textuais isolados.
9. **Cobertura de testes e fixtures.** Há testes nos principais crates e conformance MCP, além de snapshots TUI e contratos de SDK.
10. **Cross-platform explícito.** Linux, macOS e Windows possuem implementações, workflows e testes específicos, em vez de um único caminho Linux presumido.

## 7. Limites e riscos

### 7.1 Arquitetura e operação

- **Monorepo muito grande.** A quantidade de crates, vendors, schemas e workflows eleva custo de build, revisão e atualização. Absorver tudo aumentaria superfície de supply chain e dificultaria ownership.
- **`codex-core` é grande.** O próprio projeto alerta contra seu crescimento; depender diretamente dele tende a puxar TUI, login, providers e tipos internos.
- **Duplicidade Cargo/Bazel/pnpm/uv.** Quatro ecossistemas e lockfiles tornam o pinning complexo; a mesma dependência pode ter resoluções diferentes.
- **Protocolos v1/v2 e experimental.** Compatibilidade legada, flags e namespaces podem gerar divergência. Novas interfaces devem usar v2, mas o Brain precisa versionar seu adaptador.
- **Dependência de backend OpenAI/Responses API.** O runtime não é um motor de modelo genérico isolado; provedor, login, metadata e streaming assumem contratos OpenAI em partes importantes.
- **Integrações host-owned.** Apps, connectors, cloud tasks, remote control, guardian e user verification podem exigir backend, identidade ou hardware que não está contido no checkout.

### 7.2 Segurança

- **Acesso irrestrito implementado.** `danger-full-access`, `approval_policy=never`, shell e plugins podem causar destruição ou exfiltração se combinados.
- **MCP é código e rede.** Servidores locais/HTTP podem receber dados de contexto, pedir OAuth, executar ações e retornar conteúdo adversarial. Catalogação e trusted access precisam de allowlist por servidor/tenant.
- **Prompt injection por workspace, memória e tool output.** O modelo pode tratar instrução encontrada em arquivo, MCP, skill ou memória como comando. Brain precisa de marcação de origem e política de não confiança.
- **Hooks privilegiados.** Hooks podem rodar antes/depois de ferramentas e tocar decisões; a interface não é segura por si só.
- **Segredos e logs.** Rollouts, state DB, auditoria e OTEL podem conter prompts, código, tokens ou caminhos. Exigir redaction e criptografia em repouso.
- **Processos cross-platform.** Bubblewrap/Landlock/Seatbelt/restricted token têm diferenças de kernel, permissões e compatibilidade; uma falha deve resultar em deny/fail closed, não em fallback silencioso para host.
- **Execução remota/IPC.** UDS, WebSocket, app-server e exec-server exigem autenticação, autorização por thread e defesa contra confused deputy.
- **Subagentes.** Collaboration, guardian e memory consolidator podem criar ciclos, ampliar contexto ou herdar ferramentas; o código possui flags/desabilitações pontuais, mas o Brain deve impor orçamento global e depth limit.
- **Atualizações e install scripts.** O README recomenda `curl | sh`/PowerShell e gerenciadores; para absorção interna usar artefatos verificados, hashes/SLSA e nunca executar instaladores externos como parte de bootstrap privilegiado.

### 7.3 Licença e supply chain

- O projeto principal declara Apache-2.0 [1] [10]. Apache permite uso/modificação/redistribuição com preservação de licença, notices e atribuições, mas não concede marcas OpenAI e não traz garantia.
- `NOTICE` e diretórios `third_party` devem acompanhar qualquer distribuição derivada. `third_party/wezterm/LICENSE` é MIT; `third_party/voice/licenses/` inclui LGPL-2.1, Opus, PCRE2, libffi, zlib e outros avisos. O `third_party/voice/NOTICE.md` informa que releases de voz carregam bibliotecas dinâmicas e que o Windows inclui runtime Microsoft com termos separados.
- A presença de LGPL em voz exige revisão de linking, avisos, disponibilização de fontes/objetos e substituibilidade conforme o modo de distribuição do Brain. O runtime MSVC não é licenciado pela Apache/LGPL e tem termos próprios.
- `codex-rs/deny.toml` e lockfiles ajudam, mas não substituem inventário SBOM, licença transitiva e auditoria de CVEs. Dependências Git (por exemplo MCP conformance pinado no SDK TypeScript) e downloads/artefatos de releases exigem pinning, hash e revisão.
- O Brain não deve reutilizar nome, logo ou marca Codex/OpenAI sem avaliação jurídica; a licença não é licença de marca.

## 8. Recomendações de absorção com prioridade, interfaces e dependências

### P0 — obrigatório antes de qualquer produção

| Prioridade | Ação | Interface proposta | Dependências/critério de saída |
|---|---|---|---|
| P0.1 | Fixar uma revisão e gerar SBOM/licenças | `BrainCodexPin { commit, cargo_lock, module_lock, pnpm_lock, uv_lock }` | `LICENSE`, `NOTICE`, `third_party`, cargo-deny, Syft/Trivy ou equivalente; aprovação jurídica |
| P0.2 | Colocar o runtime atrás de adaptador | `BrainAgentAdapter::start_thread`, `run_turn`, `interrupt`, `resume`, `stream_events` | `codex-core`, `protocol`, `app-server-protocol`; nenhum tipo TUI atravessa a fronteira |
| P0.3 | Implementar policy broker do Brain | `PolicyDecision { approval, sandbox, roots, network, reason, expires_at }` | `execpolicy`, `sandboxing`, `config`; deny-by-default e fail-closed |
| P0.4 | Definir identidade/tenancy | `BrainPrincipal`, `ThreadScope`, `ToolPrincipal` | login/keyring/app-server; proibir acesso cruzado a `state`, rollouts e memória |
| P0.5 | Redigir persistência | `BrainStateStore` e `BrainMemoryStore` | `state`, `thread-store`, `memories`; criptografia, retenção e deleção auditável |
| P0.6 | Isolar MCP/plugins/hooks | `CapabilityRegistry` com trust/allowlist e `HookSandbox` | `codex-mcp`, `plugin`, `hooks`, `ext/skills`; nenhum plugin herda permissões por default |

### P1 — núcleo de execução e contrato

| Ação | Interface | Dependências |
|---|---|---|
| Adotar eventos do app-server v2 | `ThreadStarted`, `TurnStarted`, `ToolCall`, `ApprovalRequested`, `ToolResult`, `TurnCompleted`, `Error` | `app-server-protocol`, schemas v2, transport |
| Adotar tool loop sem TUI | `ToolSpec`, `ToolCall`, `ToolOutput`, `ToolExposure` | `codex-tools`, partes de `core` |
| Adotar execução shell/patch segura | `ExecRequest`, `PatchRequest`, `ExecResult`, `PatchResult` | `exec`, `apply-patch`, `execpolicy`, `sandboxing` |
| Adotar context budget | `ContextFragment { origin, trust, tokens, expiry }` | `core/context`, `protocol`; hard caps e redaction |
| Adotar persistência de rollout | `RolloutWriter/Reader` ou tradução para store do Brain | `rollout`, `thread-store`, `state` |
| Criar conformance suite do Brain | testes de contrato JSON e golden events | schemas v2, SDKs Python/TS; CI Linux inicial |

### P2 — extensibilidade e memória

| Ação | Interface | Dependências |
|---|---|---|
| Incorporar Skills | `SkillManifest`, `SkillProvider`, `SkillSelection` | `skills`, `ext/skills`, `plugin`; assinatura, origem e limites |
| Incorporar hooks seguros | eventos v2 + `HookResult` limitado | `hooks`; runner sandboxed, timeout e output cap |
| Incorporar Phase 1/2 | `MemoryStage1Record`, `Phase2Selection`, `MemoryDiff`, `ConsolidationJob` | `memories/read`, `memories/write`, `state`; lock distribuído se multi-worker |
| Incorporar tool discovery | catálogo lazy + busca por namespace | `tools/tool_search`, `codex-mcp`; evitar tool poisoning |
| Incorporar colaboração | `AgentChild { parent, depth, budget, permissions }` | `agent-graph-store`, `agent-roles`, collaboration templates; depth/budget obrigatório |

### P3 — capacidades de produto e plataformas

Manter fora do primeiro núcleo: TUI, voice-host/GStreamer, V8 sandbox POC/release, Windows service elevado, realtime WebRTC, desktop user verification, cloud tasks, remote control, marketing/release installers e integração BuildBuddy. Reavaliar cada um como plugin ou worker específico após o core passar pela revisão P0/P1.

## 9. Dependências e interfaces recomendadas

O recorte mínimo sugerido é:

```text
Brain API / SDK
        |
  BrainCodexAdapter  ---- app-server-protocol v2 / transport
        |
  codex-core turn loop ---- codex-tools / protocol / model provider
        |
  PolicyBroker ---------- execpolicy + sandboxing + network-proxy
        |
  CapabilityRegistry ---- skills + plugin + MCP + hooks
        |
  State/Memory adapters -- state + thread-store + rollout + memories
        |
  Observability ---------- tracing + codex-otel + audit events
```

A regra de dependência deve ser **unidirecional**: UI/SDK dependem do adaptador; o adaptador depende de contratos; contratos não dependem da TUI; plugins não chamam diretamente o banco; ferramentas não escolhem sua própria permissão; memória não reutiliza hooks do usuário; observabilidade não pode alterar decisão de execução. O Brain deve preferir copiar/adaptar tipos de fronteira estáveis a exportar dezenas de tipos internos de `codex-core`.

## 10. Veredito para a Fase 2

**Absorver agora:** protocolo app-server v2 e schemas; modelo de eventos/turno; interfaces de tools; policy/sandbox como referência; padrões de state/rollout; semântica Phase 2 de memória; hooks tipados; MCP com trust explícito; OTEL/audit com redaction.  
**Absorver com adaptação:** `codex-core`, login/provider, thread-store, Skills, plugins e colaboração.  
**Deixar como dependência opcional:** TUI, voice, V8, Windows elevated service, realtime, cloud/remote-control, installers e CI BuildBuddy.  
**Não copiar sem revisão:** conteúdo de skills/prompts, scripts de instalação, secrets/telemetria bruta, `.codex`/SQLite de usuário, vendors e artefatos de release.

O repositório é uma referência excepcional para execução agentiva local e controle de ferramentas. Ele deve ser tratado como **plataforma de componentes versionados**, não como uma biblioteca pequena. A absorção segura exige pinning, adaptadores, policy broker do Brain, isolamento de extensão, memória tenant-aware, contrato v2 e testes de negação além dos testes funcionais.

## Referências

[1]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/README.md "README oficial do openai/codex"
[2]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/AGENTS.md "Políticas de engenharia e extensão do repositório"
[3]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/Cargo.toml "Workspace Rust e dependências do Codex"
[4]: https://github.com/openai/codex/tree/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/core "Crate codex-core"
[5]: https://github.com/openai/codex/tree/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/app-server-protocol "App-server protocol, schemas e contratos"
[6]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/MODULE.bazel "Módulo Bzlmod do repositório"
[7]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/.github/workflows/README.md "Estratégia de workflows e validação CI"
[8]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/docs/protocol_v1.md "Documentação de fluxo e protocolo v1"
[9]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/memories/README.md "Arquitetura implementada de memória Phase 1/Phase 2"
[10]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/LICENSE "Licença Apache-2.0"
[11]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/NOTICE "Avisos e atribuições do projeto"
[12]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/SECURITY.md "Política de segurança e divulgação"
[13]: https://github.com/openai/codex/tree/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/skills "Crates e assets de Skills"
[14]: https://github.com/openai/codex/tree/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/hooks "Hooks, engine e schemas gerados"
[15]: https://github.com/openai/codex/tree/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/memories "Leitura e escrita de memória"
[16]: https://github.com/openai/codex/tree/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/state "Persistência, migrações, logs e runtime state"
[17]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/deny.toml "Política cargo-deny"
[18]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/third_party/voice/NOTICE.md "Avisos de bibliotecas nativas de voz e runtime Microsoft"
[19]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/sdk/typescript/package.json "Manifesto e testes do SDK TypeScript"
[20]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/sdk/python/pyproject.toml "Manifesto, dependências e testes do SDK Python"
[21]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/docs/bazel.md "Build Bazel, RBE e lockfile"
[22]: https://github.com/openai/codex/tree/53ff712a48379ce8df605e292afd6046ca88ae9b/third_party "Código de terceiros, avisos e licenças"
[23]: https://github.com/openai/codex/tree/53ff712a48379ce8df605e292afd6046ca88ae9b/.github/workflows "Workflows oficiais de CI e release"
[24]: https://github.com/openai/codex/tree/53ff712a48379ce8df605e292afd6046ca88ae9b/sdk "SDKs, exemplos e testes públicos"
[25]: https://github.com/openai/codex/blob/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/memories/write/src/phase2.rs "Implementação do worker de memória Phase 2"
[26]: https://github.com/openai/codex/tree/53ff712a48379ce8df605e292afd6046ca88ae9b/codex-rs/app-server "Servidor local app-server e documentação de RPC"

> **Caveat de atualidade:** `main` é ativo e o README/release metadata pode mudar depois do commit analisado. As referências deste relatório estão pinadas ao SHA informado quando o caminho é navegável no GitHub; qualquer absorção deve repetir a auditoria no commit candidato e comparar lockfiles, schemas, `NOTICE`, workflows e migrações.

**Autor:** Manus AI

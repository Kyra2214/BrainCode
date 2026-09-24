# Fase B — anomalyco/opencode: tecnologias, ferramentas e técnicas

> Reanálise (v2): foco na arquitetura real do código (packages, módulos,
> stack), não só o README. Levantamento, sem propor mudança em nada.

## 1. Stack e arquitetura geral

- **Linguagem**: TypeScript, rodando em Bun.
- **Arquitetura cliente/servidor**: API HTTP via **Hono**, o que permite
  controlar sessões remotamente (mobile, outros clientes) além do TUI local.
- **Abstração de modelo**: **Vercel AI SDK**, com suporte a mais de 75
  providers diferentes — trocar de modelo/provider é configuração, não
  reescrita de integração.
- **TUI**: framework próprio, **OpenTUI** (biblioteca TypeScript separada,
  mantida pelo mesmo grupo, com reconciladores para SolidJS e React além da
  API imperativa pura; precisa de Zig instalado pra buildar).
- **Persistência de sessão**: SQLite.
- **App desktop**: Tauri (macOS/Windows/Linux).
- **Runtime interno**: **Effect-TS** — não é só uma lib de utilitário, é a
  espinha dorsal: `Effect`, `Layer`, `Context` organizam serviços (ex.:
  `Service extends Context.Service<...>`), com gerenciamento de erros
  tipado (`Effect.catchReason`) e composição de camadas (`Layer.effect`).

## 2. Estrutura de pacotes (monorepo)

```
packages/
├── opencode/     # núcleo: CLI (Yargs, 21 comandos), agentes, sessão, tools, provider
├── tui/          # terminal UI
├── web/          # app desktop (Tauri)
└── opencode-ai/  # camada de abstração de modelo
```

Dentro de `opencode/src/`: `agent/` (definições + registro de agentes),
`session/` (ciclo de vida + loop do LLM), `provider/` (abstração
multi-provider), `tool/` (registro de tools).

## 3. Modos de agente (built-in)

- **build**: acesso total a tools (padrão).
- **plan**: somente leitura, pede permissão antes de agir.
- **general**: subagente para tarefas complexas.
- **explore**: navegação de código somente leitura.
- **scout**: pesquisa de repositório e busca de documentação.

Cada agente tem seu próprio *ruleset* de permissão (`agent.ts` define os 7
agentes built-in com permission rulesets próprios).

## 4. Loop de sessão (`session/`)

- `prompt.ts` — `SessionPrompt.loop()`, o loop externo do agente.
- `processor.ts` — `SessionProcessor`, consumo de stream + despacho de
  tools.
- `llm.ts` — `LLM.stream()`, wrapper sobre o Vercel AI SDK.
- `message-v2.ts` — modelo de mensagem com 12 tipos de "part" diferentes.
- `compaction.ts` — compactação de janela de contexto quando a sessão
  cresce demais.
- `retry.ts` — lógica de retry para rate limit e erros transitórios.
- `revert.ts` — **rollback de sistema de arquivos por passo** — cada
  passo da sessão pode ser revertido individualmente, não só a sessão
  inteira.
- `summary.ts` — geração de resumo de sessão.

## 5. Registro de tools (`tool/registry.ts`)

Tools built-in: `bash`, `edit`, `glob`, `grep`, `read`, `write`, `task`
(delega pra subagente), `todo` (write), `webfetch`, `websearch`,
`codesearch`, `lsp`, `apply_patch`, `question`, `plan-exit`, `skill`,
`invalid` (fallback). Cada tool é convertida para o padrão
`Tool.defineEffect(...)`, unificando I/O determinístico (ex.: `bash` com
`ChildProcessSpawner`) sob o mesmo modelo de efeito tipado.

Tools de terceiros entram via um `ToolDefinition` de plugin
(`@opencode-ai/plugin`), então o registro central mistura tools nativas e
tools de plugin sob a mesma interface.

## 6. Sistema de permissão (`PermissionV1.Ruleset`)

Consultado na montagem do system prompt junto com a lista de Skills
disponíveis (`Skill.Service`) e servidores MCP (`MCP.Service`) — os três
juntos moldam o que aquele agente específico pode fazer naquela sessão.
Conforme já registrado no README de segurança do próprio projeto: esse
sistema é uma camada de UX (avisa antes de agir), **não** um mecanismo de
isolamento — para isolamento real a recomendação é rodar dentro de
Docker/VM.

## 7. Núcleo de sessão V2 (do próprio AGENTS.md do repositório)

- Admissão de prompt (`SessionV2.prompt(...)`) é uma gravação durável de
  `session_input`, separada do agendamento de execução
  (`SessionExecution.wake(sessionID)`), a menos que `resume: false` peça
  comportamento "admit-only".
- Reuso de Session ID readota a sessão existente; reuso de ID de prompt só
  reconcilia em retry exato quando sessão, prompt e modo de entrega
  coincidem.
- Vocabulário de entrega explícito: uma entrada nova por padrão
  **direciona** (steer) a execução atual; uma entrada explicitamente
  enfileirada fica pendente até a sessão ficar ociosa, promovida uma de
  cada vez.
- Serviços (`SessionRunner`, resolução de modelo, registro de tools,
  permissões, filesystem) são escopados por `Location` — omitir
  `Location.workspaceID` assume posicionamento local implícito.

## 8. Prompts por provider

O sistema de prompt (`session/system.ts`) escolhe um template de prompt
diferente por família de modelo (`PROMPT_ANTHROPIC`, `PROMPT_GEMINI`,
`PROMPT_GPT`, `PROMPT_KIMI`, `PROMPT_CODEX`, `PROMPT_TRINITY`,
`PROMPT_BEAST`, `PROMPT_DEFAULT` como fallback) — reconhecendo que
modelos de famílias diferentes respondem melhor a formulações de
instrução diferentes, em vez de um único prompt genérico pra todos.

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- Effect-TS como runtime de composição de serviços tipados (Layer/Context/
  Effect) — alternativa a promises/async puro para lidar com erro e efeito
  de forma mais rastreável.
- Padrão `Tool.defineEffect` para unificar tools nativas e de plugin sob a
  mesma interface.
- Rollback por passo (`revert.ts`) — reverter uma ação específica da
  sessão, não só a sessão inteira.
- Vercel AI SDK como camada de abstração multi-provider já testada em
  produção (75+ providers).
- Prompt de sistema variando por família de modelo, não um prompt único.
- Separação explícita entre "admitir pedido" (durável) e "agendar
  execução" (efêmero/advisory), com vocabulário de steer vs queue.

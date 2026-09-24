# Fase 2 — Análise de `anomalyco/opencode`

**ID:** `02-02-anomalyco-opencode`  
**Papel:** referência arquitetural para o Brain  
**Repositório:** [`anomalyco/opencode`](https://github.com/anomalyco/opencode)  
**Commit analisado:** [`95daf90670b7c039c436c85537da5fbfe2205b41`](https://github.com/anomalyco/opencode/commit/95daf90670b7c039c436c85537da5fbfe2205b41)  
**Data do commit:** 2026-09-11  
**Método:** clone local da branch `dev`, leitura de manifestos, código TypeScript/TSX, documentação MD/MDX, exemplos, testes, especificações, workflows e configurações; validação das afirmações operacionais em fontes primárias versionadas no GitHub.

> **Conclusão executiva.** OpenCode é um agente de programação open source, local-first, com TUI, aplicativo desktop/web, servidor headless, extensões IDE, SDK, MCP, ACP e automação GitHub. O núcleo está migrando de uma arquitetura legada para serviços Effect-native, API HTTP tipada e modelos V2 duráveis. A implementação é ampla e madura em ferramentas, sessões, provedores, permissões, plugins, eventos e testes, mas o próprio repositório registra que a superfície V2 ainda está em reconstrução: o sistema de plugins V2 é um plano de implementação, a UI conserva adaptadores V1, a recuperação pós-crash durável é adiada e a execução não é sandbox de segurança. Para o Brain, a absorção recomendada é **seletiva**: adotar contratos de ferramentas, permissões, sessões, eventos, SDK e plugins como referências; não copiar o loop inteiro nem assumir que `ask` equivale a isolamento.

## 1. Escopo, evidência e limites da análise

A análise foi feita sobre o clone local do commit acima, sem alterar o repositório. O inventário encontrou **6.625 arquivos rastreados fora de `.git`** no estado clonado, **40 `package.json`**, **26 workflows**, **39 arquivos em `.opencode/`**, **14 especificações em `specs/`**, aproximadamente **779 arquivos Markdown/MDX** incluindo traduções e **741 arquivos de teste com convenções `.test`/`.spec`**, além de testes adicionais identificados por diretórios `test`, `e2e` e fixtures. O número de testes varia conforme o critério de contagem; não deve ser interpretado como número de casos executáveis.

“Arquivo por arquivo” é tratado aqui como inventário individual de todos os arquivos que definem arquitetura, interfaces, execução, configuração, documentação, exemplos, testes, especificações e automação. Arquivos gerados, snapshots, assets, traduções repetidas e componentes puramente visuais são agrupados por função, com seus diretórios e caminhos explícitos. Isso evita listar milhares de arquivos equivalentes sem perder a rastreabilidade.

A análise é estática. Não executei a matriz completa de `bun turbo test`, os E2E do Playwright, builds de todas as plataformas, servidores remotos ou fluxos OAuth. Portanto, “implementado” significa **presente e conectado no código/documentação do commit**, não “validado em produção”. “Planejado” significa marcado como plano, TODO, experimental, migratório ou explicitamente adiado pelo próprio repositório.

## 2. Arquitetura geral

### 2.1 Camadas e responsabilidades

| Camada | Arquivos/diretórios principais | Responsabilidade e estado |
|---|---|---|
| Entrada CLI | [`packages/opencode/src/index.ts`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/opencode/src/index.ts), [`packages/cli/src/index.ts`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/cli/src/index.ts) | Registra comandos `run`, TUI, `serve`, `acp`, `mcp`, `github`, `models`, `providers`, import/export, sessão, plugin e depuração. Implementado. |
| Experiência cliente | [`packages/tui`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/tui), [`packages/app`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/app), [`packages/desktop`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/desktop), [`sdks/vscode`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/sdks/vscode) | TUI OpenTUI/Solid, web/desktop Solid/Vite/Electron e terminal VS Code. Implementado, com migração V1/V2 ainda híbrida na app. |
| Domínio/runtime | [`packages/core/src`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/core/src), [`packages/opencode/src`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/opencode/src) | Sessões, agentes, modelos, provedores, ferramentas, permissões, filesystem, Git, LSP, MCP, plugins, instruções e persistência. Implementado em grande parte; partes V2 em evolução. |
| API e protocolo | [`packages/protocol`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/protocol), [`packages/server`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/server) | Grupos HTTP tipados, OpenAPI, eventos, autorização, handlers de sessão, mensagem, modelo, provider, PTY, skill e permission. Implementado; há endpoints/adaptadores legados. |
| Extensibilidade | [`packages/plugin`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/plugin), `.opencode/plugins`, `.opencode/tool`, `.opencode/skills` | Plugins locais/npm, hooks, custom tools, skills, comandos e temas. API atual implementada; API V2 Effect-native planejada em [`PLAN.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/plugin/src/v2/effect/PLAN.md). |
| Integrações | `packages/llm`, `packages/client`, `packages/sdk`, `packages/sdk-next`, `packages/codemode`, `packages/slack`, `sdks/vscode` | AI SDK/provedores, cliente gerado, SDK público, CodeMode confinado, Slack e VS Code. Implementado em diferentes graus de estabilidade. |
| Distribuição/operação | `infra`, `sst.config.ts`, `nix`, `install`, `packages/containers`, `.github/workflows` | Releases, Nix, contêineres, deployment SST, GitHub Action, docs e observabilidade. Implementado; depende de credenciais e serviços externos. |

### 2.2 Fluxo operacional implementado

1. A CLI lê argumentos com `yargs` em `packages/opencode/src/index.ts`, resolve versão, logging e comando.
2. A localização do projeto resolve diretório, worktree Git, configuração global/projeto, `AGENTS.md`/`CLAUDE.md`, skills, plugins, comandos, providers e integrações.
3. O core monta serviços Effect e um catálogo de agentes, ferramentas, modelos, permissões, LSP, MCP, filesystem, Git, sessão e eventos.
4. A sessão recebe texto, referências `@arquivo`, comandos slash ou entradas ACP/SDK. O prompt é enriquecido com instruções de projeto, histórico, estado, ferramentas e regras do agente.
5. O agente seleciona modelo/provedor e executa o loop de geração. O registry expõe ferramentas nativas, ferramentas customizadas e ferramentas MCP; cada chamada passa pela resolução de permissão e pelos hooks.
6. `bash`, edição, escrita, patch, leitura, busca, LSP, web, skills, subagentes, perguntas e TODOs retornam partes/eventos para a sessão e para a TUI/app/cliente.
7. A sessão persiste mensagens, partes, tool calls, tool results, diffs, TODOs e metadados no armazenamento local; a compactação pode resumir o contexto quando a janela fica cheia.
8. O servidor expõe a mesma execução por HTTP/OpenAPI/SSE/WebSocket/PTY conforme o grupo. Clientes acompanham eventos e respondem a permissões/perguntas.
9. O fluxo pode continuar em ACP (`opencode acp`), SDK, desktop, VS Code ou GitHub Actions. No GitHub, a Action executa no runner do consumidor e pode comentar, abrir branch/PR ou alterar o repositório conforme os tokens concedidos.[1] [2]

## 3. Inventário arquivo a arquivo dos componentes importantes

### 3.1 Raiz, governança e manifestos

| Caminho | Conteúdo | Classificação |
|---|---|---|
| [`README.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/README.md) | Identidade do projeto, instalação por curl/npm/bun/pnpm/yarn/scoop/choco/Homebrew/pacman/Nix, desktop beta, agentes `build`/`plan` e `general`, documentação e contribuição. | Implementado/documentado. |
| [`README.br.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/README.br.md) e demais `README.*.md` | Traduções do README, inclusive português brasileiro. | Conteúdo documentacional; não cria runtime separado. |
| [`LICENSE`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/LICENSE) | MIT, copyright 2025 opencode, sem garantia. | Implementado; compatível com absorção, sujeito à preservação de aviso. |
| [`SECURITY.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/SECURITY.md) | Threat model. Declara explicitamente “No Sandbox”; permissões são UX, não isolamento. Servidor sem senha fica não autenticado; MCP/provedores ficam fora da confiança do projeto. | Implementado como política; limite crítico de segurança. |
| [`CONTRIBUTING.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/CONTRIBUTING.md) | Processo de contribuição, estilo e validações. | Implementado/documentado. |
| [`AGENTS.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/AGENTS.md) | Instruções de trabalho do próprio repositório para agentes. | Implementado como regra de projeto. |
| [`CONTEXT.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/CONTEXT.md) | Contexto de desenvolvimento e orientação de manutenção. | Implementado/documental. |
| [`package.json`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/package.json) | Workspace Bun/Turbo, versão `1.18.30`, licença MIT, dependências `@opencode-ai/*`, TypeScript/Bun/SST, `trustedDependencies`, patches de dependências e scripts globais. | Implementado; ponto de supply chain. |
| [`bun.lock`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/bun.lock) | Lockfile grande com versões exatas e workspaces. | Implementado; deve ser mantido em absorção. |
| [`bunfig.toml`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/bunfig.toml) | Instalação exata, atraso mínimo de três dias para pacotes novos e exceções para pacotes de runtime. | Implementado; bom controle, mas exceções precisam revisão. |
| [`turbo.json`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/turbo.json) | Grafo de typecheck/build/test, dependências de build e propagação de ambiente. | Implementado. |
| [`tsconfig.json`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/tsconfig.json) | Base TypeScript para Bun. | Implementado. |
| [`flake.nix`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/flake.nix), [`flake.lock`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/flake.lock), `nix/` | Distribuição/reprodução por Nix e scripts auxiliares. | Implementado. |
| [`sst.config.ts`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/sst.config.ts), `infra/` | Infraestrutura cloud, ambientes dev/produção, integração com AWS/Cloudflare/PlanetScale/Stripe/Sentry/Honeycomb. | Implementado para a operação do projeto; dependências externas. |
| `install`, `script/`, `github/`, `patches/`, `perf/`, `artifacts/` | Instalador, scripts de build/release/geração, Action GitHub, patches de terceiros, benchmarks/performance e artefatos. | Implementado, com partes específicas do projeto. |
| `.editorconfig`, `.gitattributes`, `.gitignore`, `.gitleaksignore`, `.prettierignore`, `.oxlintrc.json`, `.husky/`, `.vscode/`, `.zed/` | Formatação, lint, hooks locais, ignore de segredos e configuração de editores. | Implementado. |

### 3.2 Pacotes e manifestos

| Pacote | Papel observado no `package.json` e código | Estado para absorção |
|---|---|---|
| [`packages/opencode`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/opencode) | Produto CLI/runtime; comandos, TUI, servidor interno, ferramentas, sessão e provider integration. | Núcleo funcional; absorver por interfaces, não copiar tudo. |
| [`packages/core`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/core) | Serviços Effect, configuração, agentes, catálogo, sessão, banco/storage, eventos, Git, FS, providers e observabilidade. | Principal referência de domínio e efeitos. |
| [`packages/server`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/server) | HTTP API, autorização, rotas, handlers, PTY e localização por request. | Interface de integração prioritária. |
| [`packages/protocol`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/protocol) | Grupos e schemas HTTP/OpenAPI, middleware e erros. | Contrato estável a extrair. |
| [`packages/plugin`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/plugin) | Tipos, plugin loader, hooks atuais e APIs V2 Promise/Effect. | Usar como contrato; V2 ainda não tratar como final. |
| [`packages/cli`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/cli) | Framework/serviços de CLI e daemon. | Referência de shell/daemon; dependência opcional. |
| [`packages/tui`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/tui) | Interface terminal baseada em OpenTUI/Solid, keymaps, editor e dialogs. | Absorver UX/estado, não renderer inteiro inicialmente. |
| [`packages/app`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/app) | App web/desktop, estado de sessão, router, prompt, browser tests e Playwright E2E. | Implementado, mas híbrido V1/V2. |
| [`packages/desktop`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages/desktop) | Empacotamento Electron e integração desktop. | Produto/distribuição, não dependência do Brain. |
| [`packages/ui`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/ui) | Componentes Solid, markdown, diff, Shiki, temas e animação. | Reaproveitar apenas componentes desejados. |
| [`packages/session-ui`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/session-ui) | Componentes compartilhados de sessão. | Candidato a adaptação visual. |
| [`packages/client`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/client) | Cliente gerado/consumo da API. | Interface recomendada para consumidores. |
| [`packages/sdk/js`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/sdk/js) | SDK público com exports V1/V2, cliente e servidor; geração OpenAPI. | Interface primária para o Brain. |
| [`packages/sdk-next`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/sdk-next) | SDK/embedding experimental com `core`, `server` e Effect. | Planejar compatibilidade; não acoplar ainda. |
| [`packages/llm`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/llm) | Adaptadores/loop de modelos e ferramentas. | Há itens de remoção planejados no TODO V2. |
| [`packages/codemode`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/codemode) | Execução de código Effect-native sobre catálogo de ferramentas descritas por schema, com limites e diagnósticos. | Forte referência de confinamento lógico, não sandbox de processo. |
| [`packages/schema`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/schema) | Schemas compartilhados. | Dependência de contratos. |
| [`packages/function`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/function) | Utilitários/abstrações funcionais. | Interno. |
| [`packages/effect-drizzle-sqlite`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/effect-drizzle-sqlite), [`effect-sqlite-node`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/effect-sqlite-node) | Integrações Effect/Drizzle/SQLite. | Persistência de referência. |
| [`packages/httpapi-codegen`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/httpapi-codegen), [`http-recorder`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/http-recorder) | Geração e gravação/exercício de API HTTP. | Ferramentas de contrato/teste. |
| [`packages/containers`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/containers) | Dockerfiles base Bun/Node, Rust, publish e Tauri. | Operação/reprodução. |
| [`packages/enterprise`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/enterprise) | Integrações/funcionalidades enterprise. | Separar do núcleo se não houver requisito. |
| [`packages/console`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/console), [`identity`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/identity), [`stats`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/stats) | Console cloud, identidade, métricas e backfill Honeycomb. | Operação do fornecedor; não necessário para Brain local. |
| [`packages/slack`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/slack) | Bot/integração Slack. | Integração opcional. |
| [`packages/storybook`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/packages/storybook) | Catálogo visual e acessibilidade de UI. | Ferramenta de UI. |
| [`sdks/vscode`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c85537da5fbfe2205b41/sdks/vscode) | Extensão VS Code que abre terminal OpenCode e injeta caminhos. | Adaptador de IDE. |

### 3.3 Código de runtime: arquivos e interfaces

| Caminho | Achado principal |
|---|---|
| [`packages/opencode/src/index.ts`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/opencode/src/index.ts) | Entrada `yargs`; registra `run`, `generate`, account, providers, agent, upgrade, uninstall, models, `serve`, debug, stats, MCP, GitHub, import/export, attach, TUI, ACP, web, PR, sessão, DB e plugin. |
| `packages/opencode/src/cli/cmd/run/*` | Runtime de `opencode run`, lifecycle, footer de permissões e execução direta; é o caminho operacional para automação sem TUI. |
| `packages/opencode/src/cli/cmd/acp/*` e [`packages/web/src/content/docs/acp.mdx`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/web/src/content/docs/acp.mdx) | Subprocesso JSON-RPC via stdio para editores ACP. Built-ins, tools, skills, MCP, regras e permissões são suportados; `/undo` e `/redo` são exceções documentadas. |
| `packages/opencode/src/server/*`, `packages/opencode/src/server/routes/*`, `packages/opencode/src/server/handlers/*` | Servidor legado/compatibilidade dentro do produto CLI, além do pacote server Effect. Deve ser isolado durante absorção por causa da migração. |
| `packages/opencode/src/session/session.ts` | Read model e operações de sessão, histórico, listagem, children, status, revert, fork, abort e compact. |
| `packages/opencode/src/session/message-v2.ts` | Paginação/hidratação de mensagens e partes V2, usada na montagem de prompts e APIs. |
| `packages/opencode/src/session/prompt.ts`, `system.ts`, `tools.ts`, `reminders.ts`, `retry.ts`, `run-state.ts`, `status.ts` | Construção do prompt, sistema, seleção de ferramentas, lembretes, retry, estado de execução e status. |
| `packages/opencode/src/session/summary.ts` | Resumo/compactação de histórico e diffs; persistência de usage/diffs. |
| `packages/opencode/src/session/instruction.ts` | Resolve instruções de `AGENTS.md`, `CLAUDE.md`, arquivos referenciados e contexto por caminho. |
| `packages/opencode/src/session/todo.ts` | TODO durável e projeções. O subagente geralmente não pode usar `todowrite` por política padrão. |
| `packages/opencode/src/storage/storage.ts`, `storage/schema.ts`, `storage/migration/*` | Armazenamento local JSON/SQLite em migração, locks por chave, migrations, sessions/messages/parts/diffs e compatibilidade histórica. |
| `packages/core/src/agent/*` | Modelo, configuração e registro de agentes; defaults built-in, modos `primary`/`subagent`, hidden agents e permission merge. |
| `packages/core/src/permission/*` | Regras globais/por agente, resolução `allow`/`ask`/`deny`, matching de recurso e guards de directory/tool. |
| `packages/opencode/src/tool/registry.ts` | Agrega ferramentas built-in, plugins, MCP, CodeMode e visibilidade pela ruleset de permissões. |
| `packages/opencode/src/tool/bash.ts`, `shell.ts`, `shell/*` | Execução de shell, parsing de comando, prompt de segurança, ambiente e restrições de aprovação. |
| `packages/opencode/src/tool/read.ts`, `write.ts`, `edit.ts`, `apply_patch.ts` | FS de leitura e mutação; `edit`, `write` e `apply_patch` compartilham a permissão `edit`. |
| `packages/opencode/src/tool/glob.ts`, `grep.ts`, `lsp.ts` | Descoberta, busca textual e consultas LSP; LSP é documentado como experimental. |
| `packages/opencode/src/tool/skill.ts`, `task.ts`, `question.ts`, `todo.ts`, `plan.ts` | Skills, subagentes, perguntas, TODO e transição plan/build. |
| `packages/opencode/src/tool/webfetch.ts`, `websearch.ts`, `mcp-websearch.ts` | HTTP fetch com limites/timout e busca por MCP hospedado/Exa/Parallel. |
| `packages/opencode/src/tool/code-mode.ts` | Integra CodeMode para descoberta progressiva e execução sobre tools tipadas. |
| `packages/opencode/src/tool/external-directory.ts`, `truncation-dir.ts`, `truncate.ts` | Controle de caminhos externos e armazenamento de output truncado. |
| [`packages/server/src/routes.ts`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/server/src/routes.ts) | Monta a API com grupos health, location, agent, session, message, model, provider, integration, credential, permission, filesystem, command, skill, event, PTY, question, reference e project-copy. |
| `packages/server/src/handlers/*.ts` | Implementa cada grupo HTTP: agent, command, credential, event, fs, health, integration, location, message, model, permission, project-copy, provider, PTY, question, reference, session e skill. |
| `packages/server/src/middleware/authorization.ts`, `packages/protocol/src/middleware/authorization.ts` | Middleware de autorização; o contrato é tipado, enquanto a autenticação concreta é injetada pelo server. |
| `packages/server/src/auth.ts`, `cors.ts`, `pty-environment.ts`, `location.ts` | Basic Auth/configuração, CORS, ambiente PTY e resolução de localização por request. |
| `packages/protocol/src/api.ts`, `groups/*.ts`, `errors.ts` | API declarativa Effect HttpApi e schemas de request/response/eventos. |
| `packages/plugin/src/index.ts` | Tipos de plugin atual: `event`, `config`, `tool`, `auth`, `provider`, `chat.message`, `chat.params`, `chat.headers`, `permission.ask`, pre/post command/tool, `shell.env`, transformações de mensagens/sistema, small model, compactação e definição de tool. |
| `packages/plugin/src/v2/effect/*.ts` | Implementação parcial/experimental da nova API Effect: agent, aisdk, catalog, command, context, event, filesystem, integration, location, npm, path, plugin, reference, registration e skill. O plano é normativo, não documentação da API atual. |
| `packages/plugin/src/v2/promise/*.ts` | Wrapper/contrato Promise V2; o próprio plano diz que deveria vir depois da API Effect estável. |
| `packages/codemode/src/codemode.ts`, `tool-runtime.ts`, `interpreter/*`, `stdlib/*` | Intérprete limitado sobre catálogo explícito, schemas, timeout, limite de tool calls, limite de bytes, logs e diagnósticos estruturados. |
| `packages/core/src/event/*`, `packages/server/src/handlers/event.ts` | EventV2 persistente, cursor/replay/pub-sub e stream HTTP; a spec TODO informa que exposição completa de cursors ao SDK ainda é trabalho restante. |

## 4. Skills, ferramentas, modos e permissões

### 4.1 Skills

A documentação [`skills.mdx`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/web/src/content/docs/skills.mdx) define uma Skill como um diretório com `SKILL.md`, frontmatter obrigatório (`name`, `description`) e carregamento sob demanda pela ferramenta nativa `skill`. A descoberta percorre:

- `.opencode/skills/<nome>/SKILL.md` e `~/.config/opencode/skills/<nome>/SKILL.md`;
- compatibilidade Claude em `.claude/skills/` e `~/.claude/skills/`;
- compatibilidade agent em `.agents/skills/` e `~/.agents/skills/`.

O nome precisa ser lowercase alfanumérico com hífens, 1–64 caracteres, igual ao diretório; descrição pode ter até 1.024 caracteres. A permissão `skill` filtra a lista: `allow` carrega, `deny` oculta/rejeita e `ask` pede aprovação. Isso é uma boa combinação de descoberta progressiva e controle de superfície, mas o texto de uma skill continua sendo instrução não confiável do ponto de vista de segurança.

No repositório analisado existem duas skills de projeto reais: [` .opencode/skills/effect/SKILL.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/.opencode/skills/effect/SKILL.md) e [` .opencode/skills/rtl-aware-development/SKILL.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/.opencode/skills/rtl-aware-development/SKILL.md). Elas são absorvíveis como conteúdo de prompt, não como código privilegiado.

### 4.2 Ferramentas nativas e extensíveis

| Família | Arquivos/implementação | Permissão/documentação |
|---|---|---|
| Arquivos | `read`, `write`, `edit`, `apply_patch` | `read` por caminho; mutações sob `edit`; inclui proteção de diretório externo. |
| Shell | `bash` e `shell/*` | `bash` por comando/prefixo; prompt de aprovação e environment hook. |
| Busca | `glob`, `grep` | Matching por glob/regex; respeita `external_directory`. |
| Código | `lsp` | Queries de definição, referências, hover, símbolos; experimental. |
| Contexto | `skill`, `task`, `question`, `todowrite`, `plan-enter`, `plan-exit` | Skills/subagentes/perguntas/planejamento controlados por permission. |
| Rede | `webfetch`, `websearch`, `mcp-websearch` | `webfetch` limita URL, resposta e timeout; busca depende de backend/MCP. |
| MCP | `packages/opencode/src/mcp/*`, docs `mcp-servers.mdx` | Servidores locais/remotos, tools/resources/prompts e auth; comportamento do servidor externo não está na trust boundary. |
| Custom tools | `.opencode/tool/*.ts`, docs `custom-tools.mdx` | Tool schema e contexto SDK; dependências locais entram via `.opencode/package.json` e Bun install. |
| Plugins | `packages/plugin`, `.opencode/plugins` | Podem registrar tools e hooks; executam JavaScript/TypeScript com acesso a `$`/Bun shell. |
| CodeMode | `packages/codemode` | Execução confinada ao intérprete e catálogo, com limites de execução; não substitui container/VM. |

No exemplo do projeto, [`github-pr-search.ts`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/.opencode/tool/github-pr-search.ts) chama a API GitHub com `GITHUB_TOKEN` e pesquisa PRs abertos; [`github-triage.ts`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/.opencode/tool/github-triage.ts) atribui issues a uma equipe. Ambos estão explicitamente desabilitados em [`.opencode/opencode.jsonc`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/.opencode/opencode.jsonc), o que demonstra uma separação útil entre código presente e capability habilitada.

### 4.3 Agentes e modos

O README documenta `build` como agente primário com acesso completo, `plan` como primário read-only e `general` como subagente para pesquisa/execução multi-etapa.[1] O código de [`packages/core/src/plugin/agent.ts`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/core/src/plugin/agent.ts) também materializa:

- agente padrão/build: `primary`, com regras permissivas, `question` e `plan_enter` habilitados;
- `plan`: `primary`, nega edição, permite planos e troca para build;
- `general`: `subagent`, nega `todowrite`;
- `explore`: `subagent`, nega tudo por padrão e permite somente `grep`, `glob`, `webfetch`, `websearch` e `read`;
- `compaction`, `title` e `summary`: `primary`, hidden, somente para funções internas.

A documentação de agentes permite JSON em `opencode.json` ou Markdown em `.opencode/agents/`/configuração global. Cada agente pode definir modelo, prompt, temperatura, modo, `permission`, `tools`, `steps` e subtask. A configuração é poderosa, mas aumenta o risco de divergência entre defaults embutidos, configuração do projeto, compatibilidade V1 e plugins.

### 4.4 Permissões

A resolução usa três efeitos: `allow`, `ask` e `deny`. Há regras globais e específicas por agente; padrões usam `*` e `?`, e **a última regra coincidente vence**. As actions documentadas são `read`, `edit`, `glob`, `grep`, `bash`, `task`, `skill`, `lsp`, `question`, `webfetch`, `websearch`, `external_directory` e `doom_loop`.[3]

Os defaults são permissivos: a maioria é `allow`; `external_directory` e `doom_loop` começam em `ask`; `.env` e `.env.*` requerem cuidado, enquanto `.env.example` pode ser lido. `--auto` aprova solicitações não explicitamente negadas; portanto não deve ser usado como mecanismo de segurança. A opção “always” mantém a aprovação somente para a sessão corrente e com os padrões sugeridos pela ferramenta.

Pontos de absorção importantes:

1. Modelar a decisão como `Decision = allow | ask | deny`, com recurso e origem da regra.
2. Preservar precedência explícita e testes de “última regra vencedora”.
3. Separar autorização de capacidade: uma tool pode estar instalada, visível, permitida ou aprovada.
4. Tratar `external_directory` como capability adicional, não como simples normalização de caminho.
5. Não prometer sandbox: a própria política de segurança diz que o agente pode executar shell e modificar arquivos fora de isolamento.[4]

## 5. Hooks, plugins, eventos e workflows

### 5.1 Hooks implementados

A API atual em `packages/plugin/src/index.ts` suporta `dispose`, `event`, `config`, registro de `tool`, `auth`, `provider`, `chat.message`, `chat.params`, `chat.headers`, `permission.ask`, `command.execute.before`, `tool.execute.before`, `shell.env`, `tool.execute.after`, transformações experimentais de mensagens/sistema, escolha de small model, compactação de sessão, auto-continue de compactação, conclusão de texto e alteração da definição de tool.

O hook fornece dois pontos de interceptação valiosos:

- **antes:** alterar parâmetros do modelo, argumentos de tool, ambiente ou partes do comando;
- **depois:** observar resultado, título, output e metadados.

O contrato permite plugins notificar, integrar provider, instrumentar chamadas, alterar prompts e registrar ferramentas. A documentação de plugins lista eventos de command, file, install, LSP, message, permission, server, session, todo, shell, tool e TUI. A ordem de carregamento é global config, projeto, plugin global e plugin de projeto; hooks rodam sequencialmente.

O plano V2 [`packages/plugin/src/v2/effect/PLAN.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/plugin/src/v2/effect/PLAN.md) propõe uma interface mais segura e explícita: `transform`, `rebuild`, `hook` e `dispose`, registros scoped, ordem determinística, snapshots, rebuild serializado/coalescido, finalização de domínio e eventos tipados. O plano diz expressamente que **não é documentação da API corrente**; deve ser absorvido como design-alvo, não como dependência estável.

### 5.2 Workflows GitHub

Os 26 workflows em [`.github/workflows/`](https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/.github/workflows) se dividem assim:

| Grupo | Arquivos | Função |
|---|---|---|
| Qualidade | `test.yml`, `typecheck.yml`, `storybook.yml`, `nix-eval.yml`, `nix-hashes.yml`, `containers.yml` | Testes unitários, geração, typecheck, Storybook, Nix e imagens. |
| Release/publish | `publish.yml`, `publish-vscode.yml`, `publish-github-action.yml`, `release-github-action.yml`, `deploy.yml`, `unlock.yml` | Publicação de CLI, VS Code, Action, releases e SST; usam tokens, OIDC e ambientes. |
| Automação do agente | `opencode.yml`, `review.yml`, `triage.yml`, `duplicate-issues.yml`, `pr-management.yml`, `close-issues.yml`, `close-prs.yml`, `compliance-close.yml` | Revisões, triagem, duplicatas, comentários, limpeza e gestão de issues/PRs. |
| Docs/sincronização | `docs-locale-sync.yml`, `docs-update.yml`, `generate.yml` | Traduções, docs, geração e sincronização. |
| Métricas/notificação | `stats.yml`, `notify-discord.yml` | Estatísticas, PostHog/telemetria e Discord. |
| Governança | `pr-standards.yml` | Padrões e checagens de PR. |

O workflow local [`opencode.yml`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/.github/workflows/opencode.yml) dispara em `issue_comment` e `pull_request_review_comment` quando aparece `/oc` ou `/opencode`; concede `id-token: write`, `contents: read`, `pull-requests: read` e `issues: read`, usa a Action fixada em SHA e passa `OPENCODE_PERMISSION='{"bash":"deny"}'`. Já a documentação oferece variantes que concedem escrita para branch/PR/issue, `use_github_token` ou troca OIDC.[2]

O workflow [`triage.yml`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/.github/workflows/triage.yml) lê issue aberta, ignora membros/owners/bot, instala OpenCode, injeta `OPENCODE_API_KEY`, `GITHUB_TOKEN` e `ISSUE_NUMBER`, e executa o agente `triage`. Isso é um exemplo concreto de agente em background com efeitos externos.

### 5.3 Observabilidade

A observabilidade aparece em quatro níveis:

1. **Logs do runtime:** níveis `DEBUG`, `INFO`, `WARN`, `ERROR`, `--print-logs` e `Effect.log*`.
2. **Eventos duráveis:** EventV2 persiste sequência/cursor, publica pub/sub, permite replay e integra stream HTTP.
3. **Métricas cloud:** `packages/stats`, script de stats, PostHog, Athena/PlanetScale e backfill Honeycomb.
4. **Erros de produto:** Sentry em app/web/deploy; Honeycomb em infraestrutura e stats.

Isso é suficiente para auditoria operacional básica, mas há ressalvas: o EventV2 ainda tem itens de cursor remoto e polling cross-process adiados; outputs podem ser truncados; logs e traces podem carregar conteúdo de prompts, caminhos, comandos ou resultados; compartilhamento de sessões (`share`) tem retenção e implicações de privacidade documentadas em `share.mdx`.

## 6. Memória, sessão e persistência

OpenCode não implementa “memória” como uma base semântica única. O equivalente funcional é a combinação de:

- histórico de sessão, mensagens e partes;
- instruções de projeto/global em `AGENTS.md`/`CLAUDE.md`;
- skills carregáveis;
- TODOs;
- diffs e resumo de sessão;
- compactação automática configurável;
- referências a arquivos e recursos;
- eventos/cursors para sincronização;
- planos persistidos em diretórios de dados.

A configuração em `config.mdx` define `compaction.auto` como compactação automática quando o contexto enche e `compaction.reserved` como buffer de tokens. O hook `experimental.session.compacting` pode alterar contexto/prompt, e `experimental.compaction.autocontinue` controla a mensagem sintética de continuação. Isso é implementado, porém o prefixo experimental indica contrato sujeito a mudança.

O TODO V2 é especialmente importante: a fatia local do runner já persiste texto, reasoning, falhas do provider, tool calls/results e saída do assistente, mas ainda lista como trabalho “preservar eager structured local-tool settlement”, rever limites/backpressure, expor cursors pelo HTTP/SDK, integrar jobs de background e adicionar recuperação pós-crash, retry, backoff e fencing durável.[5]

A persistência atual combina serviços Effect/SQLite/Drizzle e camadas de compatibilidade em migração. O repositório registra invariantes corretas — transações aninhadas devem usar a transação ativa, publicação pós-commit não pode ocorrer antes do commit e schemas permanecem no core — mas também admite que duas instâncias podem disputar migrations SQLite porque a proteção atual é somente um semáforo in-process.[5]

**Implicação para o Brain:** absorver o modelo `Session → Message → Part → ToolCall/ToolResult → Event` e a compactação como interfaces, mas adicionar desde o início uma política de retenção, classificação de segredo, criptografia, idempotência e recuperação explícita.

## 7. Implementado versus planejado

| Área | Implementado no commit | Planejado, experimental ou incompleto |
|---|---|---|
| CLI/TUI | CLI completa, TUI, `run`, `serve`, ACP, MCP, GitHub, export/import e sessões. | Alguns fluxos dependem da migração V1/V2 da app. |
| Agentes | build/plan/general/explore e agentes hidden de título/resumo/compactação. | Nova API de registro/rebuild de agentes no plugin V2. |
| Tools | Bash, FS, busca, LSP, web, skill, task, question, TODO, MCP, custom tools e CodeMode. | LSP experimental; limites e backpressure do runner ainda em revisão; alguns nomes/exports legados serão removidos. |
| Skills | Descoberta multi-compatibilidade, frontmatter, permission e carregamento sob demanda. | Evolução de domínio/transform no plugin V2; resolução dinâmica ainda alvo de rebuild. |
| Permissões | allow/ask/deny, padrões, agente, external directory, auto mode, request/reply. | Issue tracker público contém relatos de casos de precedência/enforcement; recomendar testes de integração adicionais. |
| Plugins | API Promise atual com hooks e tools; plugins locais/npm. | Plano V2 Effect com registrations scoped, snapshots, rebuild e dependências entre domínios; o plano não é API atual. |
| Sessão | Histórico, partes, compactação, abort, fork, revert, TODO e eventos. | Recuperação pós-crash durável, retry/abandon explícito, background jobs, fencing clusterizado e cursors SDK. |
| API | Effect HttpApi, OpenAPI, grupos, middleware, SSE/event stream e PTY. | App ainda lista adaptadores/fallbacks V1; share e alguns endpoints/configurações permanecem pendentes em `V1_API_MIGRATION.md`. |
| Persistência | SQLite/Drizzle/Effect e storage/migrations; EventV2 durável. | Migrations cross-process, paginação de replay grande e fallback de polling cross-process. |
| GitHub | Action, OIDC/GITHUB_TOKEN, comentários, reviews, issue triage, PR e schedules documentados. | Segurança depende da política do workflow consumidor e dos secrets; não há sandbox de agente. |
| Observabilidade | Logs, eventos, Sentry, Honeycomb, stats/PostHog e artifacts de Playwright. | Redação/controle de conteúdo sensível e garantias cross-process requerem integração do Brain. |
| V2 data/API | `packages/plugin/src/v2`, specs V2, runner local e contratos Effect. | O TODO declara que o projeto ainda está “towards a launch of v2”; não assumir estabilidade. |

O checklist [`packages/app/V1_API_MIGRATION.md`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/app/V1_API_MIGRATION.md) confirma pendências concretas: remover eventos de sessão legados, migrar LSP/reference, sharing, filesystem, Git init/worktrees, configuração global/directory, credenciais, auth MCP, adapters de tipos V1 e fixtures/testes legados. Essa é a evidência mais forte de que a arquitetura publicada é híbrida, apesar de grande parte da API V2 já estar conectada.

## 8. Testes, exemplos e configuração

### 8.1 Testes

Os testes estão distribuídos por domínio, não apenas por smoke test:

| Área | Arquivos/diretórios representativos | O que protegem |
|---|---|---|
| Core/config | `packages/core/test/config/*.test.ts` | config, agent, command, plugin, provider e skill. |
| Core/permission | `packages/core/test/permission.test.ts`, `policy.test.ts` | regras, merge, precedência e policy. |
| Core/session | `session-create`, `session-history`, `session-compaction`, `move-session`, `todo`, `background-job` | ciclo de sessão, compactação, concorrência e jobs. |
| Core/plugin/provider | `plugin.test.ts`, `plugin/promise`, `plugin/command`, `plugin/skill`, `provider-*.test.ts` | extensibilidade e muitos provedores. |
| Core FS/Git/PTY | filesystem, watcher, ripgrep, git, patch, process, pty | operações locais e protocolos. |
| OpenCode tools | `packages/opencode/test/tool-*.test.ts` | bash, edit, read, write, webfetch, websearch, skill, question, todo e output store. |
| API/protocolo | `packages/protocol/test`, `packages/server` e exerciser HTTPAPI | contratos, cursores e handlers. |
| CodeMode | `packages/codemode/test/*.test.ts` | parser, enumeração, parity, promise, assinatura, stdlib e OpenAPI. |
| App/browser | `packages/app/test-browser`, `e2e/` | prompt, persistência, ownership, sessão, router, settings, attachments, review. |
| TUI/UI | `packages/tui/test`, `packages/ui/src/*.test.*` | lifecycle, clipboard, editor, keymap, runtime, temas, renderização e transcript. |
| SDK/VS Code | `packages/sdk-next/test`, `sdks/vscode` | embedding/import boundaries e extensão. |

O workflow [`test.yml`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/.github/workflows/test.yml) executa unit tests em matriz de plataformas, geração de cliente HTTP, exerciser HttpApi e E2E Playwright Linux/Windows com artifacts de 7 dias. `typecheck.yml` e `storybook.yml` completam gates. Pontos fortes são a variedade e o foco em fronteiras; pontos fracos são a grande superfície híbrida, dependências nativas e a ausência, neste exame, de uma garantia de execução completa.

### 8.2 Exemplos e configurações de projeto

- [` .opencode/opencode.jsonc`](https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/.opencode/opencode.jsonc): MCP/provider/permission vazios; referências `effect` e `opencode-local`; duas custom tools desabilitadas.
- `.opencode/agent/triage.md` e `duplicate-pr.md`: agentes reais usados por workflows.
- `.opencode/command/*.md`: `ai-deps`, `changelog`, `commit`, `issues`, `learn`, `rmslop`, `spellcheck`, `translate`; demonstram prompts reutilizáveis, argumentos, shell output e referências.
- `.opencode/plugins/tui-smoke.tsx`, `smoke-theme.json`, `tui.json`: plugin de smoke TUI desabilitado por configuração, com keybinds de teste.
- `.opencode/themes/mytheme.json` e `themes/.gitignore`: tema customizado.
- `.opencode/glossary/*.md`: glossário traduzido para instruções/UX.
- `.opencode/tool/github-pr-search.ts` e `github-triage.ts`: exemplos de tool externa com GitHub API e token.
- `packages/plugin/src/example.ts` e `example-workspace.ts`: exemplos de authoring de plugin.
- `packages/codemode/README.md`, `codemode.md`, fixtures OpenAPI: exemplos de execução confinada e contrato.
- `packages/app/e2e/`, `packages/app/README.md`, `packages/web/README.md`, `packages/*/README.md`: instruções locais, fixtures e fluxo de desenvolvimento.

## 9. Pontos fortes

1. **Separação de domínio clara.** Core, protocolo, servidor, SDK, UI e plugins possuem pacotes distintos e contratos identificáveis.
2. **Extensibilidade de primeira classe.** Skills, commands, custom tools, MCP, LSP, provider plugins, hooks e ACP atendem diferentes consumidores sem modificar o loop central.
3. **Permissões expressivas.** Regras por ação/recurso/agente com `allow`/`ask`/`deny`, external directory e doom-loop são uma base melhor que um booleano global.
4. **Descoberta progressiva.** Skills e CodeMode evitam injetar todo o conteúdo e catálogo no prompt de uma só vez.
5. **Contrato de API tipado.** Effect HttpApi, grupos, OpenAPI e SDK gerado reduzem divergência entre server e clientes.
6. **Eventos duráveis.** EventV2, cursors e replay são uma base apropriada para UI, observabilidade e consumidores remotos.
7. **Continuidade de sessão.** Histórico, partes, diffs, compactação, fork, revert e abort são mais ricos que um chat stateless.
8. **Cobertura de testes ampla.** Há testes por domínio, providers, ferramentas, UI, E2E, geração e cross-platform.
9. **Operação reproduzível.** Lockfile, Bun exact, atraso de release, Nix, Dockerfiles, actions fixadas por SHA e matrizes de CI são boas práticas.
10. **Transparência sobre limites.** O `SECURITY.md` não vende permissões como sandbox, e o TODO lista riscos e trabalho restante explicitamente.

## 10. Limites, riscos de segurança e licença

### 10.1 Segurança operacional

- **Sem sandbox:** shell, processos, arquivos, plugins, MCP e ferramentas externas podem causar efeitos no host. A permissão só ajuda no consentimento; não resiste a agente malicioso, prompt injection ou processo filho cooperante.
- **Servidor inseguro por default:** `opencode serve` avisa quando `OPENCODE_SERVER_PASSWORD` não existe. Em rede, isso é exposição de API e PTY. O Brain deve exigir autenticação forte e binding local por default.
- **`--auto` é perigoso:** ele aprova `ask` e preserva somente `deny`; não pode ser apresentado como modo seguro.
- **External directory:** ampliar o escopo de caminho torna leitura, edição, glob, grep e comandos mais potentes. Deve haver allowlist de raiz e canonicalização com symlink/race hardening.
- **Plugins/npm:** Bun instala plugins e dependências em startup/cache. Um plugin tem shell API e hooks que podem modificar argumentos, prompts e ambiente. Tratar plugins como código privilegiado, com assinatura, pin de versão e sandbox externo.
- **MCP:** servidores configurados estão fora da trust boundary declarada; respostas e tools podem exfiltrar contexto ou executar efeitos.
- **Segredos:** tools de exemplo usam `GITHUB_TOKEN`; workflows passam API keys, cloud tokens, Stripe, Sentry e Honeycomb. Redigir secrets de logs e restringir permissões por job.
- **GitHub Action:** OIDC e `GITHUB_TOKEN` são bons mecanismos, mas workflows de schedule/issue/PR com `contents: write`, `pull-requests: write` e `issues: write` podem produzir alterações automáticas. Usar branches protegidas, allowlist de autores, revisão humana e tokens mínimos.
- **Dados enviados a providers/share:** prompts, código, paths, diffs e outputs podem sair para o provider escolhido ou sessão compartilhada. Definir retenção, consentimento, classificação de dados e desligamento do share.
- **Concorrência/persistência:** migrations SQLite cross-process, advisories process-local, replay grande, tool execution eager e recovery pós-crash são limites reconhecidos pelo TODO.
- **Injeção de instrução:** `AGENTS.md`, skills, arquivos referenciados, comentários GitHub e conteúdo web/MCP entram no contexto; devem ser dados não confiáveis e não alterar permissões/capabilities.

### 10.2 Licença e supply chain

O código raiz e os pacotes publicados indicados nos manifestos usam MIT. A licença permite copiar, modificar, distribuir e sublicenciar, desde que o aviso MIT seja mantido; não há garantia. Ao absorver código, preservar `LICENSE`, copyright e notices por arquivo/pacote.

A licença MIT do repositório **não prova** que todas as dependências são MIT. O lockfile inclui AI SDKs, OpenTUI, Electron, tree-sitter, Playwright, providers, pacotes GitHub, patches locais, binários nativos e `trustedDependencies`. Riscos a auditar antes de redistribuição:

1. gerar SBOM SPDX/CycloneDX para dependências diretas e transitivas;
2. verificar licença e obrigações de assets, fontes, Electron, Shiki, tree-sitter, Ghostty e pacotes GitHub;
3. revisar cada arquivo em `patches/` e sua licença original;
4. separar código MIT de serviços proprietários (SST, Sentry, Honeycomb, PostHog, providers e APIs);
5. fixar commits/tags de dependências GitHub e guardar checksums de binários;
6. não copiar o nome/logo `OpenCode` para um produto derivado sem seguir a orientação de não sugerir afiliação do README;
7. reter avisos MIT de todos os componentes efetivamente absorvidos.

O maior risco não é a licença do código próprio, mas distribuir um subconjunto sem a documentação e os avisos das dependências, ou transformar exemplos de integração em produto com obrigações de API/terceiros não auditadas.

## 11. Recomendações de absorção para o Brain

### P0 — segurança e contratos antes de copiar execução

| Prioridade | Recomendação | Interface proposta | Dependências |
|---|---|---|---|
| P0 | Adotar um **capability broker** separado do prompt. | `authorize({actor, action, resource, context}) -> allow\|ask\|deny`; `requestId`, `reason`, `ruleTrace`, `expiresAt`. | Policy engine, canonical path, secret classifier, audit log. |
| P0 | Não usar OpenCode como sandbox. | Executar shell/plugin/MCP em container/VM/worker com filesystem e rede explícitos. | Runtime isolado, seccomp/AppArmor ou equivalente, quotas, kill/cancel. |
| P0 | Proteger servidor e eventos. | HTTP local por default; Basic Auth no mínimo; preferir tokens curtos/mTLS para remoto; SSE com autorização por sessão. | Auth service, CORS allowlist, rate limit, origin checks. |
| P0 | Classificar/redigir dados de prompt e tool outputs. | `DataPolicy.redact(event, destination)` antes de provider, share, logs e telemetry. | Secret detector, retention store, provider policy. |
| P0 | Auditar licença/supply chain. | CI com SBOM, license allowlist, dependency pinning e review de patches. | Scanner SPDX, lockfile, notices. |

### P1 — absorver os contratos de maior valor

| Prioridade | Recomendação | Interface proposta | Dependências |
|---|---|---|---|
| P1 | Adotar catálogo de tools com schema e descoberta progressiva. | `ToolCatalog.list(filter)`, `Tool.describe(id)`, `Tool.invoke(id, input, ctx)`; CodeMode opcional. | JSON Schema, validator, limits, broker. |
| P1 | Adotar sessões duráveis e partes tipadas. | `Session`, `Message`, `Part`, `ToolCall`, `ToolResult`, `Usage`, `Diff`; paginação por cursor. | SQLite/Postgres, migrations, idempotency key. |
| P1 | Adotar eventos tipados e replayáveis. | `EventStore.append`, `subscribe(cursor)`, `replay(from)`, `ack`; pós-commit obrigatório. | Storage transacional, backpressure, retention. |
| P1 | Adotar agentes como configuração declarativa. | `Agent {mode, model, system, tools, permissions, maxSteps}`; `primary` vs `subagent`. | Catalog, policy merge, model registry. |
| P1 | Adotar Skills/Rules/Commands como dados não confiáveis. | `discover`, `describe`, `load` com checksum/origem/permission; nunca conceder capability por texto. | Filesystem resolver, frontmatter schema, trust policy. |
| P1 | Adotar SDK/OpenAPI como fronteira. | Cliente gerado para sessions/events/tools/permissions/questions; versão explícita `/api/v1`/`/api/v2`. | Schema registry, compatibility tests. |

### P2 — extensão e operação

| Prioridade | Recomendação | Interface proposta | Dependências |
|---|---|---|---|
| P2 | Usar hooks com ordem e lifecycle determinísticos. | `registration = hooks.register(name, callback, scope)`; `registration.dispose()`. | Scoped registry, snapshot, timeout, error isolation. |
| P2 | Implementar transform/rebuild somente após especificação estável. | `domain.transform`, `domain.rebuild`, commit/event after finalize. | State service, dependency graph, coalescing. |
| P2 | Separar provider, model e credential. | `Provider.list`, `Model.resolve`, `Credential.authorize`; nunca expor token no event/log. | OAuth/key store, secret vault. |
| P2 | Integrar GitHub via job controller com aprovação. | `Job.trigger`, `Job.plan`, `Job.approve`, `Job.cancel`, `Job.audit`. | GitHub App/OIDC, token scopes, branch protection. |
| P2 | Adotar observabilidade com redaction. | spans para session/model/tool/permission, métricas de latency/tokens/error, evento de decisão. | OpenTelemetry/Sentry/Honeycomb equivalente. |
| P2 | Portar somente UX de TUI/app após contratos. | renderer agnóstico de `SessionEvent` e `PermissionRequest`. | SDK estável, event reducer. |

### Ordem de implementação sugerida

1. **Semana 1–2:** capability broker, secret redaction, storage/event schemas e threat model.
2. **Semana 3–4:** tool catalog, validator, permission tests e runner isolado.
3. **Semana 5–6:** Session/Message/Part, cursor/replay, compactação e cancelamento.
4. **Semana 7–8:** Agent/Skill/Rule/Command declarativos e SDK/OpenAPI.
5. **Depois:** plugins scoped, providers, MCP isolado, GitHub jobs, UI/TUI e CodeMode.

A regra de decisão é: **absorver semântica e contratos; reimplementar efeitos perigosos**. O Brain não deve herdar o default permissivo, o servidor não autenticado, a execução sem sandbox, o acoplamento V1/V2 ou a dependência de serviços cloud sem uma escolha consciente.

## 12. Referências primárias

[1]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/README.md "OpenCode README no commit analisado"
[2]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/web/src/content/docs/github.mdx "Documentação da integração GitHub"
[3]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/web/src/content/docs/permissions.mdx "Documentação de permissões"
[4]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/SECURITY.md "Threat model e política de segurança"
[5]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/specs/v2/todo.md "TODO e slices V2"
[6]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/web/src/content/docs/skills.mdx "Documentação de Agent Skills"
[7]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/web/src/content/docs/tools.mdx "Documentação de ferramentas"
[8]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/web/src/content/docs/plugins.mdx "Documentação de plugins e hooks"
[9]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/plugin/src/v2/effect/PLAN.md "Plano da API V2 Effect de plugins"
[10]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/app/V1_API_MIGRATION.md "Checklist de migração V1/V2 da aplicação"
[11]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/.github/workflows/test.yml "Workflow de testes unitários e E2E"
[12]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/protocol/src/api.ts "API HTTP tipada e grupos de protocolo"
[13]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/server/src/routes.ts "Rotas do servidor Effect HttpApi"
[14]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/plugin/src/index.ts "Contrato atual de plugins e hooks"
[15]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/packages/codemode/src/codemode.ts "Runtime CodeMode confinado por schema e limites"
[16]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/package.json "Manifesto raiz, workspaces, patches e trusted dependencies"
[17]: https://github.com/anomalyco/opencode/blob/95daf90670b7c039c436c85537da5fbfe2205b41/LICENSE "Licença MIT"
[18]: https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/.github/workflows "Inventário de workflows GitHub"
[19]: https://github.com/anomalyco/opencode/tree/95daf90670b7c039c436c85537da5fbfe2205b41/packages "Inventário de pacotes do monorepo"

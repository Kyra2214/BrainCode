# Fase 2 — análise de `affaan-m/ECC`

**Repositório analisado:** [`affaan-m/ECC`](https://github.com/affaan-m/ECC)  
**Commit congelado:** `c4904e3f6381df934fc00bffb0afa7a1f8dae0e3` (`main`, obtido em 2026-09-12)  
**Versão declarada:** `2.2.1`  
**Data da análise:** 2026-09-12  
**Papel para o Brain:** fonte de padrões de operação para agentes de engenharia: catálogo de skills, roteamento por agentes, hooks de ciclo de vida, instalação seletiva multi-harness, memória local e gates de segurança.

## 1. Conclusão executiva

O ECC é melhor entendido como um **sistema operacional de práticas para coding agents**, e não como um único agente ou servidor de inferência. O núcleo é deliberadamente declarativo: arquivos Markdown descrevem **292 skills**, **68 agentes**, **94 comandos compatíveis** e **122 conjuntos de regras**. O runtime Node.js implementa instalação, seleção de componentes, resolução de raízes de plugin, hooks, memória, sessões, observabilidade, coordenação de worktrees e validações. Ao redor do runtime há adaptadores para Claude Code, Codex, Cursor, OpenCode, Gemini, Kiro, Qwen, Zed, Kimi, Hermes, OpenClaw e outros harnesses.

A arquitetura é ampla, modular e madura em engenharia de distribuição. Ela tem bons contratos de instalação, validação estrutural, testes por camadas, hooks com perfis, uma postura explícita de supply chain, memória `ecc.memory.v1` e uma orientação skills-first. O melhor material para absorção no Brain é o **modelo de contratos e gates**, não a cópia integral dos 3.701 arquivos. A absorção recomendada é seletiva: primeiro normalizar skills/regras/agentes e o protocolo de hooks; depois integrar memória e observabilidade; só então avaliar a camada ECC2 em Rust.

Há riscos importantes. O repositório combina conteúdo prescritivo, comandos locais e configurações que podem executar subprocessos, carregar MCPs e herdar credenciais. A instalação pode escrever em diretórios de usuário e em configurações nativas dos harnesses. A contagem e a descrição de skills em alguns manifestos de plugin estão defasadas em relação ao checkout atual: `plugins/ecc/.codex-plugin/plugin.json` anuncia 249 e `.codex-plugin/plugin.json` anuncia 281, enquanto a árvore atual contém 292 diretórios de skills. Existem exemplos e comandos que usam `npx`, `uvx`, `@latest` ou uma GitHub Action sem pin de release em documentação. A política de segurança reconhece o problema, mas a adoção deve impor pinagem, revisão e sandboxing próprios.

**Status de implementação neste commit:** a maior parte da superfície Node.js, catálogos, hooks, instaladores, memória local, testes e adaptadores está implementada. A própria documentação marca como seguimento ou não-objetivo a sincronização de grafo do ECC2, captura automática de sessão, adaptadores semânticos de memória, promoção por event log e replicação multi-máquina. O ECC2 existe no tree como crate Rust separado, mas não aparece como binário no `package.json`; não foi possível executar `cargo check` porque `cargo` não está instalado neste sandbox.

## 2. Método e escopo

Foi feito clone do repositório oficial com `gh repo clone`, leitura da árvore Git inteira e inspeção por categorias de todos os 3.701 arquivos versionados. Foram examinados README e traduções, manifestos e schemas, código Node/Python/Rust, exemplos, testes, workflows, configurações de harness, instaladores, hooks, documentação de design, políticas de segurança e arquivos de licença. O inventário medido no commit foi:

| Superfície | Quantidade observada | Interpretação |
|---|---:|---|
| Arquivos versionados | 3.701 | Monorepo de conteúdo, runtime e adaptadores |
| `agents/` | 68 | Definições Markdown de subagentes especializados |
| `skills/` | 292 diretórios | Conhecimento/workflows ativáveis; em geral cada um possui `SKILL.md` |
| `commands/` | 94 | Entrada slash/compatibilidade, explicitamente legada |
| `rules/` | 122 arquivos | Regras comuns e por linguagem sempre carregadas conforme seleção |
| `scripts/` | 284 | CLI, instaladores, hooks, memória, sessões, auditoria e CI |
| `hooks/` | 5 arquivos no primeiro nível | `hooks.json`, Codex, README e subárvore de persistência |
| `tests/` | 314 | 91 lib, 68 scripts, 54 hooks, 32 CI, 19 docs, 6 skills, integrações e fixtures |
| `docs/` | 1.505 | Documentação principal, design, segurança e localizações |
| `examples/` | 67 | Avaliação, coordenação, memória, GAN e guias de harness |
| `.github/` | 28 | CI, release, supply chain, anúncios e templates |
| `ecc2/` | 21 | Crate Rust de sessões/worktrees/TUI/observabilidade/eval |
| `src/` | 20 | Camada Python experimental de abstração LLM |
| `manifests/` | 3 | Componentes, módulos e perfis de instalação |
| `schemas/` | 12 | Contratos JSON para instalação, hooks, memória, plugin, estado e proveniência |

Além da leitura estática, `npm test` foi executado. A primeira execução falhou antes da validação de skills porque as dependências não estavam instaladas (`Cannot find module 'js-yaml'`). Depois de `npm ci --ignore-scripts`, a segunda execução passou pelos validadores e avançou para a suíte extensa, chegando a dezenas de arquivos de teste; a janela de execução da ferramenta expirou antes de fornecer o exit code e o resumo final. Portanto, este relatório **não declara a suíte verde**. A ausência de `cargo` impediu a verificação de compilação do ECC2. Não foram executadas instalações em `~/.claude`, `~/.codex` ou outros diretórios reais de usuário.

## 3. Arquitetura e fluxo operacional

### 3.1 Camadas

| Camada | Arquivos principais | Estado |
|---|---|---|
| Conteúdo declarativo | `skills/**/SKILL.md`, `agents/*.md`, `commands/*.md`, `rules/**`, `AGENTS.md`, `CLAUDE.md` | Implementado e versionado |
| Catálogo e seleção | `manifests/install-*.json`, `schemas/install-*.schema.json`, `agent.yaml`, `scripts/catalog.js`, `scripts/ci/generate-command-registry.js` | Implementado; exige sincronização após mudanças |
| Instalação | `scripts/ecc.js`, `scripts/install-guided.js`, `scripts/install-plan.js`, `scripts/install-apply.js`, `scripts/setup.js`, `install.sh`, `install.ps1` | Implementado |
| Adaptadores de harness | `.claude-plugin/`, `.codex-plugin/`, `plugins/ecc/`, `.cursor/`, `.opencode/`, `.gemini/`, `.kiro/`, `.qwen/`, `.zed/`, `.hermes/`, `.openclaw/`, `.kimi/`, `.adal/` | Implementado com paridade desigual |
| Runtime de hooks | `hooks/hooks.json`, `hooks/codex-hooks.json`, `scripts/hooks/**`, `scripts/lib/resolve-ecc-root.js`, `scripts/hooks/run-with-flags.js` | Implementado; Claude é mais completo que Codex |
| Memória | `scripts/memory.js`, `scripts/memory-mcp.mjs`, `skills/unified-memory/`, `schemas/memory.schema.json`, `docs/design/ecc-memory-vault.md` | Implementado como slice local explícito |
| Sessões/coordenação | `scripts/sessions-cli.js`, `scripts/session-inspect.js`, `scripts/lib/session-adapters/**`, `scripts/orchestrate-worktrees.js`, `scripts/worktree-lifecycle.js` | Implementado em Node; integrações dependem de tmux/harness |
| Segurança | `skills/security-scan/`, `skills/security-review/`, `skills/gateguard/`, `scripts/ci/scan-supply-chain-iocs.js`, AgentShield externo | Orientação e wrappers implementados; scanner AgentShield é dependência externa |
| Observabilidade | hooks de custo/status, `scripts/observability-readiness.js`, `scripts/operator-readiness-dashboard.js`, `scripts/status.js`, `tests/hooks/**`, eval harness | Implementado como telemetria local/readiness; não é uma plataforma central hospedada |
| ECC2 | `ecc2/src/**`, `ecc2/Cargo.toml` | Código presente, crate separado; integração de grafo e captura automática ainda são seguimento |
| Python LLM | `src/llm/**`, `pyproject.toml`, `ecc_dashboard.py` | Alpha/experimental, separado do caminho universal Node |

### 3.2 Fluxo de instalação

O caminho publicado é `npx ecc-universal@2.2.1 setup` ou `npx ecc-universal@2.2.1 install --guided`. O wizard inventaria o marketplace e os escopos nativos do Claude antes de escrever, permite `user`, `project` ou `local`, escolhe perfil de hooks e pede confirmação. O modo multi-harness permite Claude, Codex e Kimi, com escolhas explícitas e `--dry-run`. O pacote expõe os binários `ecc`, `ecc-universal`, `ecc-install`, `ecc-memory-mcp`, `ecc-control-pane` e `ecc-plan-canvas` em [`package.json`](https://github.com/affaan-m/ECC/blob/main/package.json).

A seleção é dirigida por três manifestos. `install-profiles.json` define `minimal`, `opencode`, `core`, `developer`, `security`, `research` e `full`. `install-modules.json` dá a cada módulo tipo, paths, targets, dependências, custo e estabilidade. `install-components.json` apresenta componentes de baseline, linguagem, framework, capacidade, agente e skill. O instalador deve resolver dependências antes de copiar ou sincronizar; por exemplo, `workflow-quality` depende de `skill-unified-memory`, e `framework-language` depende de regras, agentes, comandos e plataforma.

A distinção operacional mais relevante é que **instalação de conteúdo não equivale a autorização de execução**. O plugin Claude traz `hooks_enabled` e `hook_profile`; no Codex, instalação do plugin, confiança no hook e ativação do hook são controles separados. O legado `scripts/sync-ecc-to-codex.sh` copia configuração para `~/.codex` e deve ser tratado como caminho de compatibilidade, não como instalação nativa.

### 3.3 Fluxo de uma sessão

1. O harness carrega instruções de projeto e regras selecionadas.
2. O evento `SessionStart` passa por bootstrap e resolve a raiz real do ECC através de `CLAUDE_PLUGIN_ROOT`, raízes legadas conhecidas ou cache de plugin.
3. Hooks de prompt e de ferramenta executam lembretes, GateGuard, observação de aprendizado, rastreio de atividade, monitor de contexto, checagem de package manager e controles de servidor.
4. O agente usa skills e pode delegar a um dos 68 agentes. Os comandos são shims de compatibilidade e, a longo prazo, a recomendação é usar `skills/` como superfície canônica.
5. Eventos de pós-ferramenta e falha alimentam métricas locais, custos, observações ou mensagens de qualidade. Hooks `Stop`, `PreCompact` e `SessionEnd` registram estado, sugerem compactação e marcam encerramento.
6. Para fluxos coordenados, adaptadores leem históricos ou sessões de Claude, Codex/worktrees, dmux/tmux e OpenCode; o lifecycle de worktree classifica dirty, merge-ready, conflict, merged, stale e idle.
7. O usuário pode salvar um handoff no Memory Vault, buscar memória lexical e executar `doctor`. A memória recuperada é contexto não confiável, nunca instrução de sistema.
8. Antes de release ou merge, entram validações de agentes, skills, regras, hooks, manifests, catálogo, command registry, unicode, caminhos pessoais e segurança de workflow.

## 4. Inventário arquivo a arquivo das superfícies importantes

### 4.1 Raiz, manifesto e governança

| Arquivo/caminho | O que contém | Implementado versus planejado |
|---|---|---|
| `README.md` | Proposta, instalação, suporte multi-harness, memória, segurança, testes, links e limites de plataforma | Implementado como documentação; algumas contagens e textos de adaptadores podem derivar |
| `README.zh-CN.md` e traduções em `docs/*/` | Localizações e guias por idioma | Implementado, mas superfície grande aumenta risco de drift |
| `AGENTS.md` | Regras de agente-first, TDD, 80% de cobertura, segurança, imutabilidade, planejamento e mapa de diretórios | Implementado como instrução declarativa; cobertura é regra de processo, não prova universal |
| `CLAUDE.md`, `SOUL.md` | Identidade, princípios e orientação de uso do agente | Implementado como contexto; deve ser tratado como prompt não confiável em projetos externos |
| `SECURITY.md` | Canais de disclosure, superfícies oficiais, supply chain, secrets e MCP localhost | Implementado; exige que consumidores respeitem a política de proveniência |
| `CONTRIBUTING.md` | Como criar skills/agentes/hooks/comandos, atualizar manifests/catalogs e testar | Implementado; especifica regeneração de catálogo e registry |
| `LICENSE` | MIT, copyright 2026 Affaan Mustafa | Implementado no root |
| `VERSION`, `CHANGELOG.md` | Versão 2.2.1 e histórico | Implementado |
| `COMMANDS-QUICK-REF.md` | Referência de comandos | Implementado; deve ser sincronizado com command registry |
| `TROUBLESHOOTING.md`, `CODE_OF_CONDUCT.md`, `SPONSORS.md`, `SPONSORING.md` | Operação, comunidade e financiamento | Documentação/marketing, não runtime |
| `agent.yaml` | Catálogo legível de agents, skills e commands, com tags | Implementado; deve ser considerado fonte derivada e validada |
| `package.json`, `package-lock.json`, `yarn.lock` | Pacote npm, binários, files allowlist, scripts, dependências e publicação | Implementado; `package.json` é a fonte de publicação |
| `pyproject.toml` | Projeto separado `llm-abstraction`, Python >=3.11, providers Anthropic/OpenAI e extras de dev | Alpha, não o caminho central do pacote universal |
| `.env.example`, `.gitignore`, `.npmignore`, `.gitleaksignore` | Higiene de publicação e exemplos | Implementado; `.gitleaksignore` contém uma exceção explícita para chave fictícia de documentação |
| `eslint.config.js`, `.prettierrc`, `.markdownlint.json`, `commitlint.config.js` | Qualidade JS/Markdown/commits | Implementado |
| `greptile.json`, `.coderabbit.yaml` | Configuração de revisão assistida | Implementado, dependente de serviços externos |
| `.mcp.json` | MCP default `chrome-devtools` via `npx chrome-devtools-mcp@latest` | Implementado, mas `@latest` cria risco de supply chain |

### 4.2 Agents

Os 68 arquivos de `agents/*.md` seguem frontmatter com `name`, `description`, `tools` e, quando aplicável, `model`. O catálogo cobre planejamento (`planner`, `architect`, `code-architect`, `code-explorer`, `spec-miner`), implementação/reparo (`build-error-resolver` e resolvers por linguagem/framework), revisão (`code-reviewer`, `security-reviewer`, `typescript-reviewer`, `python-reviewer`, `go-reviewer`, `rust-reviewer`, `java-reviewer`, `kotlin-reviewer`, `react-reviewer`, `django-reviewer`, `database-reviewer`, `mle-reviewer`, `rag-pipeline-reviewer`), testes (`tdd-guide`, `e2e-runner`, `pr-test-analyzer`), operações (`loop-operator`, `harness-optimizer`, `silent-failure-hunter`, `harness-optimizer`) e domínios (`healthcare-reviewer`, `homelab-architect`, `network-architect`, `marketing-agent`, `opensource-*`, `gan-*`).

Os arquivos mais relevantes são `planner.md`, `architect.md`, `tdd-guide.md`, `code-reviewer.md`, `security-reviewer.md`, `spec-miner.md`, `build-error-resolver.md`, `e2e-runner.md`, `loop-operator.md`, `harness-optimizer.md`, `mle-reviewer.md`, `rag-pipeline-reviewer.md` e os pares `*-reviewer.md`/`*-build-resolver.md`. Cada agente é prompt, não processo isolado: as ferramentas listadas no frontmatter são interpretadas pelo harness. Logo, `tools: Read, Write, Edit, Bash, Grep, Glob` não é por si só uma sandbox ou uma ACL do Brain.

**Implementado:** definição, catálogo, validação de frontmatter e recomendações de roteamento em `AGENTS.md`. **Limite:** não há garantia arquitetural de que cada harness respeite exatamente as mesmas ferramentas, modelos ou instruções; paridade é explicitamente limitada.

### 4.3 Skills

`skills/` é a superfície canônica. Foram encontrados **292 diretórios**, normalmente com `SKILL.md`, frontmatter `name`, `description` e `origin`, instruções de ativação, padrões, exemplos, anti-padrões e links relacionados. A cobertura pode ser agrupada em:

| Grupo | Skills/arquivos representativos |
|---|---|
| Engenharia de software | `coding-standards`, `backend-patterns`, `frontend-patterns`, `api-design`, `contract-first`, `hexagonal-architecture`, `error-handling` |
| Linguagens/frameworks | `python-patterns`, `python-testing`, `golang-patterns`, `golang-testing`, `rust-patterns`, `rust-testing`, `java-coding-standards`, `kotlin-patterns`, `swift-*`, `cpp-*`, `django-*`, `rails-*`, `laravel-*`, `springboot-*`, `quarkus-*`, `react-*`, `vue-patterns`, `nextjs-turbopack`, `fastapi-patterns`, `nestjs-patterns` |
| TDD/verificação | `tdd-workflow`, `verification-loop`, `e2e-testing`, `browser-qa`, `delivery-gate`, `production-audit`, `eval-harness` |
| Segurança | `security-review`, `security-scan`, `gateguard`, `safety-guard`, `security-bounty-hunter`, `llm-trading-agent-security`, `healthcare-phi-compliance`, `hipaa-compliance`, `healthcare-*` |
| Agentes e loops | `agentic-engineering`, `autonomous-loops`, `continuous-agent-loop`, `continuous-learning`, `continuous-learning-v2`, `team-builder`, `team-agent-orchestration`, `operator-approval-loop`, `council`, `council-multi-model` |
| Memória e contexto | `unified-memory`, `context-budget`, `strategic-compact`, `token-budget-advisor`, `knowledge-ops`, `living-docs-governance` |
| Pesquisa e APIs | `deep-research`, `research-ops`, `documentation-lookup`, `exa-search`, `iterative-retrieval`, `scientific-thinking-*`, `scientific-db-*` |
| Orquestração | `plan-orchestrate`, `orch-*`, `dmux-workflows`, `project-flow-ops`, `parallel-execution-optimizer`, `recursive-decision-ledger` |
| Operações/conectores | `github-ops`, `google-workspace-ops`, `jira-integration`, `email-ops`, `network-*`, `homelab-*`, `ito-*`, `nasiko-control-plane` |
| Conteúdo/mídia/design | `article-writing`, `brand-voice`, `content-engine`, `investor-*`, `marketing-campaign`, `frontend-design-direction`, `design-system`, `motion-*`, `video-editing`, `videodb`, `taste-*`, `frontend-slides` |
| Domínios | `supply-chain-domain` via manifest, `energy-procurement`, `inventory-demand-planning`, `returns-reverse-logistics`, `healthcare-*`, `prediction-market-*`, `finance-billing-ops` |

Além do `SKILL.md`, algumas skills têm `scripts/`, `tests/`, `README.md`, assets ou exemplos. A validação em `scripts/ci/validate-skills.js` lê YAML e evita que uma skill malformada entre no catálogo. O guia de contribuição impõe foco de domínio, exemplos testáveis, menos de 500 linhas como meta, ausência de secrets e frontmatter compatível.

**Implementado:** catálogo, validação e instalação seletiva. **Risco:** a grande quantidade faz com que o contexto possa ficar caro e aumenta prompt injection, instruções contraditórias e manutenção. O Brain deve absorver primeiro um subconjunto curado.

### 4.4 Commands

Os 94 `commands/*.md` são entradas slash e shims de compatibilidade. Incluem `plan`, `feature-dev`, `code-review`, `security-scan`, `quality-gate`, `test-coverage`, `save-session`, `resume-session`, `sessions`, `loop-start`, `loop-status`, `multi-*`, `orch-*`, `prp-*`, `epic-*`, `hookify-*`, `instinct-*`, `learn`, `evolve`, `model-route`, `setup-pm`, `project-init`, `react-*`, `python-review`, `go-*`, `rust-*`, `kotlin-*`, `flutter-*`, `cpp-*` e `vue-review`.

`AGENTS.md` e `CONTRIBUTING.md` deixam claro que **skills são a direção futura** e `commands/` permanece para migração e paridade. Alguns comandos dependem de runtimes externos: `multi-backend.md`, `multi-frontend.md`, `multi-plan.md` e `multi-workflow.md` requerem `ccg-workflow`; `security-scan.md` requer um AgentShield já instalado e revisado; `pm2.md` requer PM2; vários comandos supõem CLIs de linguagem. Esse é um limite operacional importante: documentação instalada não garante que o comando rode.

### 4.5 Rules e configurações de harness

`rules/` tem 122 arquivos, divididos entre `common/` e packs por linguagem. São normas de alta prioridade, como coding style, git workflow, testing, performance, patterns, security e regras específicas de TypeScript, Python, Go, Swift, PHP, Rust, Java e outras linguagens.

As configurações de adaptação incluem:

| Caminho | Papel |
|---|---|
| `.claude-plugin/plugin.json` | Plugin Claude, `skills`, `commands`, user config de hooks |
| `.codex-plugin/plugin.json`, `plugins/ecc/.codex-plugin/plugin.json` | Plugin Codex e metadados de interface |
| `.cursor/**` | Regras, comandos e hooks/compatibilidade Cursor |
| `.opencode/**` | Plugin, prompts, comandos e pacote OpenCode |
| `.gemini/**`, `.qwen/**`, `.zed/**` | Adaptadores/configuração de harness |
| `.kiro/**` | Agents, prompts e exemplos MCP AWS/Strands |
| `.pi/**` | Extensão e README para Pi |
| `.trae/**` | Instalação global/local Trae com manifest de desinstalação |
| `.hermes/**`, `.openclaw/**`, `.kimi/**`, `.adal/**` | Superfícies adicionais e migrações |
| `.agents/**` | Cópias/contratos Codex e skills adaptadas |

A documentação afirma suporte completo do core Node em Linux/macOS/Windows/WSL, mas capacidades opcionais variam: Bash/Python, tmux e ferramentas de provider são necessários em vários fluxos; Windows nativo tem defeitos conhecidos de observer e memory-vault; macOS usa caminho limitado para GAN; Copilot não tem hooks; Cursor/OpenCode/Gemini/Zed têm cobertura menor.

### 4.6 Hooks

Os arquivos centrais são `hooks/hooks.json`, `hooks/codex-hooks.json`, `hooks/memory-persistence/README.md` e `hooks/memory-persistence/hooks.json`. O contrato formal está em `schemas/hooks.schema.json`, que aceita comandos, HTTP e prompt/agent hooks, matchers, timeouts e modo assíncrono.

No Claude, `hooks/hooks.json` configura eventos como `SessionStart`, `UserPromptSubmit`, `PreToolUse`, `PermissionRequest`, `PostToolUse`, `PostToolUseFailure`, `Notification`, `SubagentStart`, `Stop`, `SubagentStop`, `PreCompact`, `InstructionsLoaded`, `TeammateIdle`, `TaskCompleted`, `ConfigChange`, `WorktreeCreate`, `WorktreeRemove` e `SessionEnd`, com seleção por perfil `minimal`, `standard` e `strict`. Os wrappers chamam `scripts/hooks/run-with-flags.js`, que resolve a raiz, escolhe Node ou shell e repassa stdin/stdout.

No Codex, `hooks/codex-hooks.json` é deliberadamente menor: o caminho nativo fornece `SessionStart` bootstrap, exige `PLUGIN_ROOT` e deixa confiança/ativação no Codex. Isso é um caso explícito de paridade limitada, não um bug oculto.

Hooks de interesse incluem `plugin-hook-bootstrap.js`, `session-start-bootstrap.js`, `pre-bash-*`, `post-bash-hooks.js`, `posttooluse-dispatcher.js`, `pre-compact.js`, `stop-format-typecheck.js`, `stop-hooks-stdout.js`, `session-end-marker.js`, `cost-tracker.js`, `ecc-context-monitor.js`, `ecc-statusline.js`, `ecc-metrics-bridge.js`, `continuous-learning-observe-runner.js`, `observer-loop-*`, `gateguard-*`, `mcp-health-check.js`, `quality-gate.js`, `doc-file-warning.js`, `auto-tmux-dev.js` e `desktop-notify.js`.

**Implementado:** dispatch, flags, timeouts, resolução multi-plataforma, testes específicos de hooks e perfis. **Risco:** são comandos executáveis em eventos do agente; alteração de hook é equivalente a alteração de software privilegiado. Instalação deve ser sempre dry-run + revisão + consentimento explícito.

### 4.7 Scripts, CLI, memória, sessões e ferramentas

`package.json` define scripts de qualidade (`test`, `coverage`, `lint`), catálogo, registry, adapters, audit, eval, observabilidade, dashboard, release, security IOC scan, orchestration e `dashboard`. A divisão de `scripts/` é a seguinte:

| Subárvore/arquivo | Responsabilidade |
|---|---|
| `scripts/ecc.js`, `scripts/consult.js`, `scripts/setup.js` | Entrada da CLI e wizard |
| `scripts/install-plan.js`, `install-apply.js`, `install-guided.js`, `repair.js`, `uninstall.js` | Planejamento, mutação, reparo e remoção segura |
| `scripts/catalog.js`, `scripts/ci/generate-command-registry.js` | Catálogo e registry derivados |
| `scripts/ci/validate-*.js` | Validadores de agents, commands, rules, skills, hooks, manifests, workflows, Unicode e paths |
| `scripts/lib/state-store/**` | Estado de instalação, migrations, schema, queries e projeções |
| `scripts/lib/resolve-ecc-root.js` | Descoberta central da raiz ECC |
| `scripts/lib/session-adapters/**` | Claude history, Codex worktree, dmux/tmux, OpenCode e registry |
| `scripts/sessions-cli.js`, `session-inspect.js` | Registro/inspeção de sessões e saúde de skills |
| `scripts/worktree-lifecycle.js`, `scripts/orchestrate-worktrees.js` | Classificação, merge-tree e coordenação de worktrees |
| `scripts/memory.js`, `scripts/memory-mcp.mjs` | CLI e servidor MCP da memória |
| `scripts/observability-readiness.js`, `operator-readiness-dashboard.js`, `status.js` | Readiness, dashboard e status local |
| `scripts/agentshield.js`, `security-scan.js` | Orientação/wrapper de scan, com scanner externo |
| `scripts/plan-canvas.js`, `scripts/control-pane.js`, `dashboard-web.js` | Interfaces locais de plano/controle/dashboard |
| `scripts/ito.js`, `nasiko.js`, `claw.js`, `work-items.js` | Pontes opcionais para CLIs e sistemas externos |
| `scripts/hooks/**` | Implementações de eventos de ciclo de vida |

A maioria dessas ferramentas é real e testada, mas várias são adaptadores de processo. O Brain deve evitar inferir que `operator-readiness-dashboard.js` equivale a telemetria de produção, ou que um wrapper AgentShield contém o scanner.

### 4.8 Memory Vault

O contrato está documentado em [`docs/design/ecc-memory-vault.md`](https://github.com/affaan-m/ECC/blob/main/docs/design/ecc-memory-vault.md), implementado em `scripts/memory.js`/`scripts/memory-mcp.mjs` e validado por `schemas/memory.schema.json`, `skills/unified-memory/` e `examples/unified-memory/`.

A unidade é um Markdown com frontmatter JSON-valued `ecc.memory.v1`, ID, título, kind, scope, trust, status, source e metadados. Os escopos são `project` (`.ecc/memory/`), `team` e `user` (`~/.ecc/memory/`). A CLI expõe:

```text
ecc memory init --scope project|team|user
ecc memory save --title ... --stdin|--body-file ...
ecc memory handoff --from <harness> --target <harness> ...
ecc memory search <query> [--scope ...] [--target-harness ...]
ecc memory read <id> [--scope ...]
ecc memory doctor
```

O MCP expõe somente `memory_save`, `memory_search`, `memory_read` e `memory_doctor`. A identidade deve ser lowercase em `ECC_MEMORY_HARNESS` no processo servidor e não pode ser substituída pelo chamador. User scope exige `ECC_MEMORY_ALLOW_USER_SCOPE=1` e ainda requer escopo explícito. Escritas são create-only, entradas são não revisadas, leitores não seguem symlinks e bodies não aparecem em acknowledgements/telemetria.

**Implementado:** slice local CLI/MCP, schema, doctor, pesquisa lexical, handoff e testes cross-harness. **Planejado/não-objetivo na primeira versão:** banco vetorial, sync hospedado, importação de transcritos, promoção automática para regra/skill, sincronização ECC2/event log, adapters semânticos, captura automática de sessão, replicação multi-máquina e injeção automática de referências em `SessionStart`.

### 4.9 Observabilidade e avaliação

A observabilidade é principalmente local e orientada a evidências de processo. `cost-tracker.js`, `ecc-context-monitor.js`, `ecc-statusline.js`, `ecc-metrics-bridge.js`, `session-activity-tracker.js`, `observer-loop-*`, `evaluate-session.js`, `skill-run-tracker.js` e `session-end-marker.js` capturam eventos, custos, contexto, saúde e marcadores. `scripts/observability-readiness.js` e `scripts/operator-readiness-dashboard.js` geram relatórios de prontidão e top actions; não são um backend de traces distribuídos.

A avaliação tem `scripts/eval-harness.js`, `examples/eval-harness/`, `examples/evaluator-rag-prototype/`, `schemas/capsule-envelope.schema.json`, receipts/replay/gate no diretório `tests/lib/eval-harness/` e o crate `ecc2/src/harness_eval.rs`. O fluxo adversarial de review deduplica findings, verifica CRITICAL/HIGH, mantém incerteza como bloqueio e aprova apenas quando dimensões completas passaram. Esse padrão fail-closed é altamente absorvível.

### 4.10 Código Python e Rust

`src/llm/` é uma abstração provider-agnostic Alpha em Python: `core/interface.py` e `core/types.py` definem `LLMInput`, `LLMOutput`, `Message`, `ToolCall`, `ToolDefinition` e `ToolResult`; `providers/claude.py`, `openai.py`, `ollama.py`, `atlas.py` e `astraflow.py` adaptam providers; `providers/resolver.py` registra classes; `prompt/builder.py` constrói prompts; `tools/executor.py` fornece `ToolRegistry`, `ToolExecutor` e `ReActAgent`; `cli/selector.py` persiste `.llm.env`. O executor captura exceções e limita iterações, mas as ferramentas registradas são funções Python e a autorização depende do chamador. O pacote declara Anthropic/OpenAI, embora os caminhos Atlas/Astraflow/Ollama introduzam dependências/contratos adicionais.

`ecc_dashboard.py` é um dashboard Tkinter local que carrega agents, skills, commands e rules. Ele não é o dashboard web/operador principal do pacote Node.

`ecc2/` tem `Cargo.toml`, `Cargo.lock`, `rust-toolchain.toml` e módulos `config`, `comms`, `notifications`, `observability`, `harness_eval`, `session/{daemon,manager,output,runtime,store}`, `tui/{app,dashboard,widgets}` e `worktree`. O código modela daemon de sessões, armazenamento, saída, worktrees, TUI, notificações e evidência de eval com IDs/hash canônicos. Porém `package.json` não publica `ecc2` como binário, e a documentação do Memory Vault diz que sincronização de grafo ECC2 e captura automática pertencem a lanes futuras. Absorver essa parte sem um build e contrato de integração seria prematuro.

### 4.11 Testes

`tests/` contém 314 arquivos. As famílias importantes são:

- `tests/lib/` (91): instalação, state store, adapters, coordenação GitHub, plan canvas, eval harness, receipts, security e dry-run.
- `tests/scripts/` (68): CLI, setup, catalog, release, repair, memory, observabilidade, worktree, dashboard e publicação.
- `tests/hooks/` (54): cada evento e combinação de flags, fail-closed, Windows, memória, custo, consentimento e stdout.
- `tests/ci/` (32): supply-chain IOC, workflow security, manifests, registry, package surface e unicode.
- `tests/docs/` (19): verificam que documentação e declarações de suporte não driftam.
- `tests/skills/` (6), `tests/integration/` (2), `tests/docker/` e fixtures.
- Testes Python em `tests/test_*.py` para providers, selector, builder, executor e tipos.

O script `npm test` combina validadores estruturais e `tests/run-all.js`. `npm run coverage` pede linhas 80%, funções 80%, branches 79% e statements 80% para scripts. Isso é um bom gate de absorção, mas não prova que o comportamento probabilístico do modelo está correto. A exigência de “80%+” em `AGENTS.md` deve ser aplicada pelo Brain apenas a código determinístico sob sua propriedade.

### 4.12 Workflows e CI

Os workflows são `.github/workflows/ci.yml`, `discussion-announce.yml`, `generator-generic-ossf-slsa3-publish.yml`, `maintenance.yml`, `monthly-metrics.yml`, `release-announce.yml`, `release.yml`, `reusable-release.yml`, `reusable-test.yml`, `reusable-validate.yml`, `supply-chain-watch.yml` e `taste-skills.yml`. Eles:

- executam validadores, testes e artefatos;
- fazem `npm ci --ignore-scripts` em fases de validação;
- verificam catálogo, manifestos, hooks e segurança de workflow;
- executam scan de IOC e fontes de advisory;
- validam pacote empacotado antes de publicação;
- verificam SHA-256 do tarball antes de `npm publish`;
- usam provenance/SLSA e tags de release;
- fazem upload de artefatos e release GitHub.

A maioria das Actions observadas está fixada por commit SHA com comentário de versão, uma força relevante. O próprio `SECURITY.md` exige essa disciplina. Ainda assim, a superfície deve ser revalidada no Brain porque o repositório cresce rapidamente e há comandos/docs que apontam para `affaan-m/agentshield@v1`, `npx ...@latest`, `uvx ...@latest` e pacotes externos. O `validate-workflow-security.js` é implementado, mas valida o workflow versionado; não torna automaticamente seguras referências em Markdown executável por humanos.

### 4.13 Exemplos, plugins e documentação

Os exemplos cobrem coordenação (`examples/coordination-inventory/`), eval harness com baseline/candidate/reward-hack, protótipo evaluator-RAG com traces/reports/verifier, GAN, plan canvas, statusline, memória unificada e instruções de projeto para Django, Go, HarmonyOS, Laravel, Rails, Rust, SaaS Next.js e usuário. São bons contratos de uso, não necessariamente testes de produção.

`plugins/ecc/README.md`, `.claude-plugin/plugin.json`, `.codex-plugin/plugin.json` e `plugins/ecc/.codex-plugin/plugin.json` formam o pacote de plugins. A inconsistência de contagens dos dois JSON de Codex é um defeito de metadata: a árvore atual tem 292 skills, o root anuncia 281 e a cópia em `plugins/ecc` anuncia 249. Isso deve ser corrigido ou gerado automaticamente antes de uma absorção.

A documentação central inclui `docs/ROADMAP.md`, `docs/ECC-2.0-GA-ROADMAP.md`, `docs/ECC-2.0-REFERENCE-ARCHITECTURE.md`, `docs/SELECTIVE-INSTALL-ARCHITECTURE.md`, `docs/continuous-learning-v2-spec.md`, `docs/MCP-CONNECTOR-POLICY.md`, `docs/SKILL-DEVELOPMENT-GUIDE.md`, `docs/SKILL-PLACEMENT-POLICY.md`, `docs/token-optimization.md`, `docs/legacy-artifact-inventory.md`, `docs/skill-adaptation-policy.md`, os documentos de `docs/design/`, `docs/architecture/` e `docs/security/`. A documentação é parte do sistema: há testes que verificam claims e guias. O volume de 1.505 arquivos, com localizações duplicadas, é uma força de acessibilidade e também um vetor de desatualização.

## 5. Skills, ferramentas, modos e permissões

### Skills

A unit de absorção deve ser `SKILL.md` + scripts/tests/assets associados, nunca somente o texto. O contrato mínimo recomendado pelo ECC é `name`, `description`, `origin`, ativação explícita, procedimento, exemplos, anti-padrões e related skills. Skills são contextuais e probabilísticas; hooks são determinísticos. Essa distinção é uma das melhores ideias do ECC.

### Ferramentas

As ferramentas incluem CLI Node, scripts shell/PowerShell, Git/GitHub CLI, tmux/dmux, package managers npm/pnpm/yarn/bun, CLIs de linguagens, Playwright/Chrome DevTools, MCPs opt-in, provider APIs, `ecc-agentshield`, Itô/Nasiko e o runtime opcional `ccg-workflow`. Não se deve assumir que instalar o pacote instala qualquer um desses extras.

### Modos e perfis

- Perfis de instalação: `minimal`, `opencode`, `core`, `developer`, `security`, `research`, `full`.
- Hooks: `minimal`, `standard`, `strict`; `.claude-plugin/plugin.json` permite desligar hooks.
- Escopos Claude: user, project, local.
- Targets declarados nos módulos: claude, claude-project, cursor, antigravity, codex, gemini, opencode, codebuddy, joycode, qwen, zed, hermes, openclaw, kimi e adal, com cobertura desigual.
- Memória: project/team/user, com user opt-in no MCP.
- Avaliação: dry-run, reports Markdown/JSON/HTML em algumas ferramentas, gates e receipts.
- MCP: apenas `chrome-devtools` é default no `.mcp.json`; demais conectores devem ser opt-in em `mcp-configs/mcp-servers.json`.

### Permissões e consentimento

Não existe uma ACL única e independente do harness. O ECC opera no modelo “configuração nativa + confirmação do harness + hooks”. Claude tem `hooks_enabled`/`hook_profile`; Codex usa revisão/trust de hash em `/hooks`; MCP herda ambiente; agentes declarativos anunciam ferramentas; scripts executam subprocessos. Para o Brain, isso exige um envelope próprio de permissões:

1. listar arquivos e comandos a serem instalados;
2. mostrar todos os hooks e MCPs efetivos;
3. marcar subprocessos e variáveis de ambiente;
4. pedir consentimento para cada escopo;
5. bloquear por padrão shell destrutivo, rede e escrita fora do workspace;
6. manter hash e origem dos artefatos autorizados.

## 6. Pontos fortes

1. **Separação clara entre conhecimento e runtime.** Skills, agents, commands e rules são legíveis, versionáveis e selecionáveis.
2. **Instalação seletiva com dependências declaradas.** Perfis e módulos reduzem contexto e permitem adoção incremental.
3. **Direção skills-first.** O projeto reconhece que comandos slash são legado e que conhecimento reutilizável deve ser modular.
4. **Hooks com perfis, flags, timeouts e testes.** O wrapper central resolve raiz, normaliza plataforma e trata falhas.
5. **Postura fail-closed.** GateGuard, review adversarial, validação de workflows, receipts e incerteza mantida como bloqueio são padrões excelentes.
6. **Memória com trust boundaries explícitas.** Create-only, não seguir symlinks, identidade do harness, user-scope opt-in e “memória não é instrução” são decisões maduras.
7. **Cross-harness pragmático.** O ECC não promete paridade total e documenta diferenças de Codex, Windows, Copilot e outros.
8. **CI orientada a artefato.** Pin de Actions, lockfiles, pacote publicado, checksum, provenance e scans de supply chain formam um bom modelo operacional.
9. **Cobertura de teste orientada a contrato.** Há testes para documentação, instalação, hooks, catálogo, registry, adaptadores e publicação, não apenas unit tests.
10. **Boa observabilidade de processo.** Custo, contexto, atividade, sessão, saúde de skill, prontidão e evidência de eval têm pontos de integração claros.
11. **Documentação de não-objetivos.** A explicitação do que o Memory Vault não faz evita atribuir garantias inexistentes.
12. **Riqueza de domínio.** O catálogo cobre linguagens, frameworks, segurança, dados, ML, operações, pesquisa, conteúdo e design.

## 7. Limites e riscos

### Segurança operacional

- **Execução de configuração:** hooks, skills com scripts, comandos, MCPs e arquivos de agente podem influenciar diretamente o modelo e executar processos locais.
- **Prompt injection:** 292 skills, 68 agentes, 122 rules, exemplos e traduções são conteúdo que entra no contexto. Um arquivo comprometido pode induzir ações apesar da intenção do usuário.
- **Credenciais:** MCPs herdam ambiente; `mcp-configs/mcp-servers.json` usa placeholders e a política manda resolver secrets em runtime. Não se deve commitar configuração de usuário com PAT/API key.
- **Rede local:** a política alerta para MCPs HTTP em localhost; processo errado no port pode interceptar tráfego.
- **Dependências móveis:** `.mcp.json` usa `chrome-devtools-mcp@latest`; documentação usa `npx`, `uvx` e ferramentas sem lock de versão. Registry publication não é auditoria.
- **Action documental sem pin:** `commands/security-scan.md` contém referência de GitHub Action `affaan-m/agentshield@v1`; para CI do Brain, substituir por SHA revisado.
- **Permissões amplas:** o agente pode ter Read/Write/Edit/Bash/Grep/Glob; o arquivo Markdown não é isolamento.
- **Instaladores mutáveis:** `install.sh`, `install.ps1`, setup e sync podem modificar `~/.claude`, `~/.codex` e arquivos de projeto. Dry-run e backup são obrigatórios.
- **Shell e paths:** scripts tratam entradas de paths, URLs e subprocessos. Há testes de `block-no-verify`, PowerShell e path escaping, mas novos módulos devem passar por revisão adicional.
- **Windows/macOS:** o próprio README registra defeitos de observer/memory-vault no Windows nativo e incompatibilidade/defeito de parsing do GAN no macOS Bash 3.2.

### Licença e proveniência

O root é MIT, permissivo para uso, modificação, publicação e venda, com obrigação de preservar aviso. Existe licença adicional em `skills/taste-application/scripts/LICENSE`; assets, dependências npm/Python/Rust, exemplos de vendors e possíveis snippets externos devem ter SBOM e auditoria de compatibilidade próprios. MIT não transfere garantias, suporte, segurança ou direitos sobre marcas/serviços. O pacote anuncia links oficiais e a política proíbe aliases não oficiais, mas consumers devem verificar origem GitHub/npm e checksum.

### Coerência e manutenção

- Contagens divergentes nos plugins Codex (249/281 versus 292).
- 1.505 docs e traduções podem ficar defasadas.
- `agent.yaml`, catálogo, registry, README, plugin JSON e manifests são superfícies derivadas que precisam de geração atômica.
- Skills de domínio muito heterogêneo podem introduzir instruções conflitantes ou parecer aconselhamento profissional; as próprias descrições de prediction-market dizem “non-advisory”, mas o Brain deve adicionar guardrails.
- O pacote mistura produto OSS, integrações experimentais, sponsors, bridges de compute e docs de operações. A superfície de absorção precisa excluir marketing e conectores não necessários.
- O ECC2 Rust e a camada Python não têm o mesmo grau de integração que o runtime Node.
- O requisito de 80% é aplicável ao JS testado pelo projeto, não às decisões probabilísticas do LLM nem a cada skill textual.

## 8. Recomendação de absorção pelo Brain

### P0 — absorver antes de qualquer execução

| Entrega | Interface recomendada | Dependências | Justificativa |
|---|---|---|---|
| Contrato de skill | `Skill {id, description, triggers, instructions, tools, risk, provenance, tests}` | parser YAML/Markdown, registry | Permite curadoria dos 292 módulos sem copiar tudo |
| Contrato de agente | `AgentProfile {id, role, inputs, outputs, tools, model, escalation}` | roteador Brain | Preserva planner/reviewer/TDD/security sem confiar em frontmatter como ACL |
| Contrato de hook | `Event -> Handler(stdin JSON) -> stdout decision + stderr diagnostics` | sandbox, timeout, consent ledger | Reproduz ciclo de vida com fail-closed e sem shell arbitrário |
| Manifesto de origem | hash de commit, path, URL, licença, versão, dependencies | GitHub/npm verifier | Bloqueia pacotes e Actions móveis |
| Gate de conteúdo | scan de prompt injection, secrets, URLs, shells e permissions | AgentShield equivalente ou scanner próprio | Necessário antes de importar skills/agents/rules |
| Correção de metadata | Gerar plugin JSON/catalog/registry a partir de uma fonte | scripts CI/manifestos | Elimina discrepância 249/281/292 |

No P0, importar primeiro `tdd-workflow`, `verification-loop`, `security-review`, `security-scan`, `gateguard`, `context-budget`, `strategic-compact`, `repo-scan`, `error-handling`, `git-workflow`, `unified-memory`, `eval-harness`, `delivery-gate`, `planner`, `architect`, `tdd-guide`, `code-reviewer`, `security-reviewer`, `spec-miner`, `build-error-resolver` e `e2e-runner`. Eles representam o loop de engenharia sem trazer todos os conectores de domínio.

### P1 — integrar runtime e evidência

1. Implementar um **installer plan-only** do Brain com perfis `minimal`, `engineering`, `security`, `research` e `full`, mantendo dependências explícitas.
2. Portar a lógica de memória `ecc.memory.v1` como armazenamento local append/create-only, com scopes separados, body fora de logs, `doctor`, handoff e “contexto não é instrução”.
3. Portar GateGuard e dispatchers de hooks para sandbox com lista de comandos permitidos, sem interpolação de contexto não confiável.
4. Integrar `session-inspect`, adapters e worktree lifecycle apenas como providers opcionais, usando interface `SessionAdapter` e `WorktreeReport` estáveis.
5. Reutilizar o padrão de eval: envelope canônico, IDs content-addressed, evidência, replay, health, receipt e revisão adversarial.
6. Construir telemetria local do Brain para tokens, custos estimados, compactações, tool calls, bloqueios, falhas e consentimentos, sem enviar bodies ou secrets.
7. Criar testes de contrato para cada harness suportado, em vez de prometer paridade sem evidência.

### P2 — avaliar depois de evidência

- **ECC2 Rust:** só absorver depois de instalar Rust, rodar `cargo check/test`, compreender o daemon/TUI e definir integração com event store do Brain. O código parece valioso para sessões/worktrees/eval, mas a própria documentação deixa graph sync/captura automática para depois.
- **Python LLM abstraction:** tratar como adaptador experimental, não como base de inferência do Brain; exigir validação de provider, timeout, schema de tool args e autorização por ferramenta.
- **Conectores de domínio:** `ito-*`, `nasiko-*`, social, billing, prediction markets, healthcare e compute devem ser opt-in isolados, com políticas de dados e autorização específicas.
- **Plan Canvas/control pane/dashboard:** absorver apenas se houver necessidade de UI persistente; primeiro consumir os contratos JSON/CLI.

### Dependências mínimas da absorção

- Node.js 18 ou superior e Git para o caminho universal;
- JSON Schema/AJV e YAML parser equivalente;
- sandbox de subprocesso com timeout, cwd restrito, environment allowlist e network policy;
- Git com worktrees se coordenação for habilitada;
- Bash/Python 3.11/tmux somente para capacidades opcionais;
- Rust/Cargo apenas para ECC2;
- CLIs/providers externos somente após pin e revisão;
- armazenamento local transacional para state-store e Memory Vault;
- SBOM, lockfile, checksum, provenance e mecanismo de revogação de plugin.

## 9. Interfaces e mapeamento para o Brain

| ECC | Interface do Brain sugerida | Política |
|---|---|---|
| `SKILL.md` | `brain skill install/list/inspect/run` | Declarativa, versionada, sem execução automática de scripts sem consentimento |
| `agents/*.md` | `brain agent route` | Tools/modelos convertidos em policy, não aceitos como ACL |
| `commands/*.md` | aliases para skills | Compatibilidade temporária; telemetria de uso e depreciação |
| `rules/**` | policy bundles por projeto/linguagem | Precedência explícita e detecção de conflitos |
| `manifests/install-*.json` | capability manifest | Resolver dependências, target, custo, estabilidade e risco |
| `hooks/hooks.json` | event bus com handlers sandboxed | JSON stdin/stdout, timeout, decisão, evidência e consent ledger |
| `scripts/memory.js` | Memory provider | scopes, trust, create-only, doctor, handoff, search e redaction |
| `scripts/session-inspect.js` | SessionAdapter registry | leitura somente por padrão; paths allowlisted |
| `worktree-lifecycle.js` | Worktree service | classify/preview/cleanup-plan; nunca remover dirty/unmerged |
| `eval-harness` | Evaluation service | candidate IDs, evidence refs, receipts, replay e gates |
| `.github/workflows/*` | CI policy pack | Action SHAs, permissions mínimas, context escaping, artifact attestations |
| AgentShield | scanner provider | binário e release pinados, SBOM e atualização controlada |

## 10. Veredito

**Recomendação: absorver parcialmente, com prioridade alta para contratos e segurança e prioridade média para conteúdo.** ECC oferece um blueprint comprovado para transformar um agente de coding em processo repetível: planejar, testar, implementar, revisar, verificar, lembrar e melhorar. Seu valor maior está no desenho de fronteiras, nos testes de compatibilidade, nos manifests e no tratamento explícito de consentimento, custos, memória não confiável e supply chain.

O Brain não deve importar o repositório inteiro como instruções globais. Deve construir uma camada de proveniência e policy, curar skills P0, executar hooks em sandbox, manter memória local create-only, registrar decisões e usar os padrões de eval/review fail-closed. A camada ECC2 e os conectores externos devem permanecer atrás de feature flags até haver build reproduzível, contrato de dados, teste de segurança e decisão de licença/proveniência.

## Referências primárias

[1]: https://github.com/affaan-m/ECC "Repositório oficial affaan-m/ECC"
[2]: https://github.com/affaan-m/ECC/blob/main/README.md "README e matriz de instalação/suporte do ECC"
[3]: https://github.com/affaan-m/ECC/blob/main/AGENTS.md "Instruções de agentes e arquitetura declarada"
[4]: https://github.com/affaan-m/ECC/blob/main/CONTRIBUTING.md "Guia de contribuição, validação e publicação"
[5]: https://github.com/affaan-m/ECC/blob/main/SECURITY.md "Política de segurança e superfícies oficiais"
[6]: https://github.com/affaan-m/ECC/blob/main/package.json "Manifesto npm, binários, scripts e files allowlist"
[7]: https://github.com/affaan-m/ECC/blob/main/manifests/install-profiles.json "Perfis de instalação"
[8]: https://github.com/affaan-m/ECC/blob/main/manifests/install-components.json "Componentes instaláveis"
[9]: https://github.com/affaan-m/ECC/blob/main/manifests/install-modules.json "Módulos, targets, dependências e estabilidade"
[10]: https://github.com/affaan-m/ECC/blob/main/hooks/hooks.json "Hooks Claude e perfis de ciclo de vida"
[11]: https://github.com/affaan-m/ECC/blob/main/hooks/codex-hooks.json "Hook nativo de SessionStart para Codex"
[12]: https://github.com/affaan-m/ECC/blob/main/docs/design/ecc-memory-vault.md "Contrato de design do ECC Memory Vault"
[13]: https://github.com/affaan-m/ECC/blob/main/docs/MCP-CONNECTOR-POLICY.md "Política de conectores MCP"
[14]: https://github.com/affaan-m/ECC/blob/main/docs/continuous-learning-v2-spec.md "Especificação de aprendizado contínuo v2"
[15]: https://github.com/affaan-m/ECC/blob/main/schemas/hooks.schema.json "Schema de hooks"
[16]: https://github.com/affaan-m/ECC/blob/main/schemas/memory.schema.json "Schema de memória"
[17]: https://github.com/affaan-m/ECC/blob/main/.claude-plugin/plugin.json "Manifesto do plugin Claude"
[18]: https://github.com/affaan-m/ECC/blob/main/.codex-plugin/plugin.json "Manifesto root do plugin Codex"
[19]: https://github.com/affaan-m/ECC/blob/main/plugins/ecc/.codex-plugin/plugin.json "Cópia de plugin Codex em plugins/ecc"
[20]: https://github.com/affaan-m/ECC/tree/main/tests "Suíte de testes"
[21]: https://github.com/affaan-m/ECC/tree/main/.github/workflows "Workflows de CI, release e supply chain"
[22]: https://github.com/affaan-m/ECC/blob/main/ecc2/README.md "README do ECC2 Rust"
[23]: https://github.com/affaan-m/ECC/blob/main/pyproject.toml "Projeto Python llm-abstraction"
[24]: https://github.com/affaan-m/ECC/blob/main/LICENSE "Licença MIT do repositório"
[25]: https://github.com/affaan-m/ECC/blob/main/docs/ROADMAP.md "Roadmap documentado"
[26]: https://github.com/affaan-m/ECC/blob/main/docs/ECC-2.0-REFERENCE-ARCHITECTURE.md "Arquitetura de referência ECC 2.0"
[27]: https://github.com/affaan-m/ECC/blob/main/scripts/ci/validate-workflow-security.js "Validador de segurança de workflows"
[28]: https://github.com/affaan-m/ECC/blob/main/scripts/memory.js "CLI do Memory Vault"
[29]: https://github.com/affaan-m/ECC/blob/main/scripts/memory-mcp.mjs "Servidor MCP da memória"
[30]: https://github.com/affaan-m/ECC/blob/main/scripts/lib/resolve-ecc-root.js "Resolução de raiz do plugin"
[31]: https://github.com/affaan-m/ECC/blob/main/scripts/hooks/run-with-flags.js "Runner de hooks com modos, flags e timeouts"
[32]: https://github.com/affaan-m/ECC/blob/main/.mcp.json "Configuração MCP default"
[33]: https://github.com/affaan-m/ECC/blob/main/commands/security-scan.md "Comando de security scan e referência AgentShield"
[34]: https://github.com/affaan-m/ECC/blob/main/skills/unified-memory/SKILL.md "Skill de memória unificada"
[35]: https://github.com/affaan-m/ECC/blob/main/examples/unified-memory/README.md "Exemplo e conformance da memória"
[36]: https://github.com/affaan-m/ECC/tree/main/ecc2/src "Código Rust de sessões, TUI, worktree e observabilidade"
[37]: https://github.com/affaan-m/ECC/tree/main/src/llm "Camada Python de abstração LLM"
[38]: https://github.com/affaan-m/ECC/blob/main/skills/security-scan/SKILL.md "Skill de security scan"
[39]: https://github.com/affaan-m/ECC/blob/main/skills/gateguard/SKILL.md "Skill GateGuard"
[40]: https://github.com/affaan-m/ECC/blob/main/skills/eval-harness/SKILL.md "Skill de harness de avaliação"
[41]: https://github.com/affaan-m/ECC/blob/main/skills/verification-loop/SKILL.md "Skill de loop de verificação"
[42]: https://github.com/affaan-m/ECC/blob/main/skills/tdd-workflow/SKILL.md "Skill de TDD"
[43]: https://github.com/affaan-m/ECC/blob/main/skills/security-review/SKILL.md "Skill de revisão de segurança"
[44]: https://github.com/affaan-m/ECC/blob/main/.github/workflows/reusable-validate.yml "Workflow reutilizável de validação"
[45]: https://github.com/affaan-m/ECC/blob/main/.github/workflows/reusable-release.yml "Workflow reutilizável de release"
[46]: https://github.com/affaan-m/ECC/blob/main/.github/workflows/supply-chain-watch.yml "Workflow de monitoramento supply chain"
[47]: https://github.com/affaan-m/ECC/blob/main/tests/run-all.js "Runner da suíte de testes"
[48]: https://github.com/affaan-m/ECC/blob/main/scripts/observability-readiness.js "Readiness de observabilidade"
[49]: https://github.com/affaan-m/ECC/blob/main/scripts/session-inspect.js "Inspeção de sessões e saúde de skills"
[50]: https://github.com/affaan-m/ECC/blob/main/scripts/worktree-lifecycle.js "Lifecycle de worktrees"
[51]: https://github.com/affaan-m/ECC/blob/main/skills/continuous-learning-v2/SKILL.md "Skill de aprendizado contínuo v2"
[52]: https://github.com/affaan-m/ECC/blob/main/docs/token-optimization.md "Guia de otimização de contexto e MCP"
[53]: https://github.com/affaan-m/ECC/blob/main/docs/skill-adaptation-policy.md "Política de adaptação de skills"
[54]: https://github.com/affaan-m/ECC/blob/main/skills/agentic-engineering/SKILL.md "Skill de engenharia agêntica"
[55]: https://github.com/affaan-m/ECC/blob/main/skills/context-budget/SKILL.md "Skill de orçamento de contexto"
[56]: https://github.com/affaan-m/ECC/blob/main/skills/delivery-gate/SKILL.md "Skill de gate de entrega"
[57]: https://github.com/affaan-m/ECC/blob/main/skills/repo-scan/SKILL.md "Skill de varredura de repositório"
[58]: https://github.com/affaan-m/ECC/blob/main/skills/error-handling/SKILL.md "Skill de tratamento de erros"
[59]: https://github.com/affaan-m/ECC/blob/main/skills/git-workflow/SKILL.md "Skill de workflow Git"
[60]: https://github.com/affaan-m/ECC/blob/main/skills/strategic-compact/SKILL.md "Skill de compactação estratégica"
[61]: https://github.com/affaan-m/ECC/blob/main/skills/operator-approval-loop/SKILL.md "Skill de aprovação do operador"
[62]: https://github.com/affaan-m/ECC/blob/main/skills/continuous-agent-loop/SKILL.md "Skill de loop contínuo"
[63]: https://github.com/affaan-m/ECC/blob/main/skills/plan-canvas/SKILL.md "Skill do plan canvas"
[64]: https://github.com/affaan-m/ECC/blob/main/skills/plan-orchestrate/SKILL.md "Skill de orquestração de planos"
[65]: https://github.com/affaan-m/ECC/blob/main/skills/parallel-execution-optimizer/SKILL.md "Skill de execução paralela"
[66]: https://github.com/affaan-m/ECC/blob/main/skills/agent-self-evaluation/SKILL.md "Skill de autoavaliação"
[67]: https://github.com/affaan-m/ECC/blob/main/skills/workspace-surface-audit/SKILL.md "Skill de auditoria da superfície do workspace"

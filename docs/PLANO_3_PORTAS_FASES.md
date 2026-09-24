# BrainCode — Plano de implementação das 3 Portas em 4 fases

> Destino sugerido no repo: `docs/PLANO_3_PORTAS_FASES.md`
> Data: 2026-09-20 · Base: `docs/ARQUITETURA_3_PORTAS_SECRETARIO.md`, `docs/ROADMAP_CANONICO.md` (Marco 5) e leitura do código atual.
> Detalhe da Fase 1: `PRPs/secretario-3-portas.md`.

## Regra de execução

Fases **sequenciais**. Uma fase só começa quando a anterior estiver **consolidada com evidência verde** (CI, testes e readiness no HEAD). Cada fase tem seu PRP próprio, seguindo `PRPs/templates/prp_base.md`.

| Fase | Entrega | Depende de | Saída |
|---|---|---|---|
| 1 | Secretário + `DoorPolicy` | — | Toda ordem recebe porta e fase; Policy nega fora da porta |
| 2 | Porta 1 — Chat / Plano | Fase 1 | Conversa e planejamento reais, sem vazar para produção/execução |
| 3 | Porta 2 — Prompt | Fases 1 e 2 | Fluxo de prompt formalizado, termina sem iniciar criação |
| 4 | Porta 3 — Criação | Fases 1–3 | Discussão → aprovação → execução → entrega |

## Decisão herdada (bloqueia as Fases 2, 3 e 4)

**D1 — APIs externas.** O doc diz "APIs ❌ agora", mas o código já usa API na Porta 2 (`GatewayPromptImprover`, quando o score local é baixo) e na Porta 3 (`CodeGenerationExecutor`), e `authorizedAccountIds` é global.
Sem API, a Porta 1 só responde de forma **local e extrativa** (relógio, memória, Web + resumo de fontes); conversa livre gerada exige provider.

- **Opção A:** manter "APIs ❌" nas três portas por enquanto; conversa da Porta 1 limitada ao que é local/Web.
- **Opção B:** liberar o gateway atual só para texto de CHAT e manter a futura camada Provider (Marco 5.3) para depois.

O mecanismo (`DoorPolicy.externalAccountsAllowed(door)`) nasce na Fase 1; o valor por porta é definido por esta decisão. **Decidir antes de começar a Fase 2.**

---

## Fase 1 — Criação do Secretário

**Objetivo:** todo pedido do chat passa por um classificador determinístico que produz `OrderIntent` (porta + fase + restrições), e esse resultado vira limite verificável no Planner e na Policy.

**Escopo (PRP completo em `PRPs/secretario-3-portas.md`):**
- PR 1 — pacote `com.brain.secretary` em `:brain`: `Door`, `CreatePhase`, `Restriction`, `DoorScope`, `OrderIntent`, `SecretaryState`, `DeterministicSecretary`, `DoorPolicy`, `DoorAwareSplitter`.
- PR 2 — `PolicyContext`, `PolicyDecision` e `AuthorizationToken` ganham `doorScope`; `PolicyBroker` nega capability fora da porta; `CicloExecucaoPlano` propaga a porta ao contexto usado pelo `ActionGateway`; `BrainSandboxController.executeObjective` recebe `intent` opcional e emite `DoorDesignated`.
- PR 3 — `ThreadSession` persiste `SecretaryState`; `sendChatMessage` designa a porta sobre `resolved.currentPrompt`; comandos do catálogo `/…` entram fixos na Porta 2.
- Correção de CI aplicada na Fase 6: o `ci.yml` inclui `:android-module:testDebugUnitTest` junto dos testes do `brain` e `app`.

**Não muda:** comportamento sem `OrderIntent`; `KeywordPlannerTest` e `FunctionSplitterTest` (sem edição).

**Critérios de saída**
- Corpus de 15 classificações verde (`unit:SecretaryTest`).
- `unit:DoorPolicyTest`, `unit:PolicyBrokerDoorTest`, `unit:ActionGatewayDoorTest`, `unit:DoorAwareSplitterTest` verdes.
- `integration:BrainSandboxControllerDoorTest`: intent CHAT nunca chega a `sandbox.code`.
- Sessão antiga sem `SecretaryState` carrega normalmente.
- `bash scripts/architecture-gate.sh` e CI verdes no HEAD.

**Riscos:** vazamento por objetivo enriquecido (classificar sempre `currentPrompt`); `processarPasso` reconstruir `PolicyContext` sem porta.

---

## Fase 2 — Porta 1: Chat / Plano

**Objetivo:** conversa, pesquisa, análise e planejamento reais, com Web quando a Policy permitir e **sem** criar projeto nem executar.

**Ponto de partida (código):** o chat livre hoje vira `entender → brain.analyze`, que é mapeado para `sandbox.diagnose`; a resposta é o stdout de um script do sandbox. Não há executor conversacional.

**Tarefas**
1. **`chat.respond`:** nova capability, registrada em `BrainSandboxController`, com executor no `:app` seguindo o padrão de `WebResearchExecutor`/`PromptGenerationExecutor` e registrado no mapa `capabilityExecutors` do `SandboxViewModel`. `DoorAwareSplitter` passa a emitir `chat.respond` em CHAT; `brain.analyze` continua só no caminho legado (sem intent).
2. **Fontes de resposta (segundo D1):** relógio/data local, `LayeredMemory`/`KnowledgeMemory` (somente leitura), Web via `WebResearchExecutor` com proveniência (`ResearchResult`). Nenhuma afirmação factual da Web sem fonte anexada.
3. **Planejamento:** saída estruturada a partir do `ConversationContext` já existente (ideia, requisitos, decisões, descartados, pendências, referências). É o "Planning Agent" da Porta 1: resume e organiza, não executa.
4. **Contexto/memória:** manter `ConversationContextEngine` (continuidade só com referência explícita) e garantir que a Porta 1 acumula contexto sem disparar criação.
5. **Vazamento de intenção:** corpus de regressão de frases que **não** podem gerar pesquisa, produção ou execução (negações, "estou pensando em", "não pesquise", "sem criar nada"). Cada bug novo entra no corpus antes da correção.
6. **Restrições no plano:** `NO_WEB`, `NO_PRODUCE`, `NO_EXECUTE` respeitadas de ponta a ponta, inclusive no texto enriquecido pelo contexto.
7. **Gates para conversa:** critérios de aceite próprios de `chat.respond` (resposta não vazia; fontes quando houver Web; sem afirmação sem evidência). Hoje os rótulos de aceite são de processo ("artefato produzido") e podem gerar `validationPassed` sem sentido para chat.
8. **Requisito faltante:** quando o `RequirementGate` pede esclarecimento, `executeObjective` devolve ciclo bloqueado com passos `REPROVADO`. Na Porta 1 isso deve virar uma pergunta ao usuário, não um erro.
9. **Transições e permissões:** teste de estado da Porta 1: nunca cria projeto, nunca chama `workspace.*` nem `sandbox.*`.

**Mapeamento do doc → código (provável, confirmar):** Conversation/Analysis/Planning → `chat.respond` + `ConversationContext`; Research → `WebResearchExecutor`; Context/Memory → `LayeredMemory`, `ConversationContextEngine`.

**Critérios de saída (Marco 5.1)**
- `unit:ChatResponseExecutorTest`, `unit:ChatDoorLeakCorpusTest`, `integration:BrainSandboxControllerChatTest` verdes.
- Journey E2E de chat (com RootFS; hoje os journeys ficam `SKIPPED` sem ele).
- CI, readiness e regressão da Fase 1 verdes no HEAD. Só então marcar a Porta 1 como consolidada.

**Riscos:** qualidade da conversa sem API (D1); data/fuso no relógio local; `PostExecutionGate` produzir falso `REVISE` em respostas curtas.

---

## Fase 3 — Porta 2: Prompt

**Objetivo:** formalizar a entrada da Porta 2 pelo Secretário, preservando o que já existe, e garantir que concluir um prompt **encerra** o fluxo.

**Ponto de partida (código):** é a porta mais madura — `PromptGenerationExecutor`, `LocalPromptCreatorAgent`, `PromptLibrary`, `PromptQualityValidator`, `RevisionEngine`, pesquisa Web e feedback de prompt (`registrarFeedbackDePrompt`).

**Tarefas**
1. **Entrada formal:** `Door.PROMPT` via Secretário; comandos `/…` do catálogo já entram fixos na Porta 2 (Fase 1).
2. **Estado terminal:** após entregar o prompt, nenhum passo de `workspace.*` ou `sandbox.*`. A `DoorPolicy` já nega; falta o teste de fluxo completo e o follow-up ("melhore ele") permanecer na Porta 2.
3. **Conversa controlada com a Porta 1:** quando falta contexto para o prompt, esclarecer via `chat.respond` (permitido na matriz da Porta 2 apenas para esse fim), em vez de ciclo bloqueado.
4. **Web e restrições:** hoje pedidos visuais ganham pesquisa mesmo sem "pesquise". Com `NO_WEB` isso deve ser removido; testar.
5. **APIs (D1):** com `authorizedAccountIds` vazio, o `PromptGenerationExecutor` cai no caminho local + `RevisionEngine`. Cobrir os dois caminhos (com e sem conta autorizada) e o limiar de score.
6. **Regras próprias da Porta 2:** matriz de permissões e `NO_PRODUCE`/`NO_EXECUTE` aplicáveis; criar prompt sobre código não vira execução de código (teste já existente no splitter; manter).
7. **Não regredir:** `PromptGenerationExecutorTest`, `PromptCreatorWebResearchIntegrationTest`, `LocalPromptCreatorAgentTest`, `PromptLibraryFlowE2ETest` e o comportamento de follow-up sem progresso (nota original preservada na tag `pre-auditoria`).

**Mapeamento do doc → código (provável, confirmar):** Prompt Agent → `LocalPromptCreatorAgent`; Prompt Research → pesquisa Web do Prompt Creator; Prompt Critic → `PromptQualityValidator`/`SelfCritic`; Prompt Optimizer → `RevisionEngine`/melhorador; Prompt Library → `PromptLibrary`.

**Critérios de saída (Marco 5.2)**
- Corpus de regressão da Porta 2 verde (`unit:PromptDoorCorpusTest`).
- `integration:PromptDoorTerminalStateTest`: concluir prompt não inicia criação nem execução.
- Testes existentes de prompt verdes, sem edição.
- CI, E2E e readiness verdes no HEAD.

**Riscos:** mexer no fluxo já estável (~maior maturidade) — mudanças pequenas e sempre atrás de teste de regressão.

---

## Fase 4 — Porta 3: Criação / Desenvolvimento

**Objetivo:** fechar o fluxo pesquisa → requisitos → arquitetura → plano → aprovação → roadmap → tarefas → especialistas → integração → revisão → testes → entrega, sem criar um segundo orquestrador.

**Gate de entrada (Marco 5.3):** camada de APIs/Provider. O roadmap coloca isso entre a Porta 2 e a Porta 3. A infraestrutura existe (`ProviderRegistry`, `ProviderDispatcher`, `AccountRouter`, `BrainApiGateway`); falta consolidar por Policy e por especialidade. Decidir se entra como sub-fase 4.0 ou fica fora do escopo desta Fase.

**Tarefas**
1. **Máquina de fases:** expandir `CreatePhase` (`DISCUSSION → REQUIREMENTS → ARCHITECTURE → PLAN → APPROVED → EXECUTION → INTEGRATION → REVIEW → TESTS → DELIVERY`), persistida em `SecretaryState`/`ThreadSession`. Só `EXECUTION` em diante libera `workspace.write` e `sandbox.code`.
2. **Aprovação do usuário:** transição explícita e persistida, reaproveitando `ApprovalStore`/`FileApprovalStore` (sem mecanismo novo).
3. **Requisitos e arquitetura:** reaproveitar `ReasoningEngine`, `RequirementDiscovery`, `AssumptionManager` e `RequirementGate`; roadmap e tarefas sobre os modelos `Roadmap`/`Tarefa` e `TarefaStateMachine` (`com.brain.core`).
4. **Prompts especializados por tarefa:** `generatePrompts(roadmap, library)` da `BrainIntegrationFacade` já gera prompts a partir do roadmap; ligar ao fluxo autorizado.
5. **Especialistas amarrados:** `agent.research` e `agent.code` eram os únicos; os demais já estão declarados em `BuiltInAgentDefinitions.boundedSpecialists()` (12 definições, ainda não integradas). Declarar os demais (Requirements, Architecture, Roadmap, UI, Backend, Database, Security, Test, Review, Integration, Release) como definições bounded em `BuiltInAgentDefinitions`, sem LLM próprio, escolhidos pelo Brain, com capabilities explícitas.
6. **Execução:** `CodeGenerationExecutor`, `sandbox.build/test`, `ExecutorValidacaoProjeto` e detecção de stack já existem; ligar por tarefa e por especialista.
7. **Integração, revisão global e testes:** `PostExecutionGate` (Verification → Critic → Revision → Readiness → Learning) aplicado ao conjunto, não só por passo.
8. **Erro → responsável:** findings com o agente responsável; devolver para correção e revalidar (`RevisionFixer` já reescreve por findings); segunda opinião por outro especialista/provider só depois do gate de APIs.
9. **Entrega:** ZIP + resumo (o que foi feito, arquitetura, funcionalidades, testes, problemas, correções, providers, estado do Git). `packageLocalDelivery` existe; o Git hoje é mínimo (`GitManager`) e exige autorização por aprovação.

**Dependências externas ao Marco 5**
- **Marco 4 (jobs):** durable jobs, retry/timeout/cancelamento uniforme e recuperação de tarefas longas ainda estão abertos no roadmap; um fluxo de criação longo depende disso.
- **Marco 3 (segurança):** isolamento OS-level, limites de CPU/memória/PIDs, trust chain do RootFS e SSRF/DNS rebinding ainda estão abertos. Como a Porta 3 executa código gerado, avaliar esses gaps antes de liberar `EXECUTION` por padrão.

**Critérios de saída (Marco 5.4)**
- Fluxo completo coberto por E2E de criação (`e2e:creation-journey`).
- `unit:CreatePhaseMachineTest`: nenhuma escrita/execução antes de `APPROVED`.
- `integration:CreateApprovalTest`, `integration:CreateDeliveryTest` (ZIP + resumo).
- CI, E2E e readiness verdes no HEAD final. Só então declarar o Marco 5 concluído.

**Riscos:** escopo (é a maior fase; quebrar em sub-PRPs por bloco: máquina de fases → especialistas → integração/revisão → entrega); execução de código gerado antes do endurecimento do Marco 3; dependência de provider para segunda opinião.

---

## Práticas em todas as fases

- Um PRP por fase; validação progressiva: estático → unit/contrato → integração → E2E → readiness. Corrigir a primeira falha real antes de confiar nos níveis seguintes.
- O corpus de portas cresce a cada bug; nenhuma regra é ajustada antes de a linha entrar no corpus.
- A Policy é a autoridade; o Planner nunca é a única barreira.
- Atualizar `ROADMAP_CANONICO.md`, `ARQUITETURA_3_PORTAS_SECRETARIO.md` e `ESTADO_ATUAL.md` a cada fase, marcando itens só com evidência executada.
- Os percentuais 72/88/81% do roadmap são estimativas do próprio doc; use o critério de saída de cada fase, não o percentual.


### Referência futura da Porta 2 — MeiGen / capacidades visuais

Durante a preparação da Porta 2 foi identificado o projeto **MeiGen AI / MeiGen-AI-Design-MCP** como referência que pode casar diretamente com o domínio de prompts.

A referência deve ser tratada como uma possível **capacidade/Skill visual da Porta 2**, e não como um segundo cérebro. Os conceitos relevantes para auditoria futura são:

- Prompt Crafter / criação e refinamento;
- Gallery/Reference Research;
- biblioteca de prompts;
- especialistas/subagentes separados;
- execução paralela quando houver independência;
- Skills/commands;
- MCP;
- geração de imagem/vídeo;
- ComfyUI local;
- providers/API externos posteriormente.

Fluxo desejado no BrainCode:

```
Secretary
  ↓
Porta 2
  ↓
Prompt Specialist
  ↓
Prompt Crafter + Research + Critic/Optimizer
  ↓
Agent Self-E2E
  ↓
Secretary / Door E2E
  ↓
Provider autorizado
```

O BrainCode não deve simplesmente importar o projeto inteiro. Antes de qualquer incorporação: **AUDITAR → LICENÇA → CÓDIGO REAL → TESTES → SEGURANÇA → COMPATIBILIDADE → DECISÃO → INCORPORAR/ADAPTAR/REFERENCIAR → VALIDAR → DOCUMENTAR PROVENIÊNCIA**.

Mesmo com um provider MeiGen/ComfyUI instalado no código, **provider instalado ≠ provider autorizado**. APIs/providers continuam bloqueados até o Marco 5.3 e pela Policy.

O conceito só deve ser implementado na Porta 2 depois que a Porta 1 estiver formalmente consolidada com CI/E2E/readiness verdes.

---

## Fase 6 — CI, testes e prevenção de regressão

Itens só devem ser marcados após execução da verificação correspondente no mesmo HEAD. A coluna **Evidência** deve conter o workflow, comando ou artefato que confirmou o item.

| Estado | # | Ação | Esforço | Evidência |
|---|---:|---|---|---|
| [x] | 6.1 | Corrigir `BrainSandboxControllerExecutionTraceTest`: passar o `capabilityResolver` de teste também ao segundo cenário. | P | `android-module:testDebugUnitTest`, HEAD `5c5e893` |
| [x] | 6.2 | Reescrever `RootfsDnsToctouFixDesignTest` para exercitar `SandboxResourceManager`, ou removê-lo com justificativa porque `SandboxResourceManagerDnsPinningTest` já cobre o caso. | M | Teste de design removido; cobertura mantida em `SandboxResourceManagerDnsPinningTest`, HEAD `9cae0bc` |
| [x] | 6.3 | Adicionar ao CI `python3 -m unittest discover -s tests`, mantendo `brain_runtime` sob a decisão D3. | P | `.github/workflows/ci.yml`; 159 testes Python OK |
| [x] | 6.4 | Executar `scripts/architecture-gate.sh` no CI, instalando `ripgrep` quando necessário. | P | `.github/workflows/ci.yml`; gate local OK |
| [~] | 6.5 | Adicionar `scripts/doc-lint.sh` para links Markdown vivos, identificadores canônicos e arquivos declarados como removidos no `LEGADO`. | M | Links e marcadores de remoção OK; validação automática de identificadores canônicos ainda pendente |
| [x] | 6.6 | Adicionar `scripts/orphan-check.py` informativo, com baseline aprovado na Fase 3.3. | M | `scripts/orphan-check.py` executado informativamente |
| [x] | 6.7 | Remover o step duplicado do grader semântico Roofts ou documentar a justificativa. | P | Step duplicado removido de `.github/workflows/ci.yml` |
| [x] | 6.8 | Disponibilizar `scripts/validate-release-readiness.sh` como job manual via `workflow_dispatch`. | P | Job `release-readiness` manual em `.github/workflows/ci.yml` |

## Fase 7 — Validação final e entrega

| Estado | Critério de aceite | Evidência |
|---|---|---|
| [x] | `:brain:test :android-module:test :app:testDebugUnitTest` verdes no mesmo HEAD, com remoções justificadas. | Build final: 109 tarefas, sucesso; HEAD `5c5e893` |
| [x] | `:app:assembleDebug`, `:app:lintDebug` e `scripts/verify-apk-assets.sh` verdes. | APK asset gate: 25 Roofts Skills + trusted signing keys |
| [x] | `scripts/architecture-gate.sh` verde. | Gate local OK no HEAD `5c5e893` |
| [ ] | UI E2E (`ui-e2e.yml`) verde no emulador. | — |
| [~] | `doc-lint` verde, sem links ou símbolos quebrados nos documentos canônicos. | `doc-lint` verde para links/marcadores; símbolos canônicos pendentes |
| [ ] | APK abre e percorre Chave de API, Skills, Comandos, Prompt Library, Chat e Criação com aprovação. | — |
| [x] | `git diff --stat` revisado e limitado às alterações previstas. | Checkpoints Git limpos após cada correção |
| [ ] | `LEGADO_E_DECISOES.md` atualizado com o resumo da execução e o plano removido somente após a conclusão. | Aguardando UI E2E e lint de símbolos |

### Regra de atualização

Não marcar itens por inspeção estática ou por compilação parcial. Cada caixa deve ser marcada somente depois de a evidência ter sido executada no mesmo `HEAD` que será entregue; falhas devem permanecer desmarcadas e ser registradas ao lado do comando correspondente.

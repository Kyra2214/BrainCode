# BrainCode — Estado Atual

HEAD funcional da auditoria: será atualizado após o commit da Fase 2.

## Consolidado

Capability Registry/Discovery, PolicyBroker, ActionGateway, Dispatcher, Agents bounded, SkillRegistry, ReasoningEngine, RequirementDiscovery, AssumptionManager, ContextPack, Planner, AcceptanceCriteria, PlanningGate, AuthorizedPlan, BrainSandboxController, CicloExecucaoPlano, DurableJobRunner/WorkflowEngine, PostExecutionGate, Verification, UniversalCritic, Revision/Fix, Readiness, ValidatedLearning, LayeredMemory e EventStore/BehaviorTrace.

A UI possui estados de planejamento, execução, verificação, crítica, revisão, correção, reexecução, PASS/BLOCKED/FAILED/READY.

RooftS é uma entidade única composta pelas camadas 0.3–0.6. As camadas 0.3–0.5 permanecem preservadas e 0.6 / Agent Skills está instalada como camada do mesmo RooftS.

## 2.4 Context Engineering

Implementado: CLAUDE.md, AGENTS.md, INITIAL.md, PRP template, contrato CONTEXT_ENGINEERING.md e ContextPack como entrada tipada do PlanoExecucao.

Não implementado ainda: retrieval semântico geral, hashing/deduplicação/chunking geral, ranking lexical/semântico e executor universal de retrievalHints.

## Correção encontrada nesta auditoria

Planner.kt já tentava fazer PlanoExecucao.copy(contextPack=...), mas PlanoExecucao não possuía esse campo. Isso era uma inconsistência real do contrato.

Commit caf0d396 corrigiu o contrato: PlanoExecucao agora possui contextPack e os Tasks derivados preservam esse contexto.

## Fechamento 2.3 / estado atual

A consolidação comportamental da 2.3 está integrada no caminho Android: Reasoning → RequirementGate → Planner → Policy → Dispatcher/ActionGateway → execução → PostExecutionGate (Verification → UniversalCritic → Revision/Fix → Readiness → ValidatedLearning).

Correção aplicada neste HEAD: os requisitos descobertos pelo Reasoning agora são propagados explicitamente ao PostExecutionGate/UniversalCritic, além de continuarem alimentando o Planner.

O chat não usa fallback direto para provider quando o Sandbox não está pronto: a UI retorna estado de indisponibilidade e pede a preparação do Sandbox. Portanto não existe uma segunda semântica de “resposta validada” fora do caminho universal.

Roofts 0.6 permanece instalado como payload independente e deliberadamente **não integrado ao runtime**. Retrieval universal/semântico, hardening OS-level e leases/fencing continuam backlog de marcos posteriores.

CI/E2E devem ser considerados somente após execução comprovada no HEAD deste documento.

## Fase 1 — implementação incremental em 20/09/2026

O primeiro módulo da consolidação das três portas foi implementado sem substituir o Brain Core. O pacote `com.brain.secretary` agora contém `Door`, `CreatePhase`, `Restriction`, `DoorScope`, `OrderIntent`, `SecretaryState`, `DeterministicSecretary` e `DoorPolicy`. O `DoorAwareSplitter` aplica a intenção antes de criar o `PlanoExecucao`.

O escopo da porta é transportado pelo `PolicyContext`, `PolicyDecision`, `AuthorizationToken` e `ExecutionAuthorization`. O `PolicyBroker` continua sendo a autoridade final. O `BrainSandboxController` emite `DoorDesignated`, e `ThreadSession` persiste `SecretaryState` mantendo compatibilidade com JSONs antigos sem esse campo.

A decisão D1 permanece na opção A: APIs e contas externas seguem bloqueadas nas três portas. A Web continua sendo uma capability sujeita à matriz e às restrições explícitas.

### Evidências executadas

- Os testes focados de classificação, matriz, PolicyBroker, ActionGateway e DoorAwareSplitter passaram.
- `BrainSandboxControllerDoorTest` passou e comprovou que uma intenção CHAT não produz passos `workspace.write` ou `sandbox.code`.
- `:app:compileDebugKotlin` passou com JDK 17 e Android SDK 34.
- O teste `WebResearchIntegrationTest` já falhava no HEAD anterior por bloqueios do PostExecutionGate em fixtures fake; ele não foi usado como evidência de regressão da Fase 1.
- A Fase 1 foi consolidada no commit `14d8d11`; o CI remoto `35529958633` passou com testes, lint e upload do APK.

## Fase 2 — Porta 1: Chat / Plano

`chat.respond` foi registrado como capability local e o `DoorAwareSplitter` passou a produzir o passo conversacional para intenções `CHAT`, mantendo `network.research` como dependência apenas quando a Policy permite. O `ChatResponseExecutor` responde data/hora local, contexto da sessão em modo somente leitura e resumos de pesquisa recebidos como evidência; não chama API, provider, shell, workspace ou execução.

### Evidências locais executadas

- `ChatDoorLeakCorpusTest` e `ChatResponseExecutorTest` passaram.
- `BrainSandboxControllerChatTest` e `BrainSandboxControllerDoorTest` passaram.
- `:brain:test :android-module:test :app:testDebugUnitTest` passou.
- `bash scripts/architecture-gate.sh` e `git diff --check` passaram.
- CI remoto, readiness/E2E e consolidação formal da Porta 1 ainda são gates do commit desta fase.

## Fase 3 — Porta 2: Prompt

O fluxo formal de `Door.PROMPT` foi coberto com corpus de entrada, follow-up e `NO_WEB`. A entrega por `prompt.library.write`/`prompt.library.generate` foi testada como terminal, sem `workspace.*` ou `sandbox.*`; o Prompt Creator existente permanece responsável por biblioteca, pesquisa, crítica, melhoria e fallback local.

### Evidências locais executadas

- `PromptDoorCorpusTest` passou.
- `PromptDoorTerminalStateTest` passou.
- A regressão completa do Prompt Creator permanece incluída no gate global da fase.
- CI remoto, E2E/readiness e consolidação formal da Porta 2 ainda são gates do commit desta fase.

## Fase 4.0 — Porta 3: máquina, aprovação e especialistas bounded

`CreatePhaseMachine` e `SecretaryState.approve()` registram a sequência `DISCUSSION → REQUIREMENTS → ARCHITECTURE → PLAN → APPROVED` e bloqueiam saltos ou execução prematura. O `SandboxViewModel` reconhece aprovação explícita para uma intenção CREATE ativa e persiste o novo estado antes de executar. `BuiltInAgentDefinitions.boundedSpecialists()` declara os 11 especialistas previstos com provenance local e sem provider próprio.

### Evidências locais executadas

- `CreatePhaseMachineTest` e `BuiltInSpecialistsTest` passaram.
- `CreateApprovalTest` passou: zero escrita antes de APPROVED e uma execução controlada após aprovação.
- Regressão completa, architecture gate e diff check passaram.
- Delivery ZIP, integração/revisão final, CI remoto e APK continuam gates posteriores; nenhum APK é entregue nesta subfase.

## Fase 4.1 — integração, tarefas e delivery local

O fluxo de tarefas existente foi coberto até QA/aprovação/correção, e `DefaultPromptGenerator` permanece a fonte local para prompts por roadmap. `LocalDeliveryPackager` agora centraliza o ZIP verificável no Brain Core; a facade Android publica primeiro o recibo local e então cria o arquivo, sem rede ou provider.

### Evidências locais executadas

- `TarefaDeliveryFlowTest` passou.
- `LocalDeliveryPackagerTest` passou e verificou `README.md`, código e exclusão do próprio ZIP.
- `:app:compileDebugKotlin` passou após a extração do empacotador.
- APK ainda não foi gerado/entregue; falta o gate final de regressão/CI e a verificação do APK final.

### Correção de gaps da Fase 4

Uma auditoria posterior encontrou que a aprovação da criação estava apenas no estado da sessão e não era ligada ao `FileApprovalStore`, que o chat não armazenava o `approvalId` para o botão de retomada, e que roadmap/tarefas/especialistas ainda não eram compostos pelo fluxo. Esses gaps foram corrigidos: `executeObjective` cria `ApprovalRequest` persistente, o ViewModel decide e consome a aprovação pelo botão, o plano original é retomado, e `CreationWorkflowPlanner` registra roadmap, tarefas e assignments de especialistas. Também foram adicionados `CreateDeliveryTest`, `FileApprovalStorePersistenceTest` e uma jornada E2E de criação.

### Gates remotos finais

- CI `35536050052` passou com testes JVM/Android, lint e build/upload do APK.
- UI E2E `35536055182` passou no emulador, incluindo `creationJourneyRequiresApprovalBeforeWorkspaceExecution`.
- Commit validado: `f859426373dffdfdb0d7a6f13da205a61964c0d1`.

### Reabertura e correção da Fase 2 — Porta 1

Uma auditoria identificou que a Porta 1 ainda não tinha um Planning Agent materializando um artefato persistente e que o `NEEDS_CLARIFICATION` ainda não era um contrato verificável de pergunta via `chat.respond`. A correção adiciona `PlanningAgent`, `PlanningArtifact` e `FilePlanningArtifactStore`, além de `ClarificationQuestion`, evento `ClarificationRequested` e evidência `chat:clarification-question`. Os testes unitários e a integração Android desses caminhos passaram localmente; CI, UI E2E e readiness permanecem pendentes para o novo HEAD.

## Fase conversacional — adapter no-inference

O `ChatResponseExecutor` agora recebe o `NoInferenceConversationEngine`, adapter Kotlin/Android baseado no núcleo conversacional determinístico de `TheShovel/no-inference`. O engine carrega padrões sociais, templates, aliases e knowledge como dados em `app/src/main/assets/no_inference/`, além de uma base local em português para explicações. Ele cobre saudações, despedidas, agradecimentos, identidade, estado social, lookup factual local, tópico, follow-up e fallback conversacional neutro.

O caminho não altera a Porta 1: CHAT continua no fast path e não chama Planner, RequirementGate, Reasoning pesado, CodeAgent ou ResearchAgent para conversa simples. `ResponseComposer` continua sendo o ponto que transforma o resultado em texto e `PostExecutionGate` continua validando a resposta. Pesquisa e dados atuais permanecem no `CapabilityRegistry`/capability externa autorizada; o engine não finge que knowledge estática é dado atual.

### Proveniência, licença e exclusões

A origem é `https://github.com/TheShovel/no-inference`, licenciado sob AGPL-3.0; a cópia da licença foi preservada em `app/src/main/assets/no_inference/LICENSE`. A integração importou apenas recursos conversacionais e o adapter Android. CLI, TUI, servidor, API web, `cos` coding agent, editor, code generator, math solver, integrações externas e infraestrutura de execução do projeto de origem foram deliberadamente excluídos do runtime BrainCode. A distribuição do APK deve manter a oferta de código-fonte e os notices exigidos pela licença AGPL aplicáveis ao componente integrado.

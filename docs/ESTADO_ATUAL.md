# BrainCode — Estado Atual

Sem HEAD fixo: este documento é atualizado incrementalmente a cada fase, não a cada commit. Cada seção abaixo registra o commit e as evidências verificadas na época; o estado consolidado mais recente é o das últimas seções ("Fase conversacional", "Research Harness e Web Access" e "Ciclo textual").

## Consolidado

Capability Registry/Discovery, PolicyBroker, ActionGateway, Dispatcher, Agents bounded, SkillRegistry, ReasoningEngine, RequirementDiscovery, AssumptionManager, ContextPack, Planner, AcceptanceCriteria, PlanningGate, AuthorizedPlan, BrainSandboxController, CicloExecucaoPlano, DurableJobRunner/WorkflowEngine, PostExecutionGate, Verification, UniversalCritic, Revision/Fix, Readiness, ValidatedLearning, LayeredMemory e EventStore/BehaviorTrace.

A UI possui estados de planejamento, execução, verificação, crítica, revisão, correção, reexecução, PASS/BLOCKED/FAILED/READY.

RooftS é uma entidade única composta pelas camadas 0.3–0.6. As camadas 0.3–0.5 permanecem preservadas e 0.6 / Agent Skills está instalada como camada do mesmo RooftS.

## 2.4 Context Engineering

Implementado: CLAUDE.md, AGENTS.md, PRP template, contrato CONTEXT_ENGINEERING.md e ContextPack como entrada tipada do PlanoExecucao.

Não implementado ainda: retrieval semântico geral, hashing/deduplicação/chunking geral, ranking lexical/semântico e executor universal de retrievalHints.

## Correção encontrada nesta auditoria

Planner.kt já tentava fazer PlanoExecucao.copy(contextPack=...), mas PlanoExecucao não possuía esse campo. Isso era uma inconsistência real do contrato.

Commit caf0d396 corrigiu o contrato: PlanoExecucao agora possui contextPack e os Tasks derivados preservam esse contexto.

## Fechamento 2.3 / estado atual

A consolidação comportamental da 2.3 está integrada no caminho Android: Reasoning → RequirementGate → Planner → Policy → Dispatcher/ActionGateway → execução → PostExecutionGate (Verification → UniversalCritic → Revision/Fix → Readiness → ValidatedLearning).

Correção aplicada neste HEAD: os requisitos descobertos pelo Reasoning agora são propagados explicitamente ao PostExecutionGate/UniversalCritic, além de continuarem alimentando o Planner.

O chat não usa fallback direto para provider quando o Sandbox não está pronto: a UI retorna estado de indisponibilidade e pede a preparação do Sandbox. Portanto não existe uma segunda semântica de “resposta validada” fora do caminho universal.

Roofts 0.6 está integrado ao runtime de descoberta: `RooftsSkillLoader` mantém catálogo lazy, `RooftsSkillSelector` participa da seleção da Porta 3, `RooftsSkillActivationPlanner` filtra permissões pendentes e `RooftsSkillManifestBridge` publica manifestos no `SkillRegistry` como registros desabilitados. Corpo, recursos, scripts e efeitos continuam deliberadamente fora da execução autorizada. Retrieval universal/semântico, hardening OS-level e leases/fencing continuam backlog de marcos posteriores.

CI/E2E devem ser considerados somente após execução comprovada no HEAD deste documento.

## Fase 1 — implementação incremental em 20/09/2026

O primeiro módulo da consolidação das três portas foi implementado sem substituir o Brain Core. O pacote `com.brain.secretary` agora contém `Door`, `CreatePhase`, `Restriction`, `DoorScope`, `OrderIntent`, `SecretaryState`, `DeterministicSecretary` e `DoorPolicy`. O `DoorAwareSplitter` aplica a intenção antes de criar o `PlanoExecucao`.

O escopo da porta é transportado pelo `PolicyContext`, `PolicyDecision`, `AuthorizationToken` e `ExecutionAuthorization`. O `PolicyBroker` continua sendo a autoridade final. O `BrainSandboxController` emite `DoorDesignated`, e `ThreadSession` persiste `SecretaryState` mantendo compatibilidade com JSONs antigos sem esse campo.

A decisão D1 permanece na opção A. **Atualização de 23/09/2026:** o gating booleano `DoorPolicy.externalAccountsAllowed(door)` foi adiantado (ver Fase 5 / `docs/LEGADO_E_DECISOES.md`, "Escalonamento da Porta 2 e DoorPolicy"): `Door.CHAT` continua `false` (contas externas bloqueadas), mas `Door.PROMPT` e `Door.CREATE` já retornam `true` — a conta fica visível ao executor, que decide se a chama. Isso não antecipa a camada completa de integração de APIs/providers do Marco 5.3. A Web continua sendo uma capability sujeita à matriz e às restrições explícitas.

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

`CreatePhaseMachine` e `SecretaryState.approve()` registram a sequência `DISCUSSION → REQUIREMENTS → ARCHITECTURE → PLAN → APPROVED` e bloqueiam saltos ou execução prematura. O `SandboxViewModel` reconhece aprovação explícita para uma intenção CREATE ativa e persiste o novo estado antes de executar. `BuiltInAgentDefinitions.boundedSpecialists()` declara os 12 especialistas previstos (`BuiltInSpecialistsTest` exige 12) com provenance local e sem provider próprio. **Estado real:** são apenas definições declarativas — hoje só testes as consomem; não estão registrados no `CapabilityRegistry` do `BrainSandboxController` nem são usados pelo `CreationWorkflowPlanner`. A integração pertence ao Marco 5.4/5.5 (especialistas com executor + contrato + Self-E2E).

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

Quando o `NoInferenceConversationEngine` retorna local miss (`null` ou `knowledge.unknown`) para uma pergunta informacional, o `ChatResponseExecutor` agora usa o `WebResearchAgent` injetado automaticamente. A resposta local continua prioritária; apenas o miss dispara `ResearchRequest → fontes → síntese determinística`. A interface recebe apenas a síntese natural. citations, evidence, quality, provenance e diagnostics permanecem internos. Esse fallback não pede autorização ao usuário e não pode transformar a pergunta em CREATE; uma ordem como `Crie um app IPTV` continua sendo tratada pelo Intent Envelope e pelo fluxo de requirements/approval.

O resultado aprovado passa pelo `ResearchKnowledgePromoter` e pelo `KnowledgeLearningCycle` já existente, não por uma memória paralela. O Knowledge Store salva resposta estruturada, citations, `WEB_RESEARCH`, confiança e expiração; perguntas temporais têm TTL curto, entradas equivalentes são deduplicadas e conteúdo detectado como prompt injection não é promovido. Assim, a primeira pergunta segue MISS → WEB → RESPOSTA → APRENDE e uma reformulação válida pode seguir HIT → RESPOSTA LOCAL.

## Research Harness e Web Access — implementação incremental

Foi criado o contrato soberano `ResearchRequest`, `ResearchRunResult`, `ResearchCitation`, `ResearchEvidence`, `FailedSource`, `ResearchExecutionMetadata` e `WebProviderSet`. `WebResearchAgent` aplica sanitização, política de rede, allow/block domains, HTTPS, fallback entre SearchProviders, source diversity, citações, evidence, quality/confidence e user-facing fallback sem vazar erro técnico. `WebResearchExecutor` usa esse harness por meio de `LegacySearchProviderAdapter`, portanto o provider existente continua conectado ao Dispatcher/ActionGateway sem criar uma segunda capability.

Foram adicionadas interfaces desacopladas `SearchProvider`, `FetchProvider`, `BrowserProvider` e `ExtractionProvider`. Elas deixam Firecrawl opcional e permitem cache/local providers no futuro. `ResearchSecurityPolicy` detecta prompt injection em conteúdo web e mantém o conteúdo como dado, nunca como Policy ou instrução do Brain. `AgentRegistry` registra os especialistas por contrato, categoria, capability, provenance e licença.

SearchClaw foi usado como referência de harness de pesquisa, plano, quality gate, citações, memória e compactação; firecrawl/web-agent como referência de abstração de ferramentas, skills, schemas e subagentes. Nenhum servidor, CLI/TUI ou runtime externo foi incorporado. SearchClaw e Firecrawl são MIT; o componente no-inference permanece AGPL-3.0 com notice preservado em `app/src/main/assets/no_inference/LICENSE`. O coding agent `cos` segue excluído.

### Evidências da frente de research

- `WebResearchAgentTest` cobre agregação de fontes, citações, evidence, quality, falha parcial, offline e prompt injection.
- `AgentRegistryTest` comprova resolução por capability/contrato e provenance externo.
- `:brain:test` passou para os novos contratos e testes focados; build Android/E2E fica para o gate remoto após o commit desta frente.

### Proveniência, licença e exclusões

A origem é `https://github.com/TheShovel/no-inference`, licenciado sob AGPL-3.0; a cópia da licença foi preservada em `app/src/main/assets/no_inference/LICENSE`. A integração importou apenas recursos conversacionais e o adapter Android. CLI, TUI, servidor, API web, `cos` coding agent, editor, code generator, math solver, integrações externas e infraestrutura de execução do projeto de origem foram deliberadamente excluídos do runtime BrainCode. A distribuição do APK deve manter a oferta de código-fonte e os notices exigidos pela licença AGPL aplicáveis ao componente integrado.

## Ciclo textual — única interface humana

O novo contrato está implementado no núcleo e no executor Android. `ConversationResult`, `ConversationStatus`, `SecretaryDecision`, `BlockReason`, `SecretaryEvaluation` e `UserResponse` tipam a fronteira; `DeterministicSecretaryGate` é o gate centralizado e determinístico. A rota local segue `Conversation → Secretary ACCEPT → UI`. Em `LOCAL_KNOWLEDGE_MISS`, quando a pergunta é informacional e há provider disponível, segue `Conversation → Secretary BLOCK → Orchestrator → WebSearch → ResearchResult → Conversation synthesis → Secretary ACCEPT → UI`.

O `ChatResponseExecutor` registra evidências explícitas (`chat:conversation`, `chat:secretary:block`, `chat:websearch:executed`, `chat:websearch:evidence`, `chat:conversation:synthesis` e `chat:secretary:accept`), limita a recuperação a uma tentativa e só promove conhecimento depois do `ACCEPT`. WebSearch nunca devolve texto diretamente à UI; fontes, citations, evidence e diagnósticos continuam internos. Fallbacks neutros e perguntas do tipo “quer que eu pesquise?” foram removidos da saída final.

O teste de contrato `TextConversationContractsTest` impede que fallback, resultado bruto ou resposta sem evidência atravessem o gate. O teste Android do executor comprova o fast path local e a recuperação automática com as seis evidências mínimas do ciclo. CI, UI E2E e APK devem ser executados no novo commit antes da declaração de conclusão.

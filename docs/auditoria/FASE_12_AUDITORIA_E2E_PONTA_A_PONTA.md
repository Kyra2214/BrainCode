# Fase 12 — Auditoria de ponta a ponta (pós Fase 4.1 / correções de escalonamento)

**Snapshot auditado:** conteúdo de `BrainCode-corrigido.zip` (pós-correção do `PLANO_CORRECAO_AUDITORIA_ESCALONAMENTO.md`).
**Regra:** código é a fonte da verdade; documentação (`docs/ARQUITETURA_ATUAL.md`, `docs/ESTADO_ATUAL.md`, `docs/LEGADO_E_DECISOES.md`) foi usada só como guia de onde procurar e como base de comparação — nunca como prova de que algo está de fato ligado.

## 1. Método e limitação declarada

Sem acesso à rede neste ambiente, não foi possível baixar o Gradle wrapper (`services.gradle.org` retornou 403) nem `kotlinc`, então **não houve compilação real**. A auditoria foi feita por leitura de código e varredura estática (grep de declarações vs. chamadas/instanciações em todo o repositório, incluindo testes) para cada classe/interface dos módulos `brain/`, `android-module/` e `app/`. Isso comprova *ausência de caller em produção* com confiança alta, mas não substitui `:brain:test`/`:app:testDebugUnitTest`/CI remoto, que continuam sendo o gate formal — este documento não declara build verde.

## 2. O que foi confirmado íntegro (documentação bate com o código)

- O caminho canônico Android de execução real é único: `BrainSandboxController → BrainSandboxExecutionBridge → CicloExecucaoPlano → Dispatcher → ActionGateway → Executor/Sandbox/Provider`, exatamente como descrito na seção 4 de `ARQUITETURA_ATUAL.md`. Não existe um segundo caminho concorrente para o mesmo papel.
- `BrainExecutionCoordinator` (JVM legado, com fallback de conta/provider e retry) tem zero chamadores em `app/`/`android-module/`, mas isso é **decisão documentada e anotada em código** (`@Deprecated`, KDoc explícito, e `docs/LEGADO_E_DECISOES.md` linha 19/72–94 descrevendo exatamente esse estado). Não é um achado novo, é um caso corretamente resolvido — confirmado apenas para constar que a auditoria olhou para ele.
- RoofTS 0.6 / Agent Skills não é referenciado por `Planner`, `Dispatcher` ou `ActionGateway` — bate com a seção 7 de `ARQUITETURA_ATUAL.md` ("está instalada como payload, mas suas Skills ainda não são um runtime ativo").
- Não existe executor universal de `retrievalHints` fora do que já é consumido por `KnowledgeMemory`/`KnowledgeCompiler`/`KnowledgeLearningCycle` — bate com "Executor geral de retrievalHints ainda é backlog" (seção 6).
- `BehaviorDiagnostics`/`EventStoreBehaviorTraceSink` estão de fato instanciados em `BrainSandboxController.kt:116`, confirmando a frase "EventStoreBehaviorTraceSink usa o mesmo EventStore auditável".
- `PlanningAgent`/`FilePlanningArtifactStore` (Fase 2 reaberta) estão realmente wired em `BrainSandboxController`, não só em teste.
- `LlmConversationInterpreter` e `LlmOutputReviewer` (adapters LLM de conversação) estão de fato instanciados em `SandboxViewModel.kt`, via `ConversationBrainGatewayAdapter` — o adapter real de produção do gateway conversacional.

## 3. Achados — código presente, testado, mas não conectado ao caminho real

Nenhum destes quebra o build (por isso não apareceram em auditorias anteriores focadas em compilação), mas cada um é uma peça funcional sem caller em produção — divergindo do princípio da seção 9 de `ARQUITETURA_ATUAL.md` ("não criar segundo... para a mesma responsabilidade") ou representando comentário/intenção que o código não cumpre.

### 3.1 `ExecutionTrace`/`TraceSink` nunca é ativado em produção
`com.brain.gateway.ActionGateway` (`brain/src/main/kotlin/com/brain/gateway/ActionGateway.kt:110-116`) recebe `trace: ExecutionTrace? = null` como parâmetro opcional e chama `trace?.record(...)` em cada estágio (`TASK`, `CAPABILITY`, `POLICY`...). Os **dois únicos pontos de instanciação real** de `ActionGateway` (`SandboxPlatform.kt:45` e `BrainSandboxController.kt:138`, este último o caminho canônico) **não passam esse parâmetro** — `trace` fica sempre `null`. `ExecutionTrace`/`InMemoryTraceSink` só são exercitados em `PlanGapCoverageTest` e `BrainEndToEndTest`.
**Efeito real:** toda a instrumentação de trace por estágio (`TraceStage`) documentada em código simplesmente não roda no app — é uma capacidade construída e testada, mas desligada por omissão do parâmetro nos dois wiring points reais.
**Ação sugerida:** decidir explicitamente — ligar `trace` em `BrainSandboxController` (e opcionalmente `SandboxPlatform`) ou remover o parâmetro/testes e documentar como descontinuado, para não haver expectativa de que ele funciona.

### 3.2 `BehaviorGate` (Verification/Critic/Readiness/Learning) é uma abstração paralela só testada em unidade
`brain/src/main/kotlin/com/brain/behavior/BehaviorGates.kt` declara `VerificationGate`, `CriticGate`, `ReadinessGate` e `LearningGate` (além de `RequirementGate`/`PlanningGate`, esses sim usados em produção via `BrainSandboxController`). As quatro primeiras só aparecem em `BehaviorGatesTest.kt` — nunca em `PostExecutionGate.kt`, que é o pós-execução real do caminho Android. `PostExecutionGate` reimplementa a mesma decisão (comparar `CritiqueStatus`, `ReadinessStatus`, `evidence.verified`) diretamente contra `UniversalCritic`/`ReadinessEvaluator`/`ValidatedLearning`, sem passar pelos wrappers `Gate`.
**Efeito real:** duas implementações da mesma regra de negócio existem lado a lado (uma exercitada só por teste unitário, outra é a que roda de fato); mudar uma sem lembrar da outra é o risco de divergência silenciosa que a seção 9 do documento de arquitetura tenta evitar. Nenhum documento explica essa duplicação como intencional (diferente do caso do `BrainExecutionCoordinator`, que tem essa nota).
**Ação sugerida:** ou `PostExecutionGate` passa a delegar para os `Gate`s (removendo a lógica duplicada), ou os quatro `Gate`s não usados são removidos/marcados como utilitário de teste apenas, com nota equivalente à do `BrainExecutionCoordinator`.

### 3.3 `PolicyGatedExecutor` — comentário diz "ponte obrigatória", código nunca o instancia — ✅ REMOVIDO (`PLANO_CONEXAO_FASE_12.md`)
`app/src/main/kotlin/com/sandbox/sandbox/PolicyGatedExecutor.kt` tinha o comentário `/** Ponte obrigatória para Git, toolchains, TestLab, diagnostics e plugins. */` e chamava `PolicyBroker.authorize(...)` antes de delegar. Não havia nenhuma instanciação dele em nenhum lugar do repositório (produção ou teste). Em `SandboxPlatform.kt`, o caminho real já é `SecureCommandExecutor(GatewayBackedSandboxExecutor(actionGateway, ...), policy)`, e `GatewayBackedSandboxExecutor` já passa por `ActionGateway.execute()`, que já chama `PolicyBroker.authorize` internamente. `SecureCommandExecutor` adiciona só uma camada de heurística estática (blocklist/allowlist de comando), sem envolver `PolicyBroker`.
**Efeito real (antes da remoção):** o comentário prometia uma verificação de `PolicyBroker` "obrigatória" que, por esse arquivo, nunca acontecia — a policy real já era coberta por outro caminho (`ActionGateway`), então não havia brecha de segurança, mas o comentário e a classe eram enganosos para quem lesse o código depois achando que essa era a porta de entrada.
**Ação executada:** arquivo `PolicyGatedExecutor.kt` removido — a policy continua garantida via `ActionGateway.execute()`, sem duplicação. Isso substitui a recomendação anterior de "manter" registrada em `FASE_11_ORFAOS_SECUNDARIOS.md` (ver nota naquele documento).

### 3.4 Implementações alternativas nunca escolhidas (mortas por omissão, não por decisão registrada)
- `ContextRevisionFixer` (`android-module/.../RevisionFixer.kt`) implementava `RevisionFixer`, mas `BrainSandboxController.kt:108` usa `FindingsRevisionFixer` como padrão; `ContextRevisionFixer` não era referenciado em nenhum outro lugar — **✅ REMOVIDO** (`PLANO_CONEXAO_FASE_12.md`, seção 4): a classe foi apagada de `RevisionFixer.kt`, mantendo `RevisionFixer` (interface), `FindingsRevisionFixer` e `RevisionFixVerifyLearn` intactos.
- `HttpProviderClient` (`brain/.../ProviderClient.kt`) implementa `ProviderClient`, mas a produção (`app/.../BrainApiGateway.kt`) usa a classe privada `AndroidProviderClient`; `HttpProviderClient` não é instanciado em nenhum outro lugar, nem em teste.
- `ProviderBrainApiGateway` e `LlmIntentAdvisor` (`brain/.../LlmConversationAdapters.kt`) implementam, respectivamente, o gateway LLM de conversação e a revisão de classificação de porta via LLM. A produção usa `ConversationBrainGatewayAdapter` (não `ProviderBrainApiGateway`) para o gateway, e **não existe nenhum caller de `LlmIntentAdvisor`** fora do próprio teste — a classificação de `Door` em produção é puramente `DeterministicSecretary`, sem a camada de revisão por LLM que essa classe implementaria.
**Efeito real:** três implementações completas e válidas (não stubs) que nenhum wiring real escolhe. Isoladamente cada uma é inofensiva, mas nenhum documento diz "essas são alternativas propositalmente não usadas" — diferente de `ApiCatalogCapabilityProvider`/`LazyCapabilityDiscovery` (ver 3.5), que já têm essa nota.
**Ação sugerida:** para cada uma, decidir e documentar: (a) remover, (b) ligar (ex.: `LlmIntentAdvisor` como camada opcional de revisão do Secretário), ou (c) anotar como legado/experimental como já foi feito para `BrainExecutionCoordinator`.

### 3.5 Reconfirmação de achado já conhecido
`ApiCatalogCapabilityProvider` e `LazyCapabilityDiscovery` (`brain/.../CapabilityProvider.kt`) continuam com zero instanciação em produção. Isso já está documentado em `docs/auditoria/PLANO_CORRECAO_AUDITORIA_ESCALONAMENTO.md` seção 3 como decisão consciente (opção b: documentar como reservado para uso futuro) — sem mudança de estado, apenas reconfirmado nesta varredura.

## 4. O que não foi coberto nesta fase

- `android-sdk`/RootFS (`rootfs-builder/`, imagens `.tar.gz`) — coberto pelas Fases 3–5 anteriores, não reauditado aqui.
- `brain_runtime/` (Python) e `reference/braincode-python`, `reference/iabrain` — são código de referência/legado explicitamente fora do runtime Android atual (conforme `README.md`/`CLAUDE.md`); não faz parte do "caminho real" e não foi auditado linha a linha.
- Diff campo-a-campo de `contracts/*.md` vs. as data classes Kotlin não foi feito: os arquivos em `contracts/` são descrições de uma frase por contrato (ex.: `plan.md` só diz "plan id, task id, steps, validation strategy, delivery criteria"), abstratas demais para apontar divergência de campo sem reescrever o contrato para o nível de detalhe do código — não há inconsistência verificável nesse nível, só uma diferença de granularidade.
- Build/teste real (`:brain:test`, `:app:testDebugUnitTest`, lint, CI) não rodou aqui por falta de rede; os achados desta fase são de wiring estático, não de regressão de teste.

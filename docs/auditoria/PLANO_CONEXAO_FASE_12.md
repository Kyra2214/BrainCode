# Plano de Implementação — Conectar o que a Fase 12 encontrou desconectado

**Base:** `docs/auditoria/FASE_12_AUDITORIA_E2E_PONTA_A_PONTA.md`, seção 3.
**Critério de inclusão neste plano:** só entra aqui o que (a) não envolve LLM e (b) agrega ao projeto sem duplicar uma responsabilidade que já existe em produção. O que envolve LLM ou é redundante com algo já ativo fica fora — ver seções 4 e 5.

---

## 1. Ligar `ExecutionTrace`/`TraceSink` ao `ActionGateway` real

### Achado
`ActionGateway` já sabe registrar trace por estágio (`TASK`, `CAPABILITY`, `POLICY`, `AGENT`, `SANDBOX`, `EVIDENCE`, `CRITIC`), mas o parâmetro `trace` fica `null` nos dois pontos reais de instanciação (`SandboxPlatform.kt`, `BrainSandboxController.kt`). Hoje `trace?.record(...)` não faz nada em produção.

### Por que agrega
É a única peça desta lista que adiciona uma capacidade nova (visão cronológica por `traceId` de todo o ciclo TASK→CAPABILITY→POLICY→AGENT→SANDBOX→EVIDENCE→CRITIC), sem duplicar nada que já exista — `EventStore`/`ActionAuditLog` registram evento e auditoria, não uma linha do tempo por estágio como `ExecutionTrace.render()` produz.

### Passos de implementação
1. Criar `EventStoreTraceSink` em `brain/src/main/kotlin/com/brain/observability/ExecutionTrace.kt` (mesmo padrão de `EventStoreBehaviorTraceSink` em `BehaviorObservability.kt`): implementa `TraceSink.append` gravando um `BrainEvent` no `EventStore` já existente, em vez de criar um segundo armazenamento — preserva a regra de "não criar segundo... Registry" da seção 9 de `ARQUITETURA_ATUAL.md`.
2. Em `BrainSandboxController.kt`, instanciar `private val executionTrace = ExecutionTrace(EventStoreTraceSink(events))` (reaproveitando o `events: EventStore` que o controller já injeta em `EventStoreBehaviorTraceSink`) e passar `trace = executionTrace` no construtor de `actionGateway` (linha ~138).
3. Em `SandboxPlatform.kt`, decidir se o mesmo `EventStore` está disponível nesse escopo; se estiver, aplicar o mesmo wiring. Se `SandboxPlatform` não tiver `EventStore` disponível, documentar explicitamente por que esse caminho fica sem trace por ora (não deixar `null` silencioso sem explicação, como está hoje).
4. `traceId` de cada chamada deve ser o mesmo `runId`/`actionId` já usado pelo `ActionAuditLog`, para permitir correlação — não inventar um id novo.

### Testes a adicionar
- Teste de integração equivalente a `PlanGapCoverageTest`/`BrainEndToEndTest`, mas chamando o `ActionGateway` real de `BrainSandboxController` (não um `ActionGateway` construído à parte no teste) e verificando que `EventStoreTraceSink` recebeu eventos para os estágios esperados de um ciclo completo.
- Teste unitário de `EventStoreTraceSink` isolado (grava `TraceEvent`, evento aparece no `EventStore` com o `traceId` correto).

### Critério de conclusão
Um ciclo real de execução (`CicloExecucaoPlano` via `BrainSandboxController`) produz eventos de trace recuperáveis pelo `EventStore` para pelo menos `TASK`, `CAPABILITY`, `POLICY` e `EVIDENCE` — comprovado por teste, não só por leitura de código.

---

## 2. Unificar `PostExecutionGate` com os `BehaviorGate`s (`VerificationGate`, `CriticGate`, `ReadinessGate`, `LearningGate`)

### Achado
`PostExecutionGate.evaluate()` reimplementa inline a mesma decisão que `VerificationGate`, `CriticGate`, `ReadinessGate` e `LearningGate` já expressam via `BehaviorGate<I, O>.evaluate(input): GateResult<O>`. Hoje essas quatro classes só são exercitadas por `BehaviorGatesTest`, e mudar uma regra em `PostExecutionGate` não afeta o `Gate` correspondente (e vice-versa) — duas fontes de verdade para a mesma decisão.

### Por que agrega
Não é uma capacidade nova, mas remove uma duplicação real que a seção 9 de `ARQUITETURA_ATUAL.md` proíbe em espírito ("segundo... para a mesma responsabilidade"). Reduz o risco de alguém corrigir um bug de critério em um lugar e esquecer o outro.

### Passos de implementação
1. Em `PostExecutionGate.evaluate()` (caminho completo, não o `evaluateLightChat`), trocar:
   - a montagem manual de `VerificationResult`/`checks` por `VerificationGate().evaluate(verification)` para obter o `GateStatus`, mantendo a construção de `VerificationResult` como está (o `Gate` decora, não substitui, o cálculo dos `checks`).
   - a checagem `critique.status == PASS` por `CriticGate().evaluate(finalCritique)`.
   - a checagem `readinessReport.status == READY && ...` por `ReadinessGate().evaluate(readinessReport)`.
   - a chamada `learning.record(...)` continua igual (é o efeito colateral real), mas o `if (verification.passed && ...)` que decide *se* chama `learning.record` passa a usar `LearningGate().evaluate(LearningInput(evidence, outcome))` como pré-condição.
2. Fazer o mesmo em `evaluateLightChat`, adaptando `VerificationGate`/`CriticGate`/`ReadinessGate` ao formato mais simples do fast path (os `Gate`s já são genéricos em `I`/`O`, não precisam de mudança de assinatura).
3. Não apagar a lógica de negócio de dentro do `PostExecutionGate` (ex.: o cálculo de `researchWasUsed`, os `findings` de `research.unused`) — só a decisão final de status passa a vir do `Gate`. O `Gate` decide PASSED/FAILED/BLOCKED; `PostExecutionGate` continua dono de como construir o `CritiqueResult`/`ReadinessReport` que entra nele.

### Testes a adicionar/ajustar
- Os testes existentes de `PostExecutionGate` (via `BrainSandboxController`/`ResultadoPosExecucao`) devem continuar passando sem alteração de asserts — essa é uma refatoração de implementação, não de contrato externo.
- Adicionar um teste que comprove a unificação de fato: injetar um `CriticGate`/`ReadinessGate` fake (ou usar um spy) e confirmar que `PostExecutionGate` os invoca, para impedir que uma futura edição volte a duplicar a lógica inline.

### Critério de conclusão
`grep` por `CriticGate()`, `ReadinessGate()`, `VerificationGate()`, `LearningGate()` deve encontrar uso em `PostExecutionGate.kt`, não só em `BehaviorGatesTest.kt`. Toda a suíte de `PostExecutionGate`/ciclo completo continua verde.

---

## 2.1 Ordem de execução desta parte (sem LLM)

| Ordem | Item | Depende de |
|---|---|---|
| 1 | `EventStoreTraceSink` + wiring em `BrainSandboxController` (seção 1) | — |
| 2 | Decisão/nota sobre `SandboxPlatform` sem `EventStore` (seção 1, passo 3) | Item 1 |
| 3 | Unificação `PostExecutionGate` ↔ `BehaviorGate`s (seção 2) | — (independente do item 1) |
| 4 | Novos testes de integração de trace e de unificação dos gates | Itens 1 e 3 |

Os itens 1 e 3 são independentes entre si e podem ser feitos em qualquer ordem ou em paralelo.

---

## 3. Aviso — o que **não** entra agora porque envolve LLM

Três peças do achado da Fase 12 são especificamente sobre chamar um provedor de IA (LLM), e o plano atual do projeto é **fazer tudo sem LLM**. Elas ficam de propósito fora deste plano de conexão — não por serem inválidas ou malfeitas, mas porque conectá-las hoje contradiria essa decisão de produto:

- **`HttpProviderClient`** (`brain/provider/ProviderClient.kt`) — cliente HTTP genérico que monta `{"model":..., "prompt":...}` para chamar um provedor de completions. É infraestrutura de chamada de LLM.
- **`ProviderBrainApiGateway`** (`brain/conversation/LlmConversationAdapters.kt`) — gateway que usa um `ProviderClient` para obter `BrainCompletion` de um modelo.
- **`LlmIntentAdvisor`** (mesmo arquivo) — usaria uma LLM para revisar a classificação de `Door` que hoje é 100% determinística (`DeterministicSecretary`).

**Nota para quando (se) o projeto decidir usar LLM:** essas três classes já existem, compilam e têm teste unitário cobrindo o caso feliz e o fallback (`LlmConversationAdaptersTest`); nenhuma delas precisaria ser escrita do zero. Conectá-las nesse cenário futuro seria: (1) trocar `ConversationBrainGatewayAdapter` por `ProviderBrainApiGateway` (ou compor os dois) no ponto onde `SandboxViewModel` monta o gateway conversacional, e (2) injetar `LlmIntentAdvisor` como uma camada opcional de revisão depois de `DeterministicSecretary.classify()`, nunca substituindo-o (o Secretário determinístico continua sendo a decisão final — ver `ARQUITETURA_ATUAL.md` seção 2, "Secretário determinístico"). **Isso não deve ser feito agora.**

---

## 4. O que fica fora por ser redundante (não por envolver LLM) — ✅ REMOVIDO

Estes dois itens do achado da Fase 12 não entraram no plano de conexão porque conectá-los seria recriar algo que a produção já resolve de outra forma — a ação era remoção, não wiring, e por isso não era "implementação" no sentido deste plano. **Ambos já foram removidos:**

- **`PolicyGatedExecutor`** (`app/sandbox/PolicyGatedExecutor.kt`) — a autorização via `PolicyBroker` que ele faria já acontece dentro de `ActionGateway.execute()`, que já está no caminho real (`GatewayBackedSandboxExecutor → ActionGateway`). Conectar essa classe duplicaria a checagem de policy. **Arquivo apagado.** Isso substitui a recomendação de "manter" registrada em `FASE_11_ORFAOS_SECUNDARIOS.md` — a policy real permanece garantida por `ActionGateway`, sem essa camada extra.
- **`ContextRevisionFixer`** (`android-module/agent/RevisionFixer.kt`) — era um `RevisionFixer` mais simples (só marcava a tentativa) do que o `FindingsRevisionFixer` já usado em produção (que marca a tentativa **e** injeta o feedback dos findings no plano). Manter os dois seria uma fonte de regressão de informação caso alguém trocasse um pelo outro por engano. **Classe removida** de `RevisionFixer.kt`; `RevisionFixer` (interface), `FindingsRevisionFixer` e `RevisionFixVerifyLearn` continuam intactos e são os únicos usados.

Nenhum teste referenciava essas duas classes diretamente, então a remoção não exigiu ajuste de suíte de testes.

---

## 5. Resumo executivo

| Item | Ação | Envolve LLM? | Neste plano? |
|---|---|---|---|
| `ExecutionTrace`/`TraceSink` | Conectar (seção 1) | Não | Sim |
| `VerificationGate`/`CriticGate`/`ReadinessGate`/`LearningGate` | Unificar com `PostExecutionGate` (seção 2) | Não | Sim |
| `HttpProviderClient` | Aguardar decisão de produto | Sim | Não — aviso na seção 3 |
| `ProviderBrainApiGateway` | Aguardar decisão de produto | Sim | Não — aviso na seção 3 |
| `LlmIntentAdvisor` | Aguardar decisão de produto | Sim | Não — aviso na seção 3 |
| `PolicyGatedExecutor` | ✅ Removido | Não | Não — seção 4 |
| `ContextRevisionFixer` | ✅ Removido | Não | Não — seção 4 |
| `ApiCatalogCapabilityProvider`/`LazyCapabilityDiscovery` | Já decidido (documentar como reservado) em auditoria anterior | Não | Fora de escopo — nada novo a fazer |

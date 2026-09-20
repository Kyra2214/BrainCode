# Auditoria BrainCode 2.3 — Atualização

**Data:** 2026-09-20  
**HEAD auditado:** e068876a19ef59c75d8e9ceb4b607288be22e897

## Estado atual

A consolidação comportamental da 2.3 está integrada no caminho Android:

`Android Chat → Reasoning → RequirementGate → Planner → PlanningGate → Policy → CapabilityDiscovery → Dispatcher → ActionGateway → execução → evidência → PostExecutionGate → UI`.

O `PostExecutionGate` fecha o ciclo com:

`Verification → UniversalCritic → DoubtDrivenReview/Revision → Readiness → ValidatedLearning`.

## Correções desta rodada

### 1. Requisitos chegam ao Critic

Antes, `ReasoningEngine` descobria requisitos e o Planner os recebia, mas o caller do `PostExecutionGate` não encaminhava essa mesma lista. Isso fazia o `UniversalCritic` receber `requirements = emptyList()`.

Agora:

`Reasoning.requirements → executeWithEvents(requirements) → PostExecutionGate.evaluate(requirements) → CritiqueInput.requirements`.

Os requisitos continuam alimentando o Planner e passam também pelo pós-execução.

### 2. Fallback do chat

O caminho atual do `SandboxViewModel` não chama diretamente `BrainApiGateway.complete()` quando o Sandbox não está pronto. Ele retorna estado explícito de indisponibilidade e solicita a preparação do Sandbox.

Portanto não existe uma segunda rota que possa apresentar uma resposta de provider como execução validada sem passar pelos gates do Brain.

### 3. Semântica dos eventos de pós-execução

O evento emitido pelo `BrainSandboxController` agora distingue validação de execução de registro de aprendizado:

- `PostExecutionBlocked` quando a execução não é aprovada;
- `ValidatedLearningRecorded` somente quando a execução é aprovada **e** `learningRecorded == true`;
- `ExecutionValidated` quando a execução é aprovada, mas não houve novo registro de aprendizado.

Isso evita declarar aprendizado validado quando o `Readiness` aprovou a execução sem registrar aprendizado.

### 4. Documentação

`ESTADO_ATUAL.md`, esta auditoria e a arquitetura/roadmap devem refletir o HEAD atual. Roofts 0.6 continua instalado como payload independente, mas deliberadamente não integrado ao runtime. Retrieval universal/semântico, hardening OS-level e leases/fencing continuam marcos posteriores.

## Matriz 2.3

| Área | Estado |
|---|---|
| Intent / Reasoning | Integrado |
| Requirement Discovery / Gate | Integrado |
| Planner / Acceptance Criteria | Integrado |
| Capability Discovery | Integrado |
| Policy / Authorization | Integrado |
| Dispatcher / ActionGateway | Integrado |
| Execution Evidence | Integrado |
| Verification | Integrado via PostExecutionGate |
| Universal Critic | Integrado via PostExecutionGate |
| Revision / Fix / Re-execution | Integrado |
| Readiness | Integrado |
| Validated Learning | Integrado |
| BehaviorTrace / EventStore | Integrado |
| Android canonical path | Integrado |
| Prompt grounding / reasoning trace | Integrado |
| Chat fallback fora do Brain | Corrigido |
| Roofts 0.6 runtime | Intencionalmente pendente |
| Retrieval universal/semântico | Marco 2.5 |
| OS-level hardening | Marco 3 |
| Leases/fencing | Marco 4 |

## Critério de fechamento

A 2.3 não deve ser considerada validada apenas pela existência das classes. O fechamento depende de CI e E2E comprovados no HEAD atual.

Depois da validação, o APK gerado pelo CI é a evidência de build correspondente ao commit auditado.

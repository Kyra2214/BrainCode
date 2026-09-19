# BrainCode 2.3 — Fase 2: Contratos comportamentais

A Fase 2 introduz contratos mínimos e reutilizáveis para governar qualquer capability sem criar um segundo pipeline de execução.

## Contratos

- `BehaviorGate<I, O>`: interface comum para gates que recebem uma entrada e produzem resultado verificável.
- `GateResult`: resultado com estado `READY`, `NEEDS_CLARIFICATION`, `BLOCKED`, `PASSED` ou `FAILED`, valor opcional e issues estruturadas.
- `AcceptanceCriteria`: critério identificável, obrigatório ou opcional, com estratégia de verificação opcional.
- `VerificationResult` e `VerificationCheck`: resultado de verificação associado aos critérios e evidências.
- `CritiqueResult` e `CritiqueFinding`: crítica verificável com severidade e vínculo opcional ao critério.
- `ReadinessReport` e `ReadinessStage`: relatório estruturado de readiness e seus estágios.
- `ExecutionEvidence`: evidência com identidade, fonte, resumo e marca de verificação.
- `RevisionDecision`: decisão explícita entre aceitar, revisar, abortar ou pedir esclarecimento, com limite de tentativas.

## Invariantes de segurança

Um gate bloqueado precisa de pelo menos uma issue bloqueante. Uma crítica que não seja `PASS` precisa registrar findings. Um relatório `BLOCKED` precisa declarar blockers. Uma verificação só é considerada aprovada quando todos os checks passam. O limite de revisão é restrito a zero a três tentativas.

Os contratos não concedem autorização, não executam comandos e não substituem `PolicyBroker` ou `ActionGateway`. Eles apenas tornam estados e evidências explícitos para que as próximas fases possam conectar gates ao caminho real.

## Validação

O módulo `:brain` compilou e sua suíte de testes passou com os novos testes de invariantes comportamentais.

## Próximo módulo

A Fase 3 deve conectar `RequirementDiscovery`, `AssumptionManager` e um `ContextPack` ao caller real, preservando o fluxo existente e evitando descoberta duplicada.

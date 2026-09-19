# BrainCode — Arquitetura Runtime Android (histórico)

Este arquivo mantém o nome 2.3 por rastreabilidade. O estado atual está em docs/ARQUITETURA_ATUAL.md.

Caminho canônico atual:
UI/ViewModel → BrainSandboxController → Reasoning/Requirement → Planner/ContextPack → DurableJobRunner/WorkflowEngine → BrainSandboxExecutionBridge → Policy/AuthorizedPlan → CicloExecucaoPlano → Dispatcher → ActionGateway → Capability Executor/Sandbox/Provider → PostExecutionGate → Verification/Critic/Revision/Readiness/Learning → UI.

Regras:
- CicloExecucaoPlano é o executor Android canônico.
- BrainExecutionCoordinator não deve ser segundo pipeline Android.
- BrainApiGateway é infraestrutura interna para executores de provider; o Chat não deve chamá-lo diretamente.
- ResultadoCiclo.aprovado não depende só de sucesso técnico.
- EventStore e BehaviorTrace permanecem correlacionados.
- ContextPack é preservado no ExecutionPlan.

Textos anteriores deste arquivo não devem ser usados para afirmar o estado atual de CI/E2E.

# BrainCode — Estado Atual

HEAD funcional da auditoria: caf0d3960e8fde5fbc7bbd193a6dc15d9217ce1f.

## Consolidado

Capability Registry/Discovery, PolicyBroker, ActionGateway, Dispatcher, Agents bounded, SkillRegistry, ReasoningEngine, RequirementDiscovery, AssumptionManager, ContextPack, Planner, AcceptanceCriteria, PlanningGate, AuthorizedPlan, BrainSandboxController, CicloExecucaoPlano, DurableJobRunner/WorkflowEngine, PostExecutionGate, Verification, UniversalCritic, Revision/Fix, Readiness, ValidatedLearning, LayeredMemory e EventStore/BehaviorTrace.

A UI possui estados de planejamento, execução, verificação, crítica, revisão, correção, reexecução, PASS/BLOCKED/FAILED/READY.

Roofts 0.3–0.5 estão preservados e 0.6 está instalado separadamente.

## 2.4 Context Engineering

Implementado: CLAUDE.md, AGENTS.md, INITIAL.md, PRP template, contrato CONTEXT_ENGINEERING.md e ContextPack como entrada tipada do PlanoExecucao.

Não implementado ainda: retrieval semântico geral, hashing/deduplicação/chunking geral, ranking lexical/semântico e executor universal de retrievalHints.

## Correção encontrada nesta auditoria

Planner.kt já tentava fazer PlanoExecucao.copy(contextPack=...), mas PlanoExecucao não possuía esse campo. Isso era uma inconsistência real do contrato.

Commit caf0d396 corrigiu o contrato: PlanoExecucao agora possui contextPack e os Tasks derivados preservam esse contexto.

## Parcial/backlog

1. testes focados finais da mudança 2.4;
2. CI completo;
3. E2E completo em emulador/dispositivo;
4. readiness final;
5. testes arquiteturais contra caminhos paralelos;
6. executor universal de retrievalHints;
7. deduplicação/versionamento de conhecimento;
8. hardening OS-level e SSRF/DNS rebinding;
9. durable jobs com leases/fencing completos.

Não declarar CI/E2E verdes sem execução comprovada.

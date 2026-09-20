# BrainCode — Estado Atual

HEAD funcional da auditoria: fa4dfa1ec2643d1d9b0d9a6f5b0eb36df8dd6fd8.

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
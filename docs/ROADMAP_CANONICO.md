# BrainCode — Roadmap Canônico

Este é o roadmap operacional. Documentos de fases anteriores são históricos.

## Marco 0 — Consolidação

[x] Brain como orquestrador.
[x] Capability como unidade autorizável.
[x] Registry/Discovery, PolicyBroker, ActionGateway e Dispatcher.
[x] Agents bounded e SkillRegistry.
[x] Reasoning + Requirement/Assumption.
[x] ContextPack.
[x] Planner + AcceptanceCriteria + PlanningGate.
[x] AuthorizedPlan e caminho Android canônico.
[x] PostExecutionGate: Verification → Critic → Revision → Readiness → Learning.
[x] EventStore/BehaviorTrace.
[x] RooftS consolidado como uma entidade com camadas 0.3–0.6, preservando os artefatos existentes.
[x] RooftS 0.6 / Agent Skills instalado como camada do mesmo RooftS, ainda sem integração comportamental.

## Marco 2.4 — Context Engineering

[x] regras de repositório.
[x] INITIAL.
[x] PRP template.
[x] contrato Context Engineering.
[x] ContextPack como entrada tipada do Planner/PlanoExecucao.
[x] boundary de segurança para contexto externo.
[x] validação progressiva documentada.
[x] correção do contrato PlanoExecucao.contextPack.
[ ] testes focados finais.
[ ] CI completo.
[ ] E2E completo.
[ ] readiness final.

## Marco 2.5 — Retrieval e conhecimento

[ ] executor universal de retrievalHints.
[ ] hashing/fingerprint e deduplicação.
[ ] chunking determinístico.
[ ] recuperação lexical/semântica.
[ ] versionamento de correções.
[ ] contrato estruturado de citações/evidências.
[ ] promoção de conhecimento somente após validação.

## Marco 2.6 — Garimpagem e incorporação

AUDITAR → LICENÇA → CÓDIGO REAL → TESTES → SEGURANÇA → COMPATIBILIDADE → DECISÃO → INCORPORAR → VALIDAR → DOCUMENTAR PROVENIÊNCIA.

Preferir implementação upstream madura quando puder ser incorporada corretamente. Não substituir por reimplementação simplificada sem motivo.

## Marco 3 — Segurança

[ ] isolamento OS-level.
[ ] limites CPU/memória/PIDs/FDs/disco.
[ ] trust chain de RootFS.
[ ] credential binding.
[ ] SSRF/DNS rebinding.
[ ] bypass regression corpus.

## Marco 4 — Jobs

[ ] durable jobs completos.
[ ] leases/fencing.
[ ] retry/timeout/cancelamento uniforme.
[ ] recuperação de tarefas longas.

Regra: primeiro wiring real, evidência, testes, E2E e readiness; depois expansão.

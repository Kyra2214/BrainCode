# PRP — Fase 4: Porta 3 — Criação / Desenvolvimento

## Objetivo

Fechar a Porta 3 sem criar segundo orquestrador: discussão e requisitos continuam sob o Secretário, a Policy mantém `workspace.*` e `sandbox.*` bloqueados até `APPROVED`, e o fluxo aprovado usa as capacidades e integrações existentes.

## Subfase 4.0 — máquina e aprovação

`CreatePhaseMachine` percorre `DISCUSSION → REQUIREMENTS → ARCHITECTURE → PLAN → APPROVED → EXECUTION → INTEGRATION → REVIEW → TESTS → DELIVERY`. `SecretaryState.approve()` registra as transições e exige aprovação explícita antes de `APPROVED`. A sessão persiste o estado existente em JSON, e `BrainSandboxController` cria/consome `ApprovalRequest` no `FileApprovalStore` antes de executar o plano.

A UI reconhece aprovação explícita quando já existe uma intenção `CREATE` ativa e persiste a nova intenção antes de chamar o controller. O `DoorPolicy` continua negando escrita/execução para fases anteriores; a integração `CreateApprovalTest` comprova zero escrita antes da aprovação e execução controlada depois dela.

## Subfase 4.1 — especialistas bounded

`BuiltInAgentDefinitions.boundedSpecialists()` declara Requirements, Architecture, Roadmap, UI, Backend, Database, Security, Test, Review, Integration e Release como capabilities com provenance local. Os especialistas não possuem provider ou LLM próprio; execução permanece no `CapabilityResolver`/`PolicyBroker`.

## Subfases seguintes

`CreationWorkflowPlanner` agora compõe roadmap, tarefas e assignments de especialistas para o plano aprovado; `BrainSandboxController` registra esses eventos antes da execução. Reutilizar Requirements/Planning/Code/UI/Backend/Database/Security/Test/Review/Integration/Release existentes, gerar prompts pela biblioteca local, registrar segunda opinião/revisão e fechar a entrega local com recibo, resumo e ZIP. Git só deve operar em workspace autorizado. APIs externas seguem bloqueadas por D1.

## Critérios de aceite

1. Máquina, persistência e aprovação explícita têm testes verdes.
2. Nenhuma capability de escrita/execução passa antes de `APPROVED`.
3. Especialistas bounded têm catálogo e provenance verificáveis.
4. Implementação, integração, review, testes, delivery local e ZIP têm evidência no gate final.
5. APK somente após todas as fases e CI final verde.

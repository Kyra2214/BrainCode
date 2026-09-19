# BrainCode 2.3 — Fase 6: Execução real

A entrada Android `BrainSandboxController` agora aplica gates antes de iniciar o ciclo de execução:

1. `executeObjective` analisa o objetivo com `ReasoningEngine` e passa pelo `RequirementGate`.
2. `executePlan` passa pelo `PlanningGate`, exigindo acceptance criteria em todos os passos.
3. Somente após os gates o fluxo segue para `BrainSandboxExecutionBridge` e `CicloExecucaoPlano`.
4. O ciclo existente continua responsável por `CapabilityDiscovery`, `PolicyBroker`, `ActionGateway`, sandbox, eventos e resultado.

Quando um gate bloqueia, o controller retorna `ResultadoCiclo` explícito com `REPROVADO` e motivo, sem executar capability. Isso evita que um fallback Android ignore requisitos ou aceite plano sem critérios.

## Validação

O módulo `:brain` compilou e sua suíte passou. A compilação Android foi tentada, mas o ambiente clonado não possui Android SDK configurado; o Gradle reportou ausência de `ANDROID_HOME`/`local.properties`. A alteração permanece limitada ao caller Android existente e será validada no ambiente Android na Fase 13.

## Limites

Esta fase conecta os gates de requisito e planejamento ao caminho Android. Verification, Critic, Readiness e Learning já possuem contratos/gates, mas sua aplicação após a execução será consolidada nas fases 7–10, sem declarar integração total antes de haver caller e evidência.

## Próximo módulo

A Fase 7 deve conectar o ciclo de correção `SCAN → PLAN → FIX → VERIFY → LEARN`, começando pelo tratamento de resultado vazio e falhas controladas.

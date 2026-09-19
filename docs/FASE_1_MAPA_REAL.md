# BrainCode 2.3 — Fase 1: Mapa real

**Commit de referência:** `483289b`
**Data da auditoria:** 2026-09-18
**Baseline JVM:** `./gradlew :brain:test` — aprovado
**Baseline Android:** bloqueado por ambiente — `ANDROID_HOME` e `ANDROID_SDK_ROOT` não configurados

## Escopo

Esta fase registra o componente, o arquivo principal, o caller observado, o trecho do fluxo canônico coberto, os testes encontrados e o status real de integração. A existência de uma classe não foi considerada prova de integração.

## Matriz de integração

| Componente | Arquivo principal | Caller observado | Fluxo coberto | Testes | Status |
|---|---|---|---|---|---|
| Android Chat / ViewModel | `app/src/main/kotlin/com/sandbox/app/SandboxViewModel.kt` | `MainActivity`, executores do app | UI → ViewModel → fachada/runtime | testes de app e telas | **parcialmente integrado** |
| BrainApiGateway | `app/src/main/kotlin/com/sandbox/app/BrainApiGateway.kt` | `SandboxViewModel`, fachada Android | entrada Android → Brain/API | `BrainApiGateway` e testes de integração relacionados | **parcialmente integrado** |
| BrainSandboxController | `android-module/src/main/kotlin/com/sandbox/agent/BrainSandboxController.kt` | `BrainSandboxExecutionBridge`, ciclo Android | plano → sandbox/capability | `BrainSandboxController`/ciclo | **integrado no caminho sandbox** |
| CicloExecucaoPlano | `android-module/src/main/kotlin/com/sandbox/agent/CicloExecucaoPlano.kt` | controller/bridge Android | plano → passos → execução → resultado | `CicloExecucaoPlanoTest` | **integrado parcialmente** |
| Dispatcher | `brain/src/main/kotlin/com/brain/dispatch/Dispatcher.kt` | `BrainSandboxController`, testes E2E | capability → dispatch | `DispatcherTest`, E2E Brain | **integrado no núcleo; caminho Android parcial** |
| ActionGateway | `brain/src/main/kotlin/com/brain/gateway/ActionGateway.kt` | Dispatcher e testes de gateway | policy → ação autorizada → execução | `ActionGatewayTest`, E2E Brain | **integrado no núcleo** |
| CapabilityDiscovery | `brain/src/main/kotlin/com/brain/capability/CapabilityDiscovery.kt` | Dispatcher/registry | descoberta → capability candidata | `CapabilityDiscoveryTest`, `DispatcherTest` | **integrado no núcleo** |
| SkillRegistry | `brain/src/main/kotlin/com/brain/skill/SkillRegistry.kt` | `BrainIntegrationFacade`, registry/knowledge | skill → capabilities/validação | `SkillRegistryTest`, `SkillRegistryCapabilityTest` | **integrado parcialmente** |
| RequirementDiscovery | `brain/src/main/kotlin/com/brain/reasoning/RequirementDiscovery.kt` | `ReasoningEngine` | intent → requisitos | `RequirementDiscoveryTest` | **integrado ao reasoning; caller Android a confirmar** |
| AssumptionManager | `brain/src/main/kotlin/com/brain/reasoning/AssumptionManager.kt` | `ReasoningEngine` | requisitos → hipóteses/assunções | `ReasoningEngineTest`, `TaskStateTest` | **integrado ao reasoning** |
| ReasoningEngine | `brain/src/main/kotlin/com/brain/reasoning/ReasoningEngine.kt` | executores de prompt/reasoning | requisitos → estado de raciocínio | `ReasoningEngineTest` | **integrado no núcleo** |
| SelfCritic | `brain/src/main/kotlin/com/brain/reasoning/SelfCritic.kt` | `RevisionEngine`, fluxos de prompt | resultado → crítica | `SelfCriticTest` | **integrado parcialmente; ainda orientado a domínios existentes** |
| RevisionEngine | `brain/src/main/kotlin/com/brain/reasoning/RevisionEngine.kt` | `SelfCritic`/executores | crítica → revisão | `RevisionEngineTest` | **integrado parcialmente** |
| DurableJobRunner | `brain/src/main/kotlin/com/brain/job/DurableJobRunner.kt` | caminhos de jobs e testes E2E | execução → persistência/retomada | `DurableJobRunnerTest`, E2E Brain | **integrado no núcleo** |
| PromptLibrary | `brain/src/main/kotlin/com/brain/prompt/PromptLibrary.kt` | `PromptGenerationExecutor`, `BrainIntegrationFacade` | prompt → retrieval/generation | testes de PromptLibrary e geração | **integrado como fluxo especializado** |
| ProviderRegistry | `brain/src/main/kotlin/com/brain/provider/ProviderRegistry.kt` | registry/catalog/discovery | provider → catálogo | testes de registry/discovery | **integrado como infraestrutura; AccountPool ainda pendente** |

## Fluxo canônico observado

O caminho comprovado pelos callers e testes atuais é:

```text
Android/UI
  → BrainApiGateway / SandboxViewModel
  → BrainIntegrationFacade ou BrainSandboxController
  → Planner/Dispatcher
  → CapabilityDiscovery / ActionGateway
  → Sandbox ou Provider
  → Events / resultado
```

O caminho ainda não está comprovado de ponta a ponta para todos os casos:

```text
RequirementDiscovery
  → AssumptionManager
  → ContextPack
  → Planner
  → Policy
  → ActionGateway
  → Verification
  → SelfCritic
  → Revision
  → Readiness
  → Memory/Learning
  → Android/UI
```

Em especial, a Fase 1 encontrou os seguintes pontos para as próximas fases:

1. Os contratos comportamentais universais ainda precisam ser formalizados sem duplicar os contratos existentes.
2. O resultado de `RequirementDiscovery` precisa alimentar explicitamente o planner no caller real.
3. Acceptance criteria, verification e readiness ainda não aparecem como um único contrato obrigatório no caminho Android.
4. SelfCritic/Revision existem, mas sua aplicação universal precisa ser conectada ao fluxo de capability.
5. O baseline Android não pôde ser executado neste ambiente por falta de SDK; a Fase 13 exigirá device/emulador ARM64 ou ambiente equivalente.

## Decisão da Fase 1

A Fase 1 está concluída como **mapa de integração**, não como declaração de integração total. O próximo módulo deve ser a **Fase 2 — Contratos comportamentais**, começando por `BehaviorGate`, `RequirementGate`, `AcceptanceCriteria`, `VerificationResult`, `CritiqueResult`, `ReadinessReport`, `ExecutionEvidence` e `RevisionDecision`, sempre reaproveitando contratos existentes e adicionando callers reais.

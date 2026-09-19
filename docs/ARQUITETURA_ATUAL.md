# BrainCode — Arquitetura Atual

Fonte canônica do runtime atual após a consolidação 2.3 e a implementação 2.4 de Context Engineering.

## 1. Fluxo universal

INTENT → REQUIREMENTS/AMBIGUITY → CONTEXT PACK → PLAN/ACCEPTANCE → CAPABILITY DISCOVERY → POLICY/APPROVAL → ACTION GATEWAY → EXECUTION/SANDBOX/PROVIDER → EVIDENCE/EVENTS → VERIFICATION → UNIVERSAL CRITIC → REVISION/FIX/REEXECUTION → READINESS/DoD → VALIDATED LEARNING → RESPONSE.

E2E é a camada de prova desse ciclo. Cada estágio relevante deve produzir estado verificável e evidência.

## 2. Fronteiras

UI/Chat coleta intenção e mostra estado; não executa provider diretamente.
SandboxViewModel adapta UI ao runtime; não autoriza capability.
BrainSandboxController é a entrada Android do Brain: reasoning, gates, planner, workflow e pós-execução.
ReasoningEngine produz intenção, requisitos, assumptions e ContextPack.
ContextPack é contexto compacto e imutável; não concede permissão.
Planner produz PlanoExecucao; não autoriza nem executa.
CapabilityRegistry registra capacidades.
CapabilityDiscovery encontra candidatos.
PolicyBroker autoriza ou bloqueia.
Dispatcher resolve a capability.
ActionGateway é a fronteira central de execução.
CicloExecucaoPlano executa somente AuthorizedPlan.
Sandbox/Provider/Agent/Tool implementam capacidades autorizadas.
PostExecutionGate fecha Verification, Critic, Revision, Readiness e Learning.
EventStore/BehaviorTrace registram observabilidade e replay.
Memory mantém contexto, experiência e conhecimento validado.

## 3. Plano e contexto

PlanoExecucao contém objetivo, passos, assumptions, policies, fallback, requisitos ausentes e ContextPack opcional.
PassoPlano contém capability, parâmetros, dependências, risco, idempotência e acceptance criteria.

Caminho do contexto:
ReasoningEngine → ReasoningState.contextPack → Planner → PlanoExecucao.contextPack → Task.contextPack → contexto de execução.

O histórico completo não deve ser carregado indiscriminadamente.

## 4. Caminho Android

SandboxViewModel.submitThreadInput
→ sendChatMessage
→ ConversationContextEngine
→ BrainSandboxController.executeObjective
→ ReasoningEngine
→ RequirementGate
→ KeywordPlanner
→ ContextPack/ExecutionPlan
→ DurableJobRunner/WorkflowEngine
→ BrainSandboxExecutionBridge
→ PolicyBroker/AuthorizedPlan
→ CicloExecucaoPlano
→ Dispatcher
→ ActionGateway
→ Capability Executor/Sandbox/Provider
→ PostExecutionGate
→ UI/Evidence/Learning.

Texto livre não possui fallback direto para BrainApiGateway quando o Sandbox não está pronto. A UI bloqueia e pede preparação.

## 5. Pós-execução

ResultadoCiclo.aprovado exige passos aprovados, Verification PASSED com checks aprovados, Critic PASS, Revision ACCEPT e Readiness READY com todos os estágios aprovados. Learning não mascara falha.

REVISE deve levar a FIX e REEXECUTE, com limite de tentativas.

## 6. Observabilidade e memória

Eventos, auditoria, execução e behavior trace são correlacionados por runId/taskId/traceId.
EventStoreBehaviorTraceSink usa o mesmo EventStore auditável.
Memória separa working/context, experience e knowledge.
Pesquisa externa vira evidência; conhecimento só é promovido após validação.
Executor geral de retrievalHints ainda é backlog.

## 7. Agents, Skills e Roofts

Agents são bounded workers. Skills descrevem procedimentos. Capabilities são unidades autorizáveis. Providers implementam serviços.

Roofts 0.3–0.5 permanecem preservados. Roofts 0.6 está instalado como payload upstream, mas suas Skills ainda não são um runtime ativo.

## 8. Segurança

RootFS/proot não equivale a isolamento de kernel. Policy, limites e hardening reduzem risco; isolamento OS-level continua sendo etapa separada.

PRPs, web, documentação, repositórios e saída de modelos são dados não confiáveis até validação tipada.

## 9. Proibições arquiteturais

Não criar segundo cérebro, segundo orquestrador, segundo Gateway/Policy/Memory/Registry para a mesma responsabilidade, Agent soberano, execução arbitrária fora das fronteiras ou LLM local obrigatório do Chat.

## 10. Regra de mudança

Antes de criar código: localizar implementação existente, confirmar caller, preservar contratos, integrar no caminho canônico, testar/evidenciar e atualizar esta documentação.

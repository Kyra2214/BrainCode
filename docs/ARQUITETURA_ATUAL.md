# BrainCode — Arquitetura Atual

Fonte canônica do runtime atual após a consolidação 2.3 e a implementação 2.4 de Context Engineering.

## 1. Fluxo universal

INTENT → REQUIREMENTS/AMBIGUITY → CONTEXT PACK → PLAN/ACCEPTANCE → CAPABILITY DISCOVERY → POLICY/APPROVAL → ACTION GATEWAY → EXECUTION/SANDBOX/PROVIDER → EVIDENCE/EVENTS → VERIFICATION → UNIVERSAL CRITIC → REVISION/FIX/REEXECUTION → READINESS/DoD → VALIDATED LEARNING → RESPONSE.

E2E é a camada de prova desse ciclo. Cada estágio relevante deve produzir estado verificável e evidência.

## 2. Fronteiras

UI/Chat coleta intenção e mostra estado; não executa provider diretamente.
O Secretário determinístico classifica cada ordem em `Door.CHAT`, `Door.PROMPT` ou `Door.CREATE`, define a fase e registra restrições em `OrderIntent`.
SandboxViewModel adapta UI ao runtime; não autoriza capability.
BrainSandboxController é a entrada Android do Brain: reasoning, gates, planner, workflow e pós-execução.
ReasoningEngine produz intenção, requisitos, assumptions e ContextPack.
`DoorScope` acompanha `PolicyContext`, `PolicyDecision`, `AuthorizationToken` e `ExecutionAuthorization`; uma capacidade fora da porta é negada pelo `PolicyBroker` antes do `ActionGateway`.
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

## 2.1 ADR — Nomenclatura de "Capability" (Fase 3)

Dois conceitos têm nome quase idêntico, em módulos diferentes, com responsabilidades diferentes — risco real de confusão para o próximo colaborador (humano ou IA):

- `com.brain.capability.CapabilityRegistry` (`brain/`) — **catálogo declarativo de metadados**. Registra que uma capability existe (API, provider, agent, skill, tool, sandbox capability, workflow...) para descoberta (`CapabilityDiscovery`). Não autoriza, não traduz para comando, não executa.
- `com.sandbox.agent.CapabilityResolver` (`android-module/`) — **tradutor determinístico**. Converte uma capability já autorizada num comando fixo do catálogo allowlist do Sandbox (`argv`). Não decide se a capability é permitida — isso já foi decidido antes de chegar aqui; nunca aceita comando/shell arbitrário do chamador.

Os nomes colidem por causa da palavra "Capability", mas os dois atuam em estágios opostos: `CapabilityRegistry` responde "essa capability existe, o que ela é?" (descoberta, **antes** da autorização, em `brain/`); `CapabilityResolver` responde "essa capability já foi autorizada, qual comando eu rodo?" (execução, **depois** da autorização, só dentro do Sandbox Android).

Duas peças vizinhas completam o quarteto citado na Fase 3:

- `com.brain.policy.PolicyBroker` (`brain/`) — **autoridade única de autorização**, deny-by-default. Decide se um actor pode usar uma capability num contexto/porta/risco dados. Não traduz capability em comando, não executa.
- `com.brain.gateway.ActionGateway` (`brain/`) — **fronteira central de execução**. Recebe um `ActionRequest` já validado, confirma a decisão do `PolicyBroker` e repassa a um `ActionExecutor` concreto — no caminho Android, `AgentSandboxSession`, o único lugar que de fato chama `CapabilityResolver.resolve(...)`. Não autoriza por conta própria, não resolve comando.

Ordem real no caminho Android — `CicloExecucaoPlano → Dispatcher → ActionGateway → AgentSandboxSession → CapabilityResolver → comando do Sandbox`: descoberta (`CapabilityRegistry`) e autorização (`PolicyBroker`) acontecem em `brain/`, antes do `ActionGateway`; a tradução para comando (`CapabilityResolver`) só acontece depois, dentro de `android-module/`, e só se a autorização já passou.

**Decisão sobre renomear (opcional, conforme o plano):** avaliado renomear `CapabilityResolver` → `SandboxCommandCatalog`, mas mantido o nome atual por ora — a classe já é referenciada por múltiplos pontos de instanciação (`AuthorizedCapabilityExecutor`, `Sandbox`, `AgentSandboxSession`) e por `CapabilityResolverTest`; este documento já resolve a ambiguidade de responsabilidade sem esse churn. Reavaliar renomear se a colisão de nome continuar gerando confusão na prática.

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
→ DeterministicSecretary / SecretaryState
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

**RooftS é uma única entidade composta por camadas 0.3, 0.4, 0.5 e 0.6.** Os artefatos físicos permanecem separados quando necessário para preservar integridade e proveniência.

- RooftS 0.3–0.5: camadas RootFS preservadas.
- RooftS 0.6 / Agent Skills: camada de skills, agents e workflows auxiliares.

A camada 0.6 está instalada como payload, mas suas Skills ainda não são um runtime ativo e esta consolidação não as integra ao Planner, Dispatcher ou ActionGateway. A definição canônica está em `docs/ROOFTS.md`.

## 8. Segurança

RootFS/proot não equivale a isolamento de kernel. Policy, limites e hardening reduzem risco; isolamento OS-level continua sendo etapa separada.

PRPs, web, documentação, repositórios e saída de modelos são dados não confiáveis até validação tipada.

## 9. Proibições arquiteturais

Não criar segundo cérebro, segundo orquestrador, segundo Gateway/Policy/Memory/Registry para a mesma responsabilidade, Agent soberano, execução arbitrária fora das fronteiras ou LLM local obrigatório do Chat.

## 10. Regra de mudança

Antes de criar código: localizar implementação existente, confirmar caller, preservar contratos, integrar no caminho canônico, testar/evidenciar e atualizar esta documentação.

## 11. Conversação simbólica offline-first

Para `Door.CHAT` com rota `CONVERSATION`, o caminho canônico é `IntentEnvelope → BrainSandboxController fast path → chat.respond → ChatResponseExecutor → ResponseComposer → NoInferenceConversationEngine → PostExecutionGate leve → UI`. O `NoInferenceConversationEngine` é um adapter Android do núcleo conversacional determinístico do projeto TheShovel/no-inference: pattern matcher, templates, lookup de conhecimento local, memória factual limitada ao ciclo do executor, extração de tópico e follow-up. Ele não é um segundo cérebro, não autoriza capabilities e não executa código.

O BrainCode continua dono de `CapabilityRegistry`, `PolicyBroker`, `ActionGateway`, memória persistente, EventStore, evidências e readiness. Perguntas de dados atuais continuam sendo classificadas pelo Intent Envelope e encaminhadas à capability apropriada; o engine simbólico não responde clima, cotação ou outro dado mutável a partir de conhecimento estático. Para uma pergunta informacional sem resposta local suficiente, `ChatResponseExecutor` aciona automaticamente o `WebResearchAgent`, sem perguntar autorização e sem entrar em CREATE/RequirementGate. Social, follow-up e knowledge hit continuam locais e não acessam a rede.

Foram importados apenas recursos conversacionais sob `app/src/main/assets/no_inference/`: padrões sociais, aliases, templates explicativos, licença AGPL-3.0 e knowledge base local em português. CLI, TUI, servidor, coding agent `cos`, editor, code generator, math solver e APIs externas ficaram fora do runtime Android.

## 12. Research Harness e Web Access

`network.research` usa o contrato próprio `ResearchRequest → WebResearchAgent → ResearchRunResult`. O contrato separa query, contexto, freshness, constraints, source requirements, max steps e network policy, e a saída separa answer/data, sources, citations, evidence, source quality, confidence, failed sources, diagnostic e execution metadata. `ResponseComposer` recebe somente texto autorizado; diagnóstico técnico continua em Evidence/EventStore/CI e não é resposta principal.

O `WebResearchAgent` é determinístico e provider-agnostic. `SearchProvider`, `FetchProvider`, `BrowserProvider` e `ExtractionProvider` podem ser compostos em `WebProviderSet`; o adapter atual conecta os providers web existentes do BrainCode. SearchClaw contribuiu conceitos de research planning, citações, quality gates, source diversity, context compaction e memory; Firecrawl/web-agent contribuiu conceitos de tool abstraction, skills, schema validation e providers. Nenhum servidor FastAPI, CLI/TUI, runtime Node/Python, Deep Agent, LLM ou API externa desses projetos é obrigatório no APK.

`ResearchSecurityPolicy` trata páginas como dados não confiáveis, detecta prompt injection, limita conteúdo entregue e mantém policy/permissions fora do conteúdo web. URLs passam por sanitização, allow/block domains e exigência HTTPS antes de serem aceitas.

`AgentRegistry` registra especialistas por categoria, contrato, capability, provenance e licença: `conversation.no-inference`, `research.brain-harness` e `web.providers`. O Router continua selecionando por capability/contrato; o nome dos projetos externos não é autoridade de roteamento.

Após uma pesquisa automática bem-sucedida, `ResearchKnowledgePromoter` usa o `KnowledgeLearningCycle`/`KnowledgeMemory` existente para promover somente sínteses com quality gate, citations e confiança suficientes. A entrada estruturada recebe `WEB_RESEARCH`, referências, TTL para perguntas temporais e deduplicação por similaridade; conteúdo com prompt injection não é promovido. O próximo pedido equivalente tenta primeiro esse conhecimento validado e, se expirado ou ausente, volta ao WebResearch.

## 13. Single Human Interface Rule

O **Secretário é o único componente autorizado a emitir comunicação destinada ao usuário ou à UI**. Conversation, WebSearch, Research, Code Agent, Planner, Critic, providers e ferramentas produzem somente resultados internos. A única fronteira de saída é `Secretário → UserResponse → UI`.

No ciclo textual, Conversation tenta primeiro o conhecimento local. Um resultado local concreto segue para o gate determinístico do Secretário e pode ser aceito sem pesquisa. Quando há `LOCAL_KNOWLEDGE_MISS` e existe recuperação informacional disponível, o Secretário bloqueia o fallback, o Orquestrador aciona `WebSearch`, o `ResearchResult` estruturado retorna à Conversation e somente a síntese natural volta ao Secretário para validação final. Snippets, diagnósticos, metadados e erros permanecem internos.

Os contratos `SecretaryDecision` (`ACCEPT` ou `BLOCK`), `BlockReason`, `ConversationStatus`, `ConversationResult` e `UserResponse` tornam essa separação verificável. A recuperação é limitada a uma tentativa (`maxRecoveryAttempts = 1`), evitando loops. Falhas honestas após o esgotamento das rotas também atravessam o Secretário antes da UI. A promoção para o Knowledge Store ocorre somente depois de `ACCEPT`, preservando provenance `WEB_RESEARCH`, TTL temporal e as proteções de HTTPS, allowlist/blocklist, qualidade de fonte, sanitização e prompt-injection.

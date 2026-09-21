# BrainCode — Plano de implementação: validação por agente (Self-E2E) e E2E da Porta

**Data:** 2026-09-21

Este plano operacional complementa:
- `docs/PLANO_INTEGRACAO_E2E_BRAINCODE.md` — contrato conceitual do E2E interno.
- `docs/ROADMAP_CANONICO.md` — Marco 5 e sequência das três Portas.

## 1. Objetivo

Todo especialista/capability selecionável deve validar seu próprio resultado antes de devolvê-lo ao Secretário (**Self-E2E**). O Secretário então executa uma validação independente da Porta/etapa (**Door E2E**) e somente libera a próxima etapa com PASS.

**Policy continua sendo a única autoridade de permissão.** PASS de validação nunca concede autorização.

O Secretário é gerente de fluxo, não um segundo cérebro: classifica, coordena, valida, registra evidências e devolve falhas ao responsável. Ele não substitui nem corrige o resultado do especialista.

A validação deve reutilizar o `PostExecutionGate` existente, evitando uma segunda pipeline concorrente.

RooftS permanece uma única entidade com as camadas 0.3–0.6. O especialista de Documentação e o Roadmap permanecem componentes do fluxo, não um segundo orquestrador.

## 2. Achados da auditoria

1. O app autorizava providers em `authorizedAccountIds`, enquanto a Policy negava etapas das Portas; a UI podia exibir `Plano concluído: false` como se fosse uma resposta. A propagação correta deve usar `CicloExecucaoPlano` / `DoorScope.visibleAccounts`.
2. Jornadas de prompt e criação podiam falhar por Policy enquanto a UI E2E passava por asserções fracas.
3. `AcceptanceCriteria` com evidência padrão `evidence:step-result` e o `PostExecutionGate` verificando apenas “passo aprovado + alguma evidência” formam um critério tautológico.
4. O caminho de esclarecimento em `BrainSandboxController.executeObjective` retorna antes de `executeWithEvents`; assim, a pergunta de `chat.respond` não alcança Verification/Critic/Readiness.
5. Code prompt pode terminar em `NEEDS_REVISION`/`BLOCKED` por requisitos sintéticos como `interface/aplicativo`; `RequirementMatcher` precisa tratar `a/b` como alternativas, não como AND.
6. `BuiltInAgentDefinitions` declara agentes, mas isso não significa que estejam ligados à execução. O contrato deve estar ligado ao executor/registry real.
7. O E2E deve comprovar comportamento sem depender de textos genéricos como “Resultado”.

## 3. Reuso do que já existe

- **ValidationContract:** `AcceptanceCriteria`, `AcceptanceCriterionContract`, `VerificationMethod`.
- **ValidationResult:** `VerificationResult`, `CritiqueResult`, `ReadinessReport`.
- **Retry:** `RevisionDecision`, `RevisionFixer`, `MAX_EXECUTION_ATTEMPTS = 3`, `revision.no-progress`.
- **Missing information:** `GateStatus.NEEDS_CLARIFICATION`, `ClarificationQuestion`, `RevisionAction.ASK_CLARIFICATION`.
- **Door E2E:** `DoorPolicy`, `DoorScope`, `OrderIntent`, `CreatePhaseMachine`, `PostExecutionGate`.
- **Door 3 checklist:** `CreationWorkflowPlanner`, `Roadmap`, `Tarefa`, `TarefaStateMachine`.
- **Evidence:** `ExecutionEvidence`, `EventStore`, `BehaviorDiagnostics`, evento `ValidationCompleted`.

Não criar uma segunda pipeline de validação paralela.

## 4. Modelo de validação

Criar em `:brain`, Kotlin/JVM puro, pacote `com.brain.validation`:

### Enums

`ValidationStatus`:
- `PASS`
- `FAIL`
- `NEEDS_INPUT`

`ValidationLevel`:
- `LIGHT`
- `CONTENT`
- `AGENT`
- `DOOR`
- `PRODUCT`

`FindingOwner`:
- `AGENT`
- `USER`
- `POLICY`
- `INFRA`

### ValidationSubject

Deve carregar, conforme o estágio:
- intenção do usuário;
- requisitos;
- resultado produzido;
- estado dos passos;
- decisões de Policy;
- evidências.

### ValidationCheck

Cada check deve possuir:
- id;
- descrição;
- severidade;
- owner;
- avaliação determinística/pura.

### ValidationContract

Campos mínimos:
- id;
- capability;
- level;
- checks;
- evidências obrigatórias;
- máximo de tentativas.

### ValidationResult

Campos mínimos:
- status;
- contractId;
- capability;
- agentId;
- stage;
- checks pass/fail;
- missingRequirements;
- evidenceIds;
- attempt;
- previousResultId.

### Regras

1. Gate determinístico; LLM pode ser consultivo, nunca autoridade do gate.
2. Self-E2E e Door E2E são contratos independentes.
3. `NEEDS_INPUT` é resultado válido, especialmente na Porta 1.
4. Não usar substring literal como critério semântico; corrigir/reutilizar `RequirementMatcher`.

## 4.1. Roteamento por responsável

- **AGENT:** volta ao agente responsável → correção → Self-E2E → Secretário → Door E2E.
- **USER:** `NEEDS_CLARIFICATION` → pergunta explícita ao usuário → aguardar resposta.
- **POLICY:** diagnóstico para usuário/desenvolvedor; não fazer retry do agente nem consumir tentativa.
- **INFRA:** retry técnico controlado.

Limite padrão: `MAX_EXECUTION_ATTEMPTS = 3`. Sem progresso deve gerar `revision.no-progress` e escalonamento.

## 5. E2E por Porta

### Porta 1 — LIGHT

Validar:
- resposta existe e não está vazia;
- resposta é pertinente à solicitação;
- restrições respeitadas (`NO_WEB`, `NO_PRODUCE`, `NO_EXECUTE`);
- nenhum `workspace.*` ou `sandbox.*` indevido;
- `NEGADO_PELA_POLICY` nunca conta como sucesso;
- esclarecimento retorna `NEEDS_INPUT`, pergunta não vazia e requisitos ausentes identificados.

Porta 1 não usa MeiGen.

### Porta 2 — CONTENT

Validar, conforme o pedido:
- subject;
- action;
- environment;
- elements;
- style;
- lighting;
- composition;
- restrictions;
- format.

A conclusão do prompt é terminal para a Porta 2: não iniciar criação/desenvolvimento/execução automaticamente.

MeiGen só entra quando a implementação da Porta 2 começar, após a consolidação da Porta 1. Ele deve ser tratado como referência/capability/provider/Skill, nunca como segundo orquestrador.

### Porta 3 — PRODUCT / por fase

Macrofluxo:

`DISCUSSION → REQUIREMENTS → ARCHITECTURE → PLAN → APPROVED → EXECUTION → INTEGRATION → REVIEW → TESTS → DELIVERY`

Cada fase/tarefa possui contrato próprio. O Roadmap é o checklist operacional do Secretário.

Cada tarefa deve registrar:
- responsável;
- dependências;
- requisitos;
- restrições;
- evidências esperadas;
- Self-E2E;
- resultado do Secretário;
- tentativa;
- findings;
- owner da correção;
- bloqueios;
- condição de liberação.

Uma etapa só fecha com evidência + PASS. Tarefas independentes podem executar em paralelo quando as dependências permitirem.

## 5.1. Mapa de especialistas

Não integrar os agentes declarados em massa. Atualmente existem 14 definições bounded (incluindo Documentation), mas permanecem UNAVAILABLE enquanto não houver executor dedicado + contrato + Self-E2E conectado ao fluxo. Um agente só pode ficar AVAILABLE quando possuir capability distinta, executor real, ValidationContract e caso de uso.

### Grupo A — núcleo atual

- Research — `WebResearchExecutor`
- Requirements — `RequirementDiscovery` / `RequirementGate` / `PlanningAgent`
- Roadmap — `CreationWorkflowPlanner` / `Roadmap`
- Test — `ExecutorValidacaoProjeto` / `sandbox.test`
- Review — `UniversalCritic` / `RevisionEngine` / `RevisionFixer`
- Release — entrega local/Git autorizado
- Documentation — especialista transversal; inicialmente resumo determinístico baseado em resultados/evidências validados.

### Grupo B — após Marco 5.3

- Code
- Architecture
- Integration

### Grupo C — preferencialmente como Skills

- UI
- Backend
- Database
- Security

Security segue a decisão do projeto: ataque/auditoria do produto completo depois da primeira entrega integral, e não como gate artificial antes de existir o produto.

Regra de fail-closed: capability declarada como AGENT sem executor dedicado não pode ser executada. Agentes ainda não implementados permanecem `UNAVAILABLE`.

## 6. Fases de implementação

### E2E-0 — Base confiável

- corrigir propagação `DoorScope.visibleAccounts`;
- remover fallback de UI que transforma `cycle.resposta ?: "Plano concluído: ..."` em falsa resposta;
- corrigir `RequirementMatcher` para alternativas `a/b`;
- ignorar slots sintéticos inadequados como `interface/aplicativo` quando não forem requisitos reais;
- marcar agentes não implementados como `UNAVAILABLE`;
- `CompositeActionExecutor` rejeita AGENT sem executor dedicado;
- corrigir higiene dos testes UI;
- corrigir Roadmap.

Critérios:
`DoorPolicyTest`, `BrainSandboxControllerChatTest`, `ChatClarificationFlowTest`, `RequirementMatcherTest`, `CompositeActionExecutorTest`, CI e UI E2E verdes.

### E2E-1 — Contratos

Implementar `ValidationContract`, `ValidationResult`, checks e routing de findings.

### E2E-2 — Engine + vertical slice da Porta 1

Implementação inicial já conectada: `ValidationEngine` em `:brain`, Self-E2E/ Door E2E anexados ao `PostExecutionGate`, `ValidationCompleted` registrado no EventStore e clarificação passando pela mesma pipeline. A consolidação continua condicionada a CI/UI E2E/readiness verdes no mesmo HEAD.

- integrar `ValidationEngine` ao `PostExecutionGate`;
- caminho de esclarecimento não pode retornar antes da validação;
- produzir `ValidationResult(NEEDS_INPUT)` + `ValidationCompleted`;
- `chat.respond` usa contrato LIGHT;
- Door 1 possui check independente;
- Policy findings são diagnósticos, não retry de agente.

### E2E-3 — Migrar capabilities/agentes reais

Cada capability selecionável recebe executor real + contrato + Self-E2E + retorno ao Secretário.

### E2E-4 — Porta 2

Prompt content validation + fluxo terminal + Self-E2E + Door E2E.

### E2E-5 — Porta 3

**5a:** Grupo A + estado do Roadmap.

**5b:** Grupo B após Provider/API, com segunda opinião quando aplicável.

**5c:** Grupo C como Skills quando não houver justificativa para agente independente.

### E2E-6 — Produto/readiness

Validar jornada completa, evidências, documentação, entrega e readiness.

## 7. Testes

- JVM prova o comportamento da cadeia real; UI comprova apenas a apresentação/fluxo.
- Asserções devem ser específicas e semânticas.
- Toda regra negativa importante deve possuir teste de invariância.
- Novos testes devem falhar contra o comportamento antigo quando possível.
- E2E não concede autorização.
- CI deve incluir pelo menos `:brain:test`, `:android-module:test` e `:app:testDebugUnitTest`.

## 8. Roadmap Canônico

O Roadmap deve refletir o estado real:

1. Em Marco 5.4, retirar a declaração de que CI/E2E/readiness da Porta 3 já estão consolidados.
2. Qualificar o registro antigo de `f859426`: a jornada instrumentada não prova sozinha a execução completa de todas as fases/especialistas.
3. Criar **Marco 5.5 — Self-E2E e Door E2E**, com E2E-0 até E2E-6 inicialmente desmarcados.
4. Manter Marco 5.1 CI/E2E/readiness sem marcar até E2E-2.
5. Manter agentes sem executor/contrato como `UNAVAILABLE`.
6. `docs/PLANO_INTEGRACAO_E2E_BRAINCODE.md` deve manter a regra de que nenhum agente ligado fica sem contrato.
7. O vertical slice `chat.respond` deve ser concluído antes da migração de outros agentes/capabilities.

## 9. Decisões abertas

- **D1:** APIs externas permanecem bloqueadas até o Marco 5.3.
- **D2:** reservar “E2E” para suíte/teste externo e, se útil, chamar a validação em runtime de `ValidationContract`/Self-Check.
- **D3:** LLM Judge apenas consultivo.
- **D4:** engine em `:brain` puro.
- **D5:** Skills versus agentes conforme capability, executor, permissões e necessidade de estado.
- **D6:** Documentation inicialmente determinística; provider/LLM somente depois.

## 10. Anti-patterns proibidos

- Self-E2E isolado sem validação do Secretário.
- O mesmo check usado como Self-E2E e Door E2E.
- Finding entregue ao responsável errado.
- Clarificação tratada como erro.
- LLM como autoridade do gate.
- Contrato para agente inexistente/não executável.
- Capability marcada AVAILABLE sem executor.
- Integração dos 13 agentes de uma vez.
- Segundo orquestrador.
- Declarar uma Porta consolidada sem evidência verde no mesmo HEAD.
- Percentuais de maturidade usados como substituto de testes.

## 11. Contrato de evidências

A cadeia mínima esperada é:

`DoorDesignated → PlanCreated → step → PolicyDecision(doorScope) → Self-E2E(ValidationResult) → ValidationCompleted(Door) → ClarificationRequested/Delivered → readiness`

O Marco 5.5 somente fecha quando:
- E2E-0 estiver verde;
- toda capability/agente selecionável tiver executor + contrato;
- agentes não implementados permanecerem `UNAVAILABLE`;
- as três Portas tiverem E2E próprio com asserções positivas e invariantes negativas;
- o Roadmap só avance com PASS;
- CI/UI E2E/readiness estiverem verdes no mesmo HEAD.

## 12. Regra permanente

Nenhum novo agente entra no catálogo de execução sem:
1. capability distinta;
2. executor real;
3. ValidationContract;
4. Self-E2E;
5. caso de uso;
6. integração com a validação do Secretário;
7. evidência de CI/E2E/readiness quando aplicável.

Essa regra vale para todos os agentes futuros do BrainCode.


## Atualização de implementação — 2026-09-21

Após a auditoria do HEAD anterior, as lacunas críticas desta camada foram conectadas:

- ValidationContractRegistry liga capabilities reais aos responsáveis/contratos Self-E2E; capacidades sem executor continuam fail-closed.
- ValidationResult agora possui resultId e taskId; attempt/previousResultId são transportados pela execução.
- ValidationEngine aplica limite de tentativas do contrato e classifica excesso como finding de infraestrutura.
- Falhas de Self-E2E/E2E são promovidas a findings bloqueantes da revisão, portanto não podem ser aceitas silenciosamente pelo PostExecutionGate.
- A revisão reaproveita RevisionDecision/RevisionFixer/loop de até 3 tentativas existente, preservando revision.no-progress.
- ValidationCompleted registra contrato, agente, checks, owners, evidências, IDs de resultado, tentativa e predecessor.
- RoadmapValidationCoordinator conecta ValidationResult.taskId ao Roadmap/Tarefa, e o controller registra RoadmapValidationUpdated.
- A Porta 3 passa a selecionar contrato específico por fase quando a fase está disponível no fluxo: DISCUSSION, REQUIREMENTS, ARCHITECTURE, PLAN, APPROVED, EXECUTION, INTEGRATION, REVIEW, TESTS, DELIVERY.
- DocumentationAgent permanece declarado como UNAVAILABLE até possuir executor dedicado e contrato/fluxo real; a existência do contrato não o torna executável.

A implementação ainda só pode ser considerada consolidada após CI, suíte JVM/Android e UI E2E/readiness verdes no mesmo HEAD.

## Correção da auditoria final — 2026-09-21

Os três pontos pendentes da auditoria foram fechados estruturalmente:

1. **Self-E2E fail-closed:** `ValidationEngine.selfAgent()` não cria mais contrato genérico para capability desconhecida. Sem registro de capability + responsável + contrato, o resultado é `FAIL` com `contract.missing` e `agent.unavailable`.
2. **Porta 2:** o contrato de conteúdo agora mantém os requisitos explícitos e também valida slots estruturados declarados (`sujeito`, `ação`, `ambiente`, `elementos`, `estilo`, `iluminação`, `composição`, `formato`, `restrições`) sem obrigar slots que não foram pedidos.
3. **Secretário/Roadmap:** cada tarefa do Roadmap registra especialista responsável e dependências. `SecretaryValidationRouter` transforma cada `ValidationResult` em rota explícita: especialista, usuário, Policy ou infraestrutura. O controller registra `ValidationFindingRouted` antes de `RoadmapValidationUpdated`; a correção continua usando o `RevisionFixer` existente sobre o mesmo passo/capability, preservando o responsável sem criar um segundo orquestrador.

Testes adicionados/corrigidos cobrem: capability sem contrato, slots do Prompt, roteamento de findings e vínculo de tarefas do Roadmap. O HEAD ainda deve passar por CI/UI E2E/readiness antes de qualquer declaração de consolidação.

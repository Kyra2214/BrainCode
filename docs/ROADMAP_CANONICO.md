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


## Marco 5 — Consolidação das 3 Portas e Agente Secretário

**Estado da auditoria real em 20/09/2026:** Porta 1 ≈ 72%, Porta 2 ≈ 88%, Porta 3 ≈ 81%. Essas porcentagens são estimativas de maturidade funcional/integração, não percentual de linhas de código e não devem ser tratadas como testes de aprovação.

### Regra de execução do roadmap

A evolução das portas será sequencial:

**Porta 1 → consolidar e validar → Porta 2 → consolidar e validar → integração de APIs → finalizar Porta 3.**

Não iniciar a integração de APIs externas antes de Porta 1 e Porta 2 estarem consolidadas. A Porta 3 já possui aproximadamente 81% da infraestrutura funcional necessária; portanto, não será reconstruída nem congelada. Os componentes existentes serão preservados e os ~19% restantes serão fechados depois da consolidação das Portas 1 e 2 e da camada de APIs.

### Marco 5.1 — Porta 1: Chat / Plano — ~92%

[x] formalizar o Secretário como entrada determinística para a Porta 1.
[x] separar claramente Chat/Plano das capacidades de Prompt e Criação.
[x] preservar conversa, contexto, memória, análise, planejamento e Web.
[x] Web permitida conforme Policy.
[x] APIs externas bloqueadas nesta fase.
[x] impedir vazamento de intenção para pesquisa, produção ou execução quando a ordem/restrição do usuário não permitir.
[x] consolidar regras de negação/restrição no planejamento.
[x] validar transições de estado e permissões da Porta 1.
[x] bateria de testes funcional + regressão.
[x] Planning Agent materializa e persiste PlanningArtifact com ideia, requisitos, decisões, pendências e referências.
[x] RequirementGate `NEEDS_CLARIFICATION` gera ClarificationQuestion e passo `chat.respond` com pergunta explícita.
[ ] CI/E2E/readiness final da Porta 1 no mesmo HEAD.
[ ] declarar Porta 1 consolidada somente após CI/E2E/readiness verdes no mesmo HEAD.

### Marco 5.2 — Porta 2: Prompt — ~88%

[x] formalizar entrada da Porta 2 pelo Secretário.
[x] preservar Prompt Agent/Creator, pesquisa Web, biblioteca, crítica, otimização e validação já existentes.
[x] Web permitida conforme Policy.
[x] APIs externas continuam bloqueadas.
[ ] permitir conversa controlada com a Porta 1 quando contexto for necessário.
[x] garantir que concluir um prompt não inicie criação/desenvolvimento/execução automaticamente.
[x] consolidar regras e permissões próprias da Porta 2.
[x] bateria de testes funcional + regressão.
[ ] CI/E2E/readiness final da Porta 2.
[ ] declarar Porta 2 consolidada somente com evidência verde.

### Marco 5.3 — Integração de APIs externas — após Portas 1 e 2

[ ] somente iniciar quando Porta 1 e Porta 2 estiverem formalmente consolidadas.
[ ] criar camada de Provider/API sem contaminar o Brain Core.
[ ] definir registro, descoberta, autorização, seleção e limites de providers.
[ ] manter Web disponível nas três portas.
[ ] APIs inicialmente bloqueadas por Policy até autorização explícita do fluxo.
[ ] preparar especialização futura da Porta 3 por provider/API.
[ ] testes de segurança, contrato, fallback, quota e observabilidade.

### Marco 5.4 — Porta 3: Criação / Desenvolvimento — ~81%

[x] preservar a infraestrutura já existente de Requirements, Planning, Code, UI, Backend, Database, Security, Test, Review/Critic, Integration e Release.
[x] formalizar a Porta 3 pelo Secretário sem criar um segundo orquestrador.
[x] separar discussão de criação de autorização de desenvolvimento.
[ ] fechar fluxo: pesquisa → requisitos → arquitetura → plano → aprovação → roadmap → divisão de tarefas → especialistas → integração → revisão → testes → entrega.
[ ] conectar a futura camada de APIs/providers às especialidades conforme Policy.
[ ] fechar segunda opinião e retorno ao responsável quando houver erro.
[x] consolidar Git autorizado / entrega ZIP.
[x] bateria de testes funcional + regressão.
[x] CI/E2E/readiness final da Porta 3.
[x] declarar Porta 3 consolidada somente com evidência verde.

Após auditoria, foram adicionados o gate persistente via `FileApprovalStore`, `CreationWorkflowPlanner`, `CreateDeliveryTest`, `FileApprovalStorePersistenceTest` e a jornada instrumentada `creationJourneyRequiresApprovalBeforeWorkspaceExecution`. CI `35536050052` e UI E2E `35536055182` passaram no commit `f859426`; a Porta 3 está consolidada para este HEAD.

### Critério de conclusão do Marco 5

O Marco 5 somente será considerado concluído quando:

1. Porta 1 estiver consolidada e validada.
2. Porta 2 estiver consolidada e validada.
3. A camada de APIs estiver integrada e validada.
4. Porta 3 estiver consolidada e validada.
5. CI/E2E/readiness apresentarem evidência correspondente no HEAD final.

### Registro de execução incremental

O primeiro módulo da Fase 1 foi implementado em `com.brain.secretary`. A classificação determinística, a matriz `DoorPolicy`, o transporte de `DoorScope`, a persistência de `SecretaryState` e a proteção do splitter já possuem testes focados verdes. A Porta 1 não é considerada consolidada enquanto CI remoto, regressão completa, E2E e readiness não apresentarem evidência verde no mesmo HEAD.

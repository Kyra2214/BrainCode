# Auditoria completa do plano BrainCode 2.3

**Data:** 2026-09-18  
**Repositório:** `Kyra2214/BrainCode`  
**Commit auditado:** `687839642b29c0287773070bf8afa96c8c134ad5` (`feat: add correlated behavior diagnostics`)  
**Escopo:** comparação do plano `docs/BRAINCODE_2.3_PLANO_TOTAL.md` com o código, callers reais, testes, documentação, duplicações e CI.

## Conclusão executiva

O BrainCode possui uma base comportamental extensa e coerente no módulo `:brain`, com contratos para requisitos, contexto, acceptance criteria, crítica, revisão, readiness, learning, contas/provedores e observabilidade. A suíte completa de `:brain` passou localmente com JDK 17.

Entretanto, a **2.3 ainda não está completamente integrada no caminho real Android**. O caminho principal já atravessa Reasoning, RequirementGate, Planner, PlanningGate, PolicyBroker, CapabilityDiscovery, Dispatcher, ActionGateway, executor, eventos e resultado. Ele ainda não atravessa, de forma obrigatória e operacional, `UniversalCritic`, `VerificationGate`, `ReadinessGate`, `FixVerifyLearn` e `ValidatedLearning`. Esses componentes existem e possuem testes unitários, mas a auditoria não encontrou caller de produção para os cinco no caminho Android.

**Veredito:** **2.3 parcialmente implementada; não pode ser marcada como concluída.** A maior divergência é comportamental: uma execução pode chegar a `ResultadoCiclo.aprovado == true` sem passar por critic universal, readiness ou learning validado.

## Evidência de validação

| Verificação | Resultado |
|---|---|
| `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :brain:test --no-daemon` | **PASS** localmente |
| `./gradlew test` no CI do commit `6878396` | **FAIL** na etapa “Run all JVM/unit tests”; build APK/lint foram pulados |
| UI E2E no commit `6878396` | **Em andamento** no momento da auditoria |
| Android build/lint local | **Não executável neste sandbox**, sem Android SDK/`ANDROID_HOME`/`local.properties` |
| `./scripts/verify-roofts-06.sh` | **PASS**: 195 arquivos, commit upstream `c004a747...`, tag `0.6.10` |
| Estado Git | `main` limpo e alinhado com `origin/main` |

A falha do CI ocorreu na suíte agregada antes de `assembleDebug`; não há APK produzido por essa execução. O teste local de `:brain` não substitui a verificação de todos os módulos exigida pelo plano.

## 1. Matriz do fluxo canônico

| Etapa do plano | Implementação encontrada | Caller Android real | Estado |
|---|---|---|---|
| Entender intenção | `ReasoningEngine.analyze` | `BrainSandboxController.executeObjective` | **Integrado** |
| Descobrir requisitos | `RequirementDiscovery`, dentro de `ReasoningEngine` | `executeObjective` | **Integrado** |
| Assumir explicitamente / bloquear | `AssumptionManager`, `RequirementGate` | `executeObjective` | **Integrado parcialmente** |
| Contexto relevante | `ReasoningState.contextPack`, `ContextPackBuilder` | estado é criado, mas o `ContextPack` não é propagado como entrada explícita para o ciclo | **Parcial** |
| Planejar | `KeywordPlanner` com overload que recebe `ReasoningState` | `executeObjective` | **Integrado** |
| Acceptance criteria | `PassoPlano.acceptanceCriteria`, `PlanningGate` | `executePlan` e indiretamente `executeObjective` | **Integrado superficialmente** |
| Descobrir capability | `CapabilityDiscovery` | `Dispatcher` | **Integrado** |
| Autorizar | `PolicyBroker` | `CicloExecucaoPlano.autorizarEExecutar` | **Integrado** |
| ActionGateway | `ActionGateway` | `Dispatcher` | **Integrado** |
| Executar | Sandbox, prompt, pesquisa e geração de código | `CompositeActionExecutor` injetado pelo `SandboxViewModel` | **Integrado** |
| Observar evidência | `ActionExecution.evidence`, eventos e resultado do ciclo | `CicloExecucaoPlano`, `BrainSandboxController` | **Integrado parcialmente** |
| Verificar | `ExecutorValidacaoProjeto`, `VerificationGate` | validação legada só no fallback sem Dispatcher; `VerificationGate` sem caller de produção | **Parcial** |
| Criticar | `UniversalCritic`, `CriticGate`, `DoubtDrivenReview` | nenhum caller de produção no caminho auditado | **Não integrado** |
| Corrigir/repetir | `FixVerifyLearn` e `RevisionEngine` | nenhum caller de produção comum ao Android | **Não integrado** |
| Readiness / DoD | `ReadinessEvaluator`, `ReadinessGate`, `DefinitionOfDone` | nenhum caller de produção no caminho Android | **Não integrado** |
| Aprender | `ValidatedLearning` | nenhum caller no controller; há memória de pesquisa e trackers especializados | **Parcial / não universal** |
| Responder | UI mostra ciclo/resultados e fallbacks | `SandboxViewModel` | **Integrado parcialmente** |

## 2. Achados críticos

### C1 — Execução aprovada sem Critic, Readiness e Learning universal

**Evidência:** `BrainSandboxController.executeObjective` termina após `bridge.authorizeAndExecute`, monta `OperationalState` e retorna `finalCycle`. Não há chamada a `UniversalCritic`, `CriticGate`, `ReadinessEvaluator`, `ReadinessGate`, `FixVerifyLearn` ou `ValidatedLearning`.

O `ResultadoCiclo.aprovado` depende dos status dos passos executados; portanto, “executou com sucesso” pode ser exposto como conclusão sem provar:

- requisitos atendidos;
- critérios de aceitação verificados;
- crítica sem findings;
- regressão/QA/segurança/arquitetura/release;
- Definition of Done;
- aprendizado validado.

Isso viola diretamente as seções 10, 11, 12, 13, 17, 18 e 28 do plano.

**Prioridade:** bloqueador da conclusão da 2.3.

**Correção recomendada:** introduzir um pós-ciclo obrigatório no controller ou em uma fachada comportamental única, com:

`ResultadoCiclo → VerificationResult → UniversalCritic → RevisionDecision/FixVerifyLearn → ReadinessReport → ValidatedLearning → resposta`.

O resultado final só deve ser “concluído/aprovado” após os gates correspondentes ao tipo de tarefa.

### C2 — `FixVerifyLearn` existe somente como contrato genérico/testado

A implementação em `brain/src/main/kotlin/com/brain/behavior/FixVerifyLearn.kt` é correta como componente genérico: preserva falhas, bloqueia learning quando a verificação falha e mantém a ordem SCAN → PLAN → FIX → VERIFY → LEARN. Porém, a busca de callers encontrou apenas a própria definição e testes/documentação.

**Consequência:** o plano promete correção universal, mas uma falha de capability no Android não entra automaticamente nesse ciclo.

**Prioridade:** alta.

### C3 — `UniversalCritic` e `DoubtDrivenReview` não são usados pelo caminho real

Os contratos e testes cobrem resultado vazio, requisito ausente, restrição `must:` e alto risco sem evidência. Contudo, o controller não converte o resultado real dos passos em `CritiqueInput` nem registra a decisão de revisão em `TaskState`/eventos.

**Consequência:** o bug de resultado vazio está coberto no componente unitário, mas não está provado como regressão arquitetural no caminho Android real.

**Prioridade:** alta.

### C4 — `ReadinessEvaluator` não bloqueia conclusão Android

`ReadinessEvaluator` exige os sete estágios e evidência por estágio, mas não é chamado pelo controller, pelo ciclo ou pelo `SandboxViewModel` antes de apresentar conclusão.

**Consequência:** a regra “nenhuma conclusão automática sem readiness” permanece declarativa.

**Prioridade:** alta.

### C5 — Fallback do chat contorna o caminho universal

Quando o Sandbox não está pronto, `SandboxViewModel` chama diretamente `brainApiGateway.complete(prompt).text`. Esse caminho pode consultar provider/memória e responder sem passar pelo `RequirementGate`, `PlanningGate`, `Policy`, `ActionGateway`, Critic ou Readiness.

O plano permite fallback somente com justificativa arquitetural explícita e sem contornar controles. A documentação atual não demonstra essa justificativa como uma política formal nem aplica os gates equivalentes no fallback.

**Prioridade:** alta.

**Correção recomendada:** transformar `BrainApiGateway` em provider interno de uma capability submetida ao mesmo plano/gates, ou marcar claramente o fallback como “resposta não executada/não validada” e impedir que seja apresentado como conclusão da tarefa.

## 3. Achados importantes de integração

### I1 — ContextPack é construído, mas não é um artefato explícito do plano

`ReasoningState` expõe `contextPack`, e `ContextPackBuilder` existe. O controller usa `ReasoningState` para alimentar assumptions e missing requirements do planner, mas não transporta o ContextPack no `TaskState`, `PassoPlano`, `PolicyContext`, eventos de execução ou entrada dos executores.

Isso reduz a rastreabilidade de histórico relevante, decisões, restrições, erros conhecidos e resultados anteriores exigida pela seção 7.

### I2 — Acceptance criteria são aceitos por presença, não por verificabilidade

`PlanningGate` verifica apenas se `step.acceptanceCriteria.isEmpty()` é falso. `PassoPlano` cria por padrão um critério genérico (`success`/`criterioSucesso`). Não há exigência geral de estratégia de verificação, pré-condição, pós-condição, evidência esperada ou vínculo de cada check ao resultado real.

A seção 9 pede critérios verificáveis e a seção 8 exige mais metadados por passo. A implementação atual cobre a forma mínima, mas não o contrato semântico completo.

### I3 — Verification depende de caminhos diferentes

No caminho com `Dispatcher`, o resultado do `ActionGateway` é convertido diretamente em `StatusPasso`; a validação de projeto do `ExecutorValidacaoProjeto` só aparece no fallback sem dispatcher. Isso cria duas semânticas de verificação:

- Dispatcher: sucesso do gateway → passo aprovado;
- fallback Sandbox: execução + validação de projeto → passo aprovado.

Para a arquitetura universal, a verificação deveria ser um estágio posterior comum, independente do executor escolhido.

### I4 — Observabilidade comportamental ainda é principalmente contrato

`BehaviorTrace`, `BehaviorDiagnostics` e `InMemoryBehaviorTraceSink` existem e têm redaction. A busca de produção não encontrou instrumentação ampla desses tipos no controller, dispatcher, ActionGateway ou `BrainExecutionCoordinator`.

O sistema tem `BrainEvent`, `ActionAuditLog` e `ExecutionTrace` próprios, mas a trilha `BehaviorTrace` não recebe automaticamente decisão, critic, revisão, readiness e learning. O documento da Fase 12 reconhece essa limitação.

### I5 — ProviderRegistry não é a fonte operacional única no Android

O plano da Fase 11 descreve `ProviderRegistry` como fonte única. O caminho Android real usa `ApiCatalogRegistry`/`ApiCatalog`, `DynamicFreeApiCatalog` e `AccountRouter`; `ProviderRegistry` foi encontrado principalmente como contrato/infraestrutura e testes.

Isso não é necessariamente um defeito de segurança, mas é uma divergência de governança: deve ser decidido se `ProviderRegistry` será adaptado para alimentar o `ApiCatalog` ou se a documentação deve declarar os dois papéis explicitamente.

## 4. Duplicações e pipelines paralelos

### D1 — Dois orquestradores de execução

Existem:

1. `android-module/.../CicloExecucaoPlano.kt`, usado pelo `BrainSandboxController` e pelo Android;
2. `brain/.../BrainExecutionCoordinator.kt`, com policy, router, retry, account routing, fallback local, health e memória.

O segundo não possui caller de produção encontrado; possui testes próprios. Ele implementa lógica de execução semelhante à do ciclo Android, mas com contratos de resultado e retry diferentes.

**Classificação:** duplicação arquitetural / componente órfão de produção. Não é recomendado manter duas fontes de verdade.

**Ação:** escolher um núcleo comum de orquestração, ou declarar `BrainExecutionCoordinator` como backend canônico e fazer o ciclo Android adaptar-se a ele. Até essa decisão, ambos devem ser marcados como pipelines distintos, não como uma única integração.

### D2 — Sistemas de memória com responsabilidades sobrepostas

Foram encontrados `LayeredMemory`, `ExperienceMemory`, `KnowledgeMemory`/`FileKnowledgeMemory`, `AndroidKnowledgeMemory` e `KnowledgeLearningCycle`. Eles representam domínios diferentes, mas o caminho Android grava episódios/evidências em `LayeredMemory` e usa `BrainApiGateway` com `KnowledgeLearningCycle`, enquanto `BrainIntegrationFacade` mantém `FileExperienceMemory` separado.

**Risco:** aprendizado operacional, conhecimento externo validado e memória de experiência podem divergir. A regra de “um sistema de memória” do plano exige uma política explícita de fronteiras e promoção entre camadas.

### D3 — Dois caminhos de prompt

Há um fluxo comportamental real de chat via `PromptGenerationExecutor`/`BrainSandboxController` e uma geração determinística de roadmap em `BrainIntegrationFacade.generatePrompts`. O segundo é documentado como local e não executa no Sandbox, portanto não é necessariamente errado, mas não deve ser apresentado como o mesmo ciclo universal.

### D4 — Múltiplas trilhas de observabilidade

`BrainEvent`, `ActionAuditLog`, `ExecutionTrace` e `BehaviorTrace` coexistem. Há valor em separar auditoria de ação, eventos de domínio e diagnóstico comportamental, mas faltam adaptadores ou uma política de correlação única. Atualmente uma auditoria precisa consultar várias fontes para reconstruir um run completo.

## 5. Código órfão ou de integração não comprovada

A análise de referências encontrou classes cuja implementação aparece apenas em sua própria definição e testes, incluindo:

- `FixVerifyLearnResult`/`FixVerifyLearn`;
- `DefinitionOfDone`;
- `CritiqueInput`/`UniversalCritic`;
- `ValidatedLearning`;
- `BehaviorTrace`/`BehaviorDiagnostics`;
- `BrainExecutionCoordinator` como caller de produção;
- `ProviderRegistry` como fonte operacional do caminho Android.

Nem toda classe com poucas referências é defeito: vários são modelos, value objects ou pontos de extensão. Porém, para as classes acima, a ausência de caller é relevante porque o próprio plano exige “caller real + testes + evidência do caminho de execução”.

## 6. Jornadas e testes

### Cobertura existente

A suíte `:brain` possui testes para contratos, planner, reasoning, gates, critic, readiness, learning, provider/account, observabilidade e coordenador. A UI E2E contém jornadas para:

- prompt de imagem;
- prompt de código;
- execução via `/run`;
- texto genérico;
- continuidade em segundo turno.

### Lacunas contra a matriz mínima e o teste de ouro

Não foi encontrada prova E2E Android de:

- pergunta real de esclarecimento por ambiguidade crítica;
- geração de aplicativo/arquivos com validação posterior;
- pesquisa com proveniência exibida e criticada;
- falha controlada seguida de classificação, alternativa ou correção;
- resultado vazio/parcial/incorreto atravessando o ciclo Android;
- Critic/Revision/Readiness/Learning no mesmo run;
- APK instalado e jornada funcional validada no ambiente auditado.

Além disso, no momento da auditoria o workflow `CI` do commit `6878396` estava com **falha** na etapa agregada de testes e o workflow `UI E2E` estava **em andamento**. Portanto, a exigência da seção 28 de build, lint e jornadas reais ainda não está comprovada para este commit.

## 7. Integridade do Roofts 0.6

A verificação automatizada passou:

```text
PASS Roofts 0.6: 195 files; commit c004a74784a08295d52749b04cda634125b9a581; tag 0.6.10
```

O pacote permanece em `app/src/main/assets/roofts/0.6` e a documentação corretamente declara `SKILL INTEGRATION = NOT STARTED`. Portanto, o payload está preservado, mas as Skills ainda não participam do ciclo universal, conforme o próprio plano exige para uma etapa posterior.

## 8. Plano de correção recomendado

### P0 — Fechar o ciclo universal no Android

1. Criar uma fachada única de pós-execução que receba `PlanoExecucao`, `ResultadoCiclo`, `ReasoningState`, evidências e tipo de trabalho.
2. Executar Verification comum, `UniversalCritic` e `DoubtDrivenReview`.
3. Em `NEEDS_REVISION`, encaminhar para `FixVerifyLearn` com limite explícito e sem aprender antes de verificar.
4. Avaliar `ReadinessEvaluator` com evidências reais dos sete estágios.
5. Só retornar estado final concluído após `ReadinessGate`.
6. Chamar `ValidatedLearning` somente quando evidência, verification, critic e readiness forem aprovados.

### P1 — Unificar contratos e rastreabilidade

1. Transportar `ContextPack` no estado da tarefa e nos eventos.
2. Expandir acceptance criteria para incluir estratégia de verificação e evidência esperada.
3. Fazer `ResultadoPasso` carregar uma representação comum de verification/critic/readiness, sem depender de texto livre.
4. Conectar `BehaviorDiagnostics` aos eventos do controller, ActionGateway e pós-ciclo.
5. Definir a relação oficial entre `ApiCatalog`, `ProviderRegistry`, `AccountRouter` e `ProviderAccountRouter`.

### P1 — Eliminar ou classificar pipelines paralelos

1. Decidir o destino de `BrainExecutionCoordinator`.
2. Se for legado, marcar, migrar testes úteis e remover após compatibilidade comprovada.
3. Se for canônico, fazer o Android usá-lo, evitando duplicação do ciclo.
4. Documentar fronteiras entre `LayeredMemory`, `ExperienceMemory` e `KnowledgeMemory`.
5. Unificar correlação entre `BrainEvent`, `ActionAuditLog`, `ExecutionTrace` e `BehaviorTrace`.

### P2 — Completar a prova E2E

Adicionar jornadas Android para ambiguidade, pesquisa com fonte, falha controlada, correção, resultado vazio e criação de arquivos. A suíte deve afirmar que critic/readiness bloqueiam conclusão incompleta.

## 9. Critério final de auditoria

Com base no código e nas evidências disponíveis, os itens do critério da seção 28 ficam assim:

| Critério | Estado |
|---|---|
| Entende intenção antes de executar | **PASS no caminho principal** |
| Requisitos alimentam o plano | **PASS parcial**; reasoning é passado, mas contexto não é materializado integralmente |
| Ambiguidade crítica gera pergunta/bloqueio | **PASS unitário; E2E Android não comprovado** |
| Contexto relevante preservado | **PARCIAL** |
| Planos possuem critérios de aceitação | **PASS estrutural; verificabilidade superficial** |
| Policy antes da execução | **PASS no Dispatcher/ActionGateway** |
| ActionGateway é fronteira | **PASS no caminho principal; há fallback direto do BrainApiGateway** |
| Skills comportamentais | **FAIL/PARCIAL**; Roofts instalado, integração de Skills não iniciada |
| Execução passa por verificação | **PARCIAL**; sem gate universal comum |
| SelfCritic universal | **FAIL no caller Android** |
| FixVerifyLearn funciona | **PASS unitário, FAIL como integração** |
| Readiness bloqueia conclusão | **PASS unitário, FAIL como integração** |
| Resultado vazio não substitui válido | **PASS unitário, não provado no E2E Android** |
| Memória recebe somente conhecimento validado | **PASS no `ValidatedLearning`; não conectado ao controller** |
| Android usa mesmo caminho canônico | **PARCIAL** |
| Cinco jornadas E2E reais | **Não comprovado neste commit; UI E2E em andamento** |
| Regressões passam | **`:brain:test` PASS; CI agregado FAIL** |
| Lint/build passam | **Não comprovado; build/lint foram pulados no CI falho e SDK ausente localmente** |
| Nenhuma classe nova órfã | **FAIL** |
| Roofts 0.3–0.6 íntegros | **PASS para verificação do Roofts 0.6; validação completa Android pendente** |
| Documentação atualizada | **PASS parcial; documentação reconhece vários limites, mas matriz deve ser atualizada após integração** |
| APK final gerado e instalado | **FAIL/não comprovado** |

**Conclusão:** o repositório está em uma boa base de consolidação, mas a auditoria deve permanecer aberta até que os gates de critic/revision/readiness/learning sejam ligados ao caminho Android, os pipelines duplicados sejam classificados e o CI/E2E produzam evidência verde.

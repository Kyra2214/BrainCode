# BrainCode 2.3 — Plano Total de Consolidação e Evolução

**Status:** PLANO OFICIAL DE EXECUÇÃO  
**Data:** 2026-09-20  
**Repositório:** `Kyra2214/BrainCode`  
**Objetivo:** transformar o BrainCode em um agente que compreende intenção, planeja, executa, verifica, critica, corrige e só então conclui.

**HEAD consolidado:** fa4dfa1ec2643d1d9b0d9a6f5b0eb36df8  
**Estado da consolidação:** pós-execução Android integrado; requisitos do Reasoning agora chegam também ao UniversalCritic; fallback direto do chat fora do ciclo está removido.  
**Fora do escopo 2.3:** Roofts 0.6 runtime, retrieval universal/semântico, hardening OS-level e leases/fencing.

---

## 0. REGRA PRINCIPAL

A 2.3 não será uma coleção de Skills adicionadas uma por uma.

O objetivo é construir primeiro uma **camada comportamental universal** que governe qualquer Capability, Agent, Skill, Tool, API, Sandbox ou Provider.

O Brain deve passar a obedecer a este ciclo:

```
ENTENDER
  ↓
DESCOBRIR REQUISITOS
  ↓
RESOLVER AMBIGUIDADE
  ↓
PLANEJAR
  ↓
AUTORIZAR
  ↓
EXECUTAR
  ↓
OBSERVAR E REGISTRAR EVIDÊNCIA
  ↓
VERIFICAR
  ↓
CRITICAR
  ↓
CORRIGIR / REPETIR SE NECESSÁRIO
  ↓
READINESS / DEFINITION OF DONE
  ↓
RESPONDER
  ↓
APRENDER
```

Nenhuma etapa deve ser apenas declarativa. O plano só é considerado implementado quando houver **caller real + testes + evidência do caminho de execução**.

---

# 1. BASE CONSOLIDADA

O estado atual já possui peças importantes:

- CapabilityRegistry / CapabilityDiscovery;
- PolicyBroker;
- ActionGateway;
- Dispatcher;
- DurableJobRunner;
- AgentRegistry / Agents bounded;
- SkillRegistry;
- RequirementDiscovery;
- AssumptionManager;
- ReasoningEngine;
- SelfCritic;
- RevisionEngine;
- memória e learning;
- Evidence / research;
- Sandbox;
- runtime Python de referência em `brain_runtime/`;
- RooftS é uma entidade única composta pelas camadas 0.3, 0.4, 0.5 e 0.6;
- RooftS 0.3 / 0.4 / 0.5 permanecem preservados;
- RooftS 0.6 / Agent Skills está instalado como camada do mesmo RooftS, sem integração comportamental nesta fase;
- definição canônica: `docs/ROOFTS.md`.

A 2.3 deve **reutilizar e integrar**, não duplicar.

---

# 2. ROOFTS 0.6 — FONTE DE COMPORTAMENTO

Roofts 0.6 foi instalado em:

```
app/src/main/assets/roofts/0.6
```

e extraído em:

```
/opt/roofts/0.6
```

O pacote deve permanecer preservado.

A integração será seletiva.

## 2.1 Conceitos prioritários

### Entendimento

- interview-me;
- idea-refine;
- spec-driven-development;
- context-engineering.

### Planejamento

- planning-and-task-breakdown;
- constraint-driven-development;
- definition-of-done.

### Execução com prova

- incremental-implementation;
- test-driven-development;
- api-and-interface-design.

### Correção

- debugging-and-error-recovery;
- doubt-driven-development;
- code-review-and-quality;
- code-simplification.

### Evidência e segurança

- source-driven-development;
- security;
- observability;
- testing patterns.

### Especialistas

Os quatro Agents do Roofts 0.6 podem servir como personas/especialistas:

- code-reviewer;
- security-auditor;
- test-engineer;
- web-performance-auditor.

Eles não devem virar quatro cérebros independentes.

---

# 3. ARQUITETURA COMPORTAMENTAL UNIVERSAL

Criar uma camada conceitual de Skill/Gates que seja aplicável a qualquer capability.

Modelo:

```
User Intent
    ↓
Requirement / Context
    ↓
BehaviorPolicy
    ↓
Skill/Gate selection
    ↓
Plan
    ↓
Capability
    ↓
ActionGateway
    ↓
Verification
    ↓
Critic
    ↓
Readiness
```

A Skill define **como trabalhar**.

A Persona/Agent define **quem trabalha**.

A Capability define **o que pode ser executado**.

O Command define **quando o fluxo é iniciado**.

A Policy define **o que é permitido**.

Essas responsabilidades não devem ser misturadas.

---

# 4. SKILL REGISTRY COMO ORQUESTRADOR DE COMPORTAMENTO

O `SkillRegistry` existente deve ser expandido somente se necessário.

Ele já possui:

- manifest;
- capabilities;
- trust level;
- assinatura;
- revogação;
- publicação no CapabilityRegistry.

A próxima evolução é transformar Skill em contrato comportamental, sem quebrar a segurança existente.

## 4.1 Contrato conceitual

Cada Skill deverá poder declarar:

```
Skill
├── identity
├── trigger
├── prerequisites
├── behavior
├── allowed capabilities
├── constraints
├── verification
├── post-check
└── exit criteria
```

Não criar execução paralela fora do ActionGateway.

---

# 5. BEHAVIOR GATES

Implementar Gates reutilizáveis.

## Gate A — Requirement Gate

Responsável por verificar:

- intenção;
- objetivo;
- sujeito;
- ação;
- contexto;
- restrições;
- formato esperado;
- dependências.

Reutilizar `RequirementDiscovery` e `AssumptionManager`.

Não executar a mesma descoberta duas vezes.

### Resultado

```
READY
NEEDS_CLARIFICATION
BLOCKED
```

Se uma informação for realmente necessária e não puder ser inferida com segurança, perguntar.

Se não for crítica, assumir explicitamente e continuar.

---

# 6. INTERVIEW-ME / AMBIGUIDADE

Incorporar o princípio de entrevista adaptativa do Roofts.

Regra:

- uma pergunta por vez;
- perguntar somente quando a resposta muda materialmente o plano;
- não interrogar o usuário sobre detalhes que podem ser inferidos;
- manter hipótese corrente;
- parar quando houver confiança suficiente para executar.

Exemplo:

Pedido:

```
quero um prompt de uma imagem de foguete
```

O Brain não deve simplesmente despejar texto genérico.

Ele deve determinar se já existe informação suficiente.

Se sim:

```
gera prompt
→ verifica
→ responde
```

Se faltar algo essencial:

```
pergunta somente o necessário
```

Esse comportamento vale para código, debugging, GitHub, arquivos, pesquisa e demais capacidades.

---

# 7. CONTEXT ENGINEERING

Integrar contexto como parte do comportamento universal.

O contexto deve separar:

- objetivo atual;
- histórico relevante;
- requisitos;
- decisões;
- restrições;
- evidências;
- resultados anteriores;
- erros conhecidos;
- estado da tarefa.

Corrigir a perda de continuidade causada por reconhecimento estreito de referências linguísticas.

O contexto não deve carregar o histórico inteiro indiscriminadamente.

Deve existir um Context Pack mínimo e relevante.

---

# 8. PLANEJAMENTO

O Planner deve receber o resultado real do Requirement Gate.

Não pode existir:

```
RequirementDiscovery
   ↓
resultado ignorado
   ↓
Planner independente
```

O fluxo deve ser:

```
RequirementDiscovery
      ↓
AssumptionManager
      ↓
ReasoningState
      ↓
Planner
      ↓
Acceptance Criteria
      ↓
Execution Plan
```

Cada passo relevante deverá possuir:

- objetivo;
- capability;
- entrada;
- resultado esperado;
- critérios de aceitação;
- pré-condições;
- pós-condições;
- política necessária;
- estratégia de verificação.

---

# 9. ACCEPTANCE CRITERIA

O conceito de `resultadoEsperado` em `PassoPlano` não é suficiente.

Adicionar contrato de critérios de aceitação sem quebrar compatibilidade.

Exemplo:

```
Task:
  "corrigir bug"

Acceptance:
  - teste reproduz o bug;
  - correção elimina a falha;
  - testes anteriores continuam passando;
  - nenhuma regressão conhecida;
  - build passa.
```

Para uma tarefa textual:

```
Task:
  "criar prompt"

Acceptance:
  - sujeito correto;
  - estilo solicitado;
  - contexto solicitado;
  - restrições respeitadas;
  - formato pedido.
```

---

# 10. READINESS GATE

Portar a ideia madura de:

```
brain_runtime/readiness.py
```

para Kotlin/Android.

Estágios obrigatórios:

1. implementation;
2. tests;
3. qa;
4. security;
5. architecture;
6. regression;
7. release.

O Gate deve produzir relatório estruturado.

```
READY
ou
BLOCKED
```

Uma resposta não deve ser marcada como concluída simplesmente porque uma ferramenta retornou alguma coisa.

---

# 11. DEFINITION OF DONE

Toda execução deve possuir uma definição de pronto adequada ao tipo da tarefa.

Exemplos:

### Código

```
implementado
→ compilado
→ testado
→ revisado
→ regressão verificada
```

### Debug

```
reproduzir
→ localizar causa
→ corrigir
→ testar
→ verificar regressão
→ registrar aprendizado
```

### Pesquisa

```
pergunta
→ fonte
→ evidência
→ síntese
→ criticidade
→ resposta
```

### Prompt

```
intenção
→ requisitos
→ geração
→ crítica
→ revisão
→ saída final
```

---

# 12. FIX-VERIFY-LEARN

Portar a arquitetura de:

```
brain_runtime/fix_verify_learn.py
```

O fluxo:

```
SCAN
 ↓
PLAN
 ↓
FIX
 ↓
VERIFY
 ↓
LEARN
```

deve ser uma capacidade comportamental reutilizável.

Não criar um debugger isolado apenas para código.

O mesmo padrão deve servir para:

- prompt;
- pesquisa;
- planejamento;
- execução;
- ferramentas;
- integração;
- código.

---

# 13. SELF-CRITIC UNIVERSAL

O `SelfCritic` atual é útil, mas ainda está fortemente orientado a PromptDomain.

Generalizar o conceito.

A crítica deve comparar:

```
objetivo
+
requisitos
+
restrições
+
evidências
+
resultado
```

e produzir:

```
PASS
FAIL
NEEDS_REVISION
BLOCKED
```

A crítica deve ser verificável.

Não deve significar “eu acho que ficou bom”.

---

# 14. DOUBT-DRIVEN DEVELOPMENT

Criar um post-check adversarial para decisões não triviais.

Ativar principalmente quando houver:

- mudança de arquitetura;
- nova branching logic;
- alteração de contrato;
- execução irreversível;
- contexto oculto;
- grande blast radius;
- propriedade não comprovada pelo compilador;
- segurança;
- migração;
- alteração de persistência.

Perguntas internas:

```
O que pode estar errado?
O que não foi provado?
Qual hipótese estou assumindo?
Existe evidência?
Existe regressão?
```

---

# 15. DEBUGGING UNIVERSAL

O Brain deve parar de fazer:

```
erro
→ tenta qualquer coisa
→ diz resolvido
```

e passar a:

```
erro
→ preservar evidência
→ reproduzir
→ formular hipótese
→ testar hipótese
→ corrigir
→ verificar
→ criticar
→ aprender
```

Nunca apagar evidência antes da análise.

---

# 16. O BUG DO RESULTADO VAZIO

O problema conhecido de `sandbox.code` retornar string vazia deve virar teste de regressão arquitetural.

A saída final não pode ser determinada apenas pelo último resultado bruto.

Regra:

- resultado vazio não substitui automaticamente resultado válido;
- falha não pode ser promovida a resposta;
- resultado parcial precisa de estado explícito;
- resultado final deve passar pelo Critic/Readiness quando aplicável.

O contrato deve respeitar o modelo existente:

```
PassoPlano
 ↓
ResultadoPasso
 ↓
ResultadoCiclo
```

Não adicionar campo `resultado` a `PassoPlano` apenas para mascarar o problema.

---

# 17. FLUXO CANÔNICO REAL

O fluxo que deve ser comprovado no código é:

```
Android / Chat
   ↓
Brain API
   ↓
Intent / Requirement
   ↓
Context
   ↓
Reasoning
   ↓
Plan
   ↓
Capability Discovery
   ↓
Skill / Agent selection
   ↓
Policy
   ↓
ActionGateway
   ↓
Task / Sandbox / Provider
   ↓
Evidence / Events
   ↓
Verification
   ↓
SelfCritic
   ↓
Revision / FixVerifyLearn
   ↓
Readiness
   ↓
Memory / Learning
   ↓
Chat
```

Componentes que existam fora desse caminho devem ser classificados como:

- integrado;
- parcialmente integrado;
- infraestrutura;
- legado;
- não integrado.

Não declarar integração apenas pela existência da classe.

---

# 18. CORRIGIR O CAMINHO ANDROID

A auditoria identificou que componentes novos podem existir no módulo `brain/` sem serem chamados pelo caminho real do app.

Prioridade máxima:

```
CicloExecucaoPlano
BrainSandboxController
BrainApiGateway
Android Chat
```

devem passar pelo mesmo comportamento universal.

Não pode haver um fallback que pule:

- Requirement;
- Policy;
- Critic;
- evidência;
- Readiness;

sem uma justificativa arquitetural explícita.

---

# 19. PROMPT LIBRARY

A `PromptLibrary` não deve continuar como caminho especial isolado.

A geração de prompt deve usar o mesmo comportamento universal:

```
intent
→ requirements
→ plan
→ capability
→ generate
→ critic
→ revision
→ readiness
```

O resultado do prompt serve como primeiro caso de validação da arquitetura comportamental, não como arquitetura exclusiva para prompts.

---

# 20. EVIDENCE / SOURCE-DRIVEN

Para tarefas de pesquisa:

- identificar fonte;
- guardar proveniência;
- distinguir fato de inferência;
- validar evidência;
- não despejar pesquisa crua no resultado;
- permitir Critic rejeitar afirmação sem suporte.

Reutilizar `EvidenceEngine` / `ResearchLayer` existentes quando adequados.

Não criar segundo sistema de evidência.

---

# 21. POLICY E SEGURANÇA

Policy continua sendo autoridade.

Skill:

```
não concede permissão
```

Agent:

```
não concede permissão
```

Provider:

```
não concede permissão
```

Capability descoberta:

```
não significa autorização
```

Fluxo:

```
Discovery
 ↓
Policy
 ↓
ActionGateway
 ↓
Execution
```

Credenciais nunca entram no Agent/Skill como segredo.

---

# 22. MEMORY / LEARNING

Depois de uma execução válida:

```
resultado
→ evidência
→ critic
→ aprendizado
→ memória
```

Não armazenar erro como conhecimento automaticamente.

Aprender também:

- qual capability resolveu;
- qual plano funcionou;
- qual hipótese falhou;
- qual correção funcionou;
- onde a informação foi encontrada;
- qual caminho deve ser evitado.

---

# 23. AGENTS

Os Agents continuam bounded.

Um Agent recebe:

```
mission
allowed skills
allowed capabilities
policy
context
environment
output contract
validation policy
```

O Agent não deve:

- escolher credencial diretamente;
- ignorar Policy;
- criar seu próprio objetivo;
- possuir LLM obrigatório;
- executar comando arbitrário.

Os quatro Agents do Roofts 0.6 podem virar especialistas reutilizáveis, mas sempre subordinados ao Brain.

---

# 24. ACCOUNT POOL / PROVIDER ROUTING

O plano anterior de Account Pool continua válido, mas entra **depois da consolidação comportamental**.

Arquitetura futura:

```
Agent
 ↓
Capability
 ↓
Policy
 ↓
AccountPool
 ↓
Provider
 ↓
Model
```

Implementar posteriormente:

- AccountRegistry;
- ProviderRegistry;
- AccountPool;
- AccountHealth;
- cooldown;
- retry classificado;
- fallback seguro;
- credential isolation;
- model catalog;
- observabilidade.

Não permitir que múltiplas contas sejam usadas para contornar cobrança, limites contratuais ou controles de serviços.

---

# 25. ORDEM TOTAL DE IMPLEMENTAÇÃO

## FASE 0 — BASELINE

Antes de alterar:

- build;
- unit tests;
- Android tests;
- lint;
- E2E disponíveis;
- estado Git limpo;
- APK baseline;
- registrar commit baseline.

Se baseline falhar, parar.

---

## FASE 1 — MAPA REAL

Produzir matriz:

```
componente
→ arquivo
→ caller
→ fluxo
→ teste
→ status
```

Priorizar:

- Android Chat;
- BrainApiGateway;
- BrainSandboxController;
- CicloExecucaoPlano;
- Dispatcher;
- ActionGateway;
- CapabilityDiscovery;
- SkillRegistry;
- RequirementDiscovery;
- AssumptionManager;
- ReasoningEngine;
- SelfCritic;
- RevisionEngine;
- DurableJobRunner;
- PromptLibrary.

---

## FASE 2 — CONTRATOS COMPORTAMENTAIS

Criar/ajustar contratos para:

- BehaviorGate;
- RequirementGate;
- AcceptanceCriteria;
- VerificationResult;
- CritiqueResult;
- ReadinessReport;
- ExecutionEvidence;
- RevisionDecision.

Evitar abstrações excessivas.

---

## FASE 3 — REQUIREMENT + CONTEXT

Integrar:

```
Intent
→ RequirementDiscovery
→ AssumptionManager
→ ContextPack
```

Corrigir continuidade e referências.

Testar prompts ambíguos.

---

## FASE 4 — PLANNER

Integrar requisitos reais ao Planner.

Adicionar acceptance criteria.

Garantir que cada passo tenha critério verificável.

---

## FASE 5 — SKILL / GATES

Implementar a camada universal de Gates.

Ordem:

1. Requirement Gate;
2. Planning Gate;
3. Execution Gate;
4. Verification Gate;
5. Critic Gate;
6. Readiness Gate;
7. Learning Gate.

Não implementar 25 Skills independentes.

---

## FASE 6 — EXECUÇÃO REAL

Integrar os Gates ao caminho:

```
CicloExecucaoPlano
→ Dispatcher
→ ActionGateway
→ capability
```

Toda execução relevante passa pelo comportamento universal.

---

## FASE 7 — FIX / VERIFY / LEARN

Integrar correção estruturada.

Resolver o bug de resultado vazio como teste de prova.

Adicionar regressões.

---

## FASE 8 — CRITIC / DOUBT / REVIEW

Generalizar SelfCritic.

Adicionar revisão adversarial em mudanças não triviais.

Integrar os especialistas do Roofts quando fizer sentido.

---

## FASE 9 — READINESS / DEFINITION OF DONE

Portar ReadinessGate.

Nenhuma conclusão automática sem readiness adequada.

---

## FASE 10 — MEMORY / LEARNING

Conectar aprendizado ao resultado validado.

Persistir evidência e correções.

---

## FASE 11 — PROVIDER / ACCOUNT POOL

Somente após o comportamento universal estar funcionando:

- AccountRegistry;
- AccountPool;
- AccountRouter;
- Health;
- Cooldown;
- Failover;
- Credential Isolation.

---

## FASE 12 — DIAGNÓSTICO E OBSERVABILIDADE

Criar diagnóstico do runtime sem vazamento de segredo.

Registrar:

- decisão;
- capability;
- policy;
- tentativa;
- resultado;
- critic;
- revisão;
- readiness;
- aprendizado.

---

## FASE 13 — E2E ANDROID

Provar:

```
UI
→ Brain
→ plan
→ capability
→ execution
→ verification
→ result
→ UI
```

Não aceitar apenas teste de existência de tela.

---

## FASE 14 — CONSOLIDAÇÃO

Após todos os testes:

- remover duplicações;
- classificar código órfão;
- atualizar contratos;
- atualizar documentação;
- manter compatibilidade;
- preservar Roofts 0.3–0.6;
- garantir que nenhuma classe nova fique sem caller.

---

# 26. MATRIZ DE TESTES MÍNIMA

## Intent

- pedido claro;
- pedido ambíguo;
- refinamento;
- referência a mensagem anterior;
- pedido com múltiplas restrições.

## Planning

- requisitos alimentam planner;
- acceptance criteria são preservados;
- dependências são respeitadas.

## Execution

- Policy antes da ação;
- ActionGateway recebe ação;
- capability correta;
- resultado estruturado.

## Verification

- resultado vazio;
- resultado parcial;
- resultado incorreto;
- resultado válido.

## Critic

- requisito ausente;
- requisito atendido;
- contradição;
- resposta inventada.

## Revision

- uma revisão corrige;
- limite de revisões;
- falha permanece bloqueada.

## Readiness

Testar todos os sete estágios.

## Debug

```
reproduzir
→ corrigir
→ testar
→ regressão
→ aprendizado
```

## E2E

Executar jornadas reais no Android com RootFS disponível.

---

# 27. TESTE DE OURO DA 2.3

Criar uma suíte de jornadas que prove o comportamento.

### Jornada A — Prompt

```
"Quero um prompt de uma imagem de um foguete..."
```

Deve:

- entender;
- descobrir requisitos;
- planejar;
- gerar;
- criticar;
- corrigir se necessário;
- responder.

### Jornada B — Código

```
"Corrija este bug..."
```

Deve:

- reproduzir;
- investigar;
- corrigir;
- testar;
- verificar regressão;
- só então concluir.

### Jornada C — Pesquisa

```
"Pesquise X..."
```

Deve:

- pesquisar;
- registrar evidência;
- sintetizar;
- validar;
- responder com proveniência.

### Jornada D — Ambiguidade

Deve perguntar somente o que for realmente necessário.

### Jornada E — Falha

Uma capability deve falhar controladamente.

O Brain deve:

```
detectar
→ classificar
→ corrigir ou escolher alternativa
→ verificar
```

sem declarar sucesso falso.

---

# 28. CRITÉRIO DE CONCLUSÃO DA 2.3

A 2.3 só pode ser marcada como concluída quando:

- [ ] Brain entende intenção antes de executar;
- [ ] requisitos realmente alimentam o plano;
- [ ] ambiguidades críticas geram pergunta;
- [ ] contexto relevante é preservado;
- [ ] planos possuem critérios de aceitação;
- [ ] Policy permanece antes da execução;
- [ ] ActionGateway continua como fronteira;
- [ ] Skills são comportamentais e não apenas catálogo;
- [ ] execução passa por verificação;
- [ ] SelfCritic é universal;
- [ ] FixVerifyLearn funciona;
- [ ] ReadinessGate bloqueia conclusão incompleta;
- [ ] resultado vazio não substitui resultado válido;
- [ ] memória recebe somente conhecimento validado;
- [ ] Android usa o mesmo caminho canônico;
- [ ] pelo menos cinco jornadas E2E reais passam;
- [ ] testes de regressão passam;
- [ ] lint/build passam;
- [ ] nenhuma classe nova ficou órfã;
- [ ] Roofts 0.3–0.6 permanecem íntegros;
- [ ] documentação é atualizada;
- [ ] APK final é gerado e instalado para validação.

---

# 29. O QUE NÃO FAZER

Não:

- reescrever o BrainCode;
- criar um LLM próprio para cada Agent;
- criar uma Skill para cada bug;
- copiar o Roofts literalmente;
- copiar código externo sem necessidade;
- criar segundo CapabilityRegistry;
- criar segundo PolicyBroker;
- criar segundo EvidenceEngine;
- criar segundo sistema de memória;
- ignorar o runtime Python de referência;
- executar diretamente fora do ActionGateway;
- promover qualquer resposta automaticamente;
- declarar “aprovado” sem teste;
- considerar classe existente como integração;
- fazer fallback que contorne Policy;
- colocar credenciais em Agents;
- usar Account Pool para contornar limites de serviços.

---

# 30. ESTRATÉGIA DE IMPLEMENTAÇÃO PARA O MANUS

O Manus deverá trabalhar em **fatias verticais verificáveis**, não em uma grande alteração sem checkpoints.

Para cada fase:

1. inspecionar código real;
2. localizar o caller;
3. implementar o menor contrato necessário;
4. criar testes;
5. integrar ao fluxo real;
6. executar testes;
7. executar build/lint;
8. verificar diff;
9. produzir evidência;
10. somente então avançar.

Após cada fase aprovada, registrar:

```
commit
estado
testes
artefatos
riscos
próxima fase
```

Se encontrar arquitetura divergente do plano:

**não improvisar uma segunda arquitetura.**

Registrar a divergência, verificar os contratos existentes e adaptar a implementação ao código real preservando os princípios deste documento.

---

# 31. RESULTADO FINAL ESPERADO

O BrainCode 2.3 deverá deixar de parecer:

```
usuário
→ palavra-chave
→ ferramenta
→ resposta
```

e passar a operar como:

```
usuário
   ↓
ENTENDER
   ↓
REQUISITOS
   ↓
CONTEXTO
   ↓
PLANEJAR
   ↓
POLICY
   ↓
EXECUTAR
   ↓
EVIDÊNCIA
   ↓
VERIFICAR
   ↓
CRITICAR
   ↓
CORRIGIR
   ↓
READINESS
   ↓
APRENDER
   ↓
RESPONDER
```

Essa camada comportamental é a fundação.

Depois dela, novas APIs, Tools, Agents, Skills, providers e capacidades podem ser adicionados sem precisar reinventar o comportamento do Brain a cada recurso novo.

---

# 32. GOVERNANÇA

Este documento é o plano operacional total da consolidação 2.3.

Ele não substitui:

- `docs/ARQUITETURA_ATUAL.md`;
- `docs/ESTADO_ATUAL.md`;
- `docs/ROADMAP_CANONICO.md`;
- contratos técnicos.

Ele organiza a execução da próxima evolução.

Documentos antigos continuam válidos como histórico, mas qualquer implementação deve obedecer primeiro ao código real, testes, contratos atuais e a este plano.

**Regra final: comportamento primeiro; capacidades depois.**

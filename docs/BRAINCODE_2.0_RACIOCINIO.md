# BrainCode 2.0 — ReasoningEngine, Qualidade e Visão Futura

> Documento de planejamento técnico. A Fase 1 é a consolidação imediata do raciocínio do BrainCode e deve funcionar sem LLM e sem depender da Fase 2. A Fase 2 registra a visão futura de evolução do Brain para ciclos operacionais, memória compartilhada e estratégias avançadas de planejamento.

## Bugs 1–3

### Bug 1 — `PromptDomain.classificar()` tem wildcard

`PromptDomain.classificar()` possui uma regra ampla que classifica como `IMAGEM` qualquer texto contendo `prompt` sem determinadas exclusões:

```kotlin
texto.containsAny(
    "imagem", "foto", "fotografia", "fotorrealista", "foto-realista", "ilustra", "arte digital",
    "pintura", "retrato", "desenho", "render", "wallpaper", "cartaz", "pôster", "poster", "capa"
) || ("prompt" in texto && !texto.containsAny("código", "codigo", "program", "script", "texto", "resumo")) -> IMAGEM
```

Qualquer “prompt” sem exclusões suficientes cai em `IMAGEM`. O pedido “crie um prompt pro meu app chatbox quero a interface idêntica a do chatgpt” foi classificado como `IMAGEM`.

**Correção prevista:** remover o wildcard ou inverter a prioridade das regras, ampliando as exclusões para termos como `app`, `interface`, `aplicativo`, `tela`, `layout` e equivalentes. “Prompt” é um artefato solicitado pelo usuário, não um domínio semântico por si só.

### Bug 2 — `SandboxViewModel.kt` vaza passos internos para o chat

Em `SandboxViewModel.kt`, por volta da linha ~699, existe:

```kotlin
appendThreadEvent(ThreadEvent.System("Passo: $message"))
```

Passos internos do pipeline/gates — por exemplo, “Passo: pesquisar: APROVADO” — são adicionados como bolhas normais e persistentes no chat. Isso polui a conversa e mistura telemetria/estado interno com conteúdo destinado ao usuário. Mensagens como “Não encontrei prompt compatível...” também podem vazar diagnóstico interno como se fossem resposta final.

**Correção prevista:** eventos internos devem ir para logs/telemetria ou para eventos de progresso discretos; somente resultados e mensagens realmente destinadas ao usuário entram no histórico normal do chat.

### Bug 3 — `LocalPromptCreatorAgent.aplicarAlteracoesConcretas(...)` confunde refinamento com nova solicitação

`LocalPromptCreatorAgent.aplicarAlteracoesConcretas(promptAtual, instrucao)` aplica alterações pontuais por regras específicas de ambiente/meteorologia e não recalcula corretamente o sujeito/objetivo. Quando é usado para uma nova solicitação independente, ele mantém elementos do prompt anterior em vez de criar um novo resultado a partir do novo pedido.

**Correção prevista:** distinguir explicitamente **refinamento** de **nova tarefa**. Refinamentos devem operar sobre o artefato atual; novas solicitações devem reconstruir o artefato desde a nova identidade da tarefa, sem herdar requisitos do contexto anterior.

---

# Fase 1 — ReasoningEngine cognitivo + qualidade

## Escopo da Fase 1

A Fase 1 implementa o núcleo cognitivo determinístico do BrainCode e resolve diretamente os Bugs 1–3. Ela deve funcionar **sem LLM, sem API externa e sem depender de qualquer componente da Fase 2**. LLMs são capacidades opcionais de escalonamento para tarefas complexas, não requisito do ReasoningEngine.

A Fase 1 transforma o fluxo linear atual em um ciclo explícito de entendimento, requisitos, planejamento, evidência, execução, crítica, revisão e decisão.

```text
Usuário
  ↓
IntentAnalyzer
  ↓
RequirementDiscovery ↔ AssumptionManager
  ↓
TaskState
  ↓
Planner
  ↓
EvidenceChecker
  ↓
Execução local / Capability selecionada
  ↓
SelfCritic
  ↓
RevisionEngine (se necessário)
  ↓
EvidenceChecker / SelfCritic
  ↓
DecisionEngine
  ↓
Resultado
```

## 1. IntentAnalyzer

Substitui a classificação superficial por palavra-chave isolada por uma interpretação estruturada da solicitação.

Responsabilidades:

- identificar objetivo e tipo de tarefa;
- separar **artefato solicitado** de **domínio do artefato**;
- identificar se a mensagem é uma nova tarefa ou continuação/refinamento;
- impedir que a palavra “prompt” determine sozinha o domínio;
- preservar contexto relevante sem carregar requisitos de tarefas anteriores indevidamente.

**Regra:** `prompt` é um tipo de artefato. Não é sinônimo de `IMAGEM`.

## 2. RequirementDiscovery

Implementa descoberta de requisitos no estilo de **slot filling**.

O Brain deve:

1. identificar os requisitos já presentes;
2. identificar requisitos obrigatórios ausentes;
3. perguntar somente o que realmente falta;
4. aceitar respostas em qualquer ordem;
5. acumular as respostas no `TaskState`;
6. não inventar requisitos importantes.

Exemplo:

```text
Usuário: “crie um Prompt de código para a interface”

Brain:
- objetivo: criar prompt
- tipo: código
- objeto: interface
- faltando: finalidade/escopo da interface

Brain pergunta:
“Claro. Só preciso de mais uma coisa: essa interface é para qual tipo de app/sistema e quais elementos você quer nela?”
```

## 3. AssumptionManager

Diferencia três categorias:

- **explícito:** veio diretamente do usuário;
- **inferido seguro:** pode ser assumido sem alterar o objetivo;
- **ausente/necessário:** precisa ser perguntado.

Assumptions nunca podem ser silenciosamente promovidas a requisitos explícitos.

Isso permite que o Brain use defaults seguros sem transformar uma suposição em uma decisão do usuário.

## 4. TaskState

`TaskState` é o estado cumulativo da tarefa.

Deve conter, conceitualmente:

- objetivo;
- identidade/tipo da tarefa;
- requisitos explícitos;
- requisitos opcionais;
- requisitos ausentes;
- assumptions;
- evidências;
- plano;
- capacidades selecionadas;
- resultado;
- críticas;
- revisões;
- decisão final.

O `TaskState` também representa, tecnicamente, o **Dialogue State Tracking** do BrainCode: o estado da tarefa é acumulado entre turnos e atualizado conforme o usuário fornece novas informações.

## 5. Planner

O Planner recebe uma tarefa suficientemente especificada e produz um plano executável.

Na Fase 1, o Planner deve priorizar:

- capacidades locais;
- ferramentas existentes;
- Sandbox;
- pesquisa somente quando necessária;
- validação determinística;
- execução sem LLM sempre que possível.

O Planner não deve transformar toda tarefa em uma chamada de IA.

## 6. EvidenceChecker

Verifica se o resultado possui evidência suficiente para sustentar a decisão.

Pode usar:

- testes;
- schemas;
- regras determinísticas;
- existência de arquivos;
- resultados de compilação;
- resultados de execução;
- cobertura dos requisitos;
- fontes pesquisadas, quando houver pesquisa.

Para geração de prompts, por exemplo, deve detectar que “céu estrelado ao fundo” foi requisito explícito e não pode simplesmente desaparecer do resultado.

## 7. SelfCritic

Evolui o `PromptQualityValidator` para um mecanismo de crítica reutilizável.

O SelfCritic deve responder estruturalmente:

- o resultado atende ao objetivo?
- todos os requisitos explícitos estão presentes?
- existem contradições?
- existe conteúdo inventado?
- existe informação perdida?
- há defeitos verificáveis?
- existe evidência suficiente?

Sempre que possível, a crítica deve estar ancorada em sinais externos/determinísticos, e não somente em “o próprio modelo disse que está bom”.

## 8. RevisionEngine

Substitui correções frágeis baseadas em regex específicas por revisão guiada pela crítica.

Fluxo:

```text
Resultado
   ↓
SelfCritic
   ↓
Diagnósticos estruturados
   ↓
RevisionEngine
   ↓
Novo resultado
   ↓
SelfCritic
```

A revisão deve ser limitada por orçamento de tentativas para evitar loops infinitos.

## 9. DecisionEngine

É o ponto que decide entre:

- perguntar ao usuário;
- executar localmente;
- pesquisar;
- revisar;
- entregar;
- escalar para uma capacidade especializada, incluindo uma IA, quando essa capacidade estiver disponível e for realmente necessária.

A IA permanece subordinada à decisão do Brain. Ela não controla o fluxo global.

## As 2 regras de ouro

### Regra de ouro 1 — Não inventar requisito importante

Se uma informação necessária para cumprir corretamente a tarefa não foi fornecida e não pode ser inferida com segurança, o Brain pergunta ao usuário.

### Regra de ouro 2 — Não usar IA quando o Brain consegue resolver sozinho

O caminho local é sempre considerado primeiro. A IA é uma capacidade opcional para tarefas que realmente exigem geração/raciocínio especializado ou para escalonamento deliberado.

## ReasoningEvent

O ReasoningEngine deve emitir eventos estruturados para uma futura área de **Raciocínio** na interface.

Não deve expor chain-of-thought bruto.

Exemplo:

```text
ReasoningEvent
├── stage: INTERPRET
├── status: COMPLETED
├── summary: “Objetivo identificado”
├── details: estruturados
├── timestamp
└── metadata
```

Estágios previstos:

- objetivo identificado;
- requisitos encontrados;
- requisitos ausentes;
- assumption registrada;
- plano criado;
- pesquisa necessária;
- ação executada;
- evidência encontrada;
- crítica;
- revisão;
- decisão;
- entrega.

Esses eventos são observabilidade estruturada do Brain, não transcrição de pensamento privado.

---

# Fase 2 — Visão futura

A Fase 2 não é requisito para a Fase 1. Ela representa a evolução do Brain para um sistema com ciclos cognitivo, operacional e de qualidade integrados, memória compartilhada e estratégias avançadas para tarefas difíceis.

## 1. Três loops compartilhando o mesmo estado

```text
                 ┌────────────────────┐
                 │      TaskState      │
                 └─────────┬──────────┘
                           │
             ┌─────────────┼─────────────┐
             ↓             ↓             ↓
        COGNITIVO      OPERACIONAL     QUALIDADE
             │             │             │
      interpretar      planejar       verificar
      requisitos       agir           criticar
      perguntar        observar       revisar
      assumir          atualizar      decidir
             └─────────────┼─────────────┘
                           ↓
                      Memory futura
```

### Loop cognitivo

Responsável por compreender o pedido e manter o estado da conversa:

`Interpret → Requirements → Assumptions → Ask/Proceed`

### Loop operacional

Responsável por transformar o plano em ações reais:

`Plan → Pre-Action Check → Act → Observe → Update State → Next Action`

### Loop de qualidade

Responsável por verificar o que foi produzido:

`Result → Evidence → Critic → Revise → Verify → Deliver`

## 2. ReAct para o Sandbox

O loop operacional poderá adotar uma forma de **ReAct**, intercalando raciocínio operacional, ação e observação para adaptar o plano conforme o ambiente real muda. O objetivo no BrainCode não é copiar a implementação de um paper, mas aplicar o princípio ao Sandbox: uma ação produz uma observação; a observação atualiza o estado; o Brain decide a próxima ação. citeturn0academia0turn0search2

```text
PLAN
 ↓
PRE-ACTION CHECK
 ↓
ACT
 ↓
OBSERVE
 ↓
UPDATE TASK STATE
 ↓
DECIDE NEXT ACTION
 └──────────────→ ACT
```

Isso é especialmente relevante para:

- instalação de dependências;
- compilação;
- execução de testes;
- descoberta de toolchains;
- comandos que falham e exigem adaptação;
- tarefas de engenharia no Sandbox.

## 3. Pre-Action Check

O Brain deverá verificar autorização, risco, capacidade e contexto antes de executar uma ação.

No código atual, esse conceito deve ser mapeado para as estruturas já existentes, especialmente:

- `ActionGateway`;
- `ApprovalStore`;
- políticas de execução;
- registros de autorização.

A evolução futura deve reforçar essas estruturas, não criar um segundo sistema paralelo de autoridade.

## 4. Tree of Thoughts como modo alternativo do Planner

Tree of Thoughts (ToT) deve ser tratado como **estratégia opcional do Planner**, acionada somente para tarefas difíceis, ambíguas ou que exigem exploração de múltiplos caminhos.

Não deve ser usado em toda tarefa.

O princípio é permitir:

- gerar caminhos candidatos;
- avaliar caminhos;
- explorar alternativas;
- voltar atrás quando necessário;
- escolher uma estratégia com base em avaliação.

Isso é particularmente útil quando uma decisão inicial pode comprometer todo o plano. citeturn0academia1

## 5. Memory compartilhada e em camadas

A memória futura deve ser compartilhada pelos três loops, mas com funções distintas e **proveniência preservada**.

Camadas previstas:

```text
Memory
├── Episodic
│   └── eventos e histórico de tarefas
├── Semantic
│   └── conhecimento consolidado
├── Procedural
│   └── como executar determinadas tarefas
└── Evidence / Provenance
    └── origem, timestamp e evidência
```

A memória deve seguir um ciclo de:

`write → manage → read`

com filtros de escrita, tratamento de contradições, controle de latência e governança de privacidade. citeturn0academia3

### Mapeamento com o código existente

`ResearchResult` já possui parte importante da estrutura necessária para uma memória com proveniência. A evolução deve aproveitar os campos existentes de fonte/evidência em vez de criar uma representação paralela.

A direção futura é que um conhecimento pesquisado carregue sua origem e evidência durante todo o ciclo, permitindo que o Brain saiba **de onde veio uma informação e por que ela foi aceita**. Sistemas recentes de memória de agentes também exploram memória episódica, memória semântica curada e grafos de entidades/eventos com proveniência explícita. citeturn0academia4

## 6. TaskState como Dialogue State Tracking

O `TaskState` da Fase 1 já é a base para o Dialogue State Tracking: ele acumula slots/requisitos e atualizações entre turnos.

A Fase 2 apenas amplia esse mesmo estado para conectar:

`conversa → plano → ação → observação → evidência → memória`.

Não deve existir um estado paralelo para cada loop.

## 7. IA como capacidade especializada

Na visão final do BrainCode, LLM/IA não é o núcleo de controle.

O Brain deve conseguir operar localmente e gratuitamente sempre que a tarefa permitir. Uma IA externa ou modelo local entra como **capacidade especializada**, principalmente para geração de código complexo, raciocínio especializado ou tarefas que excedam as capacidades determinísticas disponíveis.

```text
Brain decide
    ↓
“Preciso de uma capacidade de geração especializada”
    ↓
IA/LLM opcional
    ↓
resultado
    ↓
Brain valida
    ↓
aceita / revisa / rejeita
```

A troca do provedor de IA não deve alterar o núcleo do Brain.

---

# Resumo atualizado

1. **Corrigir Bugs 1–3:** classificação errada de prompt/interface, vazamento de passos internos e confusão entre nova tarefa e refinamento.
2. **Implementar a Fase 1:** ReasoningEngine cognitivo + qualidade, com IntentAnalyzer, RequirementDiscovery, AssumptionManager, Planner, EvidenceChecker, SelfCritic, RevisionEngine, DecisionEngine, TaskState e ReasoningEvent.
3. **Manter a Fase 1 100% independente de LLM:** o Brain deve funcionar sem API e priorizar capacidades locais.
4. **Validar a Fase 1 no fluxo real do aplicativo:** sem criar componentes órfãos; o ReasoningEngine deve participar da execução efetiva das tarefas.
5. **Fase 2 — Evolução futura:** integrar os três loops, Memory compartilhada com proveniência, ReAct no Sandbox, Pre-Action Check sobre ActionGateway/ApprovalStore, Tree of Thoughts como modo opcional do Planner e memória em camadas.

---

# Referências

## Base conceitual da Fase 1

- Slot filling / Dialogue State Tracking — referência conceitual para descoberta cumulativa de requisitos e manutenção de estado da tarefa.
- Reflection / Self-Critique — geração, crítica e revisão como ciclo de melhoria; na implementação do BrainCode, críticas devem ser ancoradas em evidências verificáveis sempre que possível.
- Structured reasoning — eventos e estados estruturados em vez de exposição de chain-of-thought privado.

## Referências da Fase 2

### ReAct

Yao et al., **ReAct: Synergizing Reasoning and Acting in Language Models**. O trabalho combina raciocínio e ações intercaladas, permitindo atualizar planos a partir das observações do ambiente. citeturn0academia0turn0search2

### Tree of Thoughts

Yao et al., **Tree of Thoughts: Deliberate Problem Solving with Large Language Models**. Propõe explorar múltiplos caminhos de raciocínio, avaliar estados intermediários e realizar backtracking quando necessário. citeturn0academia1

### Memória de agentes

Du, **Memory for Autonomous LLM Agents: Mechanisms, Evaluation, and Emerging Frontiers** (2026). Apresenta memória como ciclo write–manage–read e discute armazenamento, recuperação, reflexão, contexto hierárquico, contradições, latência e privacidade. citeturn0academia3

### Memória com proveniência

Wu & Zhu, **Agent Zero Memory: Provenance-Aware Long-Term Memory for LLM Agents** (2026). Explora memória episódica, grafo entidade-evento e memória documental semântica curada, com proveniência e ponte explícita para evidências. citeturn0academia4

### SWE-agent

A literatura de agentes para engenharia de software, incluindo o SWE-agent, reforça a importância da interface entre agente e ambiente de computador como parte do próprio desenho do agente. Na Fase 2, isso orienta a evolução do Brain/Sandbox e de suas capacidades de ação e observação.

---

## Regra de arquitetura

> **O Brain pensa sobre a tarefa, controla o fluxo e valida o resultado. Ferramentas executam. A IA é uma capacidade opcional especializada. A memória preserva conhecimento e evidência. Nenhuma dessas peças substitui o Brain como orquestrador.**

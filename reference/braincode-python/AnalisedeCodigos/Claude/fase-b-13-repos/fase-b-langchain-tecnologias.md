# Fase B — langchain-ai/langchain: tecnologias, ferramentas e técnicas

> Levantamento técnico do ecossistema LangChain/LangGraph/LangSmith —
> arquitetura real, não só descrição de marketing. Sem propor mudança em
> nada.

## 1. Três produtos, três responsabilidades bem separadas

O próprio time divide o ecossistema em três peças com papéis distintos,
não sobrepostos:

- **LangChain** — blocos de construção (model adapters, retrievers,
  loaders, construção rápida de agente). Primitiva central: `Runnable`,
  composto em pipelines via **LCEL** (LangChain Expression Language).
- **LangGraph** — orquestração de agentes com estado, já em GA como
  motor principal para sistemas multi-agente. Primitiva central:
  `StateGraph` — nós, arestas, checkpoints, com **reducers** explícitos
  para mesclar atualizações de estado concorrentes.
- **LangSmith** — observabilidade e avaliação. Não gerencia estado, só
  observa e traça a execução (run tree com trace spans).

## 2. `Runnable`/LCEL vs `StateGraph`: dois modelos de controle de fluxo

- `Runnable` (LCEL): pipeline linear, com ramificação limitada. Estado
  passa através da cadeia mas **não é persistido** entre execuções.
- `StateGraph` (LangGraph): execução baseada em grafo, com loops,
  roteamento condicional, retries e **interrupts** — ou seja, suporte
  nativo a human-in-the-loop (a execução pode parar, esperar aprovação
  humana, e retomar de onde parou). Estado é explícito, compartilhado
  entre nós, e persistido via checkpoints.

Essa distinção — pipeline linear vs. grafo com estado persistido e pontos
de interrupção — é o núcleo técnico que separa as duas bibliotecas.

## 3. Checkpointing e "exactly-once execution"

LangSmith Deployment roda agentes LangGraph num runtime gerenciado e
durável, com aprovações human-in-the-loop, agentes em background, e
execução "exactly-once" (a mesma execução não roda duas vezes por engano)
em infraestrutura que escala horizontalmente. O checkpoint é o que
permite pausar/retomar sem perder estado, mesmo em falha ou reinício.

## 4. Observabilidade: LangSmith

- Tracing granular: cada chamada de LLM, chamada de tool e transição de
  estado dentro de um agente LangGraph vira um span de trace navegável,
  com diffs de estado nó-a-nó.
- Avaliação: datasets offline + avaliadores online do tipo "LLM-as-judge".
- Versionamento e teste de prompts.
- Suporte a OpenTelemetry de ponta a ponta — qualquer app compatível com
  OTel pode exportar pro endpoint do LangSmith, não só apps LangChain.
- Alternativas equivalentes no mercado (pra referência de padrão, não
  como parte do LangChain em si): Langfuse (open-source, OTel-nativo),
  Arize Phoenix, Laminar.

## 5. Padrão de node de agente dentro de workflows determinísticos (n8n como consumidor)

Vale registrar como ponto cruzado com outro repositório da Fase B: o n8n
constrói seu próprio sistema de agente **em cima** do framework LangChain
em JavaScript (nós "cluster" que definem a lógica principal do agente +
sub-nós que fornecem capacidades específicas — modelo de linguagem,
memória, execução de tool). Ou seja, LangChain não é usado só como SDK
Python direto — também aparece embutido dentro de outra ferramenta de
workflow como motor de raciocínio.

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- `StateGraph` com reducers explícitos para merge de estado concorrente —
  um padrão de gerenciamento de estado mais rigoroso que "objeto mutável
  compartilhado".
- Interrupts nativos para human-in-the-loop dentro do próprio grafo de
  execução, não como uma camada externa.
- Checkpointing como mecanismo de pausa/retomada + execução exactly-once.
- Separação de papéis entre "framework de construção" (LangChain),
  "motor de orquestração com estado" (LangGraph) e "observabilidade"
  (LangSmith) como arquitetura de referência para dividir essas mesmas
  responsabilidades em qualquer outro sistema de agentes.
- OpenTelemetry como formato de exportação de trace agnóstico de
  framework, permitindo trocar a ferramenta de observabilidade sem trocar
  a instrumentação.

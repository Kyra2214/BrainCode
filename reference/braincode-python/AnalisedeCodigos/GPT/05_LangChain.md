# 05 — langchain-ai/langchain

## Pente fino adicional

O maior valor do LangChain não é uma API específica: é o conjunto de contratos entre modelo, ferramenta, retriever e workflow. Para o Braim, isso deve virar interfaces pequenas e próprias.

### Core mínimo recomendado

```text
ModelAdapter
ToolDefinition
ToolExecutor
Retriever
WorkflowState
Evaluator
```

Cada implementação pode ser substituída sem alterar o Brain.

### Tool como contrato executável

Além de nome/descrição/schema, registrar efeitos colaterais, permissões, timeout, retry e idempotência. Ferramentas sem efeito colateral podem ser reexecutadas com mais liberdade; ferramentas destrutivas exigem política diferente.

### Agent versus workflow

O pente fino reforça que passos determinísticos devem ser código. O LLM deve decidir quando necessário, mas não ser usado para “orquestrar” uma sequência que já possui regras objetivas.

### Estado

Fluxos complexos precisam de estado persistente e transições claras. Isso combina com o Brain como máquina de estados, inclusive nos loops de correção.

## Objetivo

Estudar abstrações de agentes, ferramentas, modelos, retrievers, integração e workflows sem transformar LangChain em dependência obrigatória do Braim.

## O que interessa

O LangChain atual é uma plataforma/framework de engenharia de agentes com abstrações modulares para modelos, ferramentas, integrações, retrievers e workflows. O `langchain-core` mantém interfaces independentes de provider.

## Abstração mais importante: interface antes do provider

O Core foi desenhado para permitir que diferentes providers implementem a mesma interface. Isso combina diretamente com o catálogo de APIs do Braim.

Em vez de:

```text
GeminiProvider usado diretamente por 40 classes
```

queremos:

```text
ModelGateway
  ├── GeminiAdapter
  ├── GroqAdapter
  ├── OpenRouterAdapter
  ├── MistralAdapter
  └── LocalLLMAdapter
```

O router decide qual adapter usar.

## Tools

LangChain trata ferramentas como componentes com contrato. O Braim deve ter um `ToolDefinition` contendo:

- nome;
- descrição;
- schema de entrada;
- schema de saída;
- permissões;
- custo estimado;
- timeout;
- efeitos colaterais;
- requisitos;
- score.

## Toolkits

A ideia de agrupar ferramentas por domínio é útil. Exemplos futuros:

- Android Toolkit;
- Git Toolkit;
- Research Toolkit;
- Image Toolkit;
- API Discovery Toolkit;
- Sandbox Toolkit.

## Model interoperability

A capacidade de trocar modelos sem reescrever o restante da aplicação é uma das ideias mais importantes para o Braim. Isso permite usar o modelo gratuito que estiver disponível naquele momento.

## Agents

O Braim deve estudar principalmente a separação entre agente e workflow. Nem todo problema precisa de um agente autônomo; muitos passos são determinísticos e devem ser código normal.

## LangGraph

Embora não seja o repositório principal analisado, o ecossistema aponta LangGraph como camada de orquestração controlável. A ideia útil é representar processos como estados e transições, inclusive loops de erro/correção.

## Deep Agents

A camada mais alta do ecossistema demonstra padrões como planning, subagents e filesystem. Para o Braim, isso reforça a necessidade de separar planner, executor e subagente.

## O que absorver

- interfaces de model/provider;
- ToolDefinition com schema;
- Toolkits por domínio;
- retriever como abstração de conhecimento;
- adapters por provider;
- separação agent/workflow;
- state machine para fluxos complexos;
- observabilidade/evaluation como conceito;
- composição modular;
- idempotência e efeitos colaterais como metadados de Tool.

## O que não absorver

- toda a dependência LangChain;
- cadeia de abstrações apenas por conveniência;
- LangSmith como requisito;
- vendor lock-in indireto.

O Braim deve ser menor e mais específico.

## Arquitetura inspirada

```text
ProviderGateway
ToolRegistry
SkillRegistry
AgentRegistry
MemoryProvider
WorkflowEngine
EvaluationEngine
```

Cada interface deve ser implementável sem LangChain.

## Licença

LangChain Core e o projeto LangChain usam MIT. A licença deve ser preservada se código for reutilizado literalmente.

## Prioridade

**ALTA**, principalmente como checklist de abstrações e interoperabilidade.

## Fontes

- https://github.com/langchain-ai/langchain
- https://github.com/langchain-ai/langchain/blob/master/README.md
- https://github.com/langchain-ai/langchain/blob/master/libs/core/README.md

## Conclusão

O aprendizado principal é: **interfaces estáveis permitem que o Brain troque modelos, ferramentas e fontes de conhecimento sem reescrever o orquestrador**. O pente fino acrescenta efeitos colaterais, idempotência e state machine como contratos explícitos.

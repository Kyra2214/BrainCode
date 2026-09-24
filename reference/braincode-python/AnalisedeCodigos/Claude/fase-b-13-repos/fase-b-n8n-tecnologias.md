# Fase B — n8n-io/n8n: tecnologias, ferramentas e técnicas

> Levantamento técnico da arquitetura de execução, nós de IA e escalonamento
> do n8n. Sem propor mudança em nada.

## 1. Arquitetura geral: editor visual → JSON → motor de execução

- **Editor visual** (frontend): monta o workflow arrastando nós,
  configurando parâmetros; converte tudo pra JSON e envia pro backend.
- **Motor de execução** (backend/worker): carrega a definição do
  workflow do banco de dados e executa nó por nó — a saída de um nó
  alimenta o próximo, com tratamento de erro e log em cada etapa pra
  rastreabilidade.
- **Nós**: nós de trigger (Webhook, Schedule) e nós regulares
  (processamento de dado, chamada de API, operação de banco), a maioria
  em JavaScript/TypeScript. Centenas de nós nativos + suporte a nós
  customizados via plugin.

## 2. Escalonamento horizontal: modo fila (queue mode)

Instância única tem limite de concorrência. **Worker mode**: o nó
principal cuida só de triggers/agendamento, as tarefas de execução vão
pra filas Redis, e múltiplos processos worker consomem essa fila em
paralelo — escalonamento horizontal real conforme o volume de tarefas
cresce. Existe também **multi-main architecture** (v2) para alta
disponibilidade do próprio nó principal, não só dos workers.

## 3. Nós de IA como sistema hierárquico sobre LangChain (JS)

A funcionalidade de agente de IA no n8n é construída sobre o framework
LangChain em JavaScript, com uma hierarquia de dois níveis:
- **Nós-raiz (cluster nodes)**: definem a lógica principal do agente.
- **Sub-nós**: fornecem capacidades específicas — integração com modelo
  de linguagem, gerenciamento de memória, execução de tool.

Quatro padrões arquiteturais suportados nativamente:
1. **Chained requests** — chamadas de modelo sequenciais com
   processamento intermediário, reduzindo custo de API em 30–50%
   comparado a uma única chamada de agente monolítica, via invocação
   seletiva de modelo.
2. **Single agent com estado** — mantém contexto via nós de memória,
   ideal para interface conversacional.
3. **Multi-agent com gatekeeper** — controle centralizado com delegação
   pra agentes especializados.
4. **Multi-agent teams** — agentes especializados paralelos que
   colaboram, com decisão distribuída.

## 4. Tipos de memória para agentes

- **Window Memory**: janela das últimas N mensagens (padrão N=10,
  configurável).
- **Buffer Memory**: conversa inteira em RAM durante a execução —
  simples e rápido, mas não persiste entre execuções.
- **Vector Store Memory**: turnos de conversa codificados como embeddings
  e armazenados em Pinecone/Qdrant/etc., permitindo recuperação semântica
  sobre milhares de turnos passados.

## 5. Reusabilidade via sub-workflow

Um workflow pode ser chamado como função por outro: cria-se um workflow
reutilizável com um nó `Execute Workflow Trigger`, e ele é invocado a
partir de outro workflow via nó `Execute Workflow`, passando dados de
entrada e recebendo dados de volta — modelo de chamada de função aplicado
a workflows inteiros.

## 6. Padrão "context_package" para agentes de produção

Um padrão documentado pra produção: empacotar identidade de sessão,
permissões de usuário, restrições de execução e schema de saída num único
objeto de contexto que viaja por todos os nós do workflow do agente —
cada execução carrega um `session_id` (rastreamento) e
`user_role`/`subscription_tier` (aplicação de permissão), em vez de cada
nó decidir isso de forma independente.

## 7. Risco documentado de segurança em templates de agente

Um audit de segurança encontrou um template de referência de "AI Agent"
vulnerável a prompt injection indireto: dado do usuário entra pelo nó de
Chat Trigger sem nenhum delimitador (tags XML, aspas triplas) separando
instrução de sistema de dado do usuário, e o agente tem acesso de alto
privilégio (execução de código JS via tool, requisições HTTP via tool)
sem nenhuma sanitização antes de chegar ao modelo. Vale registrar como
alerta concreto, não hipotético, sobre misturar dado não confiável com
tools de alto privilégio no mesmo contexto de agente.

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- Modo fila com Redis para escalonamento horizontal (nó principal só
  agenda, workers processam) — padrão de separação trigger/execução.
- Hierarquia nó-raiz + sub-nós para modelar agente + capacidades como
  composição, não como um bloco monolítico.
- Os quatro padrões de arquitetura de agente (chained, single-agent
  stateful, multi-agent gatekeeper, multi-agent teams) como vocabulário
  compartilhado pra descrever topologias de agente.
- Três tipos de memória (window/buffer/vector) com trade-offs explícitos
  de persistência vs. custo vs. capacidade de recall.
- Sub-workflow como "função" reutilizável, chamável de outro workflow.
- `context_package` como objeto único carregando identidade, permissão e
  schema de saída através de todo o fluxo do agente.
- Risco real e documentado de prompt injection quando dado não confiável
  e tools de alto privilégio (execução de código, rede) convivem sem
  delimitação/sanitização — lição de segurança aplicável a qualquer
  agente com tools equivalentes.

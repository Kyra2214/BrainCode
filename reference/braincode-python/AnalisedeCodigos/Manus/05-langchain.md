# Fase 2 do Brain — análise de `langchain-ai/langchain`

**Identificador:** 05-05  
**Repositório:** [`langchain-ai/langchain`][1]  
**Snapshot auditado:** commit `348c9dc572599947d2d7d33d6a5b8b936e92a1d4`  
**Data da inspeção local:** 2026-09-12  
**Papel no Brain:** framework Python para construir aplicações orientadas por modelos de linguagem, agentes, ferramentas, recuperação, streaming, integrações de provedores e observabilidade.  
**Conclusão executiva:** absorver as abstrações do `langchain-core` e a fábrica de agentes de `langchain_v1` como referências de interface, não como um runtime monolítico. O maior valor para o Brain está no protocolo `Runnable`, no contrato tipado de mensagens e ferramentas, no grafo de agente baseado em LangGraph, no middleware composável e na combinação opcional de checkpointer/store. A absorção deve ser seletiva, com isolamento explícito para ferramentas, política de aprovação humana, telemetria redigida e compatibilidade controlada com cada provedor.

> **Método.** O snapshot foi clonado do GitHub, fixado no commit acima e varrido com `git ls-files`. Foram contabilizados e classificados **3.125 arquivos versionados**; o código Python foi buscado por símbolos, hooks, estados, permissões, tracing, testes, dependências e marcadores de planejamento. A análise aprofundada cobriu README, instruções de agentes, manifestos, Makefiles, código do núcleo, `langchain_v1`, pacote legado, pacotes parceiros, exemplos, testes, OpenWiki e os 28 workflows. O inventário completo de caminhos está no apêndice deste relatório. “Implementado” significa que há código e/ou teste no snapshot. “Planejado/limite” significa que o próprio repositório declara não suportar, manter para compatibilidade, emitir depreciação ou depender de capacidade externa. A documentação `openwiki` é gerada: seu `.last-update.json` aponta para outro `gitHead` (`22f3421...`), portanto o código no commit auditado prevalece em caso de divergência.

## 1. Decisão para absorção

| Área | Veredito | Prioridade | Interface a absorver | Dependências e caveats |
|---|---|---:|---|---|
| Contratos de execução | **Absorver** | P0 | `Runnable[Input, Output]` com `invoke`, `ainvoke`, `stream`, `astream`, `batch` e composição | Implementar primeiro sem copiar integrações; requer propagação consistente de contexto, tags e metadados. |
| Mensagens e chamadas de ferramenta | **Absorver** | P0 | `BaseMessage`, `AIMessage`, `ToolMessage`, `BaseTool`, schemas tipados e `tool_call_id` | Validar conteúdo multimodal, serialização e limites de tamanho; não assumir que todo provedor segue o mesmo formato. |
| Fábrica do agente | **Absorver seletivamente** | P0 | `create_agent(model, tools, middleware, response_format, state_schema, context_schema, checkpointer, store, interrupt_*)` | A implementação depende de LangGraph e de reducers de estado; o Brain precisa de um adaptador para seu scheduler. |
| Middleware/hooks | **Absorver** | P0 | `before_agent`, `before_model`, `wrap_model_call`, `after_model`, `wrap_tool_call`, `after_agent` | Definir política de ordem, timeout, cancelamento e isolamento. O primeiro middleware é a camada externa. |
| Aprovação humana | **Absorver com endurecimento** | P0 | `HumanInTheLoopMiddleware`, `interrupt`, `Command` e decisões approve/edit/reject/respond | É opt-in; sem middleware ou interrupção configurada a ferramenta pode executar automaticamente. |
| Memória | **Absorver como SPI** | P1 | `checkpointer` para uma thread e `store` para dados entre threads | Não é memória autônoma: persistência depende de implementações externas de saver/store e de configuração de thread. |
| Observabilidade | **Absorver interface, não fornecedor** | P1 | callback manager, tracers, `run_id`, parent run, tags, metadata, eventos/streaming | LangSmith é integração externa; redigir prompts, respostas, argumentos e segredos antes do envio. |
| MCP | **Absorver como conector isolado** | P1 | `MCPAdapter` para stdio, HTTP, in-process, múltiplos servidores e auth | Superfície de execução remota; aplicar allowlist de servidores, timeouts, autenticação por tenant e aprovação para ações destrutivas. |
| Integrações de provedores | **Não absorver em bloco** | P2 | Pacotes parceiros independentes (`langchain-openai`, `anthropic`, etc.) | Manter adaptadores próprios do Brain; cada pacote traz SDK, versão, política de dados e limites diferentes. |
| `langchain-classic` | **Não absorver** | P3 | Apenas compatibilidade/migração | O próprio repositório o marca como legado; usar somente para migração e testes de regressão. |
| Deep Agents | **Não presente no snapshot** | P3 | Referenciado no README como pacote de nível superior | É produto/pacote externo, não capacidade implementada neste repositório; não inferir suporte nativo. |

## 2. Arquitetura e fluxo operacional

### 2.1 Camadas

A arquitetura declarada separa três camadas. `langchain-core` fornece contratos estáveis e agnósticos de fornecedor para modelos, mensagens, ferramentas, prompts, parsers, retrievers, `Runnable`, callbacks e tracing. `langchain_v1` fornece orquestração de agentes, `create_agent`, middleware e inicialização dinâmica de modelos. Os pacotes em `libs/partners/<provider>` implementam os contratos do core para um provedor específico e são versionados separadamente. O pacote instalado a partir de `libs/langchain` chama-se `langchain-classic` na versão `1.0.8` e serve à compatibilidade legada, não à nova superfície de agentes. Essa separação é descrita em [`openwiki/architecture.md`][2] e refletida nos manifestos [`libs/core/pyproject.toml`][6], [`libs/langchain_v1/pyproject.toml`][7] e [`libs/langchain/pyproject.toml`][8].

O grafo de dependência efetivo é:

```text
Aplicação Brain
  ├── langchain_v1 (agentes e fábrica)
  │     ├── langchain-core (contratos)
  │     └── langgraph (StateGraph, Runtime, Command, interrupt, checkpointer)
  ├── langchain-core diretamente (Runnables, mensagens, tools, callbacks)
  └── pacote parceiro escolhido (OpenAI, Anthropic, Ollama, ...)
          └── SDK e política do provedor
```

No snapshot, `langchain` v1.4.0 depende de `langchain-core>=1.6.0,<2.0.0`, `langgraph>=1.2.11,<1.3.0` e Pydantic v2. O core é v1.6.2. O legado depende de core, text splitters, LangSmith, Pydantic, SQLAlchemy, Requests e PyYAML. Todos os pacotes declaram Python `>=3.10,<4.0`; o desenvolvimento usa `uv`, grupos de dependência, Ruff, mypy/ty, pytest e lockfiles separados.

### 2.2 Construção do agente

`create_agent` em [`libs/langchain_v1/langchain/agents/factory.py`][3] recebe um identificador de modelo ou `BaseChatModel`, ferramentas, prompt de sistema, middleware, formato estruturado, schemas de estado/contexto, checkpointer, store, interrupções, debug, nome e cache. A fábrica mescla o `AgentState` base com extensões de estado dos middlewares, cria nós do `StateGraph`, compõe os wrappers de modelo e ferramenta e retorna um grafo compilado.

O estado base é uma estrutura tipada com:

- `messages`: lista acumulada por reducer `add_messages`; contém mensagens humanas, do modelo e de ferramentas.
- `jump_to`: campo de controle efêmero que permite que hooks redirecionem para `tools`, `model` ou `end`.
- `structured_response`: saída validada quando `response_format` está configurado.

A sequência implementada é:

1. A aplicação invoca o grafo com mensagens e contexto.
2. O nó de entrada executa `before_agent` uma vez.
3. A entrada do loop executa `before_model` a cada iteração.
4. O modelo recebe um `ModelRequest` com mensagens, prompt, ferramentas, escolha de ferramenta, formato de resposta, estado e `Runtime`.
5. `wrap_model_call` intercepta o modelo; pode modificar a requisição, chamar o handler uma ou várias vezes, retornar cache ou produzir uma resposta/`Command`.
6. O resultado é um `ModelResponse` com mensagens e, opcionalmente, resposta estruturada.
7. `after_model` pode modificar estado, rejeitar/interceptar tool calls ou saltar para outro nó.
8. Se houver `AIMessage.tool_calls`, o nó de ferramentas executa cada chamada e produz `ToolMessage` com `tool_call_id`, status e conteúdo.
9. O estado acumula os resultados e volta a `before_model`/modelo.
10. Sem chamadas de ferramentas, ou com salto explícito para `end`, `after_agent` executa uma vez e o grafo devolve o estado.

A documentação confirma que o grafo é compilado com limite de recursão alto (`9.999`) e que checkpointer, `interrupt_before` e `interrupt_after` são opcionais. Isso permite loops longos, mas também aumenta o risco operacional de custo, latência e execução descontrolada; o Brain deve impor limites próprios mesmo quando middleware de limite não estiver presente.

### 2.3 Composição de `Runnable`

`libs/core/langchain_core/runnables/base.py` define o protocolo de composição. `RunnableSequence` executa etapas em série; `RunnableParallel` executa ramos concorrentes; há branching, fallback, retry, configuração, schemas inferidos, batch síncrono/assíncrono e streaming. O operador `|` viabiliza pipelines declarativos. Essa abstração é valiosa para o Brain porque fornece um contrato uniforme para modelo, parser, retriever, ferramenta ou etapa interna, sem obrigar o scheduler a conhecer cada classe concreta. O limite é que composição de funções não é, por si só, uma política de autorização, persistência ou isolamento.

### 2.4 Inicialização de modelos

`libs/langchain_v1/langchain/chat_models/base.py` implementa `init_chat_model`, que interpreta identificadores como `openai:gpt-5.5`, resolve o provedor, importa o pacote parceiro sob demanda e retorna um `BaseChatModel`. A resolução lazy evita dependências obrigatórias para todos os fornecedores. O registro não é exaustivo; para fornecedores não listados o usuário pode precisar instalar a integração e especificar `model_provider`. O Brain deve tratar o identificador como dado não confiável, manter uma registry própria de provedores permitidos e não permitir que uma string arbitrária cause importação ou acesso de rede sem política.

## 3. Inventário arquivo a arquivo e cobertura

### 3.1 Cobertura quantitativa do snapshot

| Grupo | Arquivos | Python | Testes | Exemplos | Papel |
|---|---:|---:|---:|---:|---|
| `libs/core` | 395 | 355 | 204 | 19 | Contratos e implementações agnósticas de fornecedor. |
| `libs/langchain_v1` | 188 | 159 | 122 | 17 | Agentes, middleware, MCP, chat-model factory e superfície v1. |
| `libs/langchain` | 1.655 | 1.581 | 315 | 51 | `langchain-classic`, integrações históricas e compatibilidade. |
| `libs/partners` | 679 | 411 | 388 | 0 | 15 pacotes de modelos, busca, embeddings e vector stores, além de README/Makefile do grupo. |
| `libs/model-profiles` | 16 | 9 | 6 | 0 | Perfis de capacidades e metadados de modelos. |
| `libs/standard-tests` | 43 | 36 | 14 | 0 | Contratos reutilizáveis de testes de integrações. |
| `libs/text-splitters` | 33 | 23 | 10 | 0 | Divisão de texto e utilitários de NLP. |
| `openwiki` | 50 | 0 | 0 | 0 | Documentação, manifestos e claims gerados. |
| `.github` e configuração raiz | 66 | 0 | 0 | 0 | CI/CD, automações, lint, devcontainer e políticas. |
| **Total versionado** | **3.125** | **2.579** | **1.088** | **87** | Varredura integral de caminhos e conteúdos textuais. |

A contagem de extensões inclui 2.579 `.py`, 55 Markdown, 73 JSON, 48 YAML/YML, 29 TOML, 21 lockfiles, 21 shell scripts, snapshots `.ambr`, fixtures de documentos e alguns artefatos binários. O volume não é apenas agente: a maior parte do repositório é `langchain-classic` e seus testes/integrações legadas.

### 3.2 Arquivos raiz e configuração

| Caminho | Estado | Achado e relevância |
|---|---|---|
| [`README.md`][1] | Implementado/documentado | Define LangChain como framework de agentes/aplicações LLM, mostra `uv add langchain`, `init_chat_model` e diferencia Deep Agents, LangGraph, integrações e LangSmith. Deep Agents e Deployment são referências do ecossistema, não código deste snapshot. |
| `AGENTS.md` e `CLAUDE.md` | Implementado | Instruções de desenvolvimento para agentes e colaboradores; afetam operação de contribuição, não são runtime do Brain. |
| [`LICENSE`][14] | Implementado | MIT no repositório raiz. Cada subpacote também traz `LICENSE`, majoritariamente MIT; dependências e SDKs mantêm licenças próprias. |
| `CITATION.cff` | Implementado | Metadados de citação; não altera execução. |
| `.mcp.json` | Implementado/configuração | Configuração de servidores MCP para desenvolvimento; não deve ser promovida automaticamente para produção. |
| `.pre-commit-config.yaml` | Implementado | Ruff/format/lint, checks de imports, versões, lockfiles e regras de pacotes. Inclui verificações de consistência entre `pyproject.toml` e arquivos de versão. |
| `.markdownlint.json`, `.editorconfig`, `.gitattributes`, `.gitignore`, `.dockerignore` | Implementado/configuração | Qualidade e empacotamento; não são controles de segurança de runtime. |
| `.devcontainer/*` | Implementado | Ambiente reproduzível de desenvolvimento via devcontainer/Compose. Não é sandbox de execução das ferramentas do agente. |
| `.vscode/*` | Implementado | Extensões e settings locais; não devem ser tratados como política de produção. |

### 3.3 `libs/core`: contratos absorvíveis

| Caminho/diretório | Estado | Função examinada |
|---|---|---|
| `langchain_core/runnables/base.py`, `branch.py`, `config.py`, `configurable.py`, `fallbacks.py`, `retry.py`, `router.py`, `schema.py`, `utils.py` | Implementado | Protocolo executável, composição, configuração, fallback, retry, batch, async, schemas e gráficos. É a principal referência de interface para o Brain. |
| `langchain_core/language_models/base.py`, `chat_models.py`, `fake_chat_models.py` | Implementado | Base de LLM/chat model, geração, streaming, binding de ferramentas, structured output e modelos fake para testes. |
| `langchain_core/messages/*` | Implementado | Tipos de mensagem, conteúdo multimodal, chunks, chamadas/resultados de ferramentas, serialização e compatibilidade legada. `FunctionMessage` e formatos antigos são mantidos para compatibilidade/depreciação. |
| `langchain_core/tools/base.py`, `convert.py`, `simple.py`, `structured.py`, `render.py`, `retriever.py` | Implementado | `BaseTool`, sync/async invoke, schema de argumentos via anotação/Pydantic/docstring, conversão de funções e retriever tools. Argumentos injetados como runtime não devem ser expostos ao modelo sem intenção. |
| `langchain_core/prompts/*` | Implementado | Prompt templates de string/chat, few-shot, multimodal, mensagens estruturadas e loading. |
| `langchain_core/output_parsers/*` | Implementado | Parsers string, JSON, list, XML, Pydantic, OpenAI tools/functions e transforms. |
| `langchain_core/retrievers.py`, `documents/*`, `document_loaders/*`, `embeddings/*`, `vectorstores/*`, `indexing/*`, `stores.py` | Implementado | Contratos de retrieval, documentos, embeddings, vector stores e stores. O core oferece abstrações; armazenamento seguro, ACL e retenção são responsabilidade da aplicação/adaptador. |
| `langchain_core/callbacks/*`, `tracers/*` | Implementado | Callback lifecycle para chain/LLM/chat/tool/retriever, `run_id`, parent IDs, tags, metadata, eventos, stdout, log stream, memory stream, collectors e compatibilidade LangSmith. |
| `langchain_core/load/*`, `utils/*`, `caches.py`, `rate_limiters.py`, `globals.py` | Implementado | Serialização, JSON/Pydantic, UUID, uso de tokens, cache, rate limiting e debug. Cache pode causar vazamento entre tenants se a chave não incluir identidade e política correta. |
| `tests/unit_tests/*` | Implementado | Testes isolados de cada contrato; snapshots e fakes. `conftest.py` bloqueia I/O síncrono indevido em async, desabilita sockets e permite dependências opcionais por marker. |
| `tests/integration_tests/*`, `tests/benchmarks/*`, `examples/*` | Implementado | Compile/integration e benchmarks; integração pode exigir credenciais e rede. Exemplos são material de uso, não controles de segurança. |
| `pyproject.toml`, `Makefile`, `uv.lock`, `README.md`, `LICENSE`, `scripts/*` | Implementado | Empacotamento, dependências, comandos `make test/lint/type/lock`, version check, import check e lock reproducível. |

### 3.4 `libs/langchain_v1`: agentes, modos e recursos

| Caminho/diretório | Estado | Função examinada |
|---|---|---|
| `langchain/agents/__init__.py` | Implementado | Exporta `create_agent` e `AgentState`, superfície mínima recomendada. |
| `langchain/agents/factory.py` | Implementado | Constrói o StateGraph, mescla schemas, registra nós/arestas, compõe middleware, structured output, checkpointer/store, interrupts, cache, debug e transformers. |
| `langchain/agents/middleware/types.py` | Implementado | Contratos genéricos `AgentMiddleware`, `AgentState`, `ModelRequest`, `ModelResponse`, `ToolCallRequest`, `TracePolicy`, decorators de hooks e wrappers. Suporta versões sync/async; chamar o modo errado gera `NotImplementedError`. |
| `middleware/__init__.py` | Implementado | Registro público das capacidades: HITL, limites, fallback/retry, PII, provider tool search, shell, sumarização, TODO, seleção/emulação, erros/retries de ferramentas, trace policy e file search. |
| `middleware/human_in_the_loop.py` | Implementado | Interrompe tool calls selecionadas e permite approve/edit/reject/respond conforme configuração. Sem sua configuração, não há aprovação automática por padrão. |
| `middleware/model_call_limit.py`, `tool_call_limit.py` | Implementado | Limites contra loops de modelos e ferramentas. Devem ser habilitados explicitamente pelo consumidor. |
| `middleware/model_retry.py`, `model_fallback.py` | Implementado | Retry com filtro de exceções, backoff/jitter e fallback de modelo. Retry pode duplicar operações se o provedor executar e o cliente perder a resposta; usar apenas para operações idempotentes ou com idempotency key. |
| `middleware/tool_retry.py`, `tool_error.py` | Implementado | Retry de ferramentas e conversão de exceções em `ToolMessage(status="error")`, permitindo recuperação pelo modelo. Não transforma erro de transporte em erro recuperável automaticamente. |
| `middleware/summarization.py`, `context_editing.py` | Implementado | Sumariza ou remove uso de ferramentas para controlar contexto. Pode alterar o histórico efetivamente usado pelo modelo; preservar trilha auditável separada. |
| `middleware/todo.py` | Implementado | Mantém lista de tarefas no estado e fornece ferramenta auxiliar. A documentação chama a lista de persistente no contexto da conversa, mas a persistência durável ainda depende do checkpointer. |
| `middleware/pii.py` | Implementado | Detecta PII e pode bloquear/transformar conforme configuração. Não substitui classificação regulatória, DLP ou política de retenção do Brain. |
| `middleware/shell_tool.py`, `_execution.py` | Implementado | Shell tool com políticas Host, Docker e Codex sandbox, regras de redação e execução controlada. Host é uma opção de maior risco; exige allowlist, filesystem/CPU/memória/tempo e usuário sem privilégios. |
| `middleware/file_search.py`, `filesystem.py` | Implementado | Pesquisa/uso de arquivos via ferramenta/middleware. Deve ser limitado por raiz, tenant e capacidades de leitura/escrita. |
| `middleware/elicitation.py`, `provider_tool_search.py`, `tool_selection.py`, `tool_emulator.py` | Implementado | Entrada humana durante tool call, descoberta/seleção de ferramentas e emulação por modelo. Seleção reduz contexto, mas delegar seleção ao LLM não é autorização. |
| `chat_models/base.py` | Implementado | `init_chat_model`, registry/provider inference e carregamento lazy. Registry não exaustiva é limitação documentada. |
| `mcp/*` | Implementado | `MCPAdapter`, conversão MCP→`StructuredTool`, transportes, prefixos, erros, conteúdo e interrupções/elicitation. |
| `messages/*`, `tools/*`, `embeddings/*`, `rate_limiters/*` | Implementado | Reexports e conveniências da superfície v1. |
| `tests/unit_tests/*`, `tests/integration_tests/*`, `tests/benchmarks/*`, `examples/*` | Implementado | Testes da fábrica, middleware, MCP, mensagens, ferramentas, exemplos de agentes e compile tests. Integrações exigem dependências/credenciais. |
| `pyproject.toml`, `Makefile`, `uv.lock`, READMEs e scripts | Implementado | Pacote v1.4.0, dependências de LangGraph/core/Pydantic/FastMCP, grupos de teste/lint/type e gates de versão/import. |

### 3.5 `libs/langchain`: legado

`libs/langchain` contém 1.655 arquivos, 1.581 deles Python, cobrindo chains antigas, agentes clássicos, LLMs, embeddings, retrievers, document loaders, vector stores, graphs, evaluation, memory, hub, callbacks e reexports comunitários. A estrutura é ampla e inclui 315 arquivos de testes e 51 exemplos. O manifesto identifica o pacote como `langchain-classic` v1.0.8. O arquivo é útil para entender migrações e compatibilidade, mas não deve entrar na superfície nova do Brain. Absorver somente contratos de migração necessários e manter uma camada de compatibilidade temporária, com telemetria de uso para remoção progressiva.

### 3.6 Pacotes parceiros

Todos os parceiros seguem, com variações, o mesmo padrão de `README.md`, `LICENSE`, `Makefile`, `pyproject.toml`, `uv.lock`, `langchain_<provider>`, `scripts/check_imports.py`, `scripts/check_version.py`, testes unitários e integração. Cada um implementa chat model, embeddings, retriever, search ou vector store sobre `langchain-core`. O snapshot contém **15 pacotes parceiros**. A tabela mostra o inventário auditado:

| Pacote | Versão | Arquivos | Python | Testes | Observação |
|---|---:|---:|---:|---:|---|
| `langchain-anthropic` | 1.x no snapshot | 74 | 41 | 47 | Chat model/streaming/tool use Anthropic. |
| `langchain-chroma` | 1.x no snapshot | 22 | 14 | 9 | Vector store Chroma. |
| `langchain-deepseek` | 1.x no snapshot | 23 | 14 | 7 | Chat model DeepSeek. |
| `langchain-exa` | 1.x no snapshot | 25 | 17 | 10 | Busca Exa. |
| `langchain-fireworks` | 1.6.1 | 39 | 29 | 18 | Chat model Fireworks. |
| `langchain-groq` | 1.1.3 | 33 | 22 | 15 | Chat model Groq. |
| `langchain-huggingface` | 1.2.2 | 36 | 27 | 12 | Hugging Face e compatibilidades. |
| `langchain-mistralai` | 1.1.6 | 30 | 21 | 13 | Chat/embeddings Mistral. |
| `langchain-nomic` | 1.0.1 | 21 | 13 | 8 | Embeddings Nomic. |
| `langchain-ollama` | 1.1.0 | 33 | 24 | 16 | Modelos locais Ollama. |
| `langchain-openai` | 1.6.2 | 200 | 84 | 163 | Pacote mais testado; OpenAI chat/LLM/embeddings. |
| `langchain-openrouter` | 0.2.8 | 28 | 18 | 11 | Gateway de modelos. |
| `langchain-perplexity` | 1.4.1 | 41 | 32 | 19 | Chat/embeddings/search Perplexity. |
| `langchain-qdrant` | 1.1.0 | 46 | 38 | 29 | Vector store Qdrant, async e sparse embeddings. |
| `langchain-xai` | 1.3.0 | 26 | 17 | 11 | Integração xAI sobre OpenAI/core. |

As versões de alguns primeiros parceiros variam no metadata do snapshot, por isso o relatório não inventa números que não foram necessários para a decisão; o manifesto de cada parceiro é a fonte normativa. O Brain deve absorver apenas uma SPI de `BaseChatModel`/`Embeddings`/`VectorStore` e adaptar providers individualmente. Não copiar os SDKs ou os segredos para o núcleo.

### 3.7 `model-profiles`, `standard-tests` e `text-splitters`

`libs/model-profiles` é um pacote beta com perfis TOML/JSON de capacidades, usado para informar recursos de modelos. É útil como catálogo declarativo, mas deve ser tratado como informação potencialmente desatualizada, não como prova de capacidade; capability probing e testes de contrato são necessários.

`libs/standard-tests` v1.1.9 define classes de contrato para chat models, embeddings, ferramentas, vector stores, retrievers, indexers e sandboxes. Essa ideia deve ser absorvida: todo adaptador do Brain deve passar por uma suíte comum de inicialização, schema, invoke, async, batch, streaming, erros e segredos.

`libs/text-splitters` v1.1.2 fornece character, HTML, JSON, JSX, Markdown, Python, LaTeX, NLP, spaCy, NLTK e sentence-transformers splitters. É utilitário independente; se absorvido, deve ter limites de tamanho e testes de segurança para HTML/XML, pois um splitter não é sanitizador.

### 3.8 Documentação e OpenWiki

Os 23 claims JSON em `openwiki/.claims/` guardam evidências `repo://` e hashes de linhas para páginas como arquitetura, agentes, callbacks, middleware, MCP, mensagens, prompts, runnables, streaming, structured output, tools, testes e CI. `openwiki/.page-manifest.json` lista as páginas e `openwiki/source-map.md` aponta para fontes de implementação. Isso fornece navegação útil e rastreabilidade, mas a documentação é gerada e pode ficar atrás do código; em absorção, o contrato deve ser derivado de testes e tipos, com docs como segundo nível de evidência.

| Documento | Implementado/documentado | Decisão de leitura |
|---|---|---|
| `architecture.md` | Implementado | Camadas core/v1/partners/classic e dependências. |
| `agent-factory.md`, `agent-execution.md` | Implementado | Grafo, estado, loop, hooks, composição e limites. |
| `middleware.md` | Implementado | Interceptores, retries, erros, HITL e ordem. |
| `tools.md`, `messages.md`, `prompts.md`, `structured-output.md` | Implementado | Contratos de entrada/saída e schemas. |
| `runnables.md`, `streaming.md`, `callbacks.md` | Implementado | Composição, eventos, callbacks e streaming. |
| `model-initialization.md`, `chat-models.md`, `openai-provider.md`, `partner-pattern.md` | Implementado | Factory e padrão de integração. |
| `mcp-integration.md` | Implementado com limites | Transportes, auth, prefixos e elicitation; áudio ainda não suportado. |
| `unit-tests.md`, `integration-tests.md`, `ci-workflows.md`, `dev-commands.md` | Implementado | QA, rede, credenciais, CI e comandos. |
| `quickstart.md`, `index.md` | Implementado | Navegação e exemplos de entrada. |
| `.claims/*`, `.page-manifest.json`, `.last-update.json` | Metadados gerados | Verificar contra o commit atual; claims podem apontar para outro head. |

## 4. Skills, ferramentas, modos e permissões

### 4.1 Skills/capacidades nativas

O repositório não implementa um registro genérico chamado “Skill”. A unidade extensível equivalente é o middleware, uma `BaseTool`, um `Runnable`, um pacote parceiro ou um servidor MCP. Os recursos de alto nível são:

- **Modelos e embeddings:** `BaseChatModel`, `init_chat_model`, `Embeddings`, perfis de capacidade e adapters de provedores.
- **Ferramentas:** functions→`StructuredTool`, schemas Pydantic, `BaseTool`, retriever tool, ferramentas providas por middleware e MCP.
- **Agente:** loop de modelo/ferramenta em StateGraph, structured output, tool choice e resposta tipada.
- **Orquestração:** Runnables sequenciais/paralelos, fallback, retry, batch e streaming.
- **Contexto e memória:** estado tipado, `context_schema`, `checkpointer` por thread, `store` entre threads e cache.
- **Controles:** limites de chamadas, retry/error handling, sumarização, edição de contexto, seleção de tools, PII, shell policies e HITL.
- **Integração externa:** MCP com in-process, subprocesso stdio, HTTP streamable, múltiplos servidores, bearer, OAuth 2.1 e auth customizada.
- **Observabilidade:** callbacks, tracers, run logs, eventos, metadata/tags, debug e LangSmith.

### 4.2 Modos de execução

| Modo | Implementação | Caveat para o Brain |
|---|---|---|
| Síncrono | `invoke`, `stream`, sync hooks e tools | Pode bloquear; o middleware que só implementa async falha em uso sync. |
| Assíncrono | `ainvoke`, `astream`, async hooks/tools | Preferível para API e ferramentas I/O; bloquear dentro de async é detectado por testes, não necessariamente em produção. |
| Batch/paralelo | `batch`, `abatch`, `RunnableParallel`, múltiplos tool calls | Exige limites de concorrência, quotas por tenant e idempotência. |
| Streaming | chunks de modelo, parsers transformadores, `astream_events`/callbacks | Não expor chunks não redigidos; guardar o agregado e a trilha de auditoria separadamente. |
| Debug | `debug=True`, callbacks de console/log | Pode imprimir estado e payloads sensíveis; desabilitar em produção ou redigir. |
| Structured output | `ToolStrategy`, `ProviderStrategy`, Pydantic/raw schema | Validação de schema não garante semântica, autorização ou veracidade. |
| Agent loop | StateGraph repetindo modelo→tools→modelo | Limitar modelo, ferramentas, tempo, custo e profundidade independentemente do limite 9.999. |
| MCP in-process | `FastMCP` no mesmo processo | Bom para testes; compartilha confiança e memória do processo. |
| MCP stdio | subprocesso Python/Node | Isolar UID, ambiente, diretório, rede e recursos; não passar todos os secrets. |
| MCP HTTP | streamable HTTP | TLS, auth, egress allowlist, timeout, replay e política de dados são do Brain. |
| HITL/interrupt | pausa, resposta humana e resume via checkpointer | Requer UX, timeout, expiração e autenticação do aprovador; sem isso pode travar ou autorizar pelo contexto errado. |

### 4.3 Permissões e autorização

O framework oferece mecanismos, mas não uma matriz de permissões central para recursos, tenants ou usuários. `HumanInTheLoopMiddleware` pode selecionar ferramentas que requerem aprovação e restringir tipos de decisão; `ShellToolMiddleware` oferece políticas de execução; MCP expõe anotações como `destructiveHint`; PII pode bloquear ou transformar dados; `interrupt_before/after` pode pausar nós. Porém:

1. Uma ferramenta passada a `create_agent` fica disponível ao modelo; seleção de ferramenta é controle de contexto, não autorização.
2. HITL é opt-in e depende de uma implementação de interrupção/checkpointer; não deve ser presumido.
3. `BaseTool` não conhece automaticamente identidade do usuário, escopo de tenant, ABAC/RBAC, orçamento ou consentimento.
4. MCP pode carregar ferramentas remotas com descrição e schema fornecidos pelo servidor; prompt injection e tool poisoning continuam possíveis.
5. Shell Host é significativamente mais perigoso que Docker/Codex sandbox; nenhum deles substitui um sandbox de produção dedicado.
6. Callbacks/tracing podem receber conteúdo integral de mensagens, argumentos e resultados; o Brain deve aplicar redaction antes da exportação.

**Contrato recomendado para o Brain:** toda ferramenta deve declarar `capability`, `risk_class`, `required_scopes`, `side_effects`, `idempotent`, `data_classification`, `approval_policy`, `timeout` e `resource_budget`. O executor deve verificar essa declaração contra o principal da chamada antes de chegar ao `BaseTool`.

## 5. Memória, estado e workflows

### 5.1 Estado e memória

`AgentState` acumula mensagens durante uma execução. `state_schema` permite campos adicionais; `context_schema` injeta contexto de execução sem confundi-lo com o histórico; `checkpointer` persiste snapshots para a mesma thread/conversa; `store` persiste dados entre threads/usuários; cache pode reusar resultados. O framework não fornece automaticamente uma memória semântica segura, uma política de retenção, criptografia, isolamento de tenant ou deleção por titular. A absorção deve separar:

- **estado efêmero:** mensagens intermediárias, tool calls pendentes e `jump_to`;
- **checkpoint de conversa:** snapshots versionados e retomáveis, com TTL e criptografia;
- **memória de longo prazo:** store com namespace por tenant/principal e ACL;
- **telemetria:** traces redigidos, com retenção e acesso distintos do estado.

O campo `jump_to` é efêmero e não deve ser usado como memória de controle durável. `structured_response` deve ser validado antes de ser persistido. Sumarização e context editing reduzem o prompt, mas não equivalem a deleção regulatória do histórico.

### 5.2 Workflows implementados

O workflow principal é o loop de agente compilado em LangGraph. Middleware pode adicionar ferramentas e campos, interceptar modelo/ferramenta e devolver `Command` para atualizar estado ou redirecionar. MCP adiciona um workflow de descoberta→conversão→invocação→conversão de conteúdo/artefato; no protocolo moderno, `InputRequiredResult` vira `interrupt`, recebe respostas e repete a chamada até o terminal.

Os workflows de engenharia incluem lock e check de versões, import checks, lint, testes unitários, integração com VCR, perfis de modelos, releases e atualização do OpenWiki. Os workflows GitHub não são workflows de agentes do Brain; são automações do repositório e foram catalogados na seção 7.

### 5.3 Planejado, não implementado ou externo

- O README sugere Deep Agents com planejamento, subagentes e filesystem, mas não há esse runtime como pacote neste repositório; é uma capacidade externa do ecossistema.
- `langchain-classic` é mantido por compatibilidade e contém APIs depreciadas; não é roadmap de nova arquitetura.
- A registry de `init_chat_model` não é exaustiva; inferência automática de provedor não funciona para todo pacote.
- O documento MCP declara **áudio ainda não suportado**.
- Servidores MCP de eras de protocolo diferentes podem exigir adapters separados; a compatibilidade legada não fornece o loop moderno de elicitation.
- `FunctionMessage`, formatos antigos de content blocks e métodos de mensagem depreciados existem para migração, mas devem ser tratados como dívida de compatibilidade.
- Não foi encontrado um roadmap de produto vinculante no snapshot. TODOs e `xfail` são itens de manutenção/teste, não promessas de entrega.

## 6. Observabilidade e auditoria

O callback manager do core cria IDs de execução e parent IDs para LLM, chat model, chain, tool e retriever, propaga tags/metadata e dispara eventos de início, token, fim e erro. Tracers de stdout, run collector, log stream, event stream e integração LangSmith oferecem níveis diferentes de instrumentação. Streaming e callbacks permitem UI progressiva e diagnóstico de etapas intermediárias.

Pontos fortes para o Brain:

- árvore de runs com causalidade pai/filho;
- filtros por nome, tipo e tags;
- metadata de sessão/tenant/modelo;
- eventos customizados e traces de tool/model/retriever;
- `debug` para desenvolvimento;
- testes de callbacks, streaming e snapshots.

Riscos e recomendações:

- **Exfiltração:** prompts, PII, tokens em argumentos e respostas podem entrar em traces. Implementar `TracePolicy` obrigatória com allowlist de campos e redaction antes de qualquer callback externo.
- **Cardinalidade/custo:** tags e metadata livres podem criar alta cardinalidade. Padronizar chaves e limitar tamanho.
- **Confiança no trace:** logs do modelo podem conter instruções maliciosas. Tratar trace como dado, não como comando.
- **Replay:** uma operação de ferramenta repetida a partir de trace pode ter efeito colateral. Separar modo replay sem execução.
- **Correlação:** `run_id` deve ser ligado ao request ID do Brain e ao principal, mas principal/PII não devem aparecer em claro onde não forem necessários.

## 7. Testes, workflows, hooks e cadeia de suprimentos

### 7.1 Testes

A estratégia está organizada em `tests/unit_tests`, `tests/integration_tests` e `tests/benchmarks` em cada pacote. Unitários desabilitam sockets, permitem somente Unix sockets e usam `pytest-xdist -n auto`; extended tests são marcados por dependências opcionais. `blockbuster` impede blocking I/O em async. UUIDs determinísticos estabilizam snapshots. Fakes como `FakeListChatModel`, `GenericFakeChatModel` e `ParrotFakeChatModel` evitam chamadas reais. `langchain-tests` fornece contratos comuns para integrações.

Isso é forte, mas o alcance é desigual: o pacote OpenAI tem 163 testes no snapshot, enquanto alguns parceiros têm poucos; integração depende de secrets e APIs externas. O Brain deve acrescentar testes de autorização, tenant isolation, redaction, replay seguro, timeout, cancellation, rate limit, tool poisoning, prompt injection, SSRF em MCP/loader, e propriedades de idempotência.

### 7.2 Workflows GitHub

Os 28 arquivos em `.github/workflows/` foram inventariados. Os principais são:

| Workflow | Estado | Função |
|---|---|---|
| `_test.yml`, `_compile_integration_test.yml`, `integration_tests.yml`, `_test_vcr.yml`, `_test_pydantic.yml` | Implementado | Matriz de testes, compile/integration, VCR e compatibilidade Pydantic. |
| `_lint.yml`, `check_diffs.yml`, `check_extras_sync.yml`, `check_release_deps.yml`, `check_versions.yml` | Implementado | Lint, diferenças, extras, dependências de release e versões. |
| `_release.yml` | Implementado | Empacotamento/release. Validar manualmente environments e publicação antes de reutilizar. |
| `codspeed.yml` | Implementado | Benchmark/performance. |
| `openwiki-update.yml` | Implementado | Atualização agendada/manual do OpenWiki; tem `contents: write` e `pull-requests: write`. |
| `refresh_model_profiles.yml`, `_refresh_model_profiles.yml` | Implementado | Atualização agendada/manual de perfis; grava PR/conteúdo. |
| `auto-label-by-package.yml`, `pr_labeler.yml`, `pr_labeler_backfill.yml`, `pr_lint*.yml` | Implementado | Automação de labels, trailers e títulos. Alguns eventos `pull_request_target` usam contexto privilegiado: revisar rigorosamente código não confiável. |
| `tag-external-issues.yml`, `close_unchecked_issues.yml`, `remove_waiting_on_author.yml`, `reopen_on_assignment.yml`, `require_issue_link.yml` | Implementado | Automação de issues/PRs com permissões específicas. |
| `dependabot.yml` e scripts GitHub | Implementado | Atualização e política de dependências/labels. |

Ponto de atenção explícito em `integration_tests.yml`: os jobs de matriz podem receber muitos secrets de provedores; o próprio workflow alerta que qualquer teste no job poderia tecnicamente acessar qualquer chave e que isso só é aceitável para código confiável. Não transportar esse padrão para pipelines do Brain. Usar jobs por provedor, environments, OIDC quando possível, secrets mínimos e runners isolados.

As actions estão pinadas a SHAs em vários workflows, o que reduz supply-chain drift. Ainda assim, devem existir verificação de provenance, Dependabot, SBOM, `pip-audit`/OSV, revisão de lockfiles, pinagem de imagens Docker e política para scripts de parceiros.

### 7.3 Hooks de desenvolvimento

`.pre-commit-config.yaml` habilita Ruff/format, lint de imports, checks de versões e comandos por pacote. Makefiles locais expõem `format`, `lint`, `type`, `test`, `extended_tests`, `lock`, `check-lock`, `check_version` e `check_imports`; `libs/Makefile` coordena tarefas. Essas automações são implementadas e absorvíveis como modelo de governança, mas não substituem controles de produção.

## 8. Pontos fortes

1. **Contratos claros e composáveis.** `Runnable`, mensagens, tools e chat models oferecem uma fronteira estável para trocar provedores e combinar etapas.
2. **Loop de agente explícito.** A separação modelo→tools→modelo em StateGraph facilita estados, interrupções, retries, observação e composição em subgrafos.
3. **Middleware extensível.** Hooks sync/async, wrappers, state schema, tools adicionais e `Command` permitem cross-cutting concerns sem editar a fábrica.
4. **Integração ampla.** Parceiros independentes cobrem modelos, embeddings, busca e vector stores sem obrigar todas as dependências no core.
5. **HITL e limites existentes.** Aprovação, limites de chamadas, fallback, retry, tool errors, PII e shell policies já são padrões implementados.
6. **Observabilidade de primeira classe.** IDs hierárquicos, eventos, callbacks, streaming e LangSmith reduzem tempo de diagnóstico.
7. **Testabilidade.** Fakes, snapshots determinísticos, bloqueio de socket, blockbuster e standard tests reduzem testes frágeis e chamadas acidentais.
8. **Disciplina de monorepo.** Lockfiles, checks de versões/imports, pacotes independentes e actions pinadas são boas bases para governança.
9. **MCP relativamente completo.** Descoberta, prefixos, transportes, auth, conteúdo multimodal parcial, erros de ferramenta e elicitation estão integrados à abstração de tools.

## 9. Limites, riscos e mitigação

| Risco | Evidência no snapshot | Impacto | Mitigação de absorção |
|---|---|---|---|
| Execução arbitrária de tools/shell | `BaseTool`, shell Host/Docker, filesystem e MCP | RCE, exfiltração, alteração de dados | Executor sandboxed, scopes, allowlist, budget, aprovação e network egress deny-by-default. |
| Prompt injection/tool poisoning | Descrições de tools, MCP remoto e seleção por LLM | Ação não autorizada | Tratar descrição/resultado como dados não confiáveis; política fora do prompt; HITL para risco alto. |
| Retry duplicando efeito colateral | model/tool retry | Cobrança, duplicação, mutação repetida | Idempotency keys, classificação side-effect, retry apenas em transporte/erros seguros. |
| Checkpointer/store mal isolado | APIs opcionais por thread e entre threads | Vazamento entre usuários/tenants | Namespace por principal, ACL, criptografia, TTL, deleção e testes de isolamento. |
| Tracing com dados sensíveis | callbacks, metadata, LangSmith e debug | PII/segredos no SaaS/logs | TracePolicy, redaction, retenção, opt-in para payload, acesso mínimo. |
| Loops/custo | recursion 9.999; tools podem chamar tools/modelos | Denial of wallet e latência | Limites independentes de chamadas, tokens, tempo, profundidade, concorrência e custo. |
| MCP remoto/SSRF | HTTP streamable, OAuth, custom auth | acesso a rede/credenciais | URL allowlist, resolução DNS segura, TLS, timeout, auth por tenant, sem URLs arbitrárias. |
| Compatibilidade de provedores | pacotes independentes, registry não exaustiva | quebra, divergência semântica | adapters versionados, capability probing, contract tests e fallback explícito. |
| Dependências extensas | 14 parceiros, SDKs e artefatos | superfície CVE/supply chain | SBOM, lock review, pinagem, SCA, builds isolados e minimização de extras. |
| Licença e avisos | MIT no repo, licenças de pacotes/SDKs externos | obrigações e incompatibilidades transitivas | preservar MIT/copyright, gerar inventário SPDX, revisar cada dependência e dados de fixtures. |
| Docs desatualizadas | OpenWiki com head diferente | implementação absorvida incorreta | fixar commit, testar claims contra código e manter ADRs do Brain. |
| Conteúdo multimodal incompleto | MCP não suporta áudio | perda/erro em casos de uso | capability negotiation e fallback de conteúdo; não prometer áudio. |
| Pacote legado grande | `langchain-classic` 1.655 arquivos | acoplamento e APIs depreciadas | não importar; camada de compatibilidade temporária e migração observável. |

### 9.1 Licença

O `LICENSE` raiz é MIT, permissivo para uso, modificação, distribuição e sublicenciamento, desde que o aviso de copyright e permissão sejam mantidos. O risco principal não está no código MIT isolado, mas nas dependências transitivas, SDKs de provedores, imagens do devcontainer, dados/fixtures e termos de uso das APIs. MIT não concede direito de usar marcas, modelos, dados ou endpoints dos provedores. Antes de empacotar o Brain, gerar SBOM SPDX/CycloneDX para cada combinação de extras, preservar notices e revisar as licenças/termos de OpenAI, Anthropic, Google, Qdrant, Chroma, etc.

### 9.2 Segurança

O framework contém controles úteis, porém a segurança é composicional e opt-in. A principal falha de integração seria tratar a presença de `HumanInTheLoopMiddleware`, `PIIMiddleware` ou `DockerExecutionPolicy` como prova de segurança global. Eles devem estar no caminho obrigatório do executor do Brain, com defaults fail-closed. O Brain deve impedir que o modelo escolha sozinho escopos, credenciais, tenant ou política de aprovação; esses valores vêm do contexto autenticado e imutável.

## 10. Plano de absorção priorizado

### P0 — fundação segura

1. Implementar `BrainRunnable` compatível com `invoke/ainvoke/stream/astream/batch`, com `RunContext` contendo request ID, principal, tenant, deadline, cancellation, budget e trace policy.
2. Adotar tipos de mensagem e tool calls equivalentes, mas validar tamanho, MIME, schema, `tool_call_id`, status e serialização.
3. Criar `BrainTool` com contrato obrigatório de escopo, risco, efeitos colaterais, idempotência e orçamento.
4. Implementar o ciclo modelo→tool→modelo como workflow explícito, com limites rígidos de chamadas, tokens, tempo e concorrência.
5. Tornar aprovação humana obrigatória por classificação de risco, persistida por checkpoint e vinculada ao principal, tool call hash e TTL.
6. Aplicar redaction antes de callbacks e definir armazenamento separado para estado, memória e traces.

**Dependências:** Pydantic/typing, scheduler do Brain, store/checkpointer escolhido, executor de tools e sistema de identidade.  
**Interface de saída:** `AgentResult`, `ToolInvocation`, `ApprovalRequest`, `CheckpointRef`, `TraceEvent`.

### P1 — extensibilidade e operação

1. Portar middleware como adapters: retry/fallback, error normalization, summarization/context editing, PII, tool selection e streaming transformers.
2. Implementar stores/checkpointers com namespace, criptografia, optimistic concurrency, TTL e migração de schema.
3. Criar SPI de provider e capability registry próprio; usar pacotes parceiros apenas atrás de adapters testados.
4. Criar MCP gateway isolado com allowlist, auth por tenant, prefixo de servidor, timeout, egress policy, conversão de content blocks e HITL para elicitation.
5. Construir standard tests inspirados em `langchain-tests`: init, env secrets, schema, sync/async, batch, streaming, erros, retries e observabilidade.
6. Integrar tracing com allowlist de payload e métricas de custo/latência por modelo/tool/tenant.

**Dependências:** OTel/LangSmith equivalente, serviço de secrets, sandbox/container runtime, proxy e MCP client aprovado.  
**Interface de saída:** adapters versionados, capability matrix e contratos de teste por integração.

### P2 — migração e otimização

1. Oferecer compatibilidade somente para fluxos `langchain-classic` realmente usados; medir e remover imports legados.
2. Adotar perfis de modelo como dados auxiliares, com probing e testes de contrato.
3. Integrar splitters e retrievers selecionados, mantendo ACL e sanitização no Brain.
4. Adicionar cache por tenant/modelo/prompt hash, com proteção contra dados cruzados.
5. Automatizar SBOM, SCA, lock validation, provenance e revisão de permissões de CI.

## 11. Interfaces recomendadas

```python
class BrainRunnable(Protocol[InputT, OutputT]):
    def invoke(self, input: InputT, *, context: RunContext) -> OutputT: ...
    async def ainvoke(self, input: InputT, *, context: RunContext) -> OutputT: ...
    def stream(self, input: InputT, *, context: RunContext) -> Iterator[StreamEvent]: ...
    async def astream(self, input: InputT, *, context: RunContext) -> AsyncIterator[StreamEvent]: ...
    def batch(self, inputs: Sequence[InputT], *, context: RunContext) -> Sequence[OutputT]: ...

@dataclass(frozen=True)
class ToolPolicy:
    name: str
    required_scopes: frozenset[str]
    risk_class: Literal["read", "write", "destructive", "code"]
    side_effects: bool
    idempotent: bool
    approval: Literal["never", "conditional", "always"]
    timeout_s: float
    max_cost: Decimal
    allowed_tenants: frozenset[str] | None

class BrainMiddleware(Protocol):
    def before_agent(self, state, context): ...
    def before_model(self, request, context): ...
    def wrap_model_call(self, request, handler): ...
    def after_model(self, state, context): ...
    def wrap_tool_call(self, request, handler): ...
    def after_agent(self, state, context): ...
```

A ordem deve ser documentada e testada como composição externa→interna. Uma ordem inicial segura é: autenticação/contexto imutável → budget/deadline → redaction/trace policy → approval → validation → retry/fallback → provider/model → tool executor. O retry não deve envolver aprovação de forma que uma decisão humana seja repetida silenciosamente.

## 12. Checklist de aceitação da absorção

- [ ] O commit, versão e URLs de origem ficam registrados em ADR/SBOM.
- [ ] Nenhum pacote legado é importado pelo caminho novo.
- [ ] Cada tool exige escopo e política de risco.
- [ ] Testes verificam que usuário A não lê checkpoint/store de B.
- [ ] Traces são redigidos e payload capture é opt-in.
- [ ] Host shell e MCP HTTP falham fechados sem allowlist.
- [ ] Retry é proibido para side effects sem idempotency key.
- [ ] Limites de chamadas/tokens/tempo/concurrency são independentes do modelo.
- [ ] HITL expira, autentica aprovador e registra hash da decisão.
- [ ] Provider adapters passam standard tests sync/async/batch/stream/error.
- [ ] CI não entrega secrets de todos os provedores a todo job.
- [ ] SBOM e scanners de dependência passam antes do release.
- [ ] Docs do Brain citam código/testes, não somente OpenWiki gerado.

## Referências primárias

[1]: https://github.com/langchain-ai/langchain/tree/348c9dc572599947d2d7d33d6a5b8b936e92a1d4 "langchain-ai/langchain no commit auditado"
[2]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/architecture.md "Arquitetura do LangChain"
[3]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/libs/langchain_v1/langchain/agents/factory.py "Fábrica de agentes"
[4]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/agent-execution.md "Fluxo de execução de agentes"
[5]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/middleware.md "Middleware de agentes"
[6]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/libs/core/pyproject.toml "Manifesto langchain-core"
[7]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/libs/langchain_v1/pyproject.toml "Manifesto langchain v1"
[8]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/libs/langchain/pyproject.toml "Manifesto langchain-classic"
[9]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/libs/partners/openai/pyproject.toml "Manifesto langchain-openai"
[10]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/mcp-integration.md "Integração MCP"
[11]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/unit-tests.md "Estratégia de testes unitários"
[12]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/.pre-commit-config.yaml "Hooks pre-commit e checks"
[13]: https://github.com/langchain-ai/langchain/tree/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/.github/workflows "Workflows GitHub Actions"
[14]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/LICENSE "Licença MIT do repositório"
[15]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/source-map.md "Mapa de fontes do OpenWiki"
[16]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/model-initialization.md "Inicialização dinâmica de modelos"
[17]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/tools.md "Ferramentas"
[18]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/runnables.md "Runnables e composição"
[19]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/callbacks.md "Callbacks e tracing"
[20]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/messages.md "Mensagens e content blocks"
[21]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/structured-output.md "Structured output"
[22]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/streaming.md "Streaming"
[23]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/openwiki/ci-workflows.md "CI e workflows"
[24]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/libs/core/tests/unit_tests/conftest.py "Fixtures e hooks de testes do core"
[25]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/libs/langchain_v1/langchain/agents/middleware/shell_tool.py "ShellToolMiddleware e políticas de execução"
[26]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/libs/langchain_v1/langchain/agents/middleware/human_in_the_loop.py "HumanInTheLoopMiddleware"
[27]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/libs/langchain_v1/langchain/agents/middleware/pii.py "PIIMiddleware"
[28]: https://github.com/langchain-ai/langchain/blob/348c9dc572599947d2d7d33d6a5b8b936e92a1d4/.github/workflows/integration_tests.yml "Workflow de testes de integração e secrets"

## Apêndice A — inventário completo de caminhos

A varredura de `git ls-files` confirmou que os caminhos abaixo fazem parte do snapshot auditado. A listagem é mantida em ordem lexical e complementa as tabelas por pacote acima; arquivos binários e fixtures também foram contabilizados, mesmo quando não foram interpretados semanticamente.

A lista integral (3.125 caminhos), gerada diretamente por `git ls-files` no commit auditado, segue abaixo.

```text
     1	.devcontainer/README.md
     2	.devcontainer/devcontainer.json
     3	.devcontainer/docker-compose.yaml
     4	.dockerignore
     5	.editorconfig
     6	.gitattributes
     7	.github/CODEOWNERS
     8	.github/ISSUE_TEMPLATE/bug-report.yml
     9	.github/ISSUE_TEMPLATE/config.yml
    10	.github/ISSUE_TEMPLATE/feature-request.yml
    11	.github/ISSUE_TEMPLATE/privileged.yml
    12	.github/ISSUE_TEMPLATE/task.yml
    13	.github/PULL_REQUEST_TEMPLATE.md
    14	.github/actions/uv_setup/action.yml
    15	.github/dependabot.yml
    16	.github/images/logo-dark.svg
    17	.github/images/logo-light.svg
    18	.github/scripts/check_diff.py
    19	.github/scripts/check_extras_sync.py
    20	.github/scripts/check_prerelease_dependencies.py
    21	.github/scripts/get_min_versions.py
    22	.github/scripts/pr-labeler-config.json
    23	.github/scripts/pr-labeler.js
    24	.github/scripts/test_release_options.py
    25	.github/tools/git-restore-mtime
    26	.github/workflows/_compile_integration_test.yml
    27	.github/workflows/_lint.yml
    28	.github/workflows/_refresh_model_profiles.yml
    29	.github/workflows/_release.yml
    30	.github/workflows/_test.yml
    31	.github/workflows/_test_pydantic.yml
    32	.github/workflows/_test_vcr.yml
    33	.github/workflows/auto-label-by-package.yml
    34	.github/workflows/block_fork_main_prs.yml
    35	.github/workflows/bump_uv_pin.yml
    36	.github/workflows/check_agents_sync.yml
    37	.github/workflows/check_diffs.yml
    38	.github/workflows/check_extras_sync.yml
    39	.github/workflows/check_release_deps.yml
    40	.github/workflows/check_versions.yml
    41	.github/workflows/close_unchecked_issues.yml
    42	.github/workflows/codspeed.yml
    43	.github/workflows/integration_tests.yml
    44	.github/workflows/openwiki-update.yml
    45	.github/workflows/pr_labeler.yml
    46	.github/workflows/pr_labeler_backfill.yml
    47	.github/workflows/pr_lint.yml
    48	.github/workflows/pr_lint_trailer.yml
    49	.github/workflows/refresh_model_profiles.yml
    50	.github/workflows/remove_waiting_on_author.yml
    51	.github/workflows/reopen_on_assignment.yml
    52	.github/workflows/require_issue_link.yml
    53	.github/workflows/tag-external-issues.yml
    54	.gitignore
    55	.markdownlint.json
    56	.mcp.json
    57	.pre-commit-config.yaml
    58	.vscode/extensions.json
    59	.vscode/settings.json
    60	AGENTS.md
    61	CITATION.cff
    62	CLAUDE.md
    63	LICENSE
    64	README.md
    65	libs/Makefile
    66	libs/README.md
    67	libs/core/LICENSE
    68	libs/core/Makefile
    69	libs/core/README.md
    70	libs/core/extended_testing_deps.txt
    71	libs/core/langchain_core/__init__.py
    72	libs/core/langchain_core/_api/__init__.py
    73	libs/core/langchain_core/_api/beta_decorator.py
    74	libs/core/langchain_core/_api/deprecation.py
    75	libs/core/langchain_core/_api/internal.py
    76	libs/core/langchain_core/_api/path.py
    77	libs/core/langchain_core/_import_utils.py
    78	libs/core/langchain_core/_security/__init__.py
    79	libs/core/langchain_core/_security/_exceptions.py
    80	libs/core/langchain_core/_security/_policy.py
    81	libs/core/langchain_core/_security/_ssrf_protection.py
    82	libs/core/langchain_core/_security/_transport.py
    83	libs/core/langchain_core/agents.py
    84	libs/core/langchain_core/caches.py
    85	libs/core/langchain_core/callbacks/__init__.py
    86	libs/core/langchain_core/callbacks/base.py
    87	libs/core/langchain_core/callbacks/file.py
    88	libs/core/langchain_core/callbacks/manager.py
    89	libs/core/langchain_core/callbacks/stdout.py
    90	libs/core/langchain_core/callbacks/streaming_stdout.py
    91	libs/core/langchain_core/callbacks/usage.py
    92	libs/core/langchain_core/chat_history.py
    93	libs/core/langchain_core/chat_loaders.py
    94	libs/core/langchain_core/chat_sessions.py
    95	libs/core/langchain_core/cross_encoders.py
    96	libs/core/langchain_core/document_loaders/__init__.py
    97	libs/core/langchain_core/document_loaders/base.py
    98	libs/core/langchain_core/document_loaders/blob_loaders.py
    99	libs/core/langchain_core/document_loaders/langsmith.py
   100	libs/core/langchain_core/documents/__init__.py
   101	libs/core/langchain_core/documents/base.py
   102	libs/core/langchain_core/documents/compressor.py
   103	libs/core/langchain_core/documents/transformers.py
   104	libs/core/langchain_core/embeddings/__init__.py
   105	libs/core/langchain_core/embeddings/embeddings.py
   106	libs/core/langchain_core/embeddings/fake.py
   107	libs/core/langchain_core/env.py
   108	libs/core/langchain_core/example_selectors/__init__.py
   109	libs/core/langchain_core/example_selectors/base.py
   110	libs/core/langchain_core/example_selectors/length_based.py
   111	libs/core/langchain_core/example_selectors/semantic_similarity.py
   112	libs/core/langchain_core/exceptions.py
   113	libs/core/langchain_core/globals.py
   114	libs/core/langchain_core/indexing/__init__.py
   115	libs/core/langchain_core/indexing/api.py
   116	libs/core/langchain_core/indexing/base.py
   117	libs/core/langchain_core/indexing/in_memory.py
   118	libs/core/langchain_core/language_models/__init__.py
   119	libs/core/langchain_core/language_models/_compat_bridge.py
   120	libs/core/langchain_core/language_models/_utils.py
   121	libs/core/langchain_core/language_models/base.py
   122	libs/core/langchain_core/language_models/chat_model_stream.py
   123	libs/core/langchain_core/language_models/chat_models.py
   124	libs/core/langchain_core/language_models/fake.py
   125	libs/core/langchain_core/language_models/fake_chat_models.py
   126	libs/core/langchain_core/language_models/llms.py
   127	libs/core/langchain_core/language_models/model_profile.py
   128	libs/core/langchain_core/load/__init__.py
   129	libs/core/langchain_core/load/_validation.py
   130	libs/core/langchain_core/load/dump.py
   131	libs/core/langchain_core/load/load.py
   132	libs/core/langchain_core/load/mapping.py
   133	libs/core/langchain_core/load/serializable.py
   134	libs/core/langchain_core/messages/__init__.py
   135	libs/core/langchain_core/messages/ai.py
   136	libs/core/langchain_core/messages/base.py
   137	libs/core/langchain_core/messages/block_translators/__init__.py
   138	libs/core/langchain_core/messages/block_translators/anthropic.py
   139	libs/core/langchain_core/messages/block_translators/bedrock.py
   140	libs/core/langchain_core/messages/block_translators/bedrock_converse.py
   141	libs/core/langchain_core/messages/block_translators/google_genai.py
   142	libs/core/langchain_core/messages/block_translators/google_vertexai.py
   143	libs/core/langchain_core/messages/block_translators/groq.py
   144	libs/core/langchain_core/messages/block_translators/langchain_v0.py
   145	libs/core/langchain_core/messages/block_translators/openai.py
   146	libs/core/langchain_core/messages/chat.py
   147	libs/core/langchain_core/messages/content.py
   148	libs/core/langchain_core/messages/function.py
   149	libs/core/langchain_core/messages/human.py
   150	libs/core/langchain_core/messages/modifier.py
   151	libs/core/langchain_core/messages/system.py
   152	libs/core/langchain_core/messages/tool.py
   153	libs/core/langchain_core/messages/utils.py
   154	libs/core/langchain_core/output_parsers/__init__.py
   155	libs/core/langchain_core/output_parsers/base.py
   156	libs/core/langchain_core/output_parsers/format_instructions.py
   157	libs/core/langchain_core/output_parsers/json.py
   158	libs/core/langchain_core/output_parsers/list.py
   159	libs/core/langchain_core/output_parsers/openai_functions.py
   160	libs/core/langchain_core/output_parsers/openai_tools.py
   161	libs/core/langchain_core/output_parsers/pydantic.py
   162	libs/core/langchain_core/output_parsers/string.py
   163	libs/core/langchain_core/output_parsers/transform.py
   164	libs/core/langchain_core/output_parsers/xml.py
   165	libs/core/langchain_core/outputs/__init__.py
   166	libs/core/langchain_core/outputs/chat_generation.py
   167	libs/core/langchain_core/outputs/chat_result.py
   168	libs/core/langchain_core/outputs/generation.py
   169	libs/core/langchain_core/outputs/llm_result.py
   170	libs/core/langchain_core/outputs/run_info.py
   171	libs/core/langchain_core/prompt_values.py
   172	libs/core/langchain_core/prompts/__init__.py
   173	libs/core/langchain_core/prompts/base.py
   174	libs/core/langchain_core/prompts/chat.py
   175	libs/core/langchain_core/prompts/dict.py
   176	libs/core/langchain_core/prompts/few_shot.py
   177	libs/core/langchain_core/prompts/few_shot_with_templates.py
   178	libs/core/langchain_core/prompts/image.py
   179	libs/core/langchain_core/prompts/loading.py
   180	libs/core/langchain_core/prompts/message.py
   181	libs/core/langchain_core/prompts/prompt.py
   182	libs/core/langchain_core/prompts/string.py
   183	libs/core/langchain_core/prompts/structured.py
   184	libs/core/langchain_core/py.typed
   185	libs/core/langchain_core/rate_limiters.py
   186	libs/core/langchain_core/retrievers.py
   187	libs/core/langchain_core/runnables/__init__.py
   188	libs/core/langchain_core/runnables/base.py
   189	libs/core/langchain_core/runnables/branch.py
   190	libs/core/langchain_core/runnables/config.py
   191	libs/core/langchain_core/runnables/configurable.py
   192	libs/core/langchain_core/runnables/fallbacks.py
   193	libs/core/langchain_core/runnables/graph.py
   194	libs/core/langchain_core/runnables/graph_ascii.py
   195	libs/core/langchain_core/runnables/graph_mermaid.py
   196	libs/core/langchain_core/runnables/graph_png.py
   197	libs/core/langchain_core/runnables/history.py
   198	libs/core/langchain_core/runnables/passthrough.py
   199	libs/core/langchain_core/runnables/retry.py
   200	libs/core/langchain_core/runnables/router.py
   201	libs/core/langchain_core/runnables/schema.py
   202	libs/core/langchain_core/runnables/utils.py
   203	libs/core/langchain_core/stores.py
   204	libs/core/langchain_core/structured_query.py
   205	libs/core/langchain_core/sys_info.py
   206	libs/core/langchain_core/tools/__init__.py
   207	libs/core/langchain_core/tools/base.py
   208	libs/core/langchain_core/tools/convert.py
   209	libs/core/langchain_core/tools/render.py
   210	libs/core/langchain_core/tools/retriever.py
   211	libs/core/langchain_core/tools/simple.py
   212	libs/core/langchain_core/tools/structured.py
   213	libs/core/langchain_core/tracers/__init__.py
   214	libs/core/langchain_core/tracers/_compat.py
   215	libs/core/langchain_core/tracers/_streaming.py
   216	libs/core/langchain_core/tracers/base.py
   217	libs/core/langchain_core/tracers/context.py
   218	libs/core/langchain_core/tracers/core.py
   219	libs/core/langchain_core/tracers/evaluation.py
   220	libs/core/langchain_core/tracers/event_stream.py
   221	libs/core/langchain_core/tracers/langchain.py
   222	libs/core/langchain_core/tracers/log_stream.py
   223	libs/core/langchain_core/tracers/memory_stream.py
   224	libs/core/langchain_core/tracers/root_listeners.py
   225	libs/core/langchain_core/tracers/run_collector.py
   226	libs/core/langchain_core/tracers/schemas.py
   227	libs/core/langchain_core/tracers/stdout.py
   228	libs/core/langchain_core/utils/__init__.py
   229	libs/core/langchain_core/utils/_gateway.py
   230	libs/core/langchain_core/utils/_merge.py
   231	libs/core/langchain_core/utils/aiter.py
   232	libs/core/langchain_core/utils/env.py
   233	libs/core/langchain_core/utils/formatting.py
   234	libs/core/langchain_core/utils/function_calling.py
   235	libs/core/langchain_core/utils/html.py
   236	libs/core/langchain_core/utils/image.py
   237	libs/core/langchain_core/utils/input.py
   238	libs/core/langchain_core/utils/interactive_env.py
   239	libs/core/langchain_core/utils/iter.py
   240	libs/core/langchain_core/utils/json.py
   241	libs/core/langchain_core/utils/json_schema.py
   242	libs/core/langchain_core/utils/mustache.py
   243	libs/core/langchain_core/utils/pydantic.py
   244	libs/core/langchain_core/utils/strings.py
   245	libs/core/langchain_core/utils/usage.py
   246	libs/core/langchain_core/utils/utils.py
   247	libs/core/langchain_core/utils/uuid.py
   248	libs/core/langchain_core/vectorstores/__init__.py
   249	libs/core/langchain_core/vectorstores/base.py
   250	libs/core/langchain_core/vectorstores/in_memory.py
   251	libs/core/langchain_core/vectorstores/utils.py
   252	libs/core/langchain_core/version.py
   253	libs/core/pyproject.toml
   254	libs/core/scripts/check_imports.py
   255	libs/core/scripts/check_version.py
   256	libs/core/scripts/lint_imports.sh
   257	libs/core/tests/__init__.py
   258	libs/core/tests/benchmarks/__init__.py
   259	libs/core/tests/benchmarks/test_async_callbacks.py
   260	libs/core/tests/benchmarks/test_imports.py
   261	libs/core/tests/benchmarks/test_tool_schema_conversion.py
   262	libs/core/tests/integration_tests/__init__.py
   263	libs/core/tests/integration_tests/test_compile.py
   264	libs/core/tests/unit_tests/__init__.py
   265	libs/core/tests/unit_tests/_api/__init__.py
   266	libs/core/tests/unit_tests/_api/test_beta_decorator.py
   267	libs/core/tests/unit_tests/_api/test_deprecation.py
   268	libs/core/tests/unit_tests/_api/test_imports.py
   269	libs/core/tests/unit_tests/_api/test_path.py
   270	libs/core/tests/unit_tests/caches/__init__.py
   271	libs/core/tests/unit_tests/caches/test_in_memory_cache.py
   272	libs/core/tests/unit_tests/callbacks/__init__.py
   273	libs/core/tests/unit_tests/callbacks/test_async_callback_manager.py
   274	libs/core/tests/unit_tests/callbacks/test_dispatch_custom_event.py
   275	libs/core/tests/unit_tests/callbacks/test_handle_event.py
   276	libs/core/tests/unit_tests/callbacks/test_imports.py
   277	libs/core/tests/unit_tests/callbacks/test_sync_callback_manager.py
   278	libs/core/tests/unit_tests/callbacks/test_trace_group_cancellation.py
   279	libs/core/tests/unit_tests/callbacks/test_usage_callback.py
   280	libs/core/tests/unit_tests/chat_history/__init__.py
   281	libs/core/tests/unit_tests/chat_history/test_chat_history.py
   282	libs/core/tests/unit_tests/conftest.py
   283	libs/core/tests/unit_tests/data/prompt_file.txt
   284	libs/core/tests/unit_tests/data/prompts/prompt_extra_args.json
   285	libs/core/tests/unit_tests/data/prompts/prompt_missing_args.json
   286	libs/core/tests/unit_tests/data/prompts/simple_prompt.json
   287	libs/core/tests/unit_tests/dependencies/__init__.py
   288	libs/core/tests/unit_tests/dependencies/test_dependencies.py
   289	libs/core/tests/unit_tests/document_loaders/__init__.py
   290	libs/core/tests/unit_tests/document_loaders/test_base.py
   291	libs/core/tests/unit_tests/document_loaders/test_langsmith.py
   292	libs/core/tests/unit_tests/documents/__init__.py
   293	libs/core/tests/unit_tests/documents/test_document.py
   294	libs/core/tests/unit_tests/documents/test_imports.py
   295	libs/core/tests/unit_tests/documents/test_str.py
   296	libs/core/tests/unit_tests/embeddings/__init__.py
   297	libs/core/tests/unit_tests/embeddings/test_deterministic_embedding.py
   298	libs/core/tests/unit_tests/example_selectors/__init__.py
   299	libs/core/tests/unit_tests/example_selectors/test_base.py
   300	libs/core/tests/unit_tests/example_selectors/test_imports.py
   301	libs/core/tests/unit_tests/example_selectors/test_length_based_example_selector.py
   302	libs/core/tests/unit_tests/example_selectors/test_similarity.py
   303	libs/core/tests/unit_tests/examples/example-non-utf8.csv
   304	libs/core/tests/unit_tests/examples/example-non-utf8.txt
   305	libs/core/tests/unit_tests/examples/example-utf8.csv
   306	libs/core/tests/unit_tests/examples/example-utf8.txt
   307	libs/core/tests/unit_tests/examples/example_prompt.json
   308	libs/core/tests/unit_tests/examples/examples.json
   309	libs/core/tests/unit_tests/examples/examples.yaml
   310	libs/core/tests/unit_tests/examples/few_shot_prompt.json
   311	libs/core/tests/unit_tests/examples/few_shot_prompt.yaml
   312	libs/core/tests/unit_tests/examples/few_shot_prompt_example_prompt.json
   313	libs/core/tests/unit_tests/examples/few_shot_prompt_examples_in.json
   314	libs/core/tests/unit_tests/examples/few_shot_prompt_yaml_examples.yaml
   315	libs/core/tests/unit_tests/examples/jinja_injection_prompt.json
   316	libs/core/tests/unit_tests/examples/jinja_injection_prompt.yaml
   317	libs/core/tests/unit_tests/examples/prompt_with_output_parser.json
   318	libs/core/tests/unit_tests/examples/simple_prompt.json
   319	libs/core/tests/unit_tests/examples/simple_prompt.yaml
   320	libs/core/tests/unit_tests/examples/simple_prompt_with_template_file.json
   321	libs/core/tests/unit_tests/examples/simple_template.txt
   322	libs/core/tests/unit_tests/fake/__init__.py
   323	libs/core/tests/unit_tests/fake/callbacks.py
   324	libs/core/tests/unit_tests/fake/test_fake_chat_model.py
   325	libs/core/tests/unit_tests/indexing/__init__.py
   326	libs/core/tests/unit_tests/indexing/test_hashed_document.py
   327	libs/core/tests/unit_tests/indexing/test_in_memory_indexer.py
   328	libs/core/tests/unit_tests/indexing/test_in_memory_record_manager.py
   329	libs/core/tests/unit_tests/indexing/test_indexing.py
   330	libs/core/tests/unit_tests/indexing/test_public_api.py
   331	libs/core/tests/unit_tests/language_models/__init__.py
   332	libs/core/tests/unit_tests/language_models/chat_models/__init__.py
   333	libs/core/tests/unit_tests/language_models/chat_models/test_base.py
   334	libs/core/tests/unit_tests/language_models/chat_models/test_benchmark.py
   335	libs/core/tests/unit_tests/language_models/chat_models/test_cache.py
   336	libs/core/tests/unit_tests/language_models/chat_models/test_rate_limiting.py
   337	libs/core/tests/unit_tests/language_models/llms/__init__.py
   338	libs/core/tests/unit_tests/language_models/llms/test_base.py
   339	libs/core/tests/unit_tests/language_models/llms/test_cache.py
   340	libs/core/tests/unit_tests/language_models/test_chat_model_stream.py
   341	libs/core/tests/unit_tests/language_models/test_chat_model_streamer.py
   342	libs/core/tests/unit_tests/language_models/test_chat_model_v3_stream.py
   343	libs/core/tests/unit_tests/language_models/test_compat_bridge.py
   344	libs/core/tests/unit_tests/language_models/test_imports.py
   345	libs/core/tests/unit_tests/language_models/test_model_profile.py
   346	libs/core/tests/unit_tests/language_models/test_v1_parity.py
   347	libs/core/tests/unit_tests/load/__init__.py
   348	libs/core/tests/unit_tests/load/test_imports.py
   349	libs/core/tests/unit_tests/load/test_secret_injection.py
   350	libs/core/tests/unit_tests/load/test_serializable.py
   351	libs/core/tests/unit_tests/messages/__init__.py
   352	libs/core/tests/unit_tests/messages/block_translators/__init__.py
   353	libs/core/tests/unit_tests/messages/block_translators/test_anthropic.py
   354	libs/core/tests/unit_tests/messages/block_translators/test_bedrock.py
   355	libs/core/tests/unit_tests/messages/block_translators/test_bedrock_converse.py
   356	libs/core/tests/unit_tests/messages/block_translators/test_google_genai.py
   357	libs/core/tests/unit_tests/messages/block_translators/test_groq.py
   358	libs/core/tests/unit_tests/messages/block_translators/test_langchain_v0.py
   359	libs/core/tests/unit_tests/messages/block_translators/test_openai.py
   360	libs/core/tests/unit_tests/messages/block_translators/test_registration.py
   361	libs/core/tests/unit_tests/messages/test_ai.py
   362	libs/core/tests/unit_tests/messages/test_imports.py
   363	libs/core/tests/unit_tests/messages/test_utils.py
   364	libs/core/tests/unit_tests/output_parsers/__init__.py
   365	libs/core/tests/unit_tests/output_parsers/test_base_parsers.py
   366	libs/core/tests/unit_tests/output_parsers/test_imports.py
   367	libs/core/tests/unit_tests/output_parsers/test_json.py
   368	libs/core/tests/unit_tests/output_parsers/test_list_parser.py
   369	libs/core/tests/unit_tests/output_parsers/test_openai_functions.py
   370	libs/core/tests/unit_tests/output_parsers/test_openai_tools.py
   371	libs/core/tests/unit_tests/output_parsers/test_pydantic_parser.py
   372	libs/core/tests/unit_tests/output_parsers/test_xml_parser.py
   373	libs/core/tests/unit_tests/outputs/__init__.py
   374	libs/core/tests/unit_tests/outputs/test_chat_generation.py
   375	libs/core/tests/unit_tests/outputs/test_imports.py
   376	libs/core/tests/unit_tests/prompt_file.txt
   377	libs/core/tests/unit_tests/prompts/__init__.py
   378	libs/core/tests/unit_tests/prompts/__snapshots__/test_chat.ambr
   379	libs/core/tests/unit_tests/prompts/__snapshots__/test_prompt.ambr
   380	libs/core/tests/unit_tests/prompts/prompt_extra_args.json
   381	libs/core/tests/unit_tests/prompts/prompt_missing_args.json
   382	libs/core/tests/unit_tests/prompts/simple_prompt.json
   383	libs/core/tests/unit_tests/prompts/test_chat.py
   384	libs/core/tests/unit_tests/prompts/test_dict.py
   385	libs/core/tests/unit_tests/prompts/test_few_shot.py
   386	libs/core/tests/unit_tests/prompts/test_few_shot_with_templates.py
   387	libs/core/tests/unit_tests/prompts/test_image.py
   388	libs/core/tests/unit_tests/prompts/test_imports.py
   389	libs/core/tests/unit_tests/prompts/test_loading.py
   390	libs/core/tests/unit_tests/prompts/test_prompt.py
   391	libs/core/tests/unit_tests/prompts/test_string.py
   392	libs/core/tests/unit_tests/prompts/test_structured.py
   393	libs/core/tests/unit_tests/prompts/test_utils.py
   394	libs/core/tests/unit_tests/pydantic_utils.py
   395	libs/core/tests/unit_tests/rate_limiters/__init__.py
   396	libs/core/tests/unit_tests/rate_limiters/test_in_memory_rate_limiter.py
   397	libs/core/tests/unit_tests/runnables/__init__.py
   398	libs/core/tests/unit_tests/runnables/__snapshots__/test_fallbacks.ambr
   399	libs/core/tests/unit_tests/runnables/__snapshots__/test_graph.ambr
   400	libs/core/tests/unit_tests/runnables/__snapshots__/test_runnable.ambr
   401	libs/core/tests/unit_tests/runnables/conftest.py
   402	libs/core/tests/unit_tests/runnables/test_concurrency.py
   403	libs/core/tests/unit_tests/runnables/test_config.py
   404	libs/core/tests/unit_tests/runnables/test_configurable.py
   405	libs/core/tests/unit_tests/runnables/test_fallbacks.py
   406	libs/core/tests/unit_tests/runnables/test_graph.py
   407	libs/core/tests/unit_tests/runnables/test_history.py
   408	libs/core/tests/unit_tests/runnables/test_imports.py
   409	libs/core/tests/unit_tests/runnables/test_runnable.py
   410	libs/core/tests/unit_tests/runnables/test_runnable_events_v1.py
   411	libs/core/tests/unit_tests/runnables/test_runnable_events_v2.py
   412	libs/core/tests/unit_tests/runnables/test_runnable_events_v3.py
   413	libs/core/tests/unit_tests/runnables/test_tracing_interops.py
   414	libs/core/tests/unit_tests/runnables/test_utils.py
   415	libs/core/tests/unit_tests/stores/__init__.py
   416	libs/core/tests/unit_tests/stores/test_in_memory.py
   417	libs/core/tests/unit_tests/stubs.py
   418	libs/core/tests/unit_tests/test_globals.py
   419	libs/core/tests/unit_tests/test_imports.py
   420	libs/core/tests/unit_tests/test_messages.py
   421	libs/core/tests/unit_tests/test_outputs.py
   422	libs/core/tests/unit_tests/test_prompt_values.py
   423	libs/core/tests/unit_tests/test_pydantic_imports.py
   424	libs/core/tests/unit_tests/test_pydantic_serde.py
   425	libs/core/tests/unit_tests/test_retrievers.py
   426	libs/core/tests/unit_tests/test_setup.py
   427	libs/core/tests/unit_tests/test_ssrf_policy_transport.py
   428	libs/core/tests/unit_tests/test_ssrf_protection.py
   429	libs/core/tests/unit_tests/test_sys_info.py
   430	libs/core/tests/unit_tests/test_tools.py
   431	libs/core/tests/unit_tests/test_tools_postponed_annotations.py
   432	libs/core/tests/unit_tests/tracers/__init__.py
   433	libs/core/tests/unit_tests/tracers/test_async_base_tracer.py
   434	libs/core/tests/unit_tests/tracers/test_automatic_metadata.py
   435	libs/core/tests/unit_tests/tracers/test_base_tracer.py
   436	libs/core/tests/unit_tests/tracers/test_imports.py
   437	libs/core/tests/unit_tests/tracers/test_langchain.py
   438	libs/core/tests/unit_tests/tracers/test_memory_stream.py
   439	libs/core/tests/unit_tests/tracers/test_run_collector.py
   440	libs/core/tests/unit_tests/tracers/test_schemas.py
   441	libs/core/tests/unit_tests/utils/__init__.py
   442	libs/core/tests/unit_tests/utils/test_aiter.py
   443	libs/core/tests/unit_tests/utils/test_env.py
   444	libs/core/tests/unit_tests/utils/test_formatting.py
   445	libs/core/tests/unit_tests/utils/test_function_calling.py
   446	libs/core/tests/unit_tests/utils/test_gateway.py
   447	libs/core/tests/unit_tests/utils/test_html.py
   448	libs/core/tests/unit_tests/utils/test_imports.py
   449	libs/core/tests/unit_tests/utils/test_iter.py
   450	libs/core/tests/unit_tests/utils/test_json_schema.py
   451	libs/core/tests/unit_tests/utils/test_pydantic.py
   452	libs/core/tests/unit_tests/utils/test_rm_titles.py
   453	libs/core/tests/unit_tests/utils/test_strings.py
   454	libs/core/tests/unit_tests/utils/test_usage.py
   455	libs/core/tests/unit_tests/utils/test_utils.py
   456	libs/core/tests/unit_tests/utils/test_uuid_utils.py
   457	libs/core/tests/unit_tests/vectorstores/__init__.py
   458	libs/core/tests/unit_tests/vectorstores/test_in_memory.py
   459	libs/core/tests/unit_tests/vectorstores/test_utils.py
   460	libs/core/tests/unit_tests/vectorstores/test_vectorstore.py
   461	libs/core/uv.lock
   462	libs/langchain/.dockerignore
   463	libs/langchain/.flake8
   464	libs/langchain/LICENSE
   465	libs/langchain/Makefile
   466	libs/langchain/README.md
   467	libs/langchain/dev.Dockerfile
   468	libs/langchain/extended_testing_deps.txt
   469	libs/langchain/langchain_classic/__init__.py
   470	libs/langchain/langchain_classic/_api/__init__.py
   471	libs/langchain/langchain_classic/_api/deprecation.py
   472	libs/langchain/langchain_classic/_api/interactive_env.py
   473	libs/langchain/langchain_classic/_api/module_import.py
   474	libs/langchain/langchain_classic/_api/path.py
   475	libs/langchain/langchain_classic/adapters/__init__.py
   476	libs/langchain/langchain_classic/adapters/openai.py
   477	libs/langchain/langchain_classic/agents/__init__.py
   478	libs/langchain/langchain_classic/agents/agent.py
   479	libs/langchain/langchain_classic/agents/agent_iterator.py
   480	libs/langchain/langchain_classic/agents/agent_toolkits/__init__.py
   481	libs/langchain/langchain_classic/agents/agent_toolkits/ainetwork/__init__.py
   482	libs/langchain/langchain_classic/agents/agent_toolkits/ainetwork/toolkit.py
   483	libs/langchain/langchain_classic/agents/agent_toolkits/amadeus/__init__.py
   484	libs/langchain/langchain_classic/agents/agent_toolkits/amadeus/toolkit.py
   485	libs/langchain/langchain_classic/agents/agent_toolkits/azure_cognitive_services.py
   486	libs/langchain/langchain_classic/agents/agent_toolkits/base.py
   487	libs/langchain/langchain_classic/agents/agent_toolkits/clickup/__init__.py
   488	libs/langchain/langchain_classic/agents/agent_toolkits/clickup/toolkit.py
   489	libs/langchain/langchain_classic/agents/agent_toolkits/conversational_retrieval/__init__.py
   490	libs/langchain/langchain_classic/agents/agent_toolkits/conversational_retrieval/openai_functions.py
   491	libs/langchain/langchain_classic/agents/agent_toolkits/conversational_retrieval/tool.py
   492	libs/langchain/langchain_classic/agents/agent_toolkits/csv/__init__.py
   493	libs/langchain/langchain_classic/agents/agent_toolkits/file_management/__init__.py
   494	libs/langchain/langchain_classic/agents/agent_toolkits/file_management/toolkit.py
   495	libs/langchain/langchain_classic/agents/agent_toolkits/github/__init__.py
   496	libs/langchain/langchain_classic/agents/agent_toolkits/github/toolkit.py
   497	libs/langchain/langchain_classic/agents/agent_toolkits/gitlab/__init__.py
   498	libs/langchain/langchain_classic/agents/agent_toolkits/gitlab/toolkit.py
   499	libs/langchain/langchain_classic/agents/agent_toolkits/gmail/__init__.py
   500	libs/langchain/langchain_classic/agents/agent_toolkits/gmail/toolkit.py
   501	libs/langchain/langchain_classic/agents/agent_toolkits/jira/__init__.py
   502	libs/langchain/langchain_classic/agents/agent_toolkits/jira/toolkit.py
   503	libs/langchain/langchain_classic/agents/agent_toolkits/json/__init__.py
   504	libs/langchain/langchain_classic/agents/agent_toolkits/json/base.py
   505	libs/langchain/langchain_classic/agents/agent_toolkits/json/prompt.py
   506	libs/langchain/langchain_classic/agents/agent_toolkits/json/toolkit.py
   507	libs/langchain/langchain_classic/agents/agent_toolkits/multion/__init__.py
   508	libs/langchain/langchain_classic/agents/agent_toolkits/multion/toolkit.py
   509	libs/langchain/langchain_classic/agents/agent_toolkits/nasa/__init__.py
   510	libs/langchain/langchain_classic/agents/agent_toolkits/nasa/toolkit.py
   511	libs/langchain/langchain_classic/agents/agent_toolkits/nla/__init__.py
   512	libs/langchain/langchain_classic/agents/agent_toolkits/nla/tool.py
   513	libs/langchain/langchain_classic/agents/agent_toolkits/nla/toolkit.py
   514	libs/langchain/langchain_classic/agents/agent_toolkits/office365/__init__.py
   515	libs/langchain/langchain_classic/agents/agent_toolkits/office365/toolkit.py
   516	libs/langchain/langchain_classic/agents/agent_toolkits/openapi/__init__.py
   517	libs/langchain/langchain_classic/agents/agent_toolkits/openapi/base.py
   518	libs/langchain/langchain_classic/agents/agent_toolkits/openapi/planner.py
   519	libs/langchain/langchain_classic/agents/agent_toolkits/openapi/planner_prompt.py
   520	libs/langchain/langchain_classic/agents/agent_toolkits/openapi/prompt.py
   521	libs/langchain/langchain_classic/agents/agent_toolkits/openapi/spec.py
   522	libs/langchain/langchain_classic/agents/agent_toolkits/openapi/toolkit.py
   523	libs/langchain/langchain_classic/agents/agent_toolkits/pandas/__init__.py
   524	libs/langchain/langchain_classic/agents/agent_toolkits/playwright/__init__.py
   525	libs/langchain/langchain_classic/agents/agent_toolkits/playwright/toolkit.py
   526	libs/langchain/langchain_classic/agents/agent_toolkits/powerbi/__init__.py
   527	libs/langchain/langchain_classic/agents/agent_toolkits/powerbi/base.py
   528	libs/langchain/langchain_classic/agents/agent_toolkits/powerbi/chat_base.py
   529	libs/langchain/langchain_classic/agents/agent_toolkits/powerbi/prompt.py
   530	libs/langchain/langchain_classic/agents/agent_toolkits/powerbi/toolkit.py
   531	libs/langchain/langchain_classic/agents/agent_toolkits/python/__init__.py
   532	libs/langchain/langchain_classic/agents/agent_toolkits/slack/__init__.py
   533	libs/langchain/langchain_classic/agents/agent_toolkits/slack/toolkit.py
   534	libs/langchain/langchain_classic/agents/agent_toolkits/spark/__init__.py
   535	libs/langchain/langchain_classic/agents/agent_toolkits/spark_sql/__init__.py
   536	libs/langchain/langchain_classic/agents/agent_toolkits/spark_sql/base.py
   537	libs/langchain/langchain_classic/agents/agent_toolkits/spark_sql/prompt.py
   538	libs/langchain/langchain_classic/agents/agent_toolkits/spark_sql/toolkit.py
   539	libs/langchain/langchain_classic/agents/agent_toolkits/sql/__init__.py
   540	libs/langchain/langchain_classic/agents/agent_toolkits/sql/base.py
   541	libs/langchain/langchain_classic/agents/agent_toolkits/sql/prompt.py
   542	libs/langchain/langchain_classic/agents/agent_toolkits/sql/toolkit.py
   543	libs/langchain/langchain_classic/agents/agent_toolkits/steam/__init__.py
   544	libs/langchain/langchain_classic/agents/agent_toolkits/steam/toolkit.py
   545	libs/langchain/langchain_classic/agents/agent_toolkits/vectorstore/__init__.py
   546	libs/langchain/langchain_classic/agents/agent_toolkits/vectorstore/base.py
   547	libs/langchain/langchain_classic/agents/agent_toolkits/vectorstore/prompt.py
   548	libs/langchain/langchain_classic/agents/agent_toolkits/vectorstore/toolkit.py
   549	libs/langchain/langchain_classic/agents/agent_toolkits/xorbits/__init__.py
   550	libs/langchain/langchain_classic/agents/agent_toolkits/zapier/__init__.py
   551	libs/langchain/langchain_classic/agents/agent_toolkits/zapier/toolkit.py
   552	libs/langchain/langchain_classic/agents/agent_types.py
   553	libs/langchain/langchain_classic/agents/chat/__init__.py
   554	libs/langchain/langchain_classic/agents/chat/base.py
   555	libs/langchain/langchain_classic/agents/chat/output_parser.py
   556	libs/langchain/langchain_classic/agents/chat/prompt.py
   557	libs/langchain/langchain_classic/agents/conversational/__init__.py
   558	libs/langchain/langchain_classic/agents/conversational/base.py
   559	libs/langchain/langchain_classic/agents/conversational/output_parser.py
   560	libs/langchain/langchain_classic/agents/conversational/prompt.py
   561	libs/langchain/langchain_classic/agents/conversational_chat/__init__.py
   562	libs/langchain/langchain_classic/agents/conversational_chat/base.py
   563	libs/langchain/langchain_classic/agents/conversational_chat/output_parser.py
   564	libs/langchain/langchain_classic/agents/conversational_chat/prompt.py
   565	libs/langchain/langchain_classic/agents/format_scratchpad/__init__.py
   566	libs/langchain/langchain_classic/agents/format_scratchpad/log.py
   567	libs/langchain/langchain_classic/agents/format_scratchpad/log_to_messages.py
   568	libs/langchain/langchain_classic/agents/format_scratchpad/openai_functions.py
   569	libs/langchain/langchain_classic/agents/format_scratchpad/openai_tools.py
   570	libs/langchain/langchain_classic/agents/format_scratchpad/tools.py
   571	libs/langchain/langchain_classic/agents/format_scratchpad/xml.py
   572	libs/langchain/langchain_classic/agents/initialize.py
   573	libs/langchain/langchain_classic/agents/json_chat/__init__.py
   574	libs/langchain/langchain_classic/agents/json_chat/base.py
   575	libs/langchain/langchain_classic/agents/json_chat/prompt.py
   576	libs/langchain/langchain_classic/agents/load_tools.py
   577	libs/langchain/langchain_classic/agents/loading.py
   578	libs/langchain/langchain_classic/agents/mrkl/__init__.py
   579	libs/langchain/langchain_classic/agents/mrkl/base.py
   580	libs/langchain/langchain_classic/agents/mrkl/output_parser.py
   581	libs/langchain/langchain_classic/agents/mrkl/prompt.py
   582	libs/langchain/langchain_classic/agents/openai_assistant/__init__.py
   583	libs/langchain/langchain_classic/agents/openai_assistant/base.py
   584	libs/langchain/langchain_classic/agents/openai_functions_agent/__init__.py
   585	libs/langchain/langchain_classic/agents/openai_functions_agent/agent_token_buffer_memory.py
   586	libs/langchain/langchain_classic/agents/openai_functions_agent/base.py
   587	libs/langchain/langchain_classic/agents/openai_functions_multi_agent/__init__.py
   588	libs/langchain/langchain_classic/agents/openai_functions_multi_agent/base.py
   589	libs/langchain/langchain_classic/agents/openai_tools/__init__.py
   590	libs/langchain/langchain_classic/agents/openai_tools/base.py
   591	libs/langchain/langchain_classic/agents/output_parsers/__init__.py
   592	libs/langchain/langchain_classic/agents/output_parsers/json.py
   593	libs/langchain/langchain_classic/agents/output_parsers/openai_functions.py
   594	libs/langchain/langchain_classic/agents/output_parsers/openai_tools.py
   595	libs/langchain/langchain_classic/agents/output_parsers/react_json_single_input.py
   596	libs/langchain/langchain_classic/agents/output_parsers/react_single_input.py
   597	libs/langchain/langchain_classic/agents/output_parsers/self_ask.py
   598	libs/langchain/langchain_classic/agents/output_parsers/tools.py
   599	libs/langchain/langchain_classic/agents/output_parsers/xml.py
   600	libs/langchain/langchain_classic/agents/react/__init__.py
   601	libs/langchain/langchain_classic/agents/react/agent.py
   602	libs/langchain/langchain_classic/agents/react/base.py
   603	libs/langchain/langchain_classic/agents/react/output_parser.py
   604	libs/langchain/langchain_classic/agents/react/textworld_prompt.py
   605	libs/langchain/langchain_classic/agents/react/wiki_prompt.py
   606	libs/langchain/langchain_classic/agents/schema.py
   607	libs/langchain/langchain_classic/agents/self_ask_with_search/__init__.py
   608	libs/langchain/langchain_classic/agents/self_ask_with_search/base.py
   609	libs/langchain/langchain_classic/agents/self_ask_with_search/output_parser.py
   610	libs/langchain/langchain_classic/agents/self_ask_with_search/prompt.py
   611	libs/langchain/langchain_classic/agents/structured_chat/__init__.py
   612	libs/langchain/langchain_classic/agents/structured_chat/base.py
   613	libs/langchain/langchain_classic/agents/structured_chat/output_parser.py
   614	libs/langchain/langchain_classic/agents/structured_chat/prompt.py
   615	libs/langchain/langchain_classic/agents/tool_calling_agent/__init__.py
   616	libs/langchain/langchain_classic/agents/tool_calling_agent/base.py
   617	libs/langchain/langchain_classic/agents/tools.py
   618	libs/langchain/langchain_classic/agents/types.py
   619	libs/langchain/langchain_classic/agents/utils.py
   620	libs/langchain/langchain_classic/agents/xml/__init__.py
   621	libs/langchain/langchain_classic/agents/xml/base.py
   622	libs/langchain/langchain_classic/agents/xml/prompt.py
   623	libs/langchain/langchain_classic/base_language.py
   624	libs/langchain/langchain_classic/base_memory.py
   625	libs/langchain/langchain_classic/cache.py
   626	libs/langchain/langchain_classic/callbacks/__init__.py
   627	libs/langchain/langchain_classic/callbacks/aim_callback.py
   628	libs/langchain/langchain_classic/callbacks/argilla_callback.py
   629	libs/langchain/langchain_classic/callbacks/arize_callback.py
   630	libs/langchain/langchain_classic/callbacks/arthur_callback.py
   631	libs/langchain/langchain_classic/callbacks/base.py
   632	libs/langchain/langchain_classic/callbacks/clearml_callback.py
   633	libs/langchain/langchain_classic/callbacks/comet_ml_callback.py
   634	libs/langchain/langchain_classic/callbacks/confident_callback.py
   635	libs/langchain/langchain_classic/callbacks/context_callback.py
   636	libs/langchain/langchain_classic/callbacks/file.py
   637	libs/langchain/langchain_classic/callbacks/flyte_callback.py
   638	libs/langchain/langchain_classic/callbacks/human.py
   639	libs/langchain/langchain_classic/callbacks/infino_callback.py
   640	libs/langchain/langchain_classic/callbacks/labelstudio_callback.py
   641	libs/langchain/langchain_classic/callbacks/llmonitor_callback.py
   642	libs/langchain/langchain_classic/callbacks/manager.py
   643	libs/langchain/langchain_classic/callbacks/mlflow_callback.py
   644	libs/langchain/langchain_classic/callbacks/openai_info.py
   645	libs/langchain/langchain_classic/callbacks/promptlayer_callback.py
   646	libs/langchain/langchain_classic/callbacks/sagemaker_callback.py
   647	libs/langchain/langchain_classic/callbacks/stdout.py
   648	libs/langchain/langchain_classic/callbacks/streaming_aiter.py
   649	libs/langchain/langchain_classic/callbacks/streaming_aiter_final_only.py
   650	libs/langchain/langchain_classic/callbacks/streaming_stdout.py
   651	libs/langchain/langchain_classic/callbacks/streaming_stdout_final_only.py
   652	libs/langchain/langchain_classic/callbacks/streamlit/__init__.py
   653	libs/langchain/langchain_classic/callbacks/streamlit/mutable_expander.py
   654	libs/langchain/langchain_classic/callbacks/streamlit/streamlit_callback_handler.py
   655	libs/langchain/langchain_classic/callbacks/tracers/__init__.py
   656	libs/langchain/langchain_classic/callbacks/tracers/base.py
   657	libs/langchain/langchain_classic/callbacks/tracers/comet.py
   658	libs/langchain/langchain_classic/callbacks/tracers/evaluation.py
   659	libs/langchain/langchain_classic/callbacks/tracers/langchain.py
   660	libs/langchain/langchain_classic/callbacks/tracers/log_stream.py
   661	libs/langchain/langchain_classic/callbacks/tracers/logging.py
   662	libs/langchain/langchain_classic/callbacks/tracers/root_listeners.py
   663	libs/langchain/langchain_classic/callbacks/tracers/run_collector.py
   664	libs/langchain/langchain_classic/callbacks/tracers/schemas.py
   665	libs/langchain/langchain_classic/callbacks/tracers/stdout.py
   666	libs/langchain/langchain_classic/callbacks/tracers/wandb.py
   667	libs/langchain/langchain_classic/callbacks/trubrics_callback.py
   668	libs/langchain/langchain_classic/callbacks/utils.py
   669	libs/langchain/langchain_classic/callbacks/wandb_callback.py
   670	libs/langchain/langchain_classic/callbacks/whylabs_callback.py
   671	libs/langchain/langchain_classic/chains/__init__.py
   672	libs/langchain/langchain_classic/chains/api/__init__.py
   673	libs/langchain/langchain_classic/chains/api/base.py
   674	libs/langchain/langchain_classic/chains/api/news_docs.py
   675	libs/langchain/langchain_classic/chains/api/open_meteo_docs.py
   676	libs/langchain/langchain_classic/chains/api/openapi/__init__.py
   677	libs/langchain/langchain_classic/chains/api/openapi/chain.py
   678	libs/langchain/langchain_classic/chains/api/openapi/prompts.py
   679	libs/langchain/langchain_classic/chains/api/openapi/requests_chain.py
   680	libs/langchain/langchain_classic/chains/api/openapi/response_chain.py
   681	libs/langchain/langchain_classic/chains/api/podcast_docs.py
   682	libs/langchain/langchain_classic/chains/api/prompt.py
   683	libs/langchain/langchain_classic/chains/api/tmdb_docs.py
   684	libs/langchain/langchain_classic/chains/base.py
   685	libs/langchain/langchain_classic/chains/chat_vector_db/__init__.py
   686	libs/langchain/langchain_classic/chains/chat_vector_db/prompts.py
   687	libs/langchain/langchain_classic/chains/combine_documents/__init__.py
   688	libs/langchain/langchain_classic/chains/combine_documents/base.py
   689	libs/langchain/langchain_classic/chains/combine_documents/map_reduce.py
   690	libs/langchain/langchain_classic/chains/combine_documents/map_rerank.py
   691	libs/langchain/langchain_classic/chains/combine_documents/reduce.py
   692	libs/langchain/langchain_classic/chains/combine_documents/refine.py
   693	libs/langchain/langchain_classic/chains/combine_documents/stuff.py
   694	libs/langchain/langchain_classic/chains/constitutional_ai/__init__.py
   695	libs/langchain/langchain_classic/chains/constitutional_ai/base.py
   696	libs/langchain/langchain_classic/chains/constitutional_ai/models.py
   697	libs/langchain/langchain_classic/chains/constitutional_ai/principles.py
   698	libs/langchain/langchain_classic/chains/constitutional_ai/prompts.py
   699	libs/langchain/langchain_classic/chains/conversation/__init__.py
   700	libs/langchain/langchain_classic/chains/conversation/base.py
   701	libs/langchain/langchain_classic/chains/conversation/memory.py
   702	libs/langchain/langchain_classic/chains/conversation/prompt.py
   703	libs/langchain/langchain_classic/chains/conversational_retrieval/__init__.py
   704	libs/langchain/langchain_classic/chains/conversational_retrieval/base.py
   705	libs/langchain/langchain_classic/chains/conversational_retrieval/prompts.py
   706	libs/langchain/langchain_classic/chains/elasticsearch_database/__init__.py
   707	libs/langchain/langchain_classic/chains/elasticsearch_database/base.py
   708	libs/langchain/langchain_classic/chains/elasticsearch_database/prompts.py
   709	libs/langchain/langchain_classic/chains/ernie_functions/__init__.py
   710	libs/langchain/langchain_classic/chains/ernie_functions/base.py
   711	libs/langchain/langchain_classic/chains/example_generator.py
   712	libs/langchain/langchain_classic/chains/flare/__init__.py
   713	libs/langchain/langchain_classic/chains/flare/base.py
   714	libs/langchain/langchain_classic/chains/flare/prompts.py
   715	libs/langchain/langchain_classic/chains/graph_qa/__init__.py
   716	libs/langchain/langchain_classic/chains/graph_qa/arangodb.py
   717	libs/langchain/langchain_classic/chains/graph_qa/base.py
   718	libs/langchain/langchain_classic/chains/graph_qa/cypher.py
   719	libs/langchain/langchain_classic/chains/graph_qa/cypher_utils.py
   720	libs/langchain/langchain_classic/chains/graph_qa/falkordb.py
   721	libs/langchain/langchain_classic/chains/graph_qa/gremlin.py
   722	libs/langchain/langchain_classic/chains/graph_qa/hugegraph.py
   723	libs/langchain/langchain_classic/chains/graph_qa/kuzu.py
   724	libs/langchain/langchain_classic/chains/graph_qa/nebulagraph.py
   725	libs/langchain/langchain_classic/chains/graph_qa/neptune_cypher.py
   726	libs/langchain/langchain_classic/chains/graph_qa/neptune_sparql.py
   727	libs/langchain/langchain_classic/chains/graph_qa/ontotext_graphdb.py
   728	libs/langchain/langchain_classic/chains/graph_qa/prompts.py
   729	libs/langchain/langchain_classic/chains/graph_qa/sparql.py
   730	libs/langchain/langchain_classic/chains/history_aware_retriever.py
   731	libs/langchain/langchain_classic/chains/hyde/__init__.py
   732	libs/langchain/langchain_classic/chains/hyde/base.py
   733	libs/langchain/langchain_classic/chains/hyde/prompts.py
   734	libs/langchain/langchain_classic/chains/llm.py
   735	libs/langchain/langchain_classic/chains/llm_bash/__init__.py
   736	libs/langchain/langchain_classic/chains/llm_checker/__init__.py
   737	libs/langchain/langchain_classic/chains/llm_checker/base.py
   738	libs/langchain/langchain_classic/chains/llm_checker/prompt.py
   739	libs/langchain/langchain_classic/chains/llm_math/__init__.py
   740	libs/langchain/langchain_classic/chains/llm_math/base.py
   741	libs/langchain/langchain_classic/chains/llm_math/prompt.py
   742	libs/langchain/langchain_classic/chains/llm_requests.py
   743	libs/langchain/langchain_classic/chains/llm_summarization_checker/__init__.py
   744	libs/langchain/langchain_classic/chains/llm_summarization_checker/base.py
   745	libs/langchain/langchain_classic/chains/llm_summarization_checker/prompts/are_all_true_prompt.txt
   746	libs/langchain/langchain_classic/chains/llm_summarization_checker/prompts/check_facts.txt
   747	libs/langchain/langchain_classic/chains/llm_summarization_checker/prompts/create_facts.txt
   748	libs/langchain/langchain_classic/chains/llm_summarization_checker/prompts/revise_summary.txt
   749	libs/langchain/langchain_classic/chains/llm_symbolic_math/__init__.py
   750	libs/langchain/langchain_classic/chains/loading.py
   751	libs/langchain/langchain_classic/chains/mapreduce.py
   752	libs/langchain/langchain_classic/chains/moderation.py
   753	libs/langchain/langchain_classic/chains/natbot/__init__.py
   754	libs/langchain/langchain_classic/chains/natbot/base.py
   755	libs/langchain/langchain_classic/chains/natbot/crawler.py
   756	libs/langchain/langchain_classic/chains/natbot/prompt.py
   757	libs/langchain/langchain_classic/chains/openai_functions/__init__.py
   758	libs/langchain/langchain_classic/chains/openai_functions/base.py
   759	libs/langchain/langchain_classic/chains/openai_functions/citation_fuzzy_match.py
   760	libs/langchain/langchain_classic/chains/openai_functions/extraction.py
   761	libs/langchain/langchain_classic/chains/openai_functions/openapi.py
   762	libs/langchain/langchain_classic/chains/openai_functions/qa_with_structure.py
   763	libs/langchain/langchain_classic/chains/openai_functions/tagging.py
   764	libs/langchain/langchain_classic/chains/openai_functions/utils.py
   765	libs/langchain/langchain_classic/chains/openai_tools/__init__.py
   766	libs/langchain/langchain_classic/chains/openai_tools/extraction.py
   767	libs/langchain/langchain_classic/chains/prompt_selector.py
   768	libs/langchain/langchain_classic/chains/qa_generation/__init__.py
   769	libs/langchain/langchain_classic/chains/qa_generation/base.py
   770	libs/langchain/langchain_classic/chains/qa_generation/prompt.py
   771	libs/langchain/langchain_classic/chains/qa_with_sources/__init__.py
   772	libs/langchain/langchain_classic/chains/qa_with_sources/base.py
   773	libs/langchain/langchain_classic/chains/qa_with_sources/loading.py
   774	libs/langchain/langchain_classic/chains/qa_with_sources/map_reduce_prompt.py
   775	libs/langchain/langchain_classic/chains/qa_with_sources/refine_prompts.py
   776	libs/langchain/langchain_classic/chains/qa_with_sources/retrieval.py
   777	libs/langchain/langchain_classic/chains/qa_with_sources/stuff_prompt.py
   778	libs/langchain/langchain_classic/chains/qa_with_sources/vector_db.py
   779	libs/langchain/langchain_classic/chains/query_constructor/__init__.py
   780	libs/langchain/langchain_classic/chains/query_constructor/base.py
   781	libs/langchain/langchain_classic/chains/query_constructor/ir.py
   782	libs/langchain/langchain_classic/chains/query_constructor/parser.py
   783	libs/langchain/langchain_classic/chains/query_constructor/prompt.py
   784	libs/langchain/langchain_classic/chains/query_constructor/schema.py
   785	libs/langchain/langchain_classic/chains/question_answering/__init__.py
   786	libs/langchain/langchain_classic/chains/question_answering/chain.py
   787	libs/langchain/langchain_classic/chains/question_answering/map_reduce_prompt.py
   788	libs/langchain/langchain_classic/chains/question_answering/map_rerank_prompt.py
   789	libs/langchain/langchain_classic/chains/question_answering/refine_prompts.py
   790	libs/langchain/langchain_classic/chains/question_answering/stuff_prompt.py
   791	libs/langchain/langchain_classic/chains/retrieval.py
   792	libs/langchain/langchain_classic/chains/retrieval_qa/__init__.py
   793	libs/langchain/langchain_classic/chains/retrieval_qa/base.py
   794	libs/langchain/langchain_classic/chains/retrieval_qa/prompt.py
   795	libs/langchain/langchain_classic/chains/router/__init__.py
   796	libs/langchain/langchain_classic/chains/router/base.py
   797	libs/langchain/langchain_classic/chains/router/embedding_router.py
   798	libs/langchain/langchain_classic/chains/router/llm_router.py
   799	libs/langchain/langchain_classic/chains/router/multi_prompt.py
   800	libs/langchain/langchain_classic/chains/router/multi_prompt_prompt.py
   801	libs/langchain/langchain_classic/chains/router/multi_retrieval_prompt.py
   802	libs/langchain/langchain_classic/chains/router/multi_retrieval_qa.py
   803	libs/langchain/langchain_classic/chains/sequential.py
   804	libs/langchain/langchain_classic/chains/sql_database/__init__.py
   805	libs/langchain/langchain_classic/chains/sql_database/prompt.py
   806	libs/langchain/langchain_classic/chains/sql_database/query.py
   807	libs/langchain/langchain_classic/chains/structured_output/__init__.py
   808	libs/langchain/langchain_classic/chains/structured_output/base.py
   809	libs/langchain/langchain_classic/chains/summarize/__init__.py
   810	libs/langchain/langchain_classic/chains/summarize/chain.py
   811	libs/langchain/langchain_classic/chains/summarize/map_reduce_prompt.py
   812	libs/langchain/langchain_classic/chains/summarize/refine_prompts.py
   813	libs/langchain/langchain_classic/chains/summarize/stuff_prompt.py
   814	libs/langchain/langchain_classic/chains/transform.py
   815	libs/langchain/langchain_classic/chat_loaders/__init__.py
   816	libs/langchain/langchain_classic/chat_loaders/base.py
   817	libs/langchain/langchain_classic/chat_loaders/facebook_messenger.py
   818	libs/langchain/langchain_classic/chat_loaders/gmail.py
   819	libs/langchain/langchain_classic/chat_loaders/imessage.py
   820	libs/langchain/langchain_classic/chat_loaders/langsmith.py
   821	libs/langchain/langchain_classic/chat_loaders/slack.py
   822	libs/langchain/langchain_classic/chat_loaders/telegram.py
   823	libs/langchain/langchain_classic/chat_loaders/utils.py
   824	libs/langchain/langchain_classic/chat_loaders/whatsapp.py
   825	libs/langchain/langchain_classic/chat_models/__init__.py
   826	libs/langchain/langchain_classic/chat_models/anthropic.py
   827	libs/langchain/langchain_classic/chat_models/anyscale.py
   828	libs/langchain/langchain_classic/chat_models/azure_openai.py
   829	libs/langchain/langchain_classic/chat_models/azureml_endpoint.py
   830	libs/langchain/langchain_classic/chat_models/baichuan.py
   831	libs/langchain/langchain_classic/chat_models/baidu_qianfan_endpoint.py
   832	libs/langchain/langchain_classic/chat_models/base.py
   833	libs/langchain/langchain_classic/chat_models/bedrock.py
   834	libs/langchain/langchain_classic/chat_models/cohere.py
   835	libs/langchain/langchain_classic/chat_models/databricks.py
   836	libs/langchain/langchain_classic/chat_models/ernie.py
   837	libs/langchain/langchain_classic/chat_models/everlyai.py
   838	libs/langchain/langchain_classic/chat_models/fake.py
   839	libs/langchain/langchain_classic/chat_models/fireworks.py
   840	libs/langchain/langchain_classic/chat_models/gigachat.py
   841	libs/langchain/langchain_classic/chat_models/google_palm.py
   842	libs/langchain/langchain_classic/chat_models/human.py
   843	libs/langchain/langchain_classic/chat_models/hunyuan.py
   844	libs/langchain/langchain_classic/chat_models/javelin_ai_gateway.py
   845	libs/langchain/langchain_classic/chat_models/jinachat.py
   846	libs/langchain/langchain_classic/chat_models/konko.py
   847	libs/langchain/langchain_classic/chat_models/litellm.py
   848	libs/langchain/langchain_classic/chat_models/meta.py
   849	libs/langchain/langchain_classic/chat_models/minimax.py
   850	libs/langchain/langchain_classic/chat_models/mlflow.py
   851	libs/langchain/langchain_classic/chat_models/mlflow_ai_gateway.py
   852	libs/langchain/langchain_classic/chat_models/ollama.py
   853	libs/langchain/langchain_classic/chat_models/openai.py
   854	libs/langchain/langchain_classic/chat_models/pai_eas_endpoint.py
   855	libs/langchain/langchain_classic/chat_models/promptlayer_openai.py
   856	libs/langchain/langchain_classic/chat_models/tongyi.py
   857	libs/langchain/langchain_classic/chat_models/vertexai.py
   858	libs/langchain/langchain_classic/chat_models/volcengine_maas.py
   859	libs/langchain/langchain_classic/chat_models/yandex.py
   860	libs/langchain/langchain_classic/docstore/__init__.py
   861	libs/langchain/langchain_classic/docstore/arbitrary_fn.py
   862	libs/langchain/langchain_classic/docstore/base.py
   863	libs/langchain/langchain_classic/docstore/document.py
   864	libs/langchain/langchain_classic/docstore/in_memory.py
   865	libs/langchain/langchain_classic/docstore/wikipedia.py
   866	libs/langchain/langchain_classic/document_loaders/__init__.py
   867	libs/langchain/langchain_classic/document_loaders/acreom.py
   868	libs/langchain/langchain_classic/document_loaders/airbyte.py
   869	libs/langchain/langchain_classic/document_loaders/airbyte_json.py
   870	libs/langchain/langchain_classic/document_loaders/airtable.py
   871	libs/langchain/langchain_classic/document_loaders/apify_dataset.py
   872	libs/langchain/langchain_classic/document_loaders/arcgis_loader.py
   873	libs/langchain/langchain_classic/document_loaders/arxiv.py
   874	libs/langchain/langchain_classic/document_loaders/assemblyai.py
   875	libs/langchain/langchain_classic/document_loaders/async_html.py
   876	libs/langchain/langchain_classic/document_loaders/azlyrics.py
   877	libs/langchain/langchain_classic/document_loaders/azure_ai_data.py
   878	libs/langchain/langchain_classic/document_loaders/azure_blob_storage_container.py
   879	libs/langchain/langchain_classic/document_loaders/azure_blob_storage_file.py
   880	libs/langchain/langchain_classic/document_loaders/baiducloud_bos_directory.py
   881	libs/langchain/langchain_classic/document_loaders/baiducloud_bos_file.py
   882	libs/langchain/langchain_classic/document_loaders/base.py
   883	libs/langchain/langchain_classic/document_loaders/base_o365.py
   884	libs/langchain/langchain_classic/document_loaders/bibtex.py
   885	libs/langchain/langchain_classic/document_loaders/bigquery.py
   886	libs/langchain/langchain_classic/document_loaders/bilibili.py
   887	libs/langchain/langchain_classic/document_loaders/blackboard.py
   888	libs/langchain/langchain_classic/document_loaders/blob_loaders/__init__.py
   889	libs/langchain/langchain_classic/document_loaders/blob_loaders/file_system.py
   890	libs/langchain/langchain_classic/document_loaders/blob_loaders/schema.py
   891	libs/langchain/langchain_classic/document_loaders/blob_loaders/youtube_audio.py
   892	libs/langchain/langchain_classic/document_loaders/blockchain.py
   893	libs/langchain/langchain_classic/document_loaders/brave_search.py
   894	libs/langchain/langchain_classic/document_loaders/browserless.py
   895	libs/langchain/langchain_classic/document_loaders/chatgpt.py
   896	libs/langchain/langchain_classic/document_loaders/chromium.py
   897	libs/langchain/langchain_classic/document_loaders/college_confidential.py
   898	libs/langchain/langchain_classic/document_loaders/concurrent.py
   899	libs/langchain/langchain_classic/document_loaders/confluence.py
   900	libs/langchain/langchain_classic/document_loaders/conllu.py
   901	libs/langchain/langchain_classic/document_loaders/couchbase.py
   902	libs/langchain/langchain_classic/document_loaders/csv_loader.py
   903	libs/langchain/langchain_classic/document_loaders/cube_semantic.py
   904	libs/langchain/langchain_classic/document_loaders/datadog_logs.py
   905	libs/langchain/langchain_classic/document_loaders/dataframe.py
   906	libs/langchain/langchain_classic/document_loaders/diffbot.py
   907	libs/langchain/langchain_classic/document_loaders/directory.py
   908	libs/langchain/langchain_classic/document_loaders/discord.py
   909	libs/langchain/langchain_classic/document_loaders/docugami.py
   910	libs/langchain/langchain_classic/document_loaders/docusaurus.py
   911	libs/langchain/langchain_classic/document_loaders/dropbox.py
   912	libs/langchain/langchain_classic/document_loaders/duckdb_loader.py
   913	libs/langchain/langchain_classic/document_loaders/email.py
   914	libs/langchain/langchain_classic/document_loaders/epub.py
   915	libs/langchain/langchain_classic/document_loaders/etherscan.py
   916	libs/langchain/langchain_classic/document_loaders/evernote.py
   917	libs/langchain/langchain_classic/document_loaders/excel.py
   918	libs/langchain/langchain_classic/document_loaders/facebook_chat.py
   919	libs/langchain/langchain_classic/document_loaders/fauna.py
   920	libs/langchain/langchain_classic/document_loaders/figma.py
   921	libs/langchain/langchain_classic/document_loaders/gcs_directory.py
   922	libs/langchain/langchain_classic/document_loaders/gcs_file.py
   923	libs/langchain/langchain_classic/document_loaders/generic.py
   924	libs/langchain/langchain_classic/document_loaders/geodataframe.py
   925	libs/langchain/langchain_classic/document_loaders/git.py
   926	libs/langchain/langchain_classic/document_loaders/gitbook.py
   927	libs/langchain/langchain_classic/document_loaders/github.py
   928	libs/langchain/langchain_classic/document_loaders/google_speech_to_text.py
   929	libs/langchain/langchain_classic/document_loaders/googledrive.py
   930	libs/langchain/langchain_classic/document_loaders/gutenberg.py
   931	libs/langchain/langchain_classic/document_loaders/helpers.py
   932	libs/langchain/langchain_classic/document_loaders/hn.py
   933	libs/langchain/langchain_classic/document_loaders/html.py
   934	libs/langchain/langchain_classic/document_loaders/html_bs.py
   935	libs/langchain/langchain_classic/document_loaders/hugging_face_dataset.py
   936	libs/langchain/langchain_classic/document_loaders/ifixit.py
   937	libs/langchain/langchain_classic/document_loaders/image.py
   938	libs/langchain/langchain_classic/document_loaders/image_captions.py
   939	libs/langchain/langchain_classic/document_loaders/imsdb.py
   940	libs/langchain/langchain_classic/document_loaders/iugu.py
   941	libs/langchain/langchain_classic/document_loaders/joplin.py
   942	libs/langchain/langchain_classic/document_loaders/json_loader.py
   943	libs/langchain/langchain_classic/document_loaders/lakefs.py
   944	libs/langchain/langchain_classic/document_loaders/larksuite.py
   945	libs/langchain/langchain_classic/document_loaders/markdown.py
   946	libs/langchain/langchain_classic/document_loaders/mastodon.py
   947	libs/langchain/langchain_classic/document_loaders/max_compute.py
   948	libs/langchain/langchain_classic/document_loaders/mediawikidump.py
   949	libs/langchain/langchain_classic/document_loaders/merge.py
   950	libs/langchain/langchain_classic/document_loaders/mhtml.py
   951	libs/langchain/langchain_classic/document_loaders/modern_treasury.py
   952	libs/langchain/langchain_classic/document_loaders/mongodb.py
   953	libs/langchain/langchain_classic/document_loaders/news.py
   954	libs/langchain/langchain_classic/document_loaders/notebook.py
   955	libs/langchain/langchain_classic/document_loaders/notion.py
   956	libs/langchain/langchain_classic/document_loaders/notiondb.py
   957	libs/langchain/langchain_classic/document_loaders/nuclia.py
   958	libs/langchain/langchain_classic/document_loaders/obs_directory.py
   959	libs/langchain/langchain_classic/document_loaders/obs_file.py
   960	libs/langchain/langchain_classic/document_loaders/obsidian.py
   961	libs/langchain/langchain_classic/document_loaders/odt.py
   962	libs/langchain/langchain_classic/document_loaders/onedrive.py
   963	libs/langchain/langchain_classic/document_loaders/onedrive_file.py
   964	libs/langchain/langchain_classic/document_loaders/onenote.py
   965	libs/langchain/langchain_classic/document_loaders/open_city_data.py
   966	libs/langchain/langchain_classic/document_loaders/org_mode.py
   967	libs/langchain/langchain_classic/document_loaders/parsers/__init__.py
   968	libs/langchain/langchain_classic/document_loaders/parsers/audio.py
   969	libs/langchain/langchain_classic/document_loaders/parsers/docai.py
   970	libs/langchain/langchain_classic/document_loaders/parsers/generic.py
   971	libs/langchain/langchain_classic/document_loaders/parsers/grobid.py
   972	libs/langchain/langchain_classic/document_loaders/parsers/html/__init__.py
   973	libs/langchain/langchain_classic/document_loaders/parsers/html/bs4.py
   974	libs/langchain/langchain_classic/document_loaders/parsers/language/__init__.py
   975	libs/langchain/langchain_classic/document_loaders/parsers/language/cobol.py
   976	libs/langchain/langchain_classic/document_loaders/parsers/language/code_segmenter.py
   977	libs/langchain/langchain_classic/document_loaders/parsers/language/javascript.py
   978	libs/langchain/langchain_classic/document_loaders/parsers/language/language_parser.py
   979	libs/langchain/langchain_classic/document_loaders/parsers/language/python.py
   980	libs/langchain/langchain_classic/document_loaders/parsers/msword.py
   981	libs/langchain/langchain_classic/document_loaders/parsers/pdf.py
   982	libs/langchain/langchain_classic/document_loaders/parsers/registry.py
   983	libs/langchain/langchain_classic/document_loaders/parsers/txt.py
   984	libs/langchain/langchain_classic/document_loaders/pdf.py
   985	libs/langchain/langchain_classic/document_loaders/polars_dataframe.py
   986	libs/langchain/langchain_classic/document_loaders/powerpoint.py
   987	libs/langchain/langchain_classic/document_loaders/psychic.py
   988	libs/langchain/langchain_classic/document_loaders/pubmed.py
   989	libs/langchain/langchain_classic/document_loaders/pyspark_dataframe.py
   990	libs/langchain/langchain_classic/document_loaders/python.py
   991	libs/langchain/langchain_classic/document_loaders/quip.py
   992	libs/langchain/langchain_classic/document_loaders/readthedocs.py
   993	libs/langchain/langchain_classic/document_loaders/recursive_url_loader.py
   994	libs/langchain/langchain_classic/document_loaders/reddit.py
   995	libs/langchain/langchain_classic/document_loaders/roam.py
   996	libs/langchain/langchain_classic/document_loaders/rocksetdb.py
   997	libs/langchain/langchain_classic/document_loaders/rspace.py
   998	libs/langchain/langchain_classic/document_loaders/rss.py
   999	libs/langchain/langchain_classic/document_loaders/rst.py
  1000	libs/langchain/langchain_classic/document_loaders/rtf.py
  1001	libs/langchain/langchain_classic/document_loaders/s3_directory.py
  1002	libs/langchain/langchain_classic/document_loaders/s3_file.py
  1003	libs/langchain/langchain_classic/document_loaders/sharepoint.py
  1004	libs/langchain/langchain_classic/document_loaders/sitemap.py
  1005	libs/langchain/langchain_classic/document_loaders/slack_directory.py
  1006	libs/langchain/langchain_classic/document_loaders/snowflake_loader.py
  1007	libs/langchain/langchain_classic/document_loaders/spreedly.py
  1008	libs/langchain/langchain_classic/document_loaders/srt.py
  1009	libs/langchain/langchain_classic/document_loaders/stripe.py
  1010	libs/langchain/langchain_classic/document_loaders/telegram.py
  1011	libs/langchain/langchain_classic/document_loaders/tencent_cos_directory.py
  1012	libs/langchain/langchain_classic/document_loaders/tencent_cos_file.py
  1013	libs/langchain/langchain_classic/document_loaders/tensorflow_datasets.py
  1014	libs/langchain/langchain_classic/document_loaders/text.py
  1015	libs/langchain/langchain_classic/document_loaders/tomarkdown.py
  1016	libs/langchain/langchain_classic/document_loaders/toml.py
  1017	libs/langchain/langchain_classic/document_loaders/trello.py
  1018	libs/langchain/langchain_classic/document_loaders/tsv.py
  1019	libs/langchain/langchain_classic/document_loaders/twitter.py
  1020	libs/langchain/langchain_classic/document_loaders/unstructured.py
  1021	libs/langchain/langchain_classic/document_loaders/url.py
  1022	libs/langchain/langchain_classic/document_loaders/url_playwright.py
  1023	libs/langchain/langchain_classic/document_loaders/url_selenium.py
  1024	libs/langchain/langchain_classic/document_loaders/weather.py
  1025	libs/langchain/langchain_classic/document_loaders/web_base.py
  1026	libs/langchain/langchain_classic/document_loaders/whatsapp_chat.py
  1027	libs/langchain/langchain_classic/document_loaders/wikipedia.py
  1028	libs/langchain/langchain_classic/document_loaders/word_document.py
  1029	libs/langchain/langchain_classic/document_loaders/xml.py
  1030	libs/langchain/langchain_classic/document_loaders/xorbits.py
  1031	libs/langchain/langchain_classic/document_loaders/youtube.py
  1032	libs/langchain/langchain_classic/document_transformers/__init__.py
  1033	libs/langchain/langchain_classic/document_transformers/beautiful_soup_transformer.py
  1034	libs/langchain/langchain_classic/document_transformers/doctran_text_extract.py
  1035	libs/langchain/langchain_classic/document_transformers/doctran_text_qa.py
  1036	libs/langchain/langchain_classic/document_transformers/doctran_text_translate.py
  1037	libs/langchain/langchain_classic/document_transformers/embeddings_redundant_filter.py
  1038	libs/langchain/langchain_classic/document_transformers/google_translate.py
  1039	libs/langchain/langchain_classic/document_transformers/html2text.py
  1040	libs/langchain/langchain_classic/document_transformers/long_context_reorder.py
  1041	libs/langchain/langchain_classic/document_transformers/nuclia_text_transform.py
  1042	libs/langchain/langchain_classic/document_transformers/openai_functions.py
  1043	libs/langchain/langchain_classic/document_transformers/xsl/html_chunks_with_headers.xslt
  1044	libs/langchain/langchain_classic/embeddings/__init__.py
  1045	libs/langchain/langchain_classic/embeddings/aleph_alpha.py
  1046	libs/langchain/langchain_classic/embeddings/awa.py
  1047	libs/langchain/langchain_classic/embeddings/azure_openai.py
  1048	libs/langchain/langchain_classic/embeddings/baidu_qianfan_endpoint.py
  1049	libs/langchain/langchain_classic/embeddings/base.py
  1050	libs/langchain/langchain_classic/embeddings/bedrock.py
  1051	libs/langchain/langchain_classic/embeddings/bookend.py
  1052	libs/langchain/langchain_classic/embeddings/cache.py
  1053	libs/langchain/langchain_classic/embeddings/clarifai.py
  1054	libs/langchain/langchain_classic/embeddings/cloudflare_workersai.py
  1055	libs/langchain/langchain_classic/embeddings/cohere.py
  1056	libs/langchain/langchain_classic/embeddings/dashscope.py
  1057	libs/langchain/langchain_classic/embeddings/databricks.py
  1058	libs/langchain/langchain_classic/embeddings/deepinfra.py
  1059	libs/langchain/langchain_classic/embeddings/edenai.py
  1060	libs/langchain/langchain_classic/embeddings/elasticsearch.py
  1061	libs/langchain/langchain_classic/embeddings/embaas.py
  1062	libs/langchain/langchain_classic/embeddings/ernie.py
  1063	libs/langchain/langchain_classic/embeddings/fake.py
  1064	libs/langchain/langchain_classic/embeddings/fastembed.py
  1065	libs/langchain/langchain_classic/embeddings/google_palm.py
  1066	libs/langchain/langchain_classic/embeddings/gpt4all.py
  1067	libs/langchain/langchain_classic/embeddings/gradient_ai.py
  1068	libs/langchain/langchain_classic/embeddings/huggingface.py
  1069	libs/langchain/langchain_classic/embeddings/huggingface_hub.py
  1070	libs/langchain/langchain_classic/embeddings/infinity.py
  1071	libs/langchain/langchain_classic/embeddings/javelin_ai_gateway.py
  1072	libs/langchain/langchain_classic/embeddings/jina.py
  1073	libs/langchain/langchain_classic/embeddings/johnsnowlabs.py
  1074	libs/langchain/langchain_classic/embeddings/llamacpp.py
  1075	libs/langchain/langchain_classic/embeddings/llm_rails.py
  1076	libs/langchain/langchain_classic/embeddings/localai.py
  1077	libs/langchain/langchain_classic/embeddings/minimax.py
  1078	libs/langchain/langchain_classic/embeddings/mlflow.py
  1079	libs/langchain/langchain_classic/embeddings/mlflow_gateway.py
  1080	libs/langchain/langchain_classic/embeddings/modelscope_hub.py
  1081	libs/langchain/langchain_classic/embeddings/mosaicml.py
  1082	libs/langchain/langchain_classic/embeddings/nlpcloud.py
  1083	libs/langchain/langchain_classic/embeddings/octoai_embeddings.py
  1084	libs/langchain/langchain_classic/embeddings/ollama.py
  1085	libs/langchain/langchain_classic/embeddings/openai.py
  1086	libs/langchain/langchain_classic/embeddings/sagemaker_endpoint.py
  1087	libs/langchain/langchain_classic/embeddings/self_hosted.py
  1088	libs/langchain/langchain_classic/embeddings/self_hosted_hugging_face.py
  1089	libs/langchain/langchain_classic/embeddings/sentence_transformer.py
  1090	libs/langchain/langchain_classic/embeddings/spacy_embeddings.py
  1091	libs/langchain/langchain_classic/embeddings/tensorflow_hub.py
  1092	libs/langchain/langchain_classic/embeddings/vertexai.py
  1093	libs/langchain/langchain_classic/embeddings/voyageai.py
  1094	libs/langchain/langchain_classic/embeddings/xinference.py
  1095	libs/langchain/langchain_classic/env.py
  1096	libs/langchain/langchain_classic/evaluation/__init__.py
  1097	libs/langchain/langchain_classic/evaluation/agents/__init__.py
  1098	libs/langchain/langchain_classic/evaluation/agents/trajectory_eval_chain.py
  1099	libs/langchain/langchain_classic/evaluation/agents/trajectory_eval_prompt.py
  1100	libs/langchain/langchain_classic/evaluation/comparison/__init__.py
  1101	libs/langchain/langchain_classic/evaluation/comparison/eval_chain.py
  1102	libs/langchain/langchain_classic/evaluation/comparison/prompt.py
  1103	libs/langchain/langchain_classic/evaluation/criteria/__init__.py
  1104	libs/langchain/langchain_classic/evaluation/criteria/eval_chain.py
  1105	libs/langchain/langchain_classic/evaluation/criteria/prompt.py
  1106	libs/langchain/langchain_classic/evaluation/embedding_distance/__init__.py
  1107	libs/langchain/langchain_classic/evaluation/embedding_distance/base.py
  1108	libs/langchain/langchain_classic/evaluation/exact_match/__init__.py
  1109	libs/langchain/langchain_classic/evaluation/exact_match/base.py
  1110	libs/langchain/langchain_classic/evaluation/loading.py
  1111	libs/langchain/langchain_classic/evaluation/parsing/__init__.py
  1112	libs/langchain/langchain_classic/evaluation/parsing/base.py
  1113	libs/langchain/langchain_classic/evaluation/parsing/json_distance.py
  1114	libs/langchain/langchain_classic/evaluation/parsing/json_schema.py
  1115	libs/langchain/langchain_classic/evaluation/qa/__init__.py
  1116	libs/langchain/langchain_classic/evaluation/qa/eval_chain.py
  1117	libs/langchain/langchain_classic/evaluation/qa/eval_prompt.py
  1118	libs/langchain/langchain_classic/evaluation/qa/generate_chain.py
  1119	libs/langchain/langchain_classic/evaluation/qa/generate_prompt.py
  1120	libs/langchain/langchain_classic/evaluation/regex_match/__init__.py
  1121	libs/langchain/langchain_classic/evaluation/regex_match/base.py
  1122	libs/langchain/langchain_classic/evaluation/schema.py
  1123	libs/langchain/langchain_classic/evaluation/scoring/__init__.py
  1124	libs/langchain/langchain_classic/evaluation/scoring/eval_chain.py
  1125	libs/langchain/langchain_classic/evaluation/scoring/prompt.py
  1126	libs/langchain/langchain_classic/evaluation/string_distance/__init__.py
  1127	libs/langchain/langchain_classic/evaluation/string_distance/base.py
  1128	libs/langchain/langchain_classic/example_generator.py
  1129	libs/langchain/langchain_classic/formatting.py
  1130	libs/langchain/langchain_classic/globals.py
  1131	libs/langchain/langchain_classic/graphs/__init__.py
  1132	libs/langchain/langchain_classic/graphs/arangodb_graph.py
  1133	libs/langchain/langchain_classic/graphs/falkordb_graph.py
  1134	libs/langchain/langchain_classic/graphs/graph_document.py
  1135	libs/langchain/langchain_classic/graphs/graph_store.py
  1136	libs/langchain/langchain_classic/graphs/hugegraph.py
  1137	libs/langchain/langchain_classic/graphs/kuzu_graph.py
  1138	libs/langchain/langchain_classic/graphs/memgraph_graph.py
  1139	libs/langchain/langchain_classic/graphs/nebula_graph.py
  1140	libs/langchain/langchain_classic/graphs/neo4j_graph.py
  1141	libs/langchain/langchain_classic/graphs/neptune_graph.py
  1142	libs/langchain/langchain_classic/graphs/networkx_graph.py
  1143	libs/langchain/langchain_classic/graphs/rdf_graph.py
  1144	libs/langchain/langchain_classic/hub.py
  1145	libs/langchain/langchain_classic/indexes/__init__.py
  1146	libs/langchain/langchain_classic/indexes/_api.py
  1147	libs/langchain/langchain_classic/indexes/_sql_record_manager.py
  1148	libs/langchain/langchain_classic/indexes/graph.py
  1149	libs/langchain/langchain_classic/indexes/prompts/__init__.py
  1150	libs/langchain/langchain_classic/indexes/prompts/entity_extraction.py
  1151	libs/langchain/langchain_classic/indexes/prompts/entity_summarization.py
  1152	libs/langchain/langchain_classic/indexes/prompts/knowledge_triplet_extraction.py
  1153	libs/langchain/langchain_classic/indexes/vectorstore.py
  1154	libs/langchain/langchain_classic/input.py
  1155	libs/langchain/langchain_classic/llms/__init__.py
  1156	libs/langchain/langchain_classic/llms/ai21.py
  1157	libs/langchain/langchain_classic/llms/aleph_alpha.py
  1158	libs/langchain/langchain_classic/llms/amazon_api_gateway.py
  1159	libs/langchain/langchain_classic/llms/anthropic.py
  1160	libs/langchain/langchain_classic/llms/anyscale.py
  1161	libs/langchain/langchain_classic/llms/arcee.py
  1162	libs/langchain/langchain_classic/llms/aviary.py
  1163	libs/langchain/langchain_classic/llms/azureml_endpoint.py
  1164	libs/langchain/langchain_classic/llms/baidu_qianfan_endpoint.py
  1165	libs/langchain/langchain_classic/llms/bananadev.py
  1166	libs/langchain/langchain_classic/llms/base.py
  1167	libs/langchain/langchain_classic/llms/baseten.py
  1168	libs/langchain/langchain_classic/llms/beam.py
  1169	libs/langchain/langchain_classic/llms/bedrock.py
  1170	libs/langchain/langchain_classic/llms/bittensor.py
  1171	libs/langchain/langchain_classic/llms/cerebriumai.py
  1172	libs/langchain/langchain_classic/llms/chatglm.py
  1173	libs/langchain/langchain_classic/llms/clarifai.py
  1174	libs/langchain/langchain_classic/llms/cloudflare_workersai.py
  1175	libs/langchain/langchain_classic/llms/cohere.py
  1176	libs/langchain/langchain_classic/llms/ctransformers.py
  1177	libs/langchain/langchain_classic/llms/ctranslate2.py
  1178	libs/langchain/langchain_classic/llms/databricks.py
  1179	libs/langchain/langchain_classic/llms/deepinfra.py
  1180	libs/langchain/langchain_classic/llms/deepsparse.py
  1181	libs/langchain/langchain_classic/llms/edenai.py
  1182	libs/langchain/langchain_classic/llms/fake.py
  1183	libs/langchain/langchain_classic/llms/fireworks.py
  1184	libs/langchain/langchain_classic/llms/forefrontai.py
  1185	libs/langchain/langchain_classic/llms/gigachat.py
  1186	libs/langchain/langchain_classic/llms/google_palm.py
  1187	libs/langchain/langchain_classic/llms/gooseai.py
  1188	libs/langchain/langchain_classic/llms/gpt4all.py
  1189	libs/langchain/langchain_classic/llms/gradient_ai.py
  1190	libs/langchain/langchain_classic/llms/grammars/json.gbnf
  1191	libs/langchain/langchain_classic/llms/grammars/list.gbnf
  1192	libs/langchain/langchain_classic/llms/huggingface_endpoint.py
  1193	libs/langchain/langchain_classic/llms/huggingface_hub.py
  1194	libs/langchain/langchain_classic/llms/huggingface_pipeline.py
  1195	libs/langchain/langchain_classic/llms/huggingface_text_gen_inference.py
  1196	libs/langchain/langchain_classic/llms/human.py
  1197	libs/langchain/langchain_classic/llms/javelin_ai_gateway.py
  1198	libs/langchain/langchain_classic/llms/koboldai.py
  1199	libs/langchain/langchain_classic/llms/llamacpp.py
  1200	libs/langchain/langchain_classic/llms/loading.py
  1201	libs/langchain/langchain_classic/llms/manifest.py
  1202	libs/langchain/langchain_classic/llms/minimax.py
  1203	libs/langchain/langchain_classic/llms/mlflow.py
  1204	libs/langchain/langchain_classic/llms/mlflow_ai_gateway.py
  1205	libs/langchain/langchain_classic/llms/modal.py
  1206	libs/langchain/langchain_classic/llms/mosaicml.py
  1207	libs/langchain/langchain_classic/llms/nlpcloud.py
  1208	libs/langchain/langchain_classic/llms/octoai_endpoint.py
  1209	libs/langchain/langchain_classic/llms/ollama.py
  1210	libs/langchain/langchain_classic/llms/opaqueprompts.py
  1211	libs/langchain/langchain_classic/llms/openai.py
  1212	libs/langchain/langchain_classic/llms/openllm.py
  1213	libs/langchain/langchain_classic/llms/openlm.py
  1214	libs/langchain/langchain_classic/llms/pai_eas_endpoint.py
  1215	libs/langchain/langchain_classic/llms/petals.py
  1216	libs/langchain/langchain_classic/llms/pipelineai.py
  1217	libs/langchain/langchain_classic/llms/predibase.py
  1218	libs/langchain/langchain_classic/llms/predictionguard.py
  1219	libs/langchain/langchain_classic/llms/promptlayer_openai.py
  1220	libs/langchain/langchain_classic/llms/replicate.py
  1221	libs/langchain/langchain_classic/llms/rwkv.py
  1222	libs/langchain/langchain_classic/llms/sagemaker_endpoint.py
  1223	libs/langchain/langchain_classic/llms/self_hosted.py
  1224	libs/langchain/langchain_classic/llms/self_hosted_hugging_face.py
  1225	libs/langchain/langchain_classic/llms/stochasticai.py
  1226	libs/langchain/langchain_classic/llms/symblai_nebula.py
  1227	libs/langchain/langchain_classic/llms/textgen.py
  1228	libs/langchain/langchain_classic/llms/titan_takeoff.py
  1229	libs/langchain/langchain_classic/llms/titan_takeoff_pro.py
  1230	libs/langchain/langchain_classic/llms/together.py
  1231	libs/langchain/langchain_classic/llms/tongyi.py
  1232	libs/langchain/langchain_classic/llms/utils.py
  1233	libs/langchain/langchain_classic/llms/vertexai.py
  1234	libs/langchain/langchain_classic/llms/vllm.py
  1235	libs/langchain/langchain_classic/llms/volcengine_maas.py
  1236	libs/langchain/langchain_classic/llms/watsonxllm.py
  1237	libs/langchain/langchain_classic/llms/writer.py
  1238	libs/langchain/langchain_classic/llms/xinference.py
  1239	libs/langchain/langchain_classic/llms/yandex.py
  1240	libs/langchain/langchain_classic/load/__init__.py
  1241	libs/langchain/langchain_classic/load/dump.py
  1242	libs/langchain/langchain_classic/load/load.py
  1243	libs/langchain/langchain_classic/load/serializable.py
  1244	libs/langchain/langchain_classic/memory/__init__.py
  1245	libs/langchain/langchain_classic/memory/buffer.py
  1246	libs/langchain/langchain_classic/memory/buffer_window.py
  1247	libs/langchain/langchain_classic/memory/chat_memory.py
  1248	libs/langchain/langchain_classic/memory/chat_message_histories/__init__.py
  1249	libs/langchain/langchain_classic/memory/chat_message_histories/astradb.py
  1250	libs/langchain/langchain_classic/memory/chat_message_histories/cassandra.py
  1251	libs/langchain/langchain_classic/memory/chat_message_histories/cosmos_db.py
  1252	libs/langchain/langchain_classic/memory/chat_message_histories/dynamodb.py
  1253	libs/langchain/langchain_classic/memory/chat_message_histories/elasticsearch.py
  1254	libs/langchain/langchain_classic/memory/chat_message_histories/file.py
  1255	libs/langchain/langchain_classic/memory/chat_message_histories/firestore.py
  1256	libs/langchain/langchain_classic/memory/chat_message_histories/in_memory.py
  1257	libs/langchain/langchain_classic/memory/chat_message_histories/momento.py
  1258	libs/langchain/langchain_classic/memory/chat_message_histories/mongodb.py
  1259	libs/langchain/langchain_classic/memory/chat_message_histories/neo4j.py
  1260	libs/langchain/langchain_classic/memory/chat_message_histories/postgres.py
  1261	libs/langchain/langchain_classic/memory/chat_message_histories/redis.py
  1262	libs/langchain/langchain_classic/memory/chat_message_histories/rocksetdb.py
  1263	libs/langchain/langchain_classic/memory/chat_message_histories/singlestoredb.py
  1264	libs/langchain/langchain_classic/memory/chat_message_histories/sql.py
  1265	libs/langchain/langchain_classic/memory/chat_message_histories/streamlit.py
  1266	libs/langchain/langchain_classic/memory/chat_message_histories/upstash_redis.py
  1267	libs/langchain/langchain_classic/memory/chat_message_histories/xata.py
  1268	libs/langchain/langchain_classic/memory/chat_message_histories/zep.py
  1269	libs/langchain/langchain_classic/memory/combined.py
  1270	libs/langchain/langchain_classic/memory/entity.py
  1271	libs/langchain/langchain_classic/memory/kg.py
  1272	libs/langchain/langchain_classic/memory/motorhead_memory.py
  1273	libs/langchain/langchain_classic/memory/prompt.py
  1274	libs/langchain/langchain_classic/memory/readonly.py
  1275	libs/langchain/langchain_classic/memory/simple.py
  1276	libs/langchain/langchain_classic/memory/summary.py
  1277	libs/langchain/langchain_classic/memory/summary_buffer.py
  1278	libs/langchain/langchain_classic/memory/token_buffer.py
  1279	libs/langchain/langchain_classic/memory/utils.py
  1280	libs/langchain/langchain_classic/memory/vectorstore.py
  1281	libs/langchain/langchain_classic/memory/vectorstore_token_buffer_memory.py
  1282	libs/langchain/langchain_classic/memory/zep_memory.py
  1283	libs/langchain/langchain_classic/model_laboratory.py
  1284	libs/langchain/langchain_classic/output_parsers/__init__.py
  1285	libs/langchain/langchain_classic/output_parsers/boolean.py
  1286	libs/langchain/langchain_classic/output_parsers/combining.py
  1287	libs/langchain/langchain_classic/output_parsers/datetime.py
  1288	libs/langchain/langchain_classic/output_parsers/enum.py
  1289	libs/langchain/langchain_classic/output_parsers/ernie_functions.py
  1290	libs/langchain/langchain_classic/output_parsers/fix.py
  1291	libs/langchain/langchain_classic/output_parsers/format_instructions.py
  1292	libs/langchain/langchain_classic/output_parsers/json.py
  1293	libs/langchain/langchain_classic/output_parsers/list.py
  1294	libs/langchain/langchain_classic/output_parsers/loading.py
  1295	libs/langchain/langchain_classic/output_parsers/openai_functions.py
  1296	libs/langchain/langchain_classic/output_parsers/openai_tools.py
  1297	libs/langchain/langchain_classic/output_parsers/pandas_dataframe.py
  1298	libs/langchain/langchain_classic/output_parsers/prompts.py
  1299	libs/langchain/langchain_classic/output_parsers/pydantic.py
  1300	libs/langchain/langchain_classic/output_parsers/rail_parser.py
  1301	libs/langchain/langchain_classic/output_parsers/regex.py
  1302	libs/langchain/langchain_classic/output_parsers/regex_dict.py
  1303	libs/langchain/langchain_classic/output_parsers/retry.py
  1304	libs/langchain/langchain_classic/output_parsers/structured.py
  1305	libs/langchain/langchain_classic/output_parsers/xml.py
  1306	libs/langchain/langchain_classic/output_parsers/yaml.py
  1307	libs/langchain/langchain_classic/prompts/__init__.py
  1308	libs/langchain/langchain_classic/prompts/base.py
  1309	libs/langchain/langchain_classic/prompts/chat.py
  1310	libs/langchain/langchain_classic/prompts/example_selector/__init__.py
  1311	libs/langchain/langchain_classic/prompts/example_selector/base.py
  1312	libs/langchain/langchain_classic/prompts/example_selector/length_based.py
  1313	libs/langchain/langchain_classic/prompts/example_selector/ngram_overlap.py
  1314	libs/langchain/langchain_classic/prompts/example_selector/semantic_similarity.py
  1315	libs/langchain/langchain_classic/prompts/few_shot.py
  1316	libs/langchain/langchain_classic/prompts/few_shot_with_templates.py
  1317	libs/langchain/langchain_classic/prompts/loading.py
  1318	libs/langchain/langchain_classic/prompts/prompt.py
  1319	libs/langchain/langchain_classic/py.typed
  1320	libs/langchain/langchain_classic/python.py
  1321	libs/langchain/langchain_classic/requests.py
  1322	libs/langchain/langchain_classic/retrievers/__init__.py
  1323	libs/langchain/langchain_classic/retrievers/arcee.py
  1324	libs/langchain/langchain_classic/retrievers/arxiv.py
  1325	libs/langchain/langchain_classic/retrievers/azure_ai_search.py
  1326	libs/langchain/langchain_classic/retrievers/bedrock.py
  1327	libs/langchain/langchain_classic/retrievers/bm25.py
  1328	libs/langchain/langchain_classic/retrievers/chaindesk.py
  1329	libs/langchain/langchain_classic/retrievers/chatgpt_plugin_retriever.py
  1330	libs/langchain/langchain_classic/retrievers/cohere_rag_retriever.py
  1331	libs/langchain/langchain_classic/retrievers/contextual_compression.py
  1332	libs/langchain/langchain_classic/retrievers/databerry.py
  1333	libs/langchain/langchain_classic/retrievers/docarray.py
  1334	libs/langchain/langchain_classic/retrievers/document_compressors/__init__.py
  1335	libs/langchain/langchain_classic/retrievers/document_compressors/base.py
  1336	libs/langchain/langchain_classic/retrievers/document_compressors/chain_extract.py
  1337	libs/langchain/langchain_classic/retrievers/document_compressors/chain_extract_prompt.py
  1338	libs/langchain/langchain_classic/retrievers/document_compressors/chain_filter.py
  1339	libs/langchain/langchain_classic/retrievers/document_compressors/chain_filter_prompt.py
  1340	libs/langchain/langchain_classic/retrievers/document_compressors/cohere_rerank.py
  1341	libs/langchain/langchain_classic/retrievers/document_compressors/cross_encoder.py
  1342	libs/langchain/langchain_classic/retrievers/document_compressors/cross_encoder_rerank.py
  1343	libs/langchain/langchain_classic/retrievers/document_compressors/embeddings_filter.py
  1344	libs/langchain/langchain_classic/retrievers/document_compressors/flashrank_rerank.py
  1345	libs/langchain/langchain_classic/retrievers/document_compressors/listwise_rerank.py
  1346	libs/langchain/langchain_classic/retrievers/elastic_search_bm25.py
  1347	libs/langchain/langchain_classic/retrievers/embedchain.py
  1348	libs/langchain/langchain_classic/retrievers/ensemble.py
  1349	libs/langchain/langchain_classic/retrievers/google_cloud_documentai_warehouse.py
  1350	libs/langchain/langchain_classic/retrievers/google_vertex_ai_search.py
  1351	libs/langchain/langchain_classic/retrievers/kay.py
  1352	libs/langchain/langchain_classic/retrievers/kendra.py
  1353	libs/langchain/langchain_classic/retrievers/knn.py
  1354	libs/langchain/langchain_classic/retrievers/llama_index.py
  1355	libs/langchain/langchain_classic/retrievers/merger_retriever.py
  1356	libs/langchain/langchain_classic/retrievers/metal.py
  1357	libs/langchain/langchain_classic/retrievers/milvus.py
  1358	libs/langchain/langchain_classic/retrievers/multi_query.py
  1359	libs/langchain/langchain_classic/retrievers/multi_vector.py
  1360	libs/langchain/langchain_classic/retrievers/outline.py
  1361	libs/langchain/langchain_classic/retrievers/parent_document_retriever.py
  1362	libs/langchain/langchain_classic/retrievers/pinecone_hybrid_search.py
  1363	libs/langchain/langchain_classic/retrievers/pubmed.py
  1364	libs/langchain/langchain_classic/retrievers/pupmed.py
  1365	libs/langchain/langchain_classic/retrievers/re_phraser.py
  1366	libs/langchain/langchain_classic/retrievers/remote_retriever.py
  1367	libs/langchain/langchain_classic/retrievers/self_query/__init__.py
  1368	libs/langchain/langchain_classic/retrievers/self_query/astradb.py
  1369	libs/langchain/langchain_classic/retrievers/self_query/base.py
  1370	libs/langchain/langchain_classic/retrievers/self_query/chroma.py
  1371	libs/langchain/langchain_classic/retrievers/self_query/dashvector.py
  1372	libs/langchain/langchain_classic/retrievers/self_query/databricks_vector_search.py
  1373	libs/langchain/langchain_classic/retrievers/self_query/deeplake.py
  1374	libs/langchain/langchain_classic/retrievers/self_query/dingo.py
  1375	libs/langchain/langchain_classic/retrievers/self_query/elasticsearch.py
  1376	libs/langchain/langchain_classic/retrievers/self_query/milvus.py
  1377	libs/langchain/langchain_classic/retrievers/self_query/mongodb_atlas.py
  1378	libs/langchain/langchain_classic/retrievers/self_query/myscale.py
  1379	libs/langchain/langchain_classic/retrievers/self_query/opensearch.py
  1380	libs/langchain/langchain_classic/retrievers/self_query/pgvector.py
  1381	libs/langchain/langchain_classic/retrievers/self_query/pinecone.py
  1382	libs/langchain/langchain_classic/retrievers/self_query/qdrant.py
  1383	libs/langchain/langchain_classic/retrievers/self_query/redis.py
  1384	libs/langchain/langchain_classic/retrievers/self_query/supabase.py
  1385	libs/langchain/langchain_classic/retrievers/self_query/tencentvectordb.py
  1386	libs/langchain/langchain_classic/retrievers/self_query/timescalevector.py
  1387	libs/langchain/langchain_classic/retrievers/self_query/vectara.py
  1388	libs/langchain/langchain_classic/retrievers/self_query/weaviate.py
  1389	libs/langchain/langchain_classic/retrievers/svm.py
  1390	libs/langchain/langchain_classic/retrievers/tavily_search_api.py
  1391	libs/langchain/langchain_classic/retrievers/tfidf.py
  1392	libs/langchain/langchain_classic/retrievers/time_weighted_retriever.py
  1393	libs/langchain/langchain_classic/retrievers/vespa_retriever.py
  1394	libs/langchain/langchain_classic/retrievers/weaviate_hybrid_search.py
  1395	libs/langchain/langchain_classic/retrievers/web_research.py
  1396	libs/langchain/langchain_classic/retrievers/wikipedia.py
  1397	libs/langchain/langchain_classic/retrievers/you.py
  1398	libs/langchain/langchain_classic/retrievers/zep.py
  1399	libs/langchain/langchain_classic/retrievers/zilliz.py
  1400	libs/langchain/langchain_classic/runnables/__init__.py
  1401	libs/langchain/langchain_classic/runnables/hub.py
  1402	libs/langchain/langchain_classic/runnables/openai_functions.py
  1403	libs/langchain/langchain_classic/schema/__init__.py
  1404	libs/langchain/langchain_classic/schema/agent.py
  1405	libs/langchain/langchain_classic/schema/cache.py
  1406	libs/langchain/langchain_classic/schema/callbacks/__init__.py
  1407	libs/langchain/langchain_classic/schema/callbacks/base.py
  1408	libs/langchain/langchain_classic/schema/callbacks/manager.py
  1409	libs/langchain/langchain_classic/schema/callbacks/stdout.py
  1410	libs/langchain/langchain_classic/schema/callbacks/streaming_stdout.py
  1411	libs/langchain/langchain_classic/schema/callbacks/tracers/__init__.py
  1412	libs/langchain/langchain_classic/schema/callbacks/tracers/base.py
  1413	libs/langchain/langchain_classic/schema/callbacks/tracers/evaluation.py
  1414	libs/langchain/langchain_classic/schema/callbacks/tracers/langchain.py
  1415	libs/langchain/langchain_classic/schema/callbacks/tracers/log_stream.py
  1416	libs/langchain/langchain_classic/schema/callbacks/tracers/root_listeners.py
  1417	libs/langchain/langchain_classic/schema/callbacks/tracers/run_collector.py
  1418	libs/langchain/langchain_classic/schema/callbacks/tracers/schemas.py
  1419	libs/langchain/langchain_classic/schema/callbacks/tracers/stdout.py
  1420	libs/langchain/langchain_classic/schema/chat.py
  1421	libs/langchain/langchain_classic/schema/chat_history.py
  1422	libs/langchain/langchain_classic/schema/document.py
  1423	libs/langchain/langchain_classic/schema/embeddings.py
  1424	libs/langchain/langchain_classic/schema/exceptions.py
  1425	libs/langchain/langchain_classic/schema/language_model.py
  1426	libs/langchain/langchain_classic/schema/memory.py
  1427	libs/langchain/langchain_classic/schema/messages.py
  1428	libs/langchain/langchain_classic/schema/output.py
  1429	libs/langchain/langchain_classic/schema/output_parser.py
  1430	libs/langchain/langchain_classic/schema/prompt.py
  1431	libs/langchain/langchain_classic/schema/prompt_template.py
  1432	libs/langchain/langchain_classic/schema/retriever.py
  1433	libs/langchain/langchain_classic/schema/runnable/__init__.py
  1434	libs/langchain/langchain_classic/schema/runnable/base.py
  1435	libs/langchain/langchain_classic/schema/runnable/branch.py
  1436	libs/langchain/langchain_classic/schema/runnable/config.py
  1437	libs/langchain/langchain_classic/schema/runnable/configurable.py
  1438	libs/langchain/langchain_classic/schema/runnable/fallbacks.py
  1439	libs/langchain/langchain_classic/schema/runnable/history.py
  1440	libs/langchain/langchain_classic/schema/runnable/passthrough.py
  1441	libs/langchain/langchain_classic/schema/runnable/retry.py
  1442	libs/langchain/langchain_classic/schema/runnable/router.py
  1443	libs/langchain/langchain_classic/schema/runnable/utils.py
  1444	libs/langchain/langchain_classic/schema/storage.py
  1445	libs/langchain/langchain_classic/schema/vectorstore.py
  1446	libs/langchain/langchain_classic/serpapi.py
  1447	libs/langchain/langchain_classic/smith/__init__.py
  1448	libs/langchain/langchain_classic/smith/evaluation/__init__.py
  1449	libs/langchain/langchain_classic/smith/evaluation/config.py
  1450	libs/langchain/langchain_classic/smith/evaluation/name_generation.py
  1451	libs/langchain/langchain_classic/smith/evaluation/progress.py
  1452	libs/langchain/langchain_classic/smith/evaluation/runner_utils.py
  1453	libs/langchain/langchain_classic/smith/evaluation/string_run_evaluator.py
  1454	libs/langchain/langchain_classic/sql_database.py
  1455	libs/langchain/langchain_classic/storage/__init__.py
  1456	libs/langchain/langchain_classic/storage/_lc_store.py
  1457	libs/langchain/langchain_classic/storage/encoder_backed.py
  1458	libs/langchain/langchain_classic/storage/exceptions.py
  1459	libs/langchain/langchain_classic/storage/file_system.py
  1460	libs/langchain/langchain_classic/storage/in_memory.py
  1461	libs/langchain/langchain_classic/storage/redis.py
  1462	libs/langchain/langchain_classic/storage/upstash_redis.py
  1463	libs/langchain/langchain_classic/text_splitter.py
  1464	libs/langchain/langchain_classic/tools/__init__.py
  1465	libs/langchain/langchain_classic/tools/ainetwork/__init__.py
  1466	libs/langchain/langchain_classic/tools/ainetwork/app.py
  1467	libs/langchain/langchain_classic/tools/ainetwork/base.py
  1468	libs/langchain/langchain_classic/tools/ainetwork/owner.py
  1469	libs/langchain/langchain_classic/tools/ainetwork/rule.py
  1470	libs/langchain/langchain_classic/tools/ainetwork/transfer.py
  1471	libs/langchain/langchain_classic/tools/ainetwork/value.py
  1472	libs/langchain/langchain_classic/tools/amadeus/__init__.py
  1473	libs/langchain/langchain_classic/tools/amadeus/base.py
  1474	libs/langchain/langchain_classic/tools/amadeus/closest_airport.py
  1475	libs/langchain/langchain_classic/tools/amadeus/flight_search.py
  1476	libs/langchain/langchain_classic/tools/arxiv/__init__.py
  1477	libs/langchain/langchain_classic/tools/arxiv/tool.py
  1478	libs/langchain/langchain_classic/tools/azure_cognitive_services/__init__.py
  1479	libs/langchain/langchain_classic/tools/azure_cognitive_services/form_recognizer.py
  1480	libs/langchain/langchain_classic/tools/azure_cognitive_services/image_analysis.py
  1481	libs/langchain/langchain_classic/tools/azure_cognitive_services/speech2text.py
  1482	libs/langchain/langchain_classic/tools/azure_cognitive_services/text2speech.py
  1483	libs/langchain/langchain_classic/tools/azure_cognitive_services/text_analytics_health.py
  1484	libs/langchain/langchain_classic/tools/base.py
  1485	libs/langchain/langchain_classic/tools/bearly/__init__.py
  1486	libs/langchain/langchain_classic/tools/bearly/tool.py
  1487	libs/langchain/langchain_classic/tools/bing_search/__init__.py
  1488	libs/langchain/langchain_classic/tools/bing_search/tool.py
  1489	libs/langchain/langchain_classic/tools/brave_search/__init__.py
  1490	libs/langchain/langchain_classic/tools/brave_search/tool.py
  1491	libs/langchain/langchain_classic/tools/clickup/__init__.py
  1492	libs/langchain/langchain_classic/tools/clickup/tool.py
  1493	libs/langchain/langchain_classic/tools/convert_to_openai.py
  1494	libs/langchain/langchain_classic/tools/dataforseo_api_search/__init__.py
  1495	libs/langchain/langchain_classic/tools/dataforseo_api_search/tool.py
  1496	libs/langchain/langchain_classic/tools/ddg_search/__init__.py
  1497	libs/langchain/langchain_classic/tools/ddg_search/tool.py
  1498	libs/langchain/langchain_classic/tools/e2b_data_analysis/__init__.py
  1499	libs/langchain/langchain_classic/tools/e2b_data_analysis/tool.py
  1500	libs/langchain/langchain_classic/tools/edenai/__init__.py
  1501	libs/langchain/langchain_classic/tools/edenai/audio_speech_to_text.py
  1502	libs/langchain/langchain_classic/tools/edenai/audio_text_to_speech.py
  1503	libs/langchain/langchain_classic/tools/edenai/edenai_base_tool.py
  1504	libs/langchain/langchain_classic/tools/edenai/image_explicitcontent.py
  1505	libs/langchain/langchain_classic/tools/edenai/image_objectdetection.py
  1506	libs/langchain/langchain_classic/tools/edenai/ocr_identityparser.py
  1507	libs/langchain/langchain_classic/tools/edenai/ocr_invoiceparser.py
  1508	libs/langchain/langchain_classic/tools/edenai/text_moderation.py
  1509	libs/langchain/langchain_classic/tools/eleven_labs/__init__.py
  1510	libs/langchain/langchain_classic/tools/eleven_labs/models.py
  1511	libs/langchain/langchain_classic/tools/eleven_labs/text2speech.py
  1512	libs/langchain/langchain_classic/tools/file_management/__init__.py
  1513	libs/langchain/langchain_classic/tools/file_management/copy.py
  1514	libs/langchain/langchain_classic/tools/file_management/delete.py
  1515	libs/langchain/langchain_classic/tools/file_management/file_search.py
  1516	libs/langchain/langchain_classic/tools/file_management/list_dir.py
  1517	libs/langchain/langchain_classic/tools/file_management/move.py
  1518	libs/langchain/langchain_classic/tools/file_management/read.py
  1519	libs/langchain/langchain_classic/tools/file_management/write.py
  1520	libs/langchain/langchain_classic/tools/github/__init__.py
  1521	libs/langchain/langchain_classic/tools/github/tool.py
  1522	libs/langchain/langchain_classic/tools/gitlab/__init__.py
  1523	libs/langchain/langchain_classic/tools/gitlab/tool.py
  1524	libs/langchain/langchain_classic/tools/gmail/__init__.py
  1525	libs/langchain/langchain_classic/tools/gmail/base.py
  1526	libs/langchain/langchain_classic/tools/gmail/create_draft.py
  1527	libs/langchain/langchain_classic/tools/gmail/get_message.py
  1528	libs/langchain/langchain_classic/tools/gmail/get_thread.py
  1529	libs/langchain/langchain_classic/tools/gmail/search.py
  1530	libs/langchain/langchain_classic/tools/gmail/send_message.py
  1531	libs/langchain/langchain_classic/tools/golden_query/__init__.py
  1532	libs/langchain/langchain_classic/tools/golden_query/tool.py
  1533	libs/langchain/langchain_classic/tools/google_cloud/__init__.py
  1534	libs/langchain/langchain_classic/tools/google_cloud/texttospeech.py
  1535	libs/langchain/langchain_classic/tools/google_finance/__init__.py
  1536	libs/langchain/langchain_classic/tools/google_finance/tool.py
  1537	libs/langchain/langchain_classic/tools/google_jobs/__init__.py
  1538	libs/langchain/langchain_classic/tools/google_jobs/tool.py
  1539	libs/langchain/langchain_classic/tools/google_lens/__init__.py
  1540	libs/langchain/langchain_classic/tools/google_lens/tool.py
  1541	libs/langchain/langchain_classic/tools/google_places/__init__.py
  1542	libs/langchain/langchain_classic/tools/google_places/tool.py
  1543	libs/langchain/langchain_classic/tools/google_scholar/__init__.py
  1544	libs/langchain/langchain_classic/tools/google_scholar/tool.py
  1545	libs/langchain/langchain_classic/tools/google_search/__init__.py
  1546	libs/langchain/langchain_classic/tools/google_search/tool.py
  1547	libs/langchain/langchain_classic/tools/google_serper/__init__.py
  1548	libs/langchain/langchain_classic/tools/google_serper/tool.py
  1549	libs/langchain/langchain_classic/tools/google_trends/__init__.py
  1550	libs/langchain/langchain_classic/tools/google_trends/tool.py
  1551	libs/langchain/langchain_classic/tools/graphql/__init__.py
  1552	libs/langchain/langchain_classic/tools/graphql/tool.py
  1553	libs/langchain/langchain_classic/tools/human/__init__.py
  1554	libs/langchain/langchain_classic/tools/human/tool.py
  1555	libs/langchain/langchain_classic/tools/ifttt.py
  1556	libs/langchain/langchain_classic/tools/interaction/__init__.py
  1557	libs/langchain/langchain_classic/tools/interaction/tool.py
  1558	libs/langchain/langchain_classic/tools/jira/__init__.py
  1559	libs/langchain/langchain_classic/tools/jira/tool.py
  1560	libs/langchain/langchain_classic/tools/json/__init__.py
  1561	libs/langchain/langchain_classic/tools/json/tool.py
  1562	libs/langchain/langchain_classic/tools/memorize/__init__.py
  1563	libs/langchain/langchain_classic/tools/memorize/tool.py
  1564	libs/langchain/langchain_classic/tools/merriam_webster/__init__.py
  1565	libs/langchain/langchain_classic/tools/merriam_webster/tool.py
  1566	libs/langchain/langchain_classic/tools/metaphor_search/__init__.py
  1567	libs/langchain/langchain_classic/tools/metaphor_search/tool.py
  1568	libs/langchain/langchain_classic/tools/multion/__init__.py
  1569	libs/langchain/langchain_classic/tools/multion/close_session.py
  1570	libs/langchain/langchain_classic/tools/multion/create_session.py
  1571	libs/langchain/langchain_classic/tools/multion/update_session.py
  1572	libs/langchain/langchain_classic/tools/nasa/__init__.py
  1573	libs/langchain/langchain_classic/tools/nasa/tool.py
  1574	libs/langchain/langchain_classic/tools/nuclia/__init__.py
  1575	libs/langchain/langchain_classic/tools/nuclia/tool.py
  1576	libs/langchain/langchain_classic/tools/office365/__init__.py
  1577	libs/langchain/langchain_classic/tools/office365/base.py
  1578	libs/langchain/langchain_classic/tools/office365/create_draft_message.py
  1579	libs/langchain/langchain_classic/tools/office365/events_search.py
  1580	libs/langchain/langchain_classic/tools/office365/messages_search.py
  1581	libs/langchain/langchain_classic/tools/office365/send_event.py
  1582	libs/langchain/langchain_classic/tools/office365/send_message.py
  1583	libs/langchain/langchain_classic/tools/openapi/__init__.py
  1584	libs/langchain/langchain_classic/tools/openapi/utils/__init__.py
  1585	libs/langchain/langchain_classic/tools/openapi/utils/api_models.py
  1586	libs/langchain/langchain_classic/tools/openapi/utils/openapi_utils.py
  1587	libs/langchain/langchain_classic/tools/openweathermap/__init__.py
  1588	libs/langchain/langchain_classic/tools/openweathermap/tool.py
  1589	libs/langchain/langchain_classic/tools/playwright/__init__.py
  1590	libs/langchain/langchain_classic/tools/playwright/base.py
  1591	libs/langchain/langchain_classic/tools/playwright/click.py
  1592	libs/langchain/langchain_classic/tools/playwright/current_page.py
  1593	libs/langchain/langchain_classic/tools/playwright/extract_hyperlinks.py
  1594	libs/langchain/langchain_classic/tools/playwright/extract_text.py
  1595	libs/langchain/langchain_classic/tools/playwright/get_elements.py
  1596	libs/langchain/langchain_classic/tools/playwright/navigate.py
  1597	libs/langchain/langchain_classic/tools/playwright/navigate_back.py
  1598	libs/langchain/langchain_classic/tools/plugin.py
  1599	libs/langchain/langchain_classic/tools/powerbi/__init__.py
  1600	libs/langchain/langchain_classic/tools/powerbi/tool.py
  1601	libs/langchain/langchain_classic/tools/pubmed/__init__.py
  1602	libs/langchain/langchain_classic/tools/pubmed/tool.py
  1603	libs/langchain/langchain_classic/tools/python/__init__.py
  1604	libs/langchain/langchain_classic/tools/reddit_search/__init__.py
  1605	libs/langchain/langchain_classic/tools/reddit_search/tool.py
  1606	libs/langchain/langchain_classic/tools/render.py
  1607	libs/langchain/langchain_classic/tools/requests/__init__.py
  1608	libs/langchain/langchain_classic/tools/requests/tool.py
  1609	libs/langchain/langchain_classic/tools/retriever.py
  1610	libs/langchain/langchain_classic/tools/scenexplain/__init__.py
  1611	libs/langchain/langchain_classic/tools/scenexplain/tool.py
  1612	libs/langchain/langchain_classic/tools/searchapi/__init__.py
  1613	libs/langchain/langchain_classic/tools/searchapi/tool.py
  1614	libs/langchain/langchain_classic/tools/searx_search/__init__.py
  1615	libs/langchain/langchain_classic/tools/searx_search/tool.py
  1616	libs/langchain/langchain_classic/tools/shell/__init__.py
  1617	libs/langchain/langchain_classic/tools/shell/tool.py
  1618	libs/langchain/langchain_classic/tools/slack/__init__.py
  1619	libs/langchain/langchain_classic/tools/slack/base.py
  1620	libs/langchain/langchain_classic/tools/slack/get_channel.py
  1621	libs/langchain/langchain_classic/tools/slack/get_message.py
  1622	libs/langchain/langchain_classic/tools/slack/schedule_message.py
  1623	libs/langchain/langchain_classic/tools/slack/send_message.py
  1624	libs/langchain/langchain_classic/tools/sleep/__init__.py
  1625	libs/langchain/langchain_classic/tools/sleep/tool.py
  1626	libs/langchain/langchain_classic/tools/spark_sql/__init__.py
  1627	libs/langchain/langchain_classic/tools/spark_sql/tool.py
  1628	libs/langchain/langchain_classic/tools/sql_database/__init__.py
  1629	libs/langchain/langchain_classic/tools/sql_database/prompt.py
  1630	libs/langchain/langchain_classic/tools/sql_database/tool.py
  1631	libs/langchain/langchain_classic/tools/stackexchange/__init__.py
  1632	libs/langchain/langchain_classic/tools/stackexchange/tool.py
  1633	libs/langchain/langchain_classic/tools/steam/__init__.py
  1634	libs/langchain/langchain_classic/tools/steam/tool.py
  1635	libs/langchain/langchain_classic/tools/steamship_image_generation/__init__.py
  1636	libs/langchain/langchain_classic/tools/steamship_image_generation/tool.py
  1637	libs/langchain/langchain_classic/tools/tavily_search/__init__.py
  1638	libs/langchain/langchain_classic/tools/tavily_search/tool.py
  1639	libs/langchain/langchain_classic/tools/vectorstore/__init__.py
  1640	libs/langchain/langchain_classic/tools/vectorstore/tool.py
  1641	libs/langchain/langchain_classic/tools/wikipedia/__init__.py
  1642	libs/langchain/langchain_classic/tools/wikipedia/tool.py
  1643	libs/langchain/langchain_classic/tools/wolfram_alpha/__init__.py
  1644	libs/langchain/langchain_classic/tools/wolfram_alpha/tool.py
  1645	libs/langchain/langchain_classic/tools/yahoo_finance_news.py
  1646	libs/langchain/langchain_classic/tools/youtube/__init__.py
  1647	libs/langchain/langchain_classic/tools/youtube/search.py
  1648	libs/langchain/langchain_classic/tools/zapier/__init__.py
  1649	libs/langchain/langchain_classic/tools/zapier/tool.py
  1650	libs/langchain/langchain_classic/utilities/__init__.py
  1651	libs/langchain/langchain_classic/utilities/alpha_vantage.py
  1652	libs/langchain/langchain_classic/utilities/anthropic.py
  1653	libs/langchain/langchain_classic/utilities/apify.py
  1654	libs/langchain/langchain_classic/utilities/arcee.py
  1655	libs/langchain/langchain_classic/utilities/arxiv.py
  1656	libs/langchain/langchain_classic/utilities/asyncio.py
  1657	libs/langchain/langchain_classic/utilities/awslambda.py
  1658	libs/langchain/langchain_classic/utilities/bibtex.py
  1659	libs/langchain/langchain_classic/utilities/bing_search.py
  1660	libs/langchain/langchain_classic/utilities/brave_search.py
  1661	libs/langchain/langchain_classic/utilities/clickup.py
  1662	libs/langchain/langchain_classic/utilities/dalle_image_generator.py
  1663	libs/langchain/langchain_classic/utilities/dataforseo_api_search.py
  1664	libs/langchain/langchain_classic/utilities/duckduckgo_search.py
  1665	libs/langchain/langchain_classic/utilities/github.py
  1666	libs/langchain/langchain_classic/utilities/gitlab.py
  1667	libs/langchain/langchain_classic/utilities/golden_query.py
  1668	libs/langchain/langchain_classic/utilities/google_finance.py
  1669	libs/langchain/langchain_classic/utilities/google_jobs.py
  1670	libs/langchain/langchain_classic/utilities/google_lens.py
  1671	libs/langchain/langchain_classic/utilities/google_places_api.py
  1672	libs/langchain/langchain_classic/utilities/google_scholar.py
  1673	libs/langchain/langchain_classic/utilities/google_search.py
  1674	libs/langchain/langchain_classic/utilities/google_serper.py
  1675	libs/langchain/langchain_classic/utilities/google_trends.py
  1676	libs/langchain/langchain_classic/utilities/graphql.py
  1677	libs/langchain/langchain_classic/utilities/jira.py
  1678	libs/langchain/langchain_classic/utilities/max_compute.py
  1679	libs/langchain/langchain_classic/utilities/merriam_webster.py
  1680	libs/langchain/langchain_classic/utilities/metaphor_search.py
  1681	libs/langchain/langchain_classic/utilities/nasa.py
  1682	libs/langchain/langchain_classic/utilities/opaqueprompts.py
  1683	libs/langchain/langchain_classic/utilities/openapi.py
  1684	libs/langchain/langchain_classic/utilities/openweathermap.py
  1685	libs/langchain/langchain_classic/utilities/outline.py
  1686	libs/langchain/langchain_classic/utilities/portkey.py
  1687	libs/langchain/langchain_classic/utilities/powerbi.py
  1688	libs/langchain/langchain_classic/utilities/pubmed.py
  1689	libs/langchain/langchain_classic/utilities/python.py
  1690	libs/langchain/langchain_classic/utilities/reddit_search.py
  1691	libs/langchain/langchain_classic/utilities/redis.py
  1692	libs/langchain/langchain_classic/utilities/requests.py
  1693	libs/langchain/langchain_classic/utilities/scenexplain.py
  1694	libs/langchain/langchain_classic/utilities/searchapi.py
  1695	libs/langchain/langchain_classic/utilities/searx_search.py
  1696	libs/langchain/langchain_classic/utilities/serpapi.py
  1697	libs/langchain/langchain_classic/utilities/spark_sql.py
  1698	libs/langchain/langchain_classic/utilities/sql_database.py
  1699	libs/langchain/langchain_classic/utilities/stackexchange.py
  1700	libs/langchain/langchain_classic/utilities/steam.py
  1701	libs/langchain/langchain_classic/utilities/tavily_search.py
  1702	libs/langchain/langchain_classic/utilities/tensorflow_datasets.py
  1703	libs/langchain/langchain_classic/utilities/twilio.py
  1704	libs/langchain/langchain_classic/utilities/vertexai.py
  1705	libs/langchain/langchain_classic/utilities/wikipedia.py
  1706	libs/langchain/langchain_classic/utilities/wolfram_alpha.py
  1707	libs/langchain/langchain_classic/utilities/zapier.py
  1708	libs/langchain/langchain_classic/utils/__init__.py
  1709	libs/langchain/langchain_classic/utils/aiter.py
  1710	libs/langchain/langchain_classic/utils/env.py
  1711	libs/langchain/langchain_classic/utils/ernie_functions.py
  1712	libs/langchain/langchain_classic/utils/formatting.py
  1713	libs/langchain/langchain_classic/utils/html.py
  1714	libs/langchain/langchain_classic/utils/input.py
  1715	libs/langchain/langchain_classic/utils/iter.py
  1716	libs/langchain/langchain_classic/utils/json_schema.py
  1717	libs/langchain/langchain_classic/utils/math.py
  1718	libs/langchain/langchain_classic/utils/openai.py
  1719	libs/langchain/langchain_classic/utils/openai_functions.py
  1720	libs/langchain/langchain_classic/utils/pydantic.py
  1721	libs/langchain/langchain_classic/utils/strings.py
  1722	libs/langchain/langchain_classic/utils/utils.py
  1723	libs/langchain/langchain_classic/vectorstores/__init__.py
  1724	libs/langchain/langchain_classic/vectorstores/alibabacloud_opensearch.py
  1725	libs/langchain/langchain_classic/vectorstores/analyticdb.py
  1726	libs/langchain/langchain_classic/vectorstores/annoy.py
  1727	libs/langchain/langchain_classic/vectorstores/astradb.py
  1728	libs/langchain/langchain_classic/vectorstores/atlas.py
  1729	libs/langchain/langchain_classic/vectorstores/awadb.py
  1730	libs/langchain/langchain_classic/vectorstores/azure_cosmos_db.py
  1731	libs/langchain/langchain_classic/vectorstores/azuresearch.py
  1732	libs/langchain/langchain_classic/vectorstores/bageldb.py
  1733	libs/langchain/langchain_classic/vectorstores/baiducloud_vector_search.py
  1734	libs/langchain/langchain_classic/vectorstores/base.py
  1735	libs/langchain/langchain_classic/vectorstores/cassandra.py
  1736	libs/langchain/langchain_classic/vectorstores/chroma.py
  1737	libs/langchain/langchain_classic/vectorstores/clarifai.py
  1738	libs/langchain/langchain_classic/vectorstores/clickhouse.py
  1739	libs/langchain/langchain_classic/vectorstores/dashvector.py
  1740	libs/langchain/langchain_classic/vectorstores/databricks_vector_search.py
  1741	libs/langchain/langchain_classic/vectorstores/deeplake.py
  1742	libs/langchain/langchain_classic/vectorstores/dingo.py
  1743	libs/langchain/langchain_classic/vectorstores/docarray/__init__.py
  1744	libs/langchain/langchain_classic/vectorstores/docarray/base.py
  1745	libs/langchain/langchain_classic/vectorstores/docarray/hnsw.py
  1746	libs/langchain/langchain_classic/vectorstores/docarray/in_memory.py
  1747	libs/langchain/langchain_classic/vectorstores/elastic_vector_search.py
  1748	libs/langchain/langchain_classic/vectorstores/elasticsearch.py
  1749	libs/langchain/langchain_classic/vectorstores/epsilla.py
  1750	libs/langchain/langchain_classic/vectorstores/faiss.py
  1751	libs/langchain/langchain_classic/vectorstores/hippo.py
  1752	libs/langchain/langchain_classic/vectorstores/hologres.py
  1753	libs/langchain/langchain_classic/vectorstores/lancedb.py
  1754	libs/langchain/langchain_classic/vectorstores/llm_rails.py
  1755	libs/langchain/langchain_classic/vectorstores/marqo.py
  1756	libs/langchain/langchain_classic/vectorstores/matching_engine.py
  1757	libs/langchain/langchain_classic/vectorstores/meilisearch.py
  1758	libs/langchain/langchain_classic/vectorstores/milvus.py
  1759	libs/langchain/langchain_classic/vectorstores/momento_vector_index.py
  1760	libs/langchain/langchain_classic/vectorstores/mongodb_atlas.py
  1761	libs/langchain/langchain_classic/vectorstores/myscale.py
  1762	libs/langchain/langchain_classic/vectorstores/neo4j_vector.py
  1763	libs/langchain/langchain_classic/vectorstores/nucliadb.py
  1764	libs/langchain/langchain_classic/vectorstores/opensearch_vector_search.py
  1765	libs/langchain/langchain_classic/vectorstores/pgembedding.py
  1766	libs/langchain/langchain_classic/vectorstores/pgvecto_rs.py
  1767	libs/langchain/langchain_classic/vectorstores/pgvector.py
  1768	libs/langchain/langchain_classic/vectorstores/pinecone.py
  1769	libs/langchain/langchain_classic/vectorstores/qdrant.py
  1770	libs/langchain/langchain_classic/vectorstores/redis/__init__.py
  1771	libs/langchain/langchain_classic/vectorstores/redis/base.py
  1772	libs/langchain/langchain_classic/vectorstores/redis/filters.py
  1773	libs/langchain/langchain_classic/vectorstores/redis/schema.py
  1774	libs/langchain/langchain_classic/vectorstores/rocksetdb.py
  1775	libs/langchain/langchain_classic/vectorstores/scann.py
  1776	libs/langchain/langchain_classic/vectorstores/semadb.py
  1777	libs/langchain/langchain_classic/vectorstores/singlestoredb.py
  1778	libs/langchain/langchain_classic/vectorstores/sklearn.py
  1779	libs/langchain/langchain_classic/vectorstores/sqlitevss.py
  1780	libs/langchain/langchain_classic/vectorstores/starrocks.py
  1781	libs/langchain/langchain_classic/vectorstores/supabase.py
  1782	libs/langchain/langchain_classic/vectorstores/tair.py
  1783	libs/langchain/langchain_classic/vectorstores/tencentvectordb.py
  1784	libs/langchain/langchain_classic/vectorstores/tiledb.py
  1785	libs/langchain/langchain_classic/vectorstores/timescalevector.py
  1786	libs/langchain/langchain_classic/vectorstores/typesense.py
  1787	libs/langchain/langchain_classic/vectorstores/usearch.py
  1788	libs/langchain/langchain_classic/vectorstores/utils.py
  1789	libs/langchain/langchain_classic/vectorstores/vald.py
  1790	libs/langchain/langchain_classic/vectorstores/vearch.py
  1791	libs/langchain/langchain_classic/vectorstores/vectara.py
  1792	libs/langchain/langchain_classic/vectorstores/vespa.py
  1793	libs/langchain/langchain_classic/vectorstores/weaviate.py
  1794	libs/langchain/langchain_classic/vectorstores/xata.py
  1795	libs/langchain/langchain_classic/vectorstores/yellowbrick.py
  1796	libs/langchain/langchain_classic/vectorstores/zep.py
  1797	libs/langchain/langchain_classic/vectorstores/zilliz.py
  1798	libs/langchain/pyproject.toml
  1799	libs/langchain/scripts/check_imports.py
  1800	libs/langchain/scripts/lint_imports.sh
  1801	libs/langchain/tests/__init__.py
  1802	libs/langchain/tests/data.py
  1803	libs/langchain/tests/integration_tests/.env.example
  1804	libs/langchain/tests/integration_tests/__init__.py
  1805	libs/langchain/tests/integration_tests/cache/__init__.py
  1806	libs/langchain/tests/integration_tests/cache/fake_embeddings.py
  1807	libs/langchain/tests/integration_tests/chains/__init__.py
  1808	libs/langchain/tests/integration_tests/chains/openai_functions/__init__.py
  1809	libs/langchain/tests/integration_tests/chains/openai_functions/test_openapi.py
  1810	libs/langchain/tests/integration_tests/chat_models/__init__.py
  1811	libs/langchain/tests/integration_tests/chat_models/test_base.py
  1812	libs/langchain/tests/integration_tests/conftest.py
  1813	libs/langchain/tests/integration_tests/embeddings/__init__.py
  1814	libs/langchain/tests/integration_tests/embeddings/test_base.py
  1815	libs/langchain/tests/integration_tests/evaluation/__init__.py
  1816	libs/langchain/tests/integration_tests/evaluation/embedding_distance/__init__.py
  1817	libs/langchain/tests/integration_tests/evaluation/embedding_distance/test_embedding.py
  1818	libs/langchain/tests/integration_tests/examples/README.org
  1819	libs/langchain/tests/integration_tests/examples/README.rst
  1820	libs/langchain/tests/integration_tests/examples/brandfetch-brandfetch-2.0.0-resolved.json
  1821	libs/langchain/tests/integration_tests/examples/default-encoding.py
  1822	libs/langchain/tests/integration_tests/examples/duplicate-chars.pdf
  1823	libs/langchain/tests/integration_tests/examples/example-utf8.html
  1824	libs/langchain/tests/integration_tests/examples/example.html
  1825	libs/langchain/tests/integration_tests/examples/example.json
  1826	libs/langchain/tests/integration_tests/examples/example.mht
  1827	libs/langchain/tests/integration_tests/examples/facebook_chat.json
  1828	libs/langchain/tests/integration_tests/examples/factbook.xml
  1829	libs/langchain/tests/integration_tests/examples/fake-email-attachment.eml
  1830	libs/langchain/tests/integration_tests/examples/fake.odt
  1831	libs/langchain/tests/integration_tests/examples/hello.msg
  1832	libs/langchain/tests/integration_tests/examples/hello.pdf
  1833	libs/langchain/tests/integration_tests/examples/hello_world.js
  1834	libs/langchain/tests/integration_tests/examples/hello_world.py
  1835	libs/langchain/tests/integration_tests/examples/layout-parser-paper.pdf
  1836	libs/langchain/tests/integration_tests/examples/multi-page-forms-sample-2-page.pdf
  1837	libs/langchain/tests/integration_tests/examples/non-utf8-encoding.py
  1838	libs/langchain/tests/integration_tests/examples/sample_rss_feeds.opml
  1839	libs/langchain/tests/integration_tests/examples/sitemap.xml
  1840	libs/langchain/tests/integration_tests/examples/slack_export.zip
  1841	libs/langchain/tests/integration_tests/examples/stanley-cups.csv
  1842	libs/langchain/tests/integration_tests/examples/stanley-cups.tsv
  1843	libs/langchain/tests/integration_tests/examples/stanley-cups.xlsx
  1844	libs/langchain/tests/integration_tests/examples/whatsapp_chat.txt
  1845	libs/langchain/tests/integration_tests/memory/__init__.py
  1846	libs/langchain/tests/integration_tests/memory/docker-compose/elasticsearch.yml
  1847	libs/langchain/tests/integration_tests/prompts/__init__.py
  1848	libs/langchain/tests/integration_tests/retrievers/document_compressors/__init__.py
  1849	libs/langchain/tests/integration_tests/retrievers/document_compressors/test_cohere_reranker.py
  1850	libs/langchain/tests/integration_tests/retrievers/document_compressors/test_listwise_rerank.py
  1851	libs/langchain/tests/integration_tests/test_compile.py
  1852	libs/langchain/tests/integration_tests/test_hub.py
  1853	libs/langchain/tests/integration_tests/test_schema.py
  1854	libs/langchain/tests/mock_servers/__init__.py
  1855	libs/langchain/tests/mock_servers/robot/__init__.py
  1856	libs/langchain/tests/mock_servers/robot/server.py
  1857	libs/langchain/tests/unit_tests/__init__.py
  1858	libs/langchain/tests/unit_tests/_api/__init__.py
  1859	libs/langchain/tests/unit_tests/_api/test_importing.py
  1860	libs/langchain/tests/unit_tests/agents/__init__.py
  1861	libs/langchain/tests/unit_tests/agents/agent_toolkits/__init__.py
  1862	libs/langchain/tests/unit_tests/agents/agent_toolkits/test_imports.py
  1863	libs/langchain/tests/unit_tests/agents/format_scratchpad/__init__.py
  1864	libs/langchain/tests/unit_tests/agents/format_scratchpad/test_log.py
  1865	libs/langchain/tests/unit_tests/agents/format_scratchpad/test_log_to_messages.py
  1866	libs/langchain/tests/unit_tests/agents/format_scratchpad/test_openai_functions.py
  1867	libs/langchain/tests/unit_tests/agents/format_scratchpad/test_openai_tools.py
  1868	libs/langchain/tests/unit_tests/agents/format_scratchpad/test_xml.py
  1869	libs/langchain/tests/unit_tests/agents/output_parsers/__init__.py
  1870	libs/langchain/tests/unit_tests/agents/output_parsers/test_convo_output_parser.py
  1871	libs/langchain/tests/unit_tests/agents/output_parsers/test_json.py
  1872	libs/langchain/tests/unit_tests/agents/output_parsers/test_openai_functions.py
  1873	libs/langchain/tests/unit_tests/agents/output_parsers/test_react_json_single_input.py
  1874	libs/langchain/tests/unit_tests/agents/output_parsers/test_react_single_input.py
  1875	libs/langchain/tests/unit_tests/agents/output_parsers/test_self_ask.py
  1876	libs/langchain/tests/unit_tests/agents/output_parsers/test_xml.py
  1877	libs/langchain/tests/unit_tests/agents/test_agent.py
  1878	libs/langchain/tests/unit_tests/agents/test_agent_async.py
  1879	libs/langchain/tests/unit_tests/agents/test_agent_iterator.py
  1880	libs/langchain/tests/unit_tests/agents/test_chat.py
  1881	libs/langchain/tests/unit_tests/agents/test_imports.py
  1882	libs/langchain/tests/unit_tests/agents/test_initialize.py
  1883	libs/langchain/tests/unit_tests/agents/test_mrkl.py
  1884	libs/langchain/tests/unit_tests/agents/test_mrkl_output_parser.py
  1885	libs/langchain/tests/unit_tests/agents/test_openai_assistant.py
  1886	libs/langchain/tests/unit_tests/agents/test_openai_functions_multi.py
  1887	libs/langchain/tests/unit_tests/agents/test_public_api.py
  1888	libs/langchain/tests/unit_tests/agents/test_structured_chat.py
  1889	libs/langchain/tests/unit_tests/agents/test_types.py
  1890	libs/langchain/tests/unit_tests/callbacks/__init__.py
  1891	libs/langchain/tests/unit_tests/callbacks/fake_callback_handler.py
  1892	libs/langchain/tests/unit_tests/callbacks/test_base.py
  1893	libs/langchain/tests/unit_tests/callbacks/test_file.py
  1894	libs/langchain/tests/unit_tests/callbacks/test_imports.py
  1895	libs/langchain/tests/unit_tests/callbacks/test_manager.py
  1896	libs/langchain/tests/unit_tests/callbacks/test_stdout.py
  1897	libs/langchain/tests/unit_tests/callbacks/tracers/__init__.py
  1898	libs/langchain/tests/unit_tests/callbacks/tracers/test_logging.py
  1899	libs/langchain/tests/unit_tests/chains/__init__.py
  1900	libs/langchain/tests/unit_tests/chains/query_constructor/__init__.py
  1901	libs/langchain/tests/unit_tests/chains/query_constructor/test_parser.py
  1902	libs/langchain/tests/unit_tests/chains/question_answering/__init__.py
  1903	libs/langchain/tests/unit_tests/chains/question_answering/test_map_rerank_prompt.py
  1904	libs/langchain/tests/unit_tests/chains/test_base.py
  1905	libs/langchain/tests/unit_tests/chains/test_combine_documents.py
  1906	libs/langchain/tests/unit_tests/chains/test_constitutional_ai.py
  1907	libs/langchain/tests/unit_tests/chains/test_conversation.py
  1908	libs/langchain/tests/unit_tests/chains/test_conversation_retrieval.py
  1909	libs/langchain/tests/unit_tests/chains/test_flare.py
  1910	libs/langchain/tests/unit_tests/chains/test_history_aware_retriever.py
  1911	libs/langchain/tests/unit_tests/chains/test_hyde.py
  1912	libs/langchain/tests/unit_tests/chains/test_imports.py
  1913	libs/langchain/tests/unit_tests/chains/test_llm_checker.py
  1914	libs/langchain/tests/unit_tests/chains/test_llm_math.py
  1915	libs/langchain/tests/unit_tests/chains/test_llm_summarization_checker.py
  1916	libs/langchain/tests/unit_tests/chains/test_memory.py
  1917	libs/langchain/tests/unit_tests/chains/test_qa_with_sources.py
  1918	libs/langchain/tests/unit_tests/chains/test_retrieval.py
  1919	libs/langchain/tests/unit_tests/chains/test_sequential.py
  1920	libs/langchain/tests/unit_tests/chains/test_summary_buffer_memory.py
  1921	libs/langchain/tests/unit_tests/chains/test_transform.py
  1922	libs/langchain/tests/unit_tests/chat_models/__init__.py
  1923	libs/langchain/tests/unit_tests/chat_models/test_base.py
  1924	libs/langchain/tests/unit_tests/chat_models/test_imports.py
  1925	libs/langchain/tests/unit_tests/conftest.py
  1926	libs/langchain/tests/unit_tests/data/prompt_file.txt
  1927	libs/langchain/tests/unit_tests/data/prompts/prompt_extra_args.json
  1928	libs/langchain/tests/unit_tests/data/prompts/prompt_missing_args.json
  1929	libs/langchain/tests/unit_tests/data/prompts/simple_prompt.json
  1930	libs/langchain/tests/unit_tests/docstore/__init__.py
  1931	libs/langchain/tests/unit_tests/docstore/test_imports.py
  1932	libs/langchain/tests/unit_tests/document_loaders/__init__.py
  1933	libs/langchain/tests/unit_tests/document_loaders/blob_loaders/__init__.py
  1934	libs/langchain/tests/unit_tests/document_loaders/blob_loaders/test_public_api.py
  1935	libs/langchain/tests/unit_tests/document_loaders/parsers/__init__.py
  1936	libs/langchain/tests/unit_tests/document_loaders/parsers/test_public_api.py
  1937	libs/langchain/tests/unit_tests/document_loaders/test_base.py
  1938	libs/langchain/tests/unit_tests/document_loaders/test_imports.py
  1939	libs/langchain/tests/unit_tests/document_transformers/__init__.py
  1940	libs/langchain/tests/unit_tests/document_transformers/test_imports.py
  1941	libs/langchain/tests/unit_tests/embeddings/__init__.py
  1942	libs/langchain/tests/unit_tests/embeddings/test_base.py
  1943	libs/langchain/tests/unit_tests/embeddings/test_caching.py
  1944	libs/langchain/tests/unit_tests/embeddings/test_imports.py
  1945	libs/langchain/tests/unit_tests/evaluation/__init__.py
  1946	libs/langchain/tests/unit_tests/evaluation/agents/__init__.py
  1947	libs/langchain/tests/unit_tests/evaluation/agents/test_eval_chain.py
  1948	libs/langchain/tests/unit_tests/evaluation/comparison/__init__.py
  1949	libs/langchain/tests/unit_tests/evaluation/comparison/test_eval_chain.py
  1950	libs/langchain/tests/unit_tests/evaluation/criteria/__init__.py
  1951	libs/langchain/tests/unit_tests/evaluation/criteria/test_eval_chain.py
  1952	libs/langchain/tests/unit_tests/evaluation/exact_match/__init__.py
  1953	libs/langchain/tests/unit_tests/evaluation/exact_match/test_base.py
  1954	libs/langchain/tests/unit_tests/evaluation/parsing/__init__.py
  1955	libs/langchain/tests/unit_tests/evaluation/parsing/test_base.py
  1956	libs/langchain/tests/unit_tests/evaluation/parsing/test_json_distance.py
  1957	libs/langchain/tests/unit_tests/evaluation/parsing/test_json_schema.py
  1958	libs/langchain/tests/unit_tests/evaluation/qa/__init__.py
  1959	libs/langchain/tests/unit_tests/evaluation/qa/test_eval_chain.py
  1960	libs/langchain/tests/unit_tests/evaluation/regex_match/__init__.py
  1961	libs/langchain/tests/unit_tests/evaluation/regex_match/test_base.py
  1962	libs/langchain/tests/unit_tests/evaluation/run_evaluators/__init__.py
  1963	libs/langchain/tests/unit_tests/evaluation/scoring/__init__.py
  1964	libs/langchain/tests/unit_tests/evaluation/scoring/test_eval_chain.py
  1965	libs/langchain/tests/unit_tests/evaluation/string_distance/__init__.py
  1966	libs/langchain/tests/unit_tests/evaluation/string_distance/test_base.py
  1967	libs/langchain/tests/unit_tests/evaluation/test_imports.py
  1968	libs/langchain/tests/unit_tests/examples/example-non-utf8.csv
  1969	libs/langchain/tests/unit_tests/examples/example-non-utf8.txt
  1970	libs/langchain/tests/unit_tests/examples/example-utf8.csv
  1971	libs/langchain/tests/unit_tests/examples/example-utf8.txt
  1972	libs/langchain/tests/unit_tests/examples/test_specs/apis-guru/apispec.json
  1973	libs/langchain/tests/unit_tests/examples/test_specs/biztoc/apispec.json
  1974	libs/langchain/tests/unit_tests/examples/test_specs/calculator/apispec.json
  1975	libs/langchain/tests/unit_tests/examples/test_specs/datasette/apispec.json
  1976	libs/langchain/tests/unit_tests/examples/test_specs/freetv-app/apispec.json
  1977	libs/langchain/tests/unit_tests/examples/test_specs/joinmilo/apispec.json
  1978	libs/langchain/tests/unit_tests/examples/test_specs/klarna/apispec.json
  1979	libs/langchain/tests/unit_tests/examples/test_specs/milo/apispec.json
  1980	libs/langchain/tests/unit_tests/examples/test_specs/quickchart/apispec.json
  1981	libs/langchain/tests/unit_tests/examples/test_specs/robot/apispec.yaml
  1982	libs/langchain/tests/unit_tests/examples/test_specs/robot_openapi.yaml
  1983	libs/langchain/tests/unit_tests/examples/test_specs/schooldigger/apispec.json
  1984	libs/langchain/tests/unit_tests/examples/test_specs/shop/apispec.json
  1985	libs/langchain/tests/unit_tests/examples/test_specs/slack/apispec.json
  1986	libs/langchain/tests/unit_tests/examples/test_specs/speak/apispec.json
  1987	libs/langchain/tests/unit_tests/examples/test_specs/urlbox/apispec.json
  1988	libs/langchain/tests/unit_tests/examples/test_specs/wellknown/apispec.json
  1989	libs/langchain/tests/unit_tests/examples/test_specs/wolframalpha/apispec.json
  1990	libs/langchain/tests/unit_tests/examples/test_specs/wolframcloud/apispec.json
  1991	libs/langchain/tests/unit_tests/examples/test_specs/zapier/apispec.json
  1992	libs/langchain/tests/unit_tests/graphs/__init__.py
  1993	libs/langchain/tests/unit_tests/graphs/test_imports.py
  1994	libs/langchain/tests/unit_tests/indexes/__init__.py
  1995	libs/langchain/tests/unit_tests/indexes/test_api.py
  1996	libs/langchain/tests/unit_tests/indexes/test_imports.py
  1997	libs/langchain/tests/unit_tests/indexes/test_indexing.py
  1998	libs/langchain/tests/unit_tests/llms/__init__.py
  1999	libs/langchain/tests/unit_tests/llms/fake_chat_model.py
  2000	libs/langchain/tests/unit_tests/llms/fake_llm.py
  2001	libs/langchain/tests/unit_tests/llms/test_base.py
  2002	libs/langchain/tests/unit_tests/llms/test_fake_chat_model.py
  2003	libs/langchain/tests/unit_tests/llms/test_imports.py
  2004	libs/langchain/tests/unit_tests/load/__init__.py
  2005	libs/langchain/tests/unit_tests/load/__snapshots__/test_dump.ambr
  2006	libs/langchain/tests/unit_tests/load/test_dump.py
  2007	libs/langchain/tests/unit_tests/load/test_imports.py
  2008	libs/langchain/tests/unit_tests/load/test_load.py
  2009	libs/langchain/tests/unit_tests/memory/__init__.py
  2010	libs/langchain/tests/unit_tests/memory/chat_message_histories/__init__.py
  2011	libs/langchain/tests/unit_tests/memory/chat_message_histories/test_imports.py
  2012	libs/langchain/tests/unit_tests/memory/test_combined_memory.py
  2013	libs/langchain/tests/unit_tests/memory/test_imports.py
  2014	libs/langchain/tests/unit_tests/output_parsers/__init__.py
  2015	libs/langchain/tests/unit_tests/output_parsers/test_boolean_parser.py
  2016	libs/langchain/tests/unit_tests/output_parsers/test_combining_parser.py
  2017	libs/langchain/tests/unit_tests/output_parsers/test_datetime_parser.py
  2018	libs/langchain/tests/unit_tests/output_parsers/test_enum_parser.py
  2019	libs/langchain/tests/unit_tests/output_parsers/test_fix.py
  2020	libs/langchain/tests/unit_tests/output_parsers/test_imports.py
  2021	libs/langchain/tests/unit_tests/output_parsers/test_json.py
  2022	libs/langchain/tests/unit_tests/output_parsers/test_pandas_dataframe_parser.py
  2023	libs/langchain/tests/unit_tests/output_parsers/test_regex.py
  2024	libs/langchain/tests/unit_tests/output_parsers/test_regex_dict.py
  2025	libs/langchain/tests/unit_tests/output_parsers/test_retry.py
  2026	libs/langchain/tests/unit_tests/output_parsers/test_structured_parser.py
  2027	libs/langchain/tests/unit_tests/output_parsers/test_yaml_parser.py
  2028	libs/langchain/tests/unit_tests/prompts/__init__.py
  2029	libs/langchain/tests/unit_tests/prompts/test_base.py
  2030	libs/langchain/tests/unit_tests/prompts/test_chat.py
  2031	libs/langchain/tests/unit_tests/prompts/test_few_shot.py
  2032	libs/langchain/tests/unit_tests/prompts/test_few_shot_with_templates.py
  2033	libs/langchain/tests/unit_tests/prompts/test_imports.py
  2034	libs/langchain/tests/unit_tests/prompts/test_loading.py
  2035	libs/langchain/tests/unit_tests/prompts/test_prompt.py
  2036	libs/langchain/tests/unit_tests/retrievers/__init__.py
  2037	libs/langchain/tests/unit_tests/retrievers/document_compressors/__init__.py
  2038	libs/langchain/tests/unit_tests/retrievers/document_compressors/test_chain_extract.py
  2039	libs/langchain/tests/unit_tests/retrievers/document_compressors/test_chain_filter.py
  2040	libs/langchain/tests/unit_tests/retrievers/document_compressors/test_listwise_rerank.py
  2041	libs/langchain/tests/unit_tests/retrievers/parrot_retriever.py
  2042	libs/langchain/tests/unit_tests/retrievers/self_query/__init__.py
  2043	libs/langchain/tests/unit_tests/retrievers/self_query/test_base.py
  2044	libs/langchain/tests/unit_tests/retrievers/sequential_retriever.py
  2045	libs/langchain/tests/unit_tests/retrievers/test_ensemble.py
  2046	libs/langchain/tests/unit_tests/retrievers/test_imports.py
  2047	libs/langchain/tests/unit_tests/retrievers/test_multi_query.py
  2048	libs/langchain/tests/unit_tests/retrievers/test_multi_vector.py
  2049	libs/langchain/tests/unit_tests/retrievers/test_parent_document.py
  2050	libs/langchain/tests/unit_tests/retrievers/test_time_weighted_retriever.py
  2051	libs/langchain/tests/unit_tests/runnables/__init__.py
  2052	libs/langchain/tests/unit_tests/runnables/__snapshots__/test_openai_functions.ambr
  2053	libs/langchain/tests/unit_tests/runnables/test_hub.py
  2054	libs/langchain/tests/unit_tests/runnables/test_openai_functions.py
  2055	libs/langchain/tests/unit_tests/schema/__init__.py
  2056	libs/langchain/tests/unit_tests/schema/runnable/__init__.py
  2057	libs/langchain/tests/unit_tests/schema/runnable/test_base.py
  2058	libs/langchain/tests/unit_tests/schema/runnable/test_branch.py
  2059	libs/langchain/tests/unit_tests/schema/runnable/test_config.py
  2060	libs/langchain/tests/unit_tests/schema/runnable/test_configurable.py
  2061	libs/langchain/tests/unit_tests/schema/runnable/test_fallbacks.py
  2062	libs/langchain/tests/unit_tests/schema/runnable/test_history.py
  2063	libs/langchain/tests/unit_tests/schema/runnable/test_imports.py
  2064	libs/langchain/tests/unit_tests/schema/runnable/test_passthrough.py
  2065	libs/langchain/tests/unit_tests/schema/runnable/test_retry.py
  2066	libs/langchain/tests/unit_tests/schema/runnable/test_router.py
  2067	libs/langchain/tests/unit_tests/schema/runnable/test_utils.py
  2068	libs/langchain/tests/unit_tests/schema/test_agent.py
  2069	libs/langchain/tests/unit_tests/schema/test_cache.py
  2070	libs/langchain/tests/unit_tests/schema/test_chat.py
  2071	libs/langchain/tests/unit_tests/schema/test_chat_history.py
  2072	libs/langchain/tests/unit_tests/schema/test_document.py
  2073	libs/langchain/tests/unit_tests/schema/test_embeddings.py
  2074	libs/langchain/tests/unit_tests/schema/test_exceptions.py
  2075	libs/langchain/tests/unit_tests/schema/test_imports.py
  2076	libs/langchain/tests/unit_tests/schema/test_language_model.py
  2077	libs/langchain/tests/unit_tests/schema/test_memory.py
  2078	libs/langchain/tests/unit_tests/schema/test_messages.py
  2079	libs/langchain/tests/unit_tests/schema/test_output.py
  2080	libs/langchain/tests/unit_tests/schema/test_output_parser.py
  2081	libs/langchain/tests/unit_tests/schema/test_prompt.py
  2082	libs/langchain/tests/unit_tests/schema/test_prompt_template.py
  2083	libs/langchain/tests/unit_tests/schema/test_retriever.py
  2084	libs/langchain/tests/unit_tests/schema/test_storage.py
  2085	libs/langchain/tests/unit_tests/schema/test_vectorstore.py
  2086	libs/langchain/tests/unit_tests/smith/__init__.py
  2087	libs/langchain/tests/unit_tests/smith/evaluation/__init__.py
  2088	libs/langchain/tests/unit_tests/smith/evaluation/test_runner_utils.py
  2089	libs/langchain/tests/unit_tests/smith/evaluation/test_string_run_evaluator.py
  2090	libs/langchain/tests/unit_tests/smith/test_imports.py
  2091	libs/langchain/tests/unit_tests/storage/__init__.py
  2092	libs/langchain/tests/unit_tests/storage/test_filesystem.py
  2093	libs/langchain/tests/unit_tests/storage/test_imports.py
  2094	libs/langchain/tests/unit_tests/storage/test_lc_store.py
  2095	libs/langchain/tests/unit_tests/stubs.py
  2096	libs/langchain/tests/unit_tests/test_dependencies.py
  2097	libs/langchain/tests/unit_tests/test_formatting.py
  2098	libs/langchain/tests/unit_tests/test_globals.py
  2099	libs/langchain/tests/unit_tests/test_hub.py
  2100	libs/langchain/tests/unit_tests/test_imports.py
  2101	libs/langchain/tests/unit_tests/test_pytest_config.py
  2102	libs/langchain/tests/unit_tests/test_schema.py
  2103	libs/langchain/tests/unit_tests/test_utils.py
  2104	libs/langchain/tests/unit_tests/tools/__init__.py
  2105	libs/langchain/tests/unit_tests/tools/test_base.py
  2106	libs/langchain/tests/unit_tests/tools/test_imports.py
  2107	libs/langchain/tests/unit_tests/tools/test_render.py
  2108	libs/langchain/tests/unit_tests/utilities/__init__.py
  2109	libs/langchain/tests/unit_tests/utilities/test_imports.py
  2110	libs/langchain/tests/unit_tests/utils/__init__.py
  2111	libs/langchain/tests/unit_tests/utils/test_imports.py
  2112	libs/langchain/tests/unit_tests/utils/test_iter.py
  2113	libs/langchain/tests/unit_tests/utils/test_openai_functions.py
  2114	libs/langchain/tests/unit_tests/vectorstores/__init__.py
  2115	libs/langchain/tests/unit_tests/vectorstores/test_public_api.py
  2116	libs/langchain/uv.lock
  2117	libs/langchain_v1/LICENSE
  2118	libs/langchain_v1/Makefile
  2119	libs/langchain_v1/README.md
  2120	libs/langchain_v1/examples/mcp/README.md
  2121	libs/langchain_v1/examples/mcp/_fleet_servers.py
  2122	libs/langchain_v1/examples/mcp/_servers.py
  2123	libs/langchain_v1/examples/mcp/_stdio_server.py
  2124	libs/langchain_v1/examples/mcp/auth.py
  2125	libs/langchain_v1/examples/mcp/auth_bearer.py
  2126	libs/langchain_v1/examples/mcp/auth_oauth.py
  2127	libs/langchain_v1/examples/mcp/destructive_interrupt.py
  2128	libs/langchain_v1/examples/mcp/elicitation.py
  2129	libs/langchain_v1/examples/mcp/graph_factory.py
  2130	libs/langchain_v1/examples/mcp/langgraph.json
  2131	libs/langchain_v1/examples/mcp/multi_server.py
  2132	libs/langchain_v1/examples/mcp/protocol_eras.py
  2133	libs/langchain_v1/examples/mcp/remote_server.py
  2134	libs/langchain_v1/examples/mcp/run_graph_factory_demo.py
  2135	libs/langchain_v1/examples/mcp/tool_errors.py
  2136	libs/langchain_v1/examples/mcp/transports.py
  2137	libs/langchain_v1/extended_testing_deps.txt
  2138	libs/langchain_v1/langchain/__init__.py
  2139	libs/langchain_v1/langchain/agents/__init__.py
  2140	libs/langchain_v1/langchain/agents/_subagent_transformer.py
  2141	libs/langchain_v1/langchain/agents/factory.py
  2142	libs/langchain_v1/langchain/agents/middleware/__init__.py
  2143	libs/langchain_v1/langchain/agents/middleware/_execution.py
  2144	libs/langchain_v1/langchain/agents/middleware/_redaction.py
  2145	libs/langchain_v1/langchain/agents/middleware/_retry.py
  2146	libs/langchain_v1/langchain/agents/middleware/_trace_policy.py
  2147	libs/langchain_v1/langchain/agents/middleware/context_editing.py
  2148	libs/langchain_v1/langchain/agents/middleware/file_search.py
  2149	libs/langchain_v1/langchain/agents/middleware/human_in_the_loop.py
  2150	libs/langchain_v1/langchain/agents/middleware/internal_call_transformer.py
  2151	libs/langchain_v1/langchain/agents/middleware/model_call_limit.py
  2152	libs/langchain_v1/langchain/agents/middleware/model_fallback.py
  2153	libs/langchain_v1/langchain/agents/middleware/model_retry.py
  2154	libs/langchain_v1/langchain/agents/middleware/pii.py
  2155	libs/langchain_v1/langchain/agents/middleware/provider_tool_search.py
  2156	libs/langchain_v1/langchain/agents/middleware/shell_tool.py
  2157	libs/langchain_v1/langchain/agents/middleware/summarization.py
  2158	libs/langchain_v1/langchain/agents/middleware/todo.py
  2159	libs/langchain_v1/langchain/agents/middleware/tool_call_limit.py
  2160	libs/langchain_v1/langchain/agents/middleware/tool_emulator.py
  2161	libs/langchain_v1/langchain/agents/middleware/tool_error.py
  2162	libs/langchain_v1/langchain/agents/middleware/tool_retry.py
  2163	libs/langchain_v1/langchain/agents/middleware/tool_selection.py
  2164	libs/langchain_v1/langchain/agents/middleware/types.py
  2165	libs/langchain_v1/langchain/agents/structured_output.py
  2166	libs/langchain_v1/langchain/chat_models/__init__.py
  2167	libs/langchain_v1/langchain/chat_models/base.py
  2168	libs/langchain_v1/langchain/embeddings/__init__.py
  2169	libs/langchain_v1/langchain/embeddings/base.py
  2170	libs/langchain_v1/langchain/mcp/__init__.py
  2171	libs/langchain_v1/langchain/mcp/adapter.py
  2172	libs/langchain_v1/langchain/mcp/elicitation.py
  2173	libs/langchain_v1/langchain/mcp/tools.py
  2174	libs/langchain_v1/langchain/messages/__init__.py
  2175	libs/langchain_v1/langchain/py.typed
  2176	libs/langchain_v1/langchain/rate_limiters/__init__.py
  2177	libs/langchain_v1/langchain/tools/__init__.py
  2178	libs/langchain_v1/langchain/tools/tool_node.py
  2179	libs/langchain_v1/pyproject.toml
  2180	libs/langchain_v1/scripts/check_imports.py
  2181	libs/langchain_v1/scripts/check_version.py
  2182	libs/langchain_v1/tests/__init__.py
  2183	libs/langchain_v1/tests/benchmarks/__init__.py
  2184	libs/langchain_v1/tests/benchmarks/test_create_agent.py
  2185	libs/langchain_v1/tests/cassettes/test_inference_to_native_output[False].yaml.gz
  2186	libs/langchain_v1/tests/cassettes/test_inference_to_native_output[True].yaml.gz
  2187	libs/langchain_v1/tests/cassettes/test_inference_to_tool_output[False].yaml.gz
  2188	libs/langchain_v1/tests/cassettes/test_inference_to_tool_output[True].yaml.gz
  2189	libs/langchain_v1/tests/cassettes/test_strict_mode[False].yaml.gz
  2190	libs/langchain_v1/tests/cassettes/test_strict_mode[True].yaml.gz
  2191	libs/langchain_v1/tests/integration_tests/__init__.py
  2192	libs/langchain_v1/tests/integration_tests/agents/__init__.py
  2193	libs/langchain_v1/tests/integration_tests/agents/middleware/__init__.py
  2194	libs/langchain_v1/tests/integration_tests/agents/middleware/test_human_in_the_loop_integration.py
  2195	libs/langchain_v1/tests/integration_tests/agents/middleware/test_shell_tool_integration.py
  2196	libs/langchain_v1/tests/integration_tests/cache/__init__.py
  2197	libs/langchain_v1/tests/integration_tests/cache/fake_embeddings.py
  2198	libs/langchain_v1/tests/integration_tests/chat_models/__init__.py
  2199	libs/langchain_v1/tests/integration_tests/chat_models/test_base.py
  2200	libs/langchain_v1/tests/integration_tests/conftest.py
  2201	libs/langchain_v1/tests/integration_tests/embeddings/__init__.py
  2202	libs/langchain_v1/tests/integration_tests/embeddings/test_base.py
  2203	libs/langchain_v1/tests/integration_tests/mcp/__init__.py
  2204	libs/langchain_v1/tests/integration_tests/mcp/test_protocol_eras.py
  2205	libs/langchain_v1/tests/integration_tests/test_compile.py
  2206	libs/langchain_v1/tests/unit_tests/__init__.py
  2207	libs/langchain_v1/tests/unit_tests/agents/__init__.py
  2208	libs/langchain_v1/tests/unit_tests/agents/__snapshots__/test_middleware_agent.ambr
  2209	libs/langchain_v1/tests/unit_tests/agents/__snapshots__/test_middleware_decorators.ambr
  2210	libs/langchain_v1/tests/unit_tests/agents/__snapshots__/test_middleware_framework.ambr
  2211	libs/langchain_v1/tests/unit_tests/agents/__snapshots__/test_return_direct_graph.ambr
  2212	libs/langchain_v1/tests/unit_tests/agents/any_str.py
  2213	libs/langchain_v1/tests/unit_tests/agents/compose-postgres.yml
  2214	libs/langchain_v1/tests/unit_tests/agents/compose-redis.yml
  2215	libs/langchain_v1/tests/unit_tests/agents/conftest.py
  2216	libs/langchain_v1/tests/unit_tests/agents/conftest_checkpointer.py
  2217	libs/langchain_v1/tests/unit_tests/agents/conftest_store.py
  2218	libs/langchain_v1/tests/unit_tests/agents/memory_assert.py
  2219	libs/langchain_v1/tests/unit_tests/agents/messages.py
  2220	libs/langchain_v1/tests/unit_tests/agents/middleware/__init__.py
  2221	libs/langchain_v1/tests/unit_tests/agents/middleware/__snapshots__/test_middleware_decorators.ambr
  2222	libs/langchain_v1/tests/unit_tests/agents/middleware/__snapshots__/test_middleware_diagram.ambr
  2223	libs/langchain_v1/tests/unit_tests/agents/middleware/__snapshots__/test_middleware_framework.ambr
  2224	libs/langchain_v1/tests/unit_tests/agents/middleware/core/__init__.py
  2225	libs/langchain_v1/tests/unit_tests/agents/middleware/core/__snapshots__/test_decorators.ambr
  2226	libs/langchain_v1/tests/unit_tests/agents/middleware/core/__snapshots__/test_diagram.ambr
  2227	libs/langchain_v1/tests/unit_tests/agents/middleware/core/__snapshots__/test_framework.ambr
  2228	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_composition.py
  2229	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_decorators.py
  2230	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_diagram.py
  2231	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_dynamic_tools.py
  2232	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_framework.py
  2233	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_internal_call_transformer.py
  2234	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_overrides.py
  2235	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_sync_async_wrappers.py
  2236	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_tools.py
  2237	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_tracing.py
  2238	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_transformers.py
  2239	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_wrap_model_call.py
  2240	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_wrap_model_call_state_update.py
  2241	libs/langchain_v1/tests/unit_tests/agents/middleware/core/test_wrap_tool_call.py
  2242	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/__init__.py
  2243	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_context_editing.py
  2244	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_file_search.py
  2245	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_human_in_the_loop.py
  2246	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_model_call_limit.py
  2247	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_model_fallback.py
  2248	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_model_retry.py
  2249	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_pii.py
  2250	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_provider_tool_search.py
  2251	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_shell_execution_policies.py
  2252	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_shell_tool.py
  2253	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_structured_output_retry.py
  2254	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_summarization.py
  2255	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_todo.py
  2256	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_tool_call_limit.py
  2257	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_tool_emulator.py
  2258	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_tool_error.py
  2259	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_tool_retry.py
  2260	libs/langchain_v1/tests/unit_tests/agents/middleware/implementations/test_tool_selection.py
  2261	libs/langchain_v1/tests/unit_tests/agents/middleware_typing/__init__.py
  2262	libs/langchain_v1/tests/unit_tests/agents/middleware_typing/test_middleware_backwards_compat.py
  2263	libs/langchain_v1/tests/unit_tests/agents/middleware_typing/test_middleware_type_errors.py
  2264	libs/langchain_v1/tests/unit_tests/agents/middleware_typing/test_middleware_typing.py
  2265	libs/langchain_v1/tests/unit_tests/agents/model.py
  2266	libs/langchain_v1/tests/unit_tests/agents/specifications/responses.json
  2267	libs/langchain_v1/tests/unit_tests/agents/specifications/return_direct.json
  2268	libs/langchain_v1/tests/unit_tests/agents/test_agent_name.py
  2269	libs/langchain_v1/tests/unit_tests/agents/test_agent_streaming.py
  2270	libs/langchain_v1/tests/unit_tests/agents/test_create_agent_tool_validation.py
  2271	libs/langchain_v1/tests/unit_tests/agents/test_fetch_last_ai_and_tool_messages.py
  2272	libs/langchain_v1/tests/unit_tests/agents/test_injected_runtime_create_agent.py
  2273	libs/langchain_v1/tests/unit_tests/agents/test_kwargs_tool_runtime_injection.py
  2274	libs/langchain_v1/tests/unit_tests/agents/test_react_agent.py
  2275	libs/langchain_v1/tests/unit_tests/agents/test_response_format.py
  2276	libs/langchain_v1/tests/unit_tests/agents/test_response_format_integration.py
  2277	libs/langchain_v1/tests/unit_tests/agents/test_responses.py
  2278	libs/langchain_v1/tests/unit_tests/agents/test_responses_spec.py
  2279	libs/langchain_v1/tests/unit_tests/agents/test_return_direct_graph.py
  2280	libs/langchain_v1/tests/unit_tests/agents/test_return_direct_spec.py
  2281	libs/langchain_v1/tests/unit_tests/agents/test_state_schema.py
  2282	libs/langchain_v1/tests/unit_tests/agents/test_subagent_streaming.py
  2283	libs/langchain_v1/tests/unit_tests/agents/test_subagent_transformer.py
  2284	libs/langchain_v1/tests/unit_tests/agents/test_system_message.py
  2285	libs/langchain_v1/tests/unit_tests/agents/utils.py
  2286	libs/langchain_v1/tests/unit_tests/chat_models/__init__.py
  2287	libs/langchain_v1/tests/unit_tests/chat_models/test_chat_models.py
  2288	libs/langchain_v1/tests/unit_tests/conftest.py
  2289	libs/langchain_v1/tests/unit_tests/embeddings/__init__.py
  2290	libs/langchain_v1/tests/unit_tests/embeddings/test_base.py
  2291	libs/langchain_v1/tests/unit_tests/embeddings/test_imports.py
  2292	libs/langchain_v1/tests/unit_tests/mcp/__init__.py
  2293	libs/langchain_v1/tests/unit_tests/mcp/conftest.py
  2294	libs/langchain_v1/tests/unit_tests/mcp/test_adapter.py
  2295	libs/langchain_v1/tests/unit_tests/mcp/test_elicitation.py
  2296	libs/langchain_v1/tests/unit_tests/mcp/test_namespace.py
  2297	libs/langchain_v1/tests/unit_tests/mcp/test_tools.py
  2298	libs/langchain_v1/tests/unit_tests/test_dependencies.py
  2299	libs/langchain_v1/tests/unit_tests/test_imports.py
  2300	libs/langchain_v1/tests/unit_tests/test_pytest_config.py
  2301	libs/langchain_v1/tests/unit_tests/test_version.py
  2302	libs/langchain_v1/tests/unit_tests/tools/__init__.py
  2303	libs/langchain_v1/tests/unit_tests/tools/test_imports.py
  2304	libs/langchain_v1/uv.lock
  2305	libs/model-profiles/LICENSE
  2306	libs/model-profiles/Makefile
  2307	libs/model-profiles/README.md
  2308	libs/model-profiles/extended_testing_deps.txt
  2309	libs/model-profiles/langchain_model_profiles/__init__.py
  2310	libs/model-profiles/langchain_model_profiles/_summary.py
  2311	libs/model-profiles/langchain_model_profiles/cli.py
  2312	libs/model-profiles/pyproject.toml
  2313	libs/model-profiles/scripts/lint_imports.sh
  2314	libs/model-profiles/tests/__init__.py
  2315	libs/model-profiles/tests/integration_tests/__init__.py
  2316	libs/model-profiles/tests/integration_tests/test_compile.py
  2317	libs/model-profiles/tests/unit_tests/__init__.py
  2318	libs/model-profiles/tests/unit_tests/test_cli.py
  2319	libs/model-profiles/tests/unit_tests/test_summary.py
  2320	libs/model-profiles/uv.lock
  2321	libs/partners/Makefile
  2322	libs/partners/README.md
  2323	libs/partners/anthropic/.gitignore
  2324	libs/partners/anthropic/LICENSE
  2325	libs/partners/anthropic/Makefile
  2326	libs/partners/anthropic/README.md
  2327	libs/partners/anthropic/langchain_anthropic/__init__.py
  2328	libs/partners/anthropic/langchain_anthropic/_client_utils.py
  2329	libs/partners/anthropic/langchain_anthropic/_compat.py
  2330	libs/partners/anthropic/langchain_anthropic/_sdk_compat.py
  2331	libs/partners/anthropic/langchain_anthropic/_version.py
  2332	libs/partners/anthropic/langchain_anthropic/chat_models.py
  2333	libs/partners/anthropic/langchain_anthropic/data/__init__.py
  2334	libs/partners/anthropic/langchain_anthropic/data/_profiles.py
  2335	libs/partners/anthropic/langchain_anthropic/data/profile_augmentations.toml
  2336	libs/partners/anthropic/langchain_anthropic/experimental.py
  2337	libs/partners/anthropic/langchain_anthropic/llms.py
  2338	libs/partners/anthropic/langchain_anthropic/middleware/__init__.py
  2339	libs/partners/anthropic/langchain_anthropic/middleware/anthropic_tools.py
  2340	libs/partners/anthropic/langchain_anthropic/middleware/bash.py
  2341	libs/partners/anthropic/langchain_anthropic/middleware/file_search.py
  2342	libs/partners/anthropic/langchain_anthropic/middleware/prompt_caching.py
  2343	libs/partners/anthropic/langchain_anthropic/output_parsers.py
  2344	libs/partners/anthropic/langchain_anthropic/py.typed
  2345	libs/partners/anthropic/pyproject.toml
  2346	libs/partners/anthropic/scripts/check_imports.py
  2347	libs/partners/anthropic/scripts/check_version.py
  2348	libs/partners/anthropic/scripts/lint_imports.sh
  2349	libs/partners/anthropic/tests/__init__.py
  2350	libs/partners/anthropic/tests/cassettes/TestAnthropicStandard.test_stream_time.yaml.gz
  2351	libs/partners/anthropic/tests/cassettes/test_agent_loop.yaml.gz
  2352	libs/partners/anthropic/tests/cassettes/test_agent_loop_streaming.yaml.gz
  2353	libs/partners/anthropic/tests/cassettes/test_citations.yaml.gz
  2354	libs/partners/anthropic/tests/cassettes/test_code_execution.yaml.gz
  2355	libs/partners/anthropic/tests/cassettes/test_code_execution_old.yaml.gz
  2356	libs/partners/anthropic/tests/cassettes/test_compaction.yaml.gz
  2357	libs/partners/anthropic/tests/cassettes/test_compaction_streaming.yaml.gz
  2358	libs/partners/anthropic/tests/cassettes/test_context_management.yaml.gz
  2359	libs/partners/anthropic/tests/cassettes/test_programmatic_tool_use.yaml.gz
  2360	libs/partners/anthropic/tests/cassettes/test_programmatic_tool_use_streaming.yaml.gz
  2361	libs/partners/anthropic/tests/cassettes/test_redacted_thinking.yaml.gz
  2362	libs/partners/anthropic/tests/cassettes/test_remote_mcp.yaml.gz
  2363	libs/partners/anthropic/tests/cassettes/test_response_format_in_agent.yaml.gz
  2364	libs/partners/anthropic/tests/cassettes/test_search_result_tool_message.yaml.gz
  2365	libs/partners/anthropic/tests/cassettes/test_skills.yaml.gz
  2366	libs/partners/anthropic/tests/cassettes/test_streaming_tool_call_v1_v2_parity.yaml.gz
  2367	libs/partners/anthropic/tests/cassettes/test_strict_tool_use.yaml.gz
  2368	libs/partners/anthropic/tests/cassettes/test_thinking.yaml.gz
  2369	libs/partners/anthropic/tests/cassettes/test_tool_search.yaml.gz
  2370	libs/partners/anthropic/tests/cassettes/test_web_fetch.yaml.gz
  2371	libs/partners/anthropic/tests/cassettes/test_web_fetch_v1.yaml.gz
  2372	libs/partners/anthropic/tests/cassettes/test_web_search.yaml.gz
  2373	libs/partners/anthropic/tests/conftest.py
  2374	libs/partners/anthropic/tests/integration_tests/__init__.py
  2375	libs/partners/anthropic/tests/integration_tests/test_chat_models.py
  2376	libs/partners/anthropic/tests/integration_tests/test_compile.py
  2377	libs/partners/anthropic/tests/integration_tests/test_llms.py
  2378	libs/partners/anthropic/tests/integration_tests/test_standard.py
  2379	libs/partners/anthropic/tests/unit_tests/__init__.py
  2380	libs/partners/anthropic/tests/unit_tests/__snapshots__/test_standard.ambr
  2381	libs/partners/anthropic/tests/unit_tests/_httpx_compat.py
  2382	libs/partners/anthropic/tests/unit_tests/_utils.py
  2383	libs/partners/anthropic/tests/unit_tests/middleware/__init__.py
  2384	libs/partners/anthropic/tests/unit_tests/middleware/test_anthropic_tools.py
  2385	libs/partners/anthropic/tests/unit_tests/middleware/test_bash.py
  2386	libs/partners/anthropic/tests/unit_tests/middleware/test_file_search.py
  2387	libs/partners/anthropic/tests/unit_tests/middleware/test_prompt_caching.py
  2388	libs/partners/anthropic/tests/unit_tests/test_chat_models.py
  2389	libs/partners/anthropic/tests/unit_tests/test_client_utils.py
  2390	libs/partners/anthropic/tests/unit_tests/test_compat.py
  2391	libs/partners/anthropic/tests/unit_tests/test_imports.py
  2392	libs/partners/anthropic/tests/unit_tests/test_llms.py
  2393	libs/partners/anthropic/tests/unit_tests/test_output_parsers.py
  2394	libs/partners/anthropic/tests/unit_tests/test_sdk_compat.py
  2395	libs/partners/anthropic/tests/unit_tests/test_standard.py
  2396	libs/partners/anthropic/uv.lock
  2397	libs/partners/chroma/.gitignore
  2398	libs/partners/chroma/LICENSE
  2399	libs/partners/chroma/Makefile
  2400	libs/partners/chroma/README.md
  2401	libs/partners/chroma/langchain_chroma/__init__.py
  2402	libs/partners/chroma/langchain_chroma/_version.py
  2403	libs/partners/chroma/langchain_chroma/py.typed
  2404	libs/partners/chroma/langchain_chroma/vectorstores.py
  2405	libs/partners/chroma/pyproject.toml
  2406	libs/partners/chroma/scripts/check_imports.py
  2407	libs/partners/chroma/scripts/check_version.py
  2408	libs/partners/chroma/scripts/lint_imports.sh
  2409	libs/partners/chroma/tests/__init__.py
  2410	libs/partners/chroma/tests/integration_tests/__init__.py
  2411	libs/partners/chroma/tests/integration_tests/fake_embeddings.py
  2412	libs/partners/chroma/tests/integration_tests/test_compile.py
  2413	libs/partners/chroma/tests/integration_tests/test_vectorstores.py
  2414	libs/partners/chroma/tests/unit_tests/__init__.py
  2415	libs/partners/chroma/tests/unit_tests/test_imports.py
  2416	libs/partners/chroma/tests/unit_tests/test_standard.py
  2417	libs/partners/chroma/tests/unit_tests/test_vectorstores.py
  2418	libs/partners/chroma/uv.lock
  2419	libs/partners/deepseek/.gitignore
  2420	libs/partners/deepseek/LICENSE
  2421	libs/partners/deepseek/Makefile
  2422	libs/partners/deepseek/README.md
  2423	libs/partners/deepseek/langchain_deepseek/__init__.py
  2424	libs/partners/deepseek/langchain_deepseek/_version.py
  2425	libs/partners/deepseek/langchain_deepseek/chat_models.py
  2426	libs/partners/deepseek/langchain_deepseek/data/__init__.py
  2427	libs/partners/deepseek/langchain_deepseek/data/_profiles.py
  2428	libs/partners/deepseek/langchain_deepseek/data/profile_augmentations.toml
  2429	libs/partners/deepseek/langchain_deepseek/py.typed
  2430	libs/partners/deepseek/pyproject.toml
  2431	libs/partners/deepseek/scripts/check_imports.py
  2432	libs/partners/deepseek/scripts/check_version.py
  2433	libs/partners/deepseek/scripts/lint_imports.sh
  2434	libs/partners/deepseek/tests/__init__.py
  2435	libs/partners/deepseek/tests/integration_tests/__init__.py
  2436	libs/partners/deepseek/tests/integration_tests/test_chat_models.py
  2437	libs/partners/deepseek/tests/integration_tests/test_compile.py
  2438	libs/partners/deepseek/tests/unit_tests/__init__.py
  2439	libs/partners/deepseek/tests/unit_tests/test_chat_models.py
  2440	libs/partners/deepseek/tests/unit_tests/test_imports.py
  2441	libs/partners/deepseek/uv.lock
  2442	libs/partners/exa/.gitignore
  2443	libs/partners/exa/LICENSE
  2444	libs/partners/exa/Makefile
  2445	libs/partners/exa/README.md
  2446	libs/partners/exa/langchain_exa/__init__.py
  2447	libs/partners/exa/langchain_exa/_utilities.py
  2448	libs/partners/exa/langchain_exa/_version.py
  2449	libs/partners/exa/langchain_exa/py.typed
  2450	libs/partners/exa/langchain_exa/retrievers.py
  2451	libs/partners/exa/langchain_exa/tools.py
  2452	libs/partners/exa/pyproject.toml
  2453	libs/partners/exa/scripts/check_imports.py
  2454	libs/partners/exa/scripts/check_version.py
  2455	libs/partners/exa/scripts/lint_imports.sh
  2456	libs/partners/exa/tests/__init__.py
  2457	libs/partners/exa/tests/integration_tests/__init__.py
  2458	libs/partners/exa/tests/integration_tests/test_compile.py
  2459	libs/partners/exa/tests/integration_tests/test_find_similar_tool.py
  2460	libs/partners/exa/tests/integration_tests/test_retriever.py
  2461	libs/partners/exa/tests/integration_tests/test_search_tool.py
  2462	libs/partners/exa/tests/unit_tests/__init__.py
  2463	libs/partners/exa/tests/unit_tests/test_imports.py
  2464	libs/partners/exa/tests/unit_tests/test_retrievers.py
  2465	libs/partners/exa/tests/unit_tests/test_standard.py
  2466	libs/partners/exa/uv.lock
  2467	libs/partners/fireworks/.gitignore
  2468	libs/partners/fireworks/LICENSE
  2469	libs/partners/fireworks/Makefile
  2470	libs/partners/fireworks/README.md
  2471	libs/partners/fireworks/langchain_fireworks/__init__.py
  2472	libs/partners/fireworks/langchain_fireworks/_compat.py
  2473	libs/partners/fireworks/langchain_fireworks/_version.py
  2474	libs/partners/fireworks/langchain_fireworks/chat_models.py
  2475	libs/partners/fireworks/langchain_fireworks/data/__init__.py
  2476	libs/partners/fireworks/langchain_fireworks/data/_profiles.py
  2477	libs/partners/fireworks/langchain_fireworks/data/profile_augmentations.toml
  2478	libs/partners/fireworks/langchain_fireworks/embeddings.py
  2479	libs/partners/fireworks/langchain_fireworks/llms.py
  2480	libs/partners/fireworks/langchain_fireworks/py.typed
  2481	libs/partners/fireworks/langchain_fireworks/rerank.py
  2482	libs/partners/fireworks/langchain_fireworks/version.py
  2483	libs/partners/fireworks/pyproject.toml
  2484	libs/partners/fireworks/scripts/check_imports.py
  2485	libs/partners/fireworks/scripts/check_version.py
  2486	libs/partners/fireworks/scripts/lint_imports.sh
  2487	libs/partners/fireworks/tests/__init__.py
  2488	libs/partners/fireworks/tests/integration_tests/__init__.py
  2489	libs/partners/fireworks/tests/integration_tests/_rate_limiter.py
  2490	libs/partners/fireworks/tests/integration_tests/conftest.py
  2491	libs/partners/fireworks/tests/integration_tests/test_chat_models.py
  2492	libs/partners/fireworks/tests/integration_tests/test_compile.py
  2493	libs/partners/fireworks/tests/integration_tests/test_embeddings.py
  2494	libs/partners/fireworks/tests/integration_tests/test_llms.py
  2495	libs/partners/fireworks/tests/integration_tests/test_standard.py
  2496	libs/partners/fireworks/tests/unit_tests/__init__.py
  2497	libs/partners/fireworks/tests/unit_tests/__snapshots__/test_standard.ambr
  2498	libs/partners/fireworks/tests/unit_tests/test_chat_models.py
  2499	libs/partners/fireworks/tests/unit_tests/test_embeddings.py
  2500	libs/partners/fireworks/tests/unit_tests/test_embeddings_standard.py
  2501	libs/partners/fireworks/tests/unit_tests/test_imports.py
  2502	libs/partners/fireworks/tests/unit_tests/test_llms.py
  2503	libs/partners/fireworks/tests/unit_tests/test_rerank.py
  2504	libs/partners/fireworks/tests/unit_tests/test_standard.py
  2505	libs/partners/fireworks/uv.lock
  2506	libs/partners/groq/.gitignore
  2507	libs/partners/groq/LICENSE
  2508	libs/partners/groq/Makefile
  2509	libs/partners/groq/README.md
  2510	libs/partners/groq/langchain_groq/__init__.py
  2511	libs/partners/groq/langchain_groq/_compat.py
  2512	libs/partners/groq/langchain_groq/_version.py
  2513	libs/partners/groq/langchain_groq/chat_models.py
  2514	libs/partners/groq/langchain_groq/data/__init__.py
  2515	libs/partners/groq/langchain_groq/data/_profiles.py
  2516	libs/partners/groq/langchain_groq/py.typed
  2517	libs/partners/groq/langchain_groq/version.py
  2518	libs/partners/groq/pyproject.toml
  2519	libs/partners/groq/scripts/__init__.py
  2520	libs/partners/groq/scripts/check_imports.py
  2521	libs/partners/groq/scripts/check_version.py
  2522	libs/partners/groq/scripts/lint_imports.sh
  2523	libs/partners/groq/tests/__init__.py
  2524	libs/partners/groq/tests/cassettes/test_code_interpreter.yaml.gz
  2525	libs/partners/groq/tests/cassettes/test_web_search.yaml.gz
  2526	libs/partners/groq/tests/conftest.py
  2527	libs/partners/groq/tests/integration_tests/__init__.py
  2528	libs/partners/groq/tests/integration_tests/test_chat_models.py
  2529	libs/partners/groq/tests/integration_tests/test_compile.py
  2530	libs/partners/groq/tests/integration_tests/test_standard.py
  2531	libs/partners/groq/tests/unit_tests/__init__.py
  2532	libs/partners/groq/tests/unit_tests/__snapshots__/test_standard.ambr
  2533	libs/partners/groq/tests/unit_tests/fake/__init__.py
  2534	libs/partners/groq/tests/unit_tests/fake/callbacks.py
  2535	libs/partners/groq/tests/unit_tests/test_chat_models.py
  2536	libs/partners/groq/tests/unit_tests/test_imports.py
  2537	libs/partners/groq/tests/unit_tests/test_standard.py
  2538	libs/partners/groq/uv.lock
  2539	libs/partners/huggingface/.gitignore
  2540	libs/partners/huggingface/LICENSE
  2541	libs/partners/huggingface/Makefile
  2542	libs/partners/huggingface/README.md
  2543	libs/partners/huggingface/langchain_huggingface/__init__.py
  2544	libs/partners/huggingface/langchain_huggingface/_version.py
  2545	libs/partners/huggingface/langchain_huggingface/chat_models/__init__.py
  2546	libs/partners/huggingface/langchain_huggingface/chat_models/huggingface.py
  2547	libs/partners/huggingface/langchain_huggingface/data/__init__.py
  2548	libs/partners/huggingface/langchain_huggingface/data/_profiles.py
  2549	libs/partners/huggingface/langchain_huggingface/data/profile_augmentations.toml
  2550	libs/partners/huggingface/langchain_huggingface/embeddings/__init__.py
  2551	libs/partners/huggingface/langchain_huggingface/embeddings/huggingface.py
  2552	libs/partners/huggingface/langchain_huggingface/embeddings/huggingface_endpoint.py
  2553	libs/partners/huggingface/langchain_huggingface/llms/__init__.py
  2554	libs/partners/huggingface/langchain_huggingface/llms/huggingface_endpoint.py
  2555	libs/partners/huggingface/langchain_huggingface/llms/huggingface_pipeline.py
  2556	libs/partners/huggingface/langchain_huggingface/py.typed
  2557	libs/partners/huggingface/langchain_huggingface/tests/__init__.py
  2558	libs/partners/huggingface/langchain_huggingface/tests/integration_tests/__init__.py
  2559	libs/partners/huggingface/langchain_huggingface/utils/import_utils.py
  2560	libs/partners/huggingface/pyproject.toml
  2561	libs/partners/huggingface/scripts/check_imports.py
  2562	libs/partners/huggingface/scripts/check_version.py
  2563	libs/partners/huggingface/scripts/lint_imports.sh
  2564	libs/partners/huggingface/tests/integration_tests/__init__.py
  2565	libs/partners/huggingface/tests/integration_tests/test_chat_models.py
  2566	libs/partners/huggingface/tests/integration_tests/test_compile.py
  2567	libs/partners/huggingface/tests/integration_tests/test_embeddings_standard.py
  2568	libs/partners/huggingface/tests/integration_tests/test_llms.py
  2569	libs/partners/huggingface/tests/integration_tests/test_standard.py
  2570	libs/partners/huggingface/tests/unit_tests/__init__.py
  2571	libs/partners/huggingface/tests/unit_tests/test_chat_models.py
  2572	libs/partners/huggingface/tests/unit_tests/test_huggingface_endpoint.py
  2573	libs/partners/huggingface/tests/unit_tests/test_huggingface_pipeline.py
  2574	libs/partners/huggingface/uv.lock
  2575	libs/partners/mistralai/.gitignore
  2576	libs/partners/mistralai/LICENSE
  2577	libs/partners/mistralai/Makefile
  2578	libs/partners/mistralai/README.md
  2579	libs/partners/mistralai/langchain_mistralai/__init__.py
  2580	libs/partners/mistralai/langchain_mistralai/_compat.py
  2581	libs/partners/mistralai/langchain_mistralai/_version.py
  2582	libs/partners/mistralai/langchain_mistralai/chat_models.py
  2583	libs/partners/mistralai/langchain_mistralai/data/__init__.py
  2584	libs/partners/mistralai/langchain_mistralai/data/_profiles.py
  2585	libs/partners/mistralai/langchain_mistralai/embeddings.py
  2586	libs/partners/mistralai/langchain_mistralai/py.typed
  2587	libs/partners/mistralai/pyproject.toml
  2588	libs/partners/mistralai/scripts/check_imports.py
  2589	libs/partners/mistralai/scripts/check_version.py
  2590	libs/partners/mistralai/scripts/lint_imports.sh
  2591	libs/partners/mistralai/tests/__init__.py
  2592	libs/partners/mistralai/tests/integration_tests/__init__.py
  2593	libs/partners/mistralai/tests/integration_tests/_rate_limiter.py
  2594	libs/partners/mistralai/tests/integration_tests/test_chat_models.py
  2595	libs/partners/mistralai/tests/integration_tests/test_compile.py
  2596	libs/partners/mistralai/tests/integration_tests/test_embeddings.py
  2597	libs/partners/mistralai/tests/integration_tests/test_standard.py
  2598	libs/partners/mistralai/tests/unit_tests/__init__.py
  2599	libs/partners/mistralai/tests/unit_tests/__snapshots__/test_standard.ambr
  2600	libs/partners/mistralai/tests/unit_tests/test_chat_models.py
  2601	libs/partners/mistralai/tests/unit_tests/test_embeddings.py
  2602	libs/partners/mistralai/tests/unit_tests/test_imports.py
  2603	libs/partners/mistralai/tests/unit_tests/test_standard.py
  2604	libs/partners/mistralai/uv.lock
  2605	libs/partners/nomic/.gitignore
  2606	libs/partners/nomic/LICENSE
  2607	libs/partners/nomic/Makefile
  2608	libs/partners/nomic/README.md
  2609	libs/partners/nomic/langchain_nomic/__init__.py
  2610	libs/partners/nomic/langchain_nomic/_version.py
  2611	libs/partners/nomic/langchain_nomic/embeddings.py
  2612	libs/partners/nomic/langchain_nomic/py.typed
  2613	libs/partners/nomic/pyproject.toml
  2614	libs/partners/nomic/scripts/check_imports.py
  2615	libs/partners/nomic/scripts/check_version.py
  2616	libs/partners/nomic/scripts/lint_imports.sh
  2617	libs/partners/nomic/tests/__init__.py
  2618	libs/partners/nomic/tests/integration_tests/__init__.py
  2619	libs/partners/nomic/tests/integration_tests/test_compile.py
  2620	libs/partners/nomic/tests/integration_tests/test_embeddings.py
  2621	libs/partners/nomic/tests/unit_tests/__init__.py
  2622	libs/partners/nomic/tests/unit_tests/test_embeddings.py
  2623	libs/partners/nomic/tests/unit_tests/test_imports.py
  2624	libs/partners/nomic/tests/unit_tests/test_standard.py
  2625	libs/partners/nomic/uv.lock
  2626	libs/partners/ollama/.gitignore
  2627	libs/partners/ollama/LICENSE
  2628	libs/partners/ollama/Makefile
  2629	libs/partners/ollama/README.md
  2630	libs/partners/ollama/langchain_ollama/__init__.py
  2631	libs/partners/ollama/langchain_ollama/_compat.py
  2632	libs/partners/ollama/langchain_ollama/_utils.py
  2633	libs/partners/ollama/langchain_ollama/_version.py
  2634	libs/partners/ollama/langchain_ollama/chat_models.py
  2635	libs/partners/ollama/langchain_ollama/embeddings.py
  2636	libs/partners/ollama/langchain_ollama/llms.py
  2637	libs/partners/ollama/langchain_ollama/py.typed
  2638	libs/partners/ollama/pyproject.toml
  2639	libs/partners/ollama/scripts/check_imports.py
  2640	libs/partners/ollama/scripts/check_version.py
  2641	libs/partners/ollama/scripts/lint_imports.sh
  2642	libs/partners/ollama/tests/__init__.py
  2643	libs/partners/ollama/tests/integration_tests/__init__.py
  2644	libs/partners/ollama/tests/integration_tests/chat_models/__init__.py
  2645	libs/partners/ollama/tests/integration_tests/chat_models/cassettes/test_chat_models_standard/TestChatOllama.test_stream_time.yaml
  2646	libs/partners/ollama/tests/integration_tests/chat_models/test_chat_models.py
  2647	libs/partners/ollama/tests/integration_tests/chat_models/test_chat_models_reasoning.py
  2648	libs/partners/ollama/tests/integration_tests/chat_models/test_chat_models_standard.py
  2649	libs/partners/ollama/tests/integration_tests/test_compile.py
  2650	libs/partners/ollama/tests/integration_tests/test_embeddings.py
  2651	libs/partners/ollama/tests/integration_tests/test_llms.py
  2652	libs/partners/ollama/tests/unit_tests/__init__.py
  2653	libs/partners/ollama/tests/unit_tests/test_auth.py
  2654	libs/partners/ollama/tests/unit_tests/test_chat_models.py
  2655	libs/partners/ollama/tests/unit_tests/test_embeddings.py
  2656	libs/partners/ollama/tests/unit_tests/test_imports.py
  2657	libs/partners/ollama/tests/unit_tests/test_llms.py
  2658	libs/partners/ollama/uv.lock
  2659	libs/partners/openai/.gitignore
  2660	libs/partners/openai/LICENSE
  2661	libs/partners/openai/Makefile
  2662	libs/partners/openai/README.md
  2663	libs/partners/openai/langchain_openai/__init__.py
  2664	libs/partners/openai/langchain_openai/_compat.py
  2665	libs/partners/openai/langchain_openai/_version.py
  2666	libs/partners/openai/langchain_openai/chat_models/__init__.py
  2667	libs/partners/openai/langchain_openai/chat_models/_client_utils.py
  2668	libs/partners/openai/langchain_openai/chat_models/_compat.py
  2669	libs/partners/openai/langchain_openai/chat_models/azure.py
  2670	libs/partners/openai/langchain_openai/chat_models/base.py
  2671	libs/partners/openai/langchain_openai/chat_models/codex.py
  2672	libs/partners/openai/langchain_openai/chatgpt_oauth.py
  2673	libs/partners/openai/langchain_openai/data/__init__.py
  2674	libs/partners/openai/langchain_openai/data/_profiles.py
  2675	libs/partners/openai/langchain_openai/data/profile_augmentations.toml
  2676	libs/partners/openai/langchain_openai/embeddings/__init__.py
  2677	libs/partners/openai/langchain_openai/embeddings/azure.py
  2678	libs/partners/openai/langchain_openai/embeddings/base.py
  2679	libs/partners/openai/langchain_openai/llms/__init__.py
  2680	libs/partners/openai/langchain_openai/llms/azure.py
  2681	libs/partners/openai/langchain_openai/llms/base.py
  2682	libs/partners/openai/langchain_openai/middleware/__init__.py
  2683	libs/partners/openai/langchain_openai/middleware/openai_moderation.py
  2684	libs/partners/openai/langchain_openai/output_parsers/__init__.py
  2685	libs/partners/openai/langchain_openai/output_parsers/tools.py
  2686	libs/partners/openai/langchain_openai/py.typed
  2687	libs/partners/openai/langchain_openai/tools/__init__.py
  2688	libs/partners/openai/langchain_openai/tools/custom_tool.py
  2689	libs/partners/openai/pyproject.toml
  2690	libs/partners/openai/scripts/RECORD_CODEX_CASSETTES.md
  2691	libs/partners/openai/scripts/check_imports.py
  2692	libs/partners/openai/scripts/check_version.py
  2693	libs/partners/openai/scripts/lint_imports.sh
  2694	libs/partners/openai/scripts/record_codex_cassettes.sh
  2695	libs/partners/openai/tests/__init__.py
  2696	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_abatch.yaml.gz
  2697	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_agent_loop[model0].yaml.gz
  2698	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_agent_loop[model1].yaml.gz
  2699	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_ainvoke.yaml.gz
  2700	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_ainvoke_with_model_override.yaml.gz
  2701	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_anthropic_inputs.yaml.gz
  2702	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_astream[model0].yaml.gz
  2703	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_astream[model1].yaml.gz
  2704	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_astream_events_v3.yaml.gz
  2705	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_astream_with_model_override.yaml.gz
  2706	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_audio_inputs.yaml.gz
  2707	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_batch.yaml.gz
  2708	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_bind_runnables_as_tools.yaml.gz
  2709	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_conversation.yaml.gz
  2710	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_double_messages_conversation.yaml.gz
  2711	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_image_inputs.yaml.gz
  2712	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_image_tool_message.yaml.gz
  2713	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_invoke.yaml.gz
  2714	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_invoke_with_model_override.yaml.gz
  2715	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_json_mode.yaml.gz
  2716	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_message_with_name.yaml.gz
  2717	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_pdf_inputs.yaml.gz
  2718	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_pdf_tool_message.yaml.gz
  2719	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_stop_sequence.yaml.gz
  2720	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_stream[model0].yaml.gz
  2721	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_stream[model1].yaml.gz
  2722	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_stream_events_v3.yaml.gz
  2723	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_stream_time.yaml.gz
  2724	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_stream_with_model_override.yaml.gz
  2725	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_structured_few_shot_examples.yaml.gz
  2726	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_structured_output[json_schema].yaml.gz
  2727	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_structured_output[pydantic].yaml.gz
  2728	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_structured_output[typeddict].yaml.gz
  2729	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_structured_output_async[json_schema].yaml.gz
  2730	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_structured_output_async[pydantic].yaml.gz
  2731	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_structured_output_async[typeddict].yaml.gz
  2732	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_structured_output_optional_param.yaml.gz
  2733	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_structured_output_pydantic_2_v1.yaml.gz
  2734	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_tool_calling[model0].yaml.gz
  2735	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_tool_calling[model1].yaml.gz
  2736	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_tool_calling_async.yaml.gz
  2737	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_tool_calling_with_no_arguments.yaml.gz
  2738	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_tool_choice.yaml.gz
  2739	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_tool_message_error_status.yaml.gz
  2740	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_tool_message_histories_list_content.yaml.gz
  2741	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_tool_message_histories_string_content.yaml.gz
  2742	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_unicode_tool_call_integration.yaml.gz
  2743	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_usage_metadata.yaml.gz
  2744	libs/partners/openai/tests/cassettes/TestChatOpenAICodexStandard.test_usage_metadata_streaming.yaml.gz
  2745	libs/partners/openai/tests/cassettes/TestOpenAIResponses.test_stream_time.yaml.gz
  2746	libs/partners/openai/tests/cassettes/TestOpenAIStandard.test_stream_time.yaml.gz
  2747	libs/partners/openai/tests/cassettes/test_agent_loop.yaml.gz
  2748	libs/partners/openai/tests/cassettes/test_agent_loop_streaming.yaml.gz
  2749	libs/partners/openai/tests/cassettes/test_apply_patch.yaml.gz
  2750	libs/partners/openai/tests/cassettes/test_client_executed_tool_search.yaml.gz
  2751	libs/partners/openai/tests/cassettes/test_code_interpreter.yaml.gz
  2752	libs/partners/openai/tests/cassettes/test_codex_agent_loop.yaml.gz
  2753	libs/partners/openai/tests/cassettes/test_codex_agent_loop_streaming.yaml.gz
  2754	libs/partners/openai/tests/cassettes/test_codex_custom_tool.yaml.gz
  2755	libs/partners/openai/tests/cassettes/test_codex_function_calling.yaml.gz
  2756	libs/partners/openai/tests/cassettes/test_codex_invoke.yaml.gz
  2757	libs/partners/openai/tests/cassettes/test_codex_invoke_async.yaml.gz
  2758	libs/partners/openai/tests/cassettes/test_codex_invoke_lifts_system_message_into_instructions.yaml.gz
  2759	libs/partners/openai/tests/cassettes/test_codex_invoke_with_instructions_override.yaml.gz
  2760	libs/partners/openai/tests/cassettes/test_codex_multi_turn_no_tools.yaml.gz
  2761	libs/partners/openai/tests/cassettes/test_codex_reasoning.yaml.gz
  2762	libs/partners/openai/tests/cassettes/test_codex_reasoning_summary_streaming.yaml.gz
  2763	libs/partners/openai/tests/cassettes/test_codex_stream.yaml.gz
  2764	libs/partners/openai/tests/cassettes/test_codex_stream_async.yaml.gz
  2765	libs/partners/openai/tests/cassettes/test_codex_stream_events_v3.yaml.gz
  2766	libs/partners/openai/tests/cassettes/test_codex_stream_events_v3_async.yaml.gz
  2767	libs/partners/openai/tests/cassettes/test_codex_structured_output_pydantic.yaml.gz
  2768	libs/partners/openai/tests/cassettes/test_codex_structured_output_typed_dict.yaml.gz
  2769	libs/partners/openai/tests/cassettes/test_compaction.yaml.gz
  2770	libs/partners/openai/tests/cassettes/test_compaction_streaming.yaml.gz
  2771	libs/partners/openai/tests/cassettes/test_configuration_update_block.yaml.gz
  2772	libs/partners/openai/tests/cassettes/test_custom_tool.yaml.gz
  2773	libs/partners/openai/tests/cassettes/test_file_search.yaml.gz
  2774	libs/partners/openai/tests/cassettes/test_function_calling.yaml.gz
  2775	libs/partners/openai/tests/cassettes/test_image_generation_multi_turn.yaml.gz
  2776	libs/partners/openai/tests/cassettes/test_image_generation_streaming.yaml.gz
  2777	libs/partners/openai/tests/cassettes/test_incomplete_response.yaml.gz
  2778	libs/partners/openai/tests/cassettes/test_langchain_openai_embeddings_equivalent_to_raw.yaml.gz
  2779	libs/partners/openai/tests/cassettes/test_langchain_openai_embeddings_equivalent_to_raw_async.yaml.gz
  2780	libs/partners/openai/tests/cassettes/test_mcp_builtin.yaml.gz
  2781	libs/partners/openai/tests/cassettes/test_mcp_builtin_zdr.yaml.gz
  2782	libs/partners/openai/tests/cassettes/test_parsed_pydantic_schema.yaml.gz
  2783	libs/partners/openai/tests/cassettes/test_phase.yaml.gz
  2784	libs/partners/openai/tests/cassettes/test_phase_streaming.yaml.gz
  2785	libs/partners/openai/tests/cassettes/test_reasoning.yaml.gz
  2786	libs/partners/openai/tests/cassettes/test_reasoning_text_v1_v2_parity.yaml.gz
  2787	libs/partners/openai/tests/cassettes/test_schema_parsing_failures.yaml.gz
  2788	libs/partners/openai/tests/cassettes/test_schema_parsing_failures_async.yaml.gz
  2789	libs/partners/openai/tests/cassettes/test_schema_parsing_failures_responses_api.yaml.gz
  2790	libs/partners/openai/tests/cassettes/test_schema_parsing_failures_responses_api_async.yaml.gz
  2791	libs/partners/openai/tests/cassettes/test_stream_encrypted_reasoning.yaml.gz
  2792	libs/partners/openai/tests/cassettes/test_stream_reasoning_summary.yaml.gz
  2793	libs/partners/openai/tests/cassettes/test_streaming_tool_call_v1_v2_parity.yaml.gz
  2794	libs/partners/openai/tests/cassettes/test_tool_search.yaml.gz
  2795	libs/partners/openai/tests/cassettes/test_tool_search_streaming.yaml.gz
  2796	libs/partners/openai/tests/cassettes/test_web_search.yaml.gz
  2797	libs/partners/openai/tests/conftest.py
  2798	libs/partners/openai/tests/integration_tests/__init__.py
  2799	libs/partners/openai/tests/integration_tests/chat_models/__init__.py
  2800	libs/partners/openai/tests/integration_tests/chat_models/audio_input.wav
  2801	libs/partners/openai/tests/integration_tests/chat_models/conftest.py
  2802	libs/partners/openai/tests/integration_tests/chat_models/test_azure.py
  2803	libs/partners/openai/tests/integration_tests/chat_models/test_azure_standard.py
  2804	libs/partners/openai/tests/integration_tests/chat_models/test_base.py
  2805	libs/partners/openai/tests/integration_tests/chat_models/test_base_standard.py
  2806	libs/partners/openai/tests/integration_tests/chat_models/test_codex.py
  2807	libs/partners/openai/tests/integration_tests/chat_models/test_codex_standard.py
  2808	libs/partners/openai/tests/integration_tests/chat_models/test_responses_api.py
  2809	libs/partners/openai/tests/integration_tests/chat_models/test_responses_standard.py
  2810	libs/partners/openai/tests/integration_tests/embeddings/__init__.py
  2811	libs/partners/openai/tests/integration_tests/embeddings/test_azure.py
  2812	libs/partners/openai/tests/integration_tests/embeddings/test_base.py
  2813	libs/partners/openai/tests/integration_tests/embeddings/test_base_standard.py
  2814	libs/partners/openai/tests/integration_tests/llms/__init__.py
  2815	libs/partners/openai/tests/integration_tests/llms/test_azure.py
  2816	libs/partners/openai/tests/integration_tests/llms/test_base.py
  2817	libs/partners/openai/tests/integration_tests/test_compile.py
  2818	libs/partners/openai/tests/unit_tests/__init__.py
  2819	libs/partners/openai/tests/unit_tests/chat_models/__init__.py
  2820	libs/partners/openai/tests/unit_tests/chat_models/__snapshots__/test_azure_standard.ambr
  2821	libs/partners/openai/tests/unit_tests/chat_models/__snapshots__/test_base_standard.ambr
  2822	libs/partners/openai/tests/unit_tests/chat_models/__snapshots__/test_responses_standard.ambr
  2823	libs/partners/openai/tests/unit_tests/chat_models/conftest.py
  2824	libs/partners/openai/tests/unit_tests/chat_models/test_azure.py
  2825	libs/partners/openai/tests/unit_tests/chat_models/test_azure_standard.py
  2826	libs/partners/openai/tests/unit_tests/chat_models/test_base.py
  2827	libs/partners/openai/tests/unit_tests/chat_models/test_base_standard.py
  2828	libs/partners/openai/tests/unit_tests/chat_models/test_client_utils.py
  2829	libs/partners/openai/tests/unit_tests/chat_models/test_codex.py
  2830	libs/partners/openai/tests/unit_tests/chat_models/test_imports.py
  2831	libs/partners/openai/tests/unit_tests/chat_models/test_prompt_cache_key.py
  2832	libs/partners/openai/tests/unit_tests/chat_models/test_responses_standard.py
  2833	libs/partners/openai/tests/unit_tests/chat_models/test_responses_stream.py
  2834	libs/partners/openai/tests/unit_tests/chat_models/test_stream_chunk_timeout.py
  2835	libs/partners/openai/tests/unit_tests/conftest.py
  2836	libs/partners/openai/tests/unit_tests/embeddings/__init__.py
  2837	libs/partners/openai/tests/unit_tests/embeddings/test_azure_embeddings.py
  2838	libs/partners/openai/tests/unit_tests/embeddings/test_azure_standard.py
  2839	libs/partners/openai/tests/unit_tests/embeddings/test_base.py
  2840	libs/partners/openai/tests/unit_tests/embeddings/test_base_standard.py
  2841	libs/partners/openai/tests/unit_tests/embeddings/test_imports.py
  2842	libs/partners/openai/tests/unit_tests/fake/__init__.py
  2843	libs/partners/openai/tests/unit_tests/fake/callbacks.py
  2844	libs/partners/openai/tests/unit_tests/llms/__init__.py
  2845	libs/partners/openai/tests/unit_tests/llms/test_azure.py
  2846	libs/partners/openai/tests/unit_tests/llms/test_base.py
  2847	libs/partners/openai/tests/unit_tests/llms/test_imports.py
  2848	libs/partners/openai/tests/unit_tests/middleware/__init__.py
  2849	libs/partners/openai/tests/unit_tests/middleware/test_openai_moderation_middleware.py
  2850	libs/partners/openai/tests/unit_tests/test_chatgpt_oauth.py
  2851	libs/partners/openai/tests/unit_tests/test_check_version.py
  2852	libs/partners/openai/tests/unit_tests/test_compat.py
  2853	libs/partners/openai/tests/unit_tests/test_imports.py
  2854	libs/partners/openai/tests/unit_tests/test_load.py
  2855	libs/partners/openai/tests/unit_tests/test_secrets.py
  2856	libs/partners/openai/tests/unit_tests/test_token_counts.py
  2857	libs/partners/openai/tests/unit_tests/test_tools.py
  2858	libs/partners/openai/uv.lock
  2859	libs/partners/openrouter/.gitignore
  2860	libs/partners/openrouter/LICENSE
  2861	libs/partners/openrouter/Makefile
  2862	libs/partners/openrouter/README.md
  2863	libs/partners/openrouter/langchain_openrouter/__init__.py
  2864	libs/partners/openrouter/langchain_openrouter/_version.py
  2865	libs/partners/openrouter/langchain_openrouter/chat_models.py
  2866	libs/partners/openrouter/langchain_openrouter/data/__init__.py
  2867	libs/partners/openrouter/langchain_openrouter/data/_profiles.py
  2868	libs/partners/openrouter/langchain_openrouter/data/profile_augmentations.toml
  2869	libs/partners/openrouter/langchain_openrouter/py.typed
  2870	libs/partners/openrouter/pyproject.toml
  2871	libs/partners/openrouter/scripts/__init__.py
  2872	libs/partners/openrouter/scripts/check_imports.py
  2873	libs/partners/openrouter/scripts/check_version.py
  2874	libs/partners/openrouter/scripts/lint_imports.sh
  2875	libs/partners/openrouter/tests/__init__.py
  2876	libs/partners/openrouter/tests/conftest.py
  2877	libs/partners/openrouter/tests/integration_tests/__init__.py
  2878	libs/partners/openrouter/tests/integration_tests/test_chat_models.py
  2879	libs/partners/openrouter/tests/integration_tests/test_compile.py
  2880	libs/partners/openrouter/tests/integration_tests/test_standard.py
  2881	libs/partners/openrouter/tests/unit_tests/__init__.py
  2882	libs/partners/openrouter/tests/unit_tests/__snapshots__/test_standard.ambr
  2883	libs/partners/openrouter/tests/unit_tests/test_chat_models.py
  2884	libs/partners/openrouter/tests/unit_tests/test_imports.py
  2885	libs/partners/openrouter/tests/unit_tests/test_standard.py
  2886	libs/partners/openrouter/uv.lock
  2887	libs/partners/perplexity/.gitignore
  2888	libs/partners/perplexity/LICENSE
  2889	libs/partners/perplexity/Makefile
  2890	libs/partners/perplexity/README.md
  2891	libs/partners/perplexity/langchain_perplexity/__init__.py
  2892	libs/partners/perplexity/langchain_perplexity/_utils.py
  2893	libs/partners/perplexity/langchain_perplexity/_version.py
  2894	libs/partners/perplexity/langchain_perplexity/chat_models.py
  2895	libs/partners/perplexity/langchain_perplexity/data/__init__.py
  2896	libs/partners/perplexity/langchain_perplexity/data/_profiles.py
  2897	libs/partners/perplexity/langchain_perplexity/data/profile_augmentations.toml
  2898	libs/partners/perplexity/langchain_perplexity/embeddings.py
  2899	libs/partners/perplexity/langchain_perplexity/output_parsers.py
  2900	libs/partners/perplexity/langchain_perplexity/py.typed
  2901	libs/partners/perplexity/langchain_perplexity/retrievers.py
  2902	libs/partners/perplexity/langchain_perplexity/tools.py
  2903	libs/partners/perplexity/langchain_perplexity/types.py
  2904	libs/partners/perplexity/pyproject.toml
  2905	libs/partners/perplexity/scripts/check_imports.py
  2906	libs/partners/perplexity/scripts/check_version.py
  2907	libs/partners/perplexity/scripts/lint_imports.sh
  2908	libs/partners/perplexity/tests/__init__.py
  2909	libs/partners/perplexity/tests/integration_tests/__init__.py
  2910	libs/partners/perplexity/tests/integration_tests/test_chat_models.py
  2911	libs/partners/perplexity/tests/integration_tests/test_chat_models_standard.py
  2912	libs/partners/perplexity/tests/integration_tests/test_compile.py
  2913	libs/partners/perplexity/tests/integration_tests/test_embeddings.py
  2914	libs/partners/perplexity/tests/integration_tests/test_embeddings_standard.py
  2915	libs/partners/perplexity/tests/integration_tests/test_search_api.py
  2916	libs/partners/perplexity/tests/unit_tests/__init__.py
  2917	libs/partners/perplexity/tests/unit_tests/test_chat_models.py
  2918	libs/partners/perplexity/tests/unit_tests/test_chat_models_responses.py
  2919	libs/partners/perplexity/tests/unit_tests/test_chat_models_standard.py
  2920	libs/partners/perplexity/tests/unit_tests/test_embeddings.py
  2921	libs/partners/perplexity/tests/unit_tests/test_embeddings_standard.py
  2922	libs/partners/perplexity/tests/unit_tests/test_imports.py
  2923	libs/partners/perplexity/tests/unit_tests/test_output_parsers.py
  2924	libs/partners/perplexity/tests/unit_tests/test_retrievers.py
  2925	libs/partners/perplexity/tests/unit_tests/test_secrets.py
  2926	libs/partners/perplexity/tests/unit_tests/test_tools.py
  2927	libs/partners/perplexity/uv.lock
  2928	libs/partners/qdrant/.gitignore
  2929	libs/partners/qdrant/LICENSE
  2930	libs/partners/qdrant/Makefile
  2931	libs/partners/qdrant/README.md
  2932	libs/partners/qdrant/langchain_qdrant/__init__.py
  2933	libs/partners/qdrant/langchain_qdrant/_utils.py
  2934	libs/partners/qdrant/langchain_qdrant/_version.py
  2935	libs/partners/qdrant/langchain_qdrant/fastembed_sparse.py
  2936	libs/partners/qdrant/langchain_qdrant/py.typed
  2937	libs/partners/qdrant/langchain_qdrant/qdrant.py
  2938	libs/partners/qdrant/langchain_qdrant/sparse_embeddings.py
  2939	libs/partners/qdrant/langchain_qdrant/vectorstores.py
  2940	libs/partners/qdrant/pyproject.toml
  2941	libs/partners/qdrant/scripts/check_imports.py
  2942	libs/partners/qdrant/scripts/check_version.py
  2943	libs/partners/qdrant/scripts/lint_imports.sh
  2944	libs/partners/qdrant/tests/__init__.py
  2945	libs/partners/qdrant/tests/integration_tests/__init__.py
  2946	libs/partners/qdrant/tests/integration_tests/async_api/__init__.py
  2947	libs/partners/qdrant/tests/integration_tests/async_api/test_add_texts.py
  2948	libs/partners/qdrant/tests/integration_tests/async_api/test_from_texts.py
  2949	libs/partners/qdrant/tests/integration_tests/async_api/test_max_marginal_relevance.py
  2950	libs/partners/qdrant/tests/integration_tests/async_api/test_similarity_search.py
  2951	libs/partners/qdrant/tests/integration_tests/common.py
  2952	libs/partners/qdrant/tests/integration_tests/conftest.py
  2953	libs/partners/qdrant/tests/integration_tests/fastembed/__init__.py
  2954	libs/partners/qdrant/tests/integration_tests/fastembed/test_fastembed_sparse.py
  2955	libs/partners/qdrant/tests/integration_tests/fixtures.py
  2956	libs/partners/qdrant/tests/integration_tests/qdrant_vector_store/__init__.py
  2957	libs/partners/qdrant/tests/integration_tests/qdrant_vector_store/test_add_texts.py
  2958	libs/partners/qdrant/tests/integration_tests/qdrant_vector_store/test_from_existing.py
  2959	libs/partners/qdrant/tests/integration_tests/qdrant_vector_store/test_from_texts.py
  2960	libs/partners/qdrant/tests/integration_tests/qdrant_vector_store/test_mmr.py
  2961	libs/partners/qdrant/tests/integration_tests/qdrant_vector_store/test_search.py
  2962	libs/partners/qdrant/tests/integration_tests/test_add_texts.py
  2963	libs/partners/qdrant/tests/integration_tests/test_compile.py
  2964	libs/partners/qdrant/tests/integration_tests/test_embedding_interface.py
  2965	libs/partners/qdrant/tests/integration_tests/test_from_existing_collection.py
  2966	libs/partners/qdrant/tests/integration_tests/test_from_texts.py
  2967	libs/partners/qdrant/tests/integration_tests/test_max_marginal_relevance.py
  2968	libs/partners/qdrant/tests/integration_tests/test_similarity_search.py
  2969	libs/partners/qdrant/tests/unit_tests/__init__.py
  2970	libs/partners/qdrant/tests/unit_tests/test_imports.py
  2971	libs/partners/qdrant/tests/unit_tests/test_standard.py
  2972	libs/partners/qdrant/tests/unit_tests/test_vectorstores.py
  2973	libs/partners/qdrant/uv.lock
  2974	libs/partners/xai/LICENSE
  2975	libs/partners/xai/Makefile
  2976	libs/partners/xai/README.md
  2977	libs/partners/xai/langchain_xai/__init__.py
  2978	libs/partners/xai/langchain_xai/_version.py
  2979	libs/partners/xai/langchain_xai/chat_models.py
  2980	libs/partners/xai/langchain_xai/data/__init__.py
  2981	libs/partners/xai/langchain_xai/data/_profiles.py
  2982	libs/partners/xai/langchain_xai/data/profile_augmentations.toml
  2983	libs/partners/xai/langchain_xai/py.typed
  2984	libs/partners/xai/pyproject.toml
  2985	libs/partners/xai/scripts/check_imports.py
  2986	libs/partners/xai/scripts/check_version.py
  2987	libs/partners/xai/scripts/lint_imports.sh
  2988	libs/partners/xai/tests/__init__.py
  2989	libs/partners/xai/tests/integration_tests/__init__.py
  2990	libs/partners/xai/tests/integration_tests/test_chat_models.py
  2991	libs/partners/xai/tests/integration_tests/test_chat_models_standard.py
  2992	libs/partners/xai/tests/integration_tests/test_compile.py
  2993	libs/partners/xai/tests/unit_tests/__init__.py
  2994	libs/partners/xai/tests/unit_tests/__snapshots__/test_chat_models_standard.ambr
  2995	libs/partners/xai/tests/unit_tests/test_chat_models.py
  2996	libs/partners/xai/tests/unit_tests/test_chat_models_standard.py
  2997	libs/partners/xai/tests/unit_tests/test_imports.py
  2998	libs/partners/xai/tests/unit_tests/test_secrets.py
  2999	libs/partners/xai/uv.lock
  3000	libs/standard-tests/LICENSE
  3001	libs/standard-tests/Makefile
  3002	libs/standard-tests/README.md
  3003	libs/standard-tests/langchain_tests/__init__.py
  3004	libs/standard-tests/langchain_tests/_langsmith_plugin.py
  3005	libs/standard-tests/langchain_tests/base.py
  3006	libs/standard-tests/langchain_tests/conftest.py
  3007	libs/standard-tests/langchain_tests/integration_tests/__init__.py
  3008	libs/standard-tests/langchain_tests/integration_tests/base_store.py
  3009	libs/standard-tests/langchain_tests/integration_tests/cache.py
  3010	libs/standard-tests/langchain_tests/integration_tests/chat_models.py
  3011	libs/standard-tests/langchain_tests/integration_tests/embeddings.py
  3012	libs/standard-tests/langchain_tests/integration_tests/indexer.py
  3013	libs/standard-tests/langchain_tests/integration_tests/retrievers.py
  3014	libs/standard-tests/langchain_tests/integration_tests/sandboxes.py
  3015	libs/standard-tests/langchain_tests/integration_tests/tools.py
  3016	libs/standard-tests/langchain_tests/integration_tests/vectorstores.py
  3017	libs/standard-tests/langchain_tests/py.typed
  3018	libs/standard-tests/langchain_tests/unit_tests/__init__.py
  3019	libs/standard-tests/langchain_tests/unit_tests/chat_models.py
  3020	libs/standard-tests/langchain_tests/unit_tests/embeddings.py
  3021	libs/standard-tests/langchain_tests/unit_tests/tools.py
  3022	libs/standard-tests/langchain_tests/utils/__init__.py
  3023	libs/standard-tests/langchain_tests/utils/pydantic.py
  3024	libs/standard-tests/langchain_tests/utils/stream_lifecycle.py
  3025	libs/standard-tests/pyproject.toml
  3026	libs/standard-tests/scripts/check_imports.py
  3027	libs/standard-tests/scripts/lint_imports.sh
  3028	libs/standard-tests/tests/__init__.py
  3029	libs/standard-tests/tests/integration_tests/__init__.py
  3030	libs/standard-tests/tests/integration_tests/test_compile.py
  3031	libs/standard-tests/tests/unit_tests/__init__.py
  3032	libs/standard-tests/tests/unit_tests/custom_chat_model.py
  3033	libs/standard-tests/tests/unit_tests/test_basic_retriever.py
  3034	libs/standard-tests/tests/unit_tests/test_basic_tool.py
  3035	libs/standard-tests/tests/unit_tests/test_custom_chat_model.py
  3036	libs/standard-tests/tests/unit_tests/test_decorated_tool.py
  3037	libs/standard-tests/tests/unit_tests/test_embeddings.py
  3038	libs/standard-tests/tests/unit_tests/test_in_memory_base_store.py
  3039	libs/standard-tests/tests/unit_tests/test_in_memory_cache.py
  3040	libs/standard-tests/tests/unit_tests/test_in_memory_vectorstore.py
  3041	libs/standard-tests/tests/unit_tests/test_langsmith_plugin.py
  3042	libs/standard-tests/uv.lock
  3043	libs/text-splitters/LICENSE
  3044	libs/text-splitters/Makefile
  3045	libs/text-splitters/README.md
  3046	libs/text-splitters/extended_testing_deps.txt
  3047	libs/text-splitters/langchain_text_splitters/__init__.py
  3048	libs/text-splitters/langchain_text_splitters/base.py
  3049	libs/text-splitters/langchain_text_splitters/character.py
  3050	libs/text-splitters/langchain_text_splitters/html.py
  3051	libs/text-splitters/langchain_text_splitters/json.py
  3052	libs/text-splitters/langchain_text_splitters/jsx.py
  3053	libs/text-splitters/langchain_text_splitters/konlpy.py
  3054	libs/text-splitters/langchain_text_splitters/latex.py
  3055	libs/text-splitters/langchain_text_splitters/markdown.py
  3056	libs/text-splitters/langchain_text_splitters/nltk.py
  3057	libs/text-splitters/langchain_text_splitters/py.typed
  3058	libs/text-splitters/langchain_text_splitters/python.py
  3059	libs/text-splitters/langchain_text_splitters/sentence_transformers.py
  3060	libs/text-splitters/langchain_text_splitters/spacy.py
  3061	libs/text-splitters/langchain_text_splitters/xsl/converting_to_header.xslt
  3062	libs/text-splitters/pyproject.toml
  3063	libs/text-splitters/scripts/check_imports.py
  3064	libs/text-splitters/scripts/lint_imports.sh
  3065	libs/text-splitters/tests/__init__.py
  3066	libs/text-splitters/tests/integration_tests/__init__.py
  3067	libs/text-splitters/tests/integration_tests/test_compile.py
  3068	libs/text-splitters/tests/integration_tests/test_nlp_text_splitters.py
  3069	libs/text-splitters/tests/integration_tests/test_text_splitter.py
  3070	libs/text-splitters/tests/test_data/test_splitter.xslt
  3071	libs/text-splitters/tests/unit_tests/__init__.py
  3072	libs/text-splitters/tests/unit_tests/conftest.py
  3073	libs/text-splitters/tests/unit_tests/test_html_security.py
  3074	libs/text-splitters/tests/unit_tests/test_text_splitters.py
  3075	libs/text-splitters/uv.lock
  3076	openwiki/.claims/agent-execution.json
  3077	openwiki/.claims/agent-factory.json
  3078	openwiki/.claims/architecture.json
  3079	openwiki/.claims/callbacks.json
  3080	openwiki/.claims/chat-models.json
  3081	openwiki/.claims/ci-workflows.json
  3082	openwiki/.claims/composability.json
  3083	openwiki/.claims/dev-commands.json
  3084	openwiki/.claims/integration-tests.json
  3085	openwiki/.claims/mcp-integration.json
  3086	openwiki/.claims/messages.json
  3087	openwiki/.claims/middleware.json
  3088	openwiki/.claims/model-initialization.json
  3089	openwiki/.claims/openai-provider.json
  3090	openwiki/.claims/partner-pattern.json
  3091	openwiki/.claims/prompts.json
  3092	openwiki/.claims/quickstart.json
  3093	openwiki/.claims/runnables.json
  3094	openwiki/.claims/source-map.json
  3095	openwiki/.claims/streaming.json
  3096	openwiki/.claims/structured-output.json
  3097	openwiki/.claims/tools.json
  3098	openwiki/.claims/unit-tests.json
  3099	openwiki/.last-update.json
  3100	openwiki/.page-manifest.json
  3101	openwiki/INSTRUCTIONS.md
  3102	openwiki/agent-execution.md
  3103	openwiki/agent-factory.md
  3104	openwiki/architecture.md
  3105	openwiki/callbacks.md
  3106	openwiki/chat-models.md
  3107	openwiki/ci-workflows.md
  3108	openwiki/composability.md
  3109	openwiki/dev-commands.md
  3110	openwiki/index.md
  3111	openwiki/integration-tests.md
  3112	openwiki/mcp-integration.md
  3113	openwiki/messages.md
  3114	openwiki/middleware.md
  3115	openwiki/model-initialization.md
  3116	openwiki/openai-provider.md
  3117	openwiki/partner-pattern.md
  3118	openwiki/prompts.md
  3119	openwiki/quickstart.md
  3120	openwiki/runnables.md
  3121	openwiki/source-map.md
  3122	openwiki/streaming.md
  3123	openwiki/structured-output.md
  3124	openwiki/tools.md
  3125	openwiki/unit-tests.md
```

## Apêndice B — distinção de estado

**Implementado no snapshot:** código Python versionado, testes, exemplos, manifestos, lockfiles, Makefiles, hooks de CI, middlewares, MCPAdapter, callbacks/tracers, `create_agent`, `init_chat_model`, checkpointer/store como parâmetros e documentação OpenWiki correspondente.

**Planejado, limitado, depreciado ou externo:** Deep Agents/Deployment/LangSmith como produtos externos; áudio MCP; registry de provedores não exaustiva; APIs `classic` e formatos antigos mantidos para compatibilidade; suporte de protocolo moderno de elicitation quando servidor é legado; qualquer ACL/tenant isolation, DLP, sandbox de produção, retenção, SBOM ou política de custos que não esteja no consumidor.

**Não inferido:** não foi assumido que uma capability documentada em OpenWiki implica garantia para todo provedor; não foi assumida segurança por mera presença de middleware; não foi assumido que exemplos são production-ready; não foi assumida licença uniforme para dependências transitivas.

**Arquivo do relatório:** `/home/ubuntu/reports/phase2/05-05-langchain.md`

# Análises da Fase 2 — Manus

Esta pasta reúne as análises detalhadas já concluídas para a Fase 2 do Brain. Cada relatório foi produzido a partir da inspeção de um snapshot fixado do repositório correspondente e contém arquitetura, fluxo operacional, inventário dos arquivos relevantes, ferramentas, Skills, modos de operação, pontos fortes, limites, riscos e recomendações de absorção.

## Relatórios disponíveis

| Ordem | Repositório | Relatório | Foco principal |
|---:|---|---|---|
| 1 | `openai/codex` | [`01-openai-codex.md`](./01-openai-codex.md) | Execução local, sandbox, aprovações, ferramentas, MCP, protocolo e persistência de sessões. |
| 2 | `anomalyco/opencode` | [`02-opencode.md`](./02-opencode.md) | Sessões, agentes, catálogo de ferramentas, permissões, plugins, SDK, API e eventos. |
| 3 | `affaan-m/ECC` | [`04-ecc.md`](./04-ecc.md) | Skills, agentes especializados, hooks, instalação modular, memória e gates de segurança. |
| 4 | `langchain-ai/langchain` | [`05-langchain.md`](./05-langchain.md) | Runnables, ferramentas tipadas, middleware, grafos de agentes, memória e observabilidade. |

## Síntese preliminar de absorção

Os quatro repositórios convergem em uma arquitetura modular para o Brain. O núcleo deve separar **orquestração**, **contratos de ferramentas**, **políticas de permissão**, **execução em sandbox**, **persistência**, **extensões** e **observabilidade**. A recomendação recorrente é absorver interfaces e padrões, não copiar monorepos inteiros.

A prioridade mais alta é estabelecer um contrato de execução com ciclo de vida explícito: entrada, planejamento, chamada de ferramenta, aprovação, execução, retorno, interrupção, persistência e encerramento. O Codex contribui com a separação entre agente e executor protegido. O OpenCode contribui com sessões, eventos, SDK e API tipada. O ECC contribui com Skills declarativas, agentes especializados, hooks e instalação por perfil. O LangChain contribui com composição de etapas, middleware, estado tipado e interrupção humana.

A segunda prioridade é criar uma camada de extensão segura. Skills devem ser carregadas sob demanda, possuir metadados, versão, proveniência, permissões exigidas e testes. Ferramentas devem declarar schema de entrada, efeitos, risco, timeout, recursos acessados e necessidade de aprovação. A posse de uma ferramenta pelo agente não deve equivaler à autorização para executá-la.

A terceira prioridade é a rastreabilidade. Cada execução deve possuir `run_id`, `session_id`, `task_id`, eventos ordenados, decisões de aprovação, chamadas de ferramentas, resultados redigidos e referências de persistência. Memória e observabilidade devem ser interfaces substituíveis, com isolamento por projeto/usuário e proteção contra instruções armazenadas não confiáveis.

## Estado da análise

Os demais nove repositórios permanecem pendentes nesta pasta. O workflow automatizado original não conseguiu concluir todos os agentes por limite de créditos da sessão; portanto, nenhum relatório ausente deve ser tratado como analisado. Os materiais complementares recebidos na pasta [`../Claude/fase-b-13-repos/`](../Claude/fase-b-13-repos/) são uma fonte separada e devem ser comparados com os relatórios desta pasta antes de uma consolidação final.

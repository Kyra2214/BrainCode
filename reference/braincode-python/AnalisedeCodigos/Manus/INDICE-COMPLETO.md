# Fase 2 — Índice completo das análises salvas por Manus

Este índice reúne os 13 estudos da Fase 2 disponíveis em `análise de códigos/manus`. Os quatro primeiros foram produzidos por inspeção detalhada de snapshots de código. Os nove seguintes foram salvos a partir dos levantamentos técnicos fornecidos na pasta `Claude`; quando o levantamento não conseguiu confirmar o repositório exato, essa limitação está registrada no próprio relatório.

| Nº | Repositório | Arquivo | Capacidade estudada | Aplicação potencial no Brain |
|---:|---|---|---|---|
| 1 | `openai/codex` | [`01-openai-codex.md`](./01-openai-codex.md) | Runtime de agente, execução, sandbox, approvals, MCP e persistência | Contrato Brain–Sandbox e ciclo de execução seguro |
| 2 | `anomalyco/opencode` | [`02-opencode.md`](./02-opencode.md) | Sessões, API, eventos, agentes, permissões e plugins | Sessões duráveis, SDK e catálogo de ferramentas |
| 3 | `anthropics/skills` | [`03-anthropics-skills.md`](./03-anthropics-skills.md) | Especificação `SKILL.md`, marketplace e carregamento | Skills portáveis, versionadas e carregadas sob demanda |
| 4 | `affaan-m/ECC` | [`04-ecc.md`](./04-ecc.md) | Skills, agentes, hooks, memória, instalação e segurança | Catálogo modular, gates e hooks de ciclo de vida |
| 5 | `langchain-ai/langchain` | [`05-langchain.md`](./05-langchain.md) | Runnables, middleware, agentes, estado e observabilidade | Composição de tarefas e interrupção humana |
| 6 | `nikilster/clawflows` | [`06-clawflows.md`](./06-clawflows.md) | Workflows Markdown, ativação por symlink e agendamento | Tarefas recorrentes legíveis e ativação simples |
| 7 | `n8n-io/n8n` | [`07-n8n.md`](./07-n8n.md) | Motor de workflows, workers, filas, sub-workflows e memória | Execução distribuída e composição reutilizável |
| 8 | `StarTrail-org/LEANN` | [`08-leann.md`](./08-leann.md) | Índice vetorial local com recomputação seletiva | Memória/RAG econômico em armazenamento |
| 9 | `cporter202/best-apis-for-lead-gen` | [`09-best-apis-lead-gen.md`](./09-best-apis-lead-gen.md) | Catálogo de APIs e evolução para Workers/MCP | Descoberta de APIs com schemas e conectores chamáveis |
| 10 | `cporter202/job-data-apis-and-scrapers` | [`10-job-data-apis.md`](./10-job-data-apis.md) | Curadoria de APIs oficiais e scrapers especializados | Fallback por disponibilidade, custo e termos de uso |
| 11 | `cporter202/software-income-playbooks` | [`11-software-income-playbooks.md`](./11-software-income-playbooks.md) | API → oportunidade → MVP → GTM | Avaliação de oportunidades e priorização de produtos |
| 12 | `zubair-trabzada/geo-seo-claude` | [`12-geo-seo-claude.md`](./12-geo-seo-claude.md) | Skill roteadora, sub-skills, fan-out e scoring | Auditorias especializadas com síntese estruturada |
| 13 | `Assemou007/OFFPack` | [`13-offpack.md`](./13-offpack.md) | Padrão de cache offline npm | Reprodutibilidade e operação sem rede; requer validação direta |

## Síntese de absorção

A combinação dos 13 estudos sugere que o Brain deve evoluir como uma plataforma de execução modular. O agente deve selecionar Skills e ferramentas por catálogo, mas a execução deve ocorrer em um Sandbox com políticas independentes, aprovações explícitas, limites de tempo e rastreabilidade. Workflows devem ser artefatos versionáveis e legíveis, enquanto tarefas persistentes precisam de scheduler, histórico separado e idempotência.

A camada de memória deve começar com uma interface substituível. O LEANN é referência para reduzir custo de armazenamento por recomputação seletiva, mas só deve ser incorporado após benchmark local comparando recall, latência, consumo de CPU/GPU e custo de reindexação. A camada de descoberta de APIs deve representar cada integração como um Worker com schema de entrada/saída, capacidades, custo, limites, termos de uso e fallback. APIs oficiais e scrapers devem ser tratados como fontes com níveis diferentes de estabilidade e risco jurídico.

A composição de agentes deve usar um padrão de descoberta, fan-out especializado e síntese. Cada subagente precisa receber escopo, ferramentas e permissões mínimas. O resultado deve ser estruturado, pontuado e acompanhado de evidências. O modelo GEO-SEO demonstra essa topologia em domínio vertical; o mesmo padrão pode servir para QA, segurança, descoberta, análise e validação do Brain.

## Limitações e proveniência

Os relatórios `09`, `10` e `13` registram que o repositório exato não foi confirmado diretamente nas fontes consultadas. Eles descrevem padrões do autor ou do ecossistema e não devem ser tratados como auditoria definitiva do código desses projetos. Antes de implementar qualquer componente com base nesses três estudos, deve-se clonar o repositório exato, fixar commit, verificar licença, examinar testes e confirmar a implementação.

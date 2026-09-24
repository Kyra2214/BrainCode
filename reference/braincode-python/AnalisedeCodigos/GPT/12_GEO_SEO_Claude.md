# 12 — zubair-trabzada/geo-seo-claude

## Pente fino adicional

Este é um dos melhores exemplos para o Braim de **especialização hierárquica**: uma Skill principal coordena sub-Skills, subagentes, scripts, scoring e síntese sem transformar tudo em um único prompt.

### Especialista estreito

Cada subagente deve receber apenas o contexto necessário para sua área. O Brain pode paralelizar especialistas e depois passar resultados para um sintetizador.

### Quality gates de pesquisa

Limites de páginas, concorrência, timeout, deduplicação e robots/terms devem estar em uma política compartilhada. Isso evita que cada Skill invente sua própria regra de pesquisa.

### Scoring explicável

O score deve guardar componentes individuais e evidências, não apenas um número final. Isso permite aprender depois quais critérios realmente predizem sucesso.

### Atualização segura

O mecanismo de update inspira um `SkillUpdater`: baixar nova versão, verificar origem/hash/licença, comparar diff, testar e somente então ativar.

## Objetivo

Estudar um exemplo completo de Skill especializada com sub-Skills, subagentes, comandos, scoring, relatórios, atualização e controle de qualidade.

## Arquitetura encontrada

O projeto possui uma Skill principal `geo/SKILL.md`, várias sub-Skills e cinco subagentes especializados. O fluxo de auditoria é dividido em descoberta, análise paralela e síntese.

## Esse padrão é quase um modelo do Braim

### Fase 1 — Discovery

A Skill identifica tipo de negócio, coleta páginas e prepara contexto.

### Fase 2 — Parallel Analysis

Cinco subagentes analisam áreas diferentes.

### Fase 3 — Synthesis

O resultado é agregado, pontuado e transformado em plano de ação.

O Braim precisa exatamente desse conceito para projetos complexos.

## Subagentes

O projeto separa AI visibility, platform analysis, technical, content e schema. Isso mostra que especialista deve ter escopo estreito, não prompt gigante.

## Scoring

A auditoria calcula score composto com pesos por categoria. O Braim deve aplicar scoring a agentes/APIs/Skills:

```text
score = quality + reliability + latency + cost + historical_success
```

Guardar também cada componente e evidência.

## Comandos

O conjunto `/geo audit`, `/geo quick`, `/geo technical`, `/geo content`, `/geo schema`, `/geo report` mostra como uma Skill pode oferecer uma superfície simples para vários procedimentos. No Braim, esses comandos seriam capabilities registradas, não hardcoded no parser.

## Quality gates

O projeto define limites de crawl, timeout, rate limiting, robots.txt e deduplicação. Isso é valioso para qualquer agente que pesquisa a web.

O Braim deve ter um `ResearchPolicy` com timeout, concorrência, limite de páginas, robots/terms, deduplicação, tamanho máximo de resposta e origem da informação.

## Update skill

A Skill `geo-update` compara a instalação com o upstream e atualiza skills/agentes/scripts/schema. Isso inspira versionamento e atualização segura do catálogo de Skills do Braim.

## Relatórios

O projeto gera Markdown e PDF estruturados. O Braim pode separar `raw_result` de `deliverable`, permitindo que uma mesma execução produza diferentes formatos.

## O que absorver

- Skill principal + sub-Skills;
- agentes especialistas;
- pipeline discovery → parallel → synthesis;
- scoring explicável;
- quality gates;
- comandos como capabilities;
- atualização/versionamento;
- outputs estruturados;
- relatórios;
- armazenamento persistente;
- limites de pesquisa;
- update seguro com diff/test antes de ativação.

## O que não absorver

- lógica específica de SEO/GEO como core;
- métricas de mercado sem validação;
- instalador automático sem auditoria;
- dados externos como fatos permanentes.

## Licença

O repositório usa MIT. Ainda assim, dependências e recursos individuais devem ser conferidos antes de cópia literal.

## Prioridade

**ALTA.** É um excelente modelo para o primeiro agente especialista real do Braim.

## Fontes

- https://github.com/zubair-trabzada/geo-seo-claude
- https://github.com/zubair-trabzada/geo-seo-claude/blob/main/geo/SKILL.md
- https://github.com/zubair-trabzada/geo-seo-claude/blob/main/docs/architecture.md
- https://github.com/zubair-trabzada/geo-seo-claude/blob/main/install.sh
- https://github.com/zubair-trabzada/geo-seo-claude/blob/main/LICENSE

## Conclusão

O aprendizado mais importante é a estrutura **um problema grande → descoberta → especialistas paralelos → síntese → score → plano de ação**. O pente fino acrescenta score explicável e atualização segura de Skills como padrões nativos do Brain.

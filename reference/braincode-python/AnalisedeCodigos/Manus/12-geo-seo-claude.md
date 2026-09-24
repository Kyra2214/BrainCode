# Fase B — zubair-trabzada/geo-seo-claude: tecnologias, ferramentas e técnicas

> Levantamento com base no README e arquitetura real do repositório
> (confirmado via busca). Sem propor mudança em nada.

## 1. Estrutura: skill orquestradora + sub-skills especializadas

```
geo-seo-claude/
├── geo/
│   └── SKILL.md          # orquestrador principal — comandos & roteamento
└── skills/                # 11-13 sub-skills especializadas
    ├── geo-audit/          # orquestração e scoring do audit completo
    ├── geo-citability/     # score de prontidão pra citação por IA
    ├── geo-crawlers/       # análise de acesso de crawler de IA (robots.txt)
    ├── geo-llmstxt/        # análise/geração de llms.txt
    ├── geo-brand-mentions/ # presença de marca em plataformas citadas por IA
    ├── geo-platform-optimizer/  # otimização por plataforma de busca de IA
    ├── geo-schema/         # dado estruturado pra descoberta por IA
    ├── geo-technical/       # fundamentos técnicos de SEO
    └── ...
```

Uma skill "roteadora" (`geo/SKILL.md`) decide qual comando/sub-skill
acionar, e cada sub-skill é uma capacidade isolada e testável
independentemente.

## 2. Orquestração paralela de sub-agentes por especialidade

Um único comando (`/geo audit <url>`) dispara uma fase de descoberta
(busca a homepage, detecta tipo de negócio) e depois **5 sub-agentes em
paralelo**, cada um com uma especialidade:
1. AI Visibility (citabilidade + crawlers + llms.txt + menções de marca)
2. Platform Analysis (cobertura em ChatGPT/Perplexity/AI Overviews)
3. Technical SEO (Core Web Vitals + SSR + segurança)
4. Content Quality (E-E-A-T + legibilidade + atualidade)
5. Schema Markup (detecção + validação + geração)

Os 5 resultados convergem numa etapa de **síntese**, que gera um score
composto (0–100) e um plano de ação priorizado — o mesmo padrão de
"discovery → fan-out paralelo → synthesis" observado em outros
repositórios da Fase B (ex.: subagentes do Codex), aqui aplicado a um
domínio de negócio específico (SEO/GEO) em vez de codificação.

## 3. Conceito de domínio: GEO (Generative Engine Optimization)

O projeto inteiro gira em torno de um conceito relativamente novo: otimizar
um site para ser **citado por buscadores de IA** (ChatGPT, Perplexity,
AI Overviews), não só para ranquear em buscadores tradicionais. Isso
inclui analisar o padrão `llms.txt` (um arquivo de convenção emergente,
análogo ao `robots.txt`, que sinaliza a crawlers de IA como o site quer
ser indexado/citado) e verificar quais dos 14+ crawlers de IA conhecidos
têm acesso liberado no `robots.txt` do site.

## 4. Saída como relatório profissional, não só texto de chat

Comando dedicado `/geo report-pdf` gera um relatório em PDF pronto pra
cliente, com gráficos e visualizações — a skill não entrega só uma
resposta textual, ela entrega um artefato de entrega formal.

## 5. Instalação como skill via script, não como app separado

`install.sh` baixado e executado via `curl | bash`, que instala a skill
dentro de `~/.claude/skills/` e `~/.claude/agents/` — segue exatamente o
padrão de instalação observado em `anthropics/skills` (paths por
harness), aqui aplicado a uma skill de terceiro especializada num domínio
vertical (SEO/GEO), não genérica.

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- Padrão "skill orquestradora + N sub-skills especializadas", cada uma
  isolável e testável — modelo de composição por domínio vertical.
- Fan-out paralelo de sub-agentes especializados seguido de uma etapa de
  síntese com score composto — o mesmo padrão de orquestração observado
  no estudo de subagentes do Codex, aqui validado num domínio de negócio
  diferente (SEO/GEO), o que sugere que é um padrão genérico de "audit
  multi-dimensional", não específico de código.
- Conceito de arquivo de convenção (`llms.txt`) como sinalização explícita
  para agentes de IA sobre como um recurso quer ser tratado — análogo ao
  `robots.txt`, mas dirigido a crawlers de IA especificamente.
- Entrega de artefato formal (PDF com gráficos) como parte da skill, não
  só resposta em texto.

# Auditoria aprofundada — zubair-trabzada/geo-seo-claude

**Snapshot:** `3909885af181d9141ec736d1d094c8863d60267f` · **Arquivos:** 71 · **Fonte:** https://github.com/zubair-trabzada/geo-seo-claude

## Conclusão
É uma Skill vertical implementada: roteador de comandos, 13 sub-Skills, 5 agentes paralelos, scripts Python, schemas JSON-LD, armazenamento de prospectos e geração de relatórios Markdown/PDF. É um exemplo concreto de pipeline discovery → fan-out → synthesis com score composto.

## Arquivos e fluxo
`geo/SKILL.md` roteia `/geo audit`, `quick`, `citability`, `crawlers`, `llmstxt`, `brands`, `platforms`, `schema`, `technical`, `content`, `report` e `report-pdf`. `skills/` contém `geo-audit`, `geo-citability`, `geo-crawlers`, `geo-llmstxt`, `geo-brand-mentions`, `geo-platform-optimizer`, `geo-schema`, `geo-technical`, `geo-content`, `geo-report`, `geo-report-pdf`, `geo-prospect`, `geo-proposal` e `geo-compare`. `agents/` contém cinco especialistas. `scripts/fetch_page.py`, `citability_scorer.py`, `brand_scanner.py`, `llmstxt_generator.py`, `webapp/app.py` implementam utilidades. `schema/` contém seis JSON-LD. `tests/test_fetch_page_ssr.py` valida fetch SSR; exemplos incluem JSON, proposta, relatório rápido e PDF.

## Operação
Discovery busca homepage, tipo de negócio e sitemap. Cinco agentes avaliam visibilidade, plataforma, técnico, conteúdo e schema. A síntese calcula score 0–100 com pesos: citabilidade 25%, autoridade 20%, conteúdo 20%, técnico 15%, structured data 10%, plataforma 10%. Dados persistem em `~/.geo-prospects/`.

## Absorção
Adotar o padrão de Skill roteadora e sub-Skills. Exigir schema de entrada/saída por especialista, timeout, evidência por achado, score explicável, confiança e síntese determinística. O mesmo runtime serve para QA, segurança, descoberta e análise do Brain. Relatórios devem ser artefatos com versão, não apenas chat.

## Riscos
O instalador `curl | bash` e venv externo precisam de pinagem. Claims de mercado no README devem ser tratados como claims, não como evidência operacional. Dados de prospects e relatórios ficam fora do diretório e não são removidos pelo uninstaller, o que exige retenção explícita.

## Referências
[1]: https://github.com/zubair-trabzada/geo-seo-claude "GEO-SEO Claude"
[2]: https://github.com/zubair-trabzada/geo-seo-claude/tree/main/agents "GEO subagents"
[3]: https://github.com/zubair-trabzada/geo-seo-claude/tree/main/scripts "GEO scripts"

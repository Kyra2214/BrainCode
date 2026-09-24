# Auditoria aprofundada — cporter202/job-data-apis-and-scrapers

**Snapshot:** `b88fa340d2d17da54a9822f88951a13630788031` · **Arquivos:** 17 · **Fonte:** https://github.com/cporter202/job-data-apis-and-scrapers

## Conclusão
É um catálogo especializado de dados de vagas, sinais de contratação, salários e perfis de empregadores. A implementação principal é conteúdo, mas existe automação real de sincronização diária.

## Evidência
`catalog/README.md` lista 1.149 Apify job actors; `catalog/coreclaw.md` lista 12 workers com API/MCP; `playbooks/` cobre agregação, sinais e seleção de provider; `examples/README.md` mostra uso. `.github/workflows/daily-sync.yml` agenda `23 10 * * *` no fuso `America/New_York`, limita execução a 10 minutos, usa Node 22, roda `node settings/sync_catalog.js`, adiciona somente arquivos de catálogo e faz commit automático. `CONTRIBUTING.md` exige descrição neutra, cobertura, formato, preço verificado e limitações.

## Absorção
O Brain deve separar `SourceCatalog` de `SourceAdapter`. Cada fonte recebe categoria, cobertura, formato, freshness, custo, termos, limites e score. O sync deve gerar diff auditável, nunca alterar código de execução. Para job data, criar canonical schema de vaga, deduplicação por URL/hash, normalização de salário/local e `signal_type` para hiring/funding/skills.

## Riscos
Termos de job boards, robots, privacidade e legislação de emprego são limites explícitos do repo. Não permitir profiling automatizado ou decisão de contratação. A atualização diária precisa de validação de schema e rollback.

## Referências
[1]: https://github.com/cporter202/job-data-apis-and-scrapers "Job Data APIs and Scrapers"
[2]: https://github.com/cporter202/job-data-apis-and-scrapers/blob/main/.github/workflows/daily-sync.yml "Daily catalog sync"

# Auditoria aprofundada — cporter202/best-apis-for-lead-gen

**Snapshot:** `e652db57e581ef6eb514bf139b894955e59b5460` · **Arquivos:** 79 · **Fonte:** https://github.com/cporter202/best-apis-for-lead-gen

## Conclusão
É um catálogo curado e operacional, não um dump. O repositório contém 30 perfis de API, 10 workflows, 10 playbooks, 12 categorias e templates. Seu valor para o Brain é o esquema de conhecimento e a transição de descoberta passiva para integração chamável.

## Estrutura direta
`apis/` traz perfis com uso, forças, limitações, preço e fit. `workflows/` inclui `waterfall-enrichment-workflow`, `local-business-lead-machine`, `hiring-signal-outbound-workflow`, `directory-mining-workflow` e outros. `playbooks/` traduz APIs em ofertas. `categories/` organiza descoberta, enrichment, email, job signals e intent. `templates/workflow-template.md` padroniza contribuições. `resources/` documenta critérios e compliance.

## Modelo operacional
O workflow de waterfall tenta a fonte de maior qualidade; se falhar ou faltar campo, chama a próxima, preservando provenance e custo. O padrão evita acoplamento a um provedor único. Os perfis são documentação, não SDK: o Brain precisará de um adapter real com schema, credencial, rate limit, retry e contrato de dados.

## Absorção
Criar `ApiCatalogEntry` com `provider`, `capabilities`, `input_schema`, `output_schema`, `cost`, `latency`, `rate_limit`, `legal_constraints`, `provenance` e `fallback_group`. Criar `Worker` chamável por REST/MCP e registrar cada tentativa do waterfall. Importar categorias como intents de descoberta, não como permissões.

## Riscos
Scraping pode violar termos; dados pessoais exigem base legal e retenção; links afiliados não são garantia de qualidade. O catálogo envelhece, então precisa de health checks, data de revisão e score de confiança.

## Referências
[1]: https://github.com/cporter202/best-apis-for-lead-gen "Best APIs for Lead Gen"
[2]: https://github.com/cporter202/best-apis-for-lead-gen/tree/main/workflows "Lead generation workflows"

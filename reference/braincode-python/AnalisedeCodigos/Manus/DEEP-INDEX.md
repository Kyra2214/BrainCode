# Fase 2 — Auditoria aprofundada dos nove repositórios

Estas versões substituem os levantamentos superficiais anteriores. Foram produzidas após clone direto dos repositórios e fixação dos commits abaixo. Cada relatório identifica arquivos, fluxo, testes, configuração, limitações, riscos e absorções propostas para o Brain.

| Repositório | Commit auditado | Relatório aprofundado |
|---|---|---|
| `anthropics/skills` | `34040c9c568585f6929bedeaad110ad08f079624` | [`deep/03-anthropics-skills.md`](./deep/03-anthropics-skills.md) |
| `nikilster/clawflows` | `f1e4094752b0359c7a3089720457a536a4ae2813` | [`deep/06-clawflows.md`](./deep/06-clawflows.md) |
| `n8n-io/n8n` | `46032052308219efadd5a348692e0a305ca4e524` | [`deep/07-n8n.md`](./deep/07-n8n.md) |
| `StarTrail-org/LEANN` | `9b786b024c983ad7fcafe998fd8fa90fd10657e4` | [`deep/08-leann.md`](./deep/08-leann.md) |
| `cporter202/best-apis-for-lead-gen` | `e652db57e581ef6eb514bf139b894955e59b5460` | [`deep/09-best-apis-lead-gen.md`](./deep/09-best-apis-lead-gen.md) |
| `cporter202/job-data-apis-and-scrapers` | `b88fa340d2d17da54a9822f88951a13630788031` | [`deep/10-job-data-apis.md`](./deep/10-job-data-apis.md) |
| `cporter202/software-income-playbooks` | `8b07008c9fefcc5bbeb223e9d8ad5433271a9dcd` | [`deep/11-software-income-playbooks.md`](./deep/11-software-income-playbooks.md) |
| `zubair-trabzada/geo-seo-claude` | `3909885af181d9141ec736d1d094c8863d60267f` | [`deep/12-geo-seo-claude.md`](./deep/12-geo-seo-claude.md) |
| `Assemou007/OFFPack` | `9e92ba8168bb512ab2076561cfa5555bf7645f16` | [`deep/13-offpack.md`](./deep/13-offpack.md) |

## Correções em relação à versão anterior

A versão anterior foi baseada em levantamentos resumidos e, em três casos, não confirmou o repositório exato. Esta versão confirma os nove clones diretamente. O `n8n` foi auditado como monorepo com 28.581 arquivos, o `anthropics/skills` com 419, o `LEANN` com 301 e o `Clawflows` com 182. O `OFFPack` foi confirmado como implementação Go real, mas seus próprios arquivos registram ausência de testes e limitações no resolver semver.

## Critério de absorção

As recomendações diferenciam **formato**, **interface**, **código reutilizável** e **ideia conceitual**. Nenhuma funcionalidade deve ser copiada para o Brain sem revisão de licença, pinagem de dependências, testes, threat model e adaptação ao contrato Brain ↔ Sandbox.

# Fase B — cporter202/job-data-apis-and-scrapers: tecnologias, ferramentas e técnicas

> Nota de transparência: a busca não retornou o repositório exato com esse
> nome, mas retornou repositórios do mesmo autor com padrão idêntico de
> organização (`scraping-apis-for-devs`, que já inclui uma pasta
> `jobs-apis-167/` dedicada, e `API-mega-list`, com `jobs-apis-1149/`).
> O levantamento abaixo descreve esse padrão, aplicável ao repositório de
> dados de vagas. Sem propor mudança em nada.

## 1. Categoria "jobs" como recorte específico de um catálogo maior

Nos repositórios irmãos do mesmo autor, "jobs-apis" já existe como uma
categoria entre várias outras (lead-generation, real-estate, seo-tools,
social-media, travel, videos, etc.) — ou seja, o padrão do autor é manter
um catálogo geral e também extrair recortes temáticos como repositórios
próprios focados (este parece ser exatamente esse tipo de extração,
focado só em dados de vagas de emprego + scrapers).

## 2. Combinação "API oficial" + "scraper" na mesma categoria

O nome do repositório ("apis-and-scrapers") já indica que a curadoria
mistura dois tipos de fonte de dado de vaga:
- **APIs oficiais** de job boards (quando existem e têm termos de uso
  favoráveis).
- **Scrapers** (quando não há API oficial, ou a API oficial é limitada/
  paga demais para o caso de uso).

Essa combinação é relevante como padrão de curadoria: para uma categoria
de dado específica (vagas), nem sempre a melhor fonte é uma API formal —
às vezes é um scraper mantido pela comunidade.

## 3. Estrutura de repositório-satélite dentro do mesmo ecossistema de autor

O autor mantém múltiplos repositórios especializados a partir do mesmo
catálogo-mãe (`API-mega-list`/`scraping-apis-for-devs`), cada um recortado
por categoria ou monetização (ver `software-income-playbooks`). Isso é um
padrão de "um catálogo grande, N recortes publicados separadamente" —
cada recorte é mais fácil de descobrir e de manter atualizado
isoladamente do que uma lista monolítica gigante.

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- Padrão de curadoria que mistura API oficial + scraper na mesma
  categoria, reconhecendo que a fonte "certa" de dado depende de
  disponibilidade/custo/termos de uso, não de uma preferência fixa por
  API formal.
- Estratégia de publicar recortes temáticos separados a partir de um
  catálogo-mãe maior, em vez de manter só uma lista monolítica.

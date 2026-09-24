# Fase B — cporter202/best-apis-for-lead-gen: tecnologias, ferramentas e técnicas

> Nota de transparência: a busca não retornou o repositório exato com esse
> nome, mas retornou outros repositórios do mesmo autor (cporter202) com
> padrão idêntico de organização — `scraping-apis-for-devs`,
> `API-mega-list`, `coreclaw-api-directory` — todos curadoria de APIs por
> categoria. O levantamento abaixo descreve esse padrão consistente do
> autor, que deve se aplicar igualmente ao repositório de lead-gen. Sem
> propor mudança em nada.

## 1. Estrutura: pastas por categoria com contagem no próprio nome

Convenção observada em todos os repositórios do autor:

```
lead-generation-apis-80/
jobs-apis-167/
seo-tools-apis-159/
real-estate-apis-130/
...
```

O número no nome da pasta é a contagem de APIs catalogadas naquela
categoria — visível direto na navegação do repositório, sem precisar
abrir a pasta. Simples, mas funciona como indicador de tamanho/cobertura
instantâneo.

## 2. Arquivo de convenção para dar crédito a criadores de API

`FOLLOW_CREATOR.md` — um arquivo dedicado documentando a convenção de
como creditar/seguir o criador de cada API listada, separado do conteúdo
técnico em si.

## 3. Escala e curadoria contínua

O repositório irmão `API-mega-list` do mesmo autor chega a milhares de
entradas por categoria (ex.: `lead-generation-apis-4431`,
`automation-apis-5653`), o que indica um processo de agregação/curadoria
contínuo e em grande escala, não uma lista estática montada uma vez.

## 4. Variante "Workers" com schema estruturado (coreclaw-api-directory)

Uma variante mais recente do mesmo autor reorganiza APIs como "Workers"
— cada um com input schema e output estruturado próprios, expostos por
uma camada de integração compartilhada REST + MCP. Ou seja, o autor está
migrando de "lista de links pra API de terceiro" para "wrapper
padronizado com schema uniforme e exposição MCP" — uma evolução relevante
de observar (curadoria de link vira integração ativa).

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- Convenção de nomear pasta com contagem embutida (`categoria-N/`) como
  forma barata de comunicar escala sem abrir a pasta.
- Separação de convenção de atribuição (`FOLLOW_CREATOR.md`) do conteúdo
  técnico.
- Evolução observada no ecossistema do mesmo autor: de "lista curada de
  links" para "Workers" com schema de entrada/saída padronizado e camada
  de exposição MCP — relevante como referência de como transformar um
  catálogo passivo (lista) num catálogo ativo (integração chamável).

# Fase B — cporter202/software-income-playbooks: tecnologias, ferramentas e técnicas

> Levantamento com base no README real do repositório (confirmado via
> busca). Sem propor mudança em nada.

## 1. Problema que o repositório resolve

O próprio README é explícito sobre a lacuna que preenche: coleções de API
comuns dizem **o que existe**, mas não dizem o que construir com aquilo,
quem pagaria por isso, qual seria o MVP, quão rápido dá pra lançar, ou
como transformar a API em receita recorrente. O repositório existe pra
responder essas perguntas rápido, não só listar APIs.

## 2. Estrutura em camadas (não é só uma lista)

| Camada | Conteúdo |
|---|---|
| Playbooks | ideia de negócio passo a passo: cliente, dor, MVP, preço, go-to-market |
| APIs curadas | APIs de alto sinal com pontos fortes, ressalvas e ângulo de produto |
| Categorias | mapas de oportunidade por mercado (lead gen, recrutamento, negócio local, SEO, imobiliário, hospitalidade...) |
| Templates | formatos reutilizáveis pra adicionar nova ideia, perfil de API, spec de MVP, plano de GTM |
| Recursos | docs práticos sobre como escolher API, validar ideia, precificar micro-SaaS/serviço produtizado |

## 3. Perfil de API com estrutura de avaliação, não só descrição

Cada API listada vem com "pontos fortes, ressalvas e ângulo de produto" —
ou seja, o perfil de uma API não é neutro/descritivo, já vem com uma
avaliação de aplicabilidade comercial embutida (o que ela faz bem, onde
ela falha, e uma ideia concreta de produto que se constrói em cima dela).

## 4. Templates como parte central do repositório, não um extra

Diferente de uma lista comum, o repositório expõe explicitamente
templates reutilizáveis para 4 tipos de conteúdo: ideia nova, perfil de
API, spec de MVP, plano de go-to-market — ou seja, o repositório é
desenhado desde o início pra crescer por contribuição estruturada, não
só por PRs ad-hoc de "adicionar mais uma linha na lista".

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- Estrutura de "perfil de API com avaliação de aplicabilidade embutida"
  (pontos fortes + ressalvas + ângulo de produto), em vez de descrição
  neutra — um formato de metadado mais rico que "nome + link + o que
  faz".
- Templates explícitos e versionados para os 4 tipos de conteúdo do
  repositório, como mecanismo de manter contribuições consistentes em
  escala.
- Camada de "categoria como mapa de oportunidade por mercado", separada
  da lista de APIs em si — agrupamento por caso de uso de negócio, não só
  por tipo técnico de API.

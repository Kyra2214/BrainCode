# 09 — cporter202/best-apis-for-lead-gen

## Pente fino adicional

O maior achado é a estrutura em `apis/`, `workflows/`, `playbooks/`, `categories/`, `templates/` e `resources/`. Isso sugere que o catálogo do Braim deve separar **recurso individual**, **capacidade composta** e **receita operacional**.

### API como recurso avaliável

Não basta guardar endpoint. O registro precisa responder: o que faz, como autentica, quanto custa, qual free tier existe, limites, qualidade, termos, estabilidade, latência e quando foi verificado.

### Waterfall por qualidade

O fallback não deve acontecer somente por HTTP 500. Uma resposta pode ser tecnicamente válida e ainda ser insuficiente. O `ProviderSelector` precisa aceitar sinais como ausência de campos, baixa confiança, timeout, quota esgotada e score histórico.

### Minerador de APIs

```text
Discover
→ classify
→ extract metadata
→ check terms
→ detect free tier
→ test endpoint
→ measure latency
→ normalize result
→ score
→ catalog
→ monitor
```

Isso deve virar uma capability própria do Brain.

## Objetivo

Estudar como organizar um catálogo de APIs por capacidade, workflow, risco, qualidade e utilidade prática.

## Estrutura encontrada

O repositório separa:

- `apis/` — perfis individuais;
- `workflows/` — combinações de APIs;
- `playbooks/` — produtos/serviços que podem ser construídos;
- `categories/` — indexação por caso de uso;
- `templates/` — padrão para adicionar novos recursos;
- `resources/` — critérios e estratégias.

Essa estrutura é extremamente compatível com o catálogo do Braim.

## API não é apenas endpoint

Cada API possui contexto de uso, strengths, weaknesses, workflow fit, preço e riscos. O catálogo do Braim deve armazenar mais que URL.

## Schema sugerido

```text
ApiCapability
  provider
  product
  endpoint
  capabilities[]
  categories[]
  auth_type
  pricing
  free_tier
  quota_model
  limits
  quota_remaining
  quota_reset
  card_required
  commercial_use
  legal_notes
  terms_url
  documentation_url
  latency_p50
  latency_p95
  quality_score
  reliability_score
  success_rate
  last_verified
  last_success
  last_error
  source
```

O catálogo deve distinguir **“gratuito”** de **“gratuito com restrições”**, **trial** e **pago**.

## Waterfall

O repositório possui workflows de enriquecimento em cascata. Isso confirma a arquitetura de fallback do Braim:

```text
Provider A
  ↓ falhou / quota / timeout / resultado ruim
Provider B
  ↓ falhou / quota / timeout / resultado ruim
Provider C
  ↓
resultado final
```

Não basta trocar API quando ocorre erro HTTP. O Brain deve detectar resultado incompleto e qualidade insuficiente.

## Workflows

Os workflows mostram como transformar várias APIs em uma capacidade maior. O Braim deve aprender a montar pipelines compostos.

Exemplo:

```text
Pesquisa
→ descoberta
→ enriquecimento
→ normalização
→ validação
→ scoring
→ relatório
```

## Curation score

A grande lição é preferir poucos recursos úteis a um dump gigantesco. O catálogo do Braim deve ter status:

- descoberto;
- não validado;
- validado;
- recomendado;
- degradado;
- bloqueado;
- expirado.

## Separação entre descoberta e confiança

O fato de uma API aparecer em um catálogo externo não significa que ela seja recomendada. O Brain deve guardar `source` e evidências e realizar sua própria validação.

Links afiliados devem ser identificados e não podem aumentar o score técnico.

## O que absorver

- catálogo por capacidade;
- categorias;
- perfis de API;
- workflows de combinação;
- templates;
- waterfall/fallback;
- critérios de seleção;
- compliance notes;
- atualização do catálogo;
- avaliação pela qualidade real do resultado;
- quota dinâmica;
- histórico de sucesso/erro;
- score técnico separado de confiança da fonte.

## O que não absorver

- URLs afiliadas como fonte de confiança;
- qualquer API como dependência fixa;
- catálogo estático sem verificação;
- ranking comercial como ranking técnico.

## Prioridade

**ALTA** para o “minerador de APIs gratuitas”.

## Aplicação direta no Braim

O catálogo deve permitir ao Brain responder:

> “Preciso de geração de imagem gratuita, sem cartão, com uso comercial e baixa latência. Quais recursos validados estão disponíveis agora?”

E selecionar por requisitos reais, não por popularidade.

## Fonte

https://github.com/cporter202/best-apis-for-lead-gen

## Conclusão

Esse repositório fornece quase diretamente o modelo do **catálogo inteligente de recursos** do Braim. O pente fino acrescenta descoberta → validação → normalização → score → monitoramento, além de quota dinâmica, restrições comerciais e separação entre confiança da fonte e qualidade técnica.
# 10 — cporter202/job-data-apis-and-scrapers

## Pente fino adicional

O ponto mais valioso é o catálogo vivo: API não é um registro permanente. Preço, endpoint, limite, cobertura e disponibilidade mudam. O Brain precisa tratar o catálogo como dado versionado e verificável.

### Pipeline de atualização

```text
Discovery
→ metadata
→ normalize
→ terms/license
→ live test
→ quality score
→ diff catalog
→ publish new version
```

### Normalização antes do ranking

Diferentes providers retornam formatos diferentes. O `ApiEvaluator` deve converter respostas para um schema interno antes de comparar qualidade.

### Dados de cobertura

Registrar país, idioma, frescor, paginação, campos disponíveis, deduplicação e formato é tão importante quanto preço.

### Automação

A sincronização diária demonstra que o catálogo pode ser atualizado automaticamente. No Braim isso deve ser um job do próprio sistema, com histórico e rollback do catálogo.

## Objetivo

Estudar descoberta de fontes, sincronização automática de catálogo, seleção de provider e normalização de dados.

## O que mais interessa

O repositório mantém catálogos de APIs/actors para dados de vagas e hiring signals. O catálogo possui manutenção automática por GitHub Actions e sincronização diária.

## Lição principal: catálogo vivo

O catálogo de APIs do Braim não deve ser um arquivo congelado. Recursos mudam preço, disponibilidade, limite, endpoint e qualidade.

Precisamos de:

```text
Discovery → Fetch metadata → Validate → Test → Compare → Update catalog
```

## Provider selection checklist

Comparar cobertura, país/idioma, frescor, paginação, qualidade dos campos, deduplicação, formato estruturado, API/MCP, rate limits, preço e termos.

Essa checklist deve virar parte do `ApiEvaluator` do Braim.

## Sync

Existe comando local `node settings/sync_catalog.js` e automação diária. O Brain pode ter um `ResourceMiner` que roda periodicamente quando houver servidor persistente.

## Normalização

A ideia de normalizar dados de múltiplas fontes é fundamental. O Brain deve transformar respostas diferentes em um schema interno antes de avaliar qualidade.

## O que absorver

- catálogo sincronizado;
- verificação diária;
- provider selection checklist;
- comparação de cobertura;
- avaliação de frescor;
- deduplicação;
- normalização;
- classificação por caso de uso;
- automação de atualização;
- versionamento/diff do catálogo;
- testes reais antes de recomendar provider.

## Aplicação além de APIs de emprego

O mesmo mecanismo serve para APIs de IA, imagem, vídeo, música, voz, pesquisa, scrapers e ferramentas locais.

## Cuidado

O repositório preserva parâmetros de referral existentes. Isso não deve ser tratado como recomendação independente.

## Prioridade

**ALTA** para o sistema “Minerar API Gratuita”.

## Fonte

https://github.com/cporter202/job-data-apis-and-scrapers

## Conclusão

A principal absorção é o conceito de **catálogo vivo + avaliação objetiva + sincronização automática**. O pente fino acrescenta normalização, testes live, versionamento e comparação de alterações como partes do catálogo.

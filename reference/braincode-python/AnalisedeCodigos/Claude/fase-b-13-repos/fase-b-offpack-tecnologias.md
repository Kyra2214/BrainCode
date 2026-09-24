# Fase B — Assemou007/OFFPack: tecnologias, ferramentas e técnicas

> Nota de transparência importante: a busca não localizou o repositório
> `Assemou007/OFFPack` especificamente — só retornou projetos genéricos de
> cache offline de npm de outros autores (`offline-npm`, `local-npm`,
> `offline-first-storage`) e documentação oficial do próprio npm sobre
> modo offline. Não dá pra confirmar detalhes específicos da
> implementação do OFFPack sem acesso direto ao repositório. O que segue é
> o levantamento do **padrão geral** de cache offline de dependências npm
> (baseado na descrição que você já tinha dado do projeto + nas
> ferramentas equivalentes encontradas), não uma confirmação do código
> real do OFFPack.

## 1. O problema que esse tipo de ferramenta resolve

`npm install` sem rede falha se alguma dependência (ou sub-dependência)
não estiver no cache local. Dá pra usar `--prefer-offline` (usa cache
quando disponível, só bate na rede pra metadado) ou `--offline`
(exclusivamente cache, erro se faltar algo) — mas nenhum dos dois resolve
o problema de **preparar** o cache com antecedência.

## 2. Padrão comum entre ferramentas desse tipo

- Ler `package.json`/`package-lock.json` do projeto.
- Baixar cada dependência (e sub-dependências transitivas) listada no
  lockfile pra um cache/pasta local antes de precisar instalar offline.
- Empacotar isso como um `.tgz` (formato nativo de pacote npm) ou como
  uma pasta de cache que pode ser transferida pra outra máquina.
- Instalar depois via `npm install <arquivo>.tgz` ou apontando o registry
  do npm pra um proxy/servidor local que serve do cache em vez da rede.

## 3. Riscos documentados nesse tipo de abordagem (achados de ferramentas
similares, não do OFFPack em si)

- Mudanças no próprio npm podem quebrar abordagens antigas (ex.: o
  `offline-npm` documenta que versões mais novas do npm chamam o script
  `preinstall` antes de ele conseguir interceptar como servidor de
  registry, quebrando o mecanismo).
- `--prefer-offline`/`--offline` ainda podem falhar com `ENOTCACHED` ou
  erro de dependência ausente se o cache não tiver **todas** as
  transitivas, não só as diretas.
- Boa prática recomendada: gerar e commitar artefatos de auditoria (árvore
  de dependências, manifesto do cache) pra saber com antecedência se algo
  vai faltar antes de ir offline de verdade.

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- **Ação recomendada, não uma conclusão**: como não foi possível
  confirmar o conteúdo real do `Assemou007/OFFPack` por busca, vale
  revisitar esse item mais tarde com acesso direto ao repositório (clone
  local, quando a Fase A/H estiver mais madura e rede estiver disponível
  no ambiente de execução), em vez de tratar este documento como
  levantamento definitivo.
- Do padrão geral do ecossistema (não do OFFPack especificamente): gerar
  artefato de auditoria de dependências (árvore + manifesto de cache)
  antes de qualquer tentativa de instalação offline, pra saber com
  antecedência o que vai faltar.

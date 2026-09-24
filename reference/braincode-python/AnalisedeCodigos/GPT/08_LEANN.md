# 08 — StarTrail-org/LEANN

## Pente fino adicional

LEANN é mais interessante como **índice semântico sobre uma fonte de verdade estruturada** do que como banco principal do Brain.

### Memória em duas camadas

```text
SQLite / structured store = verdade
LEANN = recuperação semântica

Experience → structured record → semantic index
```

Se o índice for perdido, ele deve poder ser reconstruído a partir da memória estruturada.

### Pesquisa híbrida

O Brain deve primeiro aplicar filtros baratos (projeto, linguagem, agente, provider, erro, data, sucesso) e só então usar busca semântica. Depois, ranking histórico decide quais experiências merecem entrar no contexto.

### Memória não é prompt

Resultado de busca é evidência/contexto; nunca deve virar automaticamente uma instrução confiável. A origem, data e score precisam acompanhar cada memória recuperada.

### Incrementalidade

Atualizar somente novos/alterados registros reduz custo e permite que o aprendizado comece no primeiro teste.

## Objetivo

Estudar memória semântica local, RAG, índice eficiente, MCP e recuperação de contexto para o aprendizado contínuo do Braim.

## Conceito central

LEANN busca reduzir drasticamente o armazenamento necessário para índices vetoriais usando recomputação seletiva baseada em grafos. O projeto é voltado para RAG local e privacidade.

## Por que isso importa para o Braim

O Braim vai registrar milhares/milhões de experiências ao longo do tempo. Não podemos mandar todo o histórico para o LLM.

Precisamos de:

```text
Memória estruturada
+
Busca semântica
+
Contexto limitado
```

## Ferramentas MCP

A integração LEANN MCP expõe:

- `leann_search` — busca semântica;
- `leann_list` — lista índices;
- `leann_build` — cria/atualiza índice;
- `leann_status` — mostra backend, modelo, quantidade de chunks, arquivos e tamanho.

Isso é uma referência excelente para um futuro `MemoryTool` do Braim.

## Incrementalidade

LEANN suporta atualização incremental do índice. Para o Brain isso significa não reconstruir toda a memória depois de cada execução.

## Organização

LEANN usa `.leann/` por projeto e um registro global de projetos. O Braim pode usar conceito semelhante:

```text
brain-memory/
  projects/
  global/
  experiences/
  skills/
  strategies/
```

## O que absorver

- busca semântica local;
- índices por projeto;
- registry global;
- atualização incremental;
- MCP como interface de memória;
- busca com score/contexto;
- possibilidade de embeddings locais;
- recuperação por arquivos/código/documentos;
- memória portátil;
- recuperação híbrida estruturada + semântica + histórico.

## Estratégia correta para o Braim

Não substituir a memória SQLite/JSON inicial por LEANN. Primeiro guardar fatos estruturados:

```text
Experience
  id
  project
  intent
  strategy
  skill
  agent
  model
  provider
  prompt_hash
  result
  tests
  score
  cost
  duration
  errors
```

Depois gerar índice semântico sobre esse registro.

## Pesquisa híbrida

O Brain deve combinar:

1. filtro estruturado;
2. busca lexical;
3. busca semântica;
4. ranking histórico.

Exemplo: procurar experiências de “APK Android com erro Gradle” primeiro pelo tipo/projeto/linguagem e depois semanticamente.

## Privacidade

LEANN é especialmente interessante para o objetivo local-first. O histórico do usuário pode permanecer no dispositivo/servidor próprio.

## Licença

LEANN é MIT. Código reutilizado deve preservar o aviso de licença.

## Prioridade

**ALTA**, mas depois da memória estruturada básica.

## Fontes

- https://github.com/StarTrail-org/LEANN
- https://github.com/StarTrail-org/LEANN/blob/main/README.md
- https://github.com/StarTrail-org/LEANN/blob/main/packages/leann-mcp/README.md
- https://github.com/StarTrail-org/LEANN/blob/main/LICENSE

## Conclusão

LEANN pode virar a camada de **memória semântica do Braim**, enquanto SQLite/JSON continua sendo a fonte de verdade estruturada. O pente fino reforça provenance, reconstrução do índice e pesquisa híbrida como requisitos.

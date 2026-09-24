# Fase B — StarTrail-org/LEANN: tecnologias, ferramentas e técnicas

> Levantamento técnico do índice vetorial LEANN (paper MLsys 2026 Best
> Paper) — a técnica central, não só o pitch de storage. Sem propor
> mudança em nada.

## 1. O problema que resolve

Índices vetoriais tradicionais (ex.: HNSW puro, FAISS) armazenam **todos**
os embeddings de alta dimensão mais metadados de grafo — o tamanho total
pode ser várias vezes maior que os dados originais (chunks de texto). Isso
torna caro ou inviável rodar busca vetorial em dispositivo pessoal ou em
datasets muito grandes.

## 2. A técnica central: recomputação seletiva guiada por grafo

Em vez de armazenar todo embedding permanentemente, o LEANN:
1. Constrói um índice de grafo de proximidade (baseado em HNSW), mas
   **poda** arestas de baixo valor de nós de baixo grau, preservando as
   arestas de nós "hub" de alto grau — isso é o **high-degree preserving
   pruning**.
2. Armazena o grafo podado (barato) em vez dos embeddings completos.
3. Na hora da busca, **recomputa embeddings sob demanda**, só para os nós
   de fato visitados durante o percurso do grafo — não para o dataset
   inteiro.

Resultado (dados do paper): redução de até 50× no tamanho do índice vs.
índices convencionais, mantendo qualidade de busca (accuracy) equivalente
ao estado da arte, com overhead de latência end-to-end de ~10% em tarefas
de RAG. Exemplo concreto citado: indexar 60 milhões de chunks de texto em
6 GB em vez de 201 GB.

## 3. Execução da recomputação: servidores ZMQ com overlap e batching

A recomputação de embeddings em tempo real usa servidores ZMQ otimizados,
com um paradigma de busca que sobrepõe (overlapping) e agrupa (batching)
as chamadas de recomputação — ou seja, a recomputação não é uma chamada
síncrona por nó visitado, é pipelinizada.

## 4. Backends de índice suportados

CLI (`leann build`) permite escolher backend (`hnsw` ou `diskann`),
modelo de embedding (padrão: `facebook/contriever`), grau do grafo
(`--graph-degree`, padrão 32) e complexidade de construção
(`--complexity`, padrão 64). Modo `--compact`/`--no-compact` controla
armazenamento compacto (precisa ser `no-compact` se for usar
`--no-recompute`) — ou seja, o trade-off storage-vs-recomputação é
configurável, não fixo.

## 5. Suporte multi-vetor visual (ColPali/ColQwen2)

Além de texto, o LEANN suporta indexação multi-vetor baseada em visão
para PDFs (via ColPali/ColQwen2), e ferramentas de avaliação de recall de
retrieval com benchmarking contra BM25 e DiskANN — ou seja, não é só uma
lib de índice, vem com o próprio arcabouço de avaliação embutido.

## 6. Fontes de dados alvo

Desenhado para busca semântica sobre fontes pessoais: e-mails, histórico
de navegador, histórico de chat, código, documentos — tudo local, sem
dependência de nuvem, rodando inteiramente num laptop.

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- Recomputação seletiva de embeddings (computar só o que o grafo visita
  na busca) como alternativa a armazenar tudo — relevante para qualquer
  cenário de memória/busca semântica com restrição de armazenamento.
- Pruning que preserva nós "hub" de alto grau e descarta arestas de baixo
  valor em nós de baixo grau, como técnica específica de compressão de
  grafo de proximidade.
- Pipeline de recomputação com overlap/batching via ZMQ, em vez de
  recomputação síncrona ingênua — relevante se uma futura implementação
  de busca semântica sobre `ExperienceMemory` precisar de custo de
  storage/latência balanceado.
- Ferramentas de avaliação de recall/benchmark (contra BM25 e DiskANN)
  entregues junto com a lib, não como trabalho à parte.
- Trade-off explícito e configurável entre grau do grafo, complexidade de
  build, e modo compacto — parâmetros que afetam diretamente custo de
  storage vs. qualidade de busca.

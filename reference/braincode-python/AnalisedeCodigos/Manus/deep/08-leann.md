# Auditoria aprofundada — StarTrail-org/LEANN

**Snapshot:** `9b786b024c983ad7fcafe998fd8fa90fd10657e4` · **Arquivos:** 301 · **Fonte:** https://github.com/StarTrail-org/LEANN

## Conclusão
LEANN implementa busca vetorial local com recomputação seletiva: armazena grafo podado e recalcula embeddings dos nós visitados. O repositório não é apenas paper: há CLI, API, servidores de embedding, backends HNSW/DiskANN/IVF, MCP, filtros, sync, chat e uma suíte extensa de testes.

## Arquivos e componentes
`packages/leann-core/src/leann/api.py` e `interface.py` expõem API; `cli.py` e `__main__.py` definem CLI; `searcher_base.py`, `registry.py`, `settings.py` e `metadata_filter.py` sustentam busca e configuração; `embedding_compute.py` e `embedding_server_manager.py` tratam recomputação; `server.py` e `mcp.py` expõem serviço; `sync.py` cobre atualização incremental; `react_agent.py` e `chat.py` integram agente. `packages/leann-backend-hnsw/` usa HNSW, ZMQ e FAISS; `diskann/` contém servidor e particionamento; `ivf` e `flashlib` oferecem alternativas. `packages/leann-mcp` é a integração MCP. `apps/claude_rag.py` e `apps/claude_data/` mostram consumo. `tests/` cobre build, incremental, BM25/FTS5, filtros, MCP, prompt template, CLI, rebuild, sync e integrações.

## Fluxo técnico
Na construção, documentos são divididos em chunks e indexados em grafo. O pruning preserva hubs e reduz armazenamento. Na busca, o grafo guia candidatos; embeddings são calculados em servidores com batching/overlap; resultados podem ser filtrados por metadata. O modo incremental sincroniza fontes sem necessariamente reconstruir tudo. MCP permite que um agente use semantic search como ferramenta.

## Absorção no Brain
Começar por uma SPI `SemanticMemory` com `build`, `search`, `sync`, `delete`, `metadata_filter` e `explain`. Integrar primeiro o backend Python como processo isolado, não copiar C++/FAISS. Exigir benchmark contra BM25, recall@k, latência p95, consumo e custo de reindexação. Armazenar origem, tenant, classificação de confiança e timestamp; memória recuperada é dado não confiável.

## Riscos
Recomputação aumenta latência e demanda CPU/GPU. Backends nativos complicam distribuição. Índice local não resolve ACL por si. Claims de redução de armazenamento devem ser reproduzidos no corpus do Brain.

## Veredito
**P1 após contrato de memória:** integrar como backend opcional e local-first, preservando fallback lexical.

## Referências
[1]: https://github.com/StarTrail-org/LEANN "LEANN"
[2]: https://github.com/StarTrail-org/LEANN/tree/main/packages/leann-core "LEANN core"
[3]: https://github.com/StarTrail-org/LEANN/tree/main/tests "LEANN tests"

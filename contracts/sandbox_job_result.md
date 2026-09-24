# SandboxJobResult

Resultado padronizado devolvido pelo Sandbox. O Brain recebe o resultado e evidências; ele não recebe acesso implícito ao ambiente de execução.

## Campos

- `jobId`, `tarefaId` e `runId`: correlação com o pedido original.
- `status`: `SUCESSO`, `FALHA`, `TIMEOUT`, `CANCELADO` ou `REQUISITO_INDISPONIVEL`.
- `saida` e `erro`: saída e diagnóstico textual, sem secrets.
- `exitCode`: código de saída quando aplicável.
- `artefatos`: caminho, hash SHA-256 opcional e tamanho de cada artefato produzido.
- `tempoExecucaoMs`: duração não negativa.
- `diagnosticos`: evidências estruturadas para validação e auditoria.

O resultado não autoriza retry, correção ou entrega por si só; essas decisões pertencem às camadas Policy e Validation.

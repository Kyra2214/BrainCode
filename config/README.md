# Configuration layout

Configuration is not secret storage.

## Estado atual

Os subdiretórios `providers/`, `routing/`, `policy/`, `execution/` e `research/`
descritos abaixo são o **layout planejado**, mas **não existem hoje neste
repositório** (git não versiona diretório vazio, e nenhum `.gitkeep` foi
commitado) — só `config/README.md` está presente. São placeholders de
intenção, não configurações prontas nem evidência de que uma integração
externa esteja habilitada. Os defaults efetivos são definidos pelo código e
pelos contratos do runtime até que um desses diretórios seja de fato criado
(com `.gitkeep` ou conteúdo real) e uma configuração declarativa seja
adicionada e validada.

- `providers/` (planejado): provider metadata/configuration references only
- `routing/` (planejado): future routing preferences and strategies
- `policy/` (planejado): policy defaults and profiles
- `execution/` (planejado): execution defaults
- `research/` (planejado): research source/configuration defaults

API keys, tokens and credentials must remain outside Git, prompts, memory and events.

## Execution modes

The `execution` section may define `mode` as `development`, `offline`, `sandboxed`, `strict`, or `production`. Readiness is optional only in `development`; the other modes require a `ReadinessGate`. Setting `enforce_readiness` to `false` is rejected for protected modes, including `production`.

Real pipeline execution must also receive the `ExecutionAuthorization` emitted by `RuntimeCoordinator`. The `run_internal_for_tests` and `resume_internal_for_tests` helpers are development-only test interfaces.

Example:

```json
{
  "mode": "production",
  "enforce_readiness": true,
  "timeout_seconds": 60
}
```

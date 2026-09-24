# PRP — Fase 3: Porta 2 — Prompt

## Objetivo

Formalizar a entrada de prompt pelo Secretário, preservar o Prompt Creator existente e garantir que a entrega do prompt seja terminal: nenhum passo posterior de criação, workspace ou execução é iniciado automaticamente.

## Contratos

`Door.PROMPT` aceita pesquisa Web somente quando a `DoorPolicy` permite. A restrição `NO_WEB` remove tanto a pesquisa explícita quanto a pesquisa visual automática. `prompt.library.write` e seu alias `prompt.library.generate` continuam sendo as capabilities de entrega; `workspace.*` e `sandbox.*` permanecem fora da Porta 2.

Follow-ups explícitos, como “melhore este prompt”, continuam classificados como `PROMPT`. O caminho de melhoria existente (`PromptGenerationExecutor`, biblioteca, validador, `RevisionEngine` e fallback local) não é substituído.

## Critérios de aceite

1. `PromptDoorCorpusTest` cobre entrada, terminalidade, `NO_WEB` e follow-up.
2. `PromptDoorTerminalStateTest` comprova entrega aprovada sem criação ou execução.
3. Todos os testes existentes de Prompt Creator permanecem verdes.
4. Regressão completa, architecture gate, CI, lint e readiness passam no HEAD.
5. O APK não é entregue nesta fase; permanece reservado ao fim da Fase 4.

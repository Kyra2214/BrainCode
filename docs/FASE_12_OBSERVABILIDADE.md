# BrainCode 2.3 — Fase 12: Diagnóstico e observabilidade

A Fase 12 adiciona `BehaviorTrace`, `BehaviorTraceSink` e `BehaviorDiagnostics` para registrar o caminho comportamental com correlação por `runId`, `taskId` e `traceId`.

Os campos suportados cobrem:

- decisão/capability/policy;
- tentativa e resultado;
- crítica;
- revisão;
- readiness;
- aprendizado.

O sink em memória é determinístico e substituível por um sink persistente ou exportador futuro. Toda entrada textual passa por redaction de bearer, api key, token, password, secret e `credential:` antes de ser armazenada, com limite de tamanho de detalhe/result.

## Limites

A Fase 12 fornece o contrato e o coletor; a instrumentação de todas as chamadas existentes será ampliada gradualmente pelos callers das fases anteriores. Não há logs de credenciais e o diagnóstico não concede autorização.

## Validação

A suíte `:brain` passou com casos de correlação por run/task e redaction de secrets e referências opacas de credencial.

## Próximo módulo

A Fase 13 deve provar a jornada E2E Android `UI → Brain → plan → capability → execution → verification → result → UI` em ambiente com Android SDK e RootFS disponíveis.

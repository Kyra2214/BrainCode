# BrainCode 2.3 — Fase 11: Provider / Account Pool

Os contratos de `AccountRegistry`, `ProviderRegistry`, `AccountPool` e `AccountRouter` já existiam no núcleo. Esta fase adiciona `ProviderAccountRouter`, uma composição que filtra a rota por:

- provider registrado e habilitado;
- capability declarada pelo provider;
- conta explicitamente autorizada;
- saúde atual da conta;
- idempotência e política de fallback do pool.

O roteador retorna somente `accountId` e `providerId`; `CredentialRef` continua opaco e nunca é entregue ao Agent ou persistido pelo roteador. A escolha permanece subordinada à autorização recebida pelo caller e não contorna Policy.

Cooldown, falhas de autenticação e health continuam representados por `AccountHealth`; o `AccountRouter` escolhe por prioridade entre alternativas elegíveis. O ProviderRegistry permanece a fonte única de providers, sem segundo catálogo paralelo.

## Validação

A suíte `:brain` passou com casos de provider/conta registrados e caso de provider ausente, que retorna `Unavailable`.

## Próximo módulo

A Fase 12 deve consolidar diagnóstico e observabilidade do runtime, registrando decisão, capability, policy, tentativa, resultado, crítica, revisão, readiness e aprendizado sem vazamento de segredo.

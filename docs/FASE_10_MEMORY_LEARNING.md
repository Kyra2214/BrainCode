# BrainCode 2.3 — Fase 10: Memory / Learning

A Fase 10 adiciona `ValidatedLearning`, uma fronteira única para converter resultados em memória procedural.

Um `LearningCandidate` só é persistido quando:

- a evidência está marcada como verificada;
- `VerificationResult` passou em todos os checks;
- `CritiqueResult` é `PASS`;
- `ReadinessReport` é `READY` e todos os sete estágios passaram.

Quando aprovado, o registro é salvo em `LayeredMemory` como procedimento validado, com provenance que contém run e task. Evidência não verificada, falha de verificação, crítica negativa ou readiness bloqueado retornam falha sem persistir aprendizado.

O histórico de falhas continua podendo ser registrado como experiência operacional, mas não é promovido automaticamente a conhecimento ou procedimento validado.

## Validação

A suíte `:brain` passou com casos de learning validado e rejeição de evidência não verificada e verification falha.

## Próximo módulo

A Fase 11 deve consolidar provider/account pool, conectando AccountRegistry, AccountPool, health, cooldown, failover e isolamento de credenciais ao roteamento existente.

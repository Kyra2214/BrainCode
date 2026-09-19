# BrainCode 2.3 — Fase 7: Fix / Verify / Learn

Foi implementado o ciclo universal Kotlin `SCAN → PLAN → FIX → VERIFY → LEARN` em `FixVerifyLearn`.

Cada fase possui uma interface substituível. Falhas em scan, plan, fix, verify ou learn produzem status `FAILED` e preservam a mensagem. Se a verificação não passar, o resultado é `BLOCKED` e o learner não é chamado. Portanto, resultado vazio, falha ou evidência inconclusiva não pode ser promovido a aprendizado.

O ciclo é genérico e não depende de código: pode ser usado por prompt, pesquisa, planejamento, execução ou integração. A verificação reutiliza `VerificationResult` e `VerificationCheck`, mantendo o contrato da Fase 2.

## Validação

A suíte `:brain` passou com testes de ordem das fases e de proteção contra aprendizado quando a verificação falha.

## Próximo módulo

A Fase 8 deve generalizar crítica e dúvida, adicionando post-check adversarial para mudanças não triviais e conectando findings a `RevisionDecision`.

# BrainCode 2.3 — Fase 9: Readiness / Definition of Done

A Fase 9 implementa `DefinitionOfDone` por tipo de trabalho e `ReadinessEvaluator` com sete estágios obrigatórios:

1. implementation;
2. tests;
3. qa;
4. security;
5. architecture;
6. regression;
7. release.

Um estágio só passa quando está marcado como concluído e possui pelo menos uma evidência. Portanto, `READY` não é derivado apenas de flags declarativas. Qualquer estágio ausente ou sem evidência produz `BLOCKED` e aparece em `blockers`.

A Definition of Done fornece critérios específicos para código, debugging, pesquisa, prompts, execução e integração. O contrato não executa os critérios; ele representa o que precisa ser comprovado pelos callers e gates.

## Validação

A suíte `:brain` passou com casos de Definition of Done por tipo, bloqueio sem evidência e readiness completo com os sete estágios.

## Próximo módulo

A Fase 10 deve conectar resultados validados, evidências e correções ao memory/learning, impedindo persistência automática de falhas ou conhecimento sem validação.

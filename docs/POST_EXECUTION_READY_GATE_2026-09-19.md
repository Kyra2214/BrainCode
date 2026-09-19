# Gate pós-execução e estado READY

O estado `READY` da UI não é derivado da ausência de erro técnico ou da ausência de `validationWarning`. Ele é derivado da flag tipada `ChatMessage.validationPassed`, preenchida diretamente por `ResultadoCiclo.aprovado`.

`ResultadoPosExecucao.aprovado` só é verdadeiro quando todos os gates abaixo estão aprovados: verification com status `PASSED` e todos os checks aprovados; critic com status `PASS`; revisão com ação `ACCEPT`; readiness com status `READY` e todos os estágios aprovados. `learningRecorded` permanece um efeito observável posterior e não transforma sozinho um ciclo aprovado em falho.

A matriz de regressão cobre os casos em que o ciclo não pode voltar para `READY`: verification `FAILED`, critic `NEEDS_REVISION`, readiness `BLOCKED`, revisão `REVISE` e um `ResultadoCiclo` com passo técnico aprovado, mas pós-execução reprovada. Esses casos são testados em `PostExecutionOutcomeTest`.

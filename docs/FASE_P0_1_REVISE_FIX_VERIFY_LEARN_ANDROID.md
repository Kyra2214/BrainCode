# P0.1 — REVISE → FixVerifyLearn → reexecução

## Estado

**Implementado no caminho Android e validado por teste determinístico.** Esta entrega cobre somente a P0.1 da ordem de correção 2.3; P0.2 em diante permanecem fora deste commit.

## Fluxo integrado

O `BrainSandboxController` agora executa cada plano com correlação por tentativa:

```text
EXECUTE (runId:attempt-1)
→ EVIDENCE / VERIFY / CRITIC / DOUBT REVIEW
→ REVISE
→ FixVerifyLearn (scan → plan → fix → verify → learn)
→ EXECUTE (runId:attempt-2)
→ EVIDENCE / VERIFY / CRITIC / READINESS
```

A correção padrão, `ContextRevisionFixer`, acrescenta ao plano um marcador de tentativa verificável. A estratégia também pode ser substituída por uma implementação específica da capability, permitindo que uma correção real altere parâmetros, arquivos ou contexto sem criar um segundo pipeline.

## Garantias

O ciclo limita a execução a três tentativas. Cada tentativa recebe um `attemptRunId` no formato `runId:attempt-N`, e os eventos de início, passos, pós-execução, correção e encerramento usam essa correlação. A correção só libera a reexecução quando o `FixVerifyLearn` conclui com verificação aprovada. O aprendizado do resultado final continua condicionado à verificação, crítica, readiness e aprendizado validado do `PostExecutionGate`; uma falha não é promovida como sucesso.

Quando a terceira tentativa ainda retorna `REVISE`, o ciclo termina com `revision.max-attempts-exceeded` e emite `RevisionAborted`, sem loop infinito. O resultado expõe `RevisionAttemptTrace` para reconstruir as tentativas.

## Evidência automatizada

`RevisionRetryIntegrationTest` cobre dois cenários no controller Android:

1. a primeira execução falha, a crítica retorna `NEEDS_REVISION`, `FixVerifyLearn` aplica a correção, a segunda execução produz nova evidência e o ciclo é entregue;
2. uma capability que falha sempre executa exatamente três vezes e termina abortada com evento de limite excedido.

A compilação e os testes do módulo Android devem ser confirmados pelo CI, pois o sandbox local desta sessão não possui Android SDK.

# SandboxJob

Contrato do pedido de execução entre Brain e Sandbox. O contrato é agnóstico de implementação: não contém comandos de shell, binários ou detalhes do runtime.

## Campos obrigatórios

| Campo | Regra |
|---|---|
| `jobId`, `tarefaId`, `sessionId`, `runId` | Identificadores para correlação e auditoria |
| `objetivo` | Objetivo de domínio, nunca comando |
| `requisitos` / `capabilitiesRequired` | Capacidades necessárias ao job |
| `contexto` | Arquivos de entrada e limites de rede/filesystem |
| `timeoutSegundos` | Deve ser positivo |
| `idempotencyKey` | Impede execução duplicada do mesmo pedido |
| `riskClass` | `LOW`, `MEDIUM`, `HIGH` ou `CRITICAL` |
| `approvalRequired` | Gate explícito de aprovação |
| `budget` | Limites de CPU, memória, saída e artefatos |
| `cancellation` | Se o job pode ser cancelado e seu deadline |
| `secretRefs` | Apenas referências; secrets nunca entram no payload |
| `artifactManifest` | Caminhos/extensões esperados |

O executor real permanece congelado até Policy, EventStore e QA estarem implementados.

## Eventos

Cada execução deve poder emitir `JOB_STARTED`, `JOB_APPROVAL_REQUESTED`, `JOB_COMPLETED`, `JOB_FAILED` e `JOB_CANCELLED`, sempre com `eventId`, `jobId`, `runId`, `sessionId`, timestamp e sequência monotônica.

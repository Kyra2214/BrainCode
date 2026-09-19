# Tarefas Pendentes — BrainCode

Atualizado pela auditoria de 2026-09-19.

## P0 — Fechamento da alteração atual

[ ] testes focados Planner/ContextPack.
[ ] :brain:test.
[ ] :android-module:testDebugUnitTest.
[ ] :app:testDebugUnitTest.
[ ] assemble Debug.
[ ] Android lint.
[ ] CI completo.
[ ] E2E em emulador/dispositivo.
[ ] readiness final.

## P1 — Wiring

[ ] testes arquiteturais contra caminhos paralelos.
[ ] prova de que texto livre não chama provider diretamente.
[ ] E2E provando ContextPack até PlanoExecucao.
[ ] E2E provando Verification → Critic → Revision → Readiness.
[ ] persistência/replay do EventStore no caminho Android.
[ ] corpus de regressão de segurança.

## P1 — Retrieval/Knowledge

[ ] executor universal de retrievalHints.
[ ] hashing/fingerprint.
[ ] deduplicação.
[ ] chunking determinístico.
[ ] ranking lexical/semântico.
[ ] versionamento.
[ ] citações/evidências estruturadas.

## P2 — Segurança

[ ] isolamento OS-level de filesystem/rede/processos.
[ ] limites CPU/memória/PIDs/FDs/disco.
[ ] trust chain RootFS/manifests.
[ ] credential binding.
[ ] SSRF/DNS rebinding.

## P2 — Jobs

[ ] durable jobs com leases/fencing.
[ ] recuperação após interrupção.
[ ] retry/timeout/cancelamento uniforme.

Nenhuma tarefa fecha por documentação. Exigir caller real, teste apropriado e evidência observável.

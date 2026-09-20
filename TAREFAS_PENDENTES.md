# Tarefas Pendentes — BrainCode

Atualizado pela implementação do loop de revisão e pesquisa de 2026-09-19.

## Alteração atual — revisão real e pesquisa

### Entrega 1 — regras locais, Fases 0/1/2/3/5

[x] fixer baseado em findings com `revision-feedback`.
[x] verificação real de mudança do plano.
[x] abortar `revision.no-progress`.
[x] selecionar melhor tentativa por severidade.
[x] incluir findings no evento e no aviso da UI.
[x] propagar feedback e evidências ao Prompt Creator/Gateway.
[x] usar sinais de pesquisa em campos genéricos do prompt local.
[x] critério `research.unused` quando fontes não são refletidas.
[x] retry técnico separado do ciclo de qualidade.
[ ] testes novos de integração listados no plano.
[ ] confirmar build Android em ambiente com SDK.

### Entrega 2 — controle da IA, Fase 4

[ ] medir quantos ciclos a Entrega 1 resolve sem IA.
[x] orçamento de tokens por ciclo.
[x] prompt compacto para o especialista.
[x] cache de melhorias e invalidação por feedback.
[x] métrica de escalonamento, custo e taxa de sucesso.

## P0 — Fechamento da alteração atual

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

# Tarefas Pendentes — BrainCode

**Atualização:** 2026-09-14.

## Concluído na janela dos últimos 30 commits

- [x] descoberta dinâmica de modelos;
- [x] catálogo free-only;
- [x] refresh runtime e substituição de modelo;
- [x] waterfall/fallback gratuito;
- [x] gateway Android de APIs;
- [x] memória de conhecimento;
- [x] persistência Android;
- [x] proveniência de respostas;
- [x] ciclo de aprendizado;
- [x] Critic automático;
- [x] reaproveitamento do engine local já instalado.

## P0 — Segurança

- [ ] isolamento de rede OS-level;
- [ ] jail filesystem OS-level;
- [ ] process group/session;
- [ ] enforcement OS-level de CPU/memória/PIDs/FDs/disco;
- [ ] trust chain autenticada dos RootFS/manifests.

## P1 — Integração

- [ ] teste arquitetural Planner → ExecutionPlan → Policy → AuthorizedPlan → Agent → Sandbox;
- [ ] bind completo de credentials;
- [ ] SSRF/DNS rebinding;
- [ ] rotação de EventStore mantendo hash-chain;
- [ ] fencing de workflow leases;
- [ ] testes de wiring/orphan;
- [ ] caller real do `BrainApiGateway` no chat Android;
- [ ] EventStore persistente no caminho principal Android;
- [ ] Security Regression Corpus fechado pelo caminho principal da UI.

## P1 — Conhecimento

- [ ] retrieval executor;
- [ ] validação semântica por Sandbox/build/test/lint ou segunda fonte;
- [ ] contrato estruturado de citações;
- [ ] deduplicação/fingerprint;
- [ ] versionamento/histórico de correções;
- [ ] escopos global/usuário/projeto;
- [ ] índice escalável.

## P2 — Validação do HEAD

- [ ] suíte Python;
- [ ] testes `:brain`;
- [ ] testes `:android-module`;
- [ ] testes `:app`;
- [ ] build Debug;
- [ ] preflight de release;
- [ ] validação ARM64/emulador.

### Ordem de recuperação 2.3

- [x] corrigir e publicar o caminho Android de pós-execução;
- [x] eliminar o bypass de `healthCheck` e classificar `BrainExecutionCoordinator` como legado;
- [x] exigir método estruturado no `PlanningGate`;
- [x] confirmar CI JVM/Android verde;
- [ ] corrigir o harness UI E2E: as jornadas funcionais ainda falham antes do composer ficar pronto, embora os três smoke tests passem;
- [ ] somente depois fechar as jornadas E2E e declarar readiness 2.3.

## Fora do escopo Android offline atual

- servidor distribuído;
- Postgres/Redis/etcd;
- multi-host;
- providers externos obrigatórios;
- reconstrução dos RootFS homologados.

## Regra

Nenhuma pendência é fechada apenas por documentação ou teste isolado. Feature de produto exige caller real e evidência observável.

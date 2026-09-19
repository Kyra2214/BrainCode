# BrainCode 2.3 — Fase 5: Skill / Gates

A Fase 5 adiciona implementações reutilizáveis dos gates comportamentais, sem criar Skills independentes nem bypassar a Policy.

## Gates implementados

- `RequirementGate`: produz `READY` ou `NEEDS_CLARIFICATION` com issues para requisitos ausentes.
- `PlanningGate`: rejeita planos sem acceptance criteria verificáveis.
- `VerificationGate`: só passa quando todos os checks associados aos critérios passam.
- `CriticGate`: converte PASS, revisão, falha e bloqueio em resultado de gate estruturado.
- `ReadinessGate`: exige relatório READY e todos os estágios aprovados.
- `LearningGate`: só permite aprendizado a partir de evidência marcada como verificada.

Todos implementam `BehaviorGate<I, O>` e retornam `GateResult`, preservando issues e valores intermediários. Nenhum gate executa comandos, escolhe credenciais ou autoriza capabilities. A autorização continua sob `PolicyBroker` e a execução sob `ActionGateway`.

## Validação

A suíte do módulo `:brain` passou com os testes de lacuna de requisito, plano com critérios, verificação falha e readiness bloqueado.

## Próximo módulo

A Fase 6 deve conectar os gates ao caminho real `CicloExecucaoPlano → Dispatcher → ActionGateway → capability`, evitando que componentes novos permaneçam somente como infraestrutura sem caller.

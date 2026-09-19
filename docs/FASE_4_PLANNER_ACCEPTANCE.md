# BrainCode 2.3 — Fase 4: Planner e Acceptance Criteria

`PassoPlano` agora possui `acceptanceCriteria`, mantendo `criterioSucesso` como API compatível e criando um critério padrão `success` quando nenhum critério explícito é informado. Os critérios exigem ids únicos e descrição não vazia.

O `Task` derivado de `PlanoExecucao` preserva a lista de critérios, permitindo que gates posteriores verifiquem cada passo sem reconstruir informação a partir de texto descritivo.

Os critérios são metadados verificáveis; eles não executam testes nem concedem autorização. A verificação física continua sendo responsabilidade de evidências e do executor de QA, enquanto as fases seguintes ligarão critérios a `VerificationResult`, Critic e Readiness.

## Validação

A suíte `:brain` passou com cobertura de critérios padrão, critérios customizados, propagação para `Task` e rejeição de ids duplicados.

## Próximo módulo

A Fase 5 deve implementar a camada de Gates reutilizáveis, começando pelo Requirement Gate e preservando `PolicyBroker`/`ActionGateway` como autoridades de autorização e execução.

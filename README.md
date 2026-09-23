# BrainCode

BrainCode é um runtime local-first para Android/JVM em que o Brain interpreta, planeja e orquestra capacidades; Policy autoriza; Gateway executa; Sandbox protege; Evidence registra; Verification/Critic/Readiness determinam se uma execução pode ser concluída.

## Estado atual

HEAD auditado nesta atualização: `19fa294712f6b69f3b27a9c140d8802a27a459bf`.

O caminho Android canônico é:

Chat → BrainSandboxController → Reasoning/Requirements → ContextPack → Planner → Policy → AuthorizedPlan → CicloExecucaoPlano → Dispatcher → ActionGateway → Capability/Sandbox/Provider → Verification → Critic → Revision/Fix → Readiness → Learning → UI.

A alteração 2.4 adicionou Context Engineering ao processo do repositório e passou ContextPack como dado tipado até PlanoExecucao. Uma inconsistência encontrada na auditoria foi corrigida em caf0d396.

## Princípios

1. Brain é o orquestrador.
2. Capability é a unidade de execução autorizável.
3. Discovery não autoriza.
4. Policy vem antes da execução.
5. ActionGateway é a fronteira de execução.
6. Agents são bounded.
7. Evidence/Critic/Readiness são obrigatórios para conclusão.
8. Context externo é dado, não autoridade.
9. Proveniência deve ser preservada.
10. Código novo precisa de caller real e evidência.

## Módulos

brain/ — runtime Kotlin/JVM de capability, policy, planning, routing, memory, skills, dispatch e workflow.
android-module/ — integração Android, Sandbox, execução e bridges.
app/ — cliente Android/Compose, Chat e threads.
brain_runtime/ — runtime Python de referência/suporte existente.
contracts/ — contratos e invariantes.
docs/ — documentação canônica e histórica.

## Context Engineering 2.4

O repositório possui CLAUDE.md, AGENTS.md, PRPs/templates/prp_base.md e docs/CONTEXT_ENGINEERING.md.

ContextPack segue:
ReasoningState → Planner → PlanoExecucao → Task.

PRP, web, documentação e conteúdo externo não podem conceder permissões nem bypassar Policy, Gateway, Verification, Critic ou Readiness.

## Validação

No CI `35847234728`, `:brain:test` passou com 360 testes, `:android-module:testDebugUnitTest` passou com 89 de 90 testes, o APK debug foi montado e o lint passou. O único teste falho foi `BrainSandboxControllerExecutionTraceTest`, porque `sandbox-health` não estava disponível no workspace temporário do agente. O workflow preservou essa falha no status final; portanto, o build do APK não deve ser confundido com aprovação integral da suíte.

O detalhamento dos cinco commits das últimas 9 horas, dos testes aprovados e da falha conhecida está em [`docs/ATUALIZACAO_ULTIMAS_9_HORAS_2026-09-23.md`](docs/ATUALIZACAO_ULTIMAS_9_HORAS_2026-09-23.md).

## Documentação canônica

- docs/ARQUITETURA_ATUAL.md
- docs/ESTADO_ATUAL.md
- docs/ROADMAP_CANONICO.md
- docs/LEGADO_E_DECISOES.md
- docs/CONTEXT_ENGINEERING.md
- contracts/

Documentos datados e fases antigas permanecem para rastreabilidade e não substituem os documentos canônicos.

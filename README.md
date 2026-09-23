# BrainCode

BrainCode é um runtime local-first para Android/JVM em que o Brain interpreta, planeja e orquestra capacidades; Policy autoriza; Gateway executa; Sandbox protege; Evidence registra; Verification/Critic/Readiness determinam se uma execução pode ser concluída.

## Estado atual

HEAD auditado nesta atualização: caf0d3960e8fde5fbc7bbd193a6dc15d9217ce1f.

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

A validação final desta alteração ainda precisa comprovar testes focados, CI, E2E e readiness. Não considerar um baseline anterior como prova das mudanças atuais.

## Documentação canônica

- docs/ARQUITETURA_ATUAL.md
- docs/ESTADO_ATUAL.md
- docs/ROADMAP_CANONICO.md
- docs/LEGADO_E_DECISOES.md
- docs/CONTEXT_ENGINEERING.md
- contracts/

Documentos datados e fases antigas permanecem para rastreabilidade e não substituem os documentos canônicos.

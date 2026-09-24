# PRP — Fase 2: Porta 1 — Chat / Plano

## Objetivo

Implementar a entrada conversacional real da Porta 1 sem permitir criação de projeto, execução de código ou chamadas de API externa. Toda intenção classificada como `CHAT` deve produzir `chat.respond`, opcionalmente precedido por `network.research` quando a pesquisa for permitida.

## Contratos

`chat.respond` é uma capability local e somente leitura. O executor devolve resposta não vazia, evidências de origem local ou de dependência de pesquisa e proveniência explícita. O contexto da sessão é fornecido por leitura; o executor não altera memória, workspace ou sessão. O `PlanningAgent` materializa um `PlanningArtifact` persistente com ideia, requisitos, decisões, pendências, referências e assumptions.

O `DoorAwareSplitter` preserva a pesquisa autorizada como dependência e cria o passo conversacional depois dela. Restrições `NO_WEB`, `NO_PRODUCE` e `NO_EXECUTE` continuam sob autoridade da `DoorPolicy`. Quando o `RequirementGate` retornar `NEEDS_CLARIFICATION` na Porta 1, o controller cria um `ClarificationQuestion`, emite `ClarificationRequested` e executa um passo `chat.respond` com pergunta explícita.

## Implementação

A capability é registrada no `BrainSandboxController` e o `SandboxViewModel` injeta `ChatResponseExecutor`, que responde localmente a data/hora, contexto acumulado e resumos de pesquisa já autorizada. Não há provider, HTTP, shell ou escrita de projeto nesse caminho.

## Critérios de aceite

1. `ChatDoorLeakCorpusTest` comprova que frases de conversa/planejamento não produzem `workspace.*` ou `sandbox.*`.
2. `ChatResponseExecutorTest` cobre relógio, contexto somente leitura, pesquisa com evidência/proveniência e pergunta de clarification.
3. `PlanningAgentTest` e `BrainSandboxControllerDoorTest` comprovam `PlanningArtifact` persistente com ideia, requisitos, decisões, pendências e referências.
4. `ClarificationQuestionTest` e a integração Android comprovam `NEEDS_CLARIFICATION → chat.respond`.
5. A regressão completa (`brain`, `android-module`, `app`), architecture gate, CI, lint e UI E2E devem passar no mesmo HEAD.
5. O APK não é entregue nesta fase; só será entregue após a Fase 4 e o CI final.

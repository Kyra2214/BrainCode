# E2E específico das três portas

**Suíte:** `com.sandbox.app.ThreeDoorsSimulationE2ETest`  
**Arquivo:** `app/src/test/kotlin/com/sandbox/app/ThreeDoorsSimulationE2ETest.kt`  
**Execução:** `./gradlew :app:testDebugUnitTest --tests com.sandbox.app.ThreeDoorsSimulationE2ETest --no-daemon`  
**Tipo:** simulação E2E determinística no JVM/Android unit test, usando os componentes reais de Secretário, roteador, Policy, ActionGateway, controller, pesquisa, PromptGenerationExecutor, biblioteca e aprovação. Providers de pesquisa e launcher de workspace são fakes locais controlados; nenhuma API externa ou emulador é necessário.

## Objetivo

Verificar, em cinco situações concretas, que as três portas não se misturam: a **Porta 1** conversa e pesquisa sem criar projeto; a **Porta 2** trabalha exclusivamente com prompts, tenta reutilizar a biblioteca, pesquisa quando solicitado e salva uma nova versão; e a **Porta 3** exige aprovação antes de gerar o roadmap, selecionar especialistas e executar a criação.

## Situação 1 — Porta 1: chat normal

**Pedido simulado:** “Quero conversar sobre uma ideia de aplicativo de lista de compras, sem criar nada ainda.”

O `DeterministicSecretary` deve classificar o pedido como `Door.CHAT` e `CreatePhase.CHAT`. O controller executa somente `chat.respond`, devolve uma resposta conversacional e não cria `PlanningArtifact`, `CreateApprovalRequested`, `workspace.*` ou `sandbox.*`.

**Critérios verificados:** `DoorDesignated=CHAT`, rota `CONVERSATION`, ciclo aprovado, resposta não vazia e ausência de produção/execução.

## Situação 2 — Porta 1: pesquisa

**Pedido simulado:** “Pesquise como organizar uma lista de compras simples e explique as melhores práticas.”

O pedido permanece na `Door.CHAT`, mas a rota interna é `CAPABILITY`. O plano contém `network.research` e `chat.respond`. O primeiro passo recebe o resultado do provider de pesquisa local; o segundo entrega uma síntese ao usuário.

**Critérios verificados:** pesquisa e síntese executadas em sequência, evidência `web-research:e2e:source=example.test`, resposta final sintetizada e ausência de criação de projeto.

## Situação 3 — Porta 2: reuso da biblioteca de prompts

**Pedido simulado:** “Crie um prompt de aplicativo de lista de compras Android com categorias e itens.”

A biblioteca recebe previamente um template compatível com taxa de sucesso suficiente. O `PromptGenerationExecutor` consulta a biblioteca antes de criar do zero, adapta o template encontrado e grava a resposta na biblioteca.

**Critérios verificados:** `Door.PROMPT`, `CreatePhase.PROMPT`, capability efetiva `prompt.library.write`, resultado mencionando a biblioteca, evidência de `prompt-library:*`, evidência `prompt-library:saved-before-response` e nova versão salva.

## Situação 4 — Porta 2: pesquisa seguida de criação e integração na biblioteca

**Interações simuladas:**

1. “Pesquise padrões para um app pequeno de lista de compras e explique as recomendações.”
2. “Crie um prompt Android para um app pequeno de lista de compras com categorias, persistência local e navegação simples”, acompanhado de uma referência resolvida com o resultado da pesquisa.

A primeira interação usa a Porta 1 para obter pesquisa com evidência. A segunda é explicitamente classificada como `Door.PROMPT`; o executor usa a referência da pesquisa, cria o prompt localmente quando não há template compatível e salva a nova versão antes de responder.

**Critérios verificados:** pesquisa aprovada com evidência, segunda interação na Porta 2, `prompt.library.write` executado, `prompt-library:saved-before-response`, template criado contendo “lista de compras” e evento `DoorDesignated=PROMPT`.

A separação em duas interações é intencional: pesquisa é uma capacidade da Porta 1; criação de prompt é uma operação da Porta 2. A referência aprovada atravessa a fronteira como dado, não como autorização.

## Situação 5 — Porta 3: criação de um app pequeno

**Pedido simulado:** “Crie um app pequeno de lista de compras Android com categorias, adicionar item e marcar como comprado.”

O Secretário classifica como `Door.CREATE` em `CreatePhase.DISCUSSION`. A primeira chamada não executa workspace nem gera prompts de roadmap; devolve `AGUARDANDO_APROVACAO` com `approvalId`. Depois da aprovação explícita, o teste chama `resumePlan` e verifica a execução da criação.

**Critérios verificados:** `CreateApprovalRequested`, nenhum prompt antes da aprovação, aprovação aceita, `RoadmapCreated`, `TaskAssigned`, `SpecialistSelected`, `CreationPromptsGenerated`, `workflow.execution.started`, execuções de criação não vazias e eventos locais saudáveis.

## Resultado esperado da suíte

A suíte possui cinco métodos de teste, um por situação. O comando focalizado deve terminar com `BUILD SUCCESSFUL` e cinco testes aprovados. A suíte é complementar ao `scripts/e2e-smoke.sh`: ela valida o fluxo lógico das três portas e a integração entre Brain/Policy/Prompt Library; o smoke test continua sendo o caminho de UI/ADB para preparação, execução, diagnóstico e reset do Sandbox.

Esta suíte **não declara UI E2E em emulador**. A validação de interface, instalação e jornada visual completa continua sendo responsabilidade do workflow `ui-e2e.yml`.

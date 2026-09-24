# Item 7 — Fronteira Kotlin `Planner → AuthorizedPlan → Agent → Sandbox`

## Implementação

Foi introduzido `com.brain.planner.AuthorizedPlan`. O tipo contém o `PlanoExecucao`, uma `ExecutionAuthorization` opaca para cada passo e, opcionalmente, as decisões de policy correspondentes para auditoria.

`CicloExecucaoPlano.executar` agora aceita exclusivamente `AuthorizedPlan`. Portanto, a camada Agent/Sandbox não recebe mais um `PlanoExecucao` cru. A conversão é feita por `autorizarEExecutar`, que consulta o `PolicyBroker` para todos os passos, rejeita `DENY`/autorizações inválidas, cria o pedido de aprovação quando necessário e só então emite o wrapper autorizado.

O `BrainSandboxExecutionBridge` expõe duas fronteiras explícitas:

- `authorizeAndExecute(PlanoExecucao, ...)`, usada pelo controller para iniciar o fluxo de decisão;
- `execute(AuthorizedPlan, ...)`, usada pela fronteira de execução já autorizada.

O `BrainSandboxController` usa a primeira; o ciclo interno usa a segunda. Retomadas passam novamente por autorização para emitir autorizações frescas, sem reutilizar uma aprovação de outro passo.

## Garantias

Um Agent não consegue chamar a execução tipada com um `PlanoExecucao` sem passar pelo método de autorização. Cada autorização também precisa ter `taskId` correspondente ao id do passo e o `AuthorizedPlan` exige cobertura completa do plano.

## Validação

Os testes do ciclo foram atualizados para chamar `autorizarEExecutar`, preservando cobertura de sucesso, negação, roteamento e aprovação. A compilação Gradle foi tentada, mas o ambiente não possui uma instalação Java 17 compatível com o toolchain requerido; a compilação Android anterior também ficou bloqueada pela ausência de Android SDK.

## Estado

Implementação concluída em commit separado após o item de assinatura do RootFS. A validação final deve ser repetida em CI ou em um ambiente com JDK 17 e Android SDK configurado.

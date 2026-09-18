# Auditoria do fluxo real do BrainCode

**Base auditada:** `origin/main` no commit `670f1bf`  
**Escopo:** UI Compose, navegação, estado, lifecycle, acessibilidade, responsividade, envio de mensagens, testes instrumentados, pipeline Prompt/Brain, pesquisa web, validação de qualidade, revisão, persistência e decisão de uso de IA.  
**Método:** rastreamento estático das chamadas de produção e comparação com os testes existentes. A documentação não foi tratada como evidência suficiente; cada conclusão abaixo foi baseada no código executável ou na ausência de uma chamada de produção.

## Conclusão executiva

O produto possui uma fatia vertical real e conectada: a Activity hospeda a árvore Compose, o `SandboxViewModel` recebe o envio do usuário, o `BrainSandboxController` classifica e raciocina sobre o objetivo, planeja capabilities, passa pelo gateway/policy/dispatcher e retorna um ciclo de execução para a UI. O fluxo de geração de prompt também possui criação local, biblioteca persistente, validação de qualidade, escalonamento opcional para IA e revisão local.

A principal limitação observada não é a ausência dos componentes, mas a diferença entre o caminho real e a expectativa de cobertura. Os testes instrumentados cobrem navegação e existência da UI, porém os cinco journeys funcionais são marcados como `SKIPPED` quando o RootFS não está disponível. Portanto, o pipeline verde atual não comprova o envio real, o resultado real, a pesquisa web ou a persistência pelo Android.

Também há componentes cuja presença no código não prova integração completa. `RequirementDiscovery` e `AssumptionManager` estão conectados por `ReasoningEngine`. `RevisionEngine`, `SelfCritic` e `PromptQualityValidator` estão conectados ao `PromptGenerationExecutor`, mas somente quando o plano chega à capability `prompt.library.generate`. Já o caminho de fallback do chat, quando o sandbox não está pronto, chama diretamente `BrainApiGateway.complete()` e pode consultar providers externos sem passar pelo ciclo completo do `BrainSandboxController`.

## Matriz de rastreabilidade

| Área | Situação observada | Evidência no código | Cobertura atual |
|---|---|---|---|
| Entrada Compose | Conectada | `MainActivity` cria `SandboxMobileApp` com `SandboxViewModel`; a aplicação alterna entre `ThreadScreen` e `SettingsScreen`. | Smoke instrumentado |
| Navegação | Parcialmente coberta | `settingsOpen`, sidebar, busca e retorno usam estado Compose; a Activity mantém o ViewModel via `viewModels()`. | Navegação de Settings coberta; sidebar e busca não aparecem nos testes instrumentados atuais |
| Estado | Conectado, mas com limites de lifecycle | O estado operacional fica no `SandboxViewModel`; sessões são persistidas em arquivo e restauradas no `init`. | Não há teste instrumentado de recriação de Activity/processo |
| Lifecycle/recomposição | Parcial | Há `rememberSaveable`, `LaunchedEffect` e estado observável; não foi encontrado `onCleared` ou `DisposableEffect` para desligar o runtime. | Não há teste de rotação, background/foreground ou descarte do runtime |
| Hierarquia Compose | Corrigida no host de teste | Os testes usam `MainActivity`, declarada também no manifesto de `androidTest`. | Os três smoke tests passam |
| Acessibilidade | Parcial | Ícones principais têm `contentDescription`, incluindo `Enviar`, `Parar`, `Configurações`, `Buscar` e `Limpar chat`. O `BasicTextField` não possui label ou `contentDescription` próprio. | Não há testes de TalkBack, foco, ordem semântica ou contraste |
| Overflow/responsividade | Parcial | `LazyColumn`, `horizontalScroll` e `TextOverflow.Ellipsis` existem em pontos específicos. | Não há teste por tamanho de tela, fonte ampliada, landscape ou teclado aberto |
| Envio real | Conectado condicionalmente | `submitThreadInput()` diferencia comandos operacionais e texto livre; texto livre chama `sendChatMessage()`. | Os journeys estão skipped quando o sandbox não está pronto |
| Resultado real | Conectado condicionalmente | O ciclo retorna `resposta`, `researchSources`, `actionId` e evidências para `ThreadEvent.Agent`. | Não comprovado no CI atual por ausência do RootFS |
| Intent | Conectada | `BrainSandboxController.executeObjective()` usa `FastIntentClassifier`; `ReasoningEngine` também deriva `ReasoningIntent`. | Testes unitários do raciocínio; sem journey Android executado |
| RequirementDiscovery | Conectada | `ReasoningEngine` instancia e chama `RequirementDiscovery.discover()`. | Teste unitário dedicado |
| AssumptionManager | Conectada | `ReasoningEngine` chama `assumptionManager.decide()` e inclui assumptions/missing no `ReasoningState`. | Cobertura indireta pelo raciocínio |
| Planner | Conectado | `executeObjective()` usa `KeywordPlanner`, que gera `PlanoExecucao` e `PassoPlano`. | Testes unitários de planner |
| Classificação imagem/texto/código | Conectada | `PromptDomain.classificar()` é usada pelo raciocínio e pelo executor de prompt. | Testes unitários; regressão de `ata` dentro de `plataforma` adicionada no commit auditado |
| Contexto entre mensagens | Implementado, não validado end-to-end | `resolveConversation()` é chamado antes de `executeObjective()`; `ConversationContextEngine` resolve referências a artefatos anteriores. | Há testes de contexto no módulo Brain, mas o journey Android correspondente está skipped |
| Criação local sem API | Conectada | `PromptGenerationExecutor` usa `LocalPromptCreatorAgent` antes de escalar para IA. | Testes unitários do agente e do executor |
| WebResearch | Conectada à capability | `SandboxViewModel` registra `WebResearchExecutor` em `sandbox.info`; o planner pode gerar `network.research`; o executor usa providers DuckDuckGo/Wikipedia. | Testes de provider e pesquisa; sem execução Android real no CI |
| PromptQualityValidator | Conectada ao executor | O prompt inicial e a resposta de IA são avaliados antes da entrega. | Testes unitários |
| SelfCritic/RevisionEngine | Conectados ao fallback local de revisão | `PromptGenerationExecutor.escalonar()` chama `RevisionEngine` quando a qualidade inicial está abaixo do padrão; `RevisionEngine` usa `SelfCritic`. | Testes unitários; dependem de o plano chegar à capability de prompt |
| Biblioteca/persistência | Conectada | `InMemoryPromptLibrary` recebe o seed local, usa arquivo persistente e salva o prompt antes da resposta. | Testes de persistência e fluxo; não comprovado pela UI no CI atual |
| Decisão de IA | Majoritariamente Brain-first no fluxo preparado | O executor cria localmente, valida e só chama `GatewayPromptImprover` quando o score está abaixo do padrão. | Testes de executor; o fallback de Activity é uma exceção importante |
| Testes instrumentados | Verdes, porém incompletos | 3 smoke passam; 5 journeys usam `Assume` e ficam skipped sem RootFS. | Verde não significa validação funcional completa |

## Caminho real da UI até o Brain

A entrada começa em `MainActivity`. Ela obtém um `SandboxViewModel` com `by viewModels()` e chama `setContent`. `SandboxMobileApp` alterna entre `ThreadScreen` e `SettingsScreen` usando `rememberSaveable`.

`ThreadScreen` renderiza o composer. O botão `Enviar` chama `viewModel.submitThreadInput()`. Essa função roteia comandos com prefixo, como `/run`, para operações específicas. O texto livre segue para `sendChatMessage()`.

Quando o sandbox está pronto, `sendChatMessage()` resolve o contexto da conversa e chama `brainController.executeObjective()`. O controlador executa as seguintes etapas observadas no código:

1. registra o evento de criação da tarefa;
2. classifica rapidamente a intenção;
3. executa `ReasoningEngine.analyze()`;
4. dentro do raciocínio, roda classificação de domínio, `RequirementDiscovery` e `AssumptionManager`;
5. chama `KeywordPlanner`;
6. consulta biblioteca e, quando aplicável, explora `TreeOfThoughts`;
7. cria um workflow durável;
8. passa pelo bridge de policy, dispatcher, gateway e executor da capability;
9. transforma o ciclo final em `ThreadEvent.Agent` para a UI.

Esse caminho comprova que o Brain decide a decomposição e a capability antes da execução. A IA não é chamada como primeira etapa do caminho de geração de prompt.

## Prompt, qualidade, revisão e biblioteca

O `PromptGenerationExecutor` é registrado pelo `SandboxViewModel` na capability `prompt.library.generate`. Ele recebe uma `InMemoryPromptLibrary`, um `GatewayPromptImprover`, um `ReasoningEngine` e um `RevisionEngine`.

O comportamento real é local-first. O executor procura um template compatível na biblioteca. Em seguida, cria um prompt com `LocalPromptCreatorAgent`. O resultado é validado por `PromptQualityValidator`. Se a qualidade for suficiente, nenhuma IA é chamada. Se for insuficiente, o executor tenta o melhorador via `BrainApiGateway`; se essa tentativa falhar ou não superar o score local, usa `RevisionEngine`, que aplica `SelfCritic` e revisões locais limitadas.

Quando o resultado é finalizado, o prompt é salvo antes da resposta retornar ao usuário. O `PromptOutcomeTracker` registra o uso técnico e pode aguardar feedback do usuário. A persistência da biblioteca é coberta por testes unitários, mas o caminho Android completo permanece sem comprovação porque os journeys funcionais não executam no CI sem RootFS.

## WebResearch

O planner cria a etapa `network.research` quando identifica pesquisa explícita ou quando o pedido visual se beneficia de referências. O Android registra `WebResearchExecutor` com um `CompositeWebResearchProvider` formado por DuckDuckGo e Wikipedia. O executor limita quantidade e tamanho de resultados, inclui contexto na resposta e registra fontes, URL e horário de recuperação.

Há fallback explícito para conhecimento local quando a pesquisa não tem consulta, falha ou não encontra resultados. Isso é uma integração real, não apenas documentação. Contudo, a cobertura atual verifica principalmente providers e executors de forma unitária; não há journey instrumentado executado que valide a decisão completa planner → pesquisa → prompt → UI.

## Componentes presentes, mas com integração limitada ou condicional

### Fallback direto fora do ciclo completo do Brain

Quando `brainController` é nulo ou `phase` não é `Ready`, `sendChatMessage()` chama diretamente `brainApiGateway.complete(prompt)`. Esse caminho consulta memória e, na ausência de resposta aprendida, usa `DefaultAIRouter` e providers configurados. Ele não passa pelo `BrainSandboxController.executeObjective()`, pelo planner de capabilities, pelo workflow durável ou pelo `ActionGateway`.

Isso cria duas semânticas de chat: o caminho preparado é Brain → planner → policy → capability; o fallback é gateway → memória/roteador/provider. O código comenta que a conversa Android entra no Brain, mas a condição `else` mostra que isso não é universalmente verdade.

### Revisão não é uma etapa universal

`RevisionEngine` e `SelfCritic` estão integrados ao `PromptGenerationExecutor`, mas não a qualquer resposta do Brain. Eles só participam quando o plano seleciona `prompt.library.generate` e a qualidade inicial fica abaixo do limite. Respostas de outras capabilities não passam por esse ciclo.

### Contexto de conversa não é coberto pelo E2E real

A resolução de contexto existe no código e há testes de módulo. Porém, `secondTurnPreservesConversationFlow` fica skipped sem RootFS. Logo, a preservação de contexto na UI Android ainda não tem evidência instrumentada verde.

### Runtime e lifecycle

O `SandboxViewModel` pode iniciar preparação automática do sandbox e reiniciar runtime durante `prepareSandbox()`. O código encontrado não apresenta um `onCleared()` equivalente para garantir shutdown em descarte do ViewModel. Isso merece verificação adicional porque o runtime é um recurso pesado e o fluxo depende de Activity/processo.

### Testes funcionais atuais

Os cinco journeys funcionais usam uma pré-condição de até 15 segundos. Quando o botão `Enviar` não fica visível e habilitado, o teste usa `Assume` e vira `SKIPPED`. Essa mudança evita falsos failures e timeouts de 120 segundos, mas também significa que o pipeline não testa envio, classificação, resultado, execução, pesquisa ou segundo turno em um CI sem RootFS.

## Estado dos testes no commit auditado

Os workflows de CI e UI E2E do commit `670f1bf` terminaram com sucesso. O relatório UI E2E anterior do commit `cc87450` registrou 8 testes, com 3 passados, 5 ignorados, 0 falhas e 0 erros. A correção de classificação de `PromptDomain` foi publicada no commit auditado e adicionou uma regressão para impedir o falso positivo de `ata` dentro de `plataforma`.

Esse resultado deve ser interpretado como **verde estrutural e de smoke**, não como aprovação funcional completa do caminho real do Brain no Android.

## Conclusão

O caminho real principal está conectado e é coerente com a regra de que o Brain deve decidir quando usar IA: o prompt é criado localmente, validado e somente depois escalado quando necessário. Requirement discovery, assumptions, planner, pesquisa, biblioteca e revisão não são apenas nomes soltos; existem chamadas de produção que os conectam.

A auditoria também encontrou três limites materiais. O fallback do chat pode bypassar o ciclo completo do Brain. A revisão é específica da capability de prompt, não uma etapa global. E os E2E funcionais Android ainda não executam no CI sem o RootFS, sendo pulados explicitamente.

Não foram implementadas alterações como parte desta auditoria. Este documento registra o estado real e os pontos que precisam de validação ou decisão posterior.

## Referências

[1]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/app/src/main/kotlin/com/sandbox/app/MainActivity.kt "Entrada Compose e navegação principal"

[2]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/app/src/main/kotlin/com/sandbox/app/ThreadScreen.kt "Thread, composer, semântica e responsividade"

[3]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/app/src/main/kotlin/com/sandbox/app/SandboxViewModel.kt "Estado Android, envio e registro de capabilities"

[4]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/android-module/src/main/kotlin/com/sandbox/agent/BrainSandboxController.kt "Caminho real do Brain até planner, policy e execução"

[5]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/brain/src/main/kotlin/com/brain/reasoning/ReasoningEngine.kt "Intent, RequirementDiscovery e AssumptionManager"

[6]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/app/src/main/kotlin/com/sandbox/app/PromptGenerationExecutor.kt "Criação local, qualidade, revisão, IA e persistência"

[7]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/app/src/main/kotlin/com/sandbox/app/BrainApiGateway.kt "Memória, roteamento e fallback de providers"

[8]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/app/src/androidTest/kotlin/com/sandbox/app/BrainCodeJourneyE2ETest.kt "Journeys instrumentados e pré-condição de sandbox"

[9]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/.github/workflows/ui-e2e.yml "Workflow do UI E2E"

[10]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/brain/src/test/kotlin/com/brain/prompt/PromptLibraryFlowE2ETest.kt "Teste de fluxo da biblioteca de prompts"

[11]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/app/src/main/kotlin/com/sandbox/app/WebResearchExecutor.kt "Execução e fallback de pesquisa web"

[12]: https://github.com/Kyra2214/BrainCode/blob/670f1bf/brain/src/main/kotlin/com/brain/prompt/InMemoryPromptLibrary.kt "Persistência, versionamento e aprendizado da biblioteca"

[13]: https://github.com/Kyra2214/BrainCode/actions "Execuções de CI e UI E2E do repositório"


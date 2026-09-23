# BrainCode — Legado e Decisões

## Decisões atuais

BrainCode não depende de LLM local baixado para o Chat.
Agents são bounded.
Capability é a unidade autorizável.
Discovery não autoriza.
Policy não executa.
ActionGateway é a fronteira de execução.
Android usa BrainSandboxController → BrainSandboxExecutionBridge → CicloExecucaoPlano.
ResultadoCiclo.aprovado depende do pós-ciclo completo.
ContextPack é dado tipado; contexto externo não possui autoridade.
RooftS é uma entidade única com camadas 0.3–0.6; as camadas 0.3–0.5 são preservadas e RooftS 0.6 / Agent Skills está instalado, mas ainda não é runtime ativo de Skills.
Projetos externos não viram dependências automaticamente.

## Legado

BrainExecutionCoordinator existe no JVM, é deprecated e não deve virar segundo pipeline Android.

Documentos FASE_*, auditorias 2.3, mapas de integração e relatórios datados são históricos. Eles explicam a evolução e podem conter estados anteriores; não definem o estado atual quando divergirem.

brain_runtime/ e reference/braincode-python/ são implementação/referência Python existente, não o caminho Android automático.

## Não adotado

Snapshots IaBrain como segundo banco/arquitetura.
LLM local como cérebro obrigatório.
Agents autônomos com objetivo próprio.
Execução fora de Policy/Gateway/Sandbox.
Segundo Registry/Router/Memory/Gateway para a mesma responsabilidade.
Importação de código externo apenas por existir em reference/.

## Regra de remoção

ATUAL = preservar.
PARCIAL = integrar ou registrar backlog.
LEGADO = remoção somente em mudança explícita.
REFERÊNCIA = preservar como referência.
DÚVIDA = não remover sem confirmação.

## Fase 0 — Limpeza documental (concluída)

Executada em 2026-09-22: apagados (não arquivados — decisão explícita de não manter `docs/archive/`) os arquivos de fase/auditoria/validação/correção soltos em `docs/` (`FASE_*`, `CORRECAO_*`, `VALIDACAO_*`, `AUDITORIA_*`, `SANDBOX_RELEASE_*`), os históricos da raiz (`PLANO_DE_ACAO.md`, `ROADMAP_UNIFICADO.md`, `TAREFAS_PENDENTES.md`, `CORRECOES-PROMPT.md`, `IMPLEMENTACAO_2.3.md`, `IMPLEMENTACAO_FASE1_A_8.md`, `AUDITORIA_PESADA.md`, `RELATORIO_AUDITORIA_FLUXO_REAL.md`, `BrainCode2.0.md`, `INITIAL.md`) e a pasta `Análisedecodigos/` (relatórios externos de Manus/GPT/Claude). Os 4 documentos canônicos (`ARQUITETURA_ATUAL.md`, `ESTADO_ATUAL.md`, `ROADMAP_CANONICO.md`, `LEGADO_E_DECISOES.md`) foram preservados.

**Regra em vigor:** nenhum novo arquivo de "fase" ou "auditoria" solto na raiz ou em `docs/`. Qualquer achado novo (inclusive de auditorias com IA) vira uma entrada resumida aqui em `LEGADO_E_DECISOES.md`, nunca um arquivo novo.

## Fase 1 — Bug do shell nos instaladores (corrigido)

Causa: `SecureCommandExecutor` bloqueia qualquer `bash`/`sh` como executável (correto, para comandos vindos de chat/terminal/agente). O problema é que todo instalador do catálogo interno (Trivy, SOPS, Ollama, grpcurl, websocat, Android NDK, e qualquer pacote apt) só funciona *através* de `bash -c` — e caía no mesmo bloqueio, falhando sempre com "shell livre não é uma capacidade autorizada".

Solução: `TrustedInstallerExecutor` (`app/src/main/kotlin/com/sandbox/sandbox/TrustedInstallerExecutor.kt`) aceita `bash -c <script>` somente quando o hash SHA-256 do script bate com um dos scripts literais do catálogo real (`BuiltInCatalog`, `BuiltInToolchains`, checagens do self-check) — nunca aceita script vindo de fora desse conjunto fixo. Qualquer outro comando continua indo pelo `SecureCommandExecutor` de sempre. Não é uma capacidade de shell genérica nova; é o mesmo padrão de catálogo fechado que `CapabilityResolver` já usava no `android-module`, aplicado aqui.

Mudanças:
- `SandboxPlatform.plugins` e `SandboxPlatform.toolchains` passaram a usar o novo `installerExecutor` em vez do `securedExecutor` genérico. Todo o resto (git, services, diagnostics, testLab) continua no `securedExecutor`, que recusa `bash`/`sh` por completo.
- Os textos dos scripts apt (instalação e remoção) deixaram de estar duplicados em `PluginModels.kt` e `ToolchainModels.kt` — agora vêm de um único `AptScripts`, para que o hash confiável nunca possa divergir do script realmente executado.
- O "Teste geral" (self-check) deixou de chamar `runtime.execute(bash -c ...)` bruto direto no runtime, ignorando toda política. Ele passou a usar o mesmo `installerExecutor` de produção (`SandboxViewModel.runFullSelfCheck`), e os scripts de checagem viraram `com.sandbox.sandbox.SelfCheckCliTools`, a mesma fonte usada para calcular os hashes confiáveis.
- Teste de integração ponta a ponta em `TrustedInstallerExecutorTest.kt`: instala Trivy e a toolchain Java através do `TrustedInstallerExecutor` real (não da política isolada), além de confirmar que o catálogo de hashes cobre 100% do `BuiltInCatalog` e que scripts fora do catálogo continuam recusados.

Limitação conhecida no primeiro commit da Fase 1, já resolvida no mesmo dia: plugins remotos (`RemotePluginCatalog`) que dependessem de instalação via lista de pacotes apt dinâmica não batiam com nenhum hash pré-computado, porque o catálogo confiável só cobria `BuiltInCatalog`/`BuiltInToolchains`, fixos em tempo de compilação. Isso já estava 100% quebrado antes desta fase (o `securedExecutor` bloqueava `bash` incondicionalmente), então não era uma regressão — mas foi fechado mesmo assim, para não virar uma pendência esquecida:

`TrustedInstallerExecutor` passou a receber um `trustedScriptsProvider: () -> Set<String>` (recalculado a cada chamada `bash -c`, não um `Set` fixo da construção) em vez de um catálogo estático. Em `SandboxPlatform`, esse provider é `TrustedInstallerCatalog.allTrustedScripts() + TrustedInstallerCatalog.scriptsFor(remotePluginCatalog.components())` — ou seja, os scripts de um plugin remoto só se tornam confiáveis depois de aceitos pelo portão de confiança que já existia em `RemotePluginCatalog.importSnapshot` (fonte na `trustedSourceIds` + hash SHA-256 do artefato verificado). Nenhuma infraestrutura de confiança nova foi inventada; o `TrustedInstallerExecutor` passou a consultar a mesma que já existia. Coberto por teste (`plugin remoto so fica confiavel depois de aceito pelo RemotePluginCatalog`): o script é recusado antes do import e aceito depois, sem recriar o executor.

## Fase 2 — Duplicação planner/dispatch/workflow (mapeada; não é duplicação real)

Mapeamento de chamadores (produção, excluindo testes):

- `com.brain.planner.Planner`/`KeywordPlanner` — instanciado só em `BrainSandboxController`. Dono de decompor o objetivo em `PassoPlano`/`PlanoExecucao`.
- `com.brain.planning.PlanningArtifact`/`PlanningAgent` — instanciado só em `BrainSandboxController`, **antes** do `Planner`. Responsabilidade diferente: decide se o `ReasoningState` já tem requisitos suficientes (`READY`/`NEEDS_CLARIFICATION`) e persiste esse veredito como artefato de auditoria (`FilePlanningArtifactStore`). Não decompõe passos, não é dono de execução. Não é duplicação de `Planner` — é o estágio anterior a ele.
- `com.brain.dispatch.Dispatcher` — instanciado em `BrainSandboxController`, injetado em `CicloExecucaoPlano` (`android-module`). É o único dispatcher usado no caminho real (`BrainSandboxController → BrainSandboxExecutionBridge → CicloExecucaoPlano → Dispatcher → ActionGateway`). `com.brain.provider.ProviderDispatcher` é uma classe homônima só na palavra "Dispatcher"; responsabilidade é seleção de provider, não distribuição de passo — não concorre com este.
- `com.brain.workflow.WorkflowEngine` — usado em dois lugares, sem concorrência: (1) produção real, dentro de `DurableJobRunner`, envolvendo a execução do plano inteiro como um único node de workflow para ganhar estado/retomada/idempotência a nível de job (`BrainSandboxController` linha do `durableJobs.run`); (2) `BrainIntegrationFacade`, que o próprio código já comenta ser "deliberadamente LOCAL" — feature de workflow para consumo direto da UI Android, fora do pipeline de execução autorizada. Duas instâncias, dois propósitos declarados, mesma classe — não é duplicação de responsabilidade.
- `com.brain.execution.BrainExecutionCoordinator` — **zero chamadores em código de produção** (`app/`, `android-module/`); só aparece em seu próprio arquivo, nos dois testes que o exercitam diretamente (`BrainExecutionCoordinatorAccountTest`, `BrainExecutionCoordinatorMemoryTest`) e numa menção em comentário do `AIRouter`. Já está marcado `@Deprecated` com KDoc explícito dizendo que o caminho Android real é só `CicloExecucaoPlano` e que ele não deve virar segundo pipeline — ou seja, a decisão de não usá-lo já estava tomada e documentada (ver seção "Legado" acima); Fase 2 apenas confirma que o código bate com a decisão.

**Achado original:** `BrainExecutionCoordinator.execute()` tinha três capacidades que não existiam em nenhum lugar do caminho de produção (`Dispatcher`/`CicloExecucaoPlano`): (1) retry com backoff exponencial por passo, (2) gravação de `Experiencia` em `ExperienceMemory` a cada passo, (3) diagnóstico via `RuntimeDoctor`. No caminho real, uma falha "retryable" do gateway em `CicloExecucaoPlano.processarPasso` só lançava exceção (`error(...)`) — sem retry, sem aprendizado, sem diagnóstico.

**Resolvido (mesmo dia, antes da Fase 3):** as três capacidades foram portadas para `CicloExecucaoPlano.processarPasso` (`android-module`), no branch que usa o `Dispatcher` moderno — sem reintroduzir o modelo antigo de múltiplos candidatos/providers:
- Retry com backoff exponencial (mesma fórmula de `BrainExecutionCoordinator`) quando `ActionExecution.retryable == true` e `passo.idempotent`; passo não-idempotente nunca é reexecutado. Falha "retryable" esgotada agora vira `StatusPasso.REPROVADO` com motivo explícito, em vez de lançar exceção — isso corrige um bug latente, não só preenche uma lacuna.
- `CicloExecucaoPlano` ganhou um parâmetro opcional `memory: ExperienceMemory?` (default `null`, sem quebrar quem não passa); quando presente, grava uma `Experiencia` por passo despachado (`SUCESSO`/`CORRIGIDO_APOS_FALHA`/`FALHA`), com o mesmo `safeError` de redação de segredos do coordenador. `BrainSandboxController` agora injeta `FileExperienceMemory(rootfsDir/brain-step-experience.jsonl)` — aprendizado por passo passou a existir no caminho real, não só no coordenador legado.
- `CicloExecucaoPlano` ganhou um parâmetro opcional `runtimeDoctor: RuntimeDoctor` (default `NoopRuntimeDoctor`, igual ao coordenador — nenhuma implementação real de `RuntimeDoctor` existe ainda em lugar nenhum do projeto); diagnóstico e reparo são chamados a cada falha final de passo, criando o mesmo gancho que existia no coordenador, pronto para receber uma implementação real no futuro.
- Coberto por `CicloExecucaoPlanoDispatcherTest` (`android-module`): passo instável reexecutado e aprovado com `CORRIGIDO_APOS_FALHA` na memória; passo idempotente esgota tentativas e reprova sem lançar exceção, registrando `FALHA`; passo não-idempotente nunca é reexecutado.

**O que não foi portado (decisão explícita, não esquecimento):** `BrainExecutionCoordinator` também fazia fallback entre múltiplas contas/providers de uma mesma capability, atualizando saúde de conta por tipo de erro (`updateAccountHealth`/`classifyError`/`TipoErro`) — testado em `BrainExecutionCoordinatorAccountTest`. Isso é uma responsabilidade diferente (múltiplos candidatos concorrendo pela mesma capability) do modelo atual do `Dispatcher` (descoberta escolhe **um** candidato e despacha uma vez). Portar isso significaria redesenhar o `Dispatcher` para aceitar fallback entre candidatos — mudança maior, fora do escopo de "resolver a lacuna de retry/aprendizado/diagnóstico". Por isso `BrainExecutionCoordinator` e `BrainExecutionCoordinatorAccountTest` continuam existindo — não por indecisão, mas porque ainda é a única cobertura de fallback de conta/provider do projeto. `BrainExecutionCoordinatorMemoryTest` também foi mantido por ora (mesmo a lógica de memória já estando portada) para não perder cobertura de regressão até uma limpeza dedicada.

**Critério de conclusão da Fase 2:** confirmado um único caminho de execução sem ambiguidade de dono (`Planner` decompõe, `PlanningArtifact` valida requisitos antes, `Dispatcher` distribui, `WorkflowEngine` dá durabilidade a nível de job) e paridade de capacidade entre caminho real e código legado para retry/aprendizado/diagnóstico. Item de backlog remanescente, explícito e isolado: fallback de conta/provider (`BrainExecutionCoordinator`/`AccountRouter` multi-candidato) — não bloqueia Fase 3.

**Backlog de fallback de conta/provider — fechado (mesmo dia, após revisão):** o item acima foi reavaliado e fechado sem redesenhar o `Dispatcher`, porque as peças já existiam e só precisavam ser conectadas: `CapabilityDiscovery` já ranqueava múltiplos candidatos (`Dispatcher` só travava `maxCandidates` em 1 e pegava `firstOrNull()`), e `AccountRouter.route()` já retornava `alternatives` (só não eram consumidas fora do coordenador legado).

- `TipoErro.classify(error)` virou a fonte única de classificação heurística de erro por texto (antes duplicada em `BrainExecutionCoordinator`); `BrainExecutionCoordinator.classifyError` agora só delega para ela.
- `Dispatcher.dispatch` ganhou `DispatchTask.maxCandidates` (default 1, preserva o comportamento anterior) e `DispatchTask.accountAlternatives` (default vazia): com mais de um candidato e/ou alternativas de conta, ele tenta as combinações candidato×conta em ordem — trocando de conta em falha específica de conta (limite, chave inválida, timeout, erro de servidor) e pulando direto para o próximo candidato em falha de policy/requisição inválida (não adianta trocar de conta) — até um sucesso ou esgotar tudo. `DispatchResult.attempts` expõe cada combinação tentada. O Dispatcher continua sem conhecer `AccountRegistry`; só recebe e itera IDs de conta que o caller já escolheu — a mesma separação de responsabilidade que já valia para Discovery/Policy.
- `CicloExecucaoPlano` ganhou `accountRegistry: AccountRegistry?` (default `null`) e `capabilityFallbackCandidates: Int` (default 3). `decidirComRefresh` agora também devolve as alternativas de conta do `AccountRouteDecision.Selected` e repassa ao `DispatchTask`; depois de cada dispatch (inclusive entre tentativas de retry), `registrarSaudeContas` atualiza a saúde de toda conta efetivamente tentada — sucesso ou falha — fechando também o gap paralelo de saúde de conta nunca refletir execução real no caminho moderno.
- `BrainSandboxController` ganhou `accountRegistry: AccountRegistry? = null` (default `null`, repassado ao `CicloExecucaoPlano`); como `accountPools` no controller de produção ainda é vazio por padrão, isso hoje não muda comportamento — fica pronto para quando existir uma origem real de contas/credenciais.
- Testes: `DispatcherTest` ganhou três casos (fallback entre candidatos de capability, fallback entre contas, e não fallback de conta em erro de policy/requisição inválida). Novo `CicloExecucaoPlanoAccountFallbackTest` cobre o caminho ponta a ponta (`CicloExecucaoPlano -> Dispatcher -> ActionGateway`): conta A falha com chave inválida, conta B assume, e o `AccountRegistry` registra `AUTHENTICATION_ERROR` na conta A e `AVAILABLE` na conta B. `CicloExecucaoPlanoDispatcherTest` e `DispatcherTest` existentes continuam válidos sem alteração (um único candidato/conta real em cada cenário, então o comportamento observável não muda).

`BrainExecutionCoordinator` e seus dois testes (`BrainExecutionCoordinatorAccountTest`, `BrainExecutionCoordinatorMemoryTest`) continuam existindo por ora — não são mais a única cobertura de fallback de conta/provider, mas removê-los é uma limpeza separada, não pré-requisito desta mudança.

**Revisão do item `WorkflowEngine`/`BrainIntegrationFacade` (reaberta e fechada):** a avaliação original acima ("duas instâncias, dois propósitos declarados... não é duplicação de responsabilidade") tratava só da classe `WorkflowEngine` em si. Não considerou que o resultado do workflow local/demonstrativo do `BrainIntegrationFacade` (`runHealthWorkflow`) chegava à UI de produção via `SandboxViewModel.runBrainWorkflow()` rotulado apenas como `"Workflow: COMPLETED/FAILED"`, sem qualquer indicação para o usuário de que aquele ciclo não passava pelo Sandbox real (`sandbox.health` nunca era autorizado/executado de fato — só simulado com `execution = "local"`). Isso é um problema de UX/confiabilidade, não só de arquitetura de código.

Decisão: removido o `WorkflowEngine` local do `BrainIntegrationFacade` (`runHealthWorkflow` e o campo `workflows`). `SandboxViewModel.runBrainWorkflow()` agora chama `BrainSandboxController.healthCheck(runId)` diretamente — o mesmo ciclo autorizado real (`BrainSandboxController → BrainSandboxExecutionBridge → CicloExecucaoPlano`) usado em todo o resto do app. `WorkflowEngine` continua existindo apenas dentro de `DurableJobRunner` (durabilidade de job em produção), que não foi tocado.

## Fase 3 — ADR de nomenclatura "Capability" (concluída)

Documentado em `docs/ARQUITETURA_ATUAL.md`, seção 2.1: diferença entre `CapabilityRegistry` (catálogo declarativo de metadados, `brain/`, estágio de descoberta), `CapabilityResolver` (tradutor de capability autorizada → comando fixo do Sandbox, `android-module/`, estágio de execução), `PolicyBroker` (autoridade de autorização, deny-by-default) e `ActionGateway` (fronteira central de execução, delega ao `ActionExecutor` concreto). Confirmado que os dois nomes parecidos atuam em estágios opostos do fluxo (descoberta vs. execução pós-autorização) e não se sobrepõem.

Renomear `CapabilityResolver` foi avaliado e descartado por ora (passo opcional do plano): o documento já resolve a ambiguidade sem o churn de tocar múltiplos pontos de instanciação e o teste dedicado. Reavaliar se a colisão de nome continuar confundindo na prática.

## Fase 4 — Itens menores (em andamento)

1. **Feedback do botão "Adicionar camada RooftS 0.6" — concluído.** `SandboxViewModel.installRoofts06OverExistingRootfs()` agora reporta explicitamente via `ThreadEvent`: distingue "instalado com sucesso", "já estava instalado, nenhuma mudança necessária" (idempotência visível, antes era silenciosa) e falha com o motivo.
2. **Micro LLM local (`modelFile`/`ensureLocalModelLinkedIntoRootfs`/`buildLocalModelChatCommand` em `AndroidSandboxFactory.kt`) — pendente de decisão do usuário.** Confirmado zero chamadores em produção e testes (grep completo no repo). Achado adicional além do que o plano original listava: `modelResourceManager(modelId)` também está órfã — o subsistema de LLM local inteiro (download do `.gguf`, link pro rootfs, comando de chat via llama.cpp) não tem nenhum ponto de entrada na UI/Brain. O plano cita um segundo documento (`PLANO_ESCALONAMENTO_ENTENDIMENTO_TAREFAS.md`) que não foi fornecido nesta sessão e que supostamente dá um papel real a esse componente — por isso a remoção não foi feita sem confirmação.
3. **Relatório de RootFS por camada — concluído.** `AndroidSandboxFactory` agora captura, durante a extração (`prepareRuntime`), o delta de arquivos/pastas/symlinks/MB de cada camada (0.3 base / 0.4 agent-extra / 0.5 agent-android) via diff de árvore antes/depois de cada `TarGzExtractor.extract`, persistido em `.rootfs-layers-report.json` (sobrevive à deleção dos `.tar.gz` pós-extração). A camada 0.6 é computada ao vivo (vive isolada em `opt/roofts/0.6`, não precisa de diff). Novo método público `layerReport(): List<RootfsLayerStats>`. O "Teste geral" (self-check) trocou o item único agregado por um item por camada na seção "Rootfs no disco"; RootFS extraído antes desta mudança mostra aviso "sem relatório por camada" até ser reinstalado, em vez de quebrar.

## Fase 0 (revisão) — Limpeza de docs/ concluída (2026-09-23)

A Fase 0 original só tinha limpo a raiz (apagado, não arquivado, por decisão do usuário). `docs/` continuava com 57 arquivos soltos além dos 4 canônicos. Revisão: 33 removidos por serem relatórios datados, planos já concluídos/superados ou notas de pesquisa (histórico completo continua no git). 24 mantidos por serem documentação viva — descrevem decisão/implementação atual (série "Item N" de segurança/isolamento, `ROOFTS.md`, `TOOLCHAINS.md`, `PLUGIN_CATALOG.md`, `THIRD_PARTY_NOTICES.md` etc.) ou trabalho planejado e ainda não integrado, referenciado por itens em aberto do `ROADMAP_CANONICO.md` (`PLANO_INTEGRACAO_E2E_BRAINCODE.md`, `PLANO_VALIDACAO_E2E_FASES.md`).

Efeito colateral corrigido: `README.md`, `CLAUDE.md` e `docs/ESTADO_ATUAL.md` citavam `INITIAL.md`, apagado junto com o legado da raiz — as três referências foram removidas.

## PLANO_ESCALONAMENTO_ENTENDIMENTO_TAREFAS.md — Item 1 (concluído)

`DoorPolicy.externalAccountsAllowed` deixou de devolver `false` fixo para as três portas e passou a refletir a regra definida no plano: `Door.CHAT` → sempre `false`; `Door.CREATE` → sempre `true` (a Porta 3 usa API desde o início); `Door.PROMPT` → `true` somente com `escalationRequested = true`.

`DeterministicSecretary.classify` calcula esse `escalationRequested` antes de montar o `DoorScope`, usando `ImprovementVocabulary.pedeIA(original)` — a mesma função que `PromptGenerationExecutor` já usa para decidir `forcarIa`. Nenhuma mudança foi necessária em `PolicyBroker.kt`: ele já lê `doorScope.externalAccountsAllowed` dinamicamente.

**ASSUMINDO:** o plano cita dois gatilhos possíveis para a Porta 2 liberar conta externa — "gate de qualidade insuficiente" OU "pedido explícito do usuário". Só o segundo é calculável no momento da classificação (antes de qualquer geração/autorização); o primeiro só é conhecido dentro do próprio executor, depois da tentativa local. Este item cobre apenas o gatilho de pedido explícito. O caminho de "qualidade insuficiente" (que hoje, com `authorizedAccountIds` sempre vazio para a Porta 2, nunca chega a acionar `improver.melhorar`) fica registrado como pendência do item 3 do plano ("validar que a Porta 2 hoje NÃO está de fato chamando API por fora da política"), não deste item.

Testes atualizados para a nova regra: `DoorPolicyTest` (`nenhuma porta permite contas externas nesta decisao` → dividido em dois testes) e `SecretaryTest` (`corpus de classificacao...` passou a esperar `externalAccountsAllowed` condicionado à porta/gatilho, em vez de sempre `false`).

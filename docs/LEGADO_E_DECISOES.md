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

## Execução de CI/testes e APK — 2026-09-24

Na branch `phase6-apk`, a Fase 6 foi implementada e registrada em `docs/PLANO_3_PORTAS_FASES.md`. O mesmo HEAD passou nos testes JVM Android (`:brain:test`, `:android-module:testDebugUnitTest`, `:app:testDebugUnitTest`), nos 159 testes Python (`python3 -m unittest discover -s tests`), no `architecture-gate`, no `doc-lint`, no `assembleDebug`, no `lintDebug` e no `verify-apk-assets` (25 Roofts Skills e chaves de assinatura confiáveis). O artefato debug e seu checksum foram exportados fora da história de código.

Foram removidos o teste de desenho TOCTOU redundante, o step duplicado do grader semântico Roofts e corrigidos os fixtures de proveniência/readiness dos testes afetados. A implementação foi sincronizada com `origin/phase6-apk`. O CI remoto `35980885173` passou, incluindo o job manual de release readiness. Permanecem pendentes somente UI E2E em emulador, jornada manual do APK e validação automática de identificadores canônicos no `doc-lint`.

A suíte dedicada `ThreeDoorsSimulationE2ETest` foi adicionada para documentar e verificar cinco situações das três portas. Todos os 5 testes passaram: chat normal e pesquisa na Porta 1; reuso de prompt e pesquisa seguida de criação/integração na biblioteca na Porta 2; e criação de app pequeno com aprovação, roadmap, especialistas e workspace na Porta 3. O detalhamento está em `docs/E2E_3_PORTAS.md`.

O benchmark de `IntentEnvelopeTest` também foi estabilizado para runners compartilhados: o limite de mil classificações passou de 1,5 s para 5 s, mantendo o teto de 5 ms por classificação. A mudança não altera o comportamento de produção e foi validada localmente no commit `c00c100`.

## Legado

BrainExecutionCoordinator foi removido (pós-auditoria, item 3.2); o único caminho é `CicloExecucaoPlano`.

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
2. **Micro LLM local — removido (verificado na Fase 5).** `modelFile`/`ensureLocalModelLinkedIntoRootfs`/`buildLocalModelChatCommand`/`modelResourceManager` não existem mais em `AndroidSandboxFactory.kt` nem em nenhum outro ponto do código (grep completo do repo, zero ocorrências). O subsistema de LLM local (download do `.gguf`, link pro rootfs, comando de chat via llama.cpp) não tem nenhum ponto de entrada na UI/Brain porque foi removido; a pendência de confirmação registrada aqui anteriormente está encerrada.
3. **Relatório de RootFS por camada — concluído.** `AndroidSandboxFactory` agora captura, durante a extração (`prepareRuntime`), o delta de arquivos/pastas/symlinks/MB de cada camada (0.3 base / 0.4 agent-extra / 0.5 agent-android) via diff de árvore antes/depois de cada `TarGzExtractor.extract`, persistido em `.rootfs-layers-report.json` (sobrevive à deleção dos `.tar.gz` pós-extração). A camada 0.6 é computada ao vivo (vive isolada em `opt/roofts/0.6`, não precisa de diff). Novo método público `layerReport(): List<RootfsLayerStats>`. O "Teste geral" (self-check) trocou o item único agregado por um item por camada na seção "Rootfs no disco"; RootFS extraído antes desta mudança mostra aviso "sem relatório por camada" até ser reinstalado, em vez de quebrar.

## Fase 0 (revisão) — Limpeza de docs/ concluída (2026-09-23)

A Fase 0 original só tinha limpo a raiz (apagado, não arquivado, por decisão do usuário). `docs/` continuava com 57 arquivos soltos além dos 4 canônicos. Revisão: 33 removidos por serem relatórios datados, planos já concluídos/superados ou notas de pesquisa (histórico completo continua no git). 24 mantidos por serem documentação viva — descrevem decisão/implementação atual (série "Item N" de segurança/isolamento, `ROOFTS.md`, `TOOLCHAINS.md`, `PLUGIN_CATALOG.md`, `THIRD_PARTY_NOTICES.md` etc.) ou trabalho planejado e ainda não integrado, referenciado por itens em aberto do `ROADMAP_CANONICO.md` (`PLANO_INTEGRACAO_E2E_BRAINCODE.md`, `PLANO_VALIDACAO_E2E_FASES.md`).

Efeito colateral corrigido: `README.md`, `CLAUDE.md` e `docs/ESTADO_ATUAL.md` citavam `INITIAL.md`, apagado junto com o legado da raiz — as três referências foram removidas.

## Escalonamento de conta externa por porta — primeira implementação (concluída, depois corrigida)

Um plano avulso não versionado (`PLANO_ESCALONAMENTO_ENTENDIMENTO_TAREFAS.md`, nunca commitado — não confundir com documentação canônica) motivou a primeira versão de `DoorPolicy.externalAccountsAllowed`: `Door.CHAT` → sempre `false`; `Door.CREATE` → sempre `true`; `Door.PROMPT` → condicionado a um parâmetro `escalationRequested` calculado uma única vez em `DeterministicSecretary.classify()`. Esse desenho tinha um bug (Porta 2 nunca escalava num primeiro pedido) e foi **substituído** — ver "Escalonamento da Porta 2 e DoorPolicy" mais abaixo para o estado atual e definitivo: `escalationRequested` não existe mais no código; `DoorPolicy.externalAccountsAllowed(door)` é uma função fixa por porta (`CHAT` → `false`, `PROMPT`/`CREATE` → `true`), e quem decide se a IA é de fato chamada é o executor.

## Auditoria técnica consolidada (2026-09) — substitui `docs/auditoria/`

Os 15 documentos de `docs/auditoria/` (Fases 1–12, dois planos e README) foram consolidados aqui e a pasta foi apagada; o histórico completo está no git (tag `pre-auditoria`). A auditoria foi estática (sem build/teste), com o código como fonte da verdade. Estados usados: LIVE / TEST-ONLY / ORPHAN / PARTIAL; para legado, as classes da "Regra de remoção" acima. Regra que permanece: não apagar código só por estar sem caller — remover legado em commit próprio, com teste de regressão.

### Legado conceitual confirmado

Snapshot Android/Room/DAO copiado do IaBrain como implementação; LLM local obrigatório como cérebro do Chat; Agent autônomo com LLM/objetivo próprio; slash command como autoridade central; RootFS de camada única. `reference/*` e `rootfs-builder/*` não são órfãos: são referência e ferramental de build/release.

### RootFS e toolchains

- Cadeia imutável e homologada: `0.3.3` (base) → `0.4.1` (agent-extra) → `0.5.0` (agent-android). Alterar a base exige nova cadeia; nunca editar release in-place. Tamanhos/SHA-256 ficam em `rootfs-builder/migrate-sandbox-releases.sh` e nos manifests `app/src/main/res/raw/rootfs_*_manifest.json`.
- Os `.tar.gz` (>1 GB) nunca foram inspecionados fisicamente; a validação cobriu Dockerfiles, scripts, manifests, hashes e metadados. Não afirmar conteúdo interno sem baixar e verificar o asset.
- Diagnóstico de "Java não encontrado": `default-jdk` e `gradle` vêm do 0.3.3 e são herdados pelos derivados, então a ausência de Java não é do pacote. Verificar, nesta ordem: RootFS realmente selecionado; camada base montada; `/usr/bin/java` no filesystem materializado; `PATH` efetivo; UID da execução; probe falhando por memória; código olhando o RootFS certo.
- Camadas de "ferramenta" que não se misturam: binário do RootFS ≠ comando `sandbox-*` ≠ toolchain (`BuiltInToolchains`: android, java, python, node, cpp, rust, go) ≠ plugin opcional (`BuiltInCatalog.plugins`: Ollama, NDK, Trivy, SOPS, grpcurl, websocat) ≠ capability. `BuiltInCatalog.tools` é vazio de propósito (ferramentas gerais vêm no RootFS). Existir binário/plugin/capability não é autorização: descoberta → Policy → ActionGateway → executor. `api-tool-placeholder` no 0.4.1 é placeholder deliberado, não executor.
- O catálogo de comandos são prompts especializados (o inventário registrou 344 no snapshot), não executores; os comandos operacionais reais (`/run`, `/testlab`, `/security`, `/git status|diff`, `/workflow`, `/approval demo`, `/workspace new`, `/sqlite start|stop`, `/discovery`, `/deliver`) têm prioridade em `SandboxViewModel.submitThreadInput()`.

### UI

Entrada real: `BrainCodeActivity` → `MainActivity` → `SandboxMobileApp` → `ThreadScreen`/`SettingsScreen` (Provedores, Extensões, Workspace, Toolchains). O cluster de UI órfã de `MainActivity.kt` (`ToolsAndApiScreen`, `SandboxValidationScreen`, `OperationsScreen`, `ChatboxSection`, `ChatBubble`, `DiagnosticsSection`, `SelfCheckReportSection`, `CommandSection`, `QuickCommandsRow`, `ResultSection`) foi removido (513 → 213 linhas), sem navegação oculta. `installedBytes` passou a aparecer na aba Toolchains (`formatBytes` movida para `SettingsScreen.kt`). Ainda não há E2E observável no aparelho para todo o caminho toque → resultado → card na Thread.

### Órfãos secundários (triagem da Fase 11 — decisão de produto pendente, caso a caso)

Mantidos como API preparada / sem superfície de produto: `SandboxViewModel.clearChat`/`clearTerminal`; `SandboxPlatform.runSecurityRegression`/`securityCorpusDigest`/`importRemotePluginSnapshot`; `BrainApiGateway.confirmKnowledge`/`correctKnowledge`; `Services.restart`; `AuthorizedCapabilityExecutor` (testado, sem instanciação produtiva). Decidir: controles de limpar chat/terminal, contrato de regressão Security no `/security` ou CI, fluxo seguro de snapshot remoto, feedback de conhecimento, política de recovery de serviços.

### Fase 12 — conexão de trace e gates

Estado verificado no código deste HEAD:
1. **`ExecutionTrace`/`TraceSink`** — ligado: `BrainSandboxController` instancia `EventStoreTraceSink(events, ...)` e o passa ao `ActionGateway` (mesmo `EventStore`, sem segundo armazenamento; `traceId` = `runId`/`actionId` do `ActionAuditLog`). `SandboxPlatform` fica deliberadamente sem `trace`: é fachada local do sandbox, sem `EventStore` nem `runId` de conversa; se precisar, o `EventStore` deve ser injetado de fora.
2. **`BehaviorGate`s** — unificados: `PostExecutionGate` recebe `VerificationGate`, `CriticGate`, `ReadinessGate` e `LearningGate` e decide o status por eles; `PostExecutionGateUnificationTest` impede voltar à lógica duplicada. O `PostExecutionGate` continua dono de montar `CritiqueResult`/`ReadinessReport`.
3. **`ContextRevisionFixer`** — removido (redundante com `FindingsRevisionFixer`, que também injeta os findings no plano). Confirmado no código.
4. **`PolicyGatedExecutor`** — removido no item 3.1 do plano pós-auditoria (sem referências). A autorização real está em `ActionGateway.execute()`.
5. **Sem LLM, fora do escopo enquanto o plano for sem LLM:** `HttpProviderClient`, `ProviderBrainApiGateway`, `LlmIntentAdvisor` (sem caller; têm teste). Se um dia forem usados: trocar/compor `ConversationBrainGatewayAdapter` por `ProviderBrainApiGateway` onde o `SandboxViewModel` monta o gateway conversacional, e injetar `LlmIntentAdvisor` como revisão opcional do Secretário. Até lá, remover ou marcar experimental.
6. **Reservados para uso futuro (Marco 5.3):** `ApiCatalogCapabilityProvider` e `LazyCapabilityDiscovery` (zero instanciação em produção, por decisão).
7. `BrainExecutionCoordinator` — removido no item 3.2 do plano pós-auditoria (junto com `StepExecutor`, `BrainStepExecutor` e seus 2 testes).

### Escalonamento da Porta 2 e DoorPolicy (concluído em 2026-09-23)

1. **Bug corrigido:** `DeterministicSecretary.classify()` calculava `escalationRequested` uma única vez, sobre a mensagem original, deixando `authorizedAccountIds` vazio; assim, num primeiro pedido ("crie um prompt de X") a IA nunca era chamada mesmo com score abaixo do padrão. Solução aplicada: `DoorPolicy.externalAccountsAllowed(door)` devolve `CHAT` → `false`, `PROMPT` → `true`, `CREATE` → `true`; o parâmetro `escalationRequested` foi removido e quem decide usar a conta é o executor (`PromptGenerationExecutor.escalonar`, por `forcarIa` ou `scoreInicial.abaixoDoPadrao`). Testes: `DoorPolicyTest`, `PromptGenerationExecutorTest` (fake com `requerContaAutorizada() = true`) e `PromptDoorAccountVisibilityTest` (Secretário → `CicloExecucaoPlano` → `Dispatcher` → `ActionGateway` → executor).
2. **Roadmap × código (Opção A):** o gating booleano por porta foi adiantado conscientemente, antes de Porta 1/2 serem declaradas consolidadas; o `ROADMAP_CANONICO.md` recebeu a exceção documentada. Isso não antecipa a camada completa de APIs/providers do Marco 5.3.
3. **`CREATE_BASE_CAPABILITIES`:** verificado que nenhuma capability `provider.*`/`account.*`/`external.*` entra no catálogo/planner nem em `DoorPolicy.allows`/`PolicyBroker.authorize` em produção; a checagem `isExternal()` em `DoorPolicy.kt` fica documentada como reservada, sem efeito hoje. O item 2 do plano original não foi implementado.

## Documentos "DÚVIDA" consolidados (2026-09) — D1 concluído

Sete documentos avulsos de `docs/` (viabilidade do launcher bwrap, apresentação, backlog offline item 5, ADRs das pendências do projeto3, release readiness, teste físico do RooftS 0.6 e plano de atualização de segurança) foram consolidados abaixo e apagados; o histórico está no git (tag `pre-auditoria`). Onde o texto original divergia do código, vale o código.

### Launcher equivalente ao bwrap no Android — decisão

Não é seguro tratar um launcher baseado em `unshare(CLONE_NEWUSER)` como requisito ou capacidade garantida do APK: depende de kernel (`CONFIG_USER_NS`), SELinux, ABI e fabricante, e o `bwrap` setuid não existe mais no upstream. Modo padrão continua `proot` + sandbox por UID/SELinux do app, com diagnóstico explícito de que isso **não** é isolamento OS-level. Não criar `BwrapProcessLauncher` antes de (1) um preflight que teste `CLONE_NEWUSER`, mount, PID e network namespaces separadamente, registrando `errno` e contexto SELinux, e (2) uma matriz em pelo menos um emulador e um dispositivo ARM64, com política explícita de mounts, `/proc`, `/dev`, sockets e capabilities. Se um modo mais forte for exigido pela policy, ele deve falhar fechado em vez de cair para `proot`. Estado no código: `NamespaceSupport.detect()` faz só um preflight parcial (lê `max_user_namespaces` e `unprivileged_userns_clone` em `/proc`); não há launcher de namespaces nem testes por errno.

### ADRs das pendências do projeto3 (decisões vigentes)

- **T-601 — scripts e hooks externos:** só hooks explicitamente declarados, via `SafeSkillResourceExecutor` e `RooftsSkillCatalog.executeDeclaredHook`: sem shell, allowlist de executáveis, workspace existente, timeout máximo de 60 s, saída até 1 MiB, rede negada, permissões vazias. Hook não declarado, com permissão ou com rede é rejeitado. Não há execução automática por descoberta, download, aprovação ou habilitação de Skill.
- **T-602 — permissões:** aprovação explícita no `PolicyBroker`. Nenhum texto, trigger, assinatura ou manifest autoaprova `workspace.write`, rede, credenciais ou efeitos externos.
- **T-603 — grader semântico:** o avaliador determinístico local é o gate obrigatório; um grader semântico externo é opcional e restrito a CI, sem provider, custo ou credencial no app.
- **T-704 — conceitos do OpenClaw:** detecção de loop de ferramentas, compactação/poda de contexto, reset de sessão e perfis de tools = referência (E), sem alterar o runtime sem corpus (o equivalente seguro de perfis de tools é capability/policy); limites de concorrência = já cobertos (B) por `maxParallelism` e pelos leases do `WorkflowEngine`.
- **T-705 — paper e listas de links:** paper de treinamento neural fora do runtime (F); as quatro listas de links preservadas como pesquisa (E). Nada é importado automaticamente; cada referência futura exige auditoria, licença e decisão A–F.
- **T-701/702/703 — prompt library sem licença comprovada:** manter como referência (E); a biblioteca operacional recebe só templates originais do BrainCode ou material com licença verificável (não atribuir licença a um PDF pelo nome).

### Backlog offline (item 5) — encerrado em código

Implementados sem servidor, provider externo nem alteração dos RootFS: rollback transacional e cache do `ToolchainManager` (`ToolchainTransactionStore`, snapshot atômico, rollback automático se a instalação/validação falhar); Security Test Lab determinístico com `SecurityRegressionCorpus` (JSONL local + digest SHA-256) ligado ao `SandboxPlatform`; geração determinística de prompts (`DefaultPromptGenerator`) e eventos locais em `BrainIntegrationFacade`. Cobertura: `OfflineBacklogTest`. O Security Test Lab é sintético e não equivale a ataque adversarial real. Fora do escopo: APK release/keystore, backend distribuído, Postgres/Redis/etcd, multi-host, isolamento OS-level dependente do host. Ressalva de integração: os métodos de geração de prompt/eventos da facade existem, mas vários ainda não têm caller de produção (ver triagem PARCIAL do plano pós-auditoria, item 3.3).

### Release readiness (preflight e gates)

`bash scripts/validate-release-readiness.sh` (a partir da raiz) confere, para os três manifestos RootFS: URL HTTPS de release do `Kyra2214/BrainCode`, `Content-Length` publicado = `sizeBytes` e SHA-256 do sidecar = valor do manifesto. Não baixa os tarballs nem testa `proot`. Os RootFS 0.3.3/0.4.1/0.5.0 são imutáveis; uma falha investiga runtime/ambiente, sem rebuild silencioso. Gates do escopo offline (estado no momento da consolidação; o script não roda no CI hoje):

| Gate | Evidência exigida | Estado |
|---|---|---|
| Build Android | `:brain:test`, `:android-module:test`, `:app:test`, `:app:assembleDebug` (JDK 17 + SDK) | aprovado no clone limpo à época; refazer na Fase 0/7 do plano pós-auditoria |
| Device/emulador ARM64 | `scripts/e2e-smoke.sh` via `adb` | pendente |
| RootFS/proot real | prepare, extrair, `bash`, health check, reset | homologado no Sandbox de origem; revalidar no app/dispositivo é teste de implantação |
| Ciclo de vida | prepare, running, cancelamento, diagnóstico, reset, recuperação | pendente (depende do gate Android) |
| Assinatura de release / infraestrutura OS-level | APK assinado; cgroups, Bubblewrap, firewall, namespaces | futuro, fora do escopo offline |

Checklist de assets Android: `app/src/main/assets/` sem symlinks (`find app/src/main/assets -type l` vazio), Roofts como cópias reais, e `:app:assembleDebug` + instalação real do Roofts 0.6 após mudar assets. `verify-apk-assets.sh` roda no CI; não confere symlinks.

### RooftS 0.6 — teste físico (2026-09-20, APK + RootFS Ubuntu 24.04.4 aarch64)

Agent Skills `0.6.10` (origem addyosmani/agent-skills, commit importado `c004a74784a08295d52749b04cda634125b9a581`) em `/opt/roofts/0.6`. Inventário físico: 25 skills, 4 agents, 9 commands, 8 hooks, 7 references, 16 docs, 13 scripts, 25 eval cases. PASS: `validate-skills.js` (25/25), `run-evals.js` (140 checks; 88/88 rank-1), `session-start-test.sh`, `simplify-ignore-test.sh` (38/38), smoke de `sdd-cache-pre/post.sh` e `session-start.sh` (exit 0). `validate-commands.js` falha com 9 erros por exigir paridade em `.claude/commands` (pendência de paridade, não ausência dos 9 TOML). Pendente de validação física: descoberta de skill numa tarefa real, invocação por agente real, integração do 0.6 com RooftS 0.3/0.4/0.5 e com o BrainCode, e o fluxo completo execução → crítica → revisão → validação → aprendizado no dispositivo. Regra: executar → observar → registrar → PASS/FAIL; presença de arquivos não prova integração.

### Plano de atualização de segurança — estado por item (conferido no código)

Princípio mantido: item só fecha com código, teste que falha no comportamento antigo, chamador real validado, evidência observável e documentação coerente; risco que depende de Android/ARM64 exige dispositivo real. Ordem original: infraestrutura de validação → P0 → P1 → P2.

| Item | Estado no código |
|---|---|
| 0.2 attack probes | feitos em `tests/attack-probes/` (fork bomb, `bash -c` com `dd`/`mount`, `/dev/tcp`, DNS rebinding, truncamento do EventStore); `attack-probes-runner.sh` não está ligado ao CI |
| 0.3 gate arquitetural | `scripts/architecture-gate.sh` (ver `docs/GATE_ARQUITETURAL.md`); não roda no CI |
| 0.1 harness ARM64 | `scripts/arm64-harness.sh`/`e2e-smoke.sh` existem, mas não rodam no CI; o CI usa emulador x86_64 (`ui-e2e.yml`, `marketplace-compat.yml`), não ARM64 |
| 1.1 `networkAllowed` | inversão corrigida (`unshare -n --` só com rede proibida), mas é best-effort: sem `unshare` ou sem user namespaces o launcher segue sem isolamento de rede. **Decisão (2026-09-23):** falhar fechado só quando a policy exigir isolamento (código não confiável); nos demais casos best-effort com evento de auditoria. Docs corrigidos; implementação de código pendente (ver `docs/PROOT_NETWORK_ISOLATION.md`) |
| 1.2 árvore de processos | ver `docs/ITEM_10_PROCESS_TREE.md` |
| 1.3 `maxProcesses` | implementado como opt-in (`ulimit -u`), **desligado por padrão** por `RLIMIT_NPROC` ser por UID no Android; watchdog equivalente não existe — aberto |
| 1.4 bypass do `SecureCommandExecutor` | `bash`/`sh` etc. como executável são rejeitados; denylist (`dd`, `mount`…) permanece como segunda camada; execução preferencial via capabilities autorizadas |
| 1.5 superfícies de execução | git/toolchains/TestLab/diagnostics/plugins do `SandboxPlatform` passam por `GatewayBackedSandboxExecutor → ActionGateway` (ver 3.4 do plano pós-auditoria sobre o segundo Policy/Gateway) |
| 1.6 terminal | decidido: terminal livre só em debug (`docs/TERMINAL_SECURITY_DECISION.md`); em release, se voltar, como `terminal.raw` com aprovação, orçamento e expiração |
| 2.1 assinatura Ed25519 do RootFS | `RootfsSignatureVerifier` existe, mas os três manifestos têm `signatureRequired=false` e `rootfs_trusted_keys.json` está vazio — **aberto** (Marco 3) |
| 2.2 assinatura de `SkillManifest` | `SkillRegistry` recebe chaves confiáveis; detalhes em `docs/skills-trust-authority.md` |
| 2.3 persistência de revogação | feita (`revoked-skills.tsv` em `BrainIntegrationFacade`) |
| 2.4/2.5 SSRF | `NetworkPolicy` cobre loopback, site-local, link-local, IPv4-mapped IPv6; `SandboxResourceManager` faz DNS pinning; ver `docs/PROOT_NETWORK_ISOLATION.md`. Não conferi o `RemotePluginCatalog` |
| 3.1/3.2 EventStore | rotação e checkpoint implementados; o checkpoint é arquivo local ao lado do log, não externo ao dispositivo |
| 3.3 lease/fencing | `WorkflowLease` no `WorkflowEngine` (Kotlin) |
| 3.4 credenciais | `ApiKeyStore` cifra com Android Keystore |
| 3.5 quota agregada | `WorkspaceManager.maxWorkspaceBytes` (2 GiB) |
| 2.6 auditoria documental | contínua: `proot` nunca descrito como jail/kernel sandbox forte |

`APRESENTACAO_BRAINCODE.md` (14/09) foi apagado sem migração de conteúdo: estava desatualizado (por exemplo, dizia que a conversa Android não usava o `BrainApiGateway`) e a descrição vigente está em `ARQUITETURA_ATUAL.md`, `ESTADO_ATUAL.md` e `ISOLAMENTO_REAL_E_LIMITES.md`.

## Duplicados (Fase 2 do plano pós-auditoria) — parcial, 2026-09-23

- **`brain/src/main/resources/catalog/`:** removidos 18 arquivos idênticos a `app/src/main/assets/` (nenhum código do `brain` os lê) e `prompts_biblioteca.json` (seed JSON substituído por `prompts_library.sql`). Ficou só `ai_api_catalog.json`, pendente de **D2**.
- **D2 (catálogo de APIs) — decidido (2026-09-23): o app é 100% gratuito; só entra o que tem acesso gratuito.** O catálogo do `brain` era uma geração antiga (lista estática) e foi removido; o do app (modelo "bootstrap" + descoberta dinâmica) é o único. Dos provedores que só existiam no `brain`, conferidos na web em 2026-09: `zai` **entrou** no app, só com os modelos gratuitos `glm-4.7-flash` e `glm-4.5-flash` (o `glm-5.3` do catálogo antigo é pago, apesar de marcado `FREE_TIER`); `deepseek` (sem plano gratuito permanente na API), `minimax` (sem plano gratuito permanente), `stepfun` (API direta paga) e `tencent-tokenhub` (só créditos promocionais) **não entram**. Regra do loader: só `FREE_TIER`/`FREE_PERMANENT` são carregados; reavaliar os provedores quando a política de preço deles mudar. **Ressalva a tratar no código:** quando o `/models` de um provedor não publica preço, a descoberta dinâmica (`DynamicFreeApiModelDiscovery`, `providerFreeTier = true` para todo provedor do catálogo) considera todos os modelos gratuitos. Em provedores que misturam modelos gratuitos e pagos no mesmo endpoint (caso do Z.ai e, possivelmente, de outros já listados) isso pode listar um modelo pago como gratuito.
- **`reference/braincode-python/`:** removidas 65 cópias byte a byte idênticas à raiz (24 testes, 11 contratos, 28 módulos de `brain_runtime`, 2 docs de skills). Restam as versões mais antigas e divergentes (14 módulos, 10 testes, `contracts/capability.md`, `docs-braincode/SECURITY_TEST_LAB.md`), que dependem de **D3**, além de `AUDITORIA_PESADA.md` e `AnalisedeCodigos/` (últimas cópias existentes, citadas em `ABSORCAO_PROVENIENCIA.md`) e `README_ORIGINAL_BRAIMCODE.md`. A raiz é a versão mais nova (5 módulos e 14 testes que só existem nela).
- **Manifestos RootFS:** a diferença entre o app e o builder (`signatureRequired`) agora está documentada em `rootfs-builder/README.md`.

## Pós-auditoria — Fase 3 (código desconectado), execução parcial

Regra aplicada (D5): pertence a marco futuro do `ROADMAP_CANONICO` ou já tem decisão registrada → **manter e sinalizar**; sem marco e sem decisão → remover.

**Removido (3.1/3.2):** `PolicyGatedExecutor`, `RuntimeFiles`, `BoundAgent.kt` (e `BoundAgentTest`, `AgentExecutionGuardTest`, `AgentRegistryCapabilityTest`), `BrainStepExecutor`, `BrainExecutionCoordinator` (+ `StepExecutor`/`StepAttempt`/`CoordinatorResult`) e seus 2 testes. Cobertura equivalente já existia: fallback de conta em `CicloExecucaoPlanoAccountFallbackTest`; retry/memória em `CicloExecucaoPlanoDispatcherTest`.

**Mantido e sinalizado (3.3):**

| Item | Marco / decisão | Estado |
|---|---|---|
| `BuiltInAgentDefinitions.boundedSpecialists` (12) | 5.4/5.5 | declarado, não integrado |
| `BrainIntegrationFacade.runWorkflow/runDueWorkflows`, marketplace `pin/list/resolve` | 2.6 / 4 | núcleo testado, sem caller de produção (roadmap corrigido) |
| `generatePrompts`/`generateCorrectionPrompt`, `TarefaStateMachine.transicaoValida` | 5.4 (Porta 3, `PLANO_3_PORTAS_FASES`) | sem caller, previstos no fluxo |
| `ProviderAccountRouter`, `ApiDiscoveryEngine`, `ResilientApiCatalog`, `ApiCatalogLoader`, `DynamicFreeApiModelDiscovery` | 5.3 | não integrados |
| `AutoSkillDetector`, `KnowledgeArtifacts`, `RetrievalAdapters`, `BuiltInSkillManifests`, `BrainApiGateway.confirm/correctKnowledge` | 2.5 | não integrados |
| `runSecurityRegression`/`securityCorpusDigest` | 3 (bypass regression corpus) | sem superfície de produto |
| `ManagedSandboxRuntime.recoverInterrupted/reopen` | 4 (recuperação) | sem caller |
| `PackagedRuntime.detectSeccompFallback` | 3 (isolamento OS-level) | sem caller |
| `HttpProviderClient`, `ProviderBrainApiGateway`, `LlmIntentAdvisor` | fora do escopo "sem LLM" (item 5 acima) | mantidos, sem caller |
| `RuntimeDoctor` | gancho do `CicloExecucaoPlano` | só `NoopRuntimeDoctor` |
| `importRemotePluginSnapshot`, `Services.restart`, `SandboxViewModel.clearChat` | "Órfãos secundários" acima | backlog documentado |

**3.4:** ADR registrado em `ARQUITETURA_ATUAL.md` §2.2 (`SandboxPlatform` mantém Policy/Gateway locais; unificar no Marco 4).

## Autorização de workflows automáticos (Marco 4, item 8b) — 2026-09-24

**Problema.** `BrainIntegrationFacade.runWorkflow/runDueWorkflows` e `WorkflowIntegrationService` existiam sem caller de produção; a autorização vinha de uma lambda `authorize: (String) -> Boolean` fornecida pelo caller (permitia `{ true }` por construção, sem DoorScope, sem `PolicyDecision`, sem auditoria). O comentário da facade dizia que não havia caminho próprio de workflow, mas ela mantinha engine/scheduler/lease próprios.

**Decisão.**
1. **Quem autoriza:** só o `PolicyBroker`, via o `ActionGateway` do `BrainSandboxController`. Habilitar, agendar ou resolver um workflow nunca autoriza. Ponto único: `WorkflowRunPort` (brain) implementada por `GatewayWorkflowRunPort` (android-module), obtida com `BrainSandboxController.workflowRunPort()`. O gateway do `SandboxPlatform` (ator `sandbox-platform`) não participa.
2. **Sem lambdas de autorização:** `WorkflowIntegrationService` recebe a porta. O `authorize` do engine é só guarda estrutural (node == `workflow.run`); a decisão é da porta e é refeita no gateway a cada passo, com audit/trace.
3. **Escopo explícito, nunca `doorScope = null`:** Porta 3, fase APROVADA (único ponto em que o `DoorPolicy` libera `workflow.run`), com `NO_WEB` e `NO_PRODUCE`. Run agendado ainda recebe `NO_EXTERNAL_APIS` e nenhuma conta externa, salvo opt-in (`allowExternalAccountsWhenScheduled`). Não foi criada uma Porta nova (mexeria nos `when` exaustivos de `Door`/`DoorPolicy`/`DeterministicSecretary`); reavaliar se surgirem outras automações.
4. **Fail-closed:** qualquer decisão diferente de ALLOW (inclusive ASK) bloqueia. Run agendado não cria approval pendente.
5. **Documento que declara `permissions`/`tools`/`effects` não roda** (manual nem agendado) enquanto não houver mapeamento declarado → capability: o único executor (`WorkflowRunExecutor`) só devolve texto. Os dois workflows embarcados declaram tudo vazio.
6. **Schedule pinado:** `ScheduledWorkflow.contentHash` (+ versão) é gravado ao registrar. Se o documento resolvido mudar (restore, override custom, edição) ou o pin estiver vazio (schedule legado), o tick remove o schedule e emite `workflow.schedule.revoked`; reabilitar registra de novo.
7. **Piso de intervalo:** 15 min (`WorkflowAutomationPolicy.MIN_SCHEDULE_PERIOD_SECONDS`). `every 1 minutes` deixa de agendar (o `/workflow enable` avisa).
8. **Escopo do scheduler:** só com o app aberto (D3 do `DECISOES_MARCO_2_6_ABSORCAO`), single-flight com o sandbox (`phase == Ready`; ocupado adia o tick sem consumir o schedule). Composition root: `SandboxViewModel` registra o executor, conecta a porta e para o runner em `onCleared`/reset. Política e loop NÃO ficam no ViewModel.
9. **Um único caminho:** removidos `BrainIntegrationFacade.runWorkflow/runDueWorkflows`. A facade guarda estado e entrega ao `WorkflowIntegrationService`. `/workflow run <id>` usa a mesma porta.

**Fora de escopo / pendente.** Budget por run (nenhum componente aplica `PolicyContext.budget` hoje). Mapeamento `permissions/tools/effects` → capabilities (pré-requisito para rodar documentos com efeitos). Trilha de auditoria de pré-voo bloqueado (hoje só o `EventStore`, via `workflow.error`).

## Pós-auditoria — Fase 4 (segurança, licença e proveniência) — 2026-09-24

**4.1 `THIRD_PARTY_NOTICES.md` completado.** Adicionadas as seções que faltavam: `no-inference`
(AGPL-3.0, componente empacotado — obrigação de oferta de código-fonte e notices, ver D7
abaixo), SearchClaw/Firecrawl-web-agent (MIT, registrados como **referência de arquitetura**,
não código empacotado — nenhuma dependência real deles é compilada no APK), `talloc`
(LGPL-3.0-or-later), `libandroid-shmem` (BSD-3-Clause) e `commons-compress` (Apache-2.0,
`org.apache.commons:commons-compress:1.26.1`). O aviso do Roofts/Agent Skills já existia.

**4.3 `libapp_proot_loader.so` registrado**, não removido: é o binário `loader` do próprio
pacote `proot_5.1.107.92_aarch64.deb` (mesma origem/licença GPL-2.0 do `libproot.so`),
renomeado pela técnica de `docs/proot-noexec-strategy.md`. SHA-256 confirmado
(`44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04`) e documentado em
`docs/proot-binary-provenance.txt` e em `THIRD_PARTY_NOTICES.md`. **Gap sinalizado:**
`scripts/fetch-proot.sh` não extrai esse arquivo automaticamente hoje (foi obtido
manualmente); atualizar o script fica como item de backlog para manter a build
reproduzível.

**4.4** `.gitignore` recebeu `*.jks`, `*.keystore`, `.ci/` e `e2e-report/`.

**4.5** Estado atual dos manifestos RootFS (`signatureRequired: false` nos três + `rootfs_trusted_keys.json` vazio) documentado explicitamente em `docs/ROOTFS_SIGNATURES.md` ("Estado atual dos três manifestos distribuídos"), além da entrada já existente na tabela de item 2.1 acima. Reemissão assinada e chave pública continuam no Marco 3.

**D7 — decisão jurídica pendente (licença do projeto vs. AGPL-3.0 do `no-inference`) — não
decidida nesta rodada.** O repositório não tem `LICENSE` na raiz e agora empacota um
componente AGPL-3.0 (`no-inference`) dentro do APK. Isso não é aconselhamento jurídico;
registrar aqui só para decisão do dono:
- **Opção A — licenciar o BrainCode inteiro sob AGPL-3.0 (ou compatível, ex. GPL-3.0/LGPL
  cascata acima).** Mais simples de cumprir; qualquer distribuição do APK (inclusive uma
  loja de apps) passa a exigir oferta de código-fonte completo do projeto, não só do
  componente importado.
- **Opção B — manter o BrainCode sob outra licença (ou fechado) e isolar/segregar o
  componente AGPL** (ex.: rodar como processo separado com IPC, ou remover o
  `no-inference` e substituir o motor conversacional por um componente com licença mais
  permissiva). Evita "contaminar" o restante do código, mas dá mais trabalho de
  engenharia e pode exigir remover o adapter atual.
- **Opção C — manter como está sem `LICENSE` na raiz.** Não recomendado: um repositório
  sem licença declarada é "todos os direitos reservados" por padrão, o que é inconsistente
  com empacotar um componente AGPL e com o espírito open-source do restante do projeto.
- Enquanto D7 não for decidido, nenhum `LICENSE` foi adicionado à raiz nesta fase — decisão
  de licenciamento de projeto não é algo para presumir. Validar a escolha final com
  advogado antes de qualquer distribuição pública do APK.

## Correções do Secretário e do ciclo de chat — 24/09/2026

A auditoria da Porta 1 identificou três problemas relacionados. Primeiro, `ResponseComposer.synthesizeResearch()` aceitava boilerplate de páginas web — cookies, menus, idiomas e login — quando esses trechos continham termos do tópico. A síntese agora rejeita padrões de navegação/UI, prioriza sentenças com dados concretos em perguntas factuais e retorna uma resposta neutra quando não há conteúdo limpo relacionado. Segundo, o relatório completo de verification/critique/revision/readiness/evidence era publicado como `ThreadEvent.Report` no mesmo feed das mensagens do chat. `SandboxViewModel.publishCycleStages()` agora mantém esse relatório em `diagnosticsReport`, reservado ao painel de diagnóstico; o feed conversacional conserva mensagens e steps de progresso.

Por fim, o classificador informacional deixou de ser uma lista de permissões baseada no início e na forma textual da frase. Para um `localMiss`, a recuperação passou a depender de `researchFallback` disponível, ausência de `NO_WEB` e ausência de clarification; saudações e comandos explícitos continuam excluídos. `ConversationResult.researchAttempted` registra se o `WebResearchAgent` realmente foi executado. O `DeterministicSecretaryGate` bloqueia `chat:conversation:local-miss` sem essa tentativa quando a recuperação está disponível e somente libera uma resposta honesta após a rota de pesquisa ter sido consumida. Regressões foram adicionadas em `ResponseComposerTest`, `ChatResponseExecutorTest`, `InformationalQuestionClassifierTest` e `TextConversationContractsTest`. A solicitação de origem está preservada em `correcao-bugs-secretario-chat.md`.

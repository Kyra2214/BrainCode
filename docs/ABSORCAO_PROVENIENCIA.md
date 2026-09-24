# Registro de absorção — Agent Skills / Roofts 0.6

## Escopo desta rodada

Esta rodada aplica as fatias executáveis do Marco 2.6 — **auditar → licença → código real → segurança → compatibilidade → incorporar → validar → documentar proveniência**. Foram implementados o catálogo lazy de Skills, o plano explícito de ativação, o registro seguro de manifestos desabilitados, o avaliador determinístico e o ciclo declarativo de `WORKFLOW.md`. O baseline e todos os gates locais — suítes, architecture gate, assemble e lint — passaram; CI, E2E, dispositivo e readiness continuam pendentes.

## Fonte auditada

| Campo | Registro |
|---|---|
| Projeto upstream | `addyosmani/agent-skills` |
| Tag | `0.6.10` |
| Commit | `c004a74784a08295d52749b04cda634125b9a581` |
| Papel | Conteúdo de Agent Skills em `SKILL.md`, frontmatter e recursos auxiliares |
| Licença | MIT; cópia preservada em `app/src/main/assets/roofts/0.6/LICENSE` |
| Registro canônico | `docs/ROOFTS_0.6.md` |

A auditoria confirmou que o upstream é uma coleção de conteúdo e scripts auxiliares, não um segundo orquestrador. O BrainCode absorve nesta rodada apenas o contrato de descoberta, metadados e filtros de seleção; texto de Skill continua sendo dado não confiável e não concede autorização.

## Arquivos incorporados/adaptados

| Camada | Arquivo | Modificação local |
|---|---|---|
| contrato | `brain/src/main/kotlin/com/brain/skill/RooftsSkill.kt` | Metadados opcionais de `triggers`, `exclusions`, `resources`, SHA-256 e origem |
| loader | `app/src/main/kotlin/com/sandbox/app/RooftsSkillLoader.kt` | Parser de frontmatter/listas, hash do conteúdo e caminho do asset |
| seleção | `brain/src/main/kotlin/com/brain/skill/RooftsSkillSelector.kt` | Respeito a não-gatilhos e uso de triggers declarados |
| caller | `app/src/main/kotlin/com/sandbox/app/CodeGenerationExecutor.kt` | Caller existente preservado; continua limitando o corpo e enviando somente contexto |
| ativação | `brain/src/main/kotlin/com/brain/skill/RooftsSkillActivation.kt` e `RooftsSkillManifestBridge.kt` | Plano, permissões pendentes e registro desabilitado no `SkillRegistry` |
| avaliação | `brain/src/main/kotlin/com/brain/skill/RooftsSkillEvaluation.kt` | Casos e score determinísticos sem provider externo |
| workflows | `brain/src/main/kotlin/com/brain/workflow/WorkflowDocument.kt` | Parser, precedência custom, enable/disable, schedule, backup/restore e adapter do engine |
| infraestrutura | `brain/src/main/kotlin/com/brain/workflow/WorkflowScheduler.kt` e `WorkflowMarketplace.kt` | Scheduler persistente com claim/lease e registry HTTPS pinado com assinatura Ed25519 |
| caller Android | `app/src/main/kotlin/com/sandbox/app/SandboxViewModel.kt` e `BrainIntegrationFacade.kt` | Catálogo lazy no executor e descoberta no registry |
| testes | `app/src/test/kotlin/com/sandbox/app/RooftsSkillLoaderTest.kt`, `brain/src/test/kotlin/com/brain/skill/*ActivationTest.kt` e `brain/src/test/kotlin/com/brain/workflow/WorkflowDocumentTest.kt` | Cobertura focada de lazy loading, ativação, avaliação e workflow |

Não houve cópia de um runtime externo, marketplace como autorização, instalador, comando shell, provider, rede ou recurso executável para dentro do BrainCode. O ciclo de workflow é nativo e entrega o documento ao `WorkflowEngine`; ele não interpreta Markdown como código.

## Segurança e compatibilidade

O loader só lê assets já empacotados no aplicativo. O selector é determinístico e local. Triggers são sinais de descoberta; `exclusions` impedem ativação por coincidência, mas nenhum deles altera `PolicyBroker`, `ActionGateway`, `CapabilityRegistry`, Sandbox, credenciais ou rede. O hash identifica o conteúdo carregado para futura invalidação de cache e evidência; não é tratado como autorização isoladamente. Os recursos declarados são apenas metadados nesta fatia; ainda não há execução automática nem resolução externa.

A mudança mantém construtores existentes compatíveis por meio de campos opcionais e preserva o limite de contexto já aplicado por `CodeGenerationExecutor`. A camada 0.6 continua sendo uma única entidade Roofts, sem criação de `SkillRuntime` paralelo.

## Overlay local e avaliação versionada

O arquivo `app/src/main/assets/roofts/0.6/braincode-skill-overlay.json` é **ADAPTER BRAINCОDE** e não é uma cópia ou alteração do upstream. Ele cobre as 25 Skills presentes no asset, com gatilhos/exclusões e campos de política separados. Seu SHA-256 é `d1413b8ee66b92c22d4695b4a9c1b615643343b770345b8af7bd13e4a6262dda`; o loader registra esse valor em `RooftsSkill.overlayHash`, mantendo `contentHash` exclusivamente para o `SKILL.md` original.

Os casos `brain/src/test/resources/catalog/roofts-skill-evaluation-cases.json` são a fonte versionada dos objetivos esperados. `RooftsSkillEvaluationTest` lê os 25 `SKILL.md` reais, aplica os gatilhos do overlay e exige score mínimo de `0.92`; a execução desta rodada passou com score integral.

## Validação

- **PASS** — `:brain:test` completo, incluindo avaliação das 25 Skills e testes de workflow/marketplace.
- **PASS** — `:android-module:test` completo em debug/release.
- **PASS** — `:app:testDebugUnitTest` completo; as 13 falhas históricas do baseline foram eliminadas.
- **PASS** — `bash scripts/architecture-gate.sh`, `:app:assembleDebug` e `:app:lint`.
- **PASS** — compilação dos testes instrumentados T-203; execução em emulador/dispositivo permanece pendente.

CI, E2E, emulador/dispositivo e readiness de release ainda não foram executados naquela sessão histórica.

A segunda rodada adicionou `WorkflowEngine.runDocument`, scheduler persistente com ciclo due/claim/complete, dois workflows originais somente leitura, chaves públicas Ed25519 compartilhadas entre marketplace e `SkillRegistry`, workflow de compatibilidade API 26/33 e gate de assets do APK. Os gates locais continuam verdes; CI, E2E e execução física aguardam o GitHub Actions.

Também foram incorporados `RooftsSemanticGrader`, opcional e offline para CI, e `SafeSkillResourceExecutor`, opt-in para hooks explicitamente declarados. Este último não usa shell, exige allowlist, workspace, timeout e limite de saída, nega rede e bloqueia permissões; descoberta ou seleção de Skill não o invoca automaticamente.

**Atualização da execução `phase6-apk` (2026-09-24):** os gates locais foram repetidos no HEAD entregue: 159 testes Python, testes `brain`/`android-module`/`app`, `assembleDebug`, `lintDebug`, `architecture-gate`, `doc-lint` e `verify-apk-assets` passaram. O CI remoto `35980885173` também passou, incluindo release readiness manual, e o APK debug foi exportado com checksum registrado. Permanecem pendentes UI E2E em emulador/dispositivo e jornada manual do APK.

## Referências estudadas, não incorporadas

`nikilster/clawflows` foi auditado no commit `f1e4094752b0359c7a3089720457a536a4ae2813` conforme `reference/braincode-python/AnalisedeCodigos/Manus/deep/06-clawflows.md`. Seu formato `WORKFLOW.md` e ciclo de habilitação foram absorvidos nativamente no adapter do `WorkflowEngine`; nenhum código Clawflows, CLI, symlink, scheduler ou workflow comunitário foi copiado nesta rodada.

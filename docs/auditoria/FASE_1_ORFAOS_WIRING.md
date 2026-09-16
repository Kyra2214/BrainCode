# Auditoria BrainCode — Fase 1: órfãos e wiring

**Snapshot auditado:** `ffed42a4fd83095797dfd6bf53213a82b1c8788d` (`main`)
**Data:** 2026-09-16
**Regra:** código, manifestos, referências de código e testes são a fonte da verdade. Documentação foi usada somente como pista e nunca como prova de integração.

## 1. Objetivo

Encontrar arquivos, classes, funções e telas existentes no projeto que não estejam ligadas ao runtime real, além de descobrir componentes que parecem integrados mas cujo caller real não aparece no código.

A auditoria considera quatro estados:

- **LIVE** — existe caminho de execução real a partir de um entrypoint, serviço, controller ou chamada de produção.
- **TEST-ONLY** — aparece apenas em testes.
- **ORPHAN** — arquivo/componente de produção sem caller de produção identificável.
- **PARTIAL** — existe e é chamado, mas uma etapa seguinte do fluxo ainda não está comprovada.

Não foram removidos arquivos nesta fase.

## 2. Entry points reais encontrados

### Android

`AndroidManifest.xml` declara `BrainCodeActivity` como launcher e `BrainCodeExecutionService` como foreground service. `BrainCodeActivity` herda de `MainActivity`, portanto `MainActivity` não é órfã: ela é a superclasse do launcher real.

Cadeia confirmada no código:

```text
AndroidManifest
  -> BrainCodeActivity
      -> MainActivity.onCreate()
          -> SandboxMobileApp()
              -> ThreadScreen()
              -> SettingsScreen() quando solicitado
```

O serviço de execução também está registrado no Manifest e é iniciado pelo launcher.

## 3. Primeiro achado importante: cluster de UI órfão

`MainActivity.kt` contém três telas que não possuem caller de produção além da própria declaração:

### 3.1 `ToolsAndApiScreen`

Estado: **ORPHAN**.

A busca por `ToolsAndApiScreen` encontrou somente sua própria declaração. Ela contém UI para Ferramentas/Chaves de API, mas a navegação atual de produção usa `SettingsScreen`, que possui abas próprias para Provedores e Extensões.

### 3.2 `SandboxValidationScreen`

Estado: **ORPHAN**.

A declaração existe em `MainActivity.kt`, porém não há chamada de produção identificada para essa composable. Ela reúne `StatusSection`, `ChatboxSection`, comandos de debug e relatórios.

### 3.3 `OperationsScreen`

Estado: **ORPHAN**.

A declaração existe, mas não há caller de produção identificado. Ela reúne TestLab, Security Assessment, workspace, Git, SQLite, Brain workflow/discovery/delivery e toolchains.

### 3.4 Consequência: subcomponentes que só existem para essas telas

`ChatboxSection` é chamado pela `SandboxValidationScreen`. Como a tela pai não possui caminho de produção, `ChatboxSection` também é um componente **órfão transitivo**.

Isso é importante porque não basta procurar classes sem referências: uma função pode ter caller, mas o caller pode estar dentro de uma tela morta.

## 4. O que NÃO é órfão

Foram verificados caminhos conhecidos que anteriormente poderiam parecer suspeitos:

- `BrainCodeActivity` — LIVE via Manifest.
- `MainActivity` — LIVE como superclasse do launcher.
- `ThreadScreen` — LIVE via `SandboxMobileApp`.
- `SettingsScreen` — LIVE via `SandboxMobileApp`.
- `PluginsScreen` — LIVE via `SettingsScreen`/Extensões.
- `ApiKeysScreen` — LIVE via `SettingsScreen`/Provedores.
- `SandboxViewModel` — LIVE, injetado por `MainActivity` e usado pela UI.
- `ApiKeyCatalogLoader` — LIVE; `SandboxViewModel` carrega `ai_api_catalog.json`.
- `ComandosCatalogoLoader` — LIVE; `SandboxViewModel` carrega `comandos_catalogo.json`.
- `SkillsCatalogoLoader` — LIVE; `BrainIntegrationFacade` registra as Skills no runtime.
- `ExplorerSeedLoader` — LIVE; `BrainIntegrationFacade` adiciona candidatos ao discovery.
- `PluginCatalogCapabilityProvider` — LIVE; `SandboxViewModel` injeta o provider ao criar o controller.

## 5. Wiring do chat

O botão de envio da UI chama `SandboxViewModel.sendChatMessage()`.

O código atual possui dois caminhos principais:

```text
UI
 -> SandboxViewModel.sendChatMessage()
 -> decisão do caminho da mensagem
 -> BrainApiGateway.complete(prompt) para o caminho de resposta por provider
```

e, para a execução de objetivo/comando operacional:

```text
UI
 -> SandboxViewModel.submitThreadInput()
 -> BrainSandboxController.executeObjective()
 -> FastIntentClassifier
 -> KeywordPlanner
 -> CicloExecucaoPlano
 -> Dispatcher
 -> ActionGateway
 -> executor/Sandbox
```

A presença de `BrainApiGateway.complete` no próprio `SandboxViewModel` elimina o antigo falso positivo de "gateway criado mas sem caller" para o HEAD atual.

## 6. Componentes anteriormente suspeitos e agora ligados

### `FastIntentClassifier`

LIVE. `BrainSandboxController` instancia o classificador e usa `classify(objective)` durante `executeObjective`.

### `KeywordFunctionSplitter`

LIVE transitivamente. `KeywordPlanner` recebe `KeywordFunctionSplitter` como implementação padrão de `FunctionSplitter`.

### `KeywordPlanner`

LIVE. `BrainSandboxController.executeObjective()` instancia o planner e o usa no ciclo de execução.

### `DurableJobRunner`

LIVE. `BrainSandboxController` instancia `DurableJobRunner` com `JobStore` e `WorkflowEngine`. Também há cobertura de teste de produção do módulo Brain.

### `CapabilityDiscovery` / registry / providers

Há wiring de produção a partir do controller e providers. A existência dos componentes não é tratada como prova isolada; o controller é o elo de integração.

## 7. Descoberta de Skills e Explorer

Os dois gaps encontrados anteriormente estão corrigidos no HEAD:

- `SkillsCatalogoLoader.load(context)` lê `assets/skills_catalog.json` e `BrainIntegrationFacade` registra cada entrada.
- `ExplorerSeedLoader.load(context)` lê `assets/explorer_china_seed.json` e `BrainIntegrationFacade` converte os itens em candidatos do discovery.

Os commits específicos são `fb8c919` e `ec3e5ea`.

## 8. Resultado da Fase 1

### Órfãos de produção identificados

| Componente | Estado | Motivo |
|---|---|---|
| `ToolsAndApiScreen` | ORPHAN | sem caller de produção |
| `SandboxValidationScreen` | ORPHAN | sem caller de produção |
| `OperationsScreen` | ORPHAN | sem caller de produção |
| `ChatboxSection` | ORPHAN transitivo | pertence à tela de validação órfã |

### Candidatos que NÃO devem ser removidos

Tudo que esteja ligado a `ThreadScreen`, `SettingsScreen`, `SandboxViewModel`, controller, gateway, providers, catálogo, discovery ou serviço deve continuar até auditoria posterior provar o contrário.

## 9. Próxima investigação

A Fase 2 deve separar **órfão atual** de **legado arquitetural**. Um componente pode não ser chamado e ainda assim ser parte de uma arquitetura histórica que deve ser removida; inversamente, um componente não chamado pode ser infraestrutura preparada para uso futuro e não deve ser apagado sem confirmação.

Nenhuma exclusão é autorizada somente pelo resultado desta fase.

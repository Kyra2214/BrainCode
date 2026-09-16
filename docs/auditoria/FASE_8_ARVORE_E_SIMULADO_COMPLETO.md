# Auditoria BrainCode — Fase 8: árvore do sistema + simulado completo

**Snapshot de código:** `ffed42a4fd83095797dfd6bf53213a82b1c8788d`
**Objetivo:** reconstruir o sistema de ponta a ponta a partir do código, sem aceitar documentação como autoridade.

---

# PARTE A — ÁRVORE REAL DO SISTEMA

## 1. Instalação do APK

```text
APK
 ↓
AndroidManifest.xml
 ↓
BrainCodeActivity (launcher)
 ↓
MainActivity
 ↓
SandboxMobileApp
 ↓
ThreadScreen
```

`BrainCodeActivity` é a Activity declarada como launcher. Ela chama `super.onCreate()` e depois inicia `BrainCodeExecutionService`.

O serviço é um anchor de execução em foreground para trabalhos longos. A UI não é o executor do processo: ela conversa com o ViewModel.

## 2. Primeira abertura

O `SandboxViewModel` é criado pela Activity com `viewModels()`.

O ViewModel inicializa/coordena:

- armazenamento de sessões;
- API key store;
- catálogo de APIs;
- catálogo de comandos;
- runtime/Sandbox;
- BrainIntegrationFacade;
- BrainSandboxController;
- catálogo de plugins/capabilities;
- toolchains;
- estados de instalação;
- eventos e resultados.

O app prepara o Sandbox conforme o estado do RootFS.

## 3. RootFS

A cadeia física é:

```text
manifesto 0.3.3
  ↓ download HTTP
rootfs-ubuntu-0.3.3.tar.gz
  ↓ extração/materialização
RootFS base
  ↓ camada 0.4.1
rootfs-agent-extra-0.4.1.tar.gz
  ↓ camada 0.5.0
rootfs-agent-android-0.5.0.tar.gz
  ↓
ambiente executável do Sandbox
```

Os três releases são separados. O app não deve tratá-los como um único arquivo monolítico.

O RootFS base contém Java/Gradle; portanto a ausência de Java precisa ser diagnosticada no ambiente materializado, e não atribuída ao Dockerfile sem evidência.

## 4. Usuário digita uma tarefa

Exemplo:

```text
"Quero um aplicativo de controle de estoque em Kotlin, com tela de produtos,
banco local, testes e um APK final."
```

O caminho é:

```text
ThreadComposer
 ↓
SandboxViewModel.submitThreadInput()
 ↓
classificação da entrada
 ├── comando operacional?
 │      └── executa handler delimitado
 │
 ├── slash do catálogo?
 │      └── transforma em prompt especializado
 │
 └── tarefa livre
        ↓
BrainSandboxController.executeObjective()
```

## 5. Dentro do BrainSandboxController

O código atual faz:

```text
executeObjective(objective)
 ↓
TaskCreated event
 ↓
FastIntentClassifier.classify(objective)
 ↓
KeywordPlanner
 ↓
KeywordFunctionSplitter
 ↓
PlanoExecucao
 ↓
PromptLibrary Retrieval opcional
 ↓
DurableJobRunner
 ↓
executeWithEvents
 ↓
BrainSandboxExecutionBridge
 ↓
CicloExecucaoPlano
```

O `DurableJobRunner` registra a tarefa como workflow retomável e executa o passo `brain.plan`.

## 6. Capability Discovery

O controller constrói um `CapabilityRegistry` com capabilities do Sandbox e providers dinâmicos.

No app, `PluginCatalogCapabilityProvider` transforma componentes do catálogo em capabilities `plugin.<id>`.

Para plugins instalados, o provider pode atualizar disponibilidade quando `refreshCapabilities()` é chamado.

Caminho:

```text
BuiltInCatalog / provider dinâmico
 ↓
CapabilityProvider
 ↓
CapabilityRegistry
 ↓
CapabilityDiscovery
 ↓
Dispatcher
```

## 7. Policy

Antes da execução:

```text
Plano
 ↓
PolicyBroker
 ↓
capacidade permitida?
 ├── não → bloqueio/approval
 └── sim → AuthorizedPlan
```

O controller cria `PolicyBroker` com as capabilities registradas e o actor `android-app`.

Isso é autorização, não apenas descoberta.

## 8. Gateway

Depois da autorização:

```text
AuthorizedPlan
 ↓
BrainSandboxExecutionBridge
 ↓
CicloExecucaoPlano
 ↓
Dispatcher
 ↓
ActionGateway
 ↓
BrainActionExecutor
 ↓
Sandbox
```

O `ActionGateway` é a fronteira de execução. O usuário não deveria conseguir inserir uma string e pular essa fronteira.

## 9. Sandbox

O Sandbox recebe a ação autorizada e trabalha sobre o RootFS materializado.

O executor chama processos por comandos estruturados. O RootFS fornece os binários.

Exemplo:

```text
capability sandbox.build
 ↓
policy
 ↓
ActionGateway
 ↓
Sandbox executor
 ↓
java/gradle/node/python/etc.
 ↓
stdout/stderr/exit code
```

## 10. Eventos e evidências

O controller emite eventos como:

- `TaskCreated`;
- `PlanCreated`;
- `ExecutionFailed`;
- `Delivered`;
- `ValidationFailed`.

O ciclo também carrega evidências por passo.

Depois o resultado volta ao ViewModel e vira `ThreadEvent`/mensagem/relatório para a UI.

---

# PARTE B — CATÁLOGOS

## 11. Skills

```text
assets/skills_catalog.json
 ↓
SkillsCatalogoLoader
 ↓
BrainIntegrationFacade
 ↓
SkillRegistry
 ↓
Brain
```

Esse wiring foi implementado nos commits `fb8c919` e `ec3e5ea`.

## 12. Explorer China Seed

```text
assets/explorer_china_seed.json
 ↓
ExplorerSeedLoader
 ↓
ExplorerCandidate
 ↓
BrainIntegrationFacade
 ↓
Discovery
```

Não é execução automática; é fonte de candidatos para descoberta.

## 13. APIs

```text
assets/ai_api_catalog.json
 ↓
ApiKeyCatalogLoader
 ↓
SandboxViewModel.apiProviders
 ↓
ApiKeysScreen
```

As chaves são armazenadas pelo `ApiKeyStore`.

O `BrainApiGateway` recebe catálogo, store e router para executar providers gratuitos quando o caminho de resposta por API é escolhido.

## 14. Comandos

```text
assets/comandos_catalogo.json
 ↓
ComandosCatalogoLoader
 ↓
catalogoComandos
 ↓
catalogoPorComando
 ↓
sugestoesDeComando
 ↓
ThreadComposer
```

Os comandos PROMPT não são shell.

---

# PARTE C — SIMULADO ESCRITO DE UMA TAREFA COMPLETA

## Cenário

O usuário instalou o APK no celular, quer criar um aplicativo e pede:

> "Quero um app Android de controle de estoque. Crie o projeto, faça a tela de produtos, use banco local, teste, gere o APK e deixe tudo pronto no workspace."

## Passo 1 — abrir o app

1. Android instala APK.
2. Sistema lê `AndroidManifest.xml`.
3. `BrainCodeActivity` é escolhida como launcher.
4. `onCreate()` chama `MainActivity.onCreate()`.
5. `MainActivity` cria Compose.
6. `SandboxMobileApp` mostra `ThreadScreen`.
7. O serviço de foreground é iniciado.

## Passo 2 — preparar o ambiente

O ViewModel verifica o estado do Sandbox.

Se o RootFS necessário não estiver disponível:

```text
manifesto
 ↓
ResourceManager
 ↓
download resumível
 ↓
SHA-256
 ↓
extração
 ↓
Sandbox pronto
```

Se o download estiver incompleto, o mecanismo de recurso pode retomá-lo. O app não deve declarar o RootFS pronto antes da validação.

## Passo 3 — usuário digita

Na caixa da Thread:

```text
Quero um app Android de controle de estoque...
```

O usuário toca em enviar.

O callback chega ao `SandboxViewModel.submitThreadInput()`.

O texto é registrado como evento de usuário.

## Passo 4 — classificação

Se não for `/testlab`, `/run`, `/workflow`, `/discovery` etc., o input segue como objetivo livre.

`BrainSandboxController.executeObjective()` recebe o texto.

Ele registra:

```text
TaskCreated
```

## Passo 5 — intenção

`FastIntentClassifier` identifica sinais como:

- aplicativo;
- Android;
- criar;
- banco;
- teste;
- APK.

Isso não executa nada. Apenas classifica.

## Passo 6 — planejamento

`KeywordPlanner` recebe o objetivo classificado.

Ele usa `KeywordFunctionSplitter` para dividir a tarefa em passos/capabilities.

Uma representação conceitual do plano pode ser:

```text
1. produzir → workspace.write
2. executar → sandbox.code
3. testar → sandbox.test
4. construir → sandbox.build
```

Os IDs concretos dependem da classificação atual; o importante é que o plano contém capabilities, não comandos shell arbitrários.

## Passo 7 — recuperação

Se houver PromptLibrary configurada, o controller tenta recuperar um template relevante.

Se encontrar:

```text
basePlan
 + assumption: prompt-template:<id>
```

Se não encontrar, segue sem inventar conteúdo.

## Passo 8 — job durável

`DurableJobRunner` cria/atualiza um job local.

O workflow possui o passo `brain.plan`.

Isso cria uma fronteira de retomada para a execução.

## Passo 9 — capabilities

O plano chega ao `BrainSandboxExecutionBridge` e ao `CicloExecucaoPlano`.

O `Dispatcher` usa `CapabilityDiscovery` para encontrar a capacidade adequada.

Se o trabalho precisar de uma ferramenta/plugin, o catálogo pode fornecer a definição.

## Passo 10 — Policy

`PolicyBroker` verifica:

- actor;
- capability;
- risco;
- autorização necessária;
- approval quando aplicável.

Se exigir aprovação humana, a Thread recebe um evento de aprovação e a execução pausa.

O usuário toca **Aprovar e retomar**.

O ViewModel chama `approveAndResume()` e o controller retoma o plano autorizado.

## Passo 11 — execução

O `ActionGateway` recebe a ação autorizada.

O `BrainActionExecutor` usa o Sandbox.

O Sandbox executa dentro do RootFS.

Para criar o projeto Android, podem aparecer chamadas como:

```text
mkdir /home/sandbox/workspace/projects/estoque
arquivos Kotlin/Gradle
java
gradle
adb/aapt2/apksigner/zipalign quando necessários
```

Os comandos reais dependem do plano/capability; o usuário não injeta diretamente uma linha shell no Gateway.

## Passo 12 — banco local

O plano pode selecionar SQLite ou outra capability disponível.

O Sandbox usa o binário/ambiente fornecido pelo RootFS.

A Policy continua sendo aplicada antes da ação.

## Passo 13 — testes

O plano pode chegar a `sandbox.test`.

O RootFS possui Python/pytest, Node/Vitest e outras ferramentas. O perfil Android possui ferramentas adicionais para build Android.

O resultado retorna como stdout/stderr/exit code e evidência.

## Passo 14 — build APK

Quando o plano chega ao build:

```text
workspace
 ↓
gradle/java/android toolchain
 ↓
APK
 ↓
resultado de execução
```

Se o APK precisar ser assinado para desenvolvimento, isso depende da configuração do projeto/CI; `apksigner` existe no perfil Android.

## Passo 15 — Git

O `GitManager` fornece operações de:

- clone;
- pull;
- push;
- branch;
- checkout;
- commit;
- diff;
- status.

Porém a UI atual expõe diretamente status/diff; commit/push não são atualmente um botão dedicado no fluxo principal.

Portanto existem duas possibilidades:

### Git final via capability/integração futura

```text
alterações
 ↓
git status
 ↓
git diff
 ↓
commit
 ↓
push
```

A API `GitManager` possui esses métodos, mas a existência do método não significa que a UI atual os chama.

### Entrega local atual

A aplicação pode produzir uma entrega local pelo `/deliver`.

## Passo 16 — `/deliver`

O comando `/deliver` chama `publishLocalDelivery()`.

Esse método usa `ObservableDelivery`.

`ObservableDelivery.publish()`:

1. recebe um diretório;
2. percorre arquivos reais;
3. ignora links simbólicos;
4. calcula SHA-256 de cada arquivo;
5. registra tamanho;
6. cria telemetry;
7. devolve `DeliveryReceipt`.

**Importante:** o código atual não cria automaticamente um ZIP nessa função. O resultado é um recibo local com a lista de artefatos e hashes.

Portanto não é correto documentar `/deliver` como "gera ZIP" sem outra etapa externa de compactação.

## Passo 17 — retorno à Thread

O ViewModel recebe o resultado e adiciona um `ThreadEvent`/resumo.

A Thread mostra:

- sucesso/falha;
- terminal;
- diff;
- approval;
- relatório;
- evidências;
- delivery summary.

## Passo 18 — resultado final do cenário

O estado final pode ser:

```text
APK criado
+ código no workspace
+ testes executados
+ evidências
+ hashes dos artefatos
+ recibo local
```

Se o usuário quiser um ZIP físico, uma etapa de empacotamento precisa existir explicitamente; o `DeliveryReceipt` sozinho não é ZIP.

Se o usuário quiser GitHub final, é necessário um caminho de Git que execute commit/push e possua credenciais/autorização adequadas. O `GitManager` tem essas operações, mas a UI principal não as expõe como botões hoje.

---

# PARTE D — ÁRVORE RESUMIDA DE PRODUÇÃO

```text
Android
│
├── Manifest
│   ├── BrainCodeActivity
│   └── BrainCodeExecutionService
│
├── UI
│   ├── ThreadScreen
│   │   ├── ThreadComposer
│   │   ├── Thread events
│   │   ├── approvals
│   │   └── resultados
│   │
│   └── SettingsScreen
│       ├── ApiKeysScreen
│       ├── PluginsScreen
│       ├── WorkspaceSettings
│       └── ToolchainSettings
│
├── SandboxViewModel
│   ├── sessions
│   ├── catalogs
│   ├── ApiKeyStore
│   ├── BrainApiGateway
│   ├── BrainIntegrationFacade
│   ├── SandboxPlatform
│   └── BrainSandboxController
│
├── BrainSandboxController
│   ├── FastIntentClassifier
│   ├── KeywordPlanner
│   │   └── KeywordFunctionSplitter
│   ├── Retrieval
│   ├── DurableJobRunner
│   └── BrainSandboxExecutionBridge
│       └── CicloExecucaoPlano
│           ├── PolicyBroker
│           ├── CapabilityDiscovery
│           ├── Dispatcher
│           └── ActionGateway
│               └── BrainActionExecutor
│                   └── Sandbox
│                       └── RootFS
│
├── BrainIntegrationFacade
│   ├── SkillsCatalogoLoader
│   ├── ExplorerSeedLoader
│   ├── SkillRegistry
│   ├── Discovery
│   └── ObservableDelivery
│
└── Resultado
    ├── EventStore/events
    ├── ThreadEvent
    ├── DeliveryReceipt
    └── artefatos locais
```

---

# PARTE E — O QUE A AUDITORIA PROVOU E O QUE NÃO PROVOU

## Provado por código

- launcher real;
- serviço foreground registrado/iniciado;
- Thread como UI principal;
- Settings como UI de configuração;
- chat/composer chegando ao ViewModel;
- controller real do Brain;
- classifier/planner/splitter ligados;
- durable jobs ligados;
- capability registry/discovery/dispatcher/gateway ligados;
- Policy antes da execução;
- Skills catalog ligado;
- Explorer seed ligado;
- plugins ligados ao capability provider;
- toolchains declaradas e gerenciadas;
- RootFS em três camadas;
- delivery local com hashes.

## Não provado somente pela leitura do código

- que cada etapa executa corretamente em um aparelho específico;
- que cada binário está fisicamente presente no tarball sem extração do asset;
- que cada capability possui E2E observável em dispositivo;
- que `/deliver` produz ZIP — o código atual mostra que produz receipt;
- que Git commit/push está exposto na UI principal — o `GitManager` possui as funções, mas a UI atual não as chama diretamente.

## Limitação de artefatos RootFS

Os releases binários têm mais de 1 GB cada. A auditoria atual conseguiu validar metadata, manifests, Dockerfiles, scripts e hashes publicados, mas não extraiu os tarballs binários dentro desta interface. Essa limitação fica registrada para não transformar documentação de build em falsa prova de conteúdo físico.

---

# CONCLUSÃO DA FASE 8

A árvore atual é coerente com o desenho:

```text
UI
 ↓
ViewModel
 ↓
Brain/controller
 ↓
Intent/Planner
 ↓
Plan
 ↓
Capability Discovery
 ↓
Policy
 ↓
Dispatcher
 ↓
ActionGateway
 ↓
Sandbox
 ↓
RootFS
 ↓
processo/artefato
 ↓
Evidence/Event/Delivery
 ↓
UI
```

O principal problema concreto encontrado pela auditoria de oito fases não é uma ausência total de arquitetura: é **sobra de código/UI antiga e algumas APIs internas que existem além do que a UI principal atualmente chama**.

A limpeza deve ser feita depois desta auditoria, separando:

1. remover cluster de UI órfão;
2. decidir o destino das APIs Git que não possuem caller atual;
3. manter RootFS homologado imutável;
4. adicionar E2E real para provar o caminho inteiro;
5. somente depois considerar remoção de código.

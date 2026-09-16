# Auditoria BrainCode — Fase 6: UI e cobertura do runtime

**Snapshot:** `ffed42a4fd83095797dfd6bf53213a82b1c8788d`

## 1. Objetivo

Verificar o caminho real da interface Android: o que o usuário consegue pedir, para qual método isso vai e quais capacidades do runtime ficam sem entrada pela UI atual.

A regra é simples: uma classe do runtime não é considerada "usada pela UI" apenas porque aparece na documentação. Deve existir um callback, comando, ViewModel, controller ou entrypoint real.

## 2. Entrada do aplicativo

```text
Manifest
  ↓
BrainCodeActivity
  ↓
MainActivity.onCreate
  ↓
SandboxMobileApp
  ├── ThreadScreen
  └── SettingsScreen
```

`BrainCodeActivity` é o launcher e também inicia `BrainCodeExecutionService`.

## 3. Tela principal: Thread

A Thread é a interface principal de produção.

Ela expõe:

- sidebar de tarefas/sessões;
- busca dentro da thread;
- diagnóstico pelo chip de status;
- limpar sessão;
- configurações;
- mensagens do usuário;
- mensagens do agente;
- terminal/resultados;
- approvals;
- relatórios;
- diff;
- composer de tarefas;
- autocomplete de slash commands;
- cancelamento durante execução.

## 4. Chat normal

O botão `Enviar` chama:

```text
ThreadScreen
  ↓
ThreadComposer
  ↓
viewModel.submitThreadInput()
```

O composer também possui um caminho direto para `sendChatMessage()` na UI auxiliar antiga, mas essa tela não é o fluxo principal atual. O caminho atual do composer passa pelo dispatcher de entrada do ViewModel.

## 5. Slash commands operacionais

O próprio código declara como operações reais:

```text
/run
/testlab
/security
/git status
/git diff
/workflow
/approval demo
/workspace new
/sqlite start
/sqlite stop
/discovery
/deliver
```

Esses comandos têm prioridade sobre o catálogo genérico de 344 prompts.

Portanto várias funções que parecem não ter botão visual continuam alcançáveis pela UI porque o composer é uma interface de comando.

## 6. Catálogo de comandos

`ComandosCatalogoLoader` carrega `assets/comandos_catalogo.json`.

As entradas do catálogo são prompts especializados, não comandos shell. O código de `ComandosCatalogo.kt` deixa isso explícito.

Fluxo:

```text
JSON
 ↓
ComandosCatalogoLoader
 ↓
SandboxViewModel.catalogoComandos
 ↓
catalogoPorComando
 ↓
sugestoesDeComando
 ↓
ThreadComposer
 ↓
usuário escolhe/digita
 ↓
Brain
```

## 7. Settings

`SettingsScreen` possui quatro seções reais:

### Provedores

`ApiKeysScreen` mostra providers do `ai_api_catalog.json`, permite inserir/salvar/testar chave e abrir cadastro/documentação.

### Extensões

`PluginsScreen` é reutilizada para Tools e Plugins. Permite busca, filtro, instalação, remoção e rollback de componentes quando o Sandbox está pronto.

### Workspace

Permite atualizar projetos, selecionar projeto e atualizar catálogos do Brain.

### Toolchains

Lista `BuiltInToolchains.all`, mostra status e permite instalar cada perfil.

Perfis atuais:

- Android SDK/NDK;
- Java;
- Python;
- Node.js;
- C/C++;
- Rust;
- Go.

## 8. Tudo do runtime é pedido pela UI?

**Não.** A auditoria encontrou três níveis:

### A. Diretamente exposto

- chat;
- sessões;
- settings;
- API keys;
- plugins/tools;
- workspace;
- toolchains;
- busca de thread;
- diagnóstico;
- cancelamento;
- approvals;
- resultados/diffs.

### B. Exposto indiretamente pelo composer

- TestLab;
- security assessment;
- Git status/diff;
- workflow;
- approval demo;
- workspace new;
- SQLite start/stop;
- discovery;
- run;
- delivery.

### C. Runtime sem comando de usuário dedicado

Há infraestrutura interna que não precisa de botão próprio: registry, policy, gateway, capability discovery, memória, eventos, planner, dispatcher, job runner e componentes de segurança. Esses componentes são chamados pelo fluxo e não devem ser confundidos com features que precisam aparecer na UI.

## 9. UI órfã

`ToolsAndApiScreen`, `SandboxValidationScreen` e `OperationsScreen` continuam sem caller de produção.

A maior parte da funcionalidade delas, entretanto, já foi redistribuída:

- API keys → Settings/Provedores;
- plugins/tools → Settings/Extensões;
- toolchains → Settings/Toolchains;
- workspace → Settings/Workspace e slash commands;
- Brain workflow/discovery/delivery → slash commands;
- TestLab/security → slash commands.

Isso é forte evidência de que o cluster é sobra de uma UI anterior.

## 10. O que a UI ainda não comprova

A UI consegue iniciar o caminho, mas a simples presença do callback não comprova que toda ação foi executada com sucesso em dispositivo real.

Para fechamento funcional ainda é necessário E2E observável:

```text
toque/digitação
 → callback
 → ViewModel
 → controller/gateway
 → policy
 → sandbox/provider
 → resultado
 → evento persistido
 → card na Thread
```

## 11. Resultado da Fase 6

**UI principal:** integrada ao runtime atual.

**UI duplicada/antiga:** cluster órfão identificado.

**Capacidades do runtime:** nem todas possuem botão próprio; as operações de usuário relevantes são cobertas pelo composer e seus comandos operacionais.

**Próximo passo:** Fase 7 inventaria todas as ferramentas reais, separando binários do RootFS, plugins, capabilities e comandos da UI.

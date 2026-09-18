# BrainCode 2.3 — Plano de Implementação Pós-Validação

**Status:** DOCUMENTAÇÃO / NÃO IMPLEMENTAR AINDA  
**Versão:** 2.3  
**Data:** 2026-09-16  
**Repositório:** `Kyra2214/BrainCode`  
**Pré-condição:** somente iniciar depois da validação oficial e da consolidação do BrainCode atual.

---

## 0. Objetivo

A versão 2.3 não é uma reescrita do BrainCode e não deve ser aplicada enquanto o runtime atual não estiver oficialmente validado.

O objetivo é registrar, com antecedência, **o que será incorporado, de onde veio cada ideia, onde deverá entrar no BrainCode, quais contratos precisam existir, quais componentes atuais serão reutilizados e quais testes deverão provar a integração**.

A regra central é:

> **Primeiro provar o BrainCode atual. Depois incrementar.**

Nenhuma classe nova deve ser criada apenas para aumentar a arquitetura. Cada mudança da 2.3 precisa ter:

- fonte identificada;
- motivo técnico;
- ponto de integração definido;
- contrato claro;
- caller real;
- teste unitário;
- teste de integração quando aplicável;
- evidência de que o fluxo canônico realmente passa pelo componente.

---

# 1. Fontes das ideias

## 1.1 BrainCode 2.0 existente

**Fonte:** `BrainCode2.0.md` no próprio repositório.

O documento 2.0 já consolidou conceitos provenientes de vários projetos, especialmente:

- conhecimento persistente e relações;
- executor de recuperação;
- Action Gateway governado por Policy;
- skills estruturadas;
- Agent Registry;
- workflows/DAG;
- jobs duráveis;
- provider failover/cooldown.

A 2.3 deve **implementar somente depois da validação do que já está no HEAD** e deve evitar duplicar mecanismos que já existam.

---

## 1.2 CodexRouter

**Fonte principal:** `cesarfavero/codexrouter`.

O projeto apresenta uma arquitetura de gateway local capaz de gerenciar **múltiplas contas isoladas para um mesmo ponto de entrada/agente**, delegando autenticação ao cliente oficial e fazendo seleção/failover entre contas configuradas. O projeto utiliza `CODEX_HOME` separado por conta, catálogo de modelos, estado de uso/cooldown e failover entre contas configuradas. fileciteturn8file0L2-L10

A ideia que interessa ao BrainCode não é copiar o aplicativo desktop nem o código específico de Codex. É adaptar o princípio:

```text
AGENTE
  ↓
POOL DE EXECUÇÃO
  ↓
CONTAS / CREDENCIAIS ISOLADAS
  ↓
PROVIDER
  ↓
MODELO
```

O agente não deve precisar saber qual conta será usada.

O CodexRouter também demonstra uma separação importante: cada identidade recebe um ambiente isolado, enquanto o cliente oficial continua responsável pelo login, persistência e refresh de credenciais. fileciteturn8file0L2-L10

**Licença:** CodexRouter declara licença MIT e informa que adapta arquitetura/UI de outro projeto também MIT; qualquer reutilização de código deve preservar as obrigações de licença, copyright e notices correspondentes. Para BrainCode, a preferência é **adotar conceitos e contratos, não copiar implementação sem necessidade**. fileciteturn8file0L2-L10

---

# 2. Ideia central da 2.3 — Account Pool

Este é o principal incremento trazido pela análise do CodexRouter.

## 2.1 Problema

Hoje o agente não deveria ficar acoplado a uma única identidade/provider. Se uma conta atingir limite, ficar indisponível ou entrar em cooldown, o agente deve continuar através de outra identidade autorizada, quando a política permitir.

## 2.2 Modelo proposto

```text
AgentDefinition
      ↓
ProviderPolicy
      ↓
AccountPool
      ├── Account A
      ├── Account B
      ├── Account C
      └── Local Provider
              ↓
          Model Catalog
              ↓
          Execution
```

O agente pede:

```text
execute(task)
```

E não:

```text
execute(task, account="conta_X")
```

A escolha da conta pertence ao Router/Policy.

---

# 3. Componentes novos previstos

## 3.1 AccountRegistry

**Responsabilidade:** registrar identidades disponíveis para execução.

```text
AccountRegistry
├── accountId
├── providerId
├── displayName
├── status
├── credentialRef
├── capabilities
├── priority
├── cooldown
└── metadata
```

### Onde implementar

Preferencialmente no módulo Brain/runtime que já controla providers, registry e configuração. Não colocar credenciais diretamente dentro de `Agent`.

### Não fazer

- senha em texto puro;
- token em log;
- token dentro de `AgentDefinition`;
- dependência direta do agente em uma conta específica.

---

## 3.2 ProviderRegistry

Caso o BrainCode atual já possua registry de providers, **estender o existente** em vez de criar outro.

Responsabilidades:

- descobrir providers;
- associar contas;
- informar modelos disponíveis;
- expor capacidades;
- informar estado de saúde;
- fornecer adaptadores de execução.

Fluxo:

```text
ProviderRegistry
      ↓
AccountRegistry
      ↓
CapabilityDiscovery
      ↓
Model Catalog
```

---

## 3.3 Capability Catalog

A 2.3 deve aproveitar `CapabilityDiscovery` já existente.

O catálogo precisa responder algo equivalente a:

```text
qual conta pode executar esta capacidade?
qual modelo pode executar esta capacidade?
qual provider está saudável?
qual política permite seu uso?
```

Não criar um segundo mecanismo paralelo de descoberta.

---

## 3.4 AccountPool

É o coração da mudança.

```text
AccountPool
├── poolId
├── capability
├── members[]
├── selectionPolicy
├── fallbackPolicy
├── retryPolicy
└── healthPolicy
```

Exemplo conceitual:

```text
pool = coding

members:
  account-a priority=100
  account-b priority=90
  account-c priority=80
  local priority=50

fallback = enabled
cooldownOnRateLimit = enabled
maxAttempts = 3
```

O pool não deve misturar quotas de assinaturas. Ele apenas seleciona entre identidades explicitamente configuradas e autorizadas.

---

# 4. Account Router

## Responsabilidade

Selecionar a identidade de execução correta antes do TaskEngine/Provider executar uma tarefa.

Fluxo:

```text
ExecutionPlan
      ↓
Policy
      ↓
Capability
      ↓
AccountRouter
      ↓
AccountPool
      ↓
Account saudável
      ↓
Provider
      ↓
Model
      ↓
TaskEngine
```

## Regras de seleção

1. respeitar Policy;
2. respeitar capability;
3. respeitar modelo solicitado;
4. excluir conta em cooldown;
5. excluir conta indisponível;
6. respeitar prioridade;
7. selecionar somente identidade autorizada;
8. registrar a decisão;
9. permitir fallback somente quando a política permitir;
10. nunca expor credencial ao agente.

---

# 5. Health / Quota / Cooldown

O CodexRouter demonstra o valor de acompanhar estado de uso, janelas de limite, reset e cooldown localmente e realizar failover quando a conta não puder continuar. fileciteturn8file0L2-L10

No BrainCode isso deve virar um mecanismo genérico, sem depender de uma API específica.

```text
AccountHealth
├── available
├── rateLimited
├── authenticationError
├── providerError
├── quotaLow
├── quotaExhausted
├── cooldownUntil
└── lastFailure
```

### Classificação de falhas

```text
SUCCESS
RATE_LIMIT
AUTH_FAILURE
TEMPORARY_PROVIDER_FAILURE
PERMANENT_PROVIDER_FAILURE
POLICY_DENIED
INVALID_REQUEST
TIMEOUT
UNKNOWN
```

Somente falhas classificadas como recuperáveis devem provocar fallback automático.

---

# 6. Credential Isolation

A ideia de isolamento por conta do CodexRouter será adaptada para o modelo local do BrainCode. O projeto de origem usa um `CODEX_HOME` diferente para cada conta, mantendo os perfis separados. fileciteturn8file0L2-L10

No BrainCode:

```text
~/.braincode/
  accounts/
    account-a/
    account-b/
    account-c/
  agents/
  pools/
  registry/
```

O formato final dependerá do storage já utilizado pelo BrainCode.

### Regra

**Isolamento lógico obrigatório; isolamento físico quando o provider/runtime exigir.**

O agente conhece apenas `accountId` lógico ou uma referência opaca de execução. O segredo real permanece sob o Credential/Provider layer.

---

# 7. AgentDefinition

A 2.3 deve aproveitar o conceito já previsto em `BrainCode2.0.md` de Agent Registry.

```text
AgentDefinition
├── id
├── role
├── objective
├── allowedSkills
├── allowedCapabilities
├── memoryScope
├── providerPolicy
├── outputContract
└── validationPolicy
```

### Nova regra 2.3

Adicionar:

```text
providerPolicy
  ↓
accountPool / capabilityPool
```

Exemplo:

```text
agent = coder
providerPolicy = coding-pool
```

O `coder` não recebe:

```text
account = X
```

Recebe:

```text
pool = coding
```

---

# 8. Integração com as classes existentes

Este ponto é obrigatório porque a auditoria atual identificou risco de componentes novos existirem sem serem chamados pelo fluxo real.

A 2.3 **não deve repetir esse erro**.

O fluxo canônico esperado deverá ser comprovado por testes:

```text
Android / CLI / API
        ↓
Conversation
        ↓
Policy
        ↓
Planner
        ↓
AgentRegistry
        ↓
AgentDefinition
        ↓
CapabilityDiscovery
        ↓
AccountRouter
        ↓
AccountPool
        ↓
ProviderRegistry
        ↓
TaskEngine
        ↓
DurableJobRunner (quando necessário)
        ↓
Provider / Sandbox
        ↓
Result
        ↓
Critic
        ↓
Evidence / Memory
```

Se uma classe existir mas não aparecer nesse fluxo ou em uma rota deliberadamente documentada, ela deve ser considerada **não integrada**.

---

# 9. Failover sem duplicação de execução

Este é um ponto crítico.

Não basta detectar erro e tentar outra conta. O BrainCode precisa saber se a operação pode ser repetida com segurança.

```text
request
 ↓
attempt A
 ↓
resultado?
 ├─ sucesso → fim
 ├─ erro recuperável → verificar idempotência
 └─ erro não recuperável → fim
          ↓
      attempt B
```

Cada tentativa deve possuir:

```text
executionId
attemptId
accountId
providerId
modelId
startedAt
finishedAt
failureClass
```

Para ações externas com efeitos colaterais, fallback automático deve ser bloqueado ou exigir confirmação/idempotency key apropriada.

---

# 10. Catálogo de modelos

O CodexRouter sincroniza o catálogo de modelos visíveis da conta e usa o catálogo para selecionar um modelo válido por trás do gateway. fileciteturn8file0L2-L10

No BrainCode:

```text
Account
  ↓
Provider discovery
  ↓
Model Catalog
  ↓
Capability mapping
```

Exemplo:

```text
Account A
  model-1 → coding, reasoning
  model-2 → fast

Account B
  model-1 → coding
  model-3 → reasoning

Local
  model-local → coding, offline
```

O Router nunca deve selecionar um modelo apenas porque ele está escrito em configuração. Ele deve confirmar disponibilidade/capacidade quando o provider permitir descoberta.

---

# 11. Local-first e custo

A 2.3 mantém a filosofia do BrainCode:

```text
local capability
      ↓
free/authorized provider
      ↓
configured external provider
```

A existência de múltiplas contas **não deve virar mecanismo para contornar cobrança, limites contratuais ou controles de um serviço**. O pool opera apenas sobre contas/providers que o usuário está autorizado a utilizar.

O BrainCode continua sem depender de serviço remoto próprio para fazer o roteamento.

---

# 12. Doctor / Diagnóstico

O padrão de diagnóstico do CodexRouter também é útil: o projeto possui comandos para status/doctor e recuperação da integração. fileciteturn8file0L2-L10

BrainCode 2.3 deve ter diagnóstico equivalente no nível adequado ao aplicativo:

```text
braincode doctor
```

ou uma tela/comando interno que verifique:

- registry;
- providers;
- accounts;
- credentials refs;
- capabilities;
- pools;
- modelos;
- sandbox;
- jobs;
- connectivity;
- policy;
- logs seguros.

Resultado esperado:

```text
PASS AccountRegistry
PASS ProviderRegistry
PASS CapabilityDiscovery
PASS AccountPool
PASS CredentialIsolation
PASS Router
PASS Policy
PASS Sandbox
WARN Provider B cooldown
FAIL Account C authentication
```

Nunca exibir segredo no diagnóstico.

---

# 13. Observabilidade

Toda seleção deve ser explicável sem revelar credenciais.

Exemplo de evento:

```json
{
  "event": "ACCOUNT_SELECTED",
  "executionId": "...",
  "pool": "coding",
  "accountId": "account-b",
  "providerId": "provider-x",
  "reason": "priority_after_account_a_cooldown"
}
```

Também registrar:

- tentativa;
- fallback;
- cooldown;
- sucesso;
- erro classificado;
- duração;
- modelo escolhido;
- resultado da Policy.

Não registrar:

- access token;
- refresh token;
- senha;
- cookies;
- conteúdo integral de `auth.json`;
- headers secretos.

---

# 14. Testes obrigatórios

## 14.1 Unitários

### AccountRegistry

- cadastrar conta;
- remover conta;
- atualizar estado;
- persistência;
- isolamento de referência de credencial.

### AccountPool

- seleção por prioridade;
- exclusão de cooldown;
- exclusão de conta indisponível;
- pool vazio;
- múltiplas contas;
- provider local;
- capability incompatível.

### Router

- seleção correta;
- fallback;
- falha não recuperável;
- limite de tentativas;
- policy deny;
- ausência de credencial;
- modelo indisponível.

### Health

- rate limit → cooldown;
- timeout → classificação;
- auth failure → sem retry cego;
- recuperação após cooldown.

---

# 15. Teste de integração real

O teste principal da 2.3 deve provar a ideia completa:

```text
Agent
 ↓
Pool
 ↓
Account A
 ↓
falha controlada
 ↓
Account B
 ↓
Provider
 ↓
resultado
```

O teste deve provar que:

1. o agente não escolheu a conta diretamente;
2. o Router escolheu A;
3. A falhou com erro classificável;
4. Policy permitiu fallback;
5. Router selecionou B;
6. B executou;
7. o resultado foi devolvido ao agente;
8. o evento de fallback foi registrado;
9. nenhuma credencial apareceu nos logs.

---

# 16. Teste E2E Android

Depois de a integração interna estar aprovada:

```text
Android UI
 ↓
chat
 ↓
BrainApiGateway
 ↓
Brain runtime
 ↓
Agent
 ↓
AccountRouter
 ↓
Provider
 ↓
resultado
 ↓
Android UI
```

Este E2E é particularmente importante porque a documentação atual do BrainCode já identifica a necessidade de provar o caminho Android → gateway.

A 2.3 não deve ser considerada integrada enquanto esse caminho não estiver demonstrado por execução real.

---

# 17. Compatibilidade com DurableJobRunner

Não transformar todo request em Job.

```text
curto/síncrono
 → execução direta

longo/assíncrono
 → DurableJobRunner
```

Quando houver job durável, o `accountId`, `providerId`, `modelId` e estado da tentativa precisam ser persistidos de forma segura para que uma reinicialização não destrua o estado da execução.

---

# 18. Compatibilidade com Sandbox

Account Pool não substitui Sandbox.

A separação correta continua sendo:

```text
Policy
 ↓
Capability
 ↓
Action
 ↓
Sandbox
```

O provider escolhido pode executar uma tarefa dentro do Sandbox, mas não recebe autorização adicional só porque foi escolhido pelo Router.

---

# 19. Compatibilidade com Critic

O Critic continua sendo pós-execução.

```text
Provider
 ↓
resultado
 ↓
Critic
 ↓
validado / rejeitado / revisão
```

Uma resposta obtida após failover não recebe confiança maior ou menor apenas por ter vindo de outra conta.

A avaliação continua baseada em evidência, contrato e política.

---

# 20. O que NÃO será levado do CodexRouter

Não implementar no BrainCode apenas por existir no projeto fonte:

- Electron;
- React/Vite desktop launcher;
- menu bar macOS;
- integração específica com Codex;
- catálogo com nomes específicos de modelos;
- `CODEX_HOME` como requisito universal;
- endpoints específicos do Codex;
- UI de desktop;
- login proprietário;
- automação de navegador;
- importação de cookies;
- mecanismo de assinatura/quota específico de outro serviço.

Apenas os padrões arquiteturais compatíveis com o BrainCode serão adaptados.

---

# 21. Ordem de implementação

A implementação futura deve ocorrer nesta ordem:

### Fase 0 — Gate

```text
VALIDAÇÃO OFICIAL ATUAL
        ↓
PASS
        ↓
congelar baseline
```

Se falhar: **não iniciar 2.3**.

### Fase 1 — Contratos

Criar/validar:

- Account;
- AccountRegistry;
- Provider;
- ProviderRegistry;
- AccountPool;
- AccountHealth;
- SelectionPolicy;
- ExecutionAttempt.

### Fase 2 — Registry

Integrar registry de contas com provider/capability existente.

### Fase 3 — Router

Integrar AccountRouter ao fluxo canônico.

### Fase 4 — Failover

Implementar classificação de erro, cooldown e retry seguro.

### Fase 5 — Observabilidade

Adicionar eventos e diagnóstico sem segredos.

### Fase 6 — E2E

Provar Brain runtime e posteriormente Android → gateway.

### Fase 7 — Consolidação

Remover classes/abstrações duplicadas e atualizar documentação.

---

# 22. Critério de aceite da 2.3

A 2.3 somente poderá ser marcada como concluída se todos forem verdadeiros:

- [ ] baseline atual aprovado;
- [ ] AccountRegistry integrado;
- [ ] ProviderRegistry reutilizado/estendido;
- [ ] CapabilityDiscovery integrado;
- [ ] AccountPool integrado;
- [ ] Agent não escolhe credencial diretamente;
- [ ] Router seleciona conta;
- [ ] Policy continua sendo autoridade;
- [ ] fallback possui classificação de erro;
- [ ] cooldown funciona;
- [ ] retries possuem limite;
- [ ] ações não idempotentes não sofrem retry cego;
- [ ] credenciais ficam isoladas;
- [ ] logs não vazam segredos;
- [ ] catálogo de modelos é validado quando possível;
- [ ] DurableJobRunner preserva estado quando necessário;
- [ ] Critic continua no fluxo;
- [ ] testes unitários passam;
- [ ] integração multi-conta passa;
- [ ] E2E passa;
- [ ] Android → gateway passa quando aplicável;
- [ ] build/release passa;
- [ ] nenhuma classe nova ficou órfã.

---

# 23. Relação com o BrainCode 2.0

O `BrainCode2.0.md` continua sendo o documento amplo de consolidação arquitetural.

Este `IMPLEMENTACAO_2.3.md` é deliberadamente diferente:

```text
BrainCode2.0.md
  = O QUE VALE A PENA ADOTAR

IMPLEMENTACAO_2.3.md
  = COMO / ONDE / EM QUE ORDEM IMPLEMENTAR
```

A 2.3 também incorpora o novo aprendizado do CodexRouter:

```text
Agent
 ↓
Policy / Capability
 ↓
AccountPool
 ↓
Provider
 ↓
Model
```

com contas isoladas, health/cooldown, failover e observabilidade.

---

# 24. Regra final de governança

**NÃO IMPLEMENTAR AGORA.**

Este documento é um plano de incremento posterior.

O estado desejado é:

```text
                 AGORA
                   │
                   ▼
        ┌─────────────────────┐
        │ Consolidar BrainCode│
        └──────────┬──────────┘
                   ▼
        ┌─────────────────────┐
        │ Validação oficial   │
        └──────────┬──────────┘
                   │
             PASS? │
              ┌────┴────┐
             NÃO       SIM
              │          │
              ▼          ▼
          corrigir    congelar
                       baseline
                          │
                          ▼
                    IMPLEMENTAÇÃO
                         2.3
                          │
             ┌────────────┼────────────┐
             ▼            ▼            ▼
         Accounts       Router       Failover
             │            │            │
             └────────────┼────────────┘
                          ▼
                    TESTES / E2E
                          │
                          ▼
                       RELEASE
```

A regra é simples: **não adicionar complexidade antes de provar que a base funciona.**

---

## Fontes principais

1. `Kyra2214/BrainCode` — `BrainCode2.0.md`, arquitetura e gaps já documentados no próprio projeto. fileciteturn7file0L2-L2
2. `cesarfavero/codexrouter` — README, arquitetura de gateway, contas isoladas, catálogo, usage/cooldown, failover, E2E, segurança e licença MIT. fileciteturn8file0L2-L10

**Observação de licenciamento:** a implementação do BrainCode deve preferir reprodução de conceitos/contratos. Se algum trecho de código do projeto fonte for reutilizado futuramente, revisar e preservar integralmente as obrigações da licença e os notices de terceiros aplicáveis.

---

# 25. Novo aprendizado — Locally Uncensored: Runtime sem Docker

**Fonte:** `PurpleDoubleD/locally-uncensored`.

A análise do projeto mostrou um padrão diretamente relevante para o BrainCode: o aplicativo desktop não depende de Docker para executar seus runtimes locais. Em vez de colocar a execução em uma cadeia obrigatória de containers, ele pode instalar/gerenciar runtimes e processos locais. Docker fica como opção para infraestrutura externa/remota quando necessário.

O aprendizado não é copiar o aplicativo. É tratar o **RootFS como runtime gerenciado**.

## 25.1 Ciclo de vida do RootFS

O RootFS não deve ser considerado apenas um arquivo baixado e extraído:

```text
DISCOVERED → DOWNLOADING → VERIFIED → PROVISIONING
                         ↓
                 DEPENDENCIES_CHECK
                         ↓
                    HEALTH_CHECK
                         ↓
                       READY
```

Estados de falha devem ser explícitos:

```text
DOWNLOAD_FAILED
CHECKSUM_FAILED
PROVISION_FAILED
DEPENDENCY_MISSING
HEALTHCHECK_FAILED
RUNTIME_CRASHED
REPAIR_REQUIRED
```

**Regra:** arquivo existente não significa runtime pronto.

## 25.2 Manifesto de runtime

Cada RootFS deve possuir manifesto verificável:

```text
RootFSManifest
├── rootfsId
├── version
├── architecture
├── source
├── sha256
├── size
├── entrypoint
├── environment
├── requiredBinaries
├── requiredVersions
├── capabilities
└── healthChecks
```

Exemplo:

```text
required:
  bash
  java
  python
  node

health:
  /bin/bash --version
  java --version
  python --version
  node --version
```

Isso é especialmente importante para o problema já encontrado no BrainCode em que os RootFS foram baixados, mas uma dependência necessária não estava disponível.

## 25.3 DependencyVerifier

Adicionar/verificar um contrato explícito para:

```text
RootFS
 ↓
DependencyVerifier
 ├── binary exists
 ├── executable
 ├── version valid
 ├── PATH valid
 ├── permissions valid
 ├── runtime callable
 └── architecture compatible
```

Resultado esperado:

```text
PASS bash
PASS java
PASS python
PASS node
FAIL gradle
```

O erro deve identificar a dependência, RootFS e caminhos pesquisados, em vez de apenas retornar `command not found`.

## 25.4 ProcessSupervisor

O runtime deve possuir supervisão de processo, reutilizando os mecanismos de execução existentes quando possível:

```text
Runtime
 ↓
ProcessSupervisor
 ├── spawn
 ├── stdout
 ├── stderr
 ├── exit code
 ├── timeout
 ├── termination reason
 └── lifecycle state
```

Estados:

```text
STARTING / RUNNING / STOPPING / STOPPED / FAILED / CRASHED / RESTARTING
```

Não criar um segundo executor paralelo ao `TaskEngine`/executor já existente.

## 25.5 Health check real

`process alive` não basta. O health check deve validar a capacidade real:

```text
runtime alive
+ filesystem accessible
+ required binaries available
+ executable
+ expected version
+ basic command succeeds
```

O teste deve ocorrer **dentro do RootFS**, pelo mesmo mecanismo que o agente utilizará posteriormente.

## 25.6 Restart, Repair e Reinstall

Separar claramente as operações:

```text
restart  = processo morreu, instalação íntegra
repair   = instalação existente inconsistente
reinstall = instalação não recuperável
```

Fluxo de recuperação:

```text
CRASH → RESTART → HEALTH CHECK → READY

DEPENDENCY_MISSING → REPAIR → VERIFY → READY

CORRUPTED → REINSTALL → VERIFY → PROVISION → HEALTH → READY
```

Nenhum restart deve virar loop infinito.

## 25.7 Retry com limite e backoff

```text
attempt 1 → failure → backoff
attempt 2 → failure → backoff
attempt 3 → failure → FAILED / REPAIR_REQUIRED
```

Número de tentativas e backoff devem ser configuráveis. Não usar `while true: restart()`.

## 25.8 Managed Runtime vs External Runtime

O BrainCode deve distinguir:

```text
Managed Runtime
  instala / configura / inicia / monitora / repara

External Runtime
  já existe fora do BrainCode
  BrainCode descobre / verifica / utiliza
```

Exemplos:

```text
RootFS local → managed
Ollama local externo → external
Provider remoto → external
```

Isso evita assumir controle de processos que pertencem a outra camada.

## 25.9 Install State Machine e instalação atômica

A instalação precisa ser persistente e recuperável:

```text
NOT_INSTALLED
 → DOWNLOAD_REQUESTED
 → DOWNLOADING
 → DOWNLOADED
 → CHECKSUM_VERIFIED
 → EXTRACTING
 → PROVISIONED
 → DEPENDENCIES_VERIFIED
 → HEALTHCHECK
 → READY
```

Artefatos temporários não devem substituir uma versão funcional:

```text
download.tmp → verify → promote → active version
```

Quando houver versões múltiplas, a troca de `current` só ocorre depois de checksum + provisionamento + dependências + health check. Isso permite rollback.

## 25.10 Doctor / diagnóstico

Adicionar ao plano um diagnóstico de runtime:

```text
RuntimeDoctor
├── installation
├── manifest
├── checksum
├── filesystem
├── permissions
├── PATH
├── dependencies
├── process
├── health
└── last failure
```

Exemplo:

```text
PASS RootFS downloaded
PASS SHA-256
PASS filesystem
PASS bash
FAIL java
PASS python
WARN node optional
```

O diagnóstico deve mostrar a camada exata da falha e nunca exibir segredos.

## 25.11 Portas e processos

Para runtimes que expõem servidor local:

```text
Runtime Manager → PortAllocator → start → health endpoint → READY
```

A instância deve registrar `runtimeId`, `pid`, `port`, `startedAt`, `state` e `health`. Não assumir porta fixa disponível.

## 25.12 Os 3 RootFS devem ter estados independentes

Aplicar o ciclo individualmente:

```text
RootFS A → Verify → Dependencies → Health → READY
RootFS B → Verify → Dependencies → Health → READY
RootFS C → Verify → Dependencies → Health → READY
```

Um RootFS saudável não pode mascarar a falha de outro. O estado global deve ser derivado:

```text
ALL_READY / PARTIAL_READY / DEGRADED / FAILED
```

## 25.13 Integração com CapabilityDiscovery

Capacidade efetiva deve vir do estado real do runtime, não somente da configuração:

```text
RootFS Health
      ↓
DependencyVerifier
      ↓
CapabilityDiscovery
      ↓
Planner / Router
```

Se `java` falhar, `JAVA` deve deixar de ser uma capacidade efetiva daquele RootFS, sem necessariamente invalidar Python, Node etc.

## 25.14 Integração com Agent Registry

O agente só pode selecionar runtime `READY` para a capacidade exigida:

```text
Agent
 ↓
required capability = JAVA
 ↓
CapabilityDiscovery
 ↓
READY candidates
 ↓
Execution
```

Se nenhum runtime for capaz, retornar um erro semântico como `NO_CAPABLE_RUNTIME`, e não apenas `command not found`.

## 25.15 Regra de arquitetura

Não criar uma camada específica para copiar o Locally Uncensored. Estender apenas os contratos necessários:

```text
RootFSManager
RuntimeManager
Manifest
DependencyVerifier
ProcessSupervisor
HealthChecker
RuntimeDoctor
```

Reutilizar, quando já existentes:

```text
CapabilityDiscovery
TaskEngine
Sandbox
DurableJobRunner
AgentRegistry
Policy
```

Objetivo final:

```text
BrainCode
   ↓
RootFS Manager
   ↓
Runtime Manager
   ↓
Provision / Verify / Health
   ↓
READY
   ↓
CapabilityDiscovery
   ↓
Agent / TaskEngine
   ↓
Sandbox
   ↓
Execution
```

## 25.16 Critérios adicionais de aceite

- [ ] RootFS possui ciclo de vida explícito.
- [ ] Manifesto e SHA-256 são verificados.
- [ ] Dependências obrigatórias são verificadas dentro do RootFS.
- [ ] PATH, permissões e executabilidade são validados.
- [ ] Health check testa a capacidade real.
- [ ] Processos são supervisionados.
- [ ] Crash não gera loop infinito.
- [ ] Retry possui limite/backoff.
- [ ] Restart, repair e reinstall são operações distintas.
- [ ] Instalação interrompida é recuperável.
- [ ] Versão funcional não é substituída antes da validação da nova.
- [ ] Rollback é possível quando aplicável.
- [ ] Managed e External Runtime são distinguidos.
- [ ] Cada um dos 3 RootFS possui estado próprio.
- [ ] CapabilityDiscovery usa o estado real do runtime.
- [ ] Agent Registry nunca seleciona runtime não saudável.
- [ ] Doctor identifica a camada exata da falha.
- [ ] Nenhum segredo aparece nos logs.

**Conclusão:** o maior ganho para o BrainCode é transformar o RootFS de simples artefato de filesystem em um **runtime gerenciado, verificável e autorrecuperável**. Isso ataca diretamente a classe de falhas em que o RootFS foi baixado, mas ainda não estava realmente pronto para executar uma ferramenta.


# 26. Agent Runtime Hardening — aprendizado do Fable 5.1 / Claude Code

A análise do material público que circula como prompt do Fable 5.1 e da arquitetura pública do Claude Code reforça uma conclusão importante: confiabilidade de agente não pode depender somente do modelo. O sistema precisa impor contratos ao redor do ciclo modelo → capability → resultado. O BrainCode já possui Policy, CapabilityDiscovery, SkillRegistry, AgentRegistry, Sandbox, EventStore, Planner, Critic e ExecutionCoordinator; esta seção consolida esses componentes em vez de criar um segundo framework.

## 26.1 Agent bounded

Toda missão deve possuir objetivo, capabilities requeridas, limite de chamadas, deadline, workspace/runtime permitido e autorização de Policy. O Agent não pode inventar capability, ampliar permissões ou continuar indefinidamente.

Já aplicado em BoundAgent.kt: maxCapabilityCalls, deadlineEpochMillis, parada no primeiro erro, evidência positiva obrigatória, captura de exceções e contagem de chamadas.
Commit: 2d39c1ced8b8f8d59a14cfda83353b918f3fafd8.

## 26.2 Evidência é contrato de conclusão

Não usar chamada de ferramenta = sucesso. O fluxo é: Mission → Capability calls → Evidence → Evidence OK → todas as capabilities satisfeitas → SUCCESS.

Uma capability que falha encerra a missão. Foram adicionados testes cobrindo falha terminal, limite de chamadas e deadline expirado.

## 26.3 Preflight obrigatório

Antes da execução: Mission → AgentRegistry → Capability check → Policy → Runtime health → Workspace scope → Execution.

O diagnóstico deve registrar agent, mission, capabilities, runtime, workspace, policy, budget e deadline. Falha em requisito obrigatório impede execução parcial.

## 26.4 Resultado tipado

Evoluir AgentEvidence para um contrato equivalente a CapabilityResult com status, output, evidence, error, retryable, sideEffects e provenance. Estados esperados: SUCCESS, FAILED_RETRYABLE, FAILED_TERMINAL, DENIED, TIMEOUT e CANCELLED.

## 26.5 Stop conditions

Toda missão deve terminar por SUCCESS, FAILURE, DENIED, WAITING_APPROVAL, TIMEOUT, BUDGET_EXCEEDED ou CANCELLED. O Agent não decide sozinho quando continuar.

## 26.6 Loop observável

Registrar MissionCreated, PreflightStarted, PreflightPassed/Failed, CapabilitySelected, ToolStarted, ToolCompleted/Failed, EvidenceRecorded, CorrectionRequested, RetryScheduled e MissionCompleted/Failed. Reutilizar o EventStore existente.

## 26.7 Correção diferente de repetição

Failure → classify error → retryable? → correction/retry policy → bounded retry. O CorrectionRequested existente deve passar a alimentar contexto real de correção, e não somente gerar log.

## 26.8 Subagents controlados

Quando houver subagents reais: Parent → Subagent com session, policy, workspace, capabilities, budget e deadline próprios. Exigir limite de profundidade, fan-out, orçamento, isolamento e canal de retorno com provenance. A literatura pública sobre Claude Code destaca delegação a subagents, isolamento por worktree e múltiplas camadas de gerenciamento de contexto. citeturn0academia17turn1search2

## 26.9 Contexto escopado

Separar Global Context, Session Context, Mission Context, Step Context, Tool Result e Memory. Resultado grande de ferramenta deve ser normalizado e resumido em evidência antes de entrar novamente no contexto.

## 26.10 Compaction preservando contrato

Quando houver compactação, nunca perder objetivo, restrições, autorização, capabilities, estado do plano, passos concluídos, evidências, erros pendentes, deadline, orçamento e workspace/runtime. A análise pública do Claude Code descreve uma pipeline de compactação em múltiplas camadas; o BrainCode deve adotar o princípio com implementação própria. citeturn0academia17

## 26.11 SkillDiscovery no caminho real

Task → Capability Discovery → Skill Discovery → Skill validation → Policy → Execution. O SkillRegistry atual já possui manifest, capabilities, permissões, trust, hash, assinatura e revogação; o próximo passo é garantir que Planner/Agent realmente consulte esse registry antes da execução.

## 26.12 Conteúdo externo é não confiável

Web, arquivo, API, MCP e saída de ferramenta são dados, não autoridade. Conteúdo externo nunca pode alterar Policy, autorização, missão, capabilities ou permissões. Uma página instruindo o Agent a ignorar suas regras deve ser tratada como conteúdo não confiável.

## 26.13 Hooks internos

Criar pontos de controle BeforeMission, BeforeCapability, BeforeTool, AfterTool, OnFailure, BeforeRetry, AfterMission, BeforeCompaction, AfterCompaction, SubagentStart e SubagentStop. Hooks observam/validam; não substituem Policy.

## 26.14 Workspace isolation

Agents paralelos devem ter raiz explícita e nenhum caminho pode escapar dela. O BrainCode já protege o workspace em AgentSandboxSession; essa mesma fronteira deve ser aplicada a futuros subagents. Worktree isolation é usado para reduzir conflitos entre agentes paralelos, e registros públicos mostram que vazamentos de diretório são uma classe real de bug; portanto o isolamento deve ser garantia do runtime, não apenas instrução no prompt. citeturn1search1turn1search11

## 26.15 Observabilidade por Agent

Registrar agentId, missionId, parentAgentId, sessionId, runtimeId, workspace, início/fim, chamadas, retries, capabilities usadas, arquivos alterados, erros, evidências e status final. Isso permite descobrir quando o Agent afirma sucesso mas o projeto continua quebrado.

## 26.16 Agent não declara sucesso sozinho

CodeAgent: output → build → tests → validation evidence → SUCCESS. ResearchAgent: output → sources → provenance → evidence validation → SUCCESS. A palavra sucesso retornada pelo modelo não é autoridade.

## 26.17 Capability continua sendo contrato

Capability.API pode usar API externa hoje e Brain nativo amanhã. Capability.SQL pode usar SQLite/PostgreSQL hoje e Brain nativo amanhã. Capability.NETWORK pode usar HTTP/DNS hoje e Brain nativo amanhã. O Agent solicita a capability; Router/Discovery escolhe a implementação disponível.

## 26.18 Ordem imediata

Já aplicado: AgentExecutionGuard, limite de chamadas, deadline, parada no primeiro erro, evidência positiva e testes unitários.

Próximos incrementos: 1) Preflight estruturado; 2) CapabilityResult tipado; 3) SkillDiscovery real no Planner → Agent; 4) eventos Before/After Tool; 5) taxonomy de erros + retryable; 6) context compaction preservando contrato; 7) subagent lifecycle com depth/fan-out/budget; 8) workspace isolation por subagent; 9) validator obrigatório por tipo de Agent; 10) E2E de missão completa com falha, retry, correção e sucesso.

## 26.19 Regra contra classes órfãs

Toda nova classe de Agent exige implementation + caller real + unit test + integration test + event trace + failure path. Criar o arquivo não conta como implementação. Isso combate diretamente o problema já encontrado no BrainCode de classes novas que existiam mas não eram chamadas pelo fluxo real.

## 26.20 Arquitetura alvo

User → Policy → Planner → Requirement/Context → Capability Discovery → Skill Discovery → Agent Selection → Preflight → Scoped Runtime → Tool/Capability → Evidence → Validator/Critic → Success? → Memory/EventStore → Result. Em caso de falha: classify error → correction/retry → bounded retry → failure ou success.

Objetivo: tirar do modelo as responsabilidades que precisam ser determinísticas. O modelo pode planejar e raciocinar; o BrainCode controla autorização, escopo, limites, estado, evidência, retry, isolamento e conclusão.

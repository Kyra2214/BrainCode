# Relatório completo da absorção de código e referências

**Projeto:** BrainCode  
**Rodada:** absorção orientada pelo Marco 2.6  
**Data:** 23 de setembro de 2026  
**Estado:** absorções e integração de workflows implementadas; gates locais verdes; CI/E2E remoto aguardando execução

## 1. Conclusão executiva

Não ficou material externo omitido do pacote final. O ZIP preserva o diretório completo `BrainCode-main` e os oito arquivos do diretório externo `absorcao-projeto3`.

Ficaram de fora somente integrações que o próprio plano classifica como etapas posteriores ou que exigiriam um gate de segurança e validação mais amplo. Entre elas estão a execução de recursos de Skills, a aprovação automática de permissões no `PolicyBroker`, a execução de hooks e scripts externos, o grader semântico, CI/E2E e testes em dispositivo. O marketplace pinado, o parser de workflows, o scheduler de metadata e o backup/restore foram implementados nativamente.

A implementação agora cobre o catálogo lazy de Skills, o plano explícito de ativação, a ponte segura para o `SkillRegistry`, o avaliador determinístico e o ciclo nativo de documentos `WORKFLOW.md`. O fluxo existente continua sendo o responsável por submeter ações aos limites do BrainCode.

> **Resultado:** o conteúdo externo foi preservado; o contrato seguro de descoberta foi incorporado; execução externa e autorização não foram abertas automaticamente.

## 1.1 Implementação adicional realizada nesta ordem

Foram implementadas as pendências que podiam ser resolvidas sem executar os testes pesados. O catálogo Android agora retém apenas metadados na descoberta e lê o corpo depois da seleção. O plano de ativação registra confiança, motivo, recursos, permissões e estado de aprovação. Skills que exigem permissões não são carregadas para o prompt sem aprovação explícita.

Também foi criada uma ponte que publica manifestos Roofts no `SkillRegistry` como registros desabilitados. Isso torna a descoberta observável sem transformar conteúdo externo em autorização. O avaliador local compara casos esperados com o selector sem chamar provider ou executar o corpo da Skill.

Para workflows, o projeto agora possui parser de frontmatter, precedência customizada sobre community, habilitação reversível, schedule como metadado, backup/restore com hashes e validação de caminhos, além de um adapter que entrega o documento ao `WorkflowEngine` existente. O corpo do Markdown permanece instrução; não é interpretado como código.

O wiring da F4 registra `workflow.run` como capability estável, aplica o gating de Porta 3 aprovada e falha fechado quando não há executor dedicado. A facade expõe restore e os comandos `/workflow list`, `/workflow enable <id>`, `/workflow disable <id>`, `/workflow backup` e `/workflow restore`; o adapter `runDocument` entrega o corpo ao engine somente depois da autorização.

Nesta rodada, o `WorkflowEngine` passou a expor `runDocument` de forma pública, a facade passou a persistir scheduler/leases e oferecer `runWorkflow`/`runDueWorkflows`, e dois workflows originais somente leitura passaram a ser semeados no catálogo após instalação. O marketplace e o `SkillRegistry` agora reutilizam chaves públicas Ed25519 provisionadas no asset, com list/resolve/pin sem download ou habilitação automática. O APK ganhou um gate que verifica as 25 Skills e as chaves confiáveis.

## 2. O que foi preservado no pacote

O pacote final contém duas raízes:

| Raiz | Conteúdo | Estado |
|---|---|---|
| `BrainCode-main` | código, assets, documentação, testes e referências do projeto | preservado com as alterações desta rodada |
| `absorcao-projeto3` | PDFs, DOCX e materiais externos usados como plano e referências; oito arquivos no total | preservado sem remoção |

A camada `app/src/main/assets/roofts/0.6` continua contendo as Skills, agentes, hooks, scripts, avaliações, documentação e arquivos de marketplace que já estavam no projeto. Esses arquivos não foram apagados nem substituídos. A preservação do conteúdo não significa que todos esses componentes estejam ativos no runtime Android.

## 3. Fonte principal incorporada

A fonte de comportamento da primeira fatia foi `addyosmani/agent-skills`, já incorporada ao projeto como Roofts 0.6.

| Campo | Valor |
|---|---|
| Projeto | `addyosmani/agent-skills` |
| Tag | `0.6.10` |
| Commit | `c004a74784a08295d52749b04cda634125b9a581` |
| Licença | MIT |
| Cópia da licença | `app/src/main/assets/roofts/0.6/LICENSE` |
| Manifesto local | `docs/ROOFTS_0.6.md` |

O upstream foi tratado como uma coleção de conteúdo e scripts auxiliares. Não foi tratado como um segundo cérebro ou um orquestrador que pudesse substituir Planner, Policy, Capability, ActionGateway ou Sandbox.

## 4. Código absorvido nesta rodada

### 4.1 Contrato de Skill

Arquivo: `brain/src/main/kotlin/com/brain/skill/RooftsSkill.kt`.

O contrato existente recebeu campos opcionais para manter compatibilidade com os construtores atuais:

| Campo | Função | Estado atual |
|---|---|---|
| `triggers` | termos explícitos que ajudam a ativar a Skill | lidos pelo loader e usados pelo selector |
| `exclusions` | termos que impedem ativação por coincidência | lidos pelo loader e aplicados pelo selector |
| `resources` | caminhos de referências ou recursos declarados | registrados como metadados; não executados |
| `contentHash` | SHA-256 do arquivo completo | calculado no loader |
| `sourcePath` | caminho do asset de origem | preenchido quando o carregamento vem dos assets |
| `origin` | origem lógica do conteúdo | padrão `roofts-0.6` |
| `license` | licença declarada para o conteúdo | padrão `MIT` nesta camada |

Esses campos descrevem o conteúdo. Nenhum deles concede capability, acesso de rede, acesso a segredo, execução de shell ou escrita em projeto.

### 4.2 Loader de `SKILL.md`

Arquivo: `app/src/main/kotlin/com/sandbox/app/RooftsSkillLoader.kt`.

O loader agora interpreta o frontmatter de `SKILL.md` para `name`, `description`, listas de `triggers`, `trigger_examples`, `exclusions`, `avoid_when`, `resources` e `references`. Ele aceita listas inline, como `[review, release]`, e listas YAML em linhas iniciadas por `-`.

O loader calcula SHA-256 do conteúdo completo e guarda o caminho do asset. A leitura permanece local e limitada aos assets empacotados no aplicativo. Não há download, instalação, execução de dependência ou ativação de script durante o carregamento.

O corpo do arquivo ainda é carregado pelo caminho Android existente. Portanto, esta rodada **não implementa carregamento progressivo real em três níveis**. Ela prepara os metadados para que essa etapa possa ser feita futuramente sem alterar o contrato de seleção.

### 4.3 Selector de Skills

Arquivo: `brain/src/main/kotlin/com/brain/skill/RooftsSkillSelector.kt`.

Antes de pontuar uma Skill, o selector verifica `exclusions`. Se algum termo de exclusão ocorrer no objetivo, aquela Skill é removida da seleção. Quando há `triggers`, eles acrescentam pontuação ao mecanismo determinístico já existente.

O selector continua limitado a `MAX_SKILLS = 3`, mantém o fallback de planejamento e não altera o caminho de autorização. O matching permanece local e determinístico através de `TermMatcher`.

### 4.4 Caller real preservado

Arquivo: `app/src/main/kotlin/com/sandbox/app/CodeGenerationExecutor.kt`.

O caller real já existente continua fazendo a seleção antes de montar o prompt. O corpo das Skills selecionadas continua limitado a `MAX_SKILL_BODY_CHARS = 1500`. A resposta do gateway continua passando pelo parser de blocos de arquivos e pelas verificações de caminho seguro.

Não foi criado um executor paralelo. A injeção de Skills continua sendo apenas contexto metodológico para a Porta 3.

### 4.5 Testes adicionados

Foram adicionados testes focados para:

- parsing de `triggers`, `avoid_when` e `resources`;
- registro de `sourcePath`;
- geração de hash SHA-256;
- valor padrão de licença;
- bloqueio de uma Skill por exclusão declarada;
- ativação de uma Skill quando o trigger ocorre sem exclusão.

## 5. O que ficou fora da absorção comportamental

### 5.1 Recursos executáveis e hooks

Os scripts, hooks e recursos que permanecem nos assets não foram conectados a um executor. Isso foi intencional. Para ativá-los seria necessário definir sandboxing, permissões, limites de tempo, política de rede, tratamento de saída, provenance e rollback.

### 5.2 Autorização operacional completa

O objeto `RooftsSkillActivation`/`RooftsSkillActivationPlan` já registra `runId`, confiança, justificativa, recursos solicitados, permissões e estado de aprovação. `RooftsSkillManifestBridge.registerDiscovered` publica cada manifesto no `SkillRegistry` como descoberta desabilitada, preservando hash e origem. O que permanece fora desta absorção é a autorização operacional completa: o plano não aprova efeitos implicitamente nem altera `PolicyBroker`, `CapabilityRegistry` ou `ActionGateway` para executar corpo, recursos ou ferramentas.

### 5.3 Carregamento progressivo real

**Implementado nesta ordem.** `RooftsSkillCatalog` retém metadados e hash, `activate()` filtra permissões pendentes e só então carrega o corpo selecionado. `loadResource()` exige que o caminho esteja declarado no manifesto, rejeita caminhos absolutos e traversal e lê somente dentro do diretório da Skill.

### 5.4 Marketplace e distribuição

O arquivo `.claude-plugin/marketplace.json` preservado nos assets não foi convertido em autorização automática. Em paralelo, `WorkflowMarketplaceRegistry` fornece um catálogo nativo separado, com origem HTTPS, pinagem e assinatura Ed25519. O registry não baixa, instala ou habilita conteúdo; isso evita confundir pertencimento de distribuição com permissão operacional.

### 5.5 Workflows do Clawflows

**Padrões absorvidos nativamente.** O repositório `nikilster/clawflows` foi auditado como referência arquitetural. O BrainCode não copia seu CLI nem symlinks, mas implementa `WorkflowDocument`, `WorkflowCatalog`, `WorkflowSchedule`, backup/restore com manifesto e adapter para o engine existente.

O formato `WORKFLOW.md` agora é convertido em `WorkflowManifest` de um node e passa pelo `WorkflowEngine`. A execução continua exigindo `authorize`, idempotency key, retry, timeout e lease do engine. Habilitar o workflow apenas altera o catálogo; não executa e não concede permissões. O `WorkflowScheduler` persiste próxima execução e claim por owner. O `WorkflowMarketplaceRegistry` aceita somente manifests HTTPS pinados com assinatura Ed25519 confiável; ele não baixa nem habilita conteúdo.

### 5.6 Evals e ciclo de criação de Skills

**Implementado em escopo determinístico.** `RooftsSkillEvaluator` executa casos locais de seleção, verifica Skills esperadas e exclusões e produz score e motivo. Ele não executa corpo, provider, rede ou ferramenta. Um grader semântico e comparação de qualidade com modelo continuam fora do escopo sem testes e infraestrutura adicionais.

### 5.7 Integrações externas não copiadas

Os materiais de análise de Codex, OpenCode, Anthropic Skills, Clawflows, SearchClaw, ferramentas e práticas permaneceram como referência documental. Nenhum provider, API key, MCP, dependência externa, comando de instalação ou código executável desses materiais foi introduzido nesta fatia. As capacidades de workflow foram reimplementadas nativamente no BrainCode, sem copiar o CLI externo.

## 6. Segurança e limites mantidos

A cadeia permanece conceitualmente:

```text
objetivo do usuário
    ↓
selector local de Skills
    ↓
contexto metodológico limitado
    ↓
PolicyBroker
    ↓
CapabilityRegistry / ActionGateway
    ↓
Sandbox e validações de artefato
```

O texto de uma Skill não pode conceder a si próprio acesso. Triggers são sinais de descoberta. Exclusões reduzem falsos positivos. Hash e origem produzem evidência. Nenhum desses dados substitui autorização.

A escrita de arquivos gerados continua protegida por caminhos relativos, rejeição de caminhos absolutos, rejeição de `..`, limite de tamanho, canonicalização e verificação de que o destino permanece dentro do projeto ativo.

## 7. Validação executada

O baseline local do fechamento foi executado no commit de referência `9a3c52020440d2d4c48b363854ad49975e1c52f3`, primeiro sem ambiente Android e depois com JDK 17 e Android SDK 34. A tabela histórica e a separação entre falha ambiental, regressão de compatibilidade e resultado final estão em `docs/BASELINE_ABSORCAO_PROJETO3_2026-09-23.md`.

| Gate | Resultado | Comando/evidência |
|---|---:|---|
| suíte JVM completa | **PASS** | `./gradlew :brain:test --no-daemon --console=plain` |
| suíte Android-module completa | **PASS** | `./gradlew :android-module:test --no-daemon --console=plain` |
| suíte Android app completa | **PASS** | `./gradlew :app:testDebugUnitTest --no-daemon --console=plain` |
| `workflow.run`/restore/segurança | **PASS** | testes focados de workflow, policy, marketplace, bridge e T-203 instrumentado compilado |
| architecture gate | **PASS** | `bash scripts/architecture-gate.sh` |
| `assembleDebug` | **PASS** | `./gradlew :app:assembleDebug --no-daemon --console=plain` |
| lint | **PASS** | `./gradlew :app:lint --no-daemon --console=plain` |
| APK assets | **PASS** | `./scripts/verify-apk-assets.sh app/build/outputs/apk/debug/app-debug.apk` |

As 13 falhas registradas no baseline foram eliminadas pelas correções de compatibilidade do resultado legado, fallback honesto do composer, proveniência de pesquisa, gate semântico apoiado em evidência e timeout de instalação compatível com o limite do Sandbox.

Os gates ainda pendentes são:

| Gate | Estado | Motivo |
|---|---|---|
| suíte JVM completa | PASS | `:brain:test` |
| suíte Android-module completa | PASS | `:android-module:test` |
| suíte Android app | PASS | `:app:testDebugUnitTest` |
| `assembleDebug` | PASS | APK debug gerado |
| lint | PASS | relatório HTML sem falha bloqueante |
| CI | workflow configurado, execução remota pendente | `.github/workflows/ci.yml` |
| E2E | workflow configurado, execução remota pendente | `.github/workflows/ui-e2e.yml` |
| Ed25519 API 26/33 | workflow configurado, execução remota pendente | `.github/workflows/marketplace-compat.yml` |
| instrumentação local | compilada, não executada | nenhum dispositivo/emulador anexado nesta sessão |
| readiness de release | não executado | reservado para a etapa final |

## 8. Critério de integração e estado atual

O código incorporado já possui contrato, caller real, limite de segurança, testes focados e documentação de proveniência. O ciclo de workflow também possui parser, catálogo, habilitação, backup/restore, scheduler persistente, comandos de facade e adapter para o engine. O critério completo de release ainda não está fechado porque os workflows remotos de CI/E2E e o teste físico ainda precisam executar no GitHub Actions.

Assim, o estado correto é:

> **Absorção e gates locais: verdes. CI/E2E/dispositivo: automatizados e aguardando execução remota; readiness de release: pendente.**

## 9. Próximas etapas, somente após ordem

A sequência recomendada para a próxima rodada é:

1. executar CI e E2E no mesmo HEAD;
2. executar a suíte instrumentada em emuladores API 26 e API 33, incluindo T-203;
3. validar a instalação física dos assets Roofts 0.6 no APK;
4. revisar logs, proveniência e regressões e declarar readiness de release;
5. somente então avaliar etapas que continuam fora do escopo: grader semântico, execução de recursos externos e aprovação automática de efeitos.

## 10. Documentos relacionados no projeto

- `docs/ABSORCAO_PROVENIENCIA.md` — registro curto de origem, arquivos e validação.
- `docs/ROOFTS_0.6.md` — estado canônico da camada Roofts 0.6.
- `docs/THIRD_PARTY_NOTICES.md` — aviso de licença e proveniência.
- `docs/BRAINCODE_2.4_GARIMPAGEM_E_INCORPORACAO.md` — critérios gerais da absorção.
- `docs/Fase2_Recursos_Estudo_ClawFlows.md` — referência de estudo do Clawflows.
- `An#U00e1lisedeCodigos/Manus/deep/03-anthropics-skills.md` — auditoria do fluxo Agent Skills.
- `An#U00e1lisedeCodigos/Manus/deep/06-clawflows.md` — auditoria do fluxo Clawflows.

## Referências

[1]: https://github.com/addyosmani/agent-skills "addyosmani/agent-skills"

[2]: https://github.com/nikilster/clawflows "nikilster/clawflows"

[3]: https://github.com/anthropics/skills "anthropics/skills"

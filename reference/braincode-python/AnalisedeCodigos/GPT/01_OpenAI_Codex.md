# 01 — OpenAI Codex

## Objetivo da análise

Estudar o Codex como referência para execução de agentes de código, ferramentas, sandbox, aprovações, contexto, testes e integração Brain ↔ Sandbox. A ideia não é copiar o Codex: é extrair padrões que façam sentido para o Braim.

## Pente fino adicional — estruturas concretas encontradas

A árvore atual do repositório mostra que o próprio Codex usa uma camada `.codex/skills/` com Skills reais, além de `agents/`, `references/` e `scripts/`. Isso é importante porque demonstra uma Skill como pacote operacional, não apenas texto de prompt. Exemplos observados incluem `babysit-pr`, `code-review`, `code-review-testing`, `code-review-context`, `remote-tests`, `test-tui`, `path-types` e `update-v8-version`. Algumas Skills possuem scripts Python de execução/teste e referências auxiliares.

O padrão que vale absorver é:

```text
Skill
├── SKILL.md
├── agents/        # configuração opcional do agente
├── references/    # conhecimento auxiliar
└── scripts/       # automação determinística
```

Isso reforça uma decisão importante para o Braim: **Skill não deve ser somente prompt**. Ela pode conter conhecimento, scripts determinísticos, testes e metadados.

### Skill + script determinístico

Quando uma operação pode ser código normal, não devemos gastar tokens de LLM. Um exemplo é um watcher de PR: a Skill orienta e o script executa. No Braim, isso vira:

```text
Skill → decide como fazer
Tool/Script → executa operação determinística
LLM → interpreta apenas onde há ambiguidade
```

### Skill com subagente

A presença de arquivos `agents/*.yaml` dentro de algumas Skills mostra outra possibilidade: uma capacidade pode declarar qual perfil especializado deve executá-la. No Braim isso pode alimentar o `AgentSelector`, sem amarrar Skill a um modelo específico.

### Referências externas separadas

`references/` evita inflar o `SKILL.md`. O Braim deve adotar carregamento progressivo: primeiro metadados, depois instrução principal, depois somente a referência necessária.

### Testes da própria Skill

O repositório também mantém testes junto de scripts de Skill. Portanto, Skill pode ter um ciclo de validação próprio:

```text
Skill instalada
→ validar estrutura
→ executar testes
→ registrar versão/resultado
→ liberar para uso
```

### Ambientes declarados

A existência de `.codex/environments/environment.toml` reforça a ideia de descrever o ambiente esperado separadamente da lógica da Skill. Isso combina com o contrato Brain→Sandbox: o Brain declara capacidades/requisitos; o Sandbox resolve o ambiente.

## O que o repositório oferece

O Codex é um coding agent executado no terminal e implementado principalmente em Rust. A arquitetura atual possui um núcleo de execução de ferramentas, `exec_command`, `apply_patch`, gerenciamento de processos, políticas de aprovação, sandbox, eventos, hooks, MCP, ambientes de execução e testes de integração. O próprio repositório mantém regras detalhadas de engenharia em `AGENTS.md`, inclusive limites de contexto, testes de integração e organização modular.

## Ferramentas e conceitos que interessam ao Braim

### 1. `exec_command`

É a referência mais importante para o contrato com o Sandbox. O executor recebe comando, diretório de trabalho, ambiente, timeout, TTY, permissões adicionais, política de sandbox e contexto de execução. O Braim não deve copiar a API inteira, mas pode absorver a ideia de um pedido de execução rico e explícito.

### 2. `apply_patch`

Separar edição de arquivos da execução shell é uma ótima ideia. No Braim, a camada de execução deve reconhecer operações diferentes: executar comando, criar/alterar arquivo, aplicar patch, consultar arquivo, testar e produzir artefato.

### 3. Aprovação e política de execução

O Codex possui uma camada central de approvals e diferencia ferramentas e permissões. Isso inspira um `ExecutionPolicy` do Braim, mesmo que o produto final tenha execução automática. A política pode decidir quais operações são automáticas, quais exigem confirmação e quais são proibidas por configuração.

### 4. Sandbox por ambiente

O executor resolve ambiente, filesystem, diretório de trabalho e permissões antes de executar. Isso reforça a decisão do nosso contrato: o Brain deve enviar intenção/requisitos e não depender de comandos internos do Sandbox.

### 5. IDs e rastreabilidade

Cada chamada de ferramenta possui identificadores e eventos de início/fim. O Braim deve ter `jobId`, `attemptId`, `stepId`, `toolCallId` e `sessionId`. Isso será essencial para aprendizado posterior.

### 6. Eventos

O fluxo de ferramenta emite eventos de execução. Absorver a ideia de um event stream interno permite registrar: iniciado, aguardando aprovação, executando, stdout parcial, stderr parcial, concluído, falhou, cancelado e timeout.

### 7. Cancelamento

Execuções longas precisam de cancellation token. O Braim deve poder cancelar um job sem matar a sessão inteira.

### 8. Timeouts

Timeout deve fazer parte do pedido de execução e também existir como limite máximo definido pelo Sandbox. O Brain pode escolher timeout esperado; o Sandbox impõe o teto real.

### 9. Truncamento de saída

Não enviar stdout gigantesco de volta ao LLM. O Codex possui mecanismos de limite/truncamento. No Braim, o resultado deve separar `stdout`, `stderr`, tamanho total, quantidade truncada e resumo opcional.

### 10. Contexto com limites

O `AGENTS.md` é explícito: contexto deve ser incremental, limitado e com hard caps. O Braim deve aplicar a mesma filosofia: memória e logs completos ficam persistidos; somente um recorte relevante entra no prompt.

### 11. Testes de integração

Mudanças no comportamento do agente devem ter testes de integração. Para o Braim, cada grande fluxo do orquestrador deve possuir testes E2E simulando secretário → planejamento → agente → execução → falha → correção → sucesso.

### 12. MCP

O Codex possui gerenciamento de MCP e ferramentas dinâmicas. O Braim pode absorver a ideia de um registry de ferramentas externas, mas o MCP deve ser apenas um mecanismo de integração, não o cérebro.

## O que absorver diretamente no desenho do Braim

- `JobRequest` estruturado.
- `JobResult` estruturado.
- IDs de execução.
- Estado de execução.
- Eventos de ferramenta.
- Timeout e cancelamento.
- stdout/stderr separados.
- Truncamento controlado.
- Política de permissões.
- Sandbox por ambiente.
- Ferramentas tipadas.
- `apply_patch` como operação distinta de shell.
- Testes de integração para comportamento do agente.
- Contexto com tamanho máximo.
- Registro de cada tentativa.
- Skills compostas por instrução + referências + scripts + testes.
- Agente declarado por Skill quando necessário.
- Ambiente/requisitos declarados separadamente.

## Modelo sugerido para o contrato

```text
BrainJobRequest
  job_id
  session_id
  objective
  task_type
  requirements[]
  workspace
  inputs[]
  expected_outputs[]
  tools_allowed[]
  capabilities_required[]
  timeout_ms
  network_required
  priority
  parent_step_id

SandboxJobResult
  job_id
  status
  exit_code
  stdout
  stderr
  files_created[]
  files_modified[]
  files_deleted[]
  artifacts[]
  duration_ms
  tests[]
  error
  diagnostics[]
  environment
```

## O que NÃO copiar

- UI/TUI do Codex.
- Dependência do ecossistema Rust.
- Modelo específico de produto.
- Regras internas de sandbox específicas do Codex.
- Configuração inteira.
- Nomes internos de ferramentas.

O Braim deve manter seu próprio modelo de execução.

## Segurança

O Codex demonstra que execução de comando é uma superfície de segurança de primeira classe. O Braim precisa considerar shell injection, paths fora do workspace, escalada de permissões, acesso à rede, segredos, processos persistentes e artefatos não esperados.

## Licença

O Codex é Apache License 2.0. Qualquer reutilização literal de código deve preservar licença, notices e condições aplicáveis. Para o Braim, a recomendação é absorver principalmente arquitetura e ideias, não copiar grandes trechos.

## Prioridade para o Braim

**CRÍTICA.** É a principal referência para o contrato Brain ↔ Sandbox.

## Fontes analisadas

- https://github.com/openai/codex
- https://github.com/openai/codex/blob/main/AGENTS.md
- https://github.com/openai/codex/blob/main/codex-rs/core/src/tools/handlers/unified_exec/exec_command.rs
- https://github.com/openai/codex/blob/main/codex-rs/core/src/tools/approvals.rs
- https://github.com/openai/codex/blob/main/codex-rs/core/src/tools/events.rs
- https://github.com/openai/codex/blob/main/LICENSE

## Conclusão

O maior aprendizado do Codex para o Braim não é “como rodar código”. É **como transformar execução em uma operação observável, controlável, cancelável, testável e rastreável**. A árvore atual acrescenta uma segunda lição: Skills podem ser pacotes executáveis com referências, scripts, testes e agentes especializados. Isso deve entrar no desenho desde o começo.

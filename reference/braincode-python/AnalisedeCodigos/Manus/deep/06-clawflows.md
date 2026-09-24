# Auditoria de fluxo e absorção — `nikilster/clawflows`

**Repositório:** [nikilster/clawflows](https://github.com/nikilster/clawflows)  
**Snapshot auditado:** `f1e4094752b0359c7a3089720457a536a4ae2813`  
**Escopo:** fluxo real do CLI, ciclo de vida dos workflows, prioridade custom/community, ativação, execução, sincronização com agente e backup. Não executei testes; usei os testes existentes para reconstruir contratos esperados.

## 1. O que o repositório é

Clawflows é uma camada de workflows textuais para OpenClaw. Seu núcleo é pequeno: um CLI Bash, diretórios convencionais e arquivos `WORKFLOW.md`. O repositório combina catálogo comunitário, workflows customizados do usuário, links de workflows habilitados, backup/restauração e sincronização de instruções para `AGENTS.md`.

O README declara 113 workflows pré-construídos. A árvore separa `workflows/available/community/`, `workflows/available/custom/`, `workflows/enabled/`, `workflows/shared/` e scripts em `system/`. A maior parte da lógica de operação fica em `system/cli/clawflows`; o catálogo comunitário é conteúdo, não código executável.

## 2. Anatomia do workflow

Um workflow é uma pasta com `WORKFLOW.md`. O frontmatter observado em `check-calendar` é:

```yaml
---
name: check-calendar
emoji: "📅"
description: Calendar review — scans the next 48 hours...
author: @davehappyminion
schedule: "8am, 6pm"
---
```

O corpo é instrução operacional. O workflow de calendário segue cinco fases:

1. buscar eventos dos próximos 48h por meio da calendar skill;
2. detectar conflitos, reuniões consecutivas, falta de almoço, blocos longos e horários extremos;
3. gerar preparação por participante, tipo de reunião e documentos/perguntas;
4. apresentar uma saída fixa com hoje, amanhã, alertas e resumo;
5. oferecer ações rápidas como adicionar buffer, recusar reunião ou criar lembrete.

O formato é legível para humanos e para o agente, mas não é uma DSL determinística. `schedule` é metadado para ativação temporal; as etapas do corpo ainda dependem de interpretação do agente e das Skills disponíveis.

O template comunitário torna mais explícitos os campos que faltam em alguns exemplos: `Prerequisites`, chaves, ferramentas, acessos, passos, formato de saída e comportamento de falha. Ele inclusive usa placeholders como `{{SECRET:EXAMPLE_API_KEY}}`, demonstrando uma intenção de referenciar segredo sem colocar seu valor no arquivo.

## 3. Fluxo de diretórios e precedência

O help do CLI documenta:

```text
~/.openclaw/workspace/clawflows/workflows/
├── available/community/  # conteúdo do repositório, atualizado por git
├── available/custom/    # workflows do usuário, gitignored
└── enabled/             # links para workflows ativos
```

A função `_find_workflow` consulta `custom/` antes de `community/`. Portanto, se os dois diretórios contêm o mesmo nome, o customizado vence. Isso permite ao usuário substituir a versão comunitária sem modificar o catálogo recebido.

O fluxo de edição segue uma regra segura: `edit <name>` copia um workflow comunitário para `custom/`; depois o usuário altera a cópia. A atualização do catálogo não sobrescreve a versão customizada. A função de `open` só abre o arquivo selecionado no editor, e `list` apresenta os dois conjuntos.

A ativação não copia conteúdo: cria um symlink em `enabled/<name>` apontando para custom ou community. A desativação remove somente o symlink, nunca o diretório customizado. Isso mantém a fonte e o estado de habilitação separados.

## 4. Fluxo de criação

O comando `create` suporta uso interativo e criação programática via JSON. O formato programático recebe `name`, `emoji`, `summary`, `schedule`, `author` e `description`; usa `jq` quando disponível para preservar aspas e barras invertidas, com fallback limitado quando `jq` não existe.

O nome é validado contra caracteres alfanuméricos, hífen e sublinhado, começa por alfanumérico e tem no máximo 64 caracteres. A validação rejeita newline para impedir bypass do regex. Essa decisão protege o caminho do filesystem contra `/`, `..` e nomes ambíguos.

Depois de criado, o workflow fica em `available/custom/<name>/WORKFLOW.md`. A criação não deve implicitamente habilitar ou executar o workflow. A separação é boa para o Brain: autoria, instalação e ativação são decisões diferentes.

## 5. Fluxo de enable/disable

O comando `enable <name>`:

1. valida o nome;
2. resolve custom antes de community;
3. verifica se o workflow existe;
4. cria `enabled/<name>` como symlink;
5. informa descrição e schedule;
6. se não houver schedule, informa que a execução é on-demand;
7. chama `sync-agent` para atualizar o contexto do agente.

Se já estiver habilitado, o fluxo é idempotente e retorna “already enabled”. O teste existente também exige que a prioridade custom seja refletida no alvo do symlink e que `AGENTS.md` seja sincronizado.

`disable <name>` remove o link. O design evita apagar o conteúdo, o que é importante para dados autorais e para rollback.

## 6. Fluxo de execução

O comando `run <name>` tem dois comportamentos:

1. se o workflow existir mas não estiver habilitado, ele o habilita automaticamente;
2. se estiver habilitado, localiza `WORKFLOW.md` e tenta delegar a execução ao comando `openclaw`.

Quando `openclaw` está disponível, o CLI entrega o workflow ao agente e imprime que está executando. Quando não está, entra em fallback: apresenta instruções para o usuário dizer ao agente para executar o workflow. Esse fallback permite instalar o catálogo sem exigir que o runtime esteja presente na máquina.

O fluxo falha quando o workflow não existe ou quando o symlink aponta para um diretório sem `WORKFLOW.md`. A preferência custom permanece aplicada mesmo quando a execução é iniciada sem `enable` prévio.

O ponto arquitetural é que Clawflows **não executa o corpo do workflow por conta própria**. Ele resolve o arquivo e o entrega ao OpenClaw. A interpretação, as chamadas a calendário/e-mail/HTTP e os efeitos ficam no agente hospedeiro.

## 7. Fluxo de sincronização com `AGENTS.md`

`sync-agent` gera um bloco delimitado por:

```text
<!-- clawflows:start -->
...
<!-- clawflows:end -->
```

Ele substitui o bloco existente em vez de duplicá-lo. Inclui workflows habilitados, schedule, caminhos para `WORKFLOW.md` e comandos do CLI. Se `AGENTS.md` não existir, o comando pula de forma graciosa. Symlinks quebrados são ignorados.

Esse mecanismo transforma estado de habilitação em contexto descobrível pelo agente, mas é uma sincronização textual. No Brain, o equivalente deve ser um registro estruturado de capacidades habilitadas e uma visão textual gerada sob demanda; editar um arquivo de instruções não deve ser o único mecanismo de autorização.

## 8. Fluxo de backup/restauração

`backup` cria um `tar.gz` em diretório de backups, normalmente com nome `clawflows-YYYY-MM-DD-HHMMSS.tar.gz`. O arquivo inclui:

- `custom/` com workflows do usuário;
- `enabled-workflows.txt` com nomes habilitados;
- não inclui `.gitkeep`;
- pode aceitar nome customizado;
- informa contagens de customizados e habilitados.

`restore` descompacta o backup, restaura customizados e recria links somente para workflows encontrados em community ou custom. Um nome órfão no `enabled-workflows.txt` é ignorado, não cria link arbitrário. Essa regra reduz risco de restauração apontar para conteúdo inexistente.

O backup é portátil entre workspaces, mas o design ainda é dependente do filesystem e não registra versão do schema, hash dos arquivos ou permissões. Para o Brain, backup deve conter manifesto, versão, hashes, origem e estado de aprovação.

## 9. Atualização e comunidade

O catálogo comunitário é atualizado via git. `update` busca novas versões, preservando `custom/`. O comando `validate` checa estrutura e frontmatter antes de submeter. `submit` prepara uma contribuição comunitária baseada no template. O fluxo comunitário é, portanto:

```text
criar custom → validar → usar localmente → submeter → revisão/merge → update
```

A separação entre comunidade e custom reduz conflitos, mas não assina conteúdo nem comprova que uma instrução comunitária é segura. O Brain deve tratar catálogo externo como conteúdo não confiável até revisão e pinagem.

## 10. O que absorver no Brain

| Prioridade | Absorção | Implementação proposta |
|---|---|---|
| P0 | Formato `WORKFLOW.md` | Parser de frontmatter + corpo Markdown |
| P0 | Separação available/custom/enabled | Catálogo, autoria e estado independentes |
| P0 | Precedência custom sobre comunidade | Resolver com origem explícita e aviso ao usuário |
| P0 | Enable/disable reversível | Estado persistente, sem exclusão do conteúdo |
| P0 | Validação de nomes | IDs seguros, sem path traversal |
| P1 | `run` com auto-enable controlado | Autoativação somente para leitura; efeitos pedem aprovação |
| P1 | Sync textual para contexto | Gerador de snapshot de capacidades habilitadas |
| P1 | Backup/restore de estado | Manifesto versionado, hashes e lista de ativações |
| P1 | Schedule em frontmatter | Scheduler com timezone, próxima execução e lock |
| P2 | Marketplace comunitário | Registry assinado, revisão, versão e rollback |

## 11. Como o fluxo deve ser endurecido

O Brain não deve entregar o texto de um workflow diretamente ao modelo sem compor contexto de segurança. Antes da execução, deve resolver:

```text
workflow_id + version + source
   ↓
manifesto e hash
   ↓
pré-requisitos e permissões
   ↓
policy gate
   ↓
contexto do agente
   ↓
tools permitidas
   ↓
aprovação para efeitos externos
```

Um workflow que diz “envie”, “delete”, “compre”, “publique” ou “altere” precisa declarar o efeito e passar por confirmação apropriada. A schedule não deve transformar automaticamente uma instrução em autorização permanente.

Também é necessário definir idempotency key por execução. Se o scheduler disparar duas vezes, `process-email` ou `send-expense-report` não pode duplicar efeitos. Cada run deve ter `run_id`, `workflow_version`, `started_at`, `trigger`, `tool_calls`, `artifacts`, `status` e `error`.

## 12. Veredito

Clawflows tem um fluxo pequeno e muito absorvível: **arquivo humano → catálogo → customização → habilitação reversível → entrega ao agente → sincronização de contexto → backup**. Seus melhores padrões são a precedência custom, o uso de symlink como ativação sem cópia, o auto-enable explícito, a validação de nomes, o fallback quando o agente não está instalado e o backup que preserva customizados.

O Brain deve absorver o formato e o ciclo de vida, mas substituir symlink e texto sincronizado por estado estruturado, permissões, versionamento, locks, idempotência, provenance e aprovação de efeitos externos.

## Referências

[1]: https://github.com/nikilster/clawflows "Clawflows"
[2]: https://github.com/nikilster/clawflows/blob/main/system/cli/clawflows "Clawflows CLI"
[3]: https://github.com/nikilster/clawflows/blob/main/workflows/available/community/check-calendar/WORKFLOW.md "Calendar workflow example"
[4]: https://github.com/nikilster/clawflows/blob/main/community-submissions/_template/WORKFLOW.md "Workflow template"
[5]: https://github.com/nikilster/clawflows/tree/main/tests "Clawflows contract tests"

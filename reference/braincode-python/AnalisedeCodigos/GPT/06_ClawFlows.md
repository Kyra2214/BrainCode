# 06 — nikilster/clawflows

## Pente fino adicional

O ponto mais útil é a separação entre definição declarativa e execução. Um `WORKFLOW.md` pode ser tratado como uma especificação versionável que o Brain transforma em passos executáveis.

### Workflow como artefato

Registrar hash/versão, origem, autor, licença, dependências, schedule, última execução e resultados. Isso permite rollback e auditoria.

### Importação segura

`import` remoto não deve significar ativação imediata. O fluxo correto no Braim:

```text
fetch → parse → schema → trust → license → security → dry-run → activate
```

### Scheduler separado

Scheduler deve acordar Tasks; não decidir lógica de negócio. IaBrain continua sendo o Orchestrator.

## Objetivo

Estudar workflows persistentes, agendamento, reutilização, importação e estado para a futura camada OpenClaw — Tarefas do Braim.

## Conceito central

ClawFlows transforma instruções em workflows reutilizáveis. Cada workflow possui descrição, agenda e passos. Há workflows on-demand e recorrentes.

## Estrutura útil

O formato `WORKFLOW.md` possui frontmatter com:

- name;
- emoji;
- description;
- author;
- schedule.

Depois vem um procedimento operacional em passos.

Isso pode virar o formato futuro de tarefas persistentes do Braim.

## Comandos interessantes

- `clawflows list available` — descobrir workflows disponíveis;
- `clawflows enable <name>` — ativar workflow;
- `clawflows import <url>` — importar workflow remoto;
- agendamento automático;
- workflows on-demand.

O Braim deve ter equivalentes próprios, sem depender do CLI ClawFlows.

## O que absorver

### Workflow declarativo

Um workflow deve descrever o objetivo e passos sem precisar codificar toda a lógica no núcleo.

### Schedule

Tarefas futuras precisam de:

```text
schedule
next_run
last_run
state
retry_policy
```

### Dependências

Um workflow pode exigir Skills/serviços. Antes de executar, o Brain deve verificar pré-requisitos.

### Versionamento

Workflows precisam ser versionados e ter rollback.

### Importação

A ideia de importar um workflow por URL é boa, mas o Braim deve validar conteúdo, assinatura/origem/licença e segurança antes de ativar.

### Estado persistente

O workflow não pode depender da memória temporária do agente. Estado deve sobreviver a reinício.

## Relação com o plano OpenClaw — Tarefas

A arquitetura planejada pelo usuário já diferencia Agent de Task. ClawFlows confirma essa separação.

```text
Agent = especialista/executor
Task = trabalho persistente
Workflow = sequência de passos da Task
```

Uma Task pode ser executada pelo mesmo Agent muitas vezes.

## O que não absorver

- OpenClaw como cérebro;
- dezenas de workflows prontos como dependência;
- scheduler específico;
- instalação automática de terceiros sem validação.

## Segurança

Workflow importado é código/instrução externa. Deve passar por:

1. parse;
2. schema validation;
3. source trust;
4. license check;
5. tool/permission review;
6. sandbox test;
7. activation.

## Prioridade

**MÉDIA/ALTA**, depois do cérebro mínimo funcionar end-to-end.

## Fontes

- https://github.com/nikilster/clawflows
- https://github.com/nikilster/clawflows/blob/main/system/AGENT.md
- https://github.com/nikilster/clawflows/blob/main/workflows/available/community/track-habits/WORKFLOW.md

## Conclusão

ClawFlows fornece um excelente modelo para a futura área de **Tarefas**: declarativa, persistente, agendada, versionada e reutilizável. O pente fino reforça que workflow importado deve ser tratado como artefato externo não confiável até passar por validação e sandbox.

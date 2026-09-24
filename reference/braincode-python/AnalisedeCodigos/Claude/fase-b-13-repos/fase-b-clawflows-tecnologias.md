# Fase B — nikilster/clawflows: tecnologias, ferramentas e técnicas

> Levantamento técnico do sistema de workflows do OpenClaw — formato de
> arquivo, CLI, ativação, agendamento. Sem propor mudança em nada.

## 1. Formato de workflow: markdown + frontmatter, sem DSL própria

Cada workflow é um `WORKFLOW.md` com frontmatter YAML simples:

```yaml
---
name: workflow-name
emoji: 📅
description: One-line summary
schedule: "7am, 5pm"   # opcional, omitido = só sob demanda
author: @handle          # opcional
---

# Workflow Title
Instruções para o agente...
```

Não existe uma DSL de automação (tipo o formato de nó do n8n) — o corpo do
arquivo é instrução em linguagem natural que o próprio agente interpreta e
executa. O agendamento (`schedule: "7am, 5pm"`) também é escrito em
linguagem natural/legível por humano, não cron syntax.

## 2. CLI monolítico em bash como toda a lógica

Toda a lógica do sistema está num único script bash de ~936 linhas
(`system/cli/clawflows`) — não é dividido em módulos. A documentação do
próprio repo (`CLAUDE.md`) marca isso explicitamente: "ALL LOGIC HERE".
Isso é uma escolha deliberada de simplicidade: um usuário (ou agente) que
precisa entender o sistema inteiro só precisa ler um arquivo.

## 3. Ativação via symlink, não flag de config

```
workflows/
├── available/
│   ├── community/    # 54 workflows somente-leitura vindos do GitHub
│   └── custom/        # workflows criados pelo usuário
└── enabled/            # symlinks para os workflows ativos
```

"Habilitar" um workflow é criar um symlink em `enabled/` apontando pra ele
em `available/`. Isso separa naturalmente "existe no catálogo" de "está
ativo agora", sem precisar de um campo de estado num banco de dados ou
arquivo de config central — o sistema de arquivos já é o estado.

## 4. Histórico de execução como diretório gitignored

`system/runs/` guarda o histórico de execução, mas é gitignored — ou
seja, o histórico não faz parte do repositório de workflows em si
(que é versionado e compartilhável), fica só local por instância.

## 5. Fluxo de criação por agente, não só por humano

O próprio agente pode criar um workflow novo via comando estruturado:

```bash
clawflows create --from-json '{
  "name": "remind-to-stretch",
  "schedule": "9am, 11am, 1pm, 3pm, 5pm",
  ...
}'
```

O padrão de conversa documentado mostra o agente perguntando detalhes de
agendamento em linguagem natural, montando o JSON, criando o arquivo, e
lendo de volta o `WORKFLOW.md` gerado pra confirmar — ou seja, criação de
workflow é ela mesma uma tarefa executável pelo agente, não uma etapa
manual separada.

## 6. Comunidade como fonte de catálogo

Mais de 100 workflows pré-construídos, puxados de um repositório GitHub
central (`community/`), com um workflow próprio (`update-clawflows`,
schedule 9am) que atualiza os workflows da comunidade e verifica
anúncios — o próprio sistema se mantém atualizado usando o mesmo
mecanismo de workflow que ele oferece ao usuário.

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- Formato de tarefa recorrente como markdown + frontmatter (não uma DSL
  binária/JSON) — legível e editável por humano e por agente igualmente.
- Agendamento em linguagem natural/legível (`"7am, 5pm"`) em vez de cron
  syntax, com o parsing ficando a cargo do CLI.
- Uso do próprio sistema de arquivos (symlink) como mecanismo de
  ativação/desativação, evitando estado central redundante.
- CLI monolítico único como escolha deliberada de simplicidade/legibilidade
  total, em vez de modularização prematura.
- Criação de tarefa recorrente como ação que o próprio agente executa
  (via comando estruturado), não uma etapa manual fora do loop do agente.
- Auto-atualização do catálogo de workflows usando o próprio mecanismo de
  workflow (meta-aplicação da própria ferramenta).

# Fase B — anthropics/skills: tecnologias, ferramentas e técnicas

> Reanálise (v2): foco na especificação, no empacotamento e nos scripts
> reais das skills, não só a arquitetura de pastas do README. Levantamento,
> sem propor mudança em nada.

## 1. Duas áreas distintas no repositório

- `skills/` — implementações reais (Skill Sets: Creative & Design,
  Development & Technical, Enterprise & Communication, Document Skills).
- `spec/` — a **Agent Skills Specification**, um padrão aberto
  (`agent-skills-spec.md`), com implementação de referência hospedada
  separadamente em agentskills.io. Ou seja, o formato SKILL.md não é só
  "como a Anthropic faz" — é documentado como padrão portável.
- `template/` — skeleton inicial (`template-skill`) pra começar uma skill
  nova do zero.

## 2. Licenciamento misto dentro do mesmo repo

- Skills de exemplo (`algorithmic-art`, `canvas-design`,
  `brand-guidelines`, `internal-comms`, `mcp-builder`, `skill-creator`,
  `webapp-testing`, etc.): Apache 2.0, open source de verdade.
- Skills de documento (`docx`, `pdf`, `pptx`, `xlsx`): **source-available**,
  não open source — são as mesmas que já rodam em produção no Claude.ai,
  compartilhadas como referência de skill complexa, mas sem licença livre.

## 3. Empacotamento via marketplace.json (e suas armadilhas reais)

Skills são agrupadas em plugins via `.claude-plugin/marketplace.json`, que
declara explicitamente quais skills pertencem a qual plugin (ex.:
`document-skills` deveria conter só `xlsx`, `docx`, `pptx`, `pdf`). Isso é
relevante como **contraexemplo documentado**: há issues abertas no próprio
repo mostrando que o mecanismo de instalação clona/cacheia o repositório
inteiro num commit hash, então plugins acabam carregando as skills
"extras" de outros plugins que não deveriam vir junto — em um caso
registrado, isso gerou ~50 mil tokens desperdiçados por skill duplicada no
contexto. É um lembrete concreto de que declarar um manifesto de
pertencimento (`marketplace.json`) não é suficiente sem o mecanismo de
instalação de fato respeitar esse escopo.

## 4. Skill complexa de referência: docx

A skill `docx` usa scripts de manipulação direta de OOXML (o formato XML
por trás do `.docx`) para operações como tracked changes, comentários e
formatação — não é só um prompt de instrução, é `SKILL.md` + scripts reais
que a skill executa. Isso demonstra o padrão "scripts/" da anatomia da
skill funcionando em produção, não só em teoria.

## 5. Portabilidade entre agentes/harnesses

O padrão de instalação já assume múltiplos harnesses-alvo, com path de
instalação diferente por agente:

| Agente | Path de instalação |
|---|---|
| Claude Code | `~/.claude/skills/` |
| VS Code / Copilot | `.github/skills/` |
| Cursor | `.cursor/skills/` |
| Amp | `~/.amp/skills/` |
| Goose | `~/.config/goose/skills/` |
| OpenCode | `~/.opencode/skills/` |
| Portátil (qualquer agente) | `.skills/` |

Isso confirma que o formato SKILL.md foi desenhado pra ser
agente-agnóstico desde o início, não uma feature interna do Claude Code.

## 6. Skill-creator como ferramenta interativa

`skill-creator` não é uma skill de domínio, é uma ferramenta que conduz a
criação de uma skill nova via perguntas guiadas (Q&A), aplicando as
próprias regras de qualidade da especificação (descrição precisa,
carregamento em camadas, etc.) no processo de criação.

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- `agent-skills-spec.md` como especificação aberta e portável — vale ler
  o texto da spec em si, não só a implementação da Anthropic.
- Padrão de manifesto de pacote (`marketplace.json`) com problema real e
  documentado de vazamento de escopo — útil como lição de design (declarar
  um manifesto não garante que o instalador respeite o escopo).
- Uso de scripts de manipulação de formato binário/estruturado (OOXML)
  dentro de uma skill, como exemplo de skill "pesada" vs. skill que é só
  instrução em texto.
- Convenção de paths de instalação por agente/harness, todos apontando pra
  uma mesma estrutura `SKILL.md` — útil como referência de portabilidade
  entre ferramentas diferentes.
- `skill-creator` como padrão de "ferramenta que cria ferramenta" via
  Q&A guiado, aplicando a própria especificação no processo.

# Fase B — affaan-m/ECC: tecnologias, ferramentas e técnicas

> Reanálise (v2): foco no sistema de hooks, no motor de aprendizado
> contínuo e na estrutura real de agentes/skills/regras, não só o README.
> Levantamento, sem propor mudança em nada.

## 1. Natureza do projeto

ECC não é um agente — é uma camada de configuração/runtime que roda em
cima de outros harnesses (Claude Code, Codex, Cursor, OpenCode, Gemini,
Zed, GitHub Copilot). Distribuído como plugin instalável
(`/plugin marketplace add` no Claude Code, `codex plugin add`, adapters
próprios para Cursor/OpenCode). Nasceu de um hackathon interno da
Anthropic.

## 2. Estrutura de diretórios

```
ECC/
├── agents/     # 68 subagentes especializados para delegação
├── skills/     # 292 workflows reutilizáveis, carregados sob demanda
├── commands/   # 94 slash-commands mantidos (legado, enquanto migra pra skills-first)
├── rules/      # padrões opt-in, common + por linguagem
├── hooks/      # automação de runtime e enforcement
├── scripts/    # instalação, reparo, sincronização, orquestração
├── .claude-plugin/  # manifesto de marketplace do Claude Code
├── .codex/          # configuração de referência e papéis de agente pro Codex
├── .opencode/        # plugin, comandos e instruções pro OpenCode
├── .cursor/           # regras e adapter de hook pro Cursor
└── docs/              # guias públicos de setup, arquitetura e operação
```

## 3. Sistema de hooks — contrato de ciclo de vida

Hooks de persistência de memória são um contrato estável, separado do
grafo executável de hooks (`hooks/hooks.json`):

| Evento | Hook | Função | Bloqueante |
|---|---|---|---|
| SessionStart | `session:start` | carrega contexto prévio limitado + metadados do projeto | não |
| PreCompact | `pre:compact` | salva estado antes da compactação de contexto | não |
| PreToolUse | `pre:observe:continuous-learning` | captura intenção da ferramenta pra sinal de aprendizado | não |
| PostToolUse | `post:observe:continuous-learning` | captura resultado da ferramenta pra sinal de aprendizado | não |
| PostToolUse | `post:session-activity-tracker` | registra atividade de tool/arquivo pra métricas do ECC2 | não |
| Stop | `stop:format-typecheck` | quality gate em lote após edições | sim, se o hook falhar |
| Stop | `stop:check-console-log` | audita arquivos modificados por debug logging | aviso/erro conforme saída do hook |
| SessionEnd | `session:end` | persiste resumo de sessão quando há metadados de transcript | não |

Configuração operacional relevante: `ECC_SESSION_START_MAX_CHARS` (limita
quanto contexto é carregado no início), `ECC_SESSION_START_CONTEXT=off`
(desliga o carregamento automático), `ECC_HOOK_PROFILE` +
`ECC_DISABLED_HOOKS` (hooks são "profile-gated", não tudo-ou-nada).
Persistência é local por padrão — transcript/tool traces não saem pra
serviços hospedados a menos que uma integração seja habilitada
explicitamente.

## 4. Continuous Learning v2 — arquitetura de "instintos"

Esse é o núcleo mais elaborado do projeto. Diferenças entre v1 e v2:

| Aspecto | v1 | v2 |
|---|---|---|
| Observação | Hook de Stop (fim de sessão) | PreToolUse/PostToolUse (100% confiável) |
| Análise | Contexto principal | Agente de background (modelo pequeno, ex. Haiku) |
| Granularidade | Skill completa | "Instinto" atômico |
| Confiança | Nenhuma | Score ponderado 0.3–0.9 |
| Evolução | Direto pra skill | Instintos → cluster → skill/command/agent |
| Compartilhamento | Nenhum | Export/import de biblioteca de instintos |

Fluxo: atividade de sessão → hooks capturam prompts + uso de tool (100%
confiável, diferente de depender só do fim da sessão) → detecção de
contexto de projeto → um agente de background (não o agente principal)
analisa e extrai "instintos" atômicos com score de confiança → instintos
relacionados se agrupam em cluster → cluster maduro vira skill, command
ou agent de verdade.

**v2.1** adiciona escopo: instintos podem ser *project-scoped* (padrões de
React ficam só no projeto React, convenções Python só no projeto Python)
ou *global* (padrões universais, ex. "sempre validar input"), com
promoção manual de projeto pra global quando fizer sentido.

## 5. Regras multi-linguagem

`rules/` dividido em `common/` (sempre instalado — coding-style,
git-workflow, testing, performance, patterns, hooks, agents, security) +
pastas por linguagem/framework (`typescript/`, `angular/`, `vue/`,
`python/`, `golang/`...). Instalação seletiva: dá pra instalar só as
linguagens relevantes ao projeto.

## 6. AgentShield

Ferramenta de scan de segurança que audita a própria configuração do
agente — prompts, hooks, configuração MCP, permissões e segredos — não o
código que o agente produz. Ou seja, o harness se auto-audita.

## 7. Perfis de instalação

- Perfil completo (agents + skills + commands + hooks + rules + memory).
- Perfil `minimal` (`npx ecc-universal@X install --profile minimal --target
  claude`): regras, agentes, comandos e config sem os hooks de runtime —
  para quem quer só a parte estática, sem automação em background.
- Adapters por harness têm paridade de feature diferente (ex.: Cursor não
  recebe `AGENTS.md` na raiz, fica escopado nas regras/agentes nativos do
  Cursor).

## Resumo — itens concretos pra estudar mais a fundo se for útil depois

- Arquitetura de "instinto atômico com score de confiança" como unidade
  intermediária entre "log de execução" e "skill pronta" — evita ir direto
  de observação bruta pra capacidade reutilizável.
- Observação via PreToolUse/PostToolUse (hook determinístico) em vez de
  confiar só no fim da sessão, que é um ponto único de falha.
- Análise das observações delegada a um modelo de background separado do
  agente principal, evitando poluir o contexto principal com trabalho de
  aprendizado.
- Escopo project vs global pra conhecimento aprendido, com promoção manual
  — evita contaminação cruzada entre projetos diferentes.
- Regras `common/` sempre carregadas + pastas por linguagem instaláveis
  seletivamente.
- Auto-auditoria de segurança da própria configuração do agente (prompts,
  hooks, MCP, segredos), não só do código gerado.
- Hooks "profile-gated" (ligáveis/desligáveis em grupo) em vez de
  tudo-ou-nada.

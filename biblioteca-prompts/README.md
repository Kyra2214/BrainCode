# Biblioteca de Prompts

`app/src/main/assets/prompts_library.sql` — biblioteca de prompts (13.864 registros em
13 tabelas: `prompts`, `money_prompts`, `coding_prompts`, `finance_prompts`,
`crafti_prompts`, `gallery_prompts`, `everything_prompts`, `llmprompts_prompts`,
`awesome_prompts`, `aiprompts2026_prompts`, `promptforge_prompts`,
`promptschat_prompts`, `extra_prompts`). Compatível com SQLite/PostgreSQL/MySQL.

## Status

**É a única fonte da biblioteca de prompts do app.** O seed em JSON
(`prompts_biblioteca.json`, 75 prompts) foi removido.

- Carga: `com.brain.prompt.PromptLibraryLoader.fromSql(reader)`
  (`brain/src/main/kotlin/com/brain/prompt/PromptLibraryLoader.kt`), chamado pelo
  `SandboxViewModel` a partir do asset `prompts_library.sql`.
- Runtime: `com.brain.prompt.InMemoryPromptLibrary` / `PromptTemplate`
  (versionamento, deduplicação por similaridade, aposentadoria automática por taxa
  de sucesso real).

O loader **não executa** o SQL: lê os comandos em streaming e converte só os
`INSERT`s das tabelas de prompt (mais as tabelas de categorias e tags) em
`PromptTemplate`. Cada linha vira um template com id `sql:<tabela>:<id>`; linhas sem
texto de prompt são ignoradas (hoje 1: `money_prompts` id 10).

Mapeamento para `PromptTemplate`:

| Campo | Origem |
|---|---|
| `textoTemplate` | texto do prompt, intacto (`[PLACEHOLDER]` / `{{var}}` da fonte); em `llmprompts_prompts`, `system_prompt` + `user_prompt` |
| `finalidade` | título (ou seção; ou início do texto quando não há título) |
| `contextoDeUso` | categoria/seção, título, descrição e tags, curtos — é o que a busca compara |
| `skillRelacionada` | palavra-chave nos metadados (nunca no corpo) |

Para acrescentar prompts, edite o SQL mantendo o formato `INSERT INTO <tabela> (colunas)
VALUES (...)`. Uma tabela de prompt nova precisa de um `when` em `PromptLibraryLoader`.
Comentários `/* */` não são suportados no SQL.

## Origem

Consolidado a partir de PDFs de prompts para gestores/negócios, coleções de
prompts para ChatGPT geradores de renda, agentic coding, finanças e da coleção
crafti.pro, mais prompts extraídos de 7 repositórios enviados pelo usuário
(agent-prompt-library, agent-prompts, ai-prompts, awesome-prompts,
claude-code-prompts, useful-ai, vibecode — deduplicados por similaridade de
cosseno TF-IDF ≥ 0,45 contra a biblioteca já existente).

Excluído por conter prompts de sistema proprietários de produtos comerciais
capturados/vazados (não é conteúdo do usuário nem prompt de reúso genérico):
repositório `agent-autopsy` e o arquivo `claude_artifacts_prompt.md` do
`awesome-prompts`.

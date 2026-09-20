# Correção de negação no Planner

## Problema

O `KeywordFunctionSplitter` detectava radicais diretamente no objetivo completo. Assim, termos como `pesquisar`, `escrever` e `executar` também eram encontrados em instruções como `não pesquisar`, `sem escrever código` e `evite executar`, criando passos que contrariavam a intenção explícita do usuário.

## Correção

Foi introduzido `IntentNegation`, uma heurística textual pequena e determinística na camada de interpretação do Planner. O `TermMatcher` permanece responsável apenas pelo matching textual; ele não foi transformado em parser semântico.

A heurística associa os marcadores `não`, `sem` e `evite` à ocorrência da intenção na mesma oração curta. Pontuação e conectivos como `e`, `mas`, `porém` e `apenas` delimitam ações distintas. Dessa forma, `pesquise e não execute` mantém a pesquisa e bloqueia a execução, enquanto `não pesquise e execute o teste local` bloqueia a pesquisa e mantém a execução.

## Garantias cobertas

Os testes verificam as formas negativas de pesquisa, produção e execução, além de combinações de ações positivas e negativas. Também foi incluída a regressão do caso do ChatBox: uma solicitação para explicar uma skill sem pesquisar e sem executar não gera `network.research`, `workspace.write` nem `sandbox.code`; o plano permanece analítico.

A correção não altera o RooftS 0.6, o `SkillRegistry`, os manifests, o loader de `SKILL.md` ou o RootFS.

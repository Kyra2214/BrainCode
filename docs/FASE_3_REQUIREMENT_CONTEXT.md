# BrainCode 2.3 — Fase 3: Requirement + Context

A análise do `ReasoningEngine` já usava `RequirementDiscovery` e `AssumptionManager`, mas o contexto resultante não possuía um contrato explícito para o caller seguinte. Esta fase adiciona `ContextPack` e o conecta diretamente a `ReasoningState`.

## Comportamento

`ContextPackBuilder.from(state)` preserva objetivo, intenção, domínio, requisitos, assunções, restrições, dependências e lacunas. Também registra decisões derivadas e aceita histórico relevante e erros conhecidos fornecidos pelo caller. O método `compact` remove duplicatas e limita cada seção, evitando carregar o histórico inteiro indiscriminadamente.

A propriedade `ReasoningState.contextPack` é o caminho canônico para o planner ou outro gate consumir esse contexto. A descoberta continua ocorrendo uma única vez dentro de `ReasoningEngine`; o ContextPack apenas organiza o resultado já produzido.

## Limites

O ContextPack não executa ações, não autoriza capacidades e não inventa requisitos. Lacunas críticas permanecem em `missingRequirements`, permitindo que a próxima camada produza `NEEDS_CLARIFICATION` ou `BLOCKED`.

## Validação

A suíte do módulo `:brain` passou, incluindo testes de contexto relevante, deduplicação e limite de histórico.

## Próximo módulo

A Fase 4 deve fazer o Planner receber esse resultado de reasoning/contexto e adicionar critérios de aceitação estruturados a cada passo, preservando `criterioSucesso` por compatibilidade.

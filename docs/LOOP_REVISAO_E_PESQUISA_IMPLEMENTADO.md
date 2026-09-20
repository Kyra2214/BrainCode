# Loop de revisão real e uso efetivo da pesquisa

## Escopo e entregas

Esta alteração implementa a **Entrega 1, sem IA nova**, do plano `PLANO_LOOP_REVISAO_E_PESQUISA.md`: Fases 0, 1, 2, 3 e 5. Findings de qualidade agora são convertidos em feedback explícito, aplicado ao plano antes da próxima tentativa, e a correção só é considerada válida quando o plano muda de forma observável.

A **Entrega 2** agora também está implementada no componente já existente: orçamento de tokens aproximado, prompt compacto, cache por contexto e métricas de chamadas/hits/bloqueios. O `GatewayPromptImprover` não foi substituído nem duplicado; ele continua sendo apenas o último recurso depois das regras locais e do `RevisionEngine`.

## Alterações

`FindingsRevisionFixer` substitui o marcador isolado do fixer legado como padrão do controlador. Cada finding é serializado como `revision-feedback:<código>: <mensagem>` em `assumptions` e nos parâmetros dos passos, permitindo que o `PromptGenerationExecutor` receba o contexto por `parameter.N`.

O executor separa esse feedback e o envia primeiro para a melhoria local e para o `RevisionEngine`. Somente se a pontuação local não melhorar o resultado, e havendo conta autorizada, o mesmo feedback e as evidências da pesquisa são enviados ao `GatewayPromptImprover`, com limite de tamanho e instrução de não inventar fatos.

O criador local usa sinais explícitos da pesquisa para preencher ambiente, composição e iluminação somente quando o pedido não especificou esses campos. A prioridade continua sendo do texto do usuário.

O gate pós-execução agora cria `research.unused` quando existem fontes e nenhum sinal rastreável aparece no resultado. O controlador verifica a mudança do plano por assinatura determinística, aborta saída e findings idênticos com `revision.no-progress`, registra os findings no evento `PostExecutionBlocked` e, ao atingir o limite, seleciona a tentativa com menor score de severidade em vez de entregar sempre a última.

Falhas técnicas são repetidas até duas vezes com backoff curto, sem consumir uma tentativa de revisão. Somente uma falha de qualidade avança o ciclo `REVISE → CORRIGINDO → REEXECUTANDO`.

A UI inclui `code: message` dos findings no aviso de validação.

## Validação

A validação deve ser executada com JDK 17, exigido pelos toolchains Gradle do projeto. Os módulos Android dependem de SDK Android e devem ser confirmados no CI ou em uma máquina com o SDK configurado.

## Compatibilidade

O limite de três tentativas permanece. Falhas técnicas continuam distinguíveis no evento `ExecutionFailed`; o tratamento de retry técnico separado deve ser validado e ampliado na próxima rodada caso os testes de integração indiquem essa necessidade. O `ContextRevisionFixer` permanece disponível como fallback explícito.

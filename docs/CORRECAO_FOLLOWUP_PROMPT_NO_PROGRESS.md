# Correção: follow-up de prompt devolvia o mesmo texto (`revision.no-progress`)

## Sintoma
Pedido de ajuste ("muda para fotorrealista, deserto, visão de uma plataforma de longe") após um prompt
gerado. A resposta voltava idêntica ("Ilustração digital detalhada... cenário coerente com o assunto") e o
aviso vermelho listava `requirement.missing` para fotorrealista/deserto/visão de plataforma, seguido de
`revision.no-progress`.

## Causas
1. `PromptGenerationExecutor.escalonar` descartava a melhoria local sempre que a nota heurística ficava
   abaixo de 90% e não havia IA — devolvia `criado.texto` (o prompt antigo). Com nota típica de 81%, a
   melhoria local nunca era entregue.
2. O artefato anterior chegava com o invólucro de apresentação ("Encontrei um prompt de referência...
   qualidade 81%:"), que era tratado como parte do prompt e aninhado a cada turno.
3. O `LocalPromptCreatorAgent` só sabia trocar fundo/deserto/meteoros: não havia regra para estilo
   fotorrealista nem para ponto de vista ("visão de X de longe").
4. `ReasoningEngine` extraía requisitos também do texto do artefato anterior (ex.: "fotográfico" → estilo
   "fotorrealista" falso).
5. O domínio da melhoria era classificado só pela instrução ("muda para deserto..." → TEXTO).

## Mudanças
- `PromptGenerationExecutor`: nunca descarta melhoria local que aplicou algo do pedido; último recurso
  determinístico `garantirRequisitos` (imagem/vídeo) inclui o que o usuário pediu e ainda não aparece;
  requisitos do `revision-feedback` também alimentam a tentativa seguinte; quando nada muda, a resposta
  diz isso em vez de fingir "Melhorei o prompt"; domínio considera o prompt anterior.
- `PromptEnvelope` (novo): extrai só o prompt (remove cabeçalho, nota de IA indisponível e referência de
  biblioteca). Usado por `ConversationContextEngine` e por `extrairPedidoDeMelhoria`.
- `LocalPromptCreatorAgent`: regras locais para `fotorrealista` (estilo + realismo) e ponto de vista
  (`visão/vista de X de longe/ao longe/à distância` → Composição), respeitando negação ("sem fotorrealismo").
- `ReasoningEngine`: requisitos/slots vêm só do pedido atual (texto após "Referências resolvidas:" é ignorado);
  lacunas/bloqueios continuam usando a leitura completa.

## Testes adicionados
`LocalPromptCreatorAgentTest`, `ReasoningEngineTest`, `PromptEnvelopeTest`, `PromptGenerationExecutorTest`.
Validar com JDK 17: `./gradlew :brain:test :app:testDebugUnitTest`.

## Adendo: frase real digitada
"vamos melhorar ele quero ele num deserto ao por do sol com a visão de uma plataforma de longe".
- O requisito "fotorrealista" do aviso vinha do "fotográfico" do prompt antigo (corrigido no `ReasoningEngine`).
- "ao por do sol" não tinha regra nem requisito: `LocalPromptCreatorAgent.aplicarHorarioDoDia` (ambiente + Iluminação)
  e slot de iluminação em `RequirementDiscovery`.
- `RequirementMatcher` agora ignora acentos ("por do sol" casa com "pôr do sol").

## Adendo 2: pesquisa web no follow-up + roteamento
- **Roteamento (regressão da correção anterior):** o cabeçalho "Encontrei um prompt de referência..." era, por
  acidente, o que colocava a palavra "prompt" no objetivo e roteava o follow-up para `prompt.library.write` (e para
  a pesquisa). Ao tirar o cabeçalho do artefato, o objetivo passou a não conter "prompt". `ConversationContextEngine`
  agora marca `tipo da referência: prompt` quando o artefato veio do gerador de prompts.
- **Consulta de pesquisa:** `KeywordPlanner` usa só o pedido atual (sem o texto do artefato) na consulta.
- **Uso da pesquisa na melhoria:** `LocalPromptCreatorAgent.refinarComPesquisa` aplica golden hour (pôr do sol/entardecer)
  e lente teleobjetiva (visão de longe) somente quando a própria fonte trouxe o termo. Sem pesquisa, nada muda.

## Adendo 3: gatilho para acionar a IA (API)
- `ImprovementVocabulary.pedeIA`: "melhore/melhorar ele", "faça/faz melhor", "refaça/refazer", "otimize", "aprimore",
  "reescreva", "reformule", "capriche", "de novo", "outra/nova versão", "mais detalhado". Ajuste simples ("quero ele num
  deserto") continua local e só escala para a IA se a qualidade local for insuficiente.
- Com gatilho, `PromptGenerationExecutor.escalonar(forcarIa = true)`: aplica regras locais + pesquisa e envia o resultado
  à IA (menos tokens); o retorno da IA é o entregue, e o que o usuário pediu e a IA omitiu volta de forma determinística.
  Sem IA disponível, entrega o resultado local com aviso explícito.
- Novos verbos entram no vocabulário de continuidade (`refaç`, `aprimor`, `refin`, `reescrev`, `caprich`).
- **Fiação:** `SandboxViewModel` criava o `BrainSandboxController` sem `authorizedAccountIds` (vazio). Com isso a política
  nunca autorizava conta e o `GatewayPromptImprover` jamais era chamado. Agora os providers do catálogo
  (`android:<providerId>`) são autorizados; quem responde depende da chave cadastrada.

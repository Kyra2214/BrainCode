# Correção — Ruído na síntese, vazamento de auditoria e reforço da regra do Secretário

## Contexto

Dois defeitos foram observados no ciclo de chat (Porta 1) e um terceiro ponto,
levantado durante a investigação, mostra que o comportamento documentado do
Secretário já prevê a regra desejada — mas a implementação não a cumpre em
todos os casos. Este documento cobre os três.

---

## Bug 1 — Síntese de pesquisa web devolve boilerplate em vez de resposta

**Onde:** `app/src/main/kotlin/com/sandbox/app/ResponseComposer.kt`, função
`synthesizeResearch()` (linhas ~83–100).

**Sintoma:** perguntas que disparam pesquisa web (ex.: "previsão do tempo em
Macaé") retornam texto de aviso de cookies, menus de idioma e cabeçalhos de
site, em vez de uma resposta objetiva.

**Causa raiz:** a função quebra o texto raspado em "sentenças" por pontuação
e seleciona até 4 que contenham algum termo do tópico da pergunta. Ela não
distingue conteúdo informativo de ruído de navegação — como o nome da cidade
aparece nos próprios cabeçalhos/menus da página ("Macaé Weather Forecast",
cookie banner de site de clima em Macaé etc.), esse ruído passa no filtro de
palavra-chave tão facilmente quanto o dado real.

**Correção esperada:**
- Descartar sentenças que sejam claramente boilerplate (avisos de cookies,
  listas de idiomas/menus de navegação, "login", "sign up", nomes de seções
  de UI), por padrão de forma e não só de palavra-chave.
- Priorizar sentenças com dado concreto (números, unidades, datas) quando a
  pergunta for factual/numérica.
- Reduzir agressivamente o número de sentenças aceitas quando nenhuma
  sentença "limpa" for encontrada, em vez de cair no conjunto bruto todo.

**Critério de aceite:** para a mesma pesquisa de clima que gerou o problema,
a resposta sintetizada deve conter só dado meteorológico (temperatura,
condição, cidade, fonte), sem texto de cookie/menu/idioma.

---

## Bug 2 — Relatório de auditoria interna aparece misturado à resposta do chat

**Onde:** `app/src/main/kotlin/com/sandbox/app/SandboxViewModel.kt`, função
`publishCycleStages()` (linhas ~1220–1263), bloco `ThreadEvent.Report`.

**Sintoma:** depois de qualquer resposta do chat, um bloco técnico aparece na
mesma área de conversa:
```
Resultado / revisão / readiness
status=PASS
verification=PASSED
...
evidences=...
artefatos=...
```

**Causa raiz:** `publishCycleStages()` é chamada sempre após cada ciclo e
publica esse relatório de auditoria (verification/critique/revision/readiness/
evidence) no mesmo fluxo de eventos que também recebe as mensagens do chat.
O Secretário/`ChatResponseExecutor` não participa dessa etapa — o relatório é
gerado depois que a resposta já foi aprovada, direto na camada de UI/ViewModel.

**Correção esperada:**
- `ThreadEvent.Report` deixa de ser publicado no mesmo feed do
  `ChatRole.ASSISTANT`/`ChatRole.STEP`. Deve ir para um canal de diagnóstico
  separado (painel de auditoria/debug), não para a área de conversa do
  usuário.
- Se o app quiser manter visibilidade rápida de status, expor só um indicador
  compacto (ex.: badge READY/FAILED), nunca o dump completo de evidências.

**Critério de aceite:** a área de chat mostra apenas mensagens de
`ChatRole.USER`/`ChatRole.ASSISTANT` (e steps de progresso, se mantidos);
o relatório completo só aparece em uma tela/painel de diagnóstico dedicado.

---

## Regra reforçada — Secretário deve bloquear "não sei" sem pesquisa e mandar pro agente web

**Já está documentado assim** (e é o comportamento correto pretendido):

> `docs/ARQUITETURA_ATUAL.md:159` — "Quando há `LOCAL_KNOWLEDGE_MISS` e existe
> recuperação informacional disponível, o Secretário bloqueia o fallback, o
> Orquestrador aciona `WebSearch`, o `ResearchResult` estruturado retorna à
> Conversation e somente a síntese natural volta ao Secretário para validação
> final."
>
> `docs/ESTADO_ATUAL.md:154` — mesmo fluxo: `LOCAL_KNOWLEDGE_MISS` →
> `Secretary BLOCK` → `Orchestrator` → `WebSearch` → síntese → `Secretary
> ACCEPT` → UI.

**O problema é que a implementação não cumpre o próprio contrato em dois pontos:**

### 3.1 — Classificador de "pergunta informacional" é restritivo demais

**Onde:** `brain/src/main/kotlin/com/brain/text/InformationalQuestionClassifier.kt`,
usado por `ChatResponseExecutor.kt` (~linhas 77–90) para decidir `shouldRecover`.

Hoje só considera recuperável via pesquisa uma frase que **comece** com um
conjunto fixo de palavras ("qual", "como", "o que", "explique"...) **e**
também contenha um gatilho explicativo fixo ("o que é", "como funciona",
"fale sobre"...). Qualquer pergunta informacional fora desse molde textual
(ex.: perguntas afirmativas, sem "?", com outra ordem de palavras) nunca
aciona `shouldRecover`, e a pesquisa web nem é tentada.

**Correção:** tratar como recuperável qualquer prompt em que `localMiss` seja
verdadeiro e exista `researchFallback` disponível, salvo os casos já
excluídos explicitamente (saudação, clarification, restrição `NO_WEB` da
Porta). O classificador deixa de ser a única porta de entrada para a
recuperação; vira, no máximo, um filtro de exclusão (não de inclusão).

### 3.2 — O gate do Secretário tem uma exceção que libera "não sei" sem pesquisa

**Onde:** `brain/src/main/kotlin/com/brain/secretary/TextConversationContracts.kt`,
`DeterministicSecretaryGate.evaluate()` (~linhas 49–56).

A checagem de relação semântica é pulada (`evidenceBackedException = true`)
sempre que a evidência contém `chat:conversation:local-miss` — isso permite
que o texto fixo "Não tenho conhecimento suficiente para responder..." passe
pelo gate como `ACCEPT`, mesmo quando a recuperação via pesquisa nunca foi
tentada.

**Correção:** o gate deve distinguir dois casos:
- **Local-miss com recuperação já tentada e esgotada** (research rodou e não
  trouxe resposta) → pode aceitar a mensagem honesta de "não encontrei",
  como já ocorre no caminho de `ChatResponseExecutor.kt` linha ~101–106.
- **Local-miss sem nenhuma tentativa de recuperação** (pesquisa nunca rodou,
  porque `researchFallback` existia mas não foi acionado) → o Secretário deve
  **bloquear** (`BlockReason.LOCAL_KNOWLEDGE_MISS`) e devolver o fluxo para o
  Orquestrador acionar o `WebResearchAgent`, exatamente como descrito em
  `docs/ARQUITETURA_ATUAL.md:159`. Isso exige propagar ao `ConversationResult`
  se a recuperação já foi tentada (e não apenas se está disponível), para o
  gate não reaprovar automaticamente por causa da tag `chat:conversation:local-miss`.

**Critério de aceite:** com `researchFallback` configurado, nenhuma resposta
de "não tenho conhecimento suficiente" deve chegar à UI sem que ao menos uma
tentativa de `WebResearchAgent` tenha ocorrido antes. Testar com perguntas
informacionais que hoje escapam do classificador atual (ex.: perguntas sobre
tecnologia sem a forma "o que é X").

---

## Documentação a atualizar

- **`docs/ARQUITETURA_ATUAL.md` (linha ~159)** — o texto já descreve o fluxo
  correto; adicionar uma frase explícita de que a decisão de "recuperação
  disponível" não deve depender de casamento de padrão textual da pergunta,
  e sim de `localMiss + researchFallback presente + não excluído por
  restrição de Porta`.
- **`docs/ESTADO_ATUAL.md` (linha ~154)** — atualizar a descrição do estado
  real assim que os itens 3.1 e 3.2 forem corrigidos, registrando a data e
  referenciando este documento (padrão já usado em
  `docs/ROADMAP_CANONICO.md:136`, que registra bugs corrigidos com data e
  link para `docs/LEGADO_E_DECISOES.md`).
- **`docs/LEGADO_E_DECISOES.md`** — adicionar entrada descrevendo os dois
  bugs (síntese com ruído, vazamento de auditoria no feed) e a correção do
  gate do Secretário, seguindo o mesmo formato usado para o bug da Porta 2
  já registrado ali.
- **`PRPs/porta1-chat-plano.md`** — no contrato de `chat.respond`, explicitar
  que resposta de conhecimento ausente sem tentativa de pesquisa prévia é uma
  saída inválida (deve reprovar em `ChatResponseExecutorTest`/critérios de
  aceite), não apenas "resposta não vazia com proveniência".

---

## Resumo para quem for implementar

1. Corrigir `synthesizeResearch()` para filtrar boilerplate.
2. Tirar `ThreadEvent.Report` do feed de chat; mandar para painel de diagnóstico.
3. Trocar `InformationalQuestionClassifier` de "lista de permissão" para
   "lista de exclusão", e fazer `DeterministicSecretaryGate` bloquear
   local-miss sem recuperação tentada, liberando só depois de passar pelo
   `WebResearchAgent`.
4. Atualizar `docs/ARQUITETURA_ATUAL.md`, `docs/ESTADO_ATUAL.md`,
   `docs/LEGADO_E_DECISOES.md` e `PRPs/porta1-chat-plano.md` para refletir a
   regra reforçada.

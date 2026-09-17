# Correções e melhorias — setor de Prompt (BrainCode)

Revisão do pacote `com.brain.prompt`, `PromptGenerationExecutor` e `BrainSandboxController`. Achados organizados por prioridade, com arquivo/trecho e proposta de correção.

---

## 🔴 Prioridade alta (bugs)

### 1. Outcome técnico do prompt fica preso ao sucesso do plano inteiro

**Arquivo:** `android-module/src/main/kotlin/com/sandbox/agent/BrainSandboxController.kt`
**Método:** `executeWithEvents`

```kotlin
promptOutcomeTracker?.let { tracker ->
    result.passos.forEach { passo ->
        passo.actionId?.let { actionId ->
            tracker.recordTechnicalOutcome(
                actionId = actionId,
                success = result.aprovado && passo.status == StatusPasso.APROVADO,
                cost = passo.custo,
                elapsedMs = elapsedMs
            )
        }
    }
}
```

**Problema:** `result.aprovado` exige que **todos** os passos do ciclo tenham sido `APROVADO`. O `KeywordPlanner` monta planos com mais de um passo quando o pedido combina palavras-chave (ex.: "crie um prompt e implemente o código" → `produzir` (`prompt.library.write`) → `executar` (`sandbox.code`), este último dependente do primeiro).

Se o passo `produzir` gerou o prompt com sucesso, mas o passo `executar` falhar depois por qualquer outro motivo (sandbox indisponível, erro de execução), `result.aprovado` vira `false` e o outcome técnico do prompt — que já tinha sido entregue corretamente — é registrado como **falha**. Isso contamina a `taxaSucesso` do template sem relação com a qualidade real do prompt.

**Correção proposta:**

```kotlin
success = passo.status == StatusPasso.APROVADO
```

Cada passo deve ser avaliado pelo próprio status, não pelo resultado agregado do ciclo.

---

### 2. Fluxo do chat não usa `taxaSucesso` como filtro — só como desempate

**Arquivo:** `app/src/main/kotlin/com/sandbox/app/PromptGenerationExecutor.kt`
**Trecho:** seleção de candidato em `execute()`

```kotlin
val candidato = runBlocking {
    promptLibrary.buscarPorContexto(objetivo)
        .asSequence()
        .map { it to PromptSimilarity.compatibility(objetivo, it) }
        .filter { (_, score) -> score >= MIN_COMPATIBILITY }
        .maxWithOrNull(
            compareBy<Pair<PromptTemplate, Double>> { it.second }
                .thenBy { it.first.taxaSucessoEfetiva() }
                .thenBy { it.first.amostrasObservadas }
        )
        ?.first
}
```

**Problema:** `taxaSucessoEfetiva()` só desempata entre templates igualmente compatíveis — nunca exclui um candidato. Um template que já recebeu vários 👎 continua sendo reaproveitado indefinidamente enquanto for o mais compatível disponível.

O fluxo de roadmap (`DefaultPromptGenerator`) já resolve isso corretamente:

```kotlin
private const val TAXA_SUCESSO_MINIMA_PARA_REUSO = 0.5
...
.filter { (it, score) -> it.taxaSucessoEfetiva() >= TAXA_SUCESSO_MINIMA_PARA_REUSO && score >= COMPATIBILIDADE_MINIMA }
```

**Correção proposta:** trazer a mesma trava de exclusão para `PromptGenerationExecutor`, com uma constante equivalente (`TAXA_SUCESSO_MINIMA_PARA_REUSO`).

---

## 🟠 Prioridade média (inconsistência / risco de divergência)

### 3. Dois algoritmos de compatibilidade fazendo a mesma coisa, com limiares diferentes

- `brain/src/main/kotlin/com/brain/prompt/PromptSimilarity.kt` → `compatibility()`, pesos 0.75/0.25, usado pelo chat (`PromptGenerationExecutor`), limiar `MIN_COMPATIBILITY = 0.85`.
- `brain/src/main/kotlin/com/brain/prompt/DefaultPromptGenerator.kt` → `compatibilidade()`, pesos 0.7/0.3, usado pelo fluxo de roadmap/autônomo, limiar `COMPATIBILIDADE_MINIMA = 0.5`.

**Problema:** são duas implementações paralelas do mesmo conceito. O ajuste de limiar feito no chat (0.50 → 0.85) **não se propagou** para o fluxo autônomo de roadmap — que é justamente onde um prompt mal encaixado tem mais risco, por rodar sem supervisão linha a linha.

**Correção proposta:** unificar em `PromptSimilarity.compatibility()` como única fonte, com um único limiar compartilhado (ou dois limiares nomeados explicitamente, se a diferença de rigor entre chat e roadmap for intencional — mas hoje parece só deriva histórica, não decisão de design).

---

### 4. Deduplicação duplicada entre executor e biblioteca, podendo divergir

- `PromptGenerationExecutor.saveGeneratedPrompt` faz sua própria checagem de "é duplicata?": compara `textoTemplate` bruto via `PromptSimilarity.contentSimilarity`, limiar `DUPLICATE_THRESHOLD = 0.90` local ao arquivo.
- `InMemoryPromptLibrary.salvarNovaVersao` faz **outra** checagem própria: compara `finalidade + contextoDeUso + textoTemplate` combinados, limiar `DUPLICATE_THRESHOLD = 0.90` duplicado em outro arquivo.

**Problema:** se as duas checagens discordarem sobre qual template é "o mesmo", o `id` que o executor devolve para `outcomeTracker.markUsed(actionId, id)` pode não ser o `id` que a biblioteca realmente persistiu (ela pode ter mesclado em um `similar` diferente). Resultado: o feedback do usuário (👍/👎) atualiza a taxa de sucesso de um template diferente do que foi de fato entregue.

**Correção proposta:** a lógica de dedup deveria viver só na biblioteca. Uma opção: mudar `salvarNovaVersao` para retornar o `id` final persistido (em vez de `Unit`), e o executor usar esse retorno diretamente em vez de tentar adivinhar o `id` antes de chamar.

---

## 🟡 Prioridade baixa (robustez / manutenção)

### 5. Sem poda de templates ruins

Não existe, na interface `PromptLibrary`, nenhum método para aposentar ou remover um template. Um template com `taxaSucesso` baixíssima e muitas amostras nunca desaparece — só perde prioridade no desempate de compatibilidade (e nem isso, ver item 2).

**Sugestão:** método `aposentar(templateId)` ou uma poda automática (ex.: remover/ocultar da busca templates com `amostrasObservadas >= N && taxaSucesso < limiar`).

### 6. Vazamento lento de memória no `PromptOutcomeTracker`

**Arquivo:** `brain/src/main/kotlin/com/brain/prompt/PromptLibrary.kt`

```kotlin
private val pending = ConcurrentHashMap<String, Usage>()
private val delivered = ConcurrentHashMap<String, Usage>()
```

Sem TTL nem limite de tamanho. Um `actionId` cujo feedback nunca chega (app fechado antes de curtir, plano interrompido, crash) fica para sempre em um dos dois mapas — pequeno, mas cresce ao longo de sessões de uso contínuo.

**Sugestão:** expirar entradas por tempo (ex.: descartar `pending`/`delivered` com mais de X horas) ou limitar tamanho com política de descarte do mais antigo.

### 7. Função `taxaSucessoEfetiva()` triplicada

A mesma lógica (`if (amostrasObservadas > 0) taxaSucesso else 0.5`) existe idêntica em três arquivos: `PromptGenerationExecutor.kt`, `DefaultPromptGenerator.kt` e (como constante `NEUTRAL_PRIOR = 0.50`) em `InMemoryPromptLibrary.kt`.

**Sugestão:** mover para uma extension única de `PromptTemplate`, num único arquivo (ex.: `PromptTemplate.kt` ou dentro de `PromptLibrary.kt`), para os três pontos nunca divergirem.

---

## Resumo de prioridade de implementação

| # | Item | Tipo | Risco de implementar |
|---|------|------|----------------------|
| 1 | Outcome técnico preso ao plano inteiro | Bug | Baixo — corrige comportamento errado |
| 2 | `taxaSucesso` não filtra no chat | Bug de comportamento | Baixo — já existe padrão equivalente no roadmap |
| 3 | Dois algoritmos de compatibilidade | Inconsistência | Médio — decidir se limiares devem ser iguais |
| 4 | Dedup duplicado entre executor/biblioteca | Risco de dado | Médio — mexe em assinatura de `salvarNovaVersao` |
| 5 | Sem poda de templates ruins | Melhoria | Baixo — funcionalidade nova, aditiva |
| 6 | Vazamento de memória no tracker | Robustez | Baixo — aditivo, não muda comportamento atual |
| 7 | `taxaSucessoEfetiva()` triplicada | Manutenção | Baixo — refatoração pura |

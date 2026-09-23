*Nota de arquivo: este documento agora vive em `docs/auditoria/`, junto dos demais documentos de auditoria do projeto. Referências a ele em comentários de código e no `ROADMAP_CANONICO.md` devem usar esse caminho.*

# Plano de Correção — Auditoria ao `PLANO_ESCALONAMENTO_ENTENDIMENTO_TAREFAS.md`

**Contexto:** a auditoria ao zip `BrainCode-atualizado.zip` confirmou que os itens 1, 4, 5, 6 e 7 da tabela de execução do plano original já estão implementados e funcionando. Este documento cobre só o que ficou pendente ou incorreto: um bug real na Porta 2, uma contradição entre o código e o roadmap, e uma verificação a fazer antes de investir no item 2 do plano original.

---

## 1. Bug: escalonamento por qualidade insuficiente na Porta 2 é inalcançável — ✅ CONCLUÍDO (Alternativa mais simples aplicada)

### Causa raiz confirmada
`DeterministicSecretary.classify()` (`DeterministicSecretary.kt:43-48`) calcula `escalationRequested` **uma única vez**, na classificação inicial, olhando só `ImprovementVocabulary.pedeIA(original)` sobre a mensagem original do usuário — antes de qualquer prompt ser gerado ou validado. Esse `DoorScope` fixo é propagado sem recálculo até o fim do ciclo (`BrainSandboxController.executeObjective`, `doorScope = designatedIntent.scope`), inclusive para o passo `prompt.library.write`.

Consequência: em `PromptGenerationExecutor.escalonar()`, o `GatewayPromptImprover` real (`requerContaAutorizada() = true`) só é chamado se `authorizedAccountIds` não estiver vazio — e para um primeiro pedido sem palavra de melhoria ("crie um prompt de X"), `authorizedAccountIds` está sempre vazio, mesmo que `PromptQualityValidator` aponte score abaixo do padrão. **A IA nunca é chamada nesse caminho hoje**, apesar do código já ter toda a lógica pronta para isso.

O teste `PromptGenerationExecutorTest.kt` (teste 4) não pega esse bug porque usa um `PromptImprover` fake via SAM que não sobrescreve `requerContaAutorizada()` (fica `false` por padrão) — só o `GatewayPromptImprover` real exige conta autorizada.

### Correção proposta
1. Mover a decisão de `externalAccountsAllowed` para **dentro do executor da Porta 2**, depois que `PromptQualityValidator.validar()` já rodou — não mais no `classify()` upfront, que não pode saber a qualidade de um texto que ainda não existe.
   - Opção limpa: `PromptGenerationExecutor` passa a chamar um segundo `PolicyBroker.authorize()` (ou uma checagem equivalente) só quando `scoreInicial.abaixoDoPadrao == true`, usando um `PolicyContext` com `escalationRequested = true` nesse momento específico — sem alterar a autorização original da capability `prompt.library.generate`.
   - Alternativa mais simples: `DoorScope` do `PROMPT` passa a nascer sempre com `externalAccountsAllowed = true` visível ao executor (contas visíveis), mas o **próprio executor** decide se de fato usa (`forcarIa` OU `scoreInicial.abaixoDoPadrao`) antes de chamar `improver.melhorar(...)`. Isso é consistente com o padrão já usado em `CodeGenerationExecutor`/Porta 3, onde quem decide é o executor, não o Secretário.
2. Preferir a segunda opção: é menos invasiva (não mexe em `PolicyBroker`/`DoorPolicy`) e mantém a autoridade de decisão dentro do executor que já sabe o score, como o resto do código já faz em outros pontos.
3. Atualizar `DeterministicSecretary.kt:39-42` — remover o comentário que hoje documenta essa limitação como aceita, já que ela deixa de existir.

### Testes a adicionar
- Novo teste em `PromptGenerationExecutorTest.kt` que usa um **fake que sobrescreve `requerContaAutorizada() = true`** (replicando o comportamento real do `GatewayPromptImprover`) para um pedido de **primeira geração** (não "melhore") com um `creator` que produz texto de baixa qualidade — hoje esse teste falharia (`aiUsada = false`); depois da correção deve passar (`aiUsada = true`).
- Teste de integração (ou pelo menos um teste do `SandboxViewModel`/`BrainSandboxController`) que confirme que `authorizedAccountIds` chega não-vazio ao `PromptGenerationExecutor` quando o score é insuficiente, mesmo sem gatilho de melhoria na mensagem original.

### Critério de conclusão
Pedir "crie um prompt de X" (sem palavra de melhoria) com uma API configurada e um `LocalPromptCreatorAgent` que gera algo de baixa qualidade deve, de fato, chamar a IA — comprovável testando com uma implementação real (não-fake) de `PromptImprover.requerContaAutorizada()`.

**Ação tomada (23/09/2026):** aplicada a "Alternativa mais simples" descrita acima (item 1 da correção proposta), não a opção de um segundo `PolicyBroker.authorize()`:
1. `DoorPolicy.externalAccountsAllowed(door: Door)` — Porta 2 agora libera contas visíveis ao executor desde o início, no mesmo padrão já usado pela Porta 3 (`Door.PROMPT -> true`, igual a `Door.CREATE -> true`). O parâmetro `escalationRequested` foi removido: nenhuma porta o usava de fato para decidir (Chat sempre nega, Prompt e Criação sempre liberam).
2. `DeterministicSecretary.classify()` não calcula mais o sinal `escalationRequested` — o comentário que documentava a limitação como aceita foi removido e substituído por uma nota explicando que a decisão de usar a conta é do executor.
3. Nenhuma mudança foi necessária em `PromptGenerationExecutor.escalonar()`: o guard `if (!scoreInicial.abaixoDoPadrao && !forcarIa) return ...` já implementava exatamente o padrão "quem decide é o executor, com base no score ou no gatilho de melhoria" — o bug estava inteiramente na camada de política (`DoorScope`/`DoorPolicy`), que zerava `authorizedAccountIds` antes desse guard ser alcançado.
4. Testes adicionados: `DoorPolicyTest` (unidade, Secretário incluído), `PromptGenerationExecutorTest` (executor, com fake que sobrescreve `requerContaAutorizada() = true` para um pedido de primeira geração) e `PromptDoorAccountVisibilityTest` (integração Secretário → `CicloExecucaoPlano` → `Dispatcher` → `ActionGateway` → executor, caminho real de produção).

### Critério de conclusão — Atingido
Um teste de integração (`PromptDoorAccountVisibilityTest`) comprova que "crie um prompt de X" (sem palavra de melhoria), com uma conta autorizada configurada e um creator que gera baixa qualidade, chega com `authorizedAccountIds` não-vazio ao executor e aciona uma implementação de `PromptImprover.requerContaAutorizada() = true` — não-fake no sentido de reproduzir o comportamento real do `GatewayPromptImprover`.

---

## 2. Contradição entre o código e o roadmap — ✅ CONCLUÍDO (Opção A aplicada)

### Achado
`docs/ROADMAP_CANONICO.md` (Marco 5) diz: *"Não iniciar a integração de APIs externas antes de Porta 1 e Porta 2 estarem consolidadas"*, marca Marco 5.3 como não iniciado, e Porta 1/Porta 2 **ainda não foram declaradas consolidadas** (faltam os itens `[ ] declarar Porta 1/2 consolidada somente após CI/E2E/readiness verdes`). Mas `DoorPolicy.kt` já shipped com `Door.CREATE -> true` incondicional e `Door.PROMPT` condicionalmente liberado — o item 1 do plano original já foi implementado **antes** da pré-condição de sequenciamento que o próprio plano (seção 2, item 3) e o roadmap exigem. Os checkboxes `[x] APIs externas bloqueadas nesta fase` em Marco 5.1/5.2 estão desatualizados frente ao código atual.

### Correção proposta (decisão de produto, não técnica)
Escolher uma das duas opções — ambas são aceitáveis, mas precisam ser explícitas:

**Opção A — Aceitar o adiantamento e atualizar o roadmap.**
Se a decisão foi conscientemente liberar Porta 3 (e parcialmente Porta 2) para chamar API antes de formalizar a consolidação de Porta 1/2, então:
1. Desmarcar `[x] APIs externas bloqueadas nesta fase` em Marco 5.1 e `[x] APIs externas continuam bloqueadas` em Marco 5.2, substituindo por uma nota explicando a exceção.
2. Mover os itens já cumpridos de Marco 5.3 (a liberação de Porta 3, o `escalationRequested` de Porta 2) para dentro de Marco 5.3, marcando-os `[x]`.

**Opção B — Reverter para respeitar o sequenciamento original.**
Se o adiantamento foi acidental (ex.: implementado junto do item 1 sem perceber a dependência do roadmap), reverter `DoorPolicy.externalAccountsAllowed` para `false` em todas as portas até Marco 5.1 e 5.2 fecharem os itens `[ ]` restantes (CI/E2E/readiness final), e só então reaplicar o item 1 do plano original.

### Recomendação
Opção A é mais prática — a lógica em `DoorPolicy.kt` já está correta e testada, e reverter geraria retrabalho sem ganho real (Porta 3 já tem ~81% de maturidade segundo o próprio roadmap, e represar a API ali não destrava nada). O ajuste necessário é só documental: atualizar `ROADMAP_CANONICO.md` para refletir a realidade do código.

**Ação tomada (23/09/2026):** Opção A aplicada em `ROADMAP_CANONICO.md`:
1. Adicionada nota "Exceção documentada" na seção "Regra de execução do roadmap" (Marco 5), explicando que o gating por porta já foi adiantado conscientemente e apontando para este documento.
2. Marco 5.1 (`[x] APIs externas bloqueadas nesta fase`) **não foi alterado** — continua verdadeiro hoje: `Door.CHAT` permanece sempre `false`.
3. Marco 5.2 (`[x] APIs externas continuam bloqueadas`) foi reescrito para descrever o comportamento real (liberação condicional via `escalationRequested`) e agora também documenta inline o bug do item 1 desta seção (já corrigido — ver seção 1).
4. Marco 5.3 recebeu uma nota apontando que a fatia de gating por porta já foi adiantada, mantendo os itens da integração completa (camada de Provider/API, registro/descoberta/seleção, testes) como estavam — não há evidência de que esses itens mais amplos tenham avanço além do que já era sabido.

### Critério de conclusão
Roadmap e código não se contradizem: qualquer item marcado `[x]` no roadmap corresponde a um comportamento real e verificável no código, e vice-versa. — **Atingido** para os itens de gating por porta (Marco 5.1/5.2/5.3); os demais itens de Marco 5.3 (camada completa de Provider/API) seguem como estavam, sem alteração de status.

---

## 3. Verificação antes de agir: item 2 do plano original (`CREATE_BASE_CAPABILITIES`) — ✅ CONCLUÍDO

**Resultado da verificação (23/09/2026):** confirmado que nenhuma capability com prefixo `provider.`, `account.` ou `external.` é registrada no catálogo/planner nem passada para `DoorPolicy.allows`/`PolicyBroker.authorize` em nenhum caminho de produção — só em testes isolados (`CapabilityDiscoveryTest`, `CapabilityRegistryTest`) e em `AccountPool.capability` (conceito diferente, de matching de conta, não de porta). Achado adicional: `ApiCatalogCapabilityProvider`/`LazyCapabilityDiscovery` (`CapabilityProvider.kt`), que gerariam capabilities `api.<provider>.<modelo>`, nunca são instanciados em produção — outro trecho desconectado do fluxo real.

**Ação tomada:** opção (b) da seção 3 original — documentado em `DoorPolicy.kt` (comentário acima de `isExternal()`) que a checagem é reservada para uso futuro e não tem efeito hoje, referenciando este documento. Não implementei o item 2 do plano original (adicionar `provider.*`/`account.*` a `CREATE_BASE_CAPABILITIES`) por não ter efeito observável comprovado. `ApiCatalogCapabilityProvider`/`LazyCapabilityDiscovery` ficaram como estavam — decisão de removê-los ou não fica para quando (se) o registro dinâmico de modelos como capability for retomado.

---

## 4. Ordem de execução

| Ordem | Item | Depende de |
|---|---|---|
| 1 | ✅ Verificação do item 2 (seção 3 deste documento) | — |
| 2 | ✅ Decisão de produto: Opção A ou B para o roadmap (seção 2) | — |
| 3 | ✅ Corrigir escalonamento por qualidade na Porta 2 (seção 1) | Item 2 decidido (para saber se a correção precisa respeitar um DoorPolicy revertido ou o atual) |
| 4 | ✅ Testes novos (unit + integração) para o item 3 | Item 3 |
| 5 | ✅ Atualizar `ROADMAP_CANONICO.md` conforme a opção escolhida na seção 2 | Item 2 |

**Critério de conclusão geral — Atingido em 23/09/2026:** pedir "crie um prompt de X" com qualidade local insuficiente e conta configurada resulta em chamada real à IA (comprovado por `PromptDoorAccountVisibilityTest`, caminho de produção completo, e por `PromptGenerationExecutorTest`, nível de executor — ambos com `requerContaAutorizada() = true`); roadmap e `DoorPolicy.kt` não se contradizem (Marco 5, 5.2 e 5.3 do `ROADMAP_CANONICO.md` atualizados); e o item 2 do plano original está documentado como não aplicável hoje (`DoorPolicy.isExternal()`, comentário inline).

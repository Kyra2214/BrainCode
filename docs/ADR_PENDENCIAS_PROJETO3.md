# ADRs das pendências do projeto3

## T-601 — Scripts e hooks externos

**Decisão:** habilitar somente hooks explicitamente declarados, através de `SafeSkillResourceExecutor` e `RooftsSkillCatalog.executeDeclaredHook`. O caminho não usa shell, exige allowlist de executáveis, workspace existente, timeout máximo de 60s, saída limitada a 1 MiB, rede negada e permissões vazias. Hooks não declarados, com permissão ou com rede são rejeitados.

**Consequência:** existe execução controlada para CI/desenvolvimento, mas não há execução automática por descoberta, download, aprovação de permissão ou habilitação de Skill.

## T-602 — Aprovação automática de permissões

**Decisão:** manter aprovação explícita no `PolicyBroker`. Nenhum texto, trigger, assinatura ou manifest pode autoaprovar `workspace.write`, rede, credenciais ou efeitos externos.

**Consequência:** workflows e Skills podem ser descobertos e avaliados sem ganhar autoridade implícita.

## T-603 — Grader semântico

**Decisão:** o avaliador determinístico local é o gate obrigatório. Um grader semântico externo fica opcional e restrito a CI, sem provider, custo ou credencial no app.

**Consequência:** a avaliação atual é reproduzível offline e não abre uma integração de produção.

## T-704 — Conceitos do OpenClaw

| Conceito | Decisão | Aplicação |
|---|---|---|
| Detecção de loop de ferramentas | E | referência para futura auditoria de limites; não alterar runtime sem corpus |
| Compactação/poda de contexto | E | referência para o limite de contexto já existente |
| Reset de sessão | E | referência para lifecycle; não introduzir reset implícito |
| Perfis de tools | E | equivalente seguro é a capability/policy existente |
| Limites de concorrência | B | já coberto por `maxParallelism` e leases do WorkflowEngine |

## T-705 — Paper e listas de links

**Decisão:** o paper de treinamento neural é F para o runtime; as quatro listas de links são E, preservadas como pesquisa. Nenhum link ou dependência foi importado automaticamente. Cada referência futura exigirá auditoria própria, licença e decisão A–F.

## T-701/T-702/T-703 — Prompt library sem licença comprovada

**Decisão:** manter os documentos como referência E e não copiar prompts de origem não identificada. A biblioteca operacional deve receber somente templates originais do BrainCode ou materiais com licença verificável. Não há base para atribuir licença ao PDF apenas por seu nome.

# Fase 11 — Auditoria de órfãos secundários

## Escopo

Esta fase foi executada após a publicação da Fase 10 (`b3f9ebb`). Conforme o plano, ela trata os itens secundários como triagem arquitetural: não remove, não liga automaticamente e não altera comportamento de produto.

## Método

Foi feita busca textual em todo o repositório, excluindo artefatos de build e o diretório local de artifacts, seguida de inspeção das implementações, testes, documentação, comandos operacionais, navegação e branches condicionais relacionados.

A navegação principal continua sendo `MainActivity -> SandboxMobileApp -> ThreadScreen/SettingsScreen`. Não foi encontrado `NavHost`, `NavController`, rota selada/enumerada ou feature flag que instancie os candidatos secundários.

## Resultados

| Símbolo | Resultado da busca | Classificação | Decisão nesta fase |
|---|---|---|---|
| `clearChat` | Declaração em `SandboxViewModel`; nenhum caller encontrado | API de UI sem controle dedicado | Manter; avaliar botão de limpar na Thread em fase de produto. |
| `clearTerminal` | Declaração em `SandboxViewModel`; nenhum caller encontrado | API de terminal sem controle dedicado | Manter; avaliar controle na UI de execução em fase própria. |
| `runSecurityRegression` | Declaração em `SandboxPlatform`; documentação e backlog mencionam o caminho, mas nenhum caller de código foi encontrado | Capability preparada; a UI de Security usa avaliação direta | Manter; decidir se deve substituir/complementar o comando `/security`. |
| `securityCorpusDigest` | Declaração em `SandboxPlatform`; nenhum caller de código encontrado | Métrica/diagnóstico de regressão | Manter; ligar a relatório ou CI somente após decisão de contrato. |
| `importRemotePluginSnapshot` | Declaração em `SandboxPlatform`; documentação descreve o fluxo, sem caller de código encontrado | Capability de importação explícita | Manter; requer decisão de produto e gatilho em `PluginsScreen`. |
| `confirmKnowledge` | Declaração em `BrainApiGateway`; nenhum caller encontrado | Feedback de conhecimento sem UI | Manter; requer interação explícita do usuário. |
| `correctKnowledge` | Declaração em `BrainApiGateway`; nenhum caller encontrado | Correção de conhecimento sem UI | Manter; requer interação explícita do usuário. |
| `Services.restart` | Declaração e chamada interna de `stop`/`start`; nenhum caller externo encontrado | Recovery API | Manter; decidir política de recuperação antes de expor. |
| `AuthorizedCapabilityExecutor` | Classe instanciada no teste `AuthorizedCapabilityExecutorTest`; nenhuma instanciação produtiva encontrada | Ponto de extensão testado, não órfão absoluto | Manter; uso produtivo deve seguir o desenho de PolicyBroker. |
| `PolicyGatedExecutor` | Declaração sem instanciação encontrada na busca atual | Ponte de policy preparada | Manter; não remover sem confirmar todos os pontos de composição futuros. |

## Evidências importantes

A documentação existente confirma intenção de uso para alguns itens, mas intenção documentada não equivale a caller ativo. Em particular:

- `docs/PLUGIN_CATALOG.md` descreve importação de snapshots remotos, mas não há gatilho de código encontrado nesta rodada.
- `docs/BACKLOG_OFFLINE_ITEM_5.md` descreve o fluxo de regressão de segurança e o corpus como backlog operacional.
- `docs/MAPA_INTEGRACAO_2026-09-13.md` registra que o caminho principal de Security ainda chama avaliação direta com lista vazia, portanto não foi alterado nesta fase.
- `AuthorizedCapabilityExecutor` possui teste unitário e documentação de decisão de segurança; por isso não deve ser classificado como código descartável apenas por ausência de instanciação produtiva.

## Conclusão

Os itens permanecem como APIs/capabilities preparadas ou funcionalidades sem superfície de produto. A auditoria não encontrou navegação oculta, branch condicional ou teste de UI que exigisse remoção ou ativação imediata. Nenhum arquivo de implementação foi alterado nesta fase; somente este relatório foi adicionado.

A próxima decisão deve ser de produto/arquitetura, caso a caso:

1. adicionar controles de limpar chat/terminal;
2. definir o contrato de regressão Security e sua integração ao `/security` ou CI;
3. definir o fluxo seguro de importação de snapshots remotos;
4. criar feedback explícito de conhecimento;
5. definir recovery de serviços;
6. compor `PolicyGatedExecutor`/`AuthorizedCapabilityExecutor` em pontos produtivos sem duplicar gates.

## Ordem de validação

A Fase 10 já foi compilada e empacotada localmente antes da publicação. Como esta Fase 11 é somente documental, o CI após os dois commits será a validação final integrada de testes, assemble debug e lint.

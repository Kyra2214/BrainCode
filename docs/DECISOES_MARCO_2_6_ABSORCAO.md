# Decisões do Marco 2.6 — absorção do projeto3

**Data:** 2026-09-23  
**Projeto:** BrainCode  
**Regra aplicada:** AUDITAR → LICENÇA → CÓDIGO REAL → TESTES → SEGURANÇA → COMPATIBILIDADE → DECISÃO → INCORPORAR → VALIDAR → PROVENIÊNCIA.

## Legenda

| Código | Decisão |
|---|---|
| A | importação integral |
| B | módulo/componente completo |
| C | componente externo, CLI ou processo |
| D | provider |
| E | referência arquitetural ou documental, sem incorporação executável |
| F | descartado para o runtime do BrainCode |

## Tabela de decisões

| Fonte | Decisão | Escopo aprovado | Consequência e evidência |
|---|---|---|---|
| `addyosmani/agent-skills`, tag `0.6.10`, SHA `c004a74784a08295d52749b04cda634125b9a581` | **A — conteúdo preservado com adapter** | Empacotar os assets Roofts 0.6; integrar apenas contrato, catálogo lazy, seleção, ativação pendente, bridge desabilitado e avaliação determinística | Caller em `CodeGenerationExecutor`/`BrainIntegrationFacade`; testes `RooftsSkillSelectorTest`, `RooftsSkillActivationTest`, `SkillRegistryTest` e `RooftsSkillEvaluationTest`; notices em `THIRD_PARTY_NOTICES.md` |
| `nikilster/clawflows` | **E — referência arquitetural** | Usar o formato `WORKFLOW.md`, precedência custom/community e ideias de catálogo como referência; não copiar CLI, symlinks ou runtime | Implementação é nativa em `WorkflowDocument.kt`; testes `WorkflowDocumentTest` e `WorkflowInfrastructureTest`; nenhuma dependência do projeto externo |
| `agentic-coding-prompt-library.pdf` | **E — referência, licença não comprovada** | Não copiar prompts até localizar licença e titularidade; permitir apenas análise documental | T-701 permanece gate para qualquer seed; nenhum prompt do PDF foi incorporado |
| `agentic-coding-prompt-library.docx` | **E — referência, licença não comprovada** | Mesmo tratamento do PDF correspondente; formato alternativo não cria autorização de cópia | T-701 permanece gate; nenhum conteúdo foi adicionado à biblioteca |
| `openclaw-cheatsheet.pdf` | **E — referência arquitetural** | Extrair e classificar conceitos de loop de ferramentas, contexto, sessão e concorrência; sem código ou runtime importado | T-704; decisões individuais devem preceder qualquer implementação |
| `NN training research paper.pdf` | **F — descartado para o runtime** | Não se aplica ao contrato operacional do BrainCode; pode permanecer arquivado como histórico | T-705; nenhuma dependência ou código derivado |
| `🐙 GitHub Repos.pdf` | **E — lista de referências** | Preservar como material de pesquisa; cada item só pode virar nova fonte após auditoria própria | T-705; não há importação executável |
| `🚀 Getting Started.pdf` | **E — lista de referências** | Preservar como material de pesquisa; não é documentação operacional canônica do app | T-705; não há importação executável |
| `🛠 Tools & Libraries.pdf` | **E — lista de referências** | Preservar como material de pesquisa; não adicionar dependências sem decisão própria | T-705; não há importação executável |
| `🧠 Best Practices.pdf` | **E — lista de referências** | Preservar como material de pesquisa; práticas só entram via ADR e testes do BrainCode | T-705; não há importação executável |

## Decisões de execução do plano

| ID | Decisão aplicada | Motivo |
|---|---|---|
| D1 | **A — corpo do workflow é enviado como instrução ao gateway sob Policy** | Mantém o corpo como dado não confiável; `WorkflowEngine` exige autorização, idempotency key, retry, timeout e lease |
| D2 | **Slash command primeiro** | Menor superfície de UI; a tela própria pode ser uma etapa posterior |
| D3 | **Scheduler somente com o app aberto** | Evita introduzir WorkManager/background antes de medir consumo e política do Sandbox |
| D4 | **Não decidir por suposição** | A assinatura Ed25519 válida e as rejeições estão cobertas na JVM; a disponibilidade por API Android ainda depende de T-501 em emuladores API 26 e 33 |
| D5 | **E até T-701** | A licença do PDF de prompts não foi identificada; nenhum prompt foi copiado ou incorporado |

Nenhuma fonte marcada como **E** ou **F** deve gerar código, dependência, provider, download, instalação ou seed executável sem nova decisão registrada.

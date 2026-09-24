# Classificação de risco da execução de código

## Decisão atual

A partir do commit `8b4137e`, o projeto classifica os dois pontos abaixo como `RiskClass.LOW`, por decisão de produto:

| Componente | Capability | Classificação atual |
|---|---|---|
| `KeywordFunctionSplitter` | `sandbox.code` | `LOW` |
| `BuiltInAgentDefinitions.codeAgent()` | `agent.code` | `LOW` |

A alteração foi aplicada em:

- `brain/src/main/kotlin/com/brain/planner/KeywordPlanner.kt`;
- `brain/src/main/kotlin/com/brain/capability/BuiltInAgentDefinitions.kt`.

## O que essa classificação significa

`RiskClass` é um atributo usado pelo planejamento, descoberta e avaliação de policy. Ao declarar `LOW`, o sistema trata essas capacidades como de menor risco relativo dentro da taxonomia do Brain.

Essa classificação **não significa** que o código executado seja seguro, confiável ou livre de efeitos colaterais. Também não substitui:

- Sandbox e limites de filesystem;
- controle de rede;
- limites de CPU, memória, processos e descritores;
- validação de comandos;
- credenciais e escopos;
- auditoria;
- aprovação para efeitos externos;
- proteção contra dados não confiáveis e prompt injection.

## Efeito na policy

O `PolicyBroker` usa a classe de risco para decisões como exigência de Sandbox, aprovação para dados restritos e tratamento de ações de produção. Portanto, a mudança de `HIGH` para `LOW` pode reduzir algumas barreiras condicionadas exclusivamente a `RiskClass`.

O runtime deve continuar aplicando as proteções concretas independentemente da classificação. Em particular, a capability `sandbox.code` deve continuar limitada ao ambiente Sandbox, e `agent.code` deve continuar sendo um agente bounded, com ferramentas e escopos explícitos.

## Regra de interpretação

A classificação `LOW` deve ser lida como:

> “O produto escolheu não tratar esta capability como HIGH na taxonomia declarativa atual; a execução ainda exige os controles operacionais do Sandbox e da Policy.”

Ela não deve ser usada para concluir que:

> “qualquer execução de código é segura” ou “nenhuma aprovação é necessária em todos os contextos”.

## Disponibilidade dinâmica de plugins

A disponibilidade do catálogo não é mais um snapshot congelado da preparação inicial. O `PluginCatalogCapabilityProvider` consulta o `statusCache` vivo, e o `BrainSandboxController.refreshCapabilities()` atualiza as definições existentes no `CapabilityRegistry` após refresh, instalação, remoção ou rollback de componentes.

Assim, um plugin instalado durante a sessão pode passar a ser descoberto como `AVAILABLE`, enquanto um plugin removido deixa de ser oferecido pelo discovery. A atualização altera somente metadados de disponibilidade; não concede novas permissões ao actor nem substitui a autorização do `PolicyBroker`.

## Revisão futura

Essa decisão deve ser revisitada se o CodeAgent passar a:

- executar fora do Sandbox;
- acessar rede ou credenciais por padrão;
- alterar produção;
- publicar ou excluir dados;
- operar sobre dados classificados como restritos;
- receber novas ferramentas com efeitos externos.

Nesses casos, a classificação, as permissões e os fluxos de aprovação precisam ser revisados em conjunto. A classificação declarativa nunca deve ser usada para contornar um controle operacional obrigatório.

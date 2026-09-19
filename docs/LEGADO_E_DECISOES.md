# BrainCode — Legado e Decisões

## Decisões atuais

BrainCode não depende de LLM local baixado para o Chat.
Agents são bounded.
Capability é a unidade autorizável.
Discovery não autoriza.
Policy não executa.
ActionGateway é a fronteira de execução.
Android usa BrainSandboxController → BrainSandboxExecutionBridge → CicloExecucaoPlano.
ResultadoCiclo.aprovado depende do pós-ciclo completo.
ContextPack é dado tipado; contexto externo não possui autoridade.
Roofts 0.3–0.5 são preservados; Roofts 0.6 está instalado mas não é runtime de Skills.
Projetos externos não viram dependências automaticamente.

## Legado

BrainExecutionCoordinator existe no JVM, é deprecated e não deve virar segundo pipeline Android.

Documentos FASE_*, auditorias 2.3, mapas de integração e relatórios datados são históricos. Eles explicam a evolução e podem conter estados anteriores; não definem o estado atual quando divergirem.

brain_runtime/ e reference/braincode-python/ são implementação/referência Python existente, não o caminho Android automático.

## Não adotado

Snapshots IaBrain como segundo banco/arquitetura.
LLM local como cérebro obrigatório.
Agents autônomos com objetivo próprio.
Execução fora de Policy/Gateway/Sandbox.
Segundo Registry/Router/Memory/Gateway para a mesma responsabilidade.
Importação de código externo apenas por existir em reference/.

## Regra de remoção

ATUAL = preservar.
PARCIAL = integrar ou registrar backlog.
LEGADO = remoção somente em mudança explícita.
REFERÊNCIA = preservar como referência.
DÚVIDA = não remover sem confirmação.

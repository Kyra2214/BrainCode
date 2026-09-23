# Atualização do BrainCode — últimas 9 horas

**Data de referência:** 23 de setembro de 2026, em UTC  
**Intervalo analisado:** commits a partir de `2026-09-22 01:29 UTC` até `2026-09-23 10:29 UTC`  
**HEAD analisado:** `19fa294` (`fix: keep no-arg capabilities valid during revision`)

## Resumo executivo

Foram encontrados **cinco commits** no intervalo analisado. O conjunto consolida a Fase 12 no caminho Android, reforça a execução com conta autorizada, preserva o resultado da integração entre Brain e Android e fecha o fluxo de evidências da conversa. Também foram adicionados ou ajustados testes para rastreabilidade de execução, fallback de conta, despacho de planos, gate pós-execução e visibilidade de contas em prompts.

A validação remota do CI `35847234728` confirmou que o código compila, o lint passa e o APK é publicado. Nos relatórios unitários exportados, **449 de 450 testes passaram**. A única falha ocorreu no teste de rastreabilidade do `BrainSandboxController`, porque o binário `sandbox-health` não estava disponível no diretório temporário do agente de teste. Essa falha é de ambiente/fixture do teste e não impediu a geração do APK.

## Commits analisados

| Commit | Data UTC | Alteração documentada |
|---|---:|---|
| `19fa294` | 05:52 | Mantém capabilities sem argumentos válidos durante revisão e corrige o caso de retry correspondente. |
| `92609e0` | 03:48 | Restaura o resultado da integração e o fluxo de evidências da conversa no ciclo de execução. |
| `e1061c0` | 03:33 | Move o teste de visibilidade de conta do prompt para o módulo `app`, onde o comportamento é exercitado. |
| `c2de2a8` | 03:28 | Alinha a expectativa de escopo de conta do Secretário com o contrato atual. |
| `7881478` | 03:18 | Sincroniza a Fase 12, incluindo rastreabilidade de execução, gate pós-execução, fallback de conta, Roofts Skill, instalador confiável e testes associados. |

## Mudanças funcionais consolidadas

### Execução e rastreabilidade

O ciclo Android agora preserva a relação entre plano, capability, política, execução e evidência. `BrainSandboxController`, `CicloExecucaoPlano` e `PostExecutionGate` foram ajustados para que o resultado da integração continue disponível depois da execução e para que o trace contenha os dados necessários à auditoria do ciclo.

A execução também passou a tratar corretamente capabilities sem argumentos durante a revisão. Esse caso não deve ser descartado apenas por não possuir parâmetros, desde que a capability tenha sido autorizada pelo plano.

### Conta autorizada e despacho

O fluxo de conta foi reforçado no dispatcher e no caminho Android. O plano pode usar fallback de conta quando o contexto exige uma conta autorizada, mas a seleção continua limitada ao escopo definido pelo contrato e pela Policy. O teste de visibilidade de conta foi colocado no módulo `app` para validar a fronteira efetivamente usada pelo cliente Android.

### Conversa e evidências

O resultado produzido pela integração Brain/Android volta a ser propagado até a camada que compõe a resposta. O fluxo de evidências da conversa permanece explícito e não deve ser substituído por texto bruto ou por um resultado intermediário sem validação. O `PostExecutionGate` continua sendo o ponto de verificação antes da apresentação à UI.

### Skills e instalação confiável

A sincronização da Fase 12 acrescentou a seleção de Roofts Skill e o executor de instalação confiável. Esses componentes permanecem subordinados à capability, à Policy e ao Gateway; a descoberta de uma skill ou de uma ferramenta não equivale a autorização para executá-la.

## Testes incluídos ou ajustados

Os commits analisados adicionaram ou alteraram testes para os seguintes contratos:

- `BrainSandboxControllerExecutionTraceTest`: verifica o trace do ciclo com TASK, CAPABILITY, POLICY e EVIDENCE.
- `CicloExecucaoPlanoAccountFallbackTest`: verifica o fallback e o escopo de contas autorizadas.
- `CicloExecucaoPlanoDispatcherTest`: verifica o despacho do plano e a entrega do resultado.
- `PostExecutionGateUnificationTest`: verifica a unificação da verificação pós-execução.
- `PromptDoorAccountVisibilityTest`: verifica a visibilidade de contas no fluxo de prompts.
- `DispatcherTest`: cobre despacho, autorização e fronteiras de execução no núcleo.
- `EventStoreTraceSinkTest`: verifica a persistência e a reconstrução de eventos por `traceId`.
- `RooftsSkillSelectorTest`: verifica seleção de skill, capability e casos não elegíveis.
- `RooftsSkillLoaderTest` e `TrustedInstallerExecutorTest`: cobrem carregamento de skill e instalação confiável no Android.

## Resultado da validação no CI

A execução foi disparada manualmente na branch `main` no workflow **CI**, execução [`35847234728`](https://github.com/Kyra2214/BrainCode/actions/runs/35847234728), usando o commit `19fa294`.

| Verificação | Resultado | Evidência |
|---|---|---|
| `:brain:test` | **360 testes aprovados; 0 falhas; 0 erros; 0 ignorados** | Relatórios XML do artefato de testes |
| `:android-module:testDebugUnitTest` | **89 aprovados; 1 falha; 0 erros; 0 ignorados** | Relatórios XML do artefato de testes |
| `:app:testDebugUnitTest` | Executado no comando agregado do CI; não houve caso adicional no XML exportado | Log e artefato de relatórios do CI |
| `:app:assembleDebug` | **Aprovado** | Artefato `app-debug.apk` publicado |
| `:app:lintDebug` | **Aprovado** | Etapa de lint do CI |
| Upload do APK | **Aprovado** | Artefato `BrainCode-debug-apk-19fa294...` |
| Status final do workflow | **Falhou por preservação da falha unitária** | Etapa `Preserve test failure status` |

### Falha conhecida

O caso falho foi:

```text
com.sandbox.agent.BrainSandboxControllerExecutionTraceTest
ciclo real via BrainSandboxController produz trace para TASK, CAPABILITY, POLICY e EVIDENCE
```

A causa registrada no relatório é:

```text
Cannot run program "sandbox-health": error=2, No such file or directory
```

O teste tentou iniciar `sandbox-health` dentro de um workspace temporário, mas o agente não estava registrado ou o executável não havia sido instalado nesse ambiente. O relatório também registra `contract.missing` e `agent.unavailable`. A correção necessária é tornar a fixture do agente disponível no ambiente do teste ou ajustar o harness para registrar essa capability antes da execução. Não se deve marcar esse teste como aprovado sem essa evidência.

## Estado documental após esta atualização

Esta página registra o delta das últimas 9 horas. Os documentos canônicos continuam sendo `docs/ARQUITETURA_ATUAL.md`, `docs/ESTADO_ATUAL.md`, `docs/ROADMAP_CANONICO.md` e `docs/LEGADO_E_DECISOES.md`. A existência do APK e a aprovação do build não substituem a correção da falha do teste de `sandbox-health` nem uma validação UI E2E no commit atual.

## Referências

[1]: https://github.com/Kyra2214/BrainCode/commits/main "Histórico de commits do BrainCode"
[2]: https://github.com/Kyra2214/BrainCode/actions/runs/35847234728 "Execução CI 35847234728 do BrainCode"
[3]: https://github.com/Kyra2214/BrainCode/tree/main/docs "Documentação do BrainCode"

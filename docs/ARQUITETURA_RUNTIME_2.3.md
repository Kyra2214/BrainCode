# Arquitetura runtime BrainCode 2.3

## Fonte única de execução Android

`CicloExecucaoPlano` é o caminho Android canônico:

```text
UI/ViewModel
  -> BrainSandboxController
  -> Reasoning/Planner/Gates
  -> BrainSandboxExecutionBridge
  -> CicloExecucaoPlano
  -> Dispatcher
  -> ActionGateway
  -> ActionExecutor
  -> Sandbox/provider
```

`BrainExecutionCoordinator` não possui caller de produção identificado no Android. Ele permanece apenas como infraestrutura experimental/testada até uma decisão explícita de consolidação. Não deve ser instanciado pelo app nem evoluir como um segundo pipeline.

A regra de manutenção é: **qualquer comportamento novo do runtime Android entra no `CicloExecucaoPlano` ou em uma etapa chamada por ele; não se cria outro orquestrador.**

## Pós-execução obrigatório

Toda execução autorizada deve retornar pelo pós-ciclo antes de ser apresentada como concluída:

```text
Execution
  -> Evidence
  -> Verification
  -> UniversalCritic
  -> DoubtDrivenReview (quando houver finding)
  -> FixVerifyLearn (quando revisão exigir correção)
  -> Readiness
  -> ValidatedLearning
  -> Final Response
```

A execução técnica não equivale à conclusão comportamental. `ResultadoCiclo.aprovado` deve considerar o resultado de `ResultadoPosExecucao`, e o controller emite evento próprio para verification, critic, revision, readiness e learning.

## Fronteira do BrainApiGateway

`BrainApiGateway.complete()` é um provider interno de IA, não uma entrada alternativa do chat. Os únicos callers de produção são:

- `PromptGenerationExecutor`, por meio de `GatewayPromptImprover`;
- `CodeGenerationExecutor`.

Esses executores são registrados como capability executors no `BrainSandboxController` e são despachados pelo `Dispatcher`/`ActionGateway`, depois da autorização. Portanto, o gateway não deve ser chamado pelo `SandboxViewModel`, pela UI ou por um fallback de resposta direta.

Qualquer novo caller direto é uma violação arquitetural e deve ser substituído por uma capability submetida ao mesmo ciclo.

## Memória

A taxonomia oficial é:

```text
Memory
├── working/context
│   └── ReasoningState, ContextPack, TaskState durante o run
├── experience
│   └── episódios, tentativas, falhas e outcomes operacionais
└── knowledge
    ├── evidence: fontes e artefatos observados
    ├── semantic: fatos validados
    └── procedural: procedimentos validados
```

`LayeredMemory` é o contrato canônico para knowledge/evidence/procedure. `ExperienceMemory`/`FileExperienceMemory` permanece um adapter de experiência operacional legado até ser migrado para uma camada explícita de experience. Nenhuma evidência de pesquisa deve ser promovida a fato/procedimento sem verification, critic e readiness.

## Observabilidade

As quatro categorias são distintas, mas devem compartilhar `runId`, `taskId` e `traceId`:

```text
Observability
├── event
│   └── fatos de domínio e transições persistidas
├── audit
│   └── decisão de autorização e tentativa de capability
├── execution trace
│   └── comando, duração, stdout/stderr e resultado técnico
└── behavior trace
    └── reasoning, gates, critic, revision, readiness e learning
```

`BrainEvent`, `ActionAuditLog`, `ExecutionTrace` e `BehaviorTrace` não são quatro cérebros. São adapters especializados sobre o mesmo run. A correlação deve ser preservada e nenhum adapter pode conceder autorização por conta própria.

## PlanningGate

Um critério só é aceito quando possui:

```text
criterion
  -> verification method
  -> verification check
  -> evidence id
  -> PASS/FAIL
```

O `PlanningGate` rejeita critérios sem método de verificação. O resultado padrão de `PassoPlano` usa `evidence:step-result`; a etapa pós-execução materializa `VerificationCheck` e associa cada check à evidência do passo.

## Status de implementação

A documentação não declara a 2.3 pronta enquanto o CI, as jornadas E2E obrigatórias e a integração completa de revisão/correção/readiness não estiverem verdes. O Roofts 0.6 está fora deste escopo: foi validado e não deve ser modificado nesta fase.

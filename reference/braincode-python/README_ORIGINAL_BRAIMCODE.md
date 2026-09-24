# Braim — runtime de referência

O Braim é um runtime experimental para execução de tarefas com Policy, approval, eventos auditáveis, Sandbox, workflows, memória, routing de providers e gates de QA. A implementação executável atual está em `brain_runtime/` e usa Python 3 com a biblioteca padrão.

> **Estado de segurança:** o projeto possui hardening significativo e 112 testes aprovados, mas ainda depende de infraestrutura do host para isolamento OS-level completo. Não deve ser interpretado como container ou ambiente de produção isolado sem uma implantação adequada.

## Executar testes

```bash
python3 -m unittest discover -s tests -q
```

A validação atual inclui contratos formais, policy/approval, hash chain e recovery, sandbox, workflows, APIs, skills, memória, observabilidade, delivery, Project Intelligence, Readiness Gate e integração E2E.

## Runtime e módulos

| Módulo | Responsabilidade |
|---|---|
| `brain_runtime/pipeline.py` | Secretary, research, planner, policy, router, dispatch, critic, correction, retry e learning |
| `brain_runtime/events.py` | EventStore append-only com hash chain, redaction, replay, rotação e retenção |
| `brain_runtime/sandbox.py` | Execução allowlisted, limites, timeout, cancelamento e artefatos |
| `brain_runtime/workflows.py` | Workflows persistentes com retry, leases, cancelamento e compensação |
| `brain_runtime/apis.py` | Catálogo e seleção de providers com quota, cooldown e fallback equivalente |
| `brain_runtime/skills.py` | Registry de skills com licença, provenance, assinatura e revogação |
| `brain_runtime/memory.py` e `learning.py` | Memória local, learning records e bridge de execução |
| `brain_runtime/project_intelligence.py` | Scan arquitetural e contexto `.projectbrain/` |
| `brain_runtime/readiness.py` | Gate de implementação, testes, QA, segurança, arquitetura, regressão e release |
| `brain_runtime/release_intelligence.py` | Comparação de commits/releases e heurísticas de regressão |
| `brain_runtime/evidence.py` | Claims, evidências, estado externo, confiança e decisão |
| `brain_runtime/context_pack.py` | Contexto estruturado para Planner/agentes |
| `brain_runtime/fix_verify_learn.py` | Ciclo scan → task → fix → verify → learn |
| `brain_runtime/runtime.py` | Orquestração E2E, delivery, readiness e replay |

## Integração de Project Intelligence

O scanner e o readiness gate são opcionais no `RuntimeCoordinator`:

```python
from brain_runtime.project_intelligence import ProjectScanner
from brain_runtime.readiness import ReadinessGate
from brain_runtime.runtime import RuntimeCoordinator

runtime = RuntimeCoordinator(
    pipeline=pipeline,
    delivery=delivery,
    events=events,
    project_scanner=ProjectScanner("/path/to/project"),
    readiness_gate=ReadinessGate(),
    enforce_readiness=True,
)
```

Quando configurado, o runtime atualiza `.projectbrain/`, emite `ProjectScanned` e `ReadinessEvaluated`, e bloqueia a conclusão quando `enforce_readiness=True` e existem blockers.

## Kotlin e Android

O diretório `app/` contém contratos e componentes Kotlin de referência. O aplicativo Android não está implementado nesta fase. Ainda não há Gradle, `AndroidManifest.xml`, UI, Keystore, foreground service ou APK.

A decisão é deliberada: o runtime e seus contratos devem ser estabilizados antes de criar o cliente móvel.

## Documentação técnica

- [Auditoria técnica atual](AUDITORIA_PESADA.md)
- [Roadmap implementado](docs/ROADMAP_IMPLEMENTADO.md)
- [Status detalhado de implementação](docs/IMPLEMENTATION_STATUS.md)
- [Contratos](contracts/)
- [Testes](tests/)

## Limites de implantação

Para isolamento forte, a implantação deve fornecer container rootless ou sandbox OS-level, cgroups graváveis, política de rede, filesystem jail e, quando aplicável, autoridade de assinatura. O runtime rejeita controles estritos ausentes e não simula capacidades que o host não fornece.

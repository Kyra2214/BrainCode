# Auditoria técnica atual do Braim

**Data da auditoria:** 2026-09-12. **Escopo:** runtime Python em `brain_runtime/`, contratos Kotlin em `app/`, documentação, configuração e testes. **Método:** inspeção dos módulos, execução da suíte nominal, compilação Python, verificação de diff e leitura dos caminhos de segurança, persistência, workflow e recovery.

## Veredito executivo

O Braim evoluiu de um esqueleto para um **runtime de referência funcional, testável e auditável em Python**, mas ainda não deve ser classificado como plataforma de produção plenamente isolada. As garantias implementadas são aplicadas em nível de código e possuem testes de regressão. As garantias que dependem do host ou de serviços externos continuam sendo explicitamente diagnosticadas, rejeitadas em modo estrito ou delegadas à implantação.

A implementação Android permanece fora do escopo executável atual. O diretório `app/` contém contratos e componentes Kotlin sem projeto Gradle, `AndroidManifest.xml`, Activity, Service ou APK.

## Evidências atuais

```text
python3 -m unittest discover -s tests -q
Ran 112 tests
OK

python3 -m compileall -q brain_runtime tests
OK

git diff --check
OK
```

Os commits estão publicados em `origin/main`. O último commit de integração é `32e805a`.

## Matriz de estado

| Área | Estado atual | Evidência principal | Limite restante |
|---|---|---|---|
| Contratos Python | Implementado | `contracts.py`, `contract_registry.py`, contract tests | Unificação futura com o contrato Kotlin |
| Policy e approval | Implementado no runtime | `policy.py`, `approval.py`, testes de anti-replay | Assinatura externa de decisões e autorização centralizada |
| EventStore | Implementado com hash chain, redaction, recovery parcial, rotação e retenção | `events.py`, security/golden tests | Escala multi-host e storage transacional externo |
| Pipeline | Implementado com research, plan, policy, binding, correction, retry e learning opcional | `pipeline.py`, integration tests | Planner/router ainda são substituíveis e simplificados |
| Sandbox | Hardening defensivo e namespaces quando suportados | `sandbox.py`, isolation/security tests | cgroups, Bubblewrap e kernel capabilities dependem do host |
| Workflows | Implementado com persistência, leases, retry, timeout, cancelamento e compensação | `workflows.py`, workflow tests | backend distribuído real e fencing multi-host |
| APIs | Catálogo persistente com quota, cooldown, probes, score e fallback equivalente | `apis.py`, routing tests | health real e credenciais dependem dos providers |
| Skills | Licença, assinatura opcional, provenance, quarentena e revogação | `skills.py`, adapters e tests | autoridade remota de chaves e execução isolada |
| Memória/Learning | SQLite, deduplicação, retenção, provenance e bridge de execução | `memory.py`, `learning.py` | semântica vetorial e storage distribuído |
| QA/Delivery | QA gate, validation evidence e delivery condicionado | `delivery.py`, E2E tests | cobertura de providers reais e artefatos externos |
| Observabilidade | Spans, counters, gauges, alerts e export JSON | `observability.py`, integration tests | exporter/collector externo |
| Project Intelligence | Scanner e contexto `.projectbrain/` | `project_intelligence.py` | análise semântica profunda e CI remoto |
| Readiness | Gate com score, blockers, warnings e exit code | `readiness.py`, runtime E2E | políticas de release específicas da organização |
| Release Intelligence | Comparação Git e heurística de regressão | `release_intelligence.py` | diagnóstico causal e histórico de produção |
| Android Mobile | Não iniciado como aplicação | ausência de Gradle/Manifest/UI | fase futura deliberada |

## Riscos remanescentes

### Isolamento do Sandbox

O Sandbox não deve ser interpretado como container completo apenas por executar `python3` ou `unshare`. O runtime valida comandos, argumentos, paths, symlinks, extensões, artefatos, recursos, cancelamento e timeout. Quando solicitado, tenta namespaces de usuário, montagem, PID e rede. O modo estrito rejeita a execução quando o host não fornece o controle exigido.

Filesystem jail via Bubblewrap, cgroups graváveis, seccomp, capabilities mínimas e políticas de kernel continuam responsabilidades da implantação. O código não simula essas garantias.

### Contrato Kotlin e Android

O contrato Kotlin em `app/src/main/kotlin/com/brain/execution/SandboxContract.kt` é útil como referência, mas ainda não é consumido pelo runtime Python. Não existe build Android no repositório. Portanto, nenhuma alegação de aplicativo Android, IPC Android, Keystore ou foreground service deve ser feita nesta fase.

### Routing e providers

O catálogo implementa seleção e contabilidade local. A disponibilidade real, a qualidade semântica da resposta, os termos do provider e a validade de uma credential reference dependem de integrações externas. O dispatcher deve continuar validando binding, policy e schema antes de executar.

### Readiness e evidência

O Readiness Gate é um mecanismo de decisão local. Ele não prova estado externo de providers. O Evidence Engine mantém essa distinção por meio do campo `external_state`; a presença de código ou dependência não é tratada como funcionamento externo confirmado.

## Recomendações de implantação

1. Executar em container rootless ou sandbox OS-level configurado pelo operador.
2. Exigir `isolation_required=True` e `cgroup_path` em ambientes que demandem isolamento forte.
3. Usar backend transacional distribuído para leases e workflows multi-host.
4. Configurar autoridade de chaves para skills e providers antes de permitir conteúdo externo.
5. Ativar `enforce_readiness=True` em pipelines de release.
6. Manter o Android como cliente/controlador futuro, sem duplicar a Policy no dispositivo.

## Conclusão

O código atual sustenta a classificação de **runtime de referência funcional com hardening significativo**. Ele não sustenta a classificação de plataforma de produção plenamente isolada ou aplicativo Android pronto. A documentação foi ajustada para distinguir implementação local, integração opcional e dependência de infraestrutura.

## Referências internas

- [README.md](README.md)
- [docs/ROADMAP_IMPLEMENTADO.md](docs/ROADMAP_IMPLEMENTADO.md)
- [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md)
- [brain_runtime/](brain_runtime/)
- [tests/](tests/)

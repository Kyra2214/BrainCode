"""Item 9 — fiação de produção do lease_store.

Antes desta mudança, o fencing distribuído tinha três peças prontas e
testadas isoladamente:

- ``DistributedLeaseStore`` (host_controls.py): acquire/renew/release/
  assert_current com fencing token monotônico.
- ``SandboxExecutor(lease_store=...)`` (sandbox.py): quando recebe um
  lease_store, passa a exigir lease válida e corrente em *todo* job,
  para *todo* consumidor (SandboxDispatcher, Orchestrator etc.) — não é
  preciso lembrar de checar a lease em cada chamador.
- Os testes de ``FencingIntegrationTests`` em
  ``tests/test_hardening_followup.py`` provam o comportamento acima.

O que faltava — e é o que este módulo fecha — é a composição de
produção real: nada em ``brain_runtime`` instanciava um
``DistributedLeaseStore`` de verdade nem emitia uma ``Lease`` por job
antes de chamar o executor. ``compose_dispatcher`` (dispatch.py) sempre
aceitou um ``SandboxExecutor`` já pronto, mas até esta rodada todo
caller real (e todo teste de integração) passava ``SandboxExecutor()``
*sem* lease_store — ou seja, em produção o fencing nunca era de fato
obrigatório: a proteção testada ficava sem caller.

Este módulo fornece a fiação que faltava, sem duplicar a lógica de
fencing já testada em host_controls.py/sandbox.py:

- ``production_lease_store``: DistributedLeaseStore com sqlite
  persistente em disco (não ``:memory:``), condição necessária para o
  fencing sobreviver a um restart de processo/worker.
- ``leased_job_factory``: envolve um job_factory existente para
  adquirir uma Lease por job antes de retornar o SandboxJob.
- ``compose_production_sandbox``: junta os dois acima com
  ``compose_dispatcher`` (credentials -> routing -> sandbox) em uma
  única chamada, para uso real por RuntimeCoordinator/app.
"""
from __future__ import annotations

from dataclasses import replace
from pathlib import Path
from typing import Callable

from .credentials import CredentialRef, CredentialVault
from .dispatch import compose_dispatcher
from .host_controls import DistributedLeaseStore
from .models import ExecutionRequest
from .sandbox import SandboxExecutor, SandboxJob

DEFAULT_LEASE_TTL_SECONDS = 300
DEFAULT_LEASE_DB_NAME = "sandbox_leases.db"


def production_lease_store(state_dir: str | Path, *, db_name: str = DEFAULT_LEASE_DB_NAME) -> DistributedLeaseStore:
    """Instancia um DistributedLeaseStore persistente para uso em produção.

    O default de ``DistributedLeaseStore`` (``:memory:``) não sobrevive
    a um restart do processo nem é compartilhável entre workers — os
    dois cenários em que o fencing existe para proteger (um worker
    reiniciado ou um segundo worker assumindo a mesma chave não devem
    conseguir pisar em uma execução ainda válida, e vice-versa). Um
    lease_store "de produção" que na prática não persiste dá falsa
    confiança de que o fencing sobrevive a um failover, então este
    helper sempre grava em arquivo — o chamador escolhe apenas o
    diretório.
    """
    directory = Path(state_dir)
    directory.mkdir(parents=True, exist_ok=True)
    return DistributedLeaseStore(directory / db_name)


def leased_job_factory(
    job_factory: Callable[..., SandboxJob],
    lease_store: DistributedLeaseStore,
    *, owner: str, ttl_seconds: int = DEFAULT_LEASE_TTL_SECONDS,
) -> Callable[..., SandboxJob]:
    """Envolve um job_factory para emitir uma Lease por job.

    A chave de fencing é derivada de ``run_id`` + ``step_id`` do
    ``ExecutionRequest`` recebido pelo factory — não do ``job_id`` que o
    SandboxJob resultante carrega, porque esse é escolhido pelo próprio
    factory e um bug (ou dois factories diferentes reusando o mesmo
    literal, como ``"job"`` nos testes) poderia colidir chaves de runs
    não relacionados. run_id + step_id é o identificador estável da
    unidade de trabalho que a lease protege.

    O wrapper preserva a assinatura ``(request, credential=...)``
    esperada por ``SandboxDispatcher.dispatch`` (que inspeciona a
    assinatura do factory para decidir se repassa ``credential``) porque
    aceita ``**kwargs`` e repassa exatamente o que recebeu.
    """
    def wrapped(request: ExecutionRequest, **kwargs) -> SandboxJob:
        job = job_factory(request, **kwargs) if kwargs else job_factory(request)
        key = f"sandbox:{request.run_id}:{request.step_id}"
        lease = lease_store.acquire(key, owner=owner, ttl_seconds=ttl_seconds)
        return replace(job, lease=lease)
    wrapped.__name__ = f"leased({getattr(job_factory, '__name__', 'job_factory')})"
    return wrapped


def compose_production_sandbox(
    catalog, job_factory: Callable[..., SandboxJob], *, state_dir: str | Path, owner: str,
    vault: CredentialVault | None = None, credential_ref: CredentialRef | None = None,
    ttl_seconds: int = DEFAULT_LEASE_TTL_SECONDS,
) -> tuple[object, DistributedLeaseStore]:
    """Composição de produção completa do caminho de execução em sandbox.

    Cadeia resultante: credentials -> routing -> sandbox (via
    ``compose_dispatcher``), com o ``SandboxExecutor`` interno recebendo
    um ``lease_store`` real (fencing obrigatório para todo job) e o
    ``job_factory`` envolvido para emitir uma lease por job antes de
    cada execução.

    Retorna ``(dispatcher, lease_store)``. O chamador é dono do
    lease_store retornado e deve chamar ``lease_store.close()`` no
    shutdown do worker.
    """
    lease_store = production_lease_store(state_dir)
    executor = SandboxExecutor(lease_store=lease_store)
    factory_with_lease = leased_job_factory(job_factory, lease_store, owner=owner, ttl_seconds=ttl_seconds)
    dispatcher = compose_dispatcher(catalog, executor, factory_with_lease, vault, credential_ref)
    return dispatcher, lease_store

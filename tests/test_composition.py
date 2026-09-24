"""Testes do item 9 do plano de hardening de 2026-09-13: fiação de
produção do lease_store.

DistributedLeaseStore, SandboxExecutor(lease_store=...) e o fencing
obrigatório em si já são cobertos por
tests/test_hardening_followup.py::FencingIntegrationTests. Este arquivo
cobre especificamente a peça que faltava: a composição de produção em
brain_runtime/composition.py realmente instancia um lease_store real,
persiste em disco, e emite/anexa uma lease por job antes do executor
rodar — e sem essa fiação o fencing testado isoladamente não protege
nada em produção.
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from brain_runtime.apis import ApiCatalogEntry, DynamicApiCatalog
from brain_runtime.binding import bind_execution
from brain_runtime.composition import (
    compose_production_sandbox,
    leased_job_factory,
    production_lease_store,
)
from brain_runtime.host_controls import DistributedLeaseStore
from brain_runtime.models import ExecutionRequest
from brain_runtime.sandbox import SandboxExecutor, SandboxJob


class ProductionLeaseStoreTests(unittest.TestCase):
    def test_creates_persistent_sqlite_file_not_in_memory(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = production_lease_store(tmp)
            self.assertTrue((Path(tmp) / "sandbox_leases.db").exists())
            store.close()

    def test_lease_survives_reopening_the_store_same_path(self):
        # Simula um restart de processo/worker: um segundo DistributedLeaseStore
        # apontado para o mesmo diretório precisa enxergar o mesmo estado de
        # lease, ou o fencing não sobreviveria a um failover.
        with tempfile.TemporaryDirectory() as tmp:
            store_a = production_lease_store(tmp)
            lease = store_a.acquire("sandbox:run-1:step-1", owner="worker-a", ttl_seconds=300)
            store_a.close()

            store_b = production_lease_store(tmp)
            # A mesma lease continua corrente do ponto de vista do novo processo.
            store_b.assert_current(lease)
            # E um segundo dono não consegue adquiri-la enquanto não expirar/for liberada.
            with self.assertRaises(RuntimeError):
                store_b.acquire("sandbox:run-1:step-1", owner="worker-b", ttl_seconds=300)
            store_b.close()

    def test_creates_missing_state_dir(self):
        with tempfile.TemporaryDirectory() as tmp:
            nested = Path(tmp) / "nested" / "state"
            store = production_lease_store(nested)
            self.assertTrue(nested.is_dir())
            store.close()


class LeasedJobFactoryTests(unittest.TestCase):
    def test_attaches_a_current_lease_and_executor_accepts_it(self):
        store = DistributedLeaseStore()
        with tempfile.TemporaryDirectory() as directory:
            def factory(request):
                return SandboxJob("job", request.run_id, "session", ("python3", "-c", "print('ok')"), directory)

            wrapped = leased_job_factory(factory, store, owner="worker-a")
            request = ExecutionRequest("req", "run-1", "task-1", "step-1", "x", "objective")
            job = wrapped(request)

            self.assertIsNotNone(job.lease)
            self.assertEqual(job.lease.key, "sandbox:run-1:step-1")

            executor = SandboxExecutor(lease_store=store)
            result = executor.execute(job)
            self.assertEqual(result.status, "SUCCEEDED")
        store.close()

    def test_preserves_credential_kwarg_signature_for_dispatcher_introspection(self):
        store = DistributedLeaseStore()
        with tempfile.TemporaryDirectory() as directory:
            received = []

            def factory(request, credential=None):
                received.append(credential)
                return SandboxJob("job", request.run_id, "session", ("python3", "-c", "print('ok')"), directory)

            wrapped = leased_job_factory(factory, store, owner="worker-a")
            request = ExecutionRequest("req", "run-2", "task-1", "step-1", "x", "objective")
            job = wrapped(request, credential="secret")
            self.assertEqual(received, ["secret"])
            self.assertIsNotNone(job.lease)
        store.close()

    def test_second_job_for_same_run_step_from_another_owner_is_rejected(self):
        # Prova o motivo de existir fencing: um segundo worker tentando a
        # mesma chave (mesmo run_id/step_id) enquanto a lease do primeiro
        # ainda é válida não pode simplesmente seguir em frente sem token.
        store = DistributedLeaseStore()
        with tempfile.TemporaryDirectory() as directory:
            def factory(request):
                return SandboxJob("job", request.run_id, "session", ("python3", "-c", "print('ok')"), directory)

            wrapped_a = leased_job_factory(factory, store, owner="worker-a")
            wrapped_b = leased_job_factory(factory, store, owner="worker-b")
            request = ExecutionRequest("req", "run-3", "task-1", "step-1", "x", "objective")
            wrapped_a(request)
            with self.assertRaises(RuntimeError):
                wrapped_b(request)
        store.close()


class ComposeProductionSandboxTests(unittest.TestCase):
    def test_end_to_end_dispatch_requires_and_uses_a_real_lease(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as state_dir:
            catalog = DynamicApiCatalog([ApiCatalogEntry("local", "sandbox", ("x",), "local")])

            def factory(request):
                return SandboxJob("job", request.run_id, "session", ("python3", "-c", "print('ok')"), directory)

            dispatcher, lease_store = compose_production_sandbox(
                catalog, factory, state_dir=state_dir, owner="worker-a",
            )
            try:
                binding = bind_execution(
                    run_id="r", task_id="t", step_id="s", capability="x",
                    provider="local/sandbox", policy_decision_id="decision", resource="local/sandbox",
                )
                request = ExecutionRequest(
                    "req", "r", "t", "s", "x", "safe objective",
                    {"provider": "local/sandbox", "binding_digest": binding.digest()}, "decision",
                )
                result = dispatcher.dispatch(request)
                self.assertTrue(result.success, result.error)
                self.assertEqual(result.status, "SUCCEEDED")
            finally:
                lease_store.close()

    def test_bypassing_the_composed_executor_without_a_lease_is_rejected(self):
        # Garante que o executor produzido por compose_production_sandbox de
        # fato tem lease_store configurado (fencing obrigatório), não um
        # SandboxExecutor() "aberto" por engano.
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as state_dir:
            catalog = DynamicApiCatalog([ApiCatalogEntry("local", "sandbox", ("x",), "local")])

            def factory(request):
                return SandboxJob("job", request.run_id, "session", ("python3", "-c", "print('ok')"), directory)

            dispatcher, lease_store = compose_production_sandbox(
                catalog, factory, state_dir=state_dir, owner="worker-a",
            )
            try:
                bare_job = SandboxJob("job", "r", "session", ("python3", "-c", "print('ok')"), directory)
                result = dispatcher.dispatcher.executor.execute(bare_job)
                self.assertEqual(result.status, "REJECTED")
                self.assertIn("fencing lease required", result.diagnostics)
            finally:
                lease_store.close()


if __name__ == "__main__":
    unittest.main()

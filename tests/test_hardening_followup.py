"""Testes dos itens 1, 8 e 9 do plano de hardening de 2026-09-13.

Cada teste aqui existe para provar em execução o que antes era só uma
afirmação: "o bwrap não expõe mais /usr e /bin do host", "a validação de
SSRF está travada no IP resolvido" e "o fencing é obrigatório para todo
consumidor do SandboxExecutor". Ver AUDITORIA_PESADA.md / conversa de
hardening para o contexto completo dos 9 pontos originais.
"""
from __future__ import annotations

import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from brain_runtime.host_controls import DistributedLeaseStore
from brain_runtime.research import _PinnedHTTPSConnection, validate_external_url
from brain_runtime.sandbox import SandboxExecutor, SandboxJob, _namespace_command


def _fake_which(preferred: set[str]):
    def which(name: str):
        return f"/usr/bin/{name}" if name in preferred else None
    return which


class BubblewrapRootfsTests(unittest.TestCase):
    """Item 1: bwrap não deve mais montar /usr e /bin do host quando um rootfs controlado está configurado."""

    def _rootfs(self, tmp: str) -> str:
        root = Path(tmp) / "rootfs"
        (root / "usr").mkdir(parents=True)
        (root / "bin").mkdir(parents=True)
        return str(root)

    def test_uses_configured_rootfs_instead_of_host(self):
        with tempfile.TemporaryDirectory() as tmp:
            rootfs = self._rootfs(tmp)
            job = SandboxJob("j", "r", "s", ("python3",), tmp, filesystem_jail=True, rootfs_path=rootfs)
            with patch("brain_runtime.sandbox.shutil.which", _fake_which({"unshare", "bwrap"})):
                command, diagnostics = _namespace_command(job.argv, job)
            self.assertIn(str(Path(rootfs) / "usr"), command)
            self.assertIn(str(Path(rootfs) / "bin"), command)
            # A montagem literal do host não deve mais aparecer como origem do bind.
            pairs = list(zip(command, command[1:]))
            self.assertNotIn(("/usr", "/usr"), pairs)
            self.assertNotIn(("/bin", "/bin"), pairs)
            self.assertTrue(any("no host /usr or /bin exposed" in item for item in diagnostics))

    def test_falls_back_to_host_with_explicit_diagnostic_when_not_configured(self):
        with tempfile.TemporaryDirectory() as tmp:
            job = SandboxJob("j", "r", "s", ("python3",), tmp, filesystem_jail=True)
            with patch("brain_runtime.sandbox.shutil.which", _fake_which({"unshare", "bwrap"})):
                command, diagnostics = _namespace_command(job.argv, job)
            self.assertIn("/usr", command)
            self.assertTrue(any("using host /usr and /bin" in item for item in diagnostics))

    def test_strict_isolation_refuses_host_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            job = SandboxJob("j", "r", "s", ("python3",), tmp, filesystem_jail=True, isolation_required=True)
            with patch("brain_runtime.sandbox.shutil.which", _fake_which({"unshare", "bwrap"})):
                command, diagnostics = _namespace_command(job.argv, job)
            self.assertEqual(command, [])
            self.assertTrue(any("rootfs_path not configured" in item for item in diagnostics))


class SsrfPinningTests(unittest.TestCase):
    """Item 8: a conexão real deve travar no IP já validado, não re-resolver o host."""

    def test_validate_external_url_returns_resolved_addresses(self):
        with patch("brain_runtime.research.socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]):
            addresses = validate_external_url("https://example.com/page")
        self.assertEqual(addresses, ("93.184.216.34",))

    def test_pinned_connection_connects_to_resolved_ip_not_hostname(self):
        recorded = {}

        def fake_create_connection(address, timeout, source_address):
            recorded["address"] = address
            return "FAKE_SOCKET"

        class FakeContext:
            def wrap_socket(self, sock, server_hostname):
                recorded["server_hostname"] = server_hostname
                return sock

        conn = _PinnedHTTPSConnection("example.com", "93.184.216.34", timeout=5)
        conn._context = FakeContext()
        with patch("brain_runtime.research.socket.create_connection", fake_create_connection):
            conn.connect()
        self.assertEqual(recorded["address"], ("93.184.216.34", 443))
        # SNI/verificação de certificado continuam no hostname real, não no IP.
        self.assertEqual(recorded["server_hostname"], "example.com")

    def test_rebinding_between_validate_and_connect_does_not_change_target(self):
        """Mesmo que o DNS 'mude de ideia' entre a validação e a conexão, o socket real usa o IP já validado."""
        with patch("brain_runtime.research.socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]):
            addresses = validate_external_url("https://example.com/page")
        recorded = {}
        with patch("brain_runtime.research.socket.getaddrinfo", return_value=[(2, 1, 6, "", ("10.0.0.1", 443))]):
            # Se a conexão real re-resolvesse o host aqui, ela pegaria 10.0.0.1
            # (privado) em vez do IP validado — o pin abaixo impede isso.
            def fake_create_connection(address, timeout, source_address):
                recorded["address"] = address
                return "FAKE_SOCKET"

            class FakeContext:
                def wrap_socket(self, sock, server_hostname):
                    return sock

            conn = _PinnedHTTPSConnection("example.com", addresses[0], timeout=5)
            conn._context = FakeContext()
            with patch("brain_runtime.research.socket.create_connection", fake_create_connection):
                conn.connect()
        self.assertEqual(recorded["address"][0], "93.184.216.34")


class FencingIntegrationTests(unittest.TestCase):
    """Item 9: SandboxExecutor é o único ponto de entrada — configurar o lease_store basta para proteger todo consumidor."""

    def test_execute_rejects_job_without_lease_when_store_configured(self):
        executor = SandboxExecutor(lease_store=DistributedLeaseStore())
        with tempfile.TemporaryDirectory() as directory:
            result = executor.execute(SandboxJob("j", "r", "s", ("python3", "-c", "print('ok')"), directory))
        self.assertEqual(result.status, "REJECTED")
        self.assertIn("fencing lease required", result.diagnostics)

    def test_execute_rejects_stale_or_superseded_lease(self):
        store = DistributedLeaseStore()
        lease = store.acquire("sandbox:job-1", owner="worker-a", ttl_seconds=300)
        # Outro worker assume a mesma chave (ex: após failover) — o token antigo fica obsoleto.
        store.release(lease)
        newer = store.acquire("sandbox:job-1", owner="worker-b", ttl_seconds=300)
        executor = SandboxExecutor(lease_store=store)
        with tempfile.TemporaryDirectory() as directory:
            result = executor.execute(SandboxJob("j", "r", "s", ("python3", "-c", "print('ok')"), directory, lease=lease))
        self.assertEqual(result.status, "REJECTED")
        self.assertTrue(any("fencing lease invalid" in item for item in result.diagnostics))
        store.release(newer)

    def test_execute_succeeds_with_a_current_lease(self):
        store = DistributedLeaseStore()
        lease = store.acquire("sandbox:job-2", owner="worker-a", ttl_seconds=300)
        executor = SandboxExecutor(lease_store=store)
        with tempfile.TemporaryDirectory() as directory:
            result = executor.execute(SandboxJob("j", "r", "s", ("python3", "-c", "print('ok')"), directory, lease=lease))
        self.assertEqual(result.status, "SUCCEEDED")

    def test_executor_without_lease_store_keeps_previous_behavior(self):
        executor = SandboxExecutor()
        with tempfile.TemporaryDirectory() as directory:
            result = executor.execute(SandboxJob("j", "r", "s", ("python3", "-c", "print('ok')"), directory))
        self.assertEqual(result.status, "SUCCEEDED")


if __name__ == "__main__":
    unittest.main()

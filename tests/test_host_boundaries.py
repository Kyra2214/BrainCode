import socket
import tempfile
import unittest
from pathlib import Path
from brain_runtime.host_controls import DistributedLeaseStore, HostCapabilityProbe
from brain_runtime.ipc import IPCProtocolError, SecureIPC

class HostBoundaryTests(unittest.TestCase):
    def test_distributed_lease_uses_fencing_tokens(self):
        with tempfile.TemporaryDirectory() as directory:
            store = DistributedLeaseStore(Path(directory) / "lease.db"); first = store.acquire("workflow", "one", 30)
            with self.assertRaises(RuntimeError): store.acquire("workflow", "two", 30)
            renewed = store.renew(first, 30); store.release(renewed); second = store.acquire("workflow", "two", 30)
            self.assertGreater(second.fencing_token, first.fencing_token)

    def test_ipc_authenticates_and_sanitizes_messages(self):
        left, right = socket.socketpair(); a = SecureIPC(left, b"shared-key"); b = SecureIPC(right, b"shared-key")
        a.send("sandbox.execute", "trace-1", {"credential": "secret", "argv": ["python3"]}); message = b.recv()
        self.assertEqual(message.correlation_id, "trace-1"); self.assertEqual(message.payload["credential"], "[REDACTED]")
        left.close(); right.close()

    def test_ipc_rejects_wrong_key(self):
        left, right = socket.socketpair(); a = SecureIPC(left, b"one"); b = SecureIPC(right, b"two")
        a.send("ping", "trace", {})
        with self.assertRaises(IPCProtocolError): b.recv()
        left.close(); right.close()

    def test_host_probe_returns_explicit_capabilities(self):
        capabilities = HostCapabilityProbe().probe()
        self.assertIsInstance(capabilities.strict_isolation_available(), bool)

if __name__ == "__main__": unittest.main()

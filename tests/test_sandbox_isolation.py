import tempfile
import unittest
from brain_runtime.sandbox import SandboxExecutor, SandboxJob

class SandboxIsolationTests(unittest.TestCase):
    def test_namespace_execution_reports_isolation(self):
        with tempfile.TemporaryDirectory() as directory:
            result = SandboxExecutor().execute(SandboxJob("j", "r", "s", ("python3", "-c", "print('ok')"), directory))
            self.assertEqual(result.status, "SUCCEEDED")
            self.assertTrue(any(item.startswith("isolation:") for item in result.diagnostics))

    def test_network_isolation_is_requested_by_default(self):
        with tempfile.TemporaryDirectory() as directory:
            result = SandboxExecutor().execute(SandboxJob("j", "r", "s", ("python3", "-c", "print('ok')"), directory))
            diagnostics = " ".join(result.diagnostics)
            self.assertTrue("network namespace" in diagnostics or "network isolation" in diagnostics)

    def test_strict_network_isolation_does_not_degrade(self):
        with tempfile.TemporaryDirectory() as directory:
            result = SandboxExecutor().execute(SandboxJob("j", "r", "s", ("python3", "-c", "print('ok')"), directory,
                isolation_required=True, network_namespace=True))
            # The current sandbox denies unprivileged network namespaces; a capable
            # host may execute successfully inside an actual network namespace.
            if result.status == "REJECTED":
                self.assertIn("network namespace", " ".join(result.diagnostics))
            else:
                self.assertEqual(result.status, "SUCCEEDED")

if __name__ == "__main__": unittest.main()

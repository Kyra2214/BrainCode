import tempfile
import unittest
from pathlib import Path
from brain_runtime.events import EventStore
from brain_runtime.orchestrator import Orchestrator
from brain_runtime.sandbox import QAGate, SandboxExecutor, SandboxJob


class SandboxTests(unittest.TestCase):
    def test_e2e_runs_allowlisted_job_and_delivers_after_qa(self):
        with tempfile.TemporaryDirectory() as directory:
            job = SandboxJob("job-1", "run-1", "session-1", ("python3", "-c", "print('ok')"), directory)
            delivery = Orchestrator(SandboxExecutor(), QAGate(), EventStore()).execute(job, "task-1", "ok")
            self.assertTrue(delivery.delivered)
            self.assertEqual(delivery.result.status, "SUCCEEDED")

    def test_non_allowlisted_command_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            result = SandboxExecutor().execute(SandboxJob("job-2", "run", "session", ("sh", "-c", "echo bad"), directory))
            self.assertEqual(result.status, "REJECTED")


if __name__ == "__main__":
    unittest.main()

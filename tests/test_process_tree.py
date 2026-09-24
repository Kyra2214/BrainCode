from __future__ import annotations
import tempfile
import time
import unittest
from pathlib import Path
from brain_runtime.sandbox import SandboxExecutor, SandboxJob


class ProcessTreeTest(unittest.TestCase):
    def test_timeout_kills_child_process_group(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "child-survived"
            script = Path(directory) / "parent.py"
            script.write_text(
                "import pathlib, subprocess, sys, time\n"
                "marker = pathlib.Path(sys.argv[1])\n"
                "subprocess.Popen([sys.executable, '-c', \"import pathlib,sys,time; time.sleep(2); pathlib.Path(sys.argv[1]).write_text('alive')\", str(marker)])\n"
                "time.sleep(5)\n"
            )
            result = SandboxExecutor().execute(SandboxJob(
                job_id="tree-timeout", run_id="run", session_id="session",
                argv=("python3", str(script), str(marker)), cwd=directory,
                timeout_seconds=1, max_processes=2048,
            ))
            self.assertEqual("TIMEOUT", result.status, result.stderr or repr(result.diagnostics))
            time.sleep(1.3)
            self.assertFalse(marker.exists(), "child process survived group termination")


if __name__ == "__main__":
    unittest.main()

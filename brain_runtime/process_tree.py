from __future__ import annotations
import os
import signal
import subprocess


def terminate_process_group(process: subprocess.Popen[bytes], sig: int = signal.SIGKILL) -> None:
    """Terminate the real session/process group, falling back to the leader."""
    try:
        os.killpg(process.pid, sig)
    except (ProcessLookupError, PermissionError):
        try:
            process.send_signal(sig)
        except ProcessLookupError:
            pass

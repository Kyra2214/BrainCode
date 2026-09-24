from __future__ import annotations
import hashlib, os, resource, signal, shutil, subprocess, time
from dataclasses import dataclass
from pathlib import Path
from .host_controls import CgroupController

@dataclass(frozen=True)
class SandboxJob:
    job_id: str; run_id: str; session_id: str; argv: tuple[str, ...]; cwd: str
    timeout_seconds: int = 30; allowed_commands: tuple[str, ...] = ("python3",); network_allowed: bool = False
    filesystem_roots: tuple[str, ...] = (); max_stdout_bytes: int = 1_048_576; max_stderr_bytes: int = 1_048_576
    max_artifacts: int = 100; max_artifact_bytes: int = 10 * 1024 * 1024; memory_mb: int = 512
    max_processes: int = 32; max_open_files: int = 64; max_disk_bytes: int = 100 * 1024 * 1024
    allowed_extensions: tuple[str, ...] = (); cancel_event: object | None = None
    isolation_required: bool = False; network_namespace: bool = False
    filesystem_jail: bool = False; cgroup_path: str = ""; allowed_artifact_dirs: tuple[str, ...] = (); modified_artifacts_only: bool = False

@dataclass(frozen=True)
class SandboxJobResult:
    job_id: str; status: str; exit_code: int | None; stdout: str; stderr: str
    artifacts: tuple[dict, ...] = (); diagnostics: tuple[str, ...] = ()
    run_id: str = ""; session_id: str = ""; duration_ms: int = 0

def _limit_process(memory_mb: int, max_processes: int | None, max_open_files: int, max_disk_bytes: int) -> None:
    limits = ((resource.RLIMIT_CPU, (60, 60)), (resource.RLIMIT_AS, (memory_mb * 1024 * 1024,) * 2),
              (resource.RLIMIT_CORE, (0, 0)), *((((resource.RLIMIT_NPROC, (max_processes,) * 2),) if max_processes else ())),
              (resource.RLIMIT_NOFILE, (max_open_files,) * 2), (resource.RLIMIT_FSIZE, (max_disk_bytes,) * 2))
    for kind, value in limits:
        try: resource.setrlimit(kind, value)
        except (OSError, ValueError): pass

def _clip(value: str | bytes | None, limit: int) -> str:
    if value is None: return ""
    if isinstance(value, bytes): value = value.decode(errors="replace")
    return value[:limit]

def _namespace_command(argv: tuple[str, ...], job: SandboxJob) -> tuple[list[str], tuple[str, ...]]:
    unshare = shutil.which("unshare")
    if not unshare:
        return ([], ("unshare is unavailable",)) if job.isolation_required else (list(argv), ("isolation:namespace unavailable; resource limits only",))
    if job.filesystem_jail:
        bwrap = shutil.which("bwrap")
        if bwrap:
            command = [bwrap, "--die-with-parent", "--unshare-pid", "--proc", "/proc", "--dev", "/dev", "--ro-bind", "/usr", "/usr", "--ro-bind", "/bin", "/bin", "--bind", str(Path(job.cwd).resolve()), "/workspace", "--chdir", "/workspace"]
            if not job.network_allowed: command.append("--unshare-net")
            return command + ["--"] + list(argv), ("isolation:bubblewrap filesystem jail enabled", "isolation:network namespace enabled" if not job.network_allowed else "isolation:network namespace not requested")
        if job.isolation_required: return [], ("bubblewrap unavailable; strict filesystem jail refused",)
    command = [unshare, "--user", "--map-root-user", "--mount", "--pid", "--fork", "--mount-proc"]
    diagnostics = ("isolation:user namespace enabled", "isolation:mount namespace enabled", "isolation:PID namespace enabled")
    # Deny-by-default must request network isolation unless the policy
    # explicitly authorizes network access. The explicit flag can still force
    # isolation for an authorized network job on a dedicated host.
    network_isolation_requested = job.network_namespace or not job.network_allowed
    if network_isolation_requested:
        probe = subprocess.run([unshare, "--net", "true"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if probe.returncode != 0:
            if job.isolation_required:
                return [], ("network namespace unavailable; strict isolation refused",)
            return list(argv), diagnostics + ("isolation:network namespace unavailable; network isolation not guaranteed",)
        command.append("--net"); diagnostics += ("isolation:network namespace enabled",)
    return command + ["--"] + list(argv), diagnostics

class SandboxExecutor:
    def execute(self, job: SandboxJob) -> SandboxJobResult:
        if not job.argv or job.argv[0] not in job.allowed_commands or any(token in job.argv[0] for token in ("/", "\\")):
            return SandboxJobResult(job.job_id, "REJECTED", None, "", "", diagnostics=("command not allowlisted",), run_id=job.run_id, session_id=job.session_id)
        if job.cancel_event is not None and getattr(job.cancel_event, "is_set", lambda: False)():
            return SandboxJobResult(job.job_id, "CANCELLED", None, "", "", diagnostics=("cancelled by caller",), run_id=job.run_id, session_id=job.session_id)
        forbidden = (";", "&&", "||", "|", ">", "<", "`", "$('", "../")
        if any(any(marker in token for marker in forbidden) for token in job.argv[1:]):
            return SandboxJobResult(job.job_id, "REJECTED", None, "", "", diagnostics=("unsafe command argument",), run_id=job.run_id, session_id=job.session_id)
        if job.timeout_seconds <= 0 or job.memory_mb <= 0 or job.max_processes <= 0 or job.max_open_files <= 0 or job.max_disk_bytes < 0 or job.max_stdout_bytes < 0 or job.max_stderr_bytes < 0:
            return SandboxJobResult(job.job_id, "REJECTED", None, "", "", diagnostics=("invalid resource limits",), run_id=job.run_id, session_id=job.session_id)
        root = Path(job.cwd).resolve()
        if not root.exists() or not root.is_dir() or any(part.is_symlink() for part in [root, *root.parents]):
            return SandboxJobResult(job.job_id, "REJECTED", None, "", "", diagnostics=("cwd is unsafe",), run_id=job.run_id, session_id=job.session_id)
        roots = tuple(Path(item).resolve() for item in job.filesystem_roots)
        if roots and not any(root == item or item in root.parents for item in roots):
            return SandboxJobResult(job.job_id, "REJECTED", None, "", "", diagnostics=("cwd outside filesystem policy",), run_id=job.run_id, session_id=job.session_id)
        if job.cancel_event is not None and getattr(job.cancel_event, "is_set", lambda: False)():
            return SandboxJobResult(job.job_id, "CANCELLED", None, "", "", diagnostics=("cancelled by caller",), run_id=job.run_id, session_id=job.session_id)
        command, isolation_diagnostics = _namespace_command(job.argv, job)
        if not command:
            return SandboxJobResult(job.job_id, "REJECTED", None, "", "", diagnostics=isolation_diagnostics, run_id=job.run_id, session_id=job.session_id)
        before = {p: p.stat().st_size for p in root.rglob("*") if p.is_file() and not p.is_symlink()}
        started = time.monotonic()
        try:
            process = subprocess.Popen(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                env={"PATH": "/usr/bin:/bin", "PYTHONNOUSERSITE": "1", "HOME": str(root), "PYTHONHASHSEED": "random"},
                start_new_session=True, preexec_fn=lambda: _limit_process(job.memory_mb, None if command[0].endswith("unshare") else job.max_processes, job.max_open_files, job.max_disk_bytes))
            if job.cgroup_path:
                try:
                    controller = CgroupController(job.cgroup_path)
                    controller.configure(memory_mb=job.memory_mb, pids=job.max_processes, cpu_seconds=job.timeout_seconds)
                    controller.attach(process.pid)
                except (OSError, PermissionError) as error:
                    if job.isolation_required:
                        os.killpg(process.pid, signal.SIGKILL); process.communicate()
                        return SandboxJobResult(job.job_id, "REJECTED", None, "", "", diagnostics=(f"cgroup isolation unavailable: {error}",), run_id=job.run_id, session_id=job.session_id)
            deadline = started + job.timeout_seconds
            while True:
                if job.cancel_event is not None and getattr(job.cancel_event, "is_set", lambda: False)():
                    os.killpg(process.pid, signal.SIGKILL); stdout, stderr = process.communicate()
                    return SandboxJobResult(job.job_id, "CANCELLED", process.returncode, _clip(stdout, job.max_stdout_bytes), _clip(stderr, job.max_stderr_bytes), diagnostics=("cancelled by caller",), run_id=job.run_id, session_id=job.session_id)
                if time.monotonic() >= deadline:
                    os.killpg(process.pid, signal.SIGKILL); stdout, stderr = process.communicate()
                    return SandboxJobResult(job.job_id, "TIMEOUT", process.returncode, _clip(stdout, job.max_stdout_bytes), _clip(stderr, job.max_stderr_bytes), diagnostics=("execution deadline exceeded",), run_id=job.run_id, session_id=job.session_id)
                try: stdout, stderr = process.communicate(timeout=min(.1, max(.01, deadline - time.monotonic()))); break
                except subprocess.TimeoutExpired: continue
            stdout_text, stderr_text = _clip(stdout, job.max_stdout_bytes), _clip(stderr, job.max_stderr_bytes)
            diagnostics = list(isolation_diagnostics)
            if len(stdout or b"") > job.max_stdout_bytes: diagnostics.append("stdout limit exceeded")
            if len(stderr or b"") > job.max_stderr_bytes: diagnostics.append("stderr limit exceeded")
            artifacts = []; total = 0
            for path in root.rglob("*"):
                if path.is_symlink(): return SandboxJobResult(job.job_id, "REJECTED", process.returncode, stdout_text, stderr_text, diagnostics=("symlink artifact rejected",), run_id=job.run_id, session_id=job.session_id)
                if not path.is_file(): continue
                relative = path.relative_to(root)
                if job.allowed_artifact_dirs and not any(relative == Path(item) or Path(item) in relative.parents for item in job.allowed_artifact_dirs):
                    diagnostics.append(f"artifact directory rejected: {relative}"); continue
                size = path.stat().st_size; total += size
                if total > job.max_disk_bytes: diagnostics.append("disk limit exceeded"); break
                if job.allowed_extensions and path.suffix.lower() not in job.allowed_extensions: diagnostics.append(f"artifact extension rejected: {path.name}"); continue
                if size > job.max_artifact_bytes: diagnostics.append(f"artifact limit exceeded: {path.name}"); continue
                if len(artifacts) >= job.max_artifacts: diagnostics.append("artifact count limit exceeded"); break
                modified = path not in before or path.stat().st_size != before[path]
                if job.modified_artifacts_only and not modified: continue
                artifacts.append({"path": str(relative), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "size": size, "modified": modified})
            failures = [item for item in diagnostics if not item.startswith("isolation:")]
            status = "SUCCEEDED" if process.returncode == 0 and not failures else ("FAILED" if process.returncode != 0 else "REJECTED")
            duration = int((time.monotonic() - started) * 1000); diagnostics.append(f"duration_ms={duration}")
            return SandboxJobResult(job.job_id, status, process.returncode, stdout_text, stderr_text, tuple(artifacts), tuple(diagnostics), job.run_id, job.session_id, duration)
        except OSError as error:
            return SandboxJobResult(job.job_id, "FAILED", None, "", str(error), diagnostics=("sandbox process error",), run_id=job.run_id, session_id=job.session_id)

class QAGate:
    def validate(self, result: SandboxJobResult, required_output: str | None = None) -> tuple[bool, tuple[str, ...]]:
        diagnostics = [item for item in result.diagnostics if not item.startswith(("duration_ms=", "isolation:"))]
        if result.status != "SUCCEEDED": diagnostics.append(f"sandbox status is {result.status}")
        if required_output and required_output not in result.stdout: diagnostics.append("required output not found")
        return not diagnostics, tuple(diagnostics)

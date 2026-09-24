from __future__ import annotations
from dataclasses import asdict, dataclass
from pathlib import Path
import json, re, subprocess

_SECRET_NAME = re.compile(r"(?i)(\.env|secret|credential|token|password|apikey)")

@dataclass(frozen=True)
class ProjectSnapshot:
    root: str
    architecture: dict
    modules: tuple[str, ...]
    dependencies: tuple[str, ...]
    providers: tuple[str, ...]
    apis: tuple[str, ...]
    tests: tuple[str, ...]
    ci_cd: tuple[str, ...]
    secrets_env: tuple[str, ...]
    git_releases: tuple[str, ...]
    risks: tuple[str, ...]

class ProjectScanner:
    def __init__(self, root: str | Path):
        self.root = Path(root).resolve()
    def _files(self):
        return tuple(p for p in self.root.rglob("*") if p.is_file() and ".git" not in p.parts and ".projectbrain" not in p.parts)
    def scan(self) -> ProjectSnapshot:
        files = self._files(); rel = lambda p: str(p.relative_to(self.root))
        modules = tuple(sorted({rel(p.parent) for p in files if p.suffix in {".py", ".kt", ".java", ".ts", ".tsx"}}))
        dependencies = []
        for p in files:
            if p.name.lower() in {"requirements.txt", "pyproject.toml", "package.json", "build.gradle", "build.gradle.kts", "libs.versions.toml"}:
                dependencies.append(rel(p))
        providers = tuple(sorted({p.stem for p in (self.root / "config/providers").glob("*") if p.is_file()})) if (self.root / "config/providers").exists() else ()
        text = "\n".join(p.read_text(encoding="utf-8", errors="ignore")[:200_000] for p in files if p.suffix in {".py", ".kt", ".java", ".json", ".toml"})
        apis = tuple(sorted(set(re.findall(r"(?:provider|api|endpoint)[_\- ]*([A-Za-z][\w.-]+)", text, re.I))))[:100]
        tests = tuple(sorted(rel(p) for p in files if p.name.startswith("test") or "tests" in p.parts))
        ci = tuple(sorted(rel(p) for p in files if ".github" in p.parts or p.name in {"Jenkinsfile", ".gitlab-ci.yml", "Dockerfile"}))
        secret_names = tuple(sorted(rel(p) for p in files if _SECRET_NAME.search(p.name)))
        releases = self._git("tag", "--list"); risks = []
        if not tests: risks.append("no tests detected")
        if secret_names: risks.append("secret-like filenames detected; values were not read into the snapshot")
        if not ci: risks.append("no CI/CD configuration detected")
        architecture = {"languages": sorted({p.suffix.lower() for p in files if p.suffix}), "file_count": len(files), "has_android_gradle": any(p.name.startswith("build.gradle") for p in files)}
        return ProjectSnapshot(str(self.root), architecture, modules, tuple(sorted(set(dependencies))), providers, apis, tests, ci, secret_names, releases, tuple(risks))
    def _git(self, *args: str) -> tuple[str, ...]:
        try:
            out = subprocess.run(["git", "-C", str(self.root), *args], text=True, capture_output=True, timeout=5, check=False).stdout
            return tuple(line.strip() for line in out.splitlines() if line.strip())
        except (OSError, subprocess.SubprocessError): return ()
    def write_context(self, snapshot: ProjectSnapshot | None = None) -> Path:
        snapshot = snapshot or self.scan(); target = self.root / ".projectbrain"; target.mkdir(exist_ok=True)
        data = asdict(snapshot)
        for name, value in {"architecture.json": snapshot.architecture, "providers.json": {"providers": snapshot.providers, "apis": snapshot.apis}, "dependencies.json": {"files": snapshot.dependencies}, "release-map.json": {"releases": snapshot.git_releases}, "risk-map.json": {"risks": snapshot.risks}, "context.json": data}.items():
            (target / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
        return target

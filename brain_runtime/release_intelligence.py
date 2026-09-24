from __future__ import annotations
from dataclasses import dataclass, asdict
from pathlib import Path
import subprocess

@dataclass(frozen=True)
class ReleaseChange:
    path: str
    change: str
    area: str

@dataclass(frozen=True)
class ReleaseComparison:
    base: str
    target: str
    changes: tuple[ReleaseChange, ...]
    probable_regressions: tuple[dict, ...]
    confidence: float
    def to_dict(self) -> dict: return asdict(self)

class ReleaseIntelligence:
    def __init__(self, root: str | Path): self.root = Path(root).resolve()
    def _git(self, *args: str) -> str:
        result = subprocess.run(["git", "-C", str(self.root), *args], text=True, capture_output=True, timeout=10, check=False)
        if result.returncode: raise ValueError(result.stderr.strip() or "git command failed")
        return result.stdout
    @staticmethod
    def _area(path: str) -> str:
        if path.startswith("tests/"): return "tests"
        if path.startswith("config/"): return "configuration"
        if path.startswith("brain_runtime/"): return "runtime"
        if path.startswith("app/"): return "android"
        return "other"
    def compare(self, base: str, target: str = "HEAD") -> ReleaseComparison:
        lines = self._git("diff", "--name-status", base, target).splitlines(); changes = tuple(ReleaseChange(line.split("\t", 1)[1] if "\t" in line else line, line.split("\t", 1)[0], self._area(line.split("\t", 1)[1] if "\t" in line else line)) for line in lines)
        changed_areas = {item.area for item in changes}; regressions = []
        if "runtime" in changed_areas and "tests" not in changed_areas:
            for item in changes:
                if item.area == "runtime": regressions.append({"path": item.path, "reason": "runtime changed without corresponding test change", "confidence": 0.62})
        return ReleaseComparison(base, target, changes, tuple(regressions), 0.87 if regressions else 0.45)
    def tags(self) -> tuple[str, ...]: return tuple(x for x in self._git("tag", "--list").splitlines() if x)

from __future__ import annotations
from dataclasses import dataclass, asdict
from typing import Iterable
import json

@dataclass(frozen=True)
class ReadinessReport:
    status: str
    score: int
    critical: int
    warnings: int
    blockers: tuple[str, ...]
    stages: tuple[dict, ...]
    def to_dict(self) -> dict: return asdict(self)
    @property
    def exit_code(self) -> int: return 0 if self.status == "ready" else 1

class ReadinessGate:
    ORDER = ("implementation", "tests", "qa", "security", "architecture", "regression", "release")
    def evaluate(self, stages: dict[str, bool] | Iterable[tuple[str, bool]], *, blockers: Iterable[str] = (), warnings: Iterable[str] = ()) -> ReadinessReport:
        values = dict(stages); explicit = tuple(str(item) for item in blockers); warning_items = tuple(str(item) for item in warnings)
        stage_rows = tuple({"stage": name, "passed": bool(values.get(name, False))} for name in self.ORDER)
        missing = tuple(name for name in self.ORDER if not values.get(name, False))
        all_blockers = explicit + tuple(f"stage failed: {name}" for name in missing)
        critical = len(all_blockers); score = max(0, min(100, 100 - critical * 18 - len(warning_items) * 4))
        return ReadinessReport("ready" if not all_blockers else "blocked", score, critical, len(warning_items), all_blockers, stage_rows)
    def write(self, report: ReadinessReport, path: str) -> None:
        with open(path, "w", encoding="utf-8") as stream: json.dump(report.to_dict(), stream, ensure_ascii=False, indent=2, sort_keys=True)

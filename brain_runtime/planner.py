from __future__ import annotations
from dataclasses import dataclass
from typing import Callable, Iterable
from .models import Plan, PlanStep, TaskSpec, ExecutionResult, new_id

@dataclass(frozen=True)
class Route:
    agent: str; skill: str; provider: str; reason: str

class Decomposer:
    def __init__(self, capability_rules: dict[str, tuple[str, ...]] | None = None):
        self.rules = capability_rules or {"research": ("pesquis", "research", "fonte"), "analysis": ("anal", "compare", "avalie"), "write": ("escrev", "redija", "document")}
    def decompose(self, task: TaskSpec) -> tuple[PlanStep, ...]:
        text = task.objective.lower(); selected = [name for name, words in self.rules.items() if any(word in text for word in words)]
        selected = selected or list(task.capabilities) or ["general_analysis"]
        steps = []
        previous = None
        for capability in dict.fromkeys(selected):
            step = PlanStep(new_id("step"), f"Execute {capability}", capability, parent_step_id=previous, dependencies=(previous,) if previous else ())
            steps.append(step); previous = step.step_id
        return tuple(steps)

class Planner:
    def __init__(self, decomposer: Decomposer | None = None): self.decomposer = decomposer or Decomposer()
    def create(self, task: TaskSpec) -> Plan:
        steps = self.decomposer.decompose(task); self.validate(steps)
        return Plan(new_id("plan"), task.task_id, steps, delivery_criteria=task.success_criteria)
    @staticmethod
    def validate(steps: Iterable[PlanStep]) -> None:
        items = tuple(steps); ids = {step.step_id for step in items}
        if len(ids) != len(items): raise ValueError("plan contains duplicate step ids")
        for step in items:
            if step.retry_limit < 0 or step.timeout_seconds <= 0: raise ValueError("invalid plan step limits")
            if any(dependency not in ids for dependency in step.dependencies): raise ValueError("unknown plan dependency")
        visited, visiting = set(), set()
        def visit(step_id: str):
            if step_id in visiting: raise ValueError("plan dependency cycle")
            if step_id in visited: return
            visiting.add(step_id); current = next(step for step in items if step.step_id == step_id)
            for dependency in current.dependencies: visit(dependency)
            visiting.remove(step_id); visited.add(step_id)
        for step in items: visit(step.step_id)

class DynamicRouter:
    def __init__(self, agents: dict[str, str] | None = None, skills: dict[str, str] | None = None, providers: dict[str, str] | None = None):
        self.agents, self.skills, self.providers = agents or {}, skills or {}, providers or {}
    def select(self, capability: str) -> Route:
        agent = self.agents.get(capability, self.agents.get("default", "general-agent"))
        skill = self.skills.get(capability, self.skills.get("default", "core"))
        provider = self.providers.get(capability, self.providers.get("default", "local"))
        return Route(agent, skill, provider, f"capability={capability}; agent/skill/provider registries")

class ValidationLoop:
    def __init__(self, validator: Callable[[ExecutionResult], tuple[bool, tuple[str, ...]]]): self.validator = validator
    def run(self, execute: Callable[[int, tuple[str, ...]], ExecutionResult], retry_limit: int = 1) -> tuple[ExecutionResult, tuple[str, ...], int]:
        diagnostics: tuple[str, ...] = ()
        for attempt in range(retry_limit + 1):
            result = execute(attempt, diagnostics)
            passed, diagnostics = self.validator(result)
            if passed: return result, diagnostics, attempt
        return result, diagnostics, retry_limit

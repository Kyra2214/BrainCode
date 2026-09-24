from __future__ import annotations

from dataclasses import dataclass, field, is_dataclass
from typing import Any, Callable, Mapping, get_type_hints

from .contracts import ContractError, migrate_payload, validate_payload

Migration = Callable[[dict[str, Any]], Mapping[str, Any]]


@dataclass(frozen=True)
class ContractSchema:
    """Runtime schema for one named contract version.

    ``types`` retains the original public API while accepting typing expressions
    such as ``tuple[PlanStep, ...]`` and nested dataclass models. ``model`` and
    ``migrations`` are optional additions, so existing positional construction
    remains valid.
    """

    name: str
    version: int
    required: tuple[str, ...]
    deprecated: tuple[str, ...] = ()
    types: Mapping[str, Any] | None = None
    model: type | None = None
    migrations: Mapping[int, Migration] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not isinstance(self.name, str) or not self.name.strip() or type(self.version) is not int or self.version < 1:
            raise ValueError("invalid contract schema")
        if any(not isinstance(item, str) or not item for item in (*self.required, *self.deprecated)):
            raise ValueError("invalid contract schema fields")
        if self.types is not None and not isinstance(self.types, Mapping):
            raise ValueError("schema types must be a mapping")
        if self.model is not None and (not isinstance(self.model, type) or not is_dataclass(self.model)):
            raise ValueError("schema model must be a dataclass type")
        if any(type(source) is not int or source < 0 or source >= self.version for source in self.migrations):
            raise ValueError("invalid schema migration source")
        if any(not callable(migrator) for migrator in self.migrations.values()):
            raise ValueError("invalid schema migration")


class ContractRegistry:
    def __init__(self):
        self._schemas: dict[tuple[str, int], ContractSchema] = {}
        self._migrations: dict[tuple[str, int, int], Migration] = {}

    def register(self, schema: ContractSchema) -> None:
        if not isinstance(schema, ContractSchema):
            raise TypeError("schema must be a ContractSchema")
        key = (schema.name, schema.version)
        if key in self._schemas:
            raise ValueError("contract schema already registered")
        self._schemas[key] = schema
        for source, migrator in schema.migrations.items():
            self._migrations[(schema.name, source, schema.version)] = migrator

    def register_migration(self, name: str, from_version: int, to_version: int, migrator: Migration) -> None:
        if not isinstance(name, str) or not name or not callable(migrator):
            raise ValueError("invalid contract migration")
        if type(from_version) is not int or type(to_version) is not int or from_version < 0 or to_version <= from_version:
            raise ValueError("invalid contract migration versions")
        self.schema(name, to_version)
        key = (name, from_version, to_version)
        if key in self._migrations:
            raise ValueError("contract migration already registered")
        self._migrations[key] = migrator

    def schema(self, name: str, version: int | None = None) -> ContractSchema:
        if version is None:
            return self.latest(name)
        try:
            return self._schemas[(name, version)]
        except KeyError as error:
            raise ContractError(f"unknown contract schema: {name} v{version}") from error

    def _migration(self, name: str, source: int, target: int) -> Migration | None:
        return self._migrations.get((name, source, target))

    def migrate(
        self,
        name: str,
        payload: Mapping[str, Any],
        from_version: int | None = None,
        to_version: int | None = None,
    ) -> dict[str, Any]:
        if not isinstance(payload, Mapping):
            raise ContractError("payload must be an object")
        try:
            latest = self.latest(name)
        except KeyError as error:
            raise ContractError(f"unknown contract: {name}") from error
        source = payload.get("schemaVersion", 0) if from_version is None else from_version
        if type(source) is not int:
            raise ContractError(f"invalid schema version: {source}")
        target = latest.version if to_version is None else to_version
        if type(target) is not int or target < 1:
            raise ContractError(f"invalid schema version: {target}")
        self.schema(name, target)
        if source == target:
            result = dict(payload)
            result.setdefault("schemaVersion", target)
            return result
        if source > target:
            raise ContractError(f"no migration from schema version {source} to {target}")
        result = dict(payload)
        current = source
        while current < target:
            next_version = current + 1
            migrator = self._migration(name, current, next_version)
            if migrator is None and current == 0 and next_version == 1:
                result = migrate_payload(result, 0, 1)
            elif migrator is not None:
                migrated = migrator(dict(result))
                if not isinstance(migrated, Mapping):
                    raise ContractError(f"migration from schema version {current} returned a non-object")
                result = dict(migrated)
            else:
                raise ContractError(f"no migration from schema version {current} to {target}")
            declared = result.get("schemaVersion", next_version)
            # A migration may leave the old marker untouched (the common
            # field-only migration shape); the registry owns the authoritative
            # version marker after the transformation.
            if declared == current:
                declared = next_version
            if type(declared) is not int or declared != next_version:
                raise ContractError(f"migration from schema version {current} must produce schema version {next_version}")
            result["schemaVersion"] = next_version
            current = next_version
        return result

    def validate(self, name: str, payload: Mapping[str, Any], version: int | None = None) -> dict[str, Any]:
        try:
            latest = self.latest(name)
        except KeyError as error:
            raise ContractError(f"unknown contract: {name}") from error
        actual = payload.get("schemaVersion", latest.version) if isinstance(payload, Mapping) else None
        selected_version = latest.version if version is None else version
        if type(selected_version) is not int:
            raise ContractError(f"invalid schema version: {selected_version}")
        selected = self.schema(name, selected_version)
        if version is None and type(actual) is int and actual != latest.version:
            if actual > latest.version:
                raise ContractError(f"unsupported schema version: {actual}")
            payload = self.migrate(name, payload, actual, latest.version)
            selected = latest
        elif version is not None and actual != selected.version:
            raise ContractError(f"unsupported schema version: {actual}")
        return validate_payload(
            payload,
            selected.required,
            selected.version,
            types=selected.types,
            deprecated=selected.deprecated,
        )

    def to_payload(self, name: str, value: Any, version: int | None = None) -> dict[str, Any]:
        schema = self.schema(name, version)
        from .contracts import contract_payload, validate_contract

        if schema.model is None or not isinstance(value, schema.model):
            expected = schema.model.__name__ if schema.model is not None else "dataclass"
            raise ContractError(f"contract value must be a {expected}")
        validate_contract(value, schema.required, schema.version, types=schema.types)
        return contract_payload(value, schema.required, schema.version)

    def from_payload(self, name: str, payload: Mapping[str, Any], version: int | None = None) -> Any:
        schema = self.schema(name, version)
        if schema.model is None:
            raise ContractError(f"contract schema has no model: {name} v{schema.version}")
        from .contracts import contract_from_payload

        validated = self.validate(name, payload, version)
        return contract_from_payload(validated, schema.model, schema.required, schema.version, types=schema.types)

    def latest(self, name: str) -> ContractSchema:
        matches = [schema for (schema_name, _), schema in self._schemas.items() if schema_name == name]
        if not matches:
            raise KeyError(name)
        return max(matches, key=lambda item: item.version)

    @classmethod
    def standard(cls) -> "ContractRegistry":
        registry = cls()
        from . import models

        def model_types(name: str) -> dict[str, Any]:
            model = getattr(models, name, None)
            return get_type_hints(model) if model is not None and is_dataclass(model) else {}

        registry.register(ContractSchema("TaskSpec", 1, ("task_id", "session_id", "objective"), types=model_types("TaskSpec"), model=getattr(models, "TaskSpec", None)))
        registry.register(ContractSchema("PolicyContext", 1, ("run_id", "task_id", "actor"), types=model_types("PolicyContext"), model=getattr(models, "PolicyContext", None)))
        registry.register(ContractSchema("PolicyDecision", 1, ("decision_id", "run_id", "task_id", "actor", "capability", "risk_class", "decision", "approval_required", "sandbox_required", "network_allowed", "filesystem_roots", "budget", "expires_at", "reason"), types=model_types("PolicyDecision"), model=getattr(models, "PolicyDecision", None)))
        registry.register(ContractSchema("Capability", 1, ("capability_id", "description"), types=model_types("Capability"), model=getattr(models, "Capability", None)))
        registry.register(ContractSchema("Plan", 1, ("plan_id", "task_id", "steps"), types=model_types("Plan"), model=getattr(models, "Plan", None)))
        registry.register(ContractSchema("PlanStep", 1, ("step_id", "description", "capability"), types=model_types("PlanStep"), model=getattr(models, "PlanStep", None)))
        registry.register(ContractSchema("ExecutionRequest", 1, ("request_id", "run_id", "task_id", "step_id", "capability", "objective", "policy_decision_id"), types=model_types("ExecutionRequest"), model=getattr(models, "ExecutionRequest", None)))
        registry.register(ContractSchema("ExecutionResult", 1, ("request_id", "success", "status"), types=model_types("ExecutionResult"), model=getattr(models, "ExecutionResult", None)))
        registry.register(ContractSchema("Event", 1, ("event_id", "run_id", "session_id", "task_id", "timestamp", "type", "version", "sequence", "payload"), types=model_types("Event"), model=getattr(models, "Event", None)))
        # These names are part of the existing registry API even though their
        # runtime dataclasses are intentionally owned by the sandbox boundary.
        registry.register(ContractSchema("SandboxJob", 1, ("job_id", "run_id", "session_id", "cwd"), types={"job_id": str, "run_id": str, "session_id": str, "cwd": str}))
        registry.register(ContractSchema("SandboxJobResult", 1, ("job_id", "status", "run_id", "session_id"), types={"job_id": str, "status": str, "run_id": str, "session_id": str}))
        return registry

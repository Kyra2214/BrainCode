from __future__ import annotations

import types as _types
from collections.abc import Mapping as ABCMapping, Sequence
from dataclasses import MISSING, fields, is_dataclass
from enum import Enum
from typing import Any, Callable, Literal, Mapping, Union, get_args, get_origin, get_type_hints

CURRENT_CONTRACT_VERSION = 1


class ContractError(ValueError):
    """Raised when a contract or its serialized payload is not valid."""


def _type_name(expected: Any) -> str:
    if expected is Any:
        return "any"
    return getattr(expected, "__name__", str(expected).replace("typing.", ""))


def _is_required_value(value: Any) -> bool:
    # False and zero are valid values; only absence-like values are rejected.
    return value is not None and value != ""


def _dataclass_required_fields(model: type) -> tuple[str, ...]:
    return tuple(
        item.name
        for item in fields(model)
        if item.init and item.default is MISSING and item.default_factory is MISSING
    )


def _validate_dataclass_payload(value: ABCMapping[str, Any], model: type, path: str) -> None:
    try:
        hints = get_type_hints(model)
    except (NameError, TypeError):
        hints = {}
    known = {item.name: item for item in fields(model) if item.init}
    missing = [name for name in _dataclass_required_fields(model) if name not in value or not _is_required_value(value[name])]
    if missing:
        raise ContractError(f"{path} missing required fields: {', '.join(missing)}")
    for name, item in known.items():
        if name in value and name in hints:
            _validate_type(value[name], hints[name], f"{path}.{name}")


def _validate_type(value: Any, expected: Any, path: str) -> None:
    """Validate a value against runtime and typing-module type expressions.

    Payloads are JSON-shaped, so a nested dataclass type accepts either an actual
    dataclass instance or a mapping that contains that dataclass's required fields.
    This keeps validation useful before deserialization while retaining support for
    the old ``types={"field": (list, tuple)}`` API.
    """
    if expected in (Any, object, None):
        return
    if isinstance(expected, tuple) and expected and all(isinstance(item, type) for item in expected):
        if not isinstance(value, expected):
            raise ContractError(f"invalid type for field {path}: expected {_type_name(expected)}, got {type(value).__name__}")
        return
    origin = get_origin(expected)
    args = get_args(expected)
    if origin in (Union, _types.UnionType):
        for candidate in args:
            try:
                _validate_type(value, candidate, path)
                return
            except ContractError:
                continue
        raise ContractError(f"invalid type for field {path}: expected {_type_name(expected)}, got {type(value).__name__}")
    if origin is Literal:
        if value not in args:
            raise ContractError(f"invalid value for field {path}: {value!r}")
        return
    if is_dataclass(expected) and isinstance(expected, type):
        if isinstance(value, expected):
            return
        if isinstance(value, ABCMapping):
            _validate_dataclass_payload(value, expected, path)
            return
        raise ContractError(f"invalid type for field {path}: expected {_type_name(expected)}, got {type(value).__name__}")
    if origin in (list, set, frozenset):
        if not isinstance(value, (list, tuple, set, frozenset)):
            raise ContractError(f"invalid type for field {path}: expected {origin.__name__}, got {type(value).__name__}")
        if args:
            for index, item in enumerate(value):
                _validate_type(item, args[0], f"{path}[{index}]")
        return
    if origin is tuple:
        if not isinstance(value, (list, tuple)):
            raise ContractError(f"invalid type for field {path}: expected tuple, got {type(value).__name__}")
        if args and args[-1] is Ellipsis:
            for index, item in enumerate(value):
                _validate_type(item, args[0], f"{path}[{index}]")
        elif args:
            if len(value) != len(args):
                raise ContractError(f"invalid length for field {path}: expected {len(args)}, got {len(value)}")
            for index, (item, item_type) in enumerate(zip(value, args)):
                _validate_type(item, item_type, f"{path}[{index}]")
        return
    if origin in (dict, ABCMapping):
        if not isinstance(value, ABCMapping):
            raise ContractError(f"invalid type for field {path}: expected object, got {type(value).__name__}")
        if len(args) == 2:
            key_type, value_type = args
            for key, item in value.items():
                _validate_type(key, key_type, f"{path}.<key>")
                _validate_type(item, value_type, f"{path}.{key}")
        return
    if origin in (Sequence,):
        if isinstance(value, (str, bytes)) or not isinstance(value, Sequence):
            raise ContractError(f"invalid type for field {path}: expected sequence, got {type(value).__name__}")
        if args:
            for index, item in enumerate(value):
                _validate_type(item, args[0], f"{path}[{index}]")
        return
    if expected is bool:
        valid = type(value) is bool
    elif expected is int:
        valid = type(value) is int
    elif isinstance(expected, type):
        valid = isinstance(value, expected)
    else:
        # Unresolved forward references are best handled by the dataclass
        # constructor; they should not make an otherwise valid payload fail.
        return
    if not valid:
        raise ContractError(f"invalid type for field {path}: expected {_type_name(expected)}, got {type(value).__name__}")


def validate_contract(
    value: Any,
    required: tuple[str, ...] = (),
    version: int = CURRENT_CONTRACT_VERSION,
    *,
    types: Mapping[str, Any] | None = None,
) -> Any:
    if not is_dataclass(value) or isinstance(value, type):
        raise ContractError("contract must be a dataclass instance")
    if version != CURRENT_CONTRACT_VERSION:
        raise ContractError(f"unsupported contract version: {version}")
    missing = [name for name in required if not hasattr(value, name) or not _is_required_value(getattr(value, name, None))]
    if missing:
        raise ContractError(f"missing required fields: {', '.join(missing)}")
    for name, expected in (types or {}).items():
        if hasattr(value, name):
            _validate_type(getattr(value, name), expected, name)
    return value


def validate_payload(
    payload: Mapping[str, Any],
    required: tuple[str, ...] = (),
    version: int = CURRENT_CONTRACT_VERSION,
    *,
    types: Mapping[str, Any] | None = None,
    deprecated: tuple[str, ...] = (),
) -> dict[str, Any]:
    if not isinstance(payload, ABCMapping):
        raise ContractError("payload must be an object")
    actual = payload.get("schemaVersion", version)
    if type(actual) is not int or actual != version:
        raise ContractError(f"unsupported schema version: {actual}")
    missing = [name for name in required if name not in payload or not _is_required_value(payload[name])]
    if missing:
        raise ContractError(f"missing required fields: {', '.join(missing)}")
    result = dict(payload)
    deprecated_present = [name for name in deprecated if name in result]
    if deprecated_present:
        raise ContractError(f"payload uses deprecated field: {', '.join(deprecated_present)}")
    for name, expected in (types or {}).items():
        if name in result:
            _validate_type(result[name], expected, name)
    return result


def _to_payload(value: Any) -> Any:
    if isinstance(value, Enum):
        return value.value
    if is_dataclass(value) and not isinstance(value, type):
        return {item.name: _to_payload(getattr(value, item.name)) for item in fields(value)}
    if isinstance(value, ABCMapping):
        return {key: _to_payload(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_to_payload(item) for item in value]
    if isinstance(value, tuple):
        # Preserve tuple compatibility with the original asdict-based API.
        return tuple(_to_payload(item) for item in value)
    if isinstance(value, set):
        return [_to_payload(item) for item in value]
    return value


def contract_payload(
    value: Any,
    required: tuple[str, ...] = (),
    version: int = CURRENT_CONTRACT_VERSION,
) -> dict[str, Any]:
    validate_contract(value, required, version)
    result = _to_payload(value)
    if not isinstance(result, dict):
        raise ContractError("contract payload must be an object")
    result.setdefault("schemaVersion", version)
    return result


def migrate_payload(
    payload: Mapping[str, Any],
    from_version: int,
    to_version: int = CURRENT_CONTRACT_VERSION,
    migrations: Mapping[int, Callable[[dict[str, Any]], Mapping[str, Any]]] | None = None,
) -> dict[str, Any]:
    if not isinstance(payload, ABCMapping):
        raise ContractError("payload must be an object")
    if type(from_version) is not int or type(to_version) is not int or from_version < 0 or to_version < 1:
        raise ContractError("invalid schema version")
    if from_version == to_version:
        return dict(payload)
    if from_version > to_version:
        raise ContractError(f"no migration from schema version {from_version} to {to_version}")
    result = dict(payload)
    current = from_version
    while current < to_version:
        if current == 0 and current + 1 == 1:
            result.setdefault("schemaVersion", 1)
            current = 1
            continue
        migrator = (migrations or {}).get(current)
        if migrator is None:
            raise ContractError(f"no migration from schema version {current} to {to_version}")
        migrated = migrator(dict(result))
        if not isinstance(migrated, ABCMapping):
            raise ContractError(f"migration from schema version {current} returned a non-object")
        result = dict(migrated)
        current = int(result.get("schemaVersion", current + 1))
        if current <= from_version or current > to_version:
            raise ContractError("invalid migration target version")
        result["schemaVersion"] = current
    result["schemaVersion"] = to_version
    return result


def _convert_value(value: Any, expected: Any, path: str) -> Any:
    if expected in (Any, object, None):
        return value
    origin = get_origin(expected)
    args = get_args(expected)
    if origin in (Union, _types.UnionType):
        if value is None and type(None) in args:
            return None
        for candidate in args:
            if candidate is type(None):
                continue
            try:
                _validate_type(value, candidate, path)
                return _convert_value(value, candidate, path)
            except ContractError:
                continue
        raise ContractError(f"invalid type for field {path}")
    if origin is Literal:
        _validate_type(value, expected, path)
        return value
    if is_dataclass(expected) and isinstance(expected, type):
        if isinstance(value, expected):
            return value
        if not isinstance(value, ABCMapping):
            raise ContractError(f"invalid type for field {path}")
        return _construct_dataclass(value, expected, path)
    if isinstance(expected, type) and issubclass(expected, Enum):
        if isinstance(value, expected):
            return value
        try:
            return expected(value)
        except (TypeError, ValueError):
            raise ContractError(f"invalid value for field {path}: {value!r}")
    if origin in (list, set, frozenset):
        if not isinstance(value, (list, tuple, set, frozenset)):
            raise ContractError(f"invalid type for field {path}")
        converted = [_convert_value(item, args[0], f"{path}[{index}]") for index, item in enumerate(value)] if args else list(value)
        return origin(converted)
    if origin is tuple:
        if not isinstance(value, (list, tuple)):
            raise ContractError(f"invalid type for field {path}")
        if args and args[-1] is Ellipsis:
            return tuple(_convert_value(item, args[0], f"{path}[{index}]") for index, item in enumerate(value))
        if args and len(value) != len(args):
            raise ContractError(f"invalid length for field {path}")
        return tuple(_convert_value(item, item_type, f"{path}[{index}]") for index, (item, item_type) in enumerate(zip(value, args))) if args else tuple(value)
    if origin in (dict, ABCMapping):
        if not isinstance(value, ABCMapping):
            raise ContractError(f"invalid type for field {path}")
        if len(args) == 2:
            return {_convert_value(key, args[0], f"{path}.<key>"): _convert_value(item, args[1], f"{path}.{key}") for key, item in value.items()}
        return dict(value)
    if origin is Sequence:
        if isinstance(value, (str, bytes)) or not isinstance(value, Sequence):
            raise ContractError(f"invalid type for field {path}")
        return [_convert_value(item, args[0], f"{path}[{index}]") for index, item in enumerate(value)] if args else list(value)
    if expected is bool:
        valid = type(value) is bool
    elif expected is int:
        valid = type(value) is int
    elif isinstance(expected, type):
        valid = isinstance(value, expected)
    else:
        # Unresolved forward references are best handled by the dataclass
        # constructor; they should not make an otherwise valid payload fail.
        return
    if not valid:
        raise ContractError(f"invalid type for field {path}: expected {_type_name(expected)}, got {type(value).__name__}")
    return value


def _construct_dataclass(payload: ABCMapping[str, Any], model: type, path: str = "contract") -> Any:
    try:
        hints = get_type_hints(model)
    except (NameError, TypeError):
        hints = {}
    kwargs = {
        item.name: _convert_value(payload[item.name], hints.get(item.name, item.type), f"{path}.{item.name}")
        for item in fields(model)
        if item.init and item.name in payload
    }
    try:
        return model(**kwargs)
    except TypeError as error:
        raise ContractError(f"could not construct {model.__name__}: {error}") from error


def contract_from_payload(
    payload: Mapping[str, Any],
    model: type,
    required: tuple[str, ...] = (),
    version: int = CURRENT_CONTRACT_VERSION,
    *,
    types: Mapping[str, Any] | None = None,
) -> Any:
    if not isinstance(model, type) or not is_dataclass(model):
        raise ContractError("model must be a dataclass type")
    effective_required = tuple(required) or _dataclass_required_fields(model)
    validated = validate_payload(payload, effective_required, version, types=types)
    return _construct_dataclass(validated, model)


def redact_secrets(payload: Any) -> Any:
    if isinstance(payload, dict):
        secret = {"secret", "token", "password", "api_key", "apikey", "credential", "authorization"}
        return {key: "[REDACTED]" if str(key).lower() in secret else redact_secrets(value) for key, value in payload.items()}
    if isinstance(payload, (list, tuple)):
        return [redact_secrets(item) for item in payload]
    return payload


CONTRACT_REQUIRED_FIELDS = {
    "TaskSpec": ("task_id", "session_id", "objective"),
    "PolicyContext": ("run_id", "task_id", "actor"),
    "PolicyDecision": ("decision_id", "run_id", "task_id", "actor", "capability", "risk_class", "decision", "approval_required", "sandbox_required", "network_allowed", "filesystem_roots", "budget", "expires_at", "reason"),
    "Capability": ("capability_id", "description"),
    "Plan": ("plan_id", "task_id", "steps"),
    "PlanStep": ("step_id", "description", "capability"),
    "ExecutionRequest": ("request_id", "run_id", "task_id", "step_id", "capability", "objective", "policy_decision_id"),
    "ExecutionResult": ("request_id", "status", "success"),
    "Event": ("event_id", "run_id", "session_id", "task_id", "timestamp", "type", "version", "sequence", "payload"),
    "SandboxJob": ("job_id", "run_id", "session_id", "cwd"),
    "SandboxJobResult": ("job_id", "status", "run_id", "session_id"),
}


def validate_named_contract(name: str, value: Any) -> Any:
    if name not in CONTRACT_REQUIRED_FIELDS:
        raise ContractError(f"unknown contract: {name}")
    types: dict[str, Any] = {}
    try:
        from . import models

        model = getattr(models, name, None)
        if model is not None and is_dataclass(model):
            types = get_type_hints(model)
    except (ImportError, NameError, TypeError):
        types = {}
    return validate_contract(value, CONTRACT_REQUIRED_FIELDS[name], types=types)


# A descriptive alias makes the round-trip API discoverable without changing the
# original contract_payload name used by existing callers.
payload_to_contract = contract_from_payload

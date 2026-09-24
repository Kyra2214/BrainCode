from __future__ import annotations
import json, os, threading, tomllib
from pathlib import Path
from typing import Any, Mapping
from .contracts import ContractError
from .modes import RuntimeMode, requirements

REQUIRED_SECTIONS = ("policy", "execution", "providers", "routing")
CONFIG_VERSION = 1

def validate_configuration(config: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(config, Mapping): raise ContractError("configuration must be an object")
    missing = [section for section in REQUIRED_SECTIONS if section not in config]
    if missing: raise ContractError(f"missing configuration sections: {', '.join(missing)}")
    if config.get("schemaVersion", CONFIG_VERSION) != CONFIG_VERSION: raise ContractError("unsupported configuration version")
    if any(not isinstance(config[section], Mapping) for section in REQUIRED_SECTIONS): raise ContractError("configuration sections must be objects")
    policy = config["policy"]
    if policy.get("deny_by_default") is not True: raise ContractError("policy must be deny-by-default")
    if "secrets" in config or any("secret" in str(key).lower() for key in config): raise ContractError("raw secrets cannot be part of configuration")
    execution = config["execution"]
    try:
        mode = RuntimeMode(execution.get("mode", RuntimeMode.DEVELOPMENT))
    except ValueError as error:
        raise ContractError("unsupported execution mode") from error
    if execution.get("enforce_readiness") is False and requirements(mode).require_readiness:
        raise ContractError(f"{mode.value} mode cannot disable readiness")
    if "timeout_seconds" in execution and (not isinstance(execution["timeout_seconds"], int) or execution["timeout_seconds"] <= 0): raise ContractError("invalid execution timeout")
    mode = RuntimeMode(execution.get("mode", RuntimeMode.DEVELOPMENT.value))
    req = requirements(mode)
    if req.require_sandbox and execution.get("sandbox_enabled", True) is False: raise ContractError(f"{mode.value} mode cannot disable sandbox")
    if req.require_readiness and execution.get("readiness_enabled", True) is False: raise ContractError(f"{mode.value} mode cannot disable readiness")
    result = dict(config)
    if "schemaVersion" in config: result["schemaVersion"] = CONFIG_VERSION
    return result

def assert_isolated_path(path: str | Path, allowed_root: str | Path) -> Path:
    root, candidate = Path(allowed_root).resolve(), Path(path).resolve()
    if candidate != root and root not in candidate.parents: raise PermissionError("path outside configured root")
    parts = [candidate] + list(candidate.parents) if candidate.exists() else []
    if any(part.is_symlink() for part in parts): raise PermissionError("symlink path is not allowed")
    return candidate

class ConfigurationManager:
    def __init__(self, directory: str | Path): self.directory = Path(directory); self._lock = threading.RLock(); self._config: dict[str, Any] | None = None
    def load(self) -> dict[str, Any]:
        candidate: dict[str, Any] = {"schemaVersion": CONFIG_VERSION}
        for path in sorted(self.directory.glob("*.json")):
            section = path.stem; candidate[section] = json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(self.directory.glob("*.toml")):
            section = path.stem; candidate[section] = tomllib.loads(path.read_text(encoding="utf-8"))
        validated = validate_configuration(candidate)
        with self._lock: self._config = validated
        return dict(validated)
    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            if self._config is None: return self.load()
            return dict(self._config)
    def reload(self) -> dict[str, Any]: return self.load()

def validate_cross_component(config: Mapping[str, Any]) -> dict[str, Any]:
    """Rejects configurations whose declared execution guarantees contradict Policy."""
    validated = validate_configuration(config); policy, execution, providers, routing = validated["policy"], validated["execution"], validated["providers"], validated["routing"]
    if execution.get("network_required") and not policy.get("allow_network", False): raise ContractError("execution requires network but policy disallows it")
    roots = set(execution.get("filesystem_roots", ())); policy_roots = set(policy.get("filesystem_roots", ()))
    if roots and not roots.issubset(policy_roots): raise ContractError("sandbox filesystem roots exceed policy roots")
    declared = set(routing.get("capabilities", ())) if isinstance(routing, Mapping) else set()
    if declared and not declared.issubset(set(policy.get("allowed_capabilities", ()))): raise ContractError("routing declares capability outside policy allowlist")
    for name, provider in providers.items():
        if isinstance(provider, Mapping) and provider.get("credential") and "credential_refs" in validated and provider["credential"] not in validated["credential_refs"]: raise ContractError(f"provider '{name}' references unknown credential")
    return validated

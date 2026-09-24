from __future__ import annotations
from dataclasses import dataclass
from enum import Enum

class RuntimeMode(str, Enum): DEVELOPMENT = "development"; OFFLINE = "offline"; SANDBOXED = "sandboxed"; STRICT = "strict"; PRODUCTION = "production"
@dataclass(frozen=True)
class RuntimeRequirements:
    mode: RuntimeMode; require_qa: bool; require_observability: bool; require_dispatcher: bool; require_policy: bool; allow_network: bool; require_readiness: bool; require_sandbox: bool = False

def requirements(mode: RuntimeMode | str) -> RuntimeRequirements:
    mode = RuntimeMode(mode)
    if mode is RuntimeMode.STRICT: return RuntimeRequirements(mode, True, True, True, True, False, True, True)
    if mode is RuntimeMode.SANDBOXED: return RuntimeRequirements(mode, True, True, True, True, False, True, True)
    if mode is RuntimeMode.OFFLINE: return RuntimeRequirements(mode, True, True, True, True, False, True, True)
    if mode is RuntimeMode.PRODUCTION: return RuntimeRequirements(mode, True, True, True, True, False, True, True)
    return RuntimeRequirements(mode, False, False, False, True, False, False, False)

def validate_runtime_components(mode: RuntimeMode | str, *, has_qa: bool, has_observability: bool, has_dispatcher: bool, has_policy: bool, network_allowed: bool = False, sandbox_enabled: bool = True, readiness_enabled: bool | None = None) -> RuntimeRequirements:
    req = requirements(mode)
    if req.require_qa and not has_qa: raise RuntimeError(f"{req.mode.value} mode requires QA gate")
    if req.require_observability and not has_observability: raise RuntimeError(f"{req.mode.value} mode requires observability")
    if req.require_dispatcher and not has_dispatcher: raise RuntimeError(f"{req.mode.value} mode requires dispatcher")
    if req.require_policy and not has_policy: raise RuntimeError(f"{req.mode.value} mode requires policy")
    if not req.allow_network and network_allowed: raise RuntimeError(f"{req.mode.value} mode forbids network")
    if req.require_sandbox and not sandbox_enabled: raise RuntimeError(f"{req.mode.value} mode requires sandbox")
    if req.require_readiness and readiness_enabled is False: raise RuntimeError(f"{req.mode.value} mode requires readiness gate")
    return req

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from time import monotonic
from typing import Callable, Iterable
import json
import os
import tempfile
import threading
import uuid


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _parse_time(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value)
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
    except (TypeError, ValueError):
        return None


@dataclass
class ApiCatalogEntry:
    provider: str
    model: str
    capabilities: tuple[str, ...]
    fallback_group: str
    quota_remaining: int | None = None
    latency_ms: float | None = None
    reliability: float = .5
    quality: float = .5
    healthy: bool = True
    errors: int = 0
    cooldown_until: str | None = None
    cost_per_call: float = 0.0
    credential_ref: str | None = None
    provenance: tuple[str, ...] = ()
    license: str = ""
    reserved_quota: int = 0
    last_probe_at: str | None = None
    # Optional routing hardening metadata. These fields are appended so old
    # positional construction remains valid.
    quota_limit: int | None = None
    quota_window_seconds: float | None = None
    quota_window_started_at: str | None = None
    risk_score: float = 0.0
    signature: str = ""
    signature_key_id: str = ""
    signature_verified: bool = False

    def __post_init__(self) -> None:
        if self.quota_limit is not None and self.quota_limit < 0:
            raise ValueError("quota_limit must be non-negative")
        if self.quota_window_seconds is not None and self.quota_window_seconds <= 0:
            raise ValueError("quota_window_seconds must be positive")
        if self.quota_remaining is None and self.quota_limit is not None:
            self.quota_remaining = self.quota_limit
        self.capabilities = tuple(dict.fromkeys(str(item) for item in self.capabilities if str(item)))
        self.provenance = tuple(str(item) for item in self.provenance if str(item))
        self.reliability = max(0.0, min(1.0, float(self.reliability)))
        self.quality = max(0.0, min(1.0, float(self.quality)))
        self.risk_score = max(0.0, min(1.0, float(self.risk_score)))
        self.reserved_quota = max(0, int(self.reserved_quota))

    def _refresh_window(self, now: datetime | None = None) -> bool:
        if self.quota_limit is None or self.quota_window_seconds is None:
            return False
        now = now or _now()
        started = _parse_time(self.quota_window_started_at)
        if started is None:
            self.quota_window_started_at = now.isoformat()
            if self.quota_remaining is None:
                self.quota_remaining = self.quota_limit
            return False
        if (now - started).total_seconds() < self.quota_window_seconds:
            return False
        self.quota_window_started_at = now.isoformat()
        self.quota_remaining = self.quota_limit
        # Reservations from an expired window cannot consume the new window.
        self.reserved_quota = 0
        return True

    def _refresh_health(self, now: datetime | None = None) -> bool:
        cooldown = _parse_time(self.cooldown_until)
        if cooldown is None:
            return False
        now = now or _now()
        if now < cooldown:
            return False
        self.cooldown_until = None
        self.healthy = True
        self.errors = 0
        return True

    def score(self, *, cost_weight: float = .15, quality_weight: float = .35, risk_weight: float = .1) -> float:
        self._refresh_window()
        quota = 0.0 if self.quota_remaining is not None and self.quota_remaining - self.reserved_quota <= 0 else 1.0
        latency = 0.0 if self.latency_ms is None else max(0.0, 1 - self.latency_ms / 10000)
        cost = 1.0 / (1.0 + max(0.0, self.cost_per_call))
        risk = max(0.0, 1.0 - self.risk_score)
        if self.credential_ref:
            risk = max(risk, .8)
        return self.quality * quality_weight + self.reliability * .25 + quota * .1 + latency * .1 + cost * cost_weight + risk * risk_weight

    def available(self) -> bool:
        self._refresh_window()
        self._refresh_health()
        remaining = self.quota_remaining - self.reserved_quota if self.quota_remaining is not None else None
        cooldown_over = not self.cooldown_until or (_parse_time(self.cooldown_until) is not None and _now() >= _parse_time(self.cooldown_until))
        return self.healthy and (remaining is None or remaining > 0) and cooldown_over and self.reserved_quota >= 0

    def equivalent_to(self, capability: str) -> bool:
        return capability in self.capabilities

    def as_dict(self) -> dict:
        return {
            "provider": self.provider, "model": self.model, "capabilities": list(self.capabilities),
            "fallback_group": self.fallback_group, "quota_remaining": self.quota_remaining,
            "latency_ms": self.latency_ms, "reliability": self.reliability, "quality": self.quality,
            "healthy": self.healthy, "errors": self.errors, "cooldown_until": self.cooldown_until,
            "cost_per_call": self.cost_per_call, "credential_ref": self.credential_ref,
            "provenance": list(self.provenance), "license": self.license,
            "reserved_quota": self.reserved_quota, "last_probe_at": self.last_probe_at,
            "quota_limit": self.quota_limit, "quota_window_seconds": self.quota_window_seconds,
            "quota_window_started_at": self.quota_window_started_at, "risk_score": self.risk_score,
            "signature": self.signature, "signature_key_id": self.signature_key_id,
            "signature_verified": self.signature_verified,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "ApiCatalogEntry":
        allowed = {field for field in cls.__dataclass_fields__}
        return cls(**{key: value for key, value in data.items() if key in allowed})


class DynamicApiCatalog:
    """Thread-safe API catalog with durable quota reservations and health state."""

    def __init__(self, entries: list[ApiCatalogEntry] | None = None, persistence_path: str | Path | None = None, *, state_path: str | Path | None = None):
        if persistence_path is not None and state_path is not None and Path(persistence_path) != Path(state_path):
            raise ValueError("persistence_path and state_path disagree")
        self.persistence_path = Path(persistence_path or state_path) if (persistence_path or state_path) else None
        self._entries: list[ApiCatalogEntry] = []
        self._lock = threading.RLock()
        with self._lock:
            loaded = self._load()
            by_key = {(item.provider, item.model): item for item in loaded}
            for entry in entries or ():
                old = by_key.get((entry.provider, entry.model))
                if old is not None:
                    # Constructor metadata (capabilities/license/etc.) is the
                    # source of truth, while runtime counters survive restart.
                    entry.reserved_quota = old.reserved_quota
                    entry.quota_remaining = old.quota_remaining
                    entry.latency_ms = old.latency_ms
                    entry.reliability = old.reliability
                    entry.quality = old.quality
                    entry.healthy = old.healthy
                    entry.errors = old.errors
                    entry.cooldown_until = old.cooldown_until
                    entry.quota_window_started_at = old.quota_window_started_at
                    entry.last_probe_at = old.last_probe_at
                by_key[(entry.provider, entry.model)] = entry
            self._entries = list(by_key.values())

    def _load(self) -> list[ApiCatalogEntry]:
        if self.persistence_path is None or not self.persistence_path.exists():
            return []
        try:
            payload = json.loads(self.persistence_path.read_text(encoding="utf-8"))
            items = payload.get("entries", []) if isinstance(payload, dict) else payload
            return [ApiCatalogEntry.from_dict(item) for item in items if isinstance(item, dict)]
        except (OSError, ValueError, TypeError, KeyError):
            return []

    def _persist(self) -> None:
        if self.persistence_path is None:
            return
        self.persistence_path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps({"version": 1, "entries": [item.as_dict() for item in self._entries]}, ensure_ascii=False, sort_keys=True)
        fd, temporary = tempfile.mkstemp(prefix=f".{self.persistence_path.name}.", dir=str(self.persistence_path.parent))
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.persistence_path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def _find(self, provider: str, model: str) -> ApiCatalogEntry:
        entry = next((item for item in self._entries if item.provider == provider and item.model == model), None)
        if entry is None:
            raise KeyError(f"API não registrada: {provider}/{model}")
        return entry

    def register(self, entry: ApiCatalogEntry) -> None:
        if not entry.capabilities or not entry.provider or not entry.model or not entry.fallback_group:
            raise ValueError("invalid API catalog entry")
        with self._lock:
            self._entries.append(entry)
            self._persist()

    def candidates(self, capability: str, fallback_group: str | None = None, *, risk_limit: float = 1.0) -> list[ApiCatalogEntry]:
        with self._lock:
            for entry in self._entries:
                entry._refresh_window()
                entry._refresh_health()
            return sorted(
                (entry for entry in self._entries if entry.equivalent_to(capability) and entry.risk_score <= risk_limit and entry.available() and (fallback_group is None or entry.fallback_group == fallback_group)),
                key=lambda item: item.score(), reverse=True,
            )

    def select(self, capability: str, fallback_group: str | None = None, *, risk_limit: float = 1.0) -> ApiCatalogEntry:
        candidates = self.candidates(capability, fallback_group, risk_limit=risk_limit)
        if not candidates:
            raise LookupError(f"nenhuma API saudável para {capability}")
        return candidates[0]

    def reserve(self, provider: str, model: str, amount: int = 1) -> ApiCatalogEntry:
        if amount <= 0:
            raise ValueError("reservation amount must be positive")
        with self._lock:
            entry = self._find(provider, model)
            entry._refresh_window()
            entry._refresh_health()
            remaining = entry.quota_remaining - entry.reserved_quota if entry.quota_remaining is not None else None
            if not entry.healthy or (remaining is not None and remaining < amount) or entry.cooldown_until:
                raise LookupError("API indisponível ou quota exhausted")
            entry.reserved_quota += amount
            self._persist()
            return entry

    def reconcile(self, provider: str, model: str, *, used: int = 1, quota_remaining: int | None = None, reservation_id: str | None = None) -> None:
        if used < 0:
            raise ValueError("used must be non-negative")
        with self._lock:
            entry = self._find(provider, model)
            entry._refresh_window()
            release = min(entry.reserved_quota, used)
            entry.reserved_quota -= release
            if quota_remaining is not None:
                entry.quota_remaining = max(0, quota_remaining)
            elif entry.quota_remaining is not None:
                # A reservation is optimistic; reconciliation makes local
                # accounting durable even when the provider has no response.
                entry.quota_remaining = max(0, entry.quota_remaining - release)
            self._persist()

    def record(self, provider: str, model: str, success: bool, latency_ms: float, quota_remaining: int | None = None) -> None:
        with self._lock:
            entry = self._find(provider, model)
            entry._refresh_window()
            entry.latency_ms = max(0.0, float(latency_ms))
            if quota_remaining is not None:
                entry.quota_remaining = max(0, quota_remaining)
            entry.reliability = min(1.0, max(0.0, entry.reliability * .8 + (.2 if success else 0)))
            if success:
                entry.errors = 0
                entry.healthy = True
                entry.cooldown_until = None
            else:
                entry.errors += 1
                delay = min(300, 2 ** min(entry.errors, 8))
                entry.cooldown_until = (_now() + timedelta(seconds=delay)).isoformat()
                # Unhealthy is a circuit-open state, not a permanent revoke.
                entry.healthy = False
            self._persist()

    def probe(self, provider: str, model: str, check: Callable[[ApiCatalogEntry], bool]) -> bool:
        with self._lock:
            entry = self._find(provider, model)
            try:
                ok = bool(check(entry))
            except Exception:
                ok = False
            entry.last_probe_at = _now().isoformat()
            self.record(provider, model, ok, entry.latency_ms or 0)
            return ok

    def waterfall(self, capability: str, call: Callable[[ApiCatalogEntry], object], fallback_group: str | None = None) -> tuple[ApiCatalogEntry, object]:
        """Try equivalent-capability providers in score order, preserving quota accounting."""
        candidates = self.candidates(capability, fallback_group)
        if not candidates:
            raise LookupError(f"nenhuma API saudável para {capability}")
        last_error: Exception | None = None
        for entry in candidates:
            reserved = False
            started = monotonic()
            try:
                self.reserve(entry.provider, entry.model)
                reserved = True
                result = call(entry)
                elapsed = (monotonic() - started) * 1000
                self.reconcile(entry.provider, entry.model)
                self.record(entry.provider, entry.model, True, elapsed)
                return entry, result
            except Exception as error:
                last_error = error
                if reserved:
                    self.reconcile(entry.provider, entry.model)
                    self.record(entry.provider, entry.model, False, (monotonic() - started) * 1000)
        raise RuntimeError("todas as APIs equivalentes do grupo de fallback falharam") from last_error

    def entries(self) -> tuple[ApiCatalogEntry, ...]:
        with self._lock:
            return tuple(self._entries)

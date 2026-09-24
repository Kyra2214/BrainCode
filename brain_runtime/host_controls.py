from __future__ import annotations
import os, shutil, sqlite3, subprocess, threading, time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable

@dataclass(frozen=True)
class HostCapabilities:
    unshare: bool; bubblewrap: bool; cgroup_v2: bool; cgroup_writable: bool; user_namespace: bool
    def strict_isolation_available(self) -> bool: return self.bubblewrap or (self.unshare and self.user_namespace)
class HostCapabilityProbe:
    def probe(self) -> HostCapabilities:
        unshare = shutil.which("unshare") is not None; bubblewrap = shutil.which("bwrap") is not None; cgroup = Path("/sys/fs/cgroup"); v2 = (cgroup / "cgroup.controllers").exists(); writable = os.access(cgroup, os.W_OK); user_ns = False
        if unshare:
            try: user_ns = subprocess.run([shutil.which("unshare"), "--user", "--map-root-user", "true"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2).returncode == 0
            except (OSError, subprocess.SubprocessError): pass
        return HostCapabilities(unshare, bubblewrap, v2, writable, user_ns)
class CgroupController:
    def __init__(self, path: str | Path, *, create: bool = False): self.path = Path(path); self.path.mkdir(parents=True, exist_ok=True) if create else None
    @property
    def available(self) -> bool: return (self.path / "cgroup.procs").exists()
    def configure(self, *, memory_mb: int | None = None, pids: int | None = None, cpu_seconds: int | None = None) -> None:
        if not self.available: raise PermissionError("cgroup controller is unavailable or not writable")
        values = {}; memory_mb is not None and values.update({"memory.max": str(memory_mb * 1024 * 1024)}); pids is not None and values.update({"pids.max": str(pids)}); cpu_seconds is not None and values.update({"cpu.max": f"{max(1, cpu_seconds * 100000)} 100000"})
        for filename, value in values.items():
            target = self.path / filename
            if target.exists(): target.write_text(value, encoding="ascii")
    def attach(self, pid: int) -> None:
        if not self.available: raise PermissionError("cgroup controller is unavailable or not writable")
        (self.path / "cgroup.procs").write_text(str(pid), encoding="ascii")
@dataclass(frozen=True)
class Lease:
    key: str; owner: str; fencing_token: int; expires_at: str
    @property
    def expired(self) -> bool: return datetime.now(timezone.utc) >= datetime.fromisoformat(self.expires_at)
class DistributedLeaseStore:
    def __init__(self, path: str | Path = ":memory:"):
        self._lock = threading.RLock(); self._db = sqlite3.connect(str(path), check_same_thread=False, isolation_level="IMMEDIATE"); self._db.execute("CREATE TABLE IF NOT EXISTS leases (key TEXT PRIMARY KEY, owner TEXT NOT NULL, token INTEGER NOT NULL, expires_at TEXT NOT NULL)"); self._db.execute("CREATE TABLE IF NOT EXISTS lease_counters (key TEXT PRIMARY KEY, token INTEGER NOT NULL)"); self._db.commit()
    def acquire(self, key: str, owner: str, ttl_seconds: int = 300) -> Lease:
        if ttl_seconds <= 0: raise ValueError("ttl must be positive")
        expires = (datetime.now(timezone.utc) + timedelta(seconds=ttl_seconds)).isoformat()
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE"); row = self._db.execute("SELECT owner, token, expires_at FROM leases WHERE key=?", (key,)).fetchone()
            if row and datetime.now(timezone.utc) < datetime.fromisoformat(row[2]) and row[0] != owner: self._db.rollback(); raise RuntimeError("lease held by another owner")
            counter = self._db.execute("SELECT token FROM lease_counters WHERE key=?", (key,)).fetchone(); token = counter[0] + 1 if counter else 1; self._db.execute("INSERT OR REPLACE INTO lease_counters VALUES (?, ?)", (key, token)); self._db.execute("INSERT OR REPLACE INTO leases VALUES (?, ?, ?, ?)", (key, owner, token, expires)); self._db.commit(); return Lease(key, owner, token, expires)
    def renew(self, lease: Lease, ttl_seconds: int = 300) -> Lease:
        if ttl_seconds <= 0: raise ValueError("ttl must be positive")
        expires = (datetime.now(timezone.utc) + timedelta(seconds=ttl_seconds)).isoformat()
        with self._lock:
            row = self._db.execute("SELECT owner, token, expires_at FROM leases WHERE key=?", (lease.key,)).fetchone()
            if not row or row[0] != lease.owner or row[1] != lease.fencing_token or datetime.now(timezone.utc) >= datetime.fromisoformat(row[2]): raise PermissionError("lease is stale")
            self._db.execute("UPDATE leases SET expires_at=? WHERE key=? AND owner=? AND token=?", (expires, lease.key, lease.owner, lease.fencing_token)); self._db.commit(); return Lease(lease.key, lease.owner, lease.fencing_token, expires)
    def assert_current(self, lease: Lease) -> None:
        row = self._db.execute("SELECT owner, token, expires_at FROM leases WHERE key=?", (lease.key,)).fetchone()
        if not row or row[0] != lease.owner or row[1] != lease.fencing_token or datetime.now(timezone.utc) >= datetime.fromisoformat(row[2]): raise PermissionError("lease fencing token is stale")
    def release(self, lease: Lease) -> None:
        with self._lock: self._db.execute("DELETE FROM leases WHERE key=? AND owner=? AND token=?", (lease.key, lease.owner, lease.fencing_token)); self._db.commit()
    def close(self) -> None: self._db.close()
class LeaseHeartbeat:
    def __init__(self, store: DistributedLeaseStore, lease: Lease, ttl_seconds: int = 30): self.store, self.lease, self.ttl_seconds, self._stop, self._thread = store, lease, ttl_seconds, threading.Event(), None
    def start(self) -> None:
        def loop():
            while not self._stop.wait(max(.1, self.ttl_seconds / 3)):
                try: self.lease = self.store.renew(self.lease, self.ttl_seconds)
                except (PermissionError, RuntimeError): self._stop.set()
        self._thread = threading.Thread(target=loop, name="lease-heartbeat", daemon=True); self._thread.start()
    def stop(self) -> None: self._stop.set(); self._thread and self._thread.join(timeout=1)

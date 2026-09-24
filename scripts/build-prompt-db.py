#!/usr/bin/env python3
from pathlib import Path
import hashlib
import sqlite3
import sys

root = Path(__file__).resolve().parents[1]
sql_path = root / "app/src/main/assets/prompts_library.sql"
out_path = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "build/releases/braincode-prompts-initial.sqlite"
out_path.parent.mkdir(parents=True, exist_ok=True)
if out_path.exists():
    out_path.unlink()

with sqlite3.connect(out_path) as db:
    db.execute("PRAGMA journal_mode=DELETE")
    db.execute("PRAGMA foreign_keys=OFF")
    db.executescript(sql_path.read_text(encoding="utf-8"))
    db.commit()
    integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
    table_count = db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").fetchone()[0]
    prompt_rows = db.execute("SELECT SUM(rows) FROM (SELECT COUNT(*) AS rows FROM prompts UNION ALL SELECT COUNT(*) FROM extra_prompts)").fetchone()[0]

if integrity != "ok":
    raise SystemExit(f"integrity_check failed: {integrity}")
if table_count != 24:
    raise SystemExit(f"unexpected table count: {table_count} (expected 24)")
if prompt_rows is None:
    raise SystemExit("prompt row check failed")

size = out_path.stat().st_size
sha = hashlib.sha256(out_path.read_bytes()).hexdigest()
print(f"path={out_path}")
print(f"size={size}")
print(f"sha256={sha}")
print(f"tables={table_count}")
print(f"prompts_plus_extra={prompt_rows}")

#!/usr/bin/env python3
"""Lista arquivos de produção sem referência textual em outro arquivo de produção.
Informativo por desenho: nunca falha o CI.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = [p for p in ROOT.rglob("*") if p.is_file() and ".git" not in p.parts and ("src/main" in p.as_posix() or p.parts[0] in {"brain_runtime", "scripts"})]
TEXT = [p for p in ROOT.rglob("*") if p.is_file() and ".git" not in p.parts and p.suffix in {".kt", ".java", ".py", ".md", ".sh", ".json", ".toml"}]
corpus = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in TEXT)
for path in sorted(PRODUCTION):
    rel = path.relative_to(ROOT).as_posix()
    stem = path.stem
    if corpus.count(rel) <= 1 and corpus.count(stem) <= 1:
        print(rel)

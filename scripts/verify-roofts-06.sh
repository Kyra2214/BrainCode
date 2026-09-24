#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
asset_root="$repo_root/app/src/main/assets/roofts/0.6"
manifest="$repo_root/app/src/main/assets/roofts/0.6.manifest.json"

[[ -d "$asset_root" ]] || { echo "missing: $asset_root" >&2; exit 1; }
[[ -f "$manifest" ]] || { echo "missing: $manifest" >&2; exit 1; }
for required in README.md LICENSE AGENTS.md; do
  [[ -r "$asset_root/$required" ]] || { echo "missing: $required" >&2; exit 1; }
done

python3 - "$asset_root" "$manifest" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = json.loads(pathlib.Path(sys.argv[2]).read_text())
assert manifest["rooftsVersion"] == "0.6"
assert manifest["upstreamRepository"] == "addyosmani/agent-skills"
assert len(manifest["upstreamCommit"]) == 40
assert manifest["upstreamTag"] == "0.6.10"
assert manifest["status"] == "INSTALLED_NOT_INTEGRATED"
assert manifest["installation"]["rootfsPath"] == "/opt/roofts/0.6"
assert manifest["installation"]["skillIntegration"] == "NOT_STARTED"

for name, expected in manifest["integrity"].items():
    actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
    assert actual == expected, f"{name}: {actual} != {expected}"

for directory in ("skills", "agents", "references", "docs", "hooks", "scripts"):
    assert (root / directory).is_dir(), directory
    assert any((root / directory).rglob("*")), directory

actual_files = sum(1 for p in root.rglob("*") if p.is_file())
assert actual_files == manifest["content"]["allFiles"], (actual_files, manifest["content"]["allFiles"])
print(f"PASS Roofts 0.6: {actual_files} files; commit {manifest['upstreamCommit']}; tag {manifest['upstreamTag']}")
PY

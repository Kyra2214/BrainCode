#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${1:-$ROOT_DIR/.e2e-rootfs-host}"
EXTRACTED="$WORK_DIR/rootfs"
mkdir -p "$WORK_DIR/artifacts" "$EXTRACTED"

manifests=(
  "$ROOT_DIR/app/src/main/res/raw/rootfs_manifest.json"
  "$ROOT_DIR/rootfs-builder/agent_extra_manifest.json"
  "$ROOT_DIR/rootfs-builder/agent_android_manifest.json"
)

for manifest in "${manifests[@]}"; do
  name="$(jq -r '.url | split("/") | last' "$manifest")"
  url="$(jq -r '.url' "$manifest")"
  expected_size="$(jq -r '.sizeBytes' "$manifest")"
  expected_sha="$(jq -r '.sha256' "$manifest" | tr '[:upper:]' '[:lower:]')"
  artifact="$WORK_DIR/artifacts/$name"
  echo "==> Download/verificação: $name"
  if [[ ! -s "$artifact" ]] || [[ "$(stat -c '%s' "$artifact")" != "$expected_size" ]]; then
    curl --fail --location --retry 3 --retry-delay 2 --continue-at - --output "$artifact" "$url"
  fi
  actual_size="$(stat -c '%s' "$artifact")"
  actual_sha="$(sha256sum "$artifact" | awk '{print tolower($1)}')"
  [[ "$actual_size" == "$expected_size" ]] || { echo "FAIL: tamanho $name: $actual_size != $expected_size" >&2; exit 1; }
  [[ "$actual_sha" == "$expected_sha" ]] || { echo "FAIL: SHA-256 $name: $actual_sha != $expected_sha" >&2; exit 1; }
  echo "PASS: $name size=$actual_size sha256=$actual_sha"
  echo "==> Inspecionando conteúdo: $name"
  tar -tzf "$artifact" >/dev/null
  echo "PASS: tar.gz legível: $name"
  echo "==> Extraindo sobre a camada anterior: $name"
  tar --no-same-owner --no-same-permissions -xzf "$artifact" -C "$EXTRACTED"
done

echo "==> Validando árvore composta"
for path in usr/bin/bash usr/bin/dash bin/bash home/sandbox etc/resolv.conf; do
  [[ -e "$EXTRACTED/$path" || -L "$EXTRACTED/$path" ]] || { echo "FAIL: ausente: $path" >&2; exit 1; }
  echo "PASS: $path"
done

python3 - "$EXTRACTED" <<'PY'
import os, pathlib, sys
root = pathlib.Path(sys.argv[1]).resolve()
problems = []
symlinks = 0
for path in root.rglob('*'):
    if path.is_symlink():
        symlinks += 1
        target = os.readlink(path)
        # Symlinks absolutos são absolutos no guest RootFS, não no host.
        candidate = (root / target.lstrip('/')) if target.startswith('/') else (path.parent / target)
        resolved = pathlib.Path(os.path.normpath(str(candidate)))
        if resolved != root and root not in resolved.parents:
            problems.append(f'{path.relative_to(root)} -> {target} escapes rootfs')
print(f'composed_rootfs={root}')
print(f'symlinks={symlinks}')
if problems:
    for problem in problems[:20]: print(f'FAIL: {problem}', file=sys.stderr)
    raise SystemExit(1)
PY

printf 'rootfs_files='; find "$EXTRACTED" -type f | wc -l
printf 'rootfs_dirs='; find "$EXTRACTED" -type d | wc -l
printf 'rootfs_size='; du -sh "$EXTRACTED" | awk '{print $1}'
echo "E2E ROOTFS HOST PASSED: três camadas verificadas, extraídas e compostas em $EXTRACTED"

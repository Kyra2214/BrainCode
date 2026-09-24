#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_MANIFEST="$ROOT_DIR/app/src/main/res/raw/rootfs_manifest.json"
EXTRA_MANIFEST="$ROOT_DIR/rootfs-builder/agent_extra_manifest.json"
ANDROID_MANIFEST="$ROOT_DIR/rootfs-builder/agent_android_manifest.json"

for command in curl jq sha256sum; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "FAIL: comando ausente: $command" >&2
    exit 2
  }
done

check_manifest() {
  local manifest="$1"
  local label="$2"
  local url size sha sidecar actual_size sidecar_sha

  [[ -f "$manifest" ]] || { echo "FAIL [$label]: manifesto ausente: $manifest" >&2; return 1; }
  url="$(jq -er '.url' "$manifest")"
  size="$(jq -er '.sizeBytes' "$manifest")"
  sha="$(jq -er '.sha256' "$manifest" | tr '[:upper:]' '[:lower:]')"
  [[ "$url" == https://github.com/Kyra2214/BrainCode/releases/download/* ]] || {
    echo "FAIL [$label]: URL não aponta para release BrainCode: $url" >&2
    return 1
  }
  [[ "$sha" =~ ^[0-9a-f]{64}$ ]] || { echo "FAIL [$label]: SHA-256 inválido" >&2; return 1; }
  [[ "$size" =~ ^[0-9]+$ ]] || { echo "FAIL [$label]: tamanho inválido" >&2; return 1; }

  actual_size="$(curl -fsSIL --max-redirs 5 "$url" | awk 'BEGIN { IGNORECASE=1 } /^content-length:/ { gsub("\r", "", $2); value=$2 } END { if (value == "") exit 1; print value }')"
  [[ "$actual_size" == "$size" ]] || {
    echo "FAIL [$label]: tamanho manifesto=$size publicado=$actual_size" >&2
    return 1
  }

  sidecar="${url}.sha256"
  sidecar_sha="$(curl -fsSL --max-redirs 5 "$sidecar" | awk 'NF { print tolower($1); exit }')"
  [[ "$sidecar_sha" == "$sha" ]] || {
    echo "FAIL [$label]: sidecar=$sidecar_sha manifesto=$sha" >&2
    return 1
  }
  echo "PASS [$label]: size=$size sha256=$sha"
}

check_manifest "$ROOT_MANIFEST" "rootfs-v0.3.3"
check_manifest "$EXTRA_MANIFEST" "rootfs-agent-v0.4.1"
check_manifest "$ANDROID_MANIFEST" "rootfs-agent-android-v0.5.0"
echo "Release readiness preflight passed: manifests, assets e sidecars consistentes."

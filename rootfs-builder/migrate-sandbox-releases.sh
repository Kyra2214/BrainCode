#!/usr/bin/env bash
set -euo pipefail

# Migrates the already validated SandBox rootfs release assets to BrainCode.
# IMPORTANT: this script does NOT rebuild or modify any rootfs.

SOURCE_REPO="${SOURCE_REPO:-Kyra2214/SandBox}"
TARGET_REPO="${TARGET_REPO:-Kyra2214/BrainCode}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${WORK_DIR:-$REPO_ROOT/output/sandbox-release-migration}"
cd "$REPO_ROOT"

mkdir -p "$WORK_DIR"
command -v gh >/dev/null || { echo "ERRO: gh CLI não encontrado." >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "ERRO: sha256sum não encontrado." >&2; exit 1; }

declare -a RELEASES=(
  "rootfs-v0.3.3|rootfs-ubuntu-0.3.3.tar.gz|a43f0915d5cd6e2e0c8640b8b30855d54f7b8873ca3be95acca769100b1487f4|1075455791|RootFS 0.3.3 — Node.js 20 LTS"
  "rootfs-agent-v0.4.1|rootfs-agent-extra-0.4.1.tar.gz|ffe23b4bb326bfd9ef7548f6378d9ca5e6c75fce342ee7c7923203492cd4850b|1727218090|Agent Extra 0.4.1 — Data, Security and API Toolchain"
  "rootfs-agent-android-v0.5.0|rootfs-agent-android-0.5.0.tar.gz|374e795ce3caa8eaf4cbb58f39d591230918f5226ec525d0140b5f94ff472c09|1764603872|Agent Android/API 0.5.0"
)

for entry in "${RELEASES[@]}"; do
  IFS='|' read -r TAG ASSET EXPECTED_SHA EXPECTED_SIZE TITLE <<< "$entry"
  DEST="$WORK_DIR/$ASSET"

  echo "==> Migrando $TAG"
  rm -f "$DEST" "$DEST.sha256"

  # Download the exact asset from the validated SandBox release.
  gh release download "$TAG" --repo "$SOURCE_REPO" --pattern "$ASSET" --dir "$WORK_DIR" --clobber

  ACTUAL_SIZE="$(stat -c%s "$DEST" 2>/dev/null || stat -f%z "$DEST")"
  ACTUAL_SHA="$(sha256sum "$DEST" | cut -d' ' -f1)"

  [[ "$ACTUAL_SIZE" == "$EXPECTED_SIZE" ]] || {
    echo "ERRO: tamanho inesperado para $ASSET: $ACTUAL_SIZE (esperado $EXPECTED_SIZE)." >&2
    exit 1
  }
  [[ "$ACTUAL_SHA" == "$EXPECTED_SHA" ]] || {
    echo "ERRO: SHA-256 divergente para $ASSET: $ACTUAL_SHA (esperado $EXPECTED_SHA)." >&2
    exit 1
  }

  # Copy the original sidecar too; do not regenerate or normalize it.
  gh release download "$TAG" --repo "$SOURCE_REPO" --pattern "$ASSET.sha256" --dir "$WORK_DIR" --clobber
  test -f "$DEST.sha256"

  # Re-publish the byte-identical artifact under the same release tag/name in BrainCode.
  if gh release view "$TAG" --repo "$TARGET_REPO" >/dev/null 2>&1; then
    echo "==> Release $TAG já existe no destino; adicionando/verificando asset."
    gh release upload "$TAG" "$DEST" "$DEST.sha256" --repo "$TARGET_REPO" --clobber
  else
    gh release create "$TAG" "$DEST" "$DEST.sha256" \
      --repo "$TARGET_REPO" \
      --title "$TITLE" \
      --notes "Artefato migrado do release validado de $SOURCE_REPO. Byte-identical; nenhum rebuild do RootFS foi realizado. SHA-256 preservado e verificado antes da publicação."
  fi

done

# Point the BrainCode manifests to the migrated releases only after all source
# assets have passed the exact size/SHA-256 checks and were published.
ROOT_MANIFEST="$REPO_ROOT/app/src/main/res/raw/rootfs_manifest.json"
EXTRA_MANIFEST="$REPO_ROOT/rootfs-builder/agent_extra_manifest.json"
ANDROID_MANIFEST="$REPO_ROOT/rootfs-builder/agent_android_manifest.json"

python3 - "$ROOT_MANIFEST" "$EXTRA_MANIFEST" "$ANDROID_MANIFEST" "$TARGET_REPO" <<'PY'
import json
import pathlib
import sys

root, extra, android, target_repo = map(pathlib.Path, sys.argv[1:])

updates = {
    root: {
        "version": "0.3.3",
        "arch": "arm64-v8a",
        "distro": "ubuntu-24.04",
        "url": f"https://github.com/{target_repo}/releases/download/rootfs-v0.3.3/rootfs-ubuntu-0.3.3.tar.gz",
        "sizeBytes": 1075455791,
        "sha256": "a43f0915d5cd6e2e0c8640b8b30855d54f7b8873ca3be95acca769100b1487f4",
        "minAppVersion": "1.0.0",
    },
    extra: {
        "version": "0.4.1",
        "arch": "arm64-v8a",
        "baseRootfs": "0.3.3",
        "distro": "ubuntu-24.04",
        "url": f"https://github.com/{target_repo}/releases/download/rootfs-agent-v0.4.1/rootfs-agent-extra-0.4.1.tar.gz",
        "sizeBytes": 1727218090,
        "sha256": "ffe23b4bb326bfd9ef7548f6378d9ca5e6c75fce342ee7c7923203492cd4850b",
        "minAppVersion": "1.0.0",
    },
    android: {
        "version": "0.5.0",
        "arch": "arm64-v8a",
        "baseRootfs": "0.4.1",
        "distro": "ubuntu-24.04",
        "url": f"https://github.com/{target_repo}/releases/download/rootfs-agent-android-v0.5.0/rootfs-agent-android-0.5.0.tar.gz",
        "sizeBytes": 1764603872,
        "sha256": "374e795ce3caa8eaf4cbb58f39d591230918f5226ec525d0140b5f94ff472c09",
        "minAppVersion": "1.0.0",
        "ollamaIncluded": False,
    },
}

for path, data in updates.items():
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"updated {path}")
PY

echo
echo "==> Migração concluída."
echo "Os três artefatos foram copiados byte-a-byte, verificados por tamanho/SHA-256 e publicados no BrainCode."
echo "Revise o git diff e faça o commit dos manifests/documentação."

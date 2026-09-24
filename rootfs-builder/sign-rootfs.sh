#!/usr/bin/env bash
set -euo pipefail

ARTIFACT="${1:?uso: sign-rootfs.sh <rootfs.tar.gz> [signature-output]>}"
OUTPUT="${2:-${ARTIFACT}.sig}"

if [[ ! -f "$ARTIFACT" ]]; then
  echo "artefato não encontrado: $ARTIFACT" >&2
  exit 2
fi

case "${ROOTFS_SIGNING_TOOL:-}" in
  minisign)
    : "${MINISIGN_SECRET_KEY:?MINISIGN_SECRET_KEY é obrigatório}"
    minisign -Sm "$ARTIFACT" -s "$MINISIGN_SECRET_KEY" -x "$OUTPUT"
    echo "minisign:$OUTPUT"
    ;;
  cosign)
    : "${COSIGN_KEY:?COSIGN_KEY é obrigatório}"
    cosign sign-blob --yes --key "$COSIGN_KEY" --output-signature "$OUTPUT" "$ARTIFACT"
    echo "cosign:$OUTPUT"
    ;;
  *)
    echo "ROOTFS_SIGNING_TOOL deve ser minisign ou cosign" >&2
    exit 2
    ;;
esac

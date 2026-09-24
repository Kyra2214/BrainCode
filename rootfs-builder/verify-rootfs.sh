#!/usr/bin/env bash
set -euo pipefail

ARTIFACT="${1:?uso: verify-rootfs.sh <rootfs.tar.gz> <assinatura> [chave-publica]>}"
SIGNATURE="${2:?uso: verify-rootfs.sh <rootfs.tar.gz> <assinatura> [chave-publica]>}"
PUBLIC_KEY="${3:-${MINISIGN_PUBLIC_KEY:-}}"

[[ -f "$ARTIFACT" && -f "$SIGNATURE" ]] || { echo "artefato ou assinatura ausente" >&2; exit 2; }

case "${ROOTFS_SIGNING_TOOL:-}" in
  minisign)
    [[ -n "$PUBLIC_KEY" ]] || { echo "MINISIGN_PUBLIC_KEY ou terceiro argumento é obrigatório" >&2; exit 2; }
    minisign -Vm "$ARTIFACT" -x "$SIGNATURE" -P "$PUBLIC_KEY"
    ;;
  cosign)
    : "${COSIGN_PUBLIC_KEY:?COSIGN_PUBLIC_KEY é obrigatório}"
    cosign verify-blob --key "$COSIGN_PUBLIC_KEY" --signature "$SIGNATURE" "$ARTIFACT"
    ;;
  *)
    echo "ROOTFS_SIGNING_TOOL deve ser minisign ou cosign" >&2
    exit 2
    ;;
esac

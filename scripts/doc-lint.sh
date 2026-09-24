#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
status=0
while IFS= read -r f; do
  while IFS= read -r p; do
    [[ -z "$p" ]] && continue
    if [[ ! -e "$ROOT_DIR/$p" ]]; then
      echo "LINK QUEBRADO: $f -> $p" >&2
      status=1
    fi
  done < <(grep -oE 'docs/[A-Za-z0-9_./-]+\.md' "$ROOT_DIR/$f" | sort -u || true)
done < <(find "$ROOT_DIR" -maxdepth 2 -type f \( -name 'README.md' -o -name 'CLAUDE.md' -o -name 'AGENTS.md' -o -path "$ROOT_DIR/docs/*.md" \) -printf '%P\n' | sort)
if [[ -f "$ROOT_DIR/docs/LEGADO_E_DECISOES.md" ]]; then
  while IFS= read -r p; do
    [[ -z "$p" ]] && continue
    [[ -e "$ROOT_DIR/$p" ]] && { echo "ARQUIVO DECLARADO COMO APAGADO AINDA EXISTE: $p" >&2; status=1; }
  done < <(grep -iE 'removid|apagado|delet' "$ROOT_DIR/docs/LEGADO_E_DECISOES.md" | grep -oE '`[^`]+`' | sed -E 's/^`|`$//g' | grep -E '^[A-Za-z0-9_./-]+\.(kt|java|py|md|sh)$' | sort -u || true)
fi
exit "$status"

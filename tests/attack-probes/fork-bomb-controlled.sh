#!/usr/bin/env bash
set -euo pipefail
[[ "${PROBE_ALLOW:-}" == 1 ]] || { echo 'refusing probe without PROBE_ALLOW=1' >&2; exit 2; }
MAX_CHILDREN="${MAX_CHILDREN:-4}"
(( MAX_CHILDREN >= 1 && MAX_CHILDREN <= 8 )) || { echo 'MAX_CHILDREN must be 1..8' >&2; exit 2; }
pids=()
cleanup() { for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap cleanup EXIT INT TERM
for _ in $(seq 1 "$MAX_CHILDREN"); do (sleep 30) & pids+=("$!"); done
sleep 0.1
alive=0
for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null && alive=$((alive + 1)) || true; done
printf 'controlled_children=%s\n' "$alive"
(( alive <= MAX_CHILDREN ))

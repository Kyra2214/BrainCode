#!/usr/bin/env bash
set -euo pipefail
[[ "${PROBE_ALLOW:-}" == 1 ]] || { echo 'refusing probe without PROBE_ALLOW=1' >&2; exit 2; }
input="${1:-}"
[[ -f "$input" ]] || { echo 'usage: eventstore-truncation-fixture.sh EVENTSTORE_FILE' >&2; exit 2; }
lines="$(wc -l < "$input")"
(( lines >= 2 )) || { echo 'eventstore fixture needs at least two records' >&2; exit 1; }
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
head -n $((lines - 1)) "$input" > "$tmp"
[[ "$(wc -l < "$tmp")" -lt "$lines" ]] || { echo 'truncation fixture failed' >&2; exit 1; }
printf 'original_records=%s\ntruncated_records=%s\ntruncation_detected=yes\n' "$lines" "$(wc -l < "$tmp")"

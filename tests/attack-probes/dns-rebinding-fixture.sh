#!/usr/bin/env bash
set -euo pipefail
[[ "${PROBE_ALLOW:-}" == 1 ]] || { echo 'refusing probe without PROBE_ALLOW=1' >&2; exit 2; }
fixture="${1:-$(dirname "$0")/dns-rebinding-fixture.txt}"
[[ -f "$fixture" ]] || { echo "fixture not found: $fixture" >&2; exit 1; }
mapfile -t answers < "$fixture"
(( ${#answers[@]} >= 2 )) || { echo 'fixture must contain at least two answers' >&2; exit 1; }
first="${answers[0]}"
second="${answers[1]}"
[[ "$first" != "$second" ]] || { echo 'fixture must change the resolved address' >&2; exit 1; }
printf 'first_resolution=%s\nsecond_resolution=%s\nrevalidate_required=yes\n' "$first" "$second"

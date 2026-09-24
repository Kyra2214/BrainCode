#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ "${PROBE_ALLOW:-}" == 1 ]] || { echo 'refusing probe suite without PROBE_ALLOW=1' >&2; exit 2; }
for script in "$ROOT"/tests/attack-probes/*.sh; do bash -n "$script"; done
PROBE_ALLOW=1 MAX_CHILDREN=2 bash "$ROOT/tests/attack-probes/fork-bomb-controlled.sh"
PROBE_ALLOW=1 bash "$ROOT/tests/attack-probes/bash-c-mount-dd.sh"
PROBE_ALLOW=1 PROBE_HOST=example.invalid PROBE_PORT=9 bash "$ROOT/tests/attack-probes/dev-tcp-controlled.sh"
PROBE_ALLOW=1 bash "$ROOT/tests/attack-probes/dns-rebinding-fixture.sh"
printf 'attack_probe_suite=passed\n'

#!/usr/bin/env bash
set -euo pipefail
[[ "${PROBE_ALLOW:-}" == 1 ]] || { echo 'refusing probe without PROBE_ALLOW=1' >&2; exit 2; }
host="${PROBE_HOST:-example.invalid}"
port="${PROBE_PORT:-9}"
set +e
timeout 2 bash -c 'exec 3<>/dev/tcp/"$1"/"$2"' _ "$host" "$port" >/tmp/dev-tcp-probe.out 2>/tmp/dev-tcp-probe.err
rc=$?
set -e
printf 'target=%s:%s\nexit=%s\n' "$host" "$port" "$rc"
[[ $rc -ne 0 ]]

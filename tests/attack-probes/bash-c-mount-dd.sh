#!/usr/bin/env bash
set -euo pipefail
[[ "${PROBE_ALLOW:-}" == 1 ]] || { echo 'refusing probe without PROBE_ALLOW=1' >&2; exit 2; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
set +e
bash -c 'dd if=/dev/zero of="$1/out" bs=1K count=1 status=none; mount -t tmpfs none "$1/mnt"' _ "$tmp" >"$tmp/stdout" 2>"$tmp/stderr"
rc=$?
set -e
[[ ! -e "$tmp/mnt"/mounted-by-probe ]] || { echo 'unexpected mount marker' >&2; exit 1; }
printf 'bash_c_exit=%s\n' "$rc"
printf 'blocked_or_failed=%s\n' "$([[ $rc -ne 0 ]] && echo yes || echo no)"
[[ $rc -ne 0 ]]

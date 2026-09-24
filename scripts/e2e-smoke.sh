#!/usr/bin/env bash
set -euo pipefail

PACKAGE="${PACKAGE:-com.sandbox.app}"
ADB="${ADB:-adb}"
DUMP="/sdcard/sandbox-window.xml"

wait_for_device() {
  "$ADB" wait-for-device
  for _ in $(seq 1 90); do
    if [[ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      return 0
    fi
    sleep 2
  done
  echo "Android device did not finish booting" >&2
  exit 1
}

ui_dump() {
  "$ADB" shell uiautomator dump "$DUMP" >/dev/null
  "$ADB" shell cat "$DUMP" | tr -d '\r'
}

has_text() {
  ui_dump | grep -Fq "$1"
}

wait_for_text() {
  local expected="$1"
  local timeout="${2:-180}"
  for _ in $(seq 1 "$timeout"); do
    if has_text "$expected"; then
      echo "PASS: $expected"
      return 0
    fi
    if has_text "Bloqueado:"; then
      echo "Sandbox entered blocked state while waiting for: $expected" >&2
      ui_dump >&2
      exit 1
    fi
    sleep 1
  done
  echo "Timed out waiting for: $expected" >&2
  ui_dump >&2
  exit 1
}

click_text() {
  local label="$1"
  local node bounds
  node="$(ui_dump | grep -F "text=\"$label\"" | head -n 1)"
  bounds="$(printf '%s\n' "$node" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')"
  if [[ -z "$bounds" ]]; then
    echo "Could not locate UI text: $label" >&2
    ui_dump >&2
    exit 1
  fi
  read -r left top right bottom <<< "$bounds"
  "$ADB" shell input tap "$(( (left + right) / 2 ))" "$(( (top + bottom) / 2 ))"
}

wait_for_device
"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell monkey -p "$PACKAGE" 1 >/dev/null
wait_for_text "Sandbox ainda não preparado" 30
click_text "Preparar sandbox"
wait_for_text "Sandbox pronto" 360

click_text "Comando (bash dentro do rootfs)"
"$ADB" shell input text 'echo%se2e-ok'
click_text "Executar"
wait_for_text "e2e-ok" 90

click_text "Diagnóstico"
wait_for_text "Diagnóstico do rootfs" 30

click_text "Resetar sandbox"
wait_for_text "Sandbox ainda não preparado" 90

echo "E2E smoke passed: prepare, execute, diagnostics, reset"

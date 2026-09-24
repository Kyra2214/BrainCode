#!/usr/bin/env bash
set -euo pipefail

# ARM64 validation harness for the Android application.
# This script intentionally refuses non-ARM64 targets: a host build or an
# x86 emulator is not evidence for the ARM64/device gate.

ADB="${ADB:-adb}"
PACKAGE="${PACKAGE:-com.sandbox.app}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
E2E_SCRIPT="${E2E_SCRIPT:-scripts/e2e-smoke.sh}"
EVIDENCE_DIR="${EVIDENCE_DIR:-artifacts/arm64-harness}"
INSTALL="${INSTALL:-1}"

fail() {
  echo "ARM64 HARNESS FAILED: $*" >&2
  exit 1
}

command -v "$ADB" >/dev/null 2>&1 || fail "adb não encontrado; instale Android platform-tools"
[[ -x "$E2E_SCRIPT" ]] || fail "script E2E não executável: $E2E_SCRIPT"
[[ "$INSTALL" == "0" || -f "$APK" ]] || fail "APK não encontrado: $APK (use APK=... ou INSTALL=0)"

mkdir -p "$EVIDENCE_DIR"
"$ADB" start-server >/dev/null
"$ADB" wait-for-device

DEVICE_COUNT="$($ADB devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
[[ "$DEVICE_COUNT" == "1" ]] || fail "esperado exatamente um device online; encontrados: $DEVICE_COUNT"

SERIAL="$($ADB devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
ADB_DEVICE=("$ADB" -s "$SERIAL")

ABI="$(${ADB_DEVICE[@]} shell getprop ro.product.cpu.abilist | tr -d '\r' | tr -d ' ' | cut -d, -f1)"
[[ "$ABI" == "arm64-v8a" || "$ABI" == "aarch64" ]] || fail "target não é ARM64: $ABI"

SDK="$(${ADB_DEVICE[@]} shell getprop ro.build.version.sdk | tr -d '\r')"
MODEL="$(${ADB_DEVICE[@]} shell getprop ro.product.model | tr -d '\r')"
MANUFACTURER="$(${ADB_DEVICE[@]} shell getprop ro.product.manufacturer | tr -d '\r')"

{
  echo "timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "serial=$SERIAL"
  echo "manufacturer=$MANUFACTURER"
  echo "model=$MODEL"
  echo "abi=$ABI"
  echo "sdk=$SDK"
  echo "host_arch=$(uname -m)"
  echo "package=$PACKAGE"
  echo "apk=$APK"
  echo "apk_sha256=$(sha256sum "$APK" 2>/dev/null | cut -d' ' -f1 || true)"
} | tee "$EVIDENCE_DIR/device.properties"

"${ADB_DEVICE[@]}" shell getprop > "$EVIDENCE_DIR/getprop.txt"
"${ADB_DEVICE[@]}" shell uname -a > "$EVIDENCE_DIR/device-uname.txt"

if [[ "$INSTALL" != "0" ]]; then
  "${ADB_DEVICE[@]}" install -r "$APK" | tee "$EVIDENCE_DIR/install.txt"
fi

ADB="$ADB" PACKAGE="$PACKAGE" > "$EVIDENCE_DIR/e2e-smoke.txt" bash "$E2E_SCRIPT"

"${ADB_DEVICE[@]}" shell dumpsys package "$PACKAGE" > "$EVIDENCE_DIR/package.txt"
echo "ARM64 HARNESS PASSED: evidências em $EVIDENCE_DIR"

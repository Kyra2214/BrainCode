#!/usr/bin/env bash
set -euo pipefail
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA_BIN" >/dev/null 2>&1 || { echo "java=NOT_FOUND:$JAVA_BIN"; exit 2; }
printf 'java='; "$JAVA_BIN" -version 2>&1 | head -1
if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
  echo "android_sdk=$ANDROID_HOME"
else
  echo 'android_sdk=NOT_CONFIGURED'
  echo 'Configure ANDROID_HOME or local.properties sdk.dir before Android builds.'
  exit 2
fi

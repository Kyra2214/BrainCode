#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
CMDLINE_TOOLS_VERSION="11076708"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  openjdk-17-jdk-headless ca-certificates curl unzip

mkdir -p "$SDK_ROOT/cmdline-tools"
if [[ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  curl -fL --retry 3 -o "$TMP_DIR/commandlinetools.zip" "$CMDLINE_TOOLS_URL"
  rm -rf "$TMP_DIR/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$TMP_DIR/cmdline-tools"
  unzip -q "$TMP_DIR/commandlinetools.zip" -d "$TMP_DIR/cmdline-tools"
  mv "$TMP_DIR/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi

export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$JAVA_HOME/bin:$SDK_ROOT/platform-tools:$SDK_ROOT/cmdline-tools/latest/bin:$PATH"

yes | sdkmanager --sdk_root="$SDK_ROOT" --licenses >/dev/null || true
sdkmanager --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "ndk;26.3.11579264"

printf '\nInstalled toolchains:\n'
java -version 2>&1 | head -3
sdkmanager --sdk_root="$SDK_ROOT" --list_installed | sed -n '1,80p'
printf '\nSDK_ROOT=%s\nJAVA_HOME=%s\n' "$SDK_ROOT" "$JAVA_HOME"

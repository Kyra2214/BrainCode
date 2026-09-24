#!/usr/bin/env bash
set -euo pipefail
apk="${1:-app/build/outputs/apk/debug/app-debug.apk}"
[[ -s "$apk" ]] || { echo "APK não encontrado: $apk" >&2; exit 1; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
unzip -q "$apk" 'assets/roofts/0.6/*' 'assets/braincode/trusted-signing-keys.json' -d "$work"
count="$(find "$work/assets/roofts/0.6/skills" -name SKILL.md -type f | wc -l)"
[[ "$count" -eq 25 ]] || { echo "Esperadas 25 Skills, encontradas $count" >&2; exit 1; }
[[ -s "$work/assets/braincode/trusted-signing-keys.json" ]] || { echo "Chaves confiáveis ausentes" >&2; exit 1; }
grep -q 'braincode-dev-2026' "$work/assets/braincode/trusted-signing-keys.json"
while IFS= read -r skill; do
  grep -q '^name:' "$skill" || { echo "SKILL sem frontmatter name: $skill" >&2; exit 1; }
done < <(find "$work/assets/roofts/0.6/skills" -name SKILL.md -type f)
printf 'APK asset gate passed: %s Roofts Skills + trusted signing keys\n' "$count"

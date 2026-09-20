#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:-.}"
# Apenas adaptadores de baixo nível podem criar processos. Managers de produto
# devem chamar SandboxCommandExecutor, cuja implementação passa pelo PolicyGate.
declare -A ALLOWED
while IFS= read -r path; do ALLOWED["$path"]=1; done <<'EOF'
android-module/src/main/kotlin/com/sandbox/runtime/ProotProcessLauncher.kt
android-module/src/main/kotlin/com/sandbox/runtime/ManagedSandboxRuntime.kt
android-module/src/main/kotlin/com/sandbox/runtime/SandboxRuntime.kt
android-module/src/main/kotlin/com/sandbox/runtime/PackagedRuntime.kt
android-module/src/main/kotlin/com/sandbox/android/AndroidSandboxFactory.kt
android-module/src/main/kotlin/com/sandbox/agent/AgentSandboxSession.kt
app/src/main/kotlin/com/sandbox/app/SandboxViewModel.kt
app/src/main/kotlin/com/sandbox/sandbox/PluginModels.kt
brain/src/main/kotlin/com/brain/qa/ExecutorValidacaoProjeto.kt
brain/src/main/kotlin/com/brain/qa/DescobertaComandosValidacao.kt
EOF
status=0
while IFS=: read -r file line text; do
  rel="${file#"$ROOT/"}"
  [[ "$rel" == */src/test/* ]] && continue
  [[ -n "${ALLOWED[$rel]:-}" ]] && continue
  printf 'UNAUTHORIZED_DIRECT_PROCESS_SURFACE %s:%s:%s\n' "$rel" "$line" "$text" >&2
  status=1
done < <(rg -n --glob '*.kt' --glob '*.java' --glob '!**/build/**' --glob '!**/.gradle/**' 'runtime\.execute\(|ProcessBuilder\(|\.launch\(\s*(command|listOf)' "$ROOT/android-module" "$ROOT/app" "$ROOT/brain" || true)
if (( status != 0 )); then exit 1; fi
echo 'architecture gate passed: product managers use policy-gated executor'

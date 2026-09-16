# Fase 10 — Exibição de `installedBytes` nas Toolchains

## Escopo

Esta fase corrige exclusivamente a regressão em que `installedBytes` era calculado e persistido, mas não era apresentado na aba **Toolchains** de `SettingsScreen`.

## Alterações

A função `formatBytes` foi movida de `MainActivity.kt` para `SettingsScreen.kt`, no mesmo package `com.sandbox.app`, mantendo a implementação original para bytes, KiB, MiB e GiB.

`ToolchainSettings` agora combina a versão detectada com o tamanho instalado quando `status.installedBytes > 0L`. Estados sem instalação ou estados antigos sem a métrica continuam sem exibir `0 B`.

## Critérios verificados

- `formatBytes` possui declaração em `SettingsScreen.kt`.
- Existe call site em `SettingsScreen.kt`.
- `installedBytes` é lido apenas para valores positivos.
- `MainActivity.kt` não contém mais a função de formatação.
- O diff permanece restrito a `MainActivity.kt`, `SettingsScreen.kt` e esta documentação.

## Validação local

Executado com JDK 17 e Android SDK configurado:

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug --no-daemon --stacktrace
```

Resultado:

```text
BUILD SUCCESSFUL
64 actionable tasks: 7 executed, 57 up-to-date
```

Foi emitido um warning não bloqueante preexistente sobre variável não utilizada em `MainActivity.kt`; não houve erro de compilação nem de empacotamento.

## Publicação

Esta fase será publicada em commit próprio antes do início da Fase 2. O CI será aguardado somente depois que as duas fases estiverem publicadas, conforme a ordem solicitada.

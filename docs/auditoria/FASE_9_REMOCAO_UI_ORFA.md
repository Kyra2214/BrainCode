# Fase 9 — Remoção do cluster de UI órfã

## Escopo

Esta etapa removeu exclusivamente o cluster de UI sem callers confirmado em `MainActivity.kt`. A referência usada foi o plano `plano-remocao-ui-orfa.md`, aplicado sobre o commit inicial `ac10105`.

## Símbolos removidos

Foram removidos os dez símbolos abaixo:

- `ToolsAndApiScreen`
- `SandboxValidationScreen`
- `OperationsScreen`
- `ChatboxSection`
- `ChatBubble`
- `DiagnosticsSection`
- `SelfCheckReportSection`
- `CommandSection`
- `QuickCommandsRow`
- `ResultSection`

A remoção foi feita por dependência transitiva quando aplicável. `SandboxMobileApp`, `StatusSection`, `statusHeadline`, `StatusPrimaryActions` e `StatusDetails` foram preservados.

## Verificação de callers e navegação

Antes do corte foi executada busca em todos os arquivos Kotlin do repositório. Os dez símbolos apareceram exclusivamente em `MainActivity.kt`; não foram encontrados callers em outros arquivos, testes ou módulos.

Também foi pesquisada a presença de `NavHost`, `NavController`, rotas seladas/enumeradas, feature flags, `BuildConfig` e referências de navegação para essas telas. A única navegação ativa encontrada permanece:

```text
MainActivity
└── SandboxMobileApp
    ├── ThreadScreen
    └── SettingsScreen
```

Não foi encontrada navegação oculta ou condicional que dependesse das telas removidas.

## Resultado do código

`MainActivity.kt` foi reduzido de 513 para 213 linhas. Imports usados exclusivamente pela UI removida foram retirados. Nenhum componente ativo do fluxo `ThreadScreen`/`SettingsScreen` foi alterado.

## Validação local

Com Android SDK em `/home/ubuntu/android-sdk` e JDK 17, foram executados:

```bash
./gradlew :app:compileDebugKotlin :app:lintDebug :app:assembleDebug --no-daemon --stacktrace
```

Resultado:

```text
BUILD SUCCESSFUL
96 actionable tasks: 96 executed
```

O build apresentou somente warnings Kotlin existentes ou não bloqueantes em outros arquivos, além de um warning de variável não utilizada em `MainActivity.kt`; não houve erro de compilação, lint ou empacotamento.

## Critério de aceite

- Busca dos dez símbolos em `app/src/main/kotlin/`: sem ocorrências.
- `:app:compileDebugKotlin`: passou.
- `:app:lintDebug`: passou.
- `:app:assembleDebug`: passou.
- APK debug gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Publicação e CI

A mudança será publicada em commit separado, sem misturar alterações funcionais de RootFS, Brain, catálogo ou runtime. O workflow `.github/workflows/ci.yml` deve executar testes JVM, assemble debug, lint e upload do APK como artifact. O resultado final do CI e o checksum do APK serão registrados na entrega após a conclusão do workflow.

# Baseline de fechamento da absorção — projeto3

**Data:** 2026-09-23 18:25–18:29 (America/Sao_Paulo)  
**Commit de referência:** `9a3c52020440d2d4c48b363854ad49975e1c52f3`  
**Branch:** `main`  
**Objetivo:** registrar o estado real antes das alterações da primeira fatia do plano de fechamento.

## Ambiente

A primeira execução ocorreu sem `JAVA_HOME`/`ANDROID_HOME` configurados, com JDK 21 disponível e sem Android SDK. Em seguida, o ambiente local foi configurado com JDK 17 (`/usr/lib/jvm/java-17-openjdk-amd64`) e Android SDK 34 (`/home/ubuntu/Android/Sdk`); `local.properties` é ignorado pelo Git e contém apenas o caminho local do SDK.

## Execução no estado original

| Comando | Resultado | Evidência |
|---|---:|---|
| `./gradlew :brain:test --no-daemon --console=plain` | **FAIL** | JDK 17 exigido pelo toolchain, mas ausente |
| `./gradlew :android-module:test --no-daemon --console=plain` | **FAIL** | SDK location not found |
| `./gradlew :app:testDebugUnitTest --no-daemon --console=plain` | **FAIL** | SDK location not found |
| `bash scripts/architecture-gate.sh` | **PASS** | `architecture gate passed: product managers use policy-gated executor` |

## Reexecução após configurar o ambiente

| Comando | Resultado | Evidência |
|---|---:|---|
| `JAVA_HOME=... ./gradlew :brain:test --no-daemon --console=plain` | **PASS** | `BUILD SUCCESSFUL`; suíte JVM executada |
| `JAVA_HOME=... ./gradlew :android-module:test --no-daemon --console=plain` | **PASS** | `BUILD SUCCESSFUL`; suíte Android-module executada |
| `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --no-daemon --console=plain` | **FAIL** | compilação inicialmente bloqueada por chamada antiga em `P0ConversationRegressionTest`; após correção da chamada, 155 testes executaram e 13 falharam |
| `bash scripts/architecture-gate.sh` | **PASS** | gate arquitetural verde |

As 13 falhas Android foram classificadas como incompatibilidades comportamentais pré-existentes no HEAD de referência. Elas estavam concentradas em `ChatResponseExecutorTest`, `ConversationEngineTest`, `WebResearchExecutorTest` e `TrustedInstallerExecutorTest`; a falha do teste `P0ConversationRegressionTest` foi apenas de compatibilidade da chamada do construtor e foi corrigida antes da primeira fatia.

## Resultado após o fechamento

Após as alterações, a suíte completa foi reexecutada e passou sem falhas:

| Gate | Resultado final |
|---|---:|
| `:brain:test` | **PASS** |
| `:android-module:test` | **PASS** |
| `:app:testDebugUnitTest` | **PASS** |
| `bash scripts/architecture-gate.sh` | **PASS** |
| `:app:assembleDebug` | **PASS** |
| `:app:lint` | **PASS** |

As correções foram limitadas a contratos já existentes: `ActionExecution.result` voltou a espelhar o texto autorizado sem substituir `UserResponse`, o composer passou a aceitar miss/contexto/pesquisa apoiados por evidência, pesquisa recebida passou a declarar proveniência e o timeout de toolchain foi alinhado ao limite seguro de 600 segundos. Nenhuma das 13 falhas permanece.

## Critério de uso

Este documento é a referência do T-002. Toda regressão introduzida pela absorção deve ser comparada com o estado original descrito acima; o resultado final verde está registrado separadamente para não apagar a evidência histórica.

## Logs locais

- Baseline sem ambiente: `/tmp/braincode-baseline-2026-09-23.log`
- Baseline com ambiente: `/tmp/braincode-baseline-configured-2026-09-23.log`
- Execução Android pós-correção do teste: relatório Gradle em `app/build/reports/tests/testDebugUnitTest/index.html`

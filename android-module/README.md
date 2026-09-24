# android-module — Fase 0.2 (Empacotamento para mobile)

Este diretório contém o código Kotlin responsável por baixar, retomar e
validar o rootfs gerado na Fase 0.1 (`rootfs-builder/`). Ainda não é um
projeto Gradle completo — é o módulo isolado, pensado para ser copiado para
dentro do seu app Android quando o projeto Gradle principal existir.

## Arquivos

- `manifest/rootfs_manifest.example.json` — formato do manifesto que descreve
  qual versão do rootfs existe, onde baixar e qual o hash esperado. Depois
  de rodar o `build.sh` (Fase 0.1), copie o hash gerado para este manifesto.
- `src/main/kotlin/com/sandbox/resource/RootfsManifest.kt` — modelo de dados
  do manifesto.
- `src/main/kotlin/com/sandbox/resource/SandboxResourceManager.kt` — a lógica
  de download com:
  - Retomada via header `Range` se o download for interrompido
  - Validação de integridade por SHA-256 antes de considerar o arquivo pronto
  - Nunca grava em `assets/` — sempre em armazenamento privado do app
  - Independente de Android Framework (só `java.net`/`java.io`/`java.security`),
    para poder ser testado em JVM pura sem emulador

## Como isso vai ser usado (próxima fase)

Na Fase 0.3, o `SandboxRuntime` vai:
1. Ler o `RootfsManifest` (de um JSON local ou remoto)
2. Chamar `SandboxResourceManager.ensureAvailable(manifest, progressListener)`
3. Quando o resultado for `Success`, extrair o `.tar.gz` para a pasta onde o
   `proot` vai apontar como raiz

## O que falta nesta fase

- Ainda não decidimos onde o manifesto/arquivo vai ser hospedado de fato
  (CDN próprio, Release do GitHub, etc.) — isso é uma decisão de
  infraestrutura, não de código, e pode ficar para quando o app estiver
  mais avançado.
- Não há testes automatizados ainda neste módulo (fica registrado como
  pendência, não como "funciona e está testado").

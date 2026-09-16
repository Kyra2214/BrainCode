# Auditoria BrainCode — Fase 5: RootFS Agent Android/API 0.5.0

**Release auditada:** `rootfs-agent-android-v0.5.0`
**Asset:** `rootfs-agent-android-0.5.0.tar.gz`
**Base:** Agent Extra 0.4.1 → RootFS 0.3.3
**Arquitetura:** arm64-v8a / Ubuntu 24.04
**Tamanho publicado:** 1,764,603,872 bytes
**SHA-256 publicado:** `374e795ce3caa8eaf4cbb58f39d591230918f5226...` (manifest/release metadata é a autoridade para o valor completo; não inferir hash truncado como valor válido).

## 1. Função do perfil

O 0.5.0 é a camada Android/API. Ele adiciona ferramentas para trabalhar com projetos Android, artefatos, jobs, segurança e integração com o app.

Cadeia de herança:

```text
Ubuntu 24.04
  ↓
RootFS 0.3.3
  ↓
Agent Extra 0.4.1
  ↓
Agent Android 0.5.0
```

## 2. Pacotes adicionais declarados

O Dockerfile instala:

- `android-sdk`;
- `android-sdk-build-tools`;
- `android-sdk-platform-tools`;
- `adb`;
- `google-android-platform-tools-installer`;
- `google-android-cmdline-tools-10.0-installer`;
- `android-sdk-platform-23`;
- `android-sdk-libsparse-utils`;
- `gitleaks`;
- `unrar-free`;
- `qpdf`;
- `rsync`;
- `socat`.

Depois copia SDKs encontrados para `/opt/android-sdk` e cria links para:

- `adb`;
- `aapt2`;
- `apksigner`;
- `zipalign`;
- `sdkmanager`;
- `avdmanager`.

NDK e imagens de emulador permanecem fora da camada, conforme o desenho de distribuição mobile enxuta.

## 3. Scripts de integração

A camada copia `integration-scripts/` para `/usr/local/bin/` e dá permissão aos scripts `sandbox-*`.

Os comandos adicionais documentados para este perfil são:

- `sandbox-artifact`;
- `sandbox-job`;
- `sandbox-health-android`.

Eles representam uma interface de agente sobre artefatos/jobs/health; não são o Brain em si.

## 4. Relação com Fase 1

Nenhum dos órfãos Android identificados na Fase 1 é incorporado ao 0.5.0.

A camada é conteúdo do RootFS, enquanto:

- `ToolsAndApiScreen`;
- `SandboxValidationScreen`;
- `OperationsScreen`;
- `ChatboxSection`

são classes/composables do APK.

Não existe justificativa para apagar ou alterar RootFS por causa desses órfãos de UI.

## 5. Relação com Fase 2

A camada Android não reintroduz as arquiteturas abandonadas. Não há LLM obrigatório, Room do IaBrain, Agent autônomo com modelo próprio ou parser de slash como cérebro.

O SDK Android é tooling do ambiente, não uma duplicação do módulo Android do BrainCode.

## 6. Java e Android build

Como o 0.5.0 herda o 0.4.1, ele herda o 0.3.3 e, portanto, `default-jdk`, `gradle`, Python, Node e toolchains base.

Além disso, adiciona ferramentas Android como `aapt2`, `apksigner` e `zipalign`.

Portanto um diagnóstico de "Java não encontrado" no 0.5.0 deve verificar, nesta ordem:

1. qual RootFS foi realmente selecionado;
2. se a camada base foi montada corretamente;
3. se `/usr/bin/java` existe no filesystem materializado;
4. `PATH` efetivo do processo;
5. usuário/UID usado pela execução;
6. se o probe de Java falhou por memória em vez de ausência do binário;
7. se o código está olhando o RootFS certo.

## 7. Compatibilidade com toolchains do app

O catálogo Android declara uma toolchain `android`, além de Java/Python/Node/C++/Rust/Go.

A UI pode consultar o status da toolchain e instalar pacotes via `ToolchainManager`, mas o 0.5.0 já carrega a maior parte da infraestrutura Android necessária para o perfil.

Isso significa que a toolchain manager e o RootFS são complementares:

```text
RootFS → baseline
ToolchainManager → detectar/instalar/remover componentes declarados
Plugin catalog → capacidades opcionais
```

## 8. Varredura binária

O asset publicado tem aproximadamente 1.76 GB. Nesta auditoria, a interface de GitHub permite validar release metadata, manifesto e fonte de construção, mas não disponibiliza o conteúdo binário do tarball para uma listagem interna completa.

Portanto a auditoria não inventa uma listagem de pathnames. O resultado é completo para a cadeia de fonte/manifesto/integração, mas a inspeção física do `.tar.gz` continua uma etapa externa de validação de artefato.

## 9. Resultado

**Estado:** HOMOLOGADO / SEM CORRESPONDÊNCIA COM OS ÓRFÃOS OU LEGADO DAS FASES 1–2.

**Achado de arquitetura:** 0.5.0 é camada de execução Android/API; não deve carregar lógica de UI nem substituir o Brain/Policy/Gateway.

**Achado de diagnóstico:** Java deveria estar disponível por herança do 0.3.3. O problema histórico de Java precisa ser rastreado no runtime de materialização, não assumido como ausência no pacote.

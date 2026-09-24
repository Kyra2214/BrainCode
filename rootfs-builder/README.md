# Rootfs Builder — Sandbox Mobile

Este diretório contém tudo o que é necessário para construir o rootfs
completo (**Ubuntu 24.04**) que será baixado e executado dentro do app via
`proot`.

> Trocamos de Alpine (v0.1.0) para Ubuntu (v0.2.0): musl (Alpine) quebra
> compatibilidade com a maioria dos wheels binários do pip e com binários
> pré-compilados comuns do npm, forçando recompilar tudo do zero. Ubuntu
> usa glibc, igual ao ambiente de referência (runtime mobile da DeepSeek).

## Requisitos para rodar este build

- Docker instalado na máquina onde você for gerar o pacote (não precisa
  Docker dentro do celular — isso é só para *fabricar* o arquivo `.tar.gz`
  uma vez, na sua máquina de desenvolvimento).

## Como usar

```bash
cd rootfs-builder
./build.sh
# ou, para versionar diferente de 0.3.0:
VERSION=0.3.1 ./build.sh
# Em máquina x86 com Docker e binfmt/QEMU configurado, arm64 é o padrão:
PLATFORM=linux/arm64 ./build.sh
```

Isso vai:
1. Construir a imagem Docker definida no `Dockerfile` (Ubuntu 24.04 arm64 +
   bash, coreutils, git, curl, wget, nodejs, npm, python3, python3-pip,
   python3-venv, build-essential, sqlite3, jq, vim/nano, openssh-client,
   entre outros — ver lista completa no `Dockerfile`).
2. Exportar o filesystem resultante como
   `../output/rootfs-ubuntu-<versão>.tar.gz`.
3. Gerar o hash SHA-256 em `../output/rootfs-ubuntu-<versão>.tar.gz.sha256`.
4. Gerar `../output/rootfs_manifest.json` já preenchido (URL, tamanho,
   hash, distro) apontando para uma release em `Kyra2214/BrainCode`.

## Resultado — release publicada

O `.tar.gz` gerado precisa ficar acessível por HTTP (nunca vai dentro do
APK). A distribuição agora pertence ao próprio repositório do projeto:
`Kyra2214/BrainCode`. O CDN dos GitHub Releases suporta o header `Range`,
que é o que `SandboxResourceManager` usa para retomar downloads interrompidos.

Para uma NOVA build, o procedimento é:

```bash
gh release create rootfs-v<versão> \
  ../output/rootfs-ubuntu-<versão>.tar.gz \
  ../output/rootfs-ubuntu-<versão>.tar.gz.sha256 \
  --repo Kyra2214/BrainCode \
  --title "Rootfs <versão> (Ubuntu 24.04)"
```

O manifesto deve apontar para a release publicada e o app valida o SHA-256
antes de considerar o RootFS pronto para uso.

## IMPORTANTE — migração dos RootFS já validados

Os RootFS da primeira fase já foram construídos, testados e publicados no
`Kyra2214/SandBox`. **Eles não devem ser reconstruídos para esta migração.**

A migração é exclusivamente byte-a-byte:

```text
SandBox release validado
        │
        ├── download do asset original
        ├── verificação de tamanho
        ├── verificação de SHA-256
        │
        ▼
BrainCode release equivalente
        │
        └── mesmo .tar.gz + mesmo .sha256
```

Use:

```bash
cd rootfs-builder
bash migrate-sandbox-releases.sh
```

O script migra exatamente estes três releases:

| Origem | Destino | Arquivo |
|---|---|---|
| `rootfs-v0.3.3` | `rootfs-v0.3.3` | `rootfs-ubuntu-0.3.3.tar.gz` |
| `rootfs-agent-v0.4.1` | `rootfs-agent-v0.4.1` | `rootfs-agent-extra-0.4.1.tar.gz` |
| `rootfs-agent-android-v0.5.0` | `rootfs-agent-android-v0.5.0` | `rootfs-agent-android-0.5.0.tar.gz` |

Os tamanhos e hashes esperados estão no próprio `migrate-sandbox-releases.sh`
(e nos manifests `app/src/main/res/raw/rootfs_*_manifest.json`). Se qualquer
verificação falhar, o script interrompe a migração. Não há rebuild automático.

O script também atualiza os manifests para as URLs do BrainCode somente após
publicar/verificar os assets.

## Ferramentas para agentes

A imagem 0.3.0 inclui lint e formatação Python (`ruff`, `black`, `mypy`), ESLint e Prettier globais, ShellCheck, ctags, GitHub CLI, Git LFS, tmux, fzf, entr, utilitários de rede (`net-tools`, `dnsutils`, `netcat-openbsd`, `socat`), `yq`, Pandoc, ImageMagick e Graphviz.

A imagem 0.3.1 acrescenta `pytest` e `bandit`, `yarn`, `pnpm` e `pipx`, `httpie`, `bat`, suporte a `.7z`/`.xz`/`.bz2`, além dos clientes `psql`, MySQL e Redis.

O RootFS base 0.3.3 remove o Ollama para manter o download enxuto; o plugin
`ollama` instala-o sob demanda. O arquivo `Dockerfile.agent-extra` e o script
`build-agent-extra.sh` geram o RootFS opcional 0.4.1 para agentes, com dados,
segurança, multimídia, depuração, testes avançados e comandos `sandbox-*`.
O builder base foi atualizado para Node.js 20 LTS via NodeSource, mantendo
compatibilidade com Vite, Vitest e Playwright.

O catálogo do app oferece ainda os plugins opcionais Android NDK, Trivy, SOPS,
grpcurl e websocat. Eles não são embutidos no RootFS: são baixados sob demanda,
validados e removíveis pelo usuário.

Para um agente via API, os comandos `sandbox-run`, `sandbox-test`,
`sandbox-build`, `sandbox-clean`, `sandbox-info`, `sandbox-diagnose` e
`sandbox-health` fornecem uma interface estável para execução, testes,
artefatos, logs e diagnóstico sem depender de detalhes do Ubuntu.

## Customizando o conteúdo do rootfs

Edite o `Dockerfile` e adicione/remova pacotes com `apt-get install`. Como
a base já é "completa" (Ubuntu), o cuidado agora é o oposto do Alpine: não
precisa economizar pacote por pacote, mas evite inflar demais o tamanho do
download (imagens de IA/ML pesadas, toolchains gigantes) sem necessidade
comprovada — isso é decisão de fase futura (Fase 1+), não da fundação.

## Próximo passo (Fase 0.2 e 0.3 do roadmap)

Este builder resolve a fabricação de novas versões. Para os artefatos da
primeira fase já validados, a operação correta é a migração documentada acima.

## RootFS Agent Android/API 0.5.0

O terceiro perfil é construído sobre o Agent Extra 0.4.1 usando
`build-agent-android.sh`. Ele adiciona Android SDK, adb, aapt2, apksigner,
zipalign, gitleaks, unrar e integração de jobs e artefatos. Também não inclui
Ollama. NDK e imagens de emulador ficam fora para evitar um pacote
excessivamente pesado em telefones.

Os comandos adicionais são `sandbox-artifact`, `sandbox-job` e
`sandbox-health-android`, destinados a agentes que trabalham via API.

## Manifestos: molde do builder × manifestos do app

`agent_extra_manifest.json` e `agent_android_manifest.json` (aqui) são o **molde** emitido pelo builder; o app usa `app/src/main/res/raw/rootfs_manifest.json` (base 0.3.3), `rootfs_extra_manifest.json` e `rootfs_android_manifest.json`. Para os dois perfis que existem nos dois lugares, a **única diferença** é `"signatureRequired": false`, presente só nos manifestos do app.

Isso é intencional e reflete o estado atual: um manifesto sem `signatureRequired` é tratado como `true` (fail-closed, ver `docs/ROOTFS_SIGNATURES.md`), mas os três RootFS homologados ainda não foram assinados, então os manifestos do app declaram `false` explicitamente e `app/src/main/assets/rootfs_trusted_keys.json` está vazio. Ao assinar os artefatos (Marco 3): remover o campo dos manifestos do app (ou passar a `true`), publicar a chave pública e manter as duas cópias sincronizadas (o molde do builder não deve receber `false`).

## Verificação no host (manual)

`scripts/e2e-rootfs-host.sh [diretório-de-trabalho]` baixa as três camadas declaradas em `app/src/main/res/raw/rootfs_manifest.json`, `rootfs-builder/agent_extra_manifest.json` e `rootfs-builder/agent_android_manifest.json`, confere tamanho e SHA-256, extrai uma sobre a outra e valida binários essenciais e symlinks que escapam do rootfs. Requer `curl`, `jq`, `tar` e `python3`, e acesso à rede; não roda no CI.

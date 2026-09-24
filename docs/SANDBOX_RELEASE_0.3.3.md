# Release `rootfs-v0.3.3` — RootFS Base

## Identidade

A release `rootfs-v0.3.3` é o **RootFS base** do Sandbox Mobile. Ela fornece o ambiente Ubuntu 24.04 arm64 que é baixado pelo aplicativo e executado dentro do runtime com `proot`. Este perfil é a fundação sobre a qual os perfis Agent Extra 0.4.1 e Agent Android/API 0.5.0 são construídos.

| Campo | Valor |
|---|---|
| Release | `rootfs-v0.3.3` |
| Artefato | `rootfs-ubuntu-0.3.3.tar.gz` |
| Distribuição | Ubuntu 24.04 |
| Arquitetura | `arm64-v8a` |
| Tamanho | 1.075.455.791 bytes |
| SHA-256 | `a43f0915d5cd6e2e0c8640b8b30855d54f7b8873ca3be95acca769100b1487f4` |
| Ollama | Não incluído; instalação opcional pelo plugin |
| Repositório de distribuição atual | [Kyra2214/BrainCode](https://github.com/Kyra2214/BrainCode) |

## Conteúdo funcional

O perfil inclui as ferramentas essenciais de execução e desenvolvimento: Bash, coreutils, Git, curl, wget, certificados, GnuPG, compactação ZIP, `jq`, editores de terminal, cliente SSH, SQLite, locales, timezone data e toolchain de compilação. Também fornece Python 3 com pip, venv e headers, Node.js 20 LTS via NodeSource e npm.

A versão 0.3.3 consolida ainda Java/JDK e Gradle, CMake e Ninja, Rust/Cargo, Go, ripgrep, fd-find, tree, procps, psmisc, htop, lsof, iproute2, ShellCheck, universal-ctags, GitHub CLI, Git LFS, tmux, fzf, entr, ferramentas de rede, `yq`, Pandoc, ImageMagick, Graphviz, HTTPie, bat, utilitários de compressão e clientes PostgreSQL, MySQL e Redis. As ferramentas Python `ruff`, `black` e `mypy`, além de ESLint, Prettier, Yarn e pnpm, ficam disponíveis globalmente.

O usuário padrão é `sandbox`, com home em `/home/sandbox` e shell Bash. O RootFS base não embute Ollama, imagens de emulador, Android NDK ou toolchains de IA pesadas; esses componentes são tratados como plugins ou perfis opcionais para manter o download principal controlado.

## Papel no runtime

O aplicativo baixa o tarball por HTTP, verifica o tamanho e o SHA-256 antes de considerá-lo pronto e o extrai para uso pelo runtime. O GitHub Releases é utilizado porque oferece distribuição HTTP e suporte a retomada por `Range`. O manifesto consumido pelo app está em `app/src/main/res/raw/rootfs_manifest.json`.

## Cadeia de dependência

O Agent Extra 0.4.1 usa esta release como `baseRootfs: 0.3.3`. O perfil Android/API 0.5.0 depende indiretamente dela por meio do Agent Extra. Portanto, alterações no RootFS base impactam os dois perfis derivados e devem ser tratadas como uma nova fase de build, não como atualização silenciosa do artefato homologado.

## Fonte e migração

Esta documentação foi consolidada a partir de `rootfs-builder/README.md`, `rootfs-builder/Dockerfile`, `rootfs-builder/build.sh`, `output/rootfs-build-info.txt`, `docs/ROADMAP_CANONICO.md` e do manifesto original no repositório [Kyra2214/SandBox](https://github.com/Kyra2214/SandBox). O artefato foi migrado para o BrainCode sem rebuild, alteração interna ou recompressão.

- [Release no BrainCode](https://github.com/Kyra2214/BrainCode/releases/tag/rootfs-v0.3.3)
- [Artefato no BrainCode](https://github.com/Kyra2214/BrainCode/releases/download/rootfs-v0.3.3/rootfs-ubuntu-0.3.3.tar.gz)
- [Sidecar SHA-256](https://github.com/Kyra2214/BrainCode/releases/download/rootfs-v0.3.3/rootfs-ubuntu-0.3.3.tar.gz.sha256)
- [Manifesto do app](../app/src/main/res/raw/rootfs_manifest.json)

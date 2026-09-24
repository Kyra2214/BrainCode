# Avisos de terceiros

Este projeto empacota, dentro do APK, binários de terceiros que não fazem
parte do código-fonte deste repositório. Nenhum deles foi modificado — são
usados como distribuídos originalmente pelo projeto de origem.

---

## proot

- **O que é**: ferramenta usada para emular `chroot`/namespaces sem privilégios
de root, permitindo rodar o rootfs Linux dentro da sandbox do app Android.
- **Projeto original**: https://proot-me.github.io/ (proot-me/proot no GitHub)
- **Licença**: GPL-2.0
- **Origem do binário empacotado**: pacote `proot` do repositório oficial do
Termux (`termux-main`).
- **Pacote exato**: `proot_5.1.107.92_aarch64.deb`
- **URL de origem**: https://cdimage.debian.org/mirror/termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.92_aarch64.deb
- **SHA-256 do binário empacotado**: `ea47e17da8e6ff4882c169c6508861e5b4be9227e477c6020f4f14facc85c10d`
- **Local no APK**: `jniLibs/arm64-v8a/libproot.so`
- **Como foi obtido**: `scripts/fetch-proot.sh`; o ELF foi apenas extraído do
`.deb` e renomeado para a convenção de empacotamento Android, sem modificação.
- **Código-fonte**: https://github.com/proot-me/proot

O arquivo `docs/proot-binary-provenance.txt` mantém o registro estruturado da origem e do hash usados nesta versão.

---

## Roofts 0.6 / Agent Skills

- **O que é**: camada de Skills e guias operacionais empacotada como assets do aplicativo; o
  conteúdo não é um orquestrador e não concede autorização.
- **Projeto original**: `addyosmani/agent-skills`.
- **Tag/commit auditado**: `0.6.10` / `c004a74784a08295d52749b04cda634125b9a581`.
- **Licença**: MIT.
- **Cópia da licença**: `app/src/main/assets/roofts/0.6/LICENSE`.
- **Local dos assets**: `app/src/main/assets/roofts/0.6`.
- **Adaptação BrainCode**: `brain/.../RooftsSkill.kt`, `app/.../RooftsSkillLoader.kt` e
  `brain/.../RooftsSkillSelector.kt`; ver `docs/ABSORCAO_PROVENIENCIA.md`.
- **Limite**: recursos externos, providers, rede, shell e escrita continuam fora desta
  absorção e sujeitos ao caminho Policy → ActionGateway → Sandbox.

---

## no-inference (motor conversacional determinístico)

- **O que é**: núcleo conversacional determinístico (pattern matcher, templates, aliases
  e knowledge base local) usado pelo `NoInferenceConversationEngine` na Porta 1 (CHAT).
- **Projeto original**: https://github.com/TheShovel/no-inference
- **Licença**: **AGPL-3.0**.
- **Cópia da licença**: `app/src/main/assets/no_inference/LICENSE`.
- **Local dos assets**: `app/src/main/assets/no_inference/`.
- **Adaptação BrainCode**: `NoInferenceConversationEngine` (adapter Android/Kotlin);
  ver `docs/ARQUITETURA_ATUAL.md` §11 e `docs/ESTADO_ATUAL.md`.
- **Limite / exclusões**: CLI, TUI, servidor, API web, coding agent `cos`, editor, code
  generator, math solver e integrações externas do projeto de origem **não** foram
  importados — só os recursos conversacionais e a licença.
- **Obrigação AGPL-3.0**: por empacotar um componente AGPL dentro do APK, a distribuição
  deve manter a oferta de código-fonte correspondente (do componente, incluindo
  modificações do adapter) e os notices desta licença. Isso é diferente das demais
  bibliotecas desta lista (MIT/Apache-2.0/GPL-2.0 do binário `proot`) e depende da
  decisão de licenciamento do projeto como um todo — ver `D7` em
  `docs/LEGADO_E_DECISOES.md` / `PLANO_IMPLEMENTACAO_POS_AUDITORIA.md`.

---

## SearchClaw e Firecrawl/web-agent (referência de arquitetura, não código empacotado)

- **O que é**: nenhum código, binário ou dependência desses projetos é compilado ou
  empacotado no APK. Eles foram usados como **referência de design** para o
  `WebResearchAgent` (harness de pesquisa determinístico do BrainCode): SearchClaw
  inspirou planning, citações, quality gates, diversidade de fontes e compactação de
  contexto; Firecrawl/web-agent inspirou a abstração de tools/providers e validação de
  schema. Ver `docs/ARQUITETURA_ATUAL.md` §12 e `docs/ESTADO_ATUAL.md`.
- **Projetos originais**: SearchClaw e Firecrawl (`firecrawl/web-agent`).
- **Licença**: MIT (ambos).
- **Por que aparece aqui**: registrado por transparência de proveniência de design,
  não porque exista obrigação de notice de um binário/dependência real.

---

## talloc

- **O que é**: biblioteca de alocação de memória hierárquica, dependência de runtime do
  `proot` empacotado (sem ela o linker do Android falha ao iniciar o `proot`).
- **Licença**: LGPL-3.0-or-later.
- **Origem do binário empacotado**: pacote `libtalloc` do repositório oficial do Termux
  (`termux-main`).
- **Pacote exato**: `libtalloc_2.4.3_aarch64.deb`.
- **URL de origem**: https://cdimage.debian.org/mirror/termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb
- **SHA-256 do binário empacotado**: `3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb`
- **Local no APK**: `jniLibs/arm64-v8a/libtalloc.so`.
- **Como foi obtido**: `scripts/fetch-proot.sh` (mesmo script que baixa o `proot`).
- **Código-fonte**: https://talloc.samba.org/

## libandroid-shmem

- **O que é**: implementação de memória compartilhada POSIX (`shm_open`/`shm_unlink`)
  para Android, dependência de runtime do `proot` empacotado.
- **Licença**: BSD de 3 cláusulas.
- **Origem do binário empacotado**: pacote `libandroid-shmem` do repositório oficial do
  Termux (`termux-main`).
- **Pacote exato**: `libandroid-shmem_0.7_aarch64.deb`.
- **URL de origem**: https://cdimage.debian.org/mirror/termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb
- **SHA-256 do binário empacotado**: `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731`
- **Local no APK**: `jniLibs/arm64-v8a/libandroid-shmem.so`.
- **Como foi obtido**: `scripts/fetch-proot.sh`.
- **Código-fonte**: https://github.com/termux/libandroid-shmem

## proot loader (`libapp_proot_loader.so`)

- **O que é**: o binário `loader` distribuído dentro do próprio pacote `proot` (não é um
  projeto separado); renomeado para `libapp_proot_loader.so` seguindo a técnica descrita
  em `docs/proot-noexec-strategy.md` (baseada em `Kyra2214/Dsh`) para ser extraído no
  diretório executável de bibliotecas nativas do Android.
- **Licença**: GPL-2.0 (mesma do `proot`, ver seção acima).
- **Origem**: mesmo pacote `proot_5.1.107.92_aarch64.deb` do `libproot.so`.
- **SHA-256 do binário empacotado**: `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04`
- **Local no APK**: `jniLibs/arm64-v8a/libapp_proot_loader.so`.
- **Gap conhecido**: `scripts/fetch-proot.sh` hoje extrai e registra `libproot.so`,
  `libtalloc.so` e `libandroid-shmem.so`, mas **não** extrai automaticamente o `loader`
  — o arquivo atual foi obtido manualmente do mesmo `.deb`. O script deveria ser
  atualizado para extrair e registrar este binário também, para manter a build
  reproduzível.

## commons-compress

- **O que é**: usada só para extrair TAR (Android não tem suporte nativo); não é
  binário nativo, é dependência JVM declarada em `android-module/build.gradle.kts`.
- **Licença**: Apache-2.0.
- **Versão**: `org.apache.commons:commons-compress:1.26.1`.
- **Origem**: Maven Central (`org.apache.commons`).

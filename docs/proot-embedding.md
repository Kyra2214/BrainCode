# Empacotando o `proot` no APK

O `SandboxRuntime` (Fase 0.3) usa o binário `proot` em
`nativeLibraryDir/libproot.so`. O artefato arm64 já está versionado em
`android-module/src/main/jniLibs/arm64-v8a/libproot.so`; resta validar a
execução em um device Android real.

## Por que o nome é `libproot.so` e não `proot`

O Android só extrai automaticamente do APK, para uma pasta executável,
arquivos que estão em `lib/<abi>/` e terminam em `.so`. É uma convenção do
sistema de empacotamento (pensada para bibliotecas nativas), mas qualquer
binário ELF vale — inclusive um executável comum, só renomeado. Essa é a
mesma técnica usada por outros projetos que empacotam binários auxiliares
dentro do APK.

**Importante sobre política de loja**: o binário precisa estar **dentro do
APK desde a instalação** (em `jniLibs`), não baixado depois. Baixar e
executar código nativo novo após a instalação é o que é restrito — baixar
*dados* (o conteúdo do rootfs, que não é código executável por si, é
interpretado pelos binários que já vieram no APK) é diferente e é o que a
Fase 0.2 já faz.

## Como obter o binário

`proot` é software livre (GPL). Duas opções, nessa ordem de preferência:

1. **Compilar a partir do código-fonte oficial** do projeto proot
   (`proot-me/proot` no GitHub) usando o Android NDK, gerando um binário
   estático para cada ABI que formos suportar (no mínimo `arm64-v8a`).
2. Usar um binário já compilado de uma distribuição que também é open
   source e publica os binários prontos (ex: pacote `proot` do repositório
   de pacotes do Termux) — nesse caso, manter o binário como está, sem
   modificação, e registrar a licença/origem no `docs/THIRD_PARTY_NOTICES.md`
   (ainda não criado).

## Onde colocar depois de obtido

```
android-module/
  src/main/jniLibs/
    arm64-v8a/
      libproot.so
    armeabi-v7a/       (opcional, dispositivos mais antigos)
      libproot.so
```

O Gradle empacota automaticamente qualquer coisa em `jniLibs/<abi>/` dentro
do APK, disponível depois em `applicationInfo.nativeLibraryDir` — é
exatamente o caminho que `AndroidSandboxFactory.prootExecutablePath` espera.

## Decisão tomada

Optamos pela opção 2: reaproveitar o binário já compilado, vindo do
**repositório oficial do Termux** (pacote `proot`, `termux-main`), não do
`proot-static` (ZhymabekRoman) — aquele é armhf-only de 2021 rodando em
arm64 via compatibilidade 32-bit, o que é frágil frente à exigência de
64-bit nativo da Play Store e depende de suporte a binários de 32 bits
que nem todo device garante mais.

O binário é obtido, sem nenhuma modificação, via `scripts/fetch-proot.sh`
(baixa o `.deb` oficial do Termux, extrai o ELF, copia para
`jniLibs/<abi>/libproot.so`). Detalhes de licença e origem exata em
`docs/THIRD_PARTY_NOTICES.md`.

O script foi executado e o binário foi incluído no projeto. A proveniência
exata, versão e hash estão em `docs/proot-binary-provenance.txt`.

## Status

- [x] Escolher a fonte do binário (compilar vs. reusar) → reusar, repo
      oficial do Termux
- [x] Rodar `scripts/fetch-proot.sh` e commitar o resultado em
      `jniLibs/arm64-v8a/libproot.so`
- [ ] Testar `proot --version` dentro do sandbox de um device real
- [ ] Preencher `docs/THIRD_PARTY_NOTICES.md` com versão/hash exatos

O único passo ainda dependente de hardware é o teste manual em device real.

## Restrição de execução do Android 10+ (W^X) e `targetSdk`

A partir do Android 10 (API 29), o SELinux bloqueia `execve()`/`dlopen()`
em qualquer arquivo que o próprio app tenha gravado em seu diretório
privado depois de instalado (`app_data_file`) — é exatamente o que
acontece toda vez que o `proot` tenta rodar um binário de dentro do
rootfs extraído (`bash`, `git`, `python3`, etc, todos em `filesDir`).
O `libproot.so` em si escapa disso porque `nativeLibraryDir` tem outro
contexto SELinux (liberado, por vir do APK); os binários do **rootfs**
não têm como se beneficiar do mesmo truque — não dá pra empacotar um
rootfs Ubuntu inteiro como arquivos `.so` individuais em `jniLibs`.

Dois caminhos possíveis, avaliados na Fase 0.4:

- **Baixar `targetSdk` pra ≤28** (escolhido): apps nessa faixa caem num
  domínio SELinux de compatibilidade (`untrusted_app_27`) isento dessa
  regra — é a mesma saída que o Termux usou por anos. Resolve na hora,
  sem tocar em `proot`/rootfs. Custo: o APK não pode ser publicado/
  atualizado via Google Play (que hoje exige `targetSdk` bem mais alto);
  só serve pra instalação manual/sideload, que é como este projeto é
  distribuído por enquanto.
- **Truque do `termux-exec`** (não implementado): interceptar todo
  `execve()` de dentro do rootfs via `LD_PRELOAD`, redirecionando pra
  `/system/bin/linker64 <binário>` em vez de exec direto no arquivo do
  rootfs — mantém `targetSdk` moderno e compatível com a Play Store, mas
  exige portar essa lib (ela intercepta TODO subprocesso disparado
  dentro do sandbox, não só o shell inicial). Fica registrado aqui como
  alternativa caso a publicação na Play volte a ser objetivo do projeto.

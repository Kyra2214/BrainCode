# Estratégia: proot disfarçado de biblioteca nativa (contorna noexec / W^X)

Baseado na técnica usada pelo projeto `Kyra2214/Dsh`. Resolve o erro:

```
proot error: execve("/usr/bin/bash"): Permission denied
fatal error: see `libproot.so --help`.
```

## Por que funciona

Em muitos dispositivos Android, o diretório de dados privados do app
(`/data/user/0/<pkg>/files/...`) está numa partição com `noexec`, ou o
SELinux/W^X (Android 10+) bloqueia a execução de arquivos "genéricos"
extraídos ali. **O único diretório que o próprio Android garante ser
executável dentro da sandbox do app é `nativeLibraryDir`** — porque é de
lá que o `System.loadLibrary()` carrega código nativo, e isso *precisa*
funcionar sempre.

A estratégia é: empacotar o binário `proot` (e o `loader` ELF que ele usa)
como se fossem bibliotecas nativas JNI comuns, deixando o próprio instalador
do Android extraí-los para esse diretório com permissão de execução já
concedida pelo sistema.

## Passo a passo

### 1. Renomear os binários como `.so`

```
proot            -> libapp_proot.so
loader (do proot) -> libapp_proot_loader.so
```

O prefixo `lib` e o sufixo `.so` são **obrigatórios** — é assim que o
empacotador do Android reconhece o arquivo como biblioteca nativa elegível
para `jniLibs`.

### 2. Colocar na estrutura de ABI correta

```
android/app/src/main/jniLibs/
└── arm64-v8a/
    ├── libapp_proot.so
    └── libapp_proot_loader.so
```

(Repita para `armeabi-v7a`, `x86_64` etc. se for dar suporte a outras ABIs.)

### 3. Configurar o `build.gradle` — ver `build.gradle.snippet`

Dois pontos críticos que quebram a estratégia se esquecidos:

- **`useLegacyPackaging = true`** (ou `android:extractNativeLibs="true"` no
  manifest, dependendo da versão do AGP): sem isso, o Android pode manter os
  `.so` **comprimidos dentro do APK** e carregá-los via `mmap` direto do zip
  em vez de extraí-los como arquivo solto — nesse caso **não existe um
  caminho de arquivo real para passar ao `ProcessBuilder`**, e a estratégia
  não funciona.
- `abiFilters` deve bater com as ABIs para as quais você compilou o proot.

### 4. Configurar o `AndroidManifest.xml` — ver `AndroidManifest.snippet.xml`

### 5. Usar as classes Kotlin

- `RuntimeFailure.kt` — exceção tipada com código de erro.
- `PackagedRuntime.kt` — a classe principal:
  1. Localiza os `.so` em `nativeLibraryDir`.
  2. Cria um **symlink** (ou hard-copy de fallback) num diretório privado
     próprio, com nome normal (`proot`, `loader`) — necessário porque você
     precisa passar argumentos livres (`-r`, `-w`, `-b`...), o que não dá
     pra fazer chamando a lib via JNI.
  3. Se o symlink falhar (`EACCES`/`EPERM`/`ENOTSUP`/`EXDEV` — comum em ROMs
     como Honor/荣耀 que bloqueiam `symlink()` via SELinux), faz fallback:
     copia o arquivo, marca `chmod +x` e tenta gravar o xattr
     `security.android.exec` (selo exigido pelo Android 15+ para permitir
     execução de arquivos fora dos caminhos "confiáveis").
  4. Monta o `argv` do proot e executa via `ProcessBuilder` com ambiente
     limpo (`environment().clear()`).
  5. Se falhar por incompatibilidade de `seccomp` do kernel, tenta de novo
     com `PROOT_NO_SECCOMP=1` antes de desistir.

## Uso

```kotlin
val runtime = PackagedRuntime(context, rootfsDir = File(context.noBackupFilesDir, "rootfs"))

runtime.prepare() // extrai/linka os binários, lança RuntimeFailure se impossível

val process = runtime.launch(
    entrypoint = listOf("/bin/bash", "--login"),
    bindMounts = listOf(ProotBindMount("/dev"), ProotBindMount("/proc")),
    env = mapOf("HOME" to "/root", "TERM" to "xterm-256color"),
)
```

## Limites que essa estratégia NÃO contorna

- **ptrace bloqueado pelo kernel** (`Yama` restrito, ROMs MIUI/EMUI
  hardened): proot depende de `ptrace()`; se o kernel nega isso para
  processos sem privilégio, não há contorno em espaço de usuário — a
  alternativa nesse caso é rodar sob `qemu-user` estático em vez de proot,
  ou orientar o usuário a usar um dispositivo/ROM sem essa restrição.
- **Arquiteturas incompatíveis**: se o `proot` compilado for arm64 e o
  dispositivo for outra ABI, nada disso ajuda — precisa compilar/empacotar
  o binário certo por ABI.

## Integração neste repositório

A implementação foi adaptada para `android-module` e usa o artefato nativo já versionado em `android-module/src/main/jniLibs/arm64-v8a/libproot.so`. O loader separado descrito no plano é opcional na implementação atual (`libapp_proot_loader.so`); quando presente, ele é configurado por `PROOT_LOADER`. A ABI atualmente suportada é `arm64-v8a`.

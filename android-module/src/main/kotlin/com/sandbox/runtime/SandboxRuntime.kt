package com.sandbox.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executa comandos dentro do rootfs isolado, via `proot`.
 *
 * Esta classe é deliberadamente "burra": não decide nada sobre IA, agente
 * ou tool-calling — só recebe um comando, roda dentro do ambiente isolado,
 * e devolve stdout/stderr/exitCode. As camadas de cima (Fase 1 em diante)
 * decidem o que mandar executar.
 *
 * @property prootExecutable caminho absoluto do binário `proot`. Deve estar
 *   empacotado DENTRO do APK (ex: `nativeLibraryDir/libproot.so`), nunca
 *   baixado separadamente — baixar e executar código nativo fora do APK
 *   depois da instalação é restrito pela política da Play Store. O que é
 *   baixado sob demanda (Fase 0.2) é só o *conteúdo* do rootfs (dados),
 *   não um executável novo.
 * @property rootfsDir pasta onde o rootfs já foi extraído (resultado do
 *   `TarGzExtractor.extract`).
 * @property tmpDir pasta gravável fora do rootfs, usada pelo `proot` para
 *   arquivos temporários internos (ex: `--link2symlink`). Normalmente
 *   `context.cacheDir/sandbox-tmp`. Se não existir, é criada.
 * @property disableSeccompAcceleration corresponde a `PROOT_NO_SECCOMP=1`.
 *   O `proot` tenta acelerar a interceptação de syscalls via seccomp; em
 *   vários kernels de fabricante (MIUI, ColorOS, One UI, etc.) esse caminho
 *   quebra e o sintoma típico é exatamente `execve` de um binário real
 *   (ex: `python3`) devolvendo `ENOSYS` ("Function not implemented"),
 *   enquanto binários minúsculos (`echo`, applet do busybox) continuam
 *   funcionando. Desligar a aceleração custa um pouco de performance e
 *   resolve a esmagadora maioria desses casos — por isso o padrão aqui é
 *   `true`.
 */
class SandboxRuntime(
    private val prootExecutable: String,
    private val rootfsDir: File,
    private val tmpDir: File,
    private val disableSeccompAcceleration: Boolean = true,
    private val nativeLibraryDir: String? = null,
    private val prootLoader: String? = null,
    // Ver ProotResourceLimits.kt — mesmo mecanismo do ProotProcessLauncher.
    private val resourceLimits: ProotResourceLimits = ProotResourceLimits.DEFAULT
) {

    init {
        require(File(prootExecutable).exists()) {
            "Binário proot não encontrado em $prootExecutable"
        }
        require(rootfsDir.exists() && rootfsDir.isDirectory) {
            "Rootfs não encontrado ou inválido em ${rootfsDir.path}. " +
                "Rode TarGzExtractor.extract antes de criar o SandboxRuntime."
        }
        tmpDir.mkdirs()
        // /dev, /proc e /sys precisam existir DENTRO do rootfs como pontos de
        // montagem para o bind (-b) funcionar; o export do Docker às vezes
        // não inclui esses diretórios vazios.
        listOf("dev", "proc", "sys", "tmp").forEach { File(rootfsDir, it).mkdirs() }
    }

    /**
     * Executa um comando dentro do rootfs isolado.
     *
     * @param command comando e argumentos, ex: `listOf("node", "-v")`.
     *   Não é uma string de shell livre — evita problemas de escaping e
     *   injeção. Se precisar de pipe/redirecionamento, passe explicitamente
     *   `listOf("/bin/bash", "-c", "comando completo")`.
     * @param timeoutSeconds tempo máximo de execução; passado esse tempo o
     *   processo é destruído e [SandboxExecutionResult.timedOut] vem `true`.
     * @param workingDir diretório de trabalho DENTRO do rootfs (ex: "/home/sandbox").
     */
    fun execute(
        command: List<String>,
        timeoutSeconds: Long = 120,
        workingDir: String = "/home/sandbox"
    ): SandboxExecutionResult {
        val prootArgs = buildProotCommand(command, workingDir)

        val processBuilder = ProcessBuilder(prootArgs)
            .redirectErrorStream(false)

        // Não herdar o ambiente do processo Android (paths/variáveis do
        // host não fazem sentido dentro do rootfs Linux) — define do zero
        // só o que o ambiente guest precisa para achar binários, HOME,
        // locale e temporários.
        processBuilder.environment().apply {
            clear()
            // O proot é iniciado diretamente como executável e depende de
            // libtalloc.so e libandroid-shmem.so. O linker do Android não
            // procura automaticamente essas dependências no diretório do
            // executável, então informamos explicitamente esse caminho.
            put("LD_LIBRARY_PATH", nativeLibraryDir ?: File(prootExecutable).parentFile?.absolutePath.orEmpty())
            put(
                "PATH",
                "/home/sandbox/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            )
            put("HOME", workingDir)
            put("USER", "sandbox")
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("TMPDIR", "/tmp")
            put("PROOT_TMP_DIR", tmpDir.absolutePath)
            prootLoader?.let { put("PROOT_LOADER", it) }
            if (disableSeccompAcceleration) {
                put("PROOT_NO_SECCOMP", "1")
            }
        }

        val process = processBuilder.start()

        val stdoutBuffer = StringBuilder()
        val stderrBuffer = StringBuilder()

        // O encerramento forçado fecha os streams enquanto as threads leem.
        // A exceção de stream fechado é esperada e não deve derrubar o app.
        val stdoutThread = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { stdoutBuffer.appendLine(it) }
            } catch (_: Exception) {
                // Stream fechado após destroyForcibly(): resultado esperado.
            }
        }
        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { stderrBuffer.appendLine(it) }
            } catch (_: Exception) {
                // Stream fechado após destroyForcibly(): resultado esperado.
            }
        }
        stdoutThread.isDaemon = true
        stderrThread.isDaemon = true
        stdoutThread.start()
        stderrThread.start()

        val finishedInTime = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!finishedInTime) {
            process.destroyForcibly()
            stdoutThread.join(2_000)
            stderrThread.join(2_000)
            val (cleaned, verified) = verifyResourceLimits(stderrBuffer.toString())
            return SandboxExecutionResult(
                stdout = stdoutBuffer.toString(),
                stderr = cleaned,
                exitCode = -1,
                timedOut = true,
                resourceLimitsVerified = verified
            )
        }

        stdoutThread.join()
        stderrThread.join()

        val (cleanedStderr, verifiedLimits) = verifyResourceLimits(stderrBuffer.toString())
        return SandboxExecutionResult(
            stdout = stdoutBuffer.toString(),
            stderr = cleanedStderr,
            exitCode = process.exitValue(),
            timedOut = false,
            resourceLimitsVerified = verifiedLimits
        )
    }

    /** Ver ManagedSandboxRuntime.verifyResourceLimits — mesma lógica, launcher diferente. */
    private fun verifyResourceLimits(rawStderr: String): Pair<String, Boolean?> {
        if (!resourceLimits.hasLimits()) return rawStderr to null
        val (cleaned, parsed) = ResourceLimitVerification.extract(rawStderr)
        return cleaned to ResourceLimitVerification.matches(resourceLimits, parsed)
    }

    /**
     * Monta a linha de comando do proot.
     *
     * `-r rootfsDir`      → usa rootfsDir como raiz do filesystem isolado
     * `-w workingDir`     → diretório de trabalho inicial, já dentro do rootfs
     * `-0`                → simula uid 0 dentro do sandbox (necessário para
     *                       muitas ferramentas de linha de comando funcionarem
     *                       sem exigir root real do dispositivo)
     * `-b /dev -b /proc -b /sys` → monta (bind) os pseudo-filesystems reais
     *                       do Android dentro do rootfs. Sem isso, qualquer
     *                       binário real (python3, node, bash) que leia
     *                       `/dev/null`, `/dev/urandom` e arquivos em
     *                       `/proc/self/` etc.
     *                       falha ou trava — o `echo` funcionava sem isso só
     *                       por não precisar de nenhum dos dois.
     * `--link2symlink`    → necessário para rootfs com symlinks "mágicos"
     *                       tipo `/bin -> usr/bin` (padrão em Alpine/Debian)
     *                       quando o armazenamento do app não suporta bem
     *                       hardlinks; é a mesma flag usada pelo
     *                       `proot-distro` do Termux para esses rootfs.
     * `--kill-on-exit`    → evita processos zumbis do lado de dentro do
     *                       rootfs se o proot for encerrado abruptamente
     *
     * A variável `PROOT_NO_SECCOMP=1` (ver [disableSeccompAcceleration]) é
     * passada via ambiente, não como argumento de linha de comando — é assim
     * que o próprio `proot` espera recebê-la.
     *
     * O shell usado para o `-c` é `/bin/bash`, não `/bin/sh`. No rootfs
     * Ubuntu (a partir da troca do Alpine), `/bin/sh` é o fim de uma cadeia
     * de 4 saltos de symlink via update-alternatives
     * (`/bin/sh -> /usr/bin/sh -> /etc/alternatives/sh -> /bin/dash ->
     * /usr/bin/dash`), e cada rewrite de link absoluto feito pelo
     * `TarGzExtractor` na extração (necessário para não vazar caminho do
     * host Android) é mais uma chance de quebrar essa cadeia sob `proot`.
     * `/bin/bash` sofre só 1 salto (usrmerge: `/bin/bash -> /usr/bin/bash`)
     * e é instalado como pacote de primeira classe no Dockerfile do
     * rootfs-builder, não via alternatives — bem mais robusto aqui.
     */
    private fun buildProotCommand(command: List<String>, workingDir: String): List<String> = buildList {
        add(prootExecutable)
        add("-r"); add(rootfsDir.absolutePath)
        add("-w"); add(workingDir)
        add("-0")
        addAll(ProotDeviceBinds.bindArgs())
        add("--link2symlink")
        add("--kill-on-exit")
        add("/bin/bash"); add("-c")
        add(resourceLimits.verifiedPreamble() + "exec " + command.joinToString(" ") { shellEscape(it) })
    }

    private fun shellEscape(arg: String): String {
        // Escaping simples e conservador — suficiente para comandos comuns
        // de build/teste. Não é um parser de shell completo.
        return if (arg.matches(Regex("^[A-Za-z0-9_\\-./=]+$"))) {
            arg
        } else {
            "'" + arg.replace("'", "'\\''") + "'"
        }
    }
}

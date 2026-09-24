package com.sandbox.runtime

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

data class ProotBindMount(val source: String, val target: String = source)

/**
 * Executa proot a partir de nativeLibraryDir, evitando executar binários
 * extraídos pelo app em filesDir. O artefato atual do projeto é libproot.so;
 * um loader separado é usado automaticamente quando libapp_proot_loader.so
 * for incluído em uma futura ABI.
 */
class PackagedRuntime(context: Context, private val rootfsDir: File) {
    private val appContext = context.applicationContext
    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)
    private val runnerLib = File(nativeDir, "libproot.so")
    private val loaderLib = File(nativeDir, "libapp_proot_loader.so")
    private val launchDir = File(appContext.noBackupFilesDir, "runner")
    private val launchRunner = File(launchDir, "proot")
    private val tmpDir = File(appContext.cacheDir, "proot-tmp")

    val preparedRunnerPath: String get() = launchRunner.absolutePath
    val nativeLibraryPath: String get() = nativeDir.absolutePath
    val prootTmpPath: File get() = tmpDir
    val packagedLoaderPath: String? get() = loaderLib.takeIf { it.isFile }?.absolutePath

    fun runnerAvailable(): Boolean = runnerLib.isFile && runnerLib.canRead()

    @Synchronized
    fun prepare() {
        if (!runnerAvailable()) throw RuntimeFailure(
            "RUNNER_UNAVAILABLE", "APK não contém libproot.so para a ABI deste dispositivo"
        )
        if (!rootfsDir.isDirectory) throw RuntimeFailure(
            "ROOTFS_UNAVAILABLE", "Rootfs não encontrado em ${rootfsDir.absolutePath}"
        )
        if (!launchDir.exists() && !launchDir.mkdirs()) throw RuntimeFailure(
            "RUNNER_PREPARE_FAILED", "Não foi possível criar o diretório do runner"
        )
        tmpDir.mkdirs()
        Os.chmod(launchDir.absolutePath, 0x1c0)
        refreshExecutableLink(runnerLib, launchRunner)
    }

    fun launch(
        entrypoint: List<String>,
        bindMounts: List<ProotBindMount> = emptyList(),
        env: Map<String, String> = emptyMap(),
        disableSeccomp: Boolean = true,
    ): Process {
        prepare()
        val command = buildList {
            add(launchRunner.absolutePath); add("-r"); add(rootfsDir.absolutePath)
            add("-0"); add("-w"); add("/root")
            bindMounts.forEach { add("-b"); add(if (it.source == it.target) it.source else "${it.source}:${it.target}") }
            add("/usr/bin/env"); add("-i")
            env.forEach { (key, value) -> add("$key=$value") }
            addAll(entrypoint)
        }
        return ProcessBuilder(command).directory(rootfsDir).redirectErrorStream(true).apply {
            environment().clear()
            environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
            environment()["TMPDIR"] = tmpDir.absolutePath
            environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
            if (loaderLib.isFile) environment()["PROOT_LOADER"] = loaderLib.absolutePath
            if (disableSeccomp) environment()["PROOT_NO_SECCOMP"] = "1"
        }.start()
    }

    fun detectSeccompFallback(timeoutSeconds: Long = 10): Boolean {
        val direct = runProbe(false, timeoutSeconds)
        if (direct.exitCode == 0) return false
        if (!direct.output.lowercase().contains("seccomp")) throw classifyFailure(direct.output)
        val fallback = runProbe(true, timeoutSeconds)
        if (fallback.exitCode == 0) return true
        throw classifyFailure(fallback.output)
    }

    private fun runProbe(disableSeccomp: Boolean, timeout: Long): ProbeResult {
        val process = launch(listOf("/bin/sh", "-c", "exit 0"), disableSeccomp = disableSeccomp)
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) process.destroyForcibly()
        return ProbeResult(if (process.isAlive) null else process.exitValue(), output)
    }

    private data class ProbeResult(val exitCode: Int?, val output: String)

    private fun classifyFailure(output: String): RuntimeFailure {
        val text = output.lowercase()
        return when {
            "ptrace" in text && ("operation not permitted" in text || "permission denied" in text) ->
                RuntimeFailure("PROOT_PTRACE_DENIED", "O kernel nega o ptrace exigido pelo proot")
            "seccomp" in text -> RuntimeFailure("PROOT_SECCOMP_UNAVAILABLE", "seccomp incompatível com o proot")
            "execve(" in text || "proot error" in text -> RuntimeFailure("PROOT_GUEST_EXEC_FAILED", "proot não carregou o programa")
            else -> RuntimeFailure("PROOT_GUEST_START_FAILED", "proot não iniciou o userland")
        }
    }

    private fun refreshExecutableLink(target: File, link: File) {
        if (isPrepared(target, link)) return
        val pending = File(launchDir, ".${link.name}.new")
        pending.delete()
        try {
            try {
                Os.symlink(target.absolutePath, pending.absolutePath)
            } catch (error: ErrnoException) {
                if (error.errno !in setOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.ENOTSUP, OsConstants.EXDEV)) throw error
                target.copyTo(pending); pending.setExecutable(true, false)
            }
            if (!isPrepared(target, pending)) throw RuntimeFailure("RUNNER_PREPARE_FAILED", "Runner não ficou executável")
            Os.rename(pending.absolutePath, link.absolutePath)
        } finally { pending.delete() }
        if (!isPrepared(target, link)) throw RuntimeFailure("RUNNER_PREPARE_FAILED", "Runner privado inválido")
    }

    private fun isPrepared(target: File, path: File): Boolean = try {
        val stat = Os.lstat(path.absolutePath)
        if (OsConstants.S_ISLNK(stat.st_mode)) Os.readlink(path.absolutePath) == target.absolutePath
        else OsConstants.S_ISREG(stat.st_mode) && stat.st_size > 0
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw error
    }
}

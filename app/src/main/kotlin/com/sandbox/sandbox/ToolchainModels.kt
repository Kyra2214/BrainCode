package com.sandbox.sandbox

import java.io.File

/** Toolchains suportadas pelo catálogo declarativo do Sandbox. */
enum class ToolchainKind { ANDROID, JAVA, PYTHON, NODE, CPP, RUST, GO }

data class ToolchainProfile(
    val id: String,
    val kind: ToolchainKind,
    val displayName: String,
    val executable: String,
    val versionArguments: List<String>,
    val packages: List<String>,
    val validationArguments: List<String> = versionArguments
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "ID de toolchain inválido" }
        require(displayName.isNotBlank())
        require(executable.matches(Regex("[a-zA-Z0-9._+-]+"))) { "Executável de toolchain inválido" }
        require(versionArguments.isNotEmpty() && validationArguments.isNotEmpty())
        require(packages.isNotEmpty() && packages.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9+._:-]*")) }) {
            "Pacotes de toolchain inválidos"
        }
    }
}

data class ToolchainDetection(
    val profile: ToolchainProfile,
    val installed: Boolean,
    val versionOutput: String = "",
    val diagnostic: String? = null
)

data class ToolchainInstallPlan(
    val profile: ToolchainProfile,
    val command: List<String>,
    val timeoutSeconds: Long = 900
) {
    init {
        require(command.size == 3 && command[0] == "bash" && command[1] == "-c")
        require(timeoutSeconds in 60..3600)
    }
}

object BuiltInToolchains {
    val all: List<ToolchainProfile> = listOf(
        ToolchainProfile("android", ToolchainKind.ANDROID, "Android SDK/NDK", "sdkmanager", listOf("--version"), listOf("android-sdk", "android-sdk-platform-tools", "android-sdk-build-tools")),
        ToolchainProfile("java", ToolchainKind.JAVA, "Java", "java", listOf("--version"), listOf("default-jdk")),
        ToolchainProfile("python", ToolchainKind.PYTHON, "Python", "python3", listOf("--version"), listOf("python3", "python3-pip")),
        ToolchainProfile("node", ToolchainKind.NODE, "Node.js", "node", listOf("--version"), listOf("nodejs", "npm")),
        ToolchainProfile("cpp", ToolchainKind.CPP, "C/C++", "g++", listOf("--version"), listOf("g++", "make")),
        ToolchainProfile("rust", ToolchainKind.RUST, "Rust", "rustc", listOf("--version"), listOf("rustc", "cargo")),
        ToolchainProfile("go", ToolchainKind.GO, "Go", "go", listOf("version"), listOf("golang-go"))
    )
}

/** Detecta somente comandos declarados pelo perfil; nunca aceita entrada do usuário como shell. */
class ToolchainDetector(private val executor: SandboxCommandExecutor) {
    fun detect(profile: ToolchainProfile): ToolchainDetection {
        // Java must use the same direct allowlisted execution path as every other toolchain.
        // The previous implementation wrapped `java --version` in `bash -c`, which is
        // intentionally rejected by SecureCommandExecutor as unrestricted shell execution.
        val command = listOf(profile.executable) + profile.versionArguments
        val result = executor.execute(command, timeoutSeconds = 30)
        if (result.succeeded) {
            return ToolchainDetection(profile, true, result.stdout.take(4096), null)
        }
        val diagnostic = result.stderr.take(4096)
        val memoryProbeFailure = diagnostic.contains("reserve", ignoreCase = true) ||
            diagnostic.contains("memory", ignoreCase = true) ||
            diagnostic.contains("heap", ignoreCase = true)
        if (memoryProbeFailure) {
            if (result.exitCode != 127) {
                return ToolchainDetection(
                    profile,
                    installed = true,
                    versionOutput = "binário instalado: ${profile.executable}",
                    diagnostic = "probe de versão bloqueado por memória: $diagnostic"
                )
            }
        }
        return ToolchainDetection(profile, false, (result.stdout.ifBlank { result.stderr }).take(4096), diagnostic.ifBlank { null })
    }

    /** Captura somente versões dos pacotes do próprio perfil que já estavam instalados. */
    fun snapshotInstalledPackages(profile: ToolchainProfile): List<String> {
        return profile.packages.mapNotNull { packageName ->
            val result = executor.execute(listOf("dpkg-query", "-W", "-f=\${db:Status-Status} \${Version}", packageName), timeoutSeconds = 30)
            if (result.succeeded && result.stdout.startsWith("installed ")) "$packageName=${result.stdout.substringAfter(' ').trim()}" else null
        }.sorted()
            .toList()
    }

    fun installedBytes(profile: ToolchainProfile): Long = profile.packages.sumOf { packageName ->
        val result = executor.execute(listOf("dpkg-query", "-W", "-f=\${Installed-Size}", packageName), timeoutSeconds = 30)
        result.stdout.trim().toLongOrNull()?.times(1024L) ?: 0L
    }

    fun planInstall(profile: ToolchainProfile): ToolchainInstallPlan {
        val packageList = profile.packages.joinToString(" ")
        val script = "set -o pipefail; " +
            "export DEBIAN_FRONTEND=noninteractive; " +
            "if ! find /var/lib/apt/lists -type f -print -quit 2>/dev/null | grep -q .; then " +
            "apt-get update -qq || exit ${'$'}?; " +
            "fi; " +
            "apt-get -o Dpkg::Use-Pty=0 install -y --no-install-recommends --fix-missing " +
            packageList + " 2>&1 | tail -n 120"
        return ToolchainInstallPlan(profile, listOf("bash", "-c", script))
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

enum class ToolchainState { NOT_INSTALLED, INSTALLING, INSTALLED, FAILED, REMOVING, ROLLED_BACK }

data class ToolchainStatus(
    val profileId: String,
    val state: ToolchainState,
    val versionOutput: String = "",
    val error: String? = null,
    /** Espaço alocado pelos pacotes desta toolchain; não é espaço livre do disco. */
    val installedBytes: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Lifecycle transacional local com restauração exata do estado observado antes da instalação. */
class ToolchainManager(
    private val executor: SandboxCommandExecutor,
    private val stateDir: File,
    profiles: List<ToolchainProfile> = BuiltInToolchains.all
) {
    private val profilesById = profiles.associateBy { it.id }
    private val detector = ToolchainDetector(executor)
    private val transactionStore = ToolchainTransactionStore(File(stateDir, "transactions"))
    private val lock = Any()

    init { stateDir.mkdirs() }

    fun status(id: String): ToolchainStatus = synchronized(lock) {
        val profile = profile(id)
        val persisted = readState(profile)
        if (persisted != null) return@synchronized persisted
        transactionStore.cached(id) ?: run {
            val detected = detector.detect(profile)
            ToolchainStatus(
                profileId = id,
                state = if (detected.installed) ToolchainState.INSTALLED else ToolchainState.NOT_INSTALLED,
                versionOutput = detected.versionOutput,
                error = detected.diagnostic,
                installedBytes = if (detected.installed) detector.installedBytes(profile) else 0L
            )
        }
    }

    fun cachedStatus(id: String): ToolchainStatus? = synchronized(lock) { profile(id); transactionStore.cached(id) }

    fun refreshStatus(id: String): ToolchainStatus = synchronized(lock) {
        val profile = profile(id)
        stateFile(profile).delete()
        val detected = detector.detect(profile)
        persist(
            ToolchainStatus(
                profileId = id,
                state = if (detected.installed) ToolchainState.INSTALLED else ToolchainState.NOT_INSTALLED,
                versionOutput = detected.versionOutput,
                error = detected.diagnostic,
                installedBytes = if (detected.installed) detector.installedBytes(profile) else 0L
            )
        )
    }

    fun install(id: String): ToolchainStatus = synchronized(lock) {
        val profile = profile(id)
        val before = detector.detect(profile)
        val previous = ToolchainStatus(id, if (before.installed) ToolchainState.INSTALLED else ToolchainState.NOT_INSTALLED, before.versionOutput, before.diagnostic)
        if (before.installed) return@synchronized persist(previous)
        val installedBefore = detector.snapshotInstalledPackages(profile)
        transactionStore.saveBeforeInstall(profile, previous, installedBefore)
        persist(ToolchainStatus(id, ToolchainState.INSTALLING))
        return@synchronized try {
            val execution = executor.execute(detector.planInstall(profile).command, 900)
            if (!execution.succeeded) error(execution.stderr.ifBlank { "instalação falhou" })
            val after = detector.detect(profile)
            check(after.installed) { "validação pós-instalação falhou" }
            val installed = persist(ToolchainStatus(id, ToolchainState.INSTALLED, after.versionOutput, installedBytes = detector.installedBytes(profile)))
            transactionStore.clearSnapshot(id)
            installed
        } catch (error: Exception) {
            val rollbackError = rollbackLocked(profile)
            val diagnostic = listOfNotNull(error.message, rollbackError).joinToString("; ")
            persist(ToolchainStatus(id, if (rollbackError == null) ToolchainState.ROLLED_BACK else ToolchainState.FAILED, error = diagnostic))
        }
    }

    fun rollback(id: String): ToolchainStatus = synchronized(lock) {
        rollbackLocked(profile(id))
        status(id)
    }

    fun remove(id: String): ToolchainStatus = synchronized(lock) {
        val profile = profile(id)
        persist(ToolchainStatus(id, ToolchainState.REMOVING))
        return@synchronized try {
            val command = listOf("apt-get", "-o", "Dpkg::Use-Pty=0", "remove", "-y") + profile.packages
            val execution = executor.execute(command, 900)
            check(execution.succeeded) { execution.stderr.ifBlank { "remoção falhou" } }
            val result = persist(ToolchainStatus(id, ToolchainState.NOT_INSTALLED))
            transactionStore.clearSnapshot(id)
            result
        } catch (error: Exception) {
            persist(ToolchainStatus(id, ToolchainState.FAILED, error = error.message ?: error.javaClass.simpleName))
        }
    }

    private fun rollbackLocked(profile: ToolchainProfile): String? {
        val snapshot = transactionStore.loadSnapshot(profile.id) ?: return null
        val preExistingNames = snapshot.installedPackages.map { it.substringBefore('=') }.toSet()
        val addedByTransaction = profile.packages.filter { it !in preExistingNames }
        if (addedByTransaction.isNotEmpty()) {
            val result = executor.execute(listOf("apt-get", "-o", "Dpkg::Use-Pty=0", "remove", "-y") + addedByTransaction, 900)
            if (!result.succeeded) return result.stderr.ifBlank { "rollback remove falhou" }.take(4096)
        }

        if (snapshot.installedPackages.isNotEmpty()) {
            val exact = snapshot.installedPackages.map { it.substringBefore('=') }
            val result = executor.execute(listOf("apt-get", "-o", "Dpkg::Use-Pty=0", "install", "-y") + exact, 900)
            if (!result.succeeded) return result.stderr.ifBlank { "rollback restore falhou: versão anterior indisponível" }.take(4096)
        }

        transactionStore.clearSnapshot(profile.id)
        return null
    }

    private fun profile(id: String): ToolchainProfile = profilesById[id] ?: error("Toolchain desconhecida: $id")
    private fun stateFile(profile: ToolchainProfile) = File(stateDir, "${profile.id}.state")

    private fun readState(profile: ToolchainProfile): ToolchainStatus? {
        val file = stateFile(profile)
        if (!file.isFile) return null
        val parts = file.readLines()
        return runCatching { ToolchainStatus(profile.id, ToolchainState.valueOf(parts.firstOrNull().orEmpty()), parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty().ifBlank { null }, parts.getOrNull(4)?.toLongOrNull() ?: 0L, parts.getOrNull(3)?.toLong() ?: file.lastModified()) }.getOrNull()
    }

    private fun persist(status: ToolchainStatus): ToolchainStatus {
        stateDir.mkdirs()
        val file = stateFile(profile(status.profileId))
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(listOf(status.state.name, status.versionOutput.take(4096), status.error.orEmpty().take(4096), status.updatedAt.toString(), status.installedBytes.toString()).joinToString("\n"))
        check(temp.renameTo(file)) { "Não foi possível confirmar estado da toolchain" }
        transactionStore.cache(status)
        return status
    }
}

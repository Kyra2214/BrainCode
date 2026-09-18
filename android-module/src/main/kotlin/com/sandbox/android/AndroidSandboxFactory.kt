package com.sandbox.android

import android.content.Context
import android.os.Build
import com.sandbox.agent.Sandbox
import com.sandbox.resource.Ed25519RootfsSignatureVerifier
import com.sandbox.resource.SandboxResourceManager
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.FileRuntimeEventStore
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.PackagedRuntime
import com.sandbox.runtime.ProotProcessLauncher
import com.sandbox.runtime.ProotResourceLimits
import com.sandbox.runtime.SandboxRuntime
import com.sandbox.runtime.TarGzExtractor
import java.io.File
import java.util.zip.ZipFile

class AndroidSandboxFactory(private val context: Context) {
    private companion object {
        private const val EXTRACTOR_VERSION = "5"
        private const val ROOFTS06_COMMIT = "c004a74784a08295d52749b04cda634125b9a581"
        private const val SESSION_PREFS = "sandbox_runtime"
        private const val SESSION_ID = "session_id"
    }

    private val sandboxBaseDir = File(context.filesDir, "sandbox")
    private val downloadedArchives = listOf(
        File(sandboxBaseDir, "rootfs-base.tar.gz"),
        File(sandboxBaseDir, "rootfs-extra.tar.gz"),
        File(sandboxBaseDir, "rootfs-android.tar.gz")
    )
    private val modelDir = File(sandboxBaseDir, "models")
    private val extractedRootfsDir = File(sandboxBaseDir, "rootfs")
    private val extractionMarker = File(sandboxBaseDir, ".extractor-version")
    private val roofts06Marker = File(sandboxBaseDir, ".roofts-0.6-commit")
    private val prootTmpDir = File(context.cacheDir, "sandbox-tmp")

    private val rootfsVerifier by lazy {
        val trustedKeys = runCatching {
            context.assets.open("rootfs_trusted_keys.json").bufferedReader().use { reader ->
                val json = org.json.JSONObject(reader.readText())
                val keys = json.optJSONObject("keys") ?: org.json.JSONObject()
                keys.keys().asSequence().associateWith { java.util.Base64.getDecoder().decode(keys.getString(it)) }
            }
        }.getOrDefault(emptyMap())
        Ed25519RootfsSignatureVerifier(trustedKeys)
    }

    private fun ensureProotExecutable(): File {
        val extracted = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (extracted.exists()) {
            extracted.setExecutable(true, false)
            return extracted
        }
        val destination = File(sandboxBaseDir, "bin/libproot.so")
        if (!destination.exists() || destination.length() == 0L) {
            destination.parentFile?.mkdirs()
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            ZipFile(context.applicationInfo.sourceDir).use { apk ->
                listOf("libproot.so", "libtalloc.so", "libandroid-shmem.so").forEach { library ->
                    val entryName = "lib/$abi/$library"
                    val entry = apk.getEntry(entryName) ?: error("biblioteca nativa não encontrada no APK em $entryName")
                    val outputFile = File(destination.parentFile, library)
                    apk.getInputStream(entry).use { input -> outputFile.outputStream().use { output -> input.copyTo(output) } }
                }
            }
            destination.setExecutable(true, false)
        }
        return destination
    }

    fun resourceManager(): SandboxResourceManager {
        sandboxBaseDir.mkdirs()
        return SandboxResourceManager(downloadedArchives.first(), rootfsVerifier)
    }

    fun layerResourceManager(layer: Int): SandboxResourceManager {
        require(layer in downloadedArchives.indices) { "Camada RootFS inválida: $layer" }
        sandboxBaseDir.mkdirs()
        return SandboxResourceManager(downloadedArchives[layer], rootfsVerifier)
    }

    fun modelResourceManager(modelId: String): SandboxResourceManager {
        require(modelId.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "ID de modelo inválido" }
        modelDir.mkdirs()
        return SandboxResourceManager(File(modelDir, "$modelId.gguf"))
    }

    fun modelFile(modelId: String): File = File(modelDir, "$modelId.gguf")

    fun ensureLocalModelLinkedIntoRootfs(modelId: String): String? {
        val source = modelFile(modelId)
        if (!source.isFile || !extractedRootfsDir.isDirectory) return null
        val guestRelativePath = "home/sandbox/models/$modelId.gguf"
        val destination = File(extractedRootfsDir, guestRelativePath)
        if (!destination.exists() || destination.length() != source.length()) {
            destination.parentFile?.mkdirs()
            destination.delete()
            val linked = runCatching { java.nio.file.Files.createLink(destination.toPath(), source.toPath()) }.isSuccess
            if (!linked && runCatching { source.copyTo(destination, overwrite = true) }.isFailure) return null
        }
        return "/$guestRelativePath"
    }

    fun buildLocalModelChatCommand(modelPathInGuest: String, prompt: String, maxTokens: Int = 200): List<String> {
        val script = """
            BIN=${'$'}(command -v llama-cli 2>/dev/null || command -v llama-server 2>/dev/null || command -v llama 2>/dev/null || command -v main 2>/dev/null)
            if [ -z "${'$'}BIN" ]; then
              echo "LLAMA_CPP_NAO_ENCONTRADO: nenhum binario de inferencia foi encontrado no rootfs." >&2
              exit 127
            fi
            exec "${'$'}BIN" -m "$modelPathInGuest" -p "${'$'}1" -n $maxTokens --temp 0.7
        """.trimIndent()
        return listOf("/bin/bash", "-c", script, "chat", prompt)
    }

    fun isRootfsReady(): Boolean = rootfsExtractionValid()

    /** True when the incremental 0.6 payload has been installed over the layers. */
    fun isRoofts06Installed(): Boolean =
        File(extractedRootfsDir, "opt/roofts/0.6/skills").isDirectory &&
            roofts06Marker.readTextOrNull() == ROOFTS06_COMMIT

    private fun rootfsExtractionValid(): Boolean =
        extractedRootfsDir.exists() && !extractedRootfsDir.list().isNullOrEmpty() &&
            extractionMarker.readTextOrNull() == EXTRACTOR_VERSION && hasRequiredRootfsEntries()

    private fun deleteDownloadedArchives() = downloadedArchives.forEach { SandboxResourceManager(it).purge() }

    fun prepareRuntime(
        forceReExtract: Boolean = false,
        progressListener: ((completed: Long, total: Long, stage: String) -> Unit)? = null
    ): SandboxRuntime {
        if (forceReExtract && extractedRootfsDir.exists()) extractedRootfsDir.deleteRecursively()
        if (forceReExtract || !rootfsExtractionValid()) {
            require(downloadedArchives.all { it.exists() }) { "As três camadas RootFS ainda não foram baixadas. Prepare o sandbox novamente." }
            extractedRootfsDir.deleteRecursively()
            extractionMarker.delete()
            val totalArchiveBytes = downloadedArchives.sumOf { it.length() }.coerceAtLeast(1L)
            var completedArchiveBytes = 0L
            downloadedArchives.forEachIndexed { index, archive ->
                progressListener?.invoke(completedArchiveBytes, totalArchiveBytes, "Extraindo camada ${index + 1}/3")
                TarGzExtractor.extract(archive, extractedRootfsDir) { bytesRead ->
                    progressListener?.invoke((completedArchiveBytes + bytesRead).coerceAtMost(totalArchiveBytes), totalArchiveBytes, "Extraindo camada ${index + 1}/3")
                }
                completedArchiveBytes += archive.length()
            }
            validateExtractedRootfs()
            extractionMarker.writeText(EXTRACTOR_VERSION)
            deleteDownloadedArchives()
        }
        installRoofts06()
        progressListener?.invoke(1L, 1L, "Inicializando runtime")
        ensureResolvConf()
        val packagedRuntime = PackagedRuntime(context, extractedRootfsDir).also { it.prepare() }
        val runtime = SandboxRuntime(
            prootExecutable = packagedRuntime.preparedRunnerPath,
            rootfsDir = extractedRootfsDir,
            tmpDir = packagedRuntime.prootTmpPath,
            nativeLibraryDir = packagedRuntime.nativeLibraryPath,
            prootLoader = packagedRuntime.packagedLoaderPath,
            resourceLimits = ProotResourceLimits.DEFAULT
        )
        ensureVenv(runtime)
        return runtime
    }

    /** Copies the complete, not-yet-integrated Roofts 0.6 payload into the guest. */
    private fun installRoofts06() {
        if (isRoofts06Installed()) return
        val sourceRoot = "roofts/0.6"
        val destinationRoot = File(extractedRootfsDir, "opt/roofts/0.6")
        destinationRoot.deleteRecursively()
        copyAssetTree(sourceRoot, destinationRoot)
        check(isRoofts06InstalledPayload(destinationRoot)) { "Roofts 0.6 incompleto após a cópia" }
        roofts06Marker.writeText(ROOFTS06_COMMIT)
    }

    private fun isRoofts06InstalledPayload(root: File): Boolean =
        File(root, "skills").isDirectory && File(root, "agents").isDirectory &&
            File(root, "references").isDirectory && File(root, "docs").isDirectory &&
            File(root, "LICENSE").isFile

    private fun copyAssetTree(assetPath: String, destination: File) {
        destination.mkdirs()
        for (name in context.assets.list(assetPath).orEmpty()) {
            val childAsset = "$assetPath/$name"
            val childDestination = File(destination, name)
            if (context.assets.list(childAsset).orEmpty().isNotEmpty()) {
                copyAssetTree(childAsset, childDestination)
            } else {
                context.assets.open(childAsset).use { input ->
                    childDestination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    fun prepareManagedRuntime(
        sessionId: String = persistentSessionId(),
        progressListener: ((completed: Long, total: Long, stage: String) -> Unit)? = null
    ): ManagedSandboxRuntime {
        prepareRuntime(progressListener = progressListener)
        val packagedRuntime = PackagedRuntime(context, extractedRootfsDir).also { it.prepare() }
        val launcher = ProotProcessLauncher(
            prootExecutable = packagedRuntime.preparedRunnerPath,
            rootfsDir = extractedRootfsDir,
            tmpDir = packagedRuntime.prootTmpPath,
            nativeLibraryDir = packagedRuntime.nativeLibraryPath,
            prootLoader = packagedRuntime.packagedLoaderPath,
            resourceLimits = ProotResourceLimits.DEFAULT
        )
        return ManagedSandboxRuntime(
            launcher,
            FileExecutionLogRepository(File(sandboxBaseDir, "execution-logs")),
            sessionId,
            runtimeEventRepository = FileRuntimeEventStore(File(sandboxBaseDir, "runtime-events"))
        )
    }

    fun createSandbox(sessionId: String = persistentSessionId()): Sandbox =
        Sandbox(runtime = prepareManagedRuntime(sessionId), rootfsDir = extractedRootfsDir)

    fun persistentSessionId(): String {
        val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        prefs.getString(SESSION_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = FileExecutionLogRepository.newId()
        prefs.edit().putString(SESSION_ID, created).apply()
        return created
    }

    fun clearPersistentSession() = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit().remove(SESSION_ID).apply()

    fun inspectExtractedRootfs(): String {
        if (!extractedRootfsDir.exists()) return "Rootfs ainda não foi extraído (pasta ${extractedRootfsDir.path} não existe)."
        val report = StringBuilder()
        report.appendLine("Pasta: ${extractedRootfsDir.path}")
        report.appendLine("Marcador de versão: ${extractionMarker.readTextOrNull() ?: "(ausente)"}")
        val topLevel = extractedRootfsDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        report.appendLine("Entradas na raiz (${topLevel.size}): ${topLevel.joinToString(", ") { it.name }}")
        var totalFiles = 0; var totalDirs = 0; var totalSymlinks = 0; var totalBytes = 0L; var walkErrors = 0
        fun walk(dir: File) {
            for (child in runCatching { dir.listFiles() }.getOrNull().orEmpty()) {
                try {
                    when {
                        java.nio.file.Files.isSymbolicLink(child.toPath()) -> totalSymlinks++
                        child.isDirectory -> { totalDirs++; walk(child) }
                        child.isFile -> { totalFiles++; totalBytes += child.length() }
                    }
                } catch (_: Exception) { walkErrors++ }
            }
        }
        walk(extractedRootfsDir)
        report.appendLine("Total: $totalFiles arquivos, $totalDirs pastas, $totalSymlinks symlinks, ${totalBytes / (1024 * 1024)} MB" + if (walkErrors > 0) " ($walkErrors entradas com erro ao inspecionar)" else "")
        report.appendLine()
        report.appendLine("Caminhos essenciais:")
        listOf("bin", "usr/bin/bash", "usr/bin/dash", "bin/bash", "home", "home/sandbox", "etc/resolv.conf").forEach { relativePath ->
            val target = File(extractedRootfsDir, relativePath)
            val exists = java.nio.file.Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            val kind = when {
                !exists -> "AUSENTE"
                java.nio.file.Files.isSymbolicLink(target.toPath()) -> "symlink -> ${runCatching { java.nio.file.Files.readSymbolicLink(target.toPath()) }.getOrNull()}"
                target.isDirectory -> "pasta"
                else -> "arquivo (${target.length()} bytes)"
            }
            report.appendLine("  $relativePath: $kind")
        }
        report.appendLine()
        report.appendLine("Cadeia de interpretador:")
        listOf("bin/bash", "lib/ld-linux-aarch64.so.1").forEach { path -> report.appendLine("  $path: ${describeInterpreterChain(File(extractedRootfsDir, path))}") }
        report.appendLine()
        report.appendLine("Teste funcional do Bash via Proot:")
        report.appendLine("  ${prootBashProbe()}")
        report.appendLine()
        report.appendLine("Teste host direto (informativo; não é teste do RootFS):")
        report.appendLine("  ${directHostExecProbe()}")
        return report.toString()
    }

    private fun prootBashProbe(): String {
        if (!hasRequiredRootfsEntries()) return "NÃO EXECUTADO: RootFS não passou na validação estrutural."
        return runCatching {
            val packagedRuntime = PackagedRuntime(context, extractedRootfsDir).also { it.prepare() }
            val launcher = ProotProcessLauncher(
                prootExecutable = packagedRuntime.preparedRunnerPath,
                rootfsDir = extractedRootfsDir,
                tmpDir = packagedRuntime.prootTmpPath,
                nativeLibraryDir = packagedRuntime.nativeLibraryPath,
                prootLoader = packagedRuntime.packagedLoaderPath,
                resourceLimits = ProotResourceLimits.DEFAULT
            )
            val process = launcher.launch(listOf("/bin/bash", "--version"), "/home/sandbox")
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                "FALHOU: timeout de 5s"
            } else if (process.exitValue() == 0) {
                "OK, exit code 0: ${output.take(160)}"
            } else {
                "FALHOU, exit code ${process.exitValue()}: ${output.take(160)}"
            }
        }.getOrElse { e -> "FALHOU: ${e.javaClass.simpleName}: ${e.message.orEmpty()}" }
    }

    private fun directHostExecProbe(): String {
        val bash = File(extractedRootfsDir, "usr/bin/bash")
        if (!bash.isFile) return "usr/bin/bash não é um arquivo regular, não dá pra testar."
        return try {
            val process = ProcessBuilder(bash.absolutePath, "--version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); "travou (timeout de 5s)" }
            else "sucesso, exit code ${process.exitValue()}: ${output.take(120)}"
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            val interpretation = when {
                message.contains("error=13") || message.contains("Permission denied") -> " → EACCES: restrição de exec do Android."
                message.contains("error=2") || message.contains("No such file") -> " → ENOENT esperado: o host Android não resolve o loader dentro do RootFS."
                else -> " → erro host sem padrão reconhecido."
            }
            "FALHOU: ${e.javaClass.simpleName}: $message$interpretation"
        }
    }

    private fun validateExtractedRootfs() {
        val problems = mutableListOf<String>()
        listOf("bin/bash", "lib/ld-linux-aarch64.so.1").forEach { relativePath ->
            val description = describeInterpreterChain(File(extractedRootfsDir, relativePath))
            if (!description.startsWith("OK")) problems += "${relativeLabel(File(extractedRootfsDir, relativePath))}: $description"
        }
        check(problems.isEmpty()) {
            "Rootfs extraído está incompleto ou corrompido:\n" + problems.joinToString("\n") { "  - $it" } +
                "\nToque em Resetar sandbox e baixe o rootfs novamente."
        }
    }

    private fun hasRequiredRootfsEntries(): Boolean =
        File(extractedRootfsDir, "home/sandbox").isDirectory &&
            describeInterpreterChain(File(extractedRootfsDir, "bin/bash")).startsWith("OK") &&
            describeInterpreterChain(File(extractedRootfsDir, "lib/ld-linux-aarch64.so.1")).startsWith("OK")

    private fun describeInterpreterChain(start: File, maxHops: Int = 10): String {
        var current = start.toPath().toAbsolutePath().normalize()
        val chain = mutableListOf(current)
        repeat(maxHops) {
            if (!java.nio.file.Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "QUEBRADO em ${relativeLabel(current.toFile())} (não existe) — cadeia: " + chain.joinToString(" -> ") { relativeLabel(it.toFile()) }
            if (!java.nio.file.Files.isSymbolicLink(current)) {
                return if (current.toFile().isFile) "OK -> ${relativeLabel(current.toFile())} (${current.toFile().length()} bytes)" + if (chain.size > 1) " — cadeia: ${chain.joinToString(" -> ") { relativeLabel(it.toFile()) }}" else ""
                else "QUEBRADO: alvo final ${relativeLabel(current.toFile())} não é um arquivo regular"
            }
            val link = java.nio.file.Files.readSymbolicLink(current)
            current = (if (link.isAbsolute) extractedRootfsDir.toPath().resolve(link.toString().removePrefix("/")) else current.parent.resolve(link)).normalize()
            chain.add(current)
        }
        return "QUEBRADO: cadeia de symlink excede $maxHops saltos (possível loop) — " + chain.joinToString(" -> ") { relativeLabel(it.toFile()) }
    }

    private fun relativeLabel(file: File): String = runCatching { file.relativeTo(extractedRootfsDir).path }.getOrDefault(file.path)
    private fun File.readTextOrNull(): String? = if (isFile) runCatching { readText() }.getOrNull() else null

    private fun ensureResolvConf() {
        val resolvConf = File(extractedRootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    private fun ensureVenv(runtime: SandboxRuntime) {
        val venvMarker = File(extractedRootfsDir, "home/sandbox/venv/bin/python3")
        if (venvMarker.exists()) return
        runtime.execute(
            command = listOf("/bin/bash", "-c", "python3 -m venv /home/sandbox/venv --system-site-packages 2>&1 || echo 'venv indisponível, seguindo sem ela'"),
            timeoutSeconds = 120
        )
    }

    fun purgeAll() {
        downloadedArchives.forEach { SandboxResourceManager(it).purge() }
        if (extractedRootfsDir.exists()) extractedRootfsDir.deleteRecursively()
        if (extractionMarker.exists()) extractionMarker.delete()
        if (prootTmpDir.exists()) prootTmpDir.deleteRecursively()
    }
}

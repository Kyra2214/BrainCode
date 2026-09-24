package com.sandbox.sandbox

import com.sandbox.runtime.ExecutionLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Tipo de componente disponível no catálogo do Sandbox. */
enum class ComponentKind { PLUGIN, TOOL }

enum class InstallationState { NOT_INSTALLED, INSTALLING, INSTALLED, FAILED, REMOVING }

data class SandboxComponent(
    val id: String,
    val name: String,
    val description: String,
    val kind: ComponentKind,
    val version: String? = null,
    val packages: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val validationCommand: List<String> = listOf("true"),
    /** Comando de instalação alternativo, para componentes que não vêm de pacotes apt (ex: Ollama via script). */
    val installCommand: List<String>? = null,
    /** Comando de remoção alternativo, usado junto com [installCommand]. */
    val removeCommand: List<String>? = null
)

/** Estado persistido de uma instalação ou remoção. */
data class InstalledComponent(
    val componentId: String,
    val version: String?,
    val installedAt: Long,
    val dependencies: List<String>,
    val state: InstallationState,
    val error: String? = null,
    val updatedAt: Long = installedAt,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val installDurationMs: Long = 0L,
    val installedByUser: String = "system",
    val packageVersion: String? = null,
    val validationStatus: Boolean = true,
    val notes: String? = null
)

data class PluginSnapshot(
    val version: Long,
    val createdAt: Long,
    val reason: String,
    val components: List<InstalledComponent>
)

data class PluginOperationRecord(
    val timestamp: Long,
    val operation: String,
    val componentId: String?,
    val snapshotVersion: Long?,
    val success: Boolean,
    val message: String? = null
)

/** Persistência local, versionada e atômica do estado anterior às operações. */
class PluginSnapshotStore(private val file: File, private val historyFile: File) {
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
        historyFile.parentFile?.mkdirs()
    }

    @Synchronized
    fun create(reason: String, components: List<InstalledComponent>): PluginSnapshot {
        val snapshots = snapshots().toMutableList()
        val snapshot = PluginSnapshot(
            version = (snapshots.maxOfOrNull { it.version } ?: 0L) + 1L,
            createdAt = System.currentTimeMillis(),
            reason = reason,
            components = components.toList()
        )
        snapshots += snapshot
        writeSnapshots(snapshots.takeLast(20))
        return snapshot
    }

    @Synchronized
    fun snapshots(): List<PluginSnapshot> {
        if (!file.isFile || file.readText().isBlank()) return emptyList()
        val root = runCatching { MiniJson.parse(file.readText()) as? Map<*, *> }.getOrNull() ?: return emptyList()
        return (root["snapshots"] as? List<*>)?.mapNotNull { parseSnapshot(it) }.orEmpty()
    }

    @Synchronized
    fun get(version: Long): PluginSnapshot? = snapshots().firstOrNull { it.version == version }

    @Synchronized
    fun record(record: PluginOperationRecord) {
        val line = "{" +
            "\"timestamp\": ${record.timestamp}, " +
            "\"operation\": \"${MiniJson.escape(record.operation)}\", " +
            "\"componentId\": ${record.componentId?.let { "\"${MiniJson.escape(it)}\"" } ?: "null"}, " +
            "\"snapshotVersion\": ${record.snapshotVersion ?: "null"}, " +
            "\"success\": ${record.success}, " +
            "\"message\": ${record.message?.let { "\"${MiniJson.escape(it)}\"" } ?: "null"}" +
            "}\n"
        historyFile.appendText(line)
    }

    @Synchronized
    fun history(limit: Int = 50): List<PluginOperationRecord> {
        if (!historyFile.isFile) return emptyList()
        return historyFile.readLines().takeLast(limit).mapNotNull { line ->
            runCatching {
                val value = MiniJson.parse(line) as Map<*, *>
                PluginOperationRecord(
                    timestamp = (value["timestamp"] as Number).toLong(),
                    operation = value["operation"] as String,
                    componentId = value["componentId"] as String?,
                    snapshotVersion = (value["snapshotVersion"] as Number?)?.toLong(),
                    success = value["success"] as Boolean,
                    message = value["message"] as String?
                )
            }.getOrNull()
        }
    }

    private fun parseSnapshot(raw: Any?): PluginSnapshot? = runCatching {
        val value = raw as Map<*, *>
        PluginSnapshot(
            version = (value["version"] as Number).toLong(),
            createdAt = (value["createdAt"] as Number).toLong(),
            reason = value["reason"] as String,
            components = (value["components"] as? List<*>)?.mapNotNull { ComponentJson.parseOne(it) }.orEmpty()
        )
    }.getOrNull()

    private fun writeSnapshots(snapshots: List<PluginSnapshot>) {
        val content = "{\n  \"snapshots\": [\n" +
            snapshots.joinToString(",\n") { snapshot ->
                "    {\"version\": ${snapshot.version}, \"createdAt\": ${snapshot.createdAt}, " +
                    "\"reason\": \"${MiniJson.escape(snapshot.reason)}\", \"components\": [" +
                    snapshot.components.joinToString(", ") { ComponentJson.serialize(it) } + "]}"
            } + "\n  ]\n}\n"
        val temporary = File(file.parentFile ?: file.absoluteFile.parentFile, ".${file.name}.tmp")
        temporary.writeText(content)
        runCatching {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { temporary.delete(); error("Não foi possível persistir snapshots: ${it.message}") }
    }
}

interface SandboxCommandExecutor {
    fun execute(command: List<String>, timeoutSeconds: Long = 120, workingDir: String = "/home/sandbox"): ExecutionLog
}

interface ComponentRepository {
    fun get(id: String): InstalledComponent?
    fun all(): List<InstalledComponent>
    fun save(component: InstalledComponent)
    fun remove(id: String)
}

/**
 * Repositório TSV legado. Ele continua sendo aceito apenas como fonte de
 * migração para o repositório JSON, preservando instalações antigas.
 */
class FileComponentRepository(private val file: File) : ComponentRepository {
    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    override fun get(id: String): InstalledComponent? = all().firstOrNull { it.componentId == id }

    @Synchronized
    override fun all(): List<InstalledComponent> {
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = line.split("\t", limit = 6)
            if (parts.size < 6) return@mapNotNull null
            runCatching {
                InstalledComponent(
                    componentId = parts[0],
                    version = parts[1].ifEmpty { null },
                    installedAt = parts[2].toLong(),
                    dependencies = parts[3].split(",").filter(String::isNotEmpty),
                    state = InstallationState.valueOf(parts[4]),
                    error = parts[5].takeUnless { it == "-" || it.isEmpty() }
                )
            }.getOrNull()
        }
    }

    @Synchronized
    override fun save(component: InstalledComponent) {
        val records = all().filterNot { it.componentId == component.componentId } + component
        saveAll(records)
    }

    @Synchronized
    override fun remove(id: String) {
        saveAll(all().filterNot { it.componentId == id })
    }

    private fun saveAll(records: List<InstalledComponent>) {
        val content = records.joinToString("\n") { component ->
            listOf(
                component.componentId,
                component.version.orEmpty(),
                component.installedAt.toString(),
                component.dependencies.joinToString(","),
                component.state.name,
                component.error.ifNullOrBlank()
            ).joinToString("\t")
        } + if (records.isEmpty()) "" else "\n"
        file.writeText(content)
    }

    private fun String?.ifNullOrBlank(): String = if (isNullOrBlank()) "-" else this
}

/** Parser JSON mínimo para o schema fixo do estado de componentes. */
internal object MiniJson {
    fun parse(text: String): Any? {
        val position = intArrayOf(0)
        skipWhitespace(text, position)
        if (position[0] >= text.length) return null
        val value = parseValue(text, position)
        skipWhitespace(text, position)
        require(position[0] == text.length) { "JSON malformado: conteúdo após o documento" }
        return value
    }

    private fun parseValue(text: String, position: IntArray): Any? {
        skipWhitespace(text, position)
        require(position[0] < text.length) { "JSON malformado: valor ausente" }
        return when (text[position[0]]) {
            '{' -> parseObject(text, position)
            '[' -> parseArray(text, position)
            '"' -> parseString(text, position)
            't' -> {
                expect(text, position, "true")
                true
            }
            'f' -> {
                expect(text, position, "false")
                false
            }
            'n' -> {
                expect(text, position, "null")
                null
            }
            else -> parseNumber(text, position)
        }
    }

    private fun skipWhitespace(text: String, position: IntArray) {
        while (position[0] < text.length && text[position[0]].isWhitespace()) position[0]++
    }

    private fun expect(text: String, position: IntArray, token: String) {
        require(text.regionMatches(position[0], token, 0, token.length)) {
            "JSON malformado: esperado '$token' na posição ${position[0]}"
        }
        position[0] += token.length
    }

    private fun parseObject(text: String, position: IntArray): LinkedHashMap<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        position[0]++
        skipWhitespace(text, position)
        if (position[0] < text.length && text[position[0]] == '}') {
            position[0]++
            return result
        }
        while (true) {
            skipWhitespace(text, position)
            val key = parseString(text, position)
            skipWhitespace(text, position)
            require(position[0] < text.length && text[position[0]] == ':') {
                "JSON malformado: esperado ':' na posição ${position[0]}"
            }
            position[0]++
            result[key] = parseValue(text, position)
            skipWhitespace(text, position)
            require(position[0] < text.length) { "JSON malformado: objeto incompleto" }
            when (text[position[0]]) {
                ',' -> position[0]++
                '}' -> {
                    position[0]++
                    return result
                }
                else -> error("JSON malformado: token inesperado na posição ${position[0]}")
            }
        }
    }

    private fun parseArray(text: String, position: IntArray): List<Any?> {
        val result = mutableListOf<Any?>()
        position[0]++
        skipWhitespace(text, position)
        if (position[0] < text.length && text[position[0]] == ']') {
            position[0]++
            return result
        }
        while (true) {
            result += parseValue(text, position)
            skipWhitespace(text, position)
            require(position[0] < text.length) { "JSON malformado: array incompleto" }
            when (text[position[0]]) {
                ',' -> position[0]++
                ']' -> {
                    position[0]++
                    return result
                }
                else -> error("JSON malformado: token inesperado na posição ${position[0]}")
            }
        }
    }

    private fun parseString(text: String, position: IntArray): String {
        require(position[0] < text.length && text[position[0]] == '"') {
            "JSON malformado: string esperada na posição ${position[0]}"
        }
        position[0]++
        val result = StringBuilder()
        while (true) {
            require(position[0] < text.length) { "JSON malformado: string sem fechamento" }
            val char = text[position[0]]
            if (char == '"') {
                position[0]++
                return result.toString()
            }
            if (char == '\\') {
                position[0]++
                require(position[0] < text.length) { "JSON malformado: escape incompleto" }
                when (val escaped = text[position[0]]) {
                    '"' -> result.append('"')
                    '\\' -> result.append('\\')
                    '/' -> result.append('/')
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        require(position[0] + 4 < text.length) { "JSON malformado: escape Unicode incompleto" }
                        result.append(text.substring(position[0] + 1, position[0] + 5).toInt(16).toChar())
                        position[0] += 4
                    }
                    else -> error("JSON malformado: escape inválido '$escaped'")
                }
            } else {
                require(char.code >= 0x20) { "JSON malformado: caractere de controle em string" }
                result.append(char)
            }
            position[0]++
        }
    }

    private fun parseNumber(text: String, position: IntArray): Double {
        val start = position[0]
        while (position[0] < text.length && text[position[0]] in "0123456789+-.eE") position[0]++
        require(start < position[0]) { "JSON malformado: número ausente" }
        return text.substring(start, position[0]).toDoubleOrNull()
            ?: error("JSON malformado: número inválido")
    }

    fun escape(value: String): String {
        val result = StringBuilder()
        value.forEach { char ->
            when (char) {
                '"' -> result.append("\\\"")
                '\\' -> result.append("\\\\")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                '\b' -> result.append("\\b")
                '\u000C' -> result.append("\\f")
                else -> if (char.code < 0x20) result.append("\\u%04x".format(char.code)) else result.append(char)
            }
        }
        return result.toString()
    }
}

internal object ComponentJson {
    fun serializeAll(records: List<InstalledComponent>): String {
        if (records.isEmpty()) return "{\n  \"components\": []\n}\n"
        val items = records.joinToString(",\n") { "    ${serialize(it)}" }
        return "{\n  \"components\": [\n$items\n  ]\n}\n"
    }

    internal fun serialize(component: InstalledComponent): String {
        fun string(value: String?): String = value?.let { "\"${MiniJson.escape(it)}\"" } ?: "null"
        val dependencies = component.dependencies.joinToString(", ") { "\"${MiniJson.escape(it)}\"" }
        return "{" +
            "\"componentId\": ${string(component.componentId)}, " +
            "\"version\": ${string(component.version)}, " +
            "\"installedAt\": ${component.installedAt}, " +
            "\"updatedAt\": ${component.updatedAt}, " +
            "\"dependencies\": [$dependencies], " +
            "\"state\": ${string(component.state.name)}, " +
            "\"error\": ${string(component.error)}, " +
            "\"downloadedBytes\": ${component.downloadedBytes}, " +
            "\"totalBytes\": ${component.totalBytes}, " +
            "\"installDurationMs\": ${component.installDurationMs}, " +
            "\"installedByUser\": ${string(component.installedByUser)}, " +
            "\"packageVersion\": ${string(component.packageVersion)}, " +
            "\"validationStatus\": ${component.validationStatus}, " +
            "\"notes\": ${string(component.notes)}" +
            "}"
    }

    fun parseAll(text: String): List<InstalledComponent> {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return emptyList()
        val list = root["components"] as? List<*> ?: return emptyList()
        return list.mapNotNull(::parseOne)
    }

    internal fun parseOne(item: Any?): InstalledComponent? {
        val objectValue = item as? Map<*, *> ?: return null
        return runCatching {
            val installedAt = (objectValue["installedAt"] as Number).toLong()
            InstalledComponent(
                componentId = objectValue["componentId"] as String,
                version = objectValue["version"] as String?,
                installedAt = installedAt,
                dependencies = (objectValue["dependencies"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                state = InstallationState.valueOf(objectValue["state"] as String),
                error = objectValue["error"] as String?,
                updatedAt = (objectValue["updatedAt"] as? Number)?.toLong() ?: installedAt,
                downloadedBytes = (objectValue["downloadedBytes"] as? Number)?.toLong() ?: 0L,
                totalBytes = (objectValue["totalBytes"] as? Number)?.toLong() ?: 0L,
                installDurationMs = (objectValue["installDurationMs"] as? Number)?.toLong() ?: 0L,
                installedByUser = objectValue["installedByUser"] as? String ?: "system",
                packageVersion = objectValue["packageVersion"] as? String,
                validationStatus = objectValue["validationStatus"] as? Boolean ?: true,
                notes = objectValue["notes"] as? String
            )
        }.getOrNull()
    }
}

/**
 * Persistência principal do estado dos componentes.
 * Escritas usam arquivo temporário + rename para não deixar JSON truncado se o
 * processo Android for encerrado durante a instalação.
 */
class JsonComponentRepository(
    private val file: File,
    legacyTsvFile: File? = null
) : ComponentRepository {
    init {
        file.parentFile?.mkdirs()
        if (!file.isFile && legacyTsvFile?.isFile == true) {
            val migrated = FileComponentRepository(legacyTsvFile).all()
            if (migrated.isNotEmpty()) saveAll(migrated)
        }
        recoverInterruptedOperations()
    }

    @Synchronized
    override fun get(id: String): InstalledComponent? = all().firstOrNull { it.componentId == id }

    @Synchronized
    override fun all(): List<InstalledComponent> {
        if (!file.isFile || file.readText().isBlank()) return emptyList()
        return runCatching { ComponentJson.parseAll(file.readText()) }.getOrElse { emptyList() }
    }

    @Synchronized
    override fun save(component: InstalledComponent) {
        saveAll(all().filterNot { it.componentId == component.componentId } + component)
    }

    @Synchronized
    override fun remove(id: String) {
        saveAll(all().filterNot { it.componentId == id })
    }

    /**
     * Uma morte do processo Android pode ocorrer depois do estado INSTALLING
     * ou REMOVING ser persistido e antes do apt terminar. Esses estados nunca
     * podem ficar indefinidamente na UI; na próxima abertura viram uma falha
     * explícita que o usuário pode tentar novamente.
     */
    private fun recoverInterruptedOperations() {
        val records = all()
        val recovered = records.map { component ->
            if (component.state == InstallationState.INSTALLING || component.state == InstallationState.REMOVING) {
                component.copy(
                    state = InstallationState.FAILED,
                    updatedAt = System.currentTimeMillis(),
                    validationStatus = false,
                    error = "Operação interrompida quando o Sandbox foi encerrado.",
                    notes = "Verifique o estado dos pacotes e tente novamente."
                )
            } else component
        }
        if (recovered != records) saveAll(recovered)
    }

    private fun saveAll(records: List<InstalledComponent>) {
        val content = ComponentJson.serializeAll(records)
        val temporary = File(file.parentFile ?: file.absoluteFile.parentFile, ".${file.name}.tmp")
        temporary.writeText(content)
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.recoverCatching {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { error ->
            temporary.delete()
            error("Não foi possível persistir o estado de componentes: ${error.message}")
        }
    }
}

object BuiltInCatalog {
    val plugins: List<SandboxComponent> = listOf(
        SandboxComponent(
            id = "ollama",
            name = "Ollama",
            description = "Executa modelos de linguagem localmente (LLMs)",
            kind = ComponentKind.PLUGIN,
            packages = emptyList(),
            installCommand = listOf(
                "bash", "-c",
                "set -o pipefail; curl -fsSL https://ollama.com/install.sh | sh 2>&1 | tail -n 120"
            ),
            removeCommand = listOf(
                "bash", "-c",
                "rm -f \$(command -v ollama); rm -rf /usr/share/ollama /usr/local/lib/ollama"
            ),
            validationCommand = listOf("bash", "-c", "command -v ollama && ollama --version")
        ),
        SandboxComponent(
            id = "android-ndk",
            name = "Android NDK",
            description = "Toolchain nativa C/C++ para builds Android",
            kind = ComponentKind.PLUGIN,
            version = "r27c",
            installCommand = listOf("bash", "-c", "set -o pipefail -e; mkdir -p /opt; (curl -fsSL --retry 3 -o /tmp/android-ndk.zip https://dl.google.com/android/repository/android-ndk-r27c-linux.zip; rm -rf /opt/android-ndk-r27c; unzip -q /tmp/android-ndk.zip -d /opt; rm -f /tmp/android-ndk.zip; ln -sfn /opt/android-ndk-r27c /opt/android-ndk) 2>&1 | tail -n 120"),
            removeCommand = listOf("bash", "-c", "rm -rf /opt/android-ndk /opt/android-ndk-r27c"),
            validationCommand = listOf("bash", "-c", "test -x /opt/android-ndk/ndk-build && /opt/android-ndk/ndk-build --version")
        ),
        SandboxComponent(
            id = "trivy",
            name = "Trivy",
            description = "Scanner de vulnerabilidades para código, imagens e dependências",
            kind = ComponentKind.PLUGIN,
            version = "0.58.2",
            installCommand = listOf("bash", "-c", "set -o pipefail -e; (curl -fsSL --retry 3 https://github.com/aquasecurity/trivy/releases/download/v0.58.2/trivy_0.58.2_Linux-ARM64.tar.gz | tar -xz -C /tmp; install -m 0755 /tmp/trivy /usr/local/bin/trivy; rm -f /tmp/trivy) 2>&1 | tail -n 120"),
            removeCommand = listOf("bash", "-c", "rm -f /usr/local/bin/trivy"),
            validationCommand = listOf("trivy", "--version")
        ),
        SandboxComponent(
            id = "sops",
            name = "SOPS",
            description = "Criptografia e gestão segura de arquivos de configuração",
            kind = ComponentKind.PLUGIN,
            version = "3.9.4",
            installCommand = listOf("bash", "-c", "set -e; curl -fL --retry 3 -o /usr/local/bin/sops https://github.com/getsops/sops/releases/download/v3.9.4/sops-v3.9.4.linux.arm64; chmod 0755 /usr/local/bin/sops"),
            removeCommand = listOf("bash", "-c", "rm -f /usr/local/bin/sops"),
            validationCommand = listOf("sops", "--version")
        ),
        SandboxComponent(
            id = "grpcurl",
            name = "grpcurl",
            description = "Cliente de inspeção e teste de APIs gRPC",
            kind = ComponentKind.PLUGIN,
            version = "1.9.2",
            installCommand = listOf("bash", "-c", "set -e; tmp=${'$'}(mktemp -d); curl -fL --retry 3 https://github.com/fullstorydev/grpcurl/releases/download/v1.9.2/grpcurl_1.9.2_linux_arm64.tar.gz | tar -xz -C ${'$'}tmp; install -m 0755 ${'$'}tmp/grpcurl /usr/local/bin/grpcurl; rm -rf ${'$'}tmp"),
            removeCommand = listOf("bash", "-c", "rm -f /usr/local/bin/grpcurl"),
            validationCommand = listOf("grpcurl", "-version")
        ),
        SandboxComponent(
            id = "websocat",
            name = "websocat",
            description = "Cliente WebSocket para testes e integrações em tempo real",
            kind = ComponentKind.PLUGIN,
            version = "1.13.0",
            installCommand = listOf("bash", "-c", "set -e; curl -fL --retry 3 -o /usr/local/bin/websocat https://github.com/vi/websocat/releases/download/v1.13.0/websocat.aarch64-unknown-linux-musl; chmod 0755 /usr/local/bin/websocat"),
            removeCommand = listOf("bash", "-c", "rm -f /usr/local/bin/websocat"),
            validationCommand = listOf("websocat", "--version")
        )
    )

    /** Tudo nesta lista já vem do RootFS base ou do Agent Extra. */
    val tools: List<SandboxComponent> = emptyList()

    val all: List<SandboxComponent> get() = plugins + tools

}

/**
 * Gerenciador das semanas 2 e 3: catálogo, dependências, instalação segura,
 * validação pós-instalação, remoção protegida e persistência de estado.
 */
class PluginManager(
    private val executor: SandboxCommandExecutor,
    private val repository: ComponentRepository,
    private val catalog: List<SandboxComponent> = BuiltInCatalog.all,
    private val catalogProvider: (() -> List<SandboxComponent>)? = null,
    private val snapshotStore: PluginSnapshotStore? = null
) {
    private val lock = Any()

    private fun currentCatalog(): List<SandboxComponent> = catalogProvider?.invoke() ?: catalog

    fun components(): List<SandboxComponent> = currentCatalog()

    fun status(id: String): InstalledComponent? = synchronized(lock) { repository.get(id) }

    fun installed(): List<InstalledComponent> = synchronized(lock) { repository.all() }

    fun install(id: String): InstalledComponent = synchronized(lock) {
        val snapshot = snapshotStore?.create("antes da instalação de $id", repository.all())
        return@synchronized try {
            installInternal(id, linkedSetOf()).also { result ->
                snapshotStore?.record(PluginOperationRecord(System.currentTimeMillis(), "install", id, snapshot?.version, result.state == InstallationState.INSTALLED, result.error))
            }
        } catch (error: Exception) {
            snapshotStore?.record(PluginOperationRecord(System.currentTimeMillis(), "install", id, snapshot?.version, false, error.message))
            throw error
        }
    }

    fun snapshots(): List<PluginSnapshot> = synchronized(lock) { snapshotStore?.snapshots().orEmpty() }
    fun history(limit: Int = 50): List<PluginOperationRecord> = synchronized(lock) { snapshotStore?.history(limit).orEmpty() }

    /** Restaura apenas o estado local persistido; não finge desfazer pacotes já alterados no RootFS. */
    fun rollback(version: Long): PluginSnapshot = synchronized(lock) {
        val store = snapshotStore ?: error("Snapshots locais não estão configurados")
        val snapshot = store.get(version) ?: error("Snapshot local v$version não encontrado")
        repository.all().map { it.componentId }.forEach { repository.remove(it) }
        snapshot.components.forEach(repository::save)
        store.record(PluginOperationRecord(System.currentTimeMillis(), "rollback", null, version, true, "Estado local restaurado"))
        snapshot
    }

    private fun installInternal(id: String, visiting: LinkedHashSet<String>): InstalledComponent {
        val component = currentCatalog().firstOrNull { it.id == id } ?: error("Componente desconhecido: $id")
        val existing = repository.get(id)
        if (existing?.state == InstallationState.INSTALLED && validate(component)) return existing

        val now = Instant.now().toEpochMilli()
        if (!visiting.add(id)) {
            return persistFailure(
                component = component,
                installedAt = existing?.installedAt ?: now,
                startedAtMs = now,
                error = "Dependência cíclica detectada: ${(visiting + id).joinToString(" -> ")}"
            )
        }

        val startedAtMs = System.currentTimeMillis()
        repository.save(
            InstalledComponent(
                componentId = id,
                version = component.version,
                installedAt = existing?.installedAt ?: now,
                dependencies = component.dependencies,
                state = InstallationState.INSTALLING,
                updatedAt = now,
                installedByUser = "user",
                validationStatus = false
            )
        )

        return try {
            val dependencyFailure = component.dependencies
                .map { dependency -> installInternal(dependency, visiting) }
                .firstOrNull { it.state != InstallationState.INSTALLED }
            if (dependencyFailure != null) {
                persistFailure(
                    component,
                    existing?.installedAt ?: now,
                    startedAtMs,
                    "Dependência ${dependencyFailure.componentId} não foi instalada: ${dependencyFailure.error ?: dependencyFailure.state.name}"
                )
            } else if (validate(component)) {
                persistSuccess(component, startedAtMs)
            } else if (component.installCommand != null) {
                val result = executor.execute(command = component.installCommand, timeoutSeconds = 600)
                val validated = result.succeeded && validate(component)
                if (validated) {
                    persistSuccess(component, startedAtMs)
                } else {
                    persistFailure(
                        component,
                        existing?.installedAt ?: now,
                        startedAtMs,
                        result.stderr.ifBlank { "Validação pós-instalação falhou" }
                    )
                }
            } else if (component.packages.isEmpty()) {
                persistFailure(component, existing?.installedAt ?: now, startedAtMs, "Nenhum pacote foi definido para o componente")
            } else {
                val result = executor.execute(
                    command = listOf("bash", "-c", AptScripts.install(component.packages)),
                    timeoutSeconds = 600
                )
                val validated = result.succeeded && validate(component)
                if (validated) {
                    persistSuccess(component, startedAtMs)
                } else {
                    persistFailure(
                        component,
                        existing?.installedAt ?: now,
                        startedAtMs,
                        result.stderr.ifBlank { "Validação pós-instalação falhou" }
                    )
                }
            }
        } catch (error: Exception) {
            persistFailure(
                component,
                existing?.installedAt ?: now,
                startedAtMs,
                error.message ?: error.javaClass.simpleName
            )
        } finally {
            visiting.remove(id)
        }
    }

    fun remove(id: String): InstalledComponent? = synchronized(lock) {
        val component = currentCatalog().firstOrNull { it.id == id } ?: return@synchronized null
        val existing = repository.get(id) ?: return@synchronized null
        if (existing.state != InstallationState.INSTALLED) return@synchronized existing
        val snapshot = snapshotStore?.create("antes da remoção de $id", repository.all())

        val dependents = repository.all().filter {
            it.state == InstallationState.INSTALLED && id in it.dependencies
        }
        require(dependents.isEmpty()) {
            "Não é possível remover $id; dependentes: ${dependents.joinToString { it.componentId }}"
        }

        val startedAtMs = System.currentTimeMillis()
        val removing = existing.copy(
            state = InstallationState.REMOVING,
            updatedAt = Instant.now().toEpochMilli(),
            validationStatus = false,
            error = null
        )
        repository.save(removing)
        return@synchronized try {
            val result = when {
                component.removeCommand != null -> executor.execute(command = component.removeCommand, timeoutSeconds = 600)
                component.packages.isEmpty() -> null
                else -> executor.execute(
                    command = listOf("bash", "-c", AptScripts.remove(component.packages)),
                    timeoutSeconds = 600
                )
            }
            if (result == null || result.succeeded) {
                repository.remove(id)
                snapshotStore?.record(PluginOperationRecord(System.currentTimeMillis(), "remove", id, snapshot?.version, true))
                null
            } else {
                existing.copy(
                    state = InstallationState.FAILED,
                    updatedAt = Instant.now().toEpochMilli(),
                    installDurationMs = System.currentTimeMillis() - startedAtMs,
                    validationStatus = false,
                    error = result.stderr.ifBlank { "Remoção falhou" }.take(MAX_ERROR_CHARS),
                    notes = "Os pacotes podem continuar presentes; tente remover novamente."
                ).also {
                    repository.save(it)
                    snapshotStore?.record(PluginOperationRecord(System.currentTimeMillis(), "remove", id, snapshot?.version, false, it.error))
                }
            }
        } catch (error: Exception) {
            existing.copy(
                state = InstallationState.FAILED,
                updatedAt = Instant.now().toEpochMilli(),
                installDurationMs = System.currentTimeMillis() - startedAtMs,
                validationStatus = false,
                error = (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS),
                notes = "Os pacotes podem continuar presentes; tente remover novamente."
            ).also {
                repository.save(it)
                snapshotStore?.record(PluginOperationRecord(System.currentTimeMillis(), "remove", id, snapshot?.version, false, it.error))
            }
        }
    }

    fun validate(component: SandboxComponent): Boolean =
        runCatching { executor.execute(component.validationCommand, 30).succeeded }.getOrDefault(false)

    private fun persistSuccess(component: SandboxComponent, startedAtMs: Long): InstalledComponent =
        InstalledComponent(
            componentId = component.id,
            version = component.version,
            installedAt = Instant.now().toEpochMilli(),
            dependencies = component.dependencies,
            state = InstallationState.INSTALLED,
            updatedAt = Instant.now().toEpochMilli(),
            installDurationMs = System.currentTimeMillis() - startedAtMs,
            installedByUser = "user",
            validationStatus = true
        ).also(repository::save)

    private fun persistFailure(
        component: SandboxComponent,
        installedAt: Long,
        startedAtMs: Long,
        error: String
    ): InstalledComponent = InstalledComponent(
        componentId = component.id,
        version = component.version,
        installedAt = installedAt,
        dependencies = component.dependencies,
        state = InstallationState.FAILED,
        error = error.take(MAX_ERROR_CHARS),
        updatedAt = Instant.now().toEpochMilli(),
        installDurationMs = System.currentTimeMillis() - startedAtMs,
        installedByUser = "user",
        validationStatus = false
    ).also(repository::save)

    private companion object {
        /** Limite de caracteres persistidos em InstalledComponent.error, mesmo padrão do ToolchainManager. */
        const val MAX_ERROR_CHARS = 4096
    }
}

/** Camada de busca e filtros sobre o gerenciador de plugins. */
class SearchablePluginManager(
    executor: SandboxCommandExecutor,
    private val repository: ComponentRepository,
    private val catalog: List<SandboxComponent> = BuiltInCatalog.all,
    catalogProvider: (() -> List<SandboxComponent>)? = null,
    snapshotStore: PluginSnapshotStore? = null
) {
    private val delegate = PluginManager(executor, repository, catalog, catalogProvider, snapshotStore)

    fun components(): List<SandboxComponent> = delegate.components()
    fun status(id: String): InstalledComponent? = delegate.status(id)
    fun installed(): List<InstalledComponent> = delegate.installed()
    fun install(id: String): InstalledComponent = delegate.install(id)
    fun remove(id: String): InstalledComponent? = delegate.remove(id)
    fun snapshots(): List<PluginSnapshot> = delegate.snapshots()
    fun history(limit: Int = 50): List<PluginOperationRecord> = delegate.history(limit)
    fun rollback(version: Long): PluginSnapshot = delegate.rollback(version)
    fun validate(component: SandboxComponent): Boolean = delegate.validate(component)

    fun search(query: String, items: List<SandboxComponent>? = null): List<SandboxComponent> {
        val source = items ?: components()
        if (query.isBlank()) return source
        return source.filter { component ->
            component.name.contains(query, ignoreCase = true) ||
                component.description.contains(query, ignoreCase = true) ||
                component.id.contains(query, ignoreCase = true)
        }
    }

    fun filterByKind(kind: ComponentKind, items: List<SandboxComponent>? = null): List<SandboxComponent> =
        (items ?: components()).filter { it.kind == kind }

    fun filterByInstalled(installed: Boolean, items: List<SandboxComponent>? = null): List<SandboxComponent> {
        val installedIds = repository.all()
            .filter { it.state == InstallationState.INSTALLED }
            .map { it.componentId }
            .toSet()
        return (items ?: components()).filter { (it.id in installedIds) == installed }
    }

    fun filterByDependencies(hasDependencies: Boolean, items: List<SandboxComponent>? = null): List<SandboxComponent> =
        (items ?: components()).filter { it.dependencies.isNotEmpty() == hasDependencies }

    fun query(text: String = "", kind: ComponentKind? = null, installedOnly: Boolean? = null): List<SandboxComponent> {
        var result = search(text)
        if (kind != null) result = filterByKind(kind, result)
        if (installedOnly != null) result = filterByInstalled(installedOnly, result)
        return result
    }
}

class ManagedRuntimeExecutor(private val runtime: com.sandbox.runtime.ManagedSandboxRuntime) : SandboxCommandExecutor {
    override fun execute(command: List<String>, timeoutSeconds: Long, workingDir: String): ExecutionLog =
        runtime.execute(command, timeoutSeconds, workingDir)
}

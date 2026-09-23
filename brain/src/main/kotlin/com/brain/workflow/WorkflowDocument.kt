package com.brain.workflow

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

/** Documento declarativo de workflow. O corpo é instrução, não código executável. */
data class WorkflowDocument(
    val id: String,
    val version: String,
    val description: String,
    val author: String? = null,
    val schedule: String? = null,
    val body: String,
    val source: WorkflowSource,
    val path: String? = null,
    val license: String? = null,
    val contentHash: String,
    val requiredPermissions: Set<String> = emptySet(),
    val tools: Set<String> = emptySet(),
    val effects: Set<String> = emptySet()
) {
    init {
        require(ID_PATTERN.matches(id)) { "id de workflow inválido" }
        require(VERSION_PATTERN.matches(version)) { "versão de workflow deve ser semver" }
        require(description.isNotBlank()) { "descrição de workflow obrigatória" }
        require(body.isNotBlank()) { "corpo de workflow obrigatório" }
    }

    fun executionManifest(capability: String = "workflow.$id"): WorkflowManifest = WorkflowManifest(
        id = id,
        version = version,
        nodes = listOf(WorkflowNode("document", capability)),
        enabled = true
    )

    companion object {
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        private val VERSION_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+$")
    }
}

enum class WorkflowSource { COMMUNITY, CUSTOM }

data class WorkflowSchedule(val expression: String) {
    init { require(expression.length <= 160) { "schedule de workflow excede limite" } }

    fun nextAfter(after: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val raw = expression.trim().lowercase()
        if (raw.isBlank() || raw == "on-demand" || raw == "manual") return null
        val every = Regex("every\\s+(\\d+)\\s*(minute|minutes|hour|hours|day|days)").find(raw)
        if (every != null) {
            val amount = every.groupValues[1].toLong()
            val unit = every.groupValues[2]
            val seconds = when {
                unit.startsWith("minute") -> amount * 60
                unit.startsWith("hour") -> amount * 3600
                else -> amount * 86_400
            }
            require(amount > 0) { "intervalo de schedule deve ser positivo" }
            return after.plusSeconds(seconds)
        }
        val target = Regex("(?:daily\\s+)?(\\d{1,2}):(\\d{2})").find(raw) ?: return null
        val hour = target.groupValues[1].toInt()
        val minute = target.groupValues[2].toInt()
        require(hour in 0..23 && minute in 0..59) { "hora de schedule inválida" }
        val current = ZonedDateTime.ofInstant(after, zone)
        var candidate = ZonedDateTime.of(current.toLocalDate(), LocalTime.of(hour, minute), zone)
        if (!candidate.toInstant().isAfter(after)) candidate = candidate.plusDays(1)
        return candidate.toInstant()
    }
}

/** Parser de frontmatter simples e estrito para o formato WORKFLOW.md. */
object WorkflowDocumentParser {
    fun parse(content: String, source: WorkflowSource, path: String? = null): WorkflowDocument {
        val parts = content.split("---", limit = 3)
        require(parts.size == 3) { "WORKFLOW.md precisa de frontmatter" }
        val frontmatter = parts[1]
        val body = parts[2].trim()
        val id = scalar(frontmatter, "name") ?: throw IllegalArgumentException("name obrigatório")
        val version = scalar(frontmatter, "version") ?: "1.0.0"
        val description = scalar(frontmatter, "description") ?: throw IllegalArgumentException("description obrigatória")
        return WorkflowDocument(
            id = id,
            version = version,
            description = description,
            author = scalar(frontmatter, "author"),
            schedule = scalar(frontmatter, "schedule"),
            body = body,
            source = source,
            path = path,
            license = scalar(frontmatter, "license"),
            contentHash = sha256(content),
            requiredPermissions = list(frontmatter, "required_permissions", "permissions"),
            tools = list(frontmatter, "tools"),
            effects = list(frontmatter, "effects")
        )
    }

    private fun scalar(frontmatter: String, key: String): String? = frontmatter.lines()
        .firstOrNull { it.trimStart().startsWith("$key:") }
        ?.substringAfter(":")
        ?.trim()
        ?.trim('"', '\'')
        ?.ifBlank { null }

    private fun list(frontmatter: String, vararg keys: String): Set<String> {
        val lines = frontmatter.lines()
        keys.forEach { key ->
            val index = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
            if (index < 0) return@forEach
            val inline = lines[index].substringAfter(":").trim()
            if (inline.isNotBlank()) return parseInline(inline)
            val values = buildSet {
                lines.drop(index + 1).takeWhile { it.trim().startsWith("-") }.forEach {
                    add(it.trim().removePrefix("-").trim().trim('"', '\''))
                }
            }
            if (values.isNotEmpty()) return values
        }
        return emptySet()
    }

    private fun parseInline(value: String): Set<String> = value.removePrefix("[").removeSuffix("]")
        .split(',').map { it.trim().trim('"', '\'') }.filter(String::isNotBlank).toSet()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * Catálogo nativo do Brain. Mantém conteúdo disponível, autoria customizada e habilitação
 * separadas. Habilitar só altera estado; não executa e não concede permissões.
 */
class WorkflowCatalog(private val root: File) {
    private val community = File(root, "available/community")
    private val custom = File(root, "available/custom")
    private val enabledFile = File(root, "enabled-workflows.json")

    fun list(): List<WorkflowDocument> = (scan(WorkflowSource.CUSTOM, custom) + scan(WorkflowSource.COMMUNITY, community))
        .groupBy { it.id }.values.map { it.firstOrNull { doc -> doc.source == WorkflowSource.CUSTOM } ?: it.first() }
        .sortedBy { it.id }

    fun resolve(id: String): WorkflowDocument? {
        requireSafeId(id)
        return list().firstOrNull { it.id == id }
    }

    fun enabled(): List<WorkflowDocument> = enabledIds().mapNotNull(::resolve)

    fun isEnabled(id: String): Boolean = id in enabledIds()

    fun enable(id: String): WorkflowDocument {
        val document = resolve(id) ?: throw NoSuchElementException("workflow não encontrado: $id")
        val ids = enabledIds().toMutableSet()
        ids += document.id
        writeEnabled(ids)
        return document
    }

    fun disable(id: String) {
        requireSafeId(id)
        writeEnabled(enabledIds() - id)
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("enabled", JSONArray(enabledIds().sorted()))
        .put("workflows", JSONArray(list().map { doc ->
            JSONObject().put("id", doc.id).put("version", doc.version).put("source", doc.source.name)
                .put("contentHash", doc.contentHash).put("path", doc.path ?: JSONObject.NULL)
        }))

    fun backup(output: File) {
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("catalog-state.json"))
            zip.write(snapshot().toString().toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            custom.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = custom.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry("custom/$relative"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun restore(backup: File) {
        require(backup.isFile) { "backup de workflow não encontrado" }
        ZipFile(backup).use { zip ->
            val state = zip.getEntry("catalog-state.json") ?: throw IllegalArgumentException("backup sem estado")
            val stateJson = JSONObject(zip.getInputStream(state).bufferedReader().use { it.readText() })
            val enabled = (0 until stateJson.optJSONArray("enabled").length()).map { stateJson.getJSONArray("enabled").getString(it) }.toSet()
            val extracted = mutableSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.startsWith("custom/") || entry.isDirectory) continue
                val relative = entry.name.removePrefix("custom/")
                require(relative.isNotBlank() && relative.split('/').none { it.isBlank() || it == ".." }) { "entrada de backup insegura" }
                val target = File(custom, relative).canonicalFile
                require(target.path.startsWith(custom.canonicalFile.path + File.separator)) { "backup fora do catálogo" }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                extracted += relative.substringBefore('/')
            }
            writeEnabled(enabled.filter { resolve(it) != null || it in extracted }.toSet())
        }
    }

    private fun scan(source: WorkflowSource, dir: File): List<WorkflowDocument> =
        if (!dir.isDirectory) emptyList() else dir.walkTopDown().filter { it.isFile && it.name == "WORKFLOW.md" }.mapNotNull {
            runCatching { WorkflowDocumentParser.parse(it.readText(), source, it.canonicalPath) }.getOrNull()
        }.toList()

    private fun enabledIds(): Set<String> = if (!enabledFile.isFile) emptySet() else runCatching {
        val array = JSONArray(enabledFile.readText())
        (0 until array.length()).map { array.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    private fun writeEnabled(ids: Set<String>) {
        root.mkdirs()
        enabledFile.writeText(JSONArray(ids.filter(::isSafeId).sorted()).toString())
    }

    private fun requireSafeId(id: String) { require(isSafeId(id)) { "id de workflow inválido" } }
    private fun isSafeId(id: String): Boolean = Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches(id)
}

/** Adapta um documento ao executor existente sem interpretar seu Markdown como código. */
fun WorkflowEngine.runDocument(
    document: WorkflowDocument,
    runId: String,
    idempotencyKey: String,
    authorize: (String) -> Boolean,
    executeBody: (WorkflowDocument, WorkflowNode, Int) -> WorkflowStepResult
): WorkflowRunResult = run(
    manifest = document.executionManifest(),
    runId = runId,
    idempotencyKey = idempotencyKey,
    authorize = authorize,
    execute = { node, attempt -> executeBody(document, node, attempt) }
)

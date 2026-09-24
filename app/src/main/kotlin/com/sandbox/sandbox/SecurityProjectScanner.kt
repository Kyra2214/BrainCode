package com.sandbox.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

enum class ScanRule(val severity: SecuritySeverity, val description: String) {
    PRIVATE_KEY(SecuritySeverity.CRITICAL, "chave privada materializada no workspace"),
    CREDENTIAL_ASSIGNMENT(SecuritySeverity.HIGH, "credencial atribuída diretamente no código"),
    SHELL_INTERPOLATION(SecuritySeverity.MEDIUM, "interpolação potencialmente insegura em comando shell"),
    DISABLED_TLS_VALIDATION(SecuritySeverity.HIGH, "validação TLS desabilitada explicitamente")
}

data class ProjectScanFinding(
    val rule: ScanRule,
    val relativePath: String,
    val line: Int,
    val evidence: String
)

data class ProjectScanReport(
    val root: String,
    val filesScanned: Int,
    val findings: List<ProjectScanFinding>,
    val skippedFiles: Int
) {
    val blockers: List<ProjectScanFinding>
        get() = findings.filter { it.rule.severity == SecuritySeverity.HIGH || it.rule.severity == SecuritySeverity.CRITICAL }
}

/** Scanner lexical read-only: não interpreta, importa ou executa o conteúdo encontrado. */
class SecurityProjectScanner(
    private val maxFileBytes: Long = 1L * 1024 * 1024,
    private val excludedDirectories: Set<String> = setOf(".git", "build", "node_modules", ".gradle")
) {
    fun scan(root: File): ProjectScanReport {
        require(root.isDirectory) { "Workspace do scanner deve ser um diretório" }
        val findings = mutableListOf<ProjectScanFinding>()
        var filesScanned = 0
        var skipped = 0
        Files.walk(root.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val relative = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                if (relative.split('/').any { it in excludedDirectories } || Files.size(path) > maxFileBytes) {
                    skipped++
                    return@forEach
                }
                val content = runCatching { path.toFile().readText() }.getOrNull() ?: run {
                    skipped++
                    return@forEach
                }
                filesScanned++
                scanLines(relative, content, findings)
            }
        }
        return ProjectScanReport(root.absolutePath, filesScanned, findings, skipped)
    }

    private fun scanLines(path: String, content: String, findings: MutableList<ProjectScanFinding>) {
        content.lineSequence().forEachIndexed { index, raw ->
            val line = raw.take(4096)
            val lower = line.lowercase()
            fun add(rule: ScanRule) {
                findings += ProjectScanFinding(rule, path, index + 1, redact(line))
            }
            when {
                "-----begin " in lower && " private key-----" in lower -> add(ScanRule.PRIVATE_KEY)
                Regex("(?i)(api[_-]?key|secret|password|token)\\s*[:=]\\s*['\"]?[^'\"\\s]{8,}").containsMatchIn(line) -> add(ScanRule.CREDENTIAL_ASSIGNMENT)
                Regex("(?i)(curl|wget).*(\\$\\{|`[^`]+`)").containsMatchIn(line) -> add(ScanRule.SHELL_INTERPOLATION)
                "insecure_skip_verify" in lower || "verify=false" in lower || "check_hostname=false" in lower -> add(ScanRule.DISABLED_TLS_VALIDATION)
            }
        }
    }

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(api[_-]?key|secret|password|token)(\\s*[:=]\\s*)['\"]?[^'\"\\s]+"), "$1$2<redacted>")
        .take(512)
}

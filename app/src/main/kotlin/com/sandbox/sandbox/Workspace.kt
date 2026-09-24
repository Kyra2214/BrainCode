package com.sandbox.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipInputStream

data class Project(val id: String, val name: String, val path: File, val createdAt: Long, val updatedAt: Long)

class WorkspaceManager(private val root: File, private val maxWorkspaceBytes: Long = 2L * 1024 * 1024 * 1024) {
    val projectsDir = File(root, "projects")
    val toolsDir = File(root, "tools")
    val environmentsDir = File(root, "environments")
    val tempDir = File(root, "tmp")
    val configDir = File(root, "config")
    init { listOf(projectsDir, toolsDir, environmentsDir, tempDir, configDir).forEach { it.mkdirs() } }

    @Synchronized fun listProjects(): List<Project> = projectsDir.listFiles()?.filter { it.isDirectory }?.map { project(it) }?.sortedBy { it.name } ?: emptyList()
    @Synchronized fun createProject(name: String): Project {
        ensureCapacity(256)
        validateName(name)
        val safe = safeName(name)
        require(safe.isNotBlank()) { "Nome do projeto inválido" }
        val dir = File(projectsDir, safe)
        require(!dir.exists()) { "Projeto já existe: $safe" }
        dir.mkdirs(); File(dir, ".sandbox-project").writeText("name=$safe\ncreatedAt=${System.currentTimeMillis()}\n")
        return project(dir)
    }
    @Synchronized fun openProject(name: String): Project {
        validateName(name)
        val dir = File(projectsDir, safeName(name))
        require(dir.isDirectory) { "Projeto não encontrado: $name" }
        return project(dir)
    }
    @Synchronized fun deleteProject(name: String) { openProject(name).path.deleteRecursively() }
    @Synchronized fun importProject(source: File, name: String = source.nameWithoutExtension): Project {
        require(source.isFile) { "Arquivo de projeto não encontrado" }
        ensureCapacity(source.length())
        val target = createProject(name).path
        if (source.extension.equals("zip", true)) extractZip(source, target) else Files.copy(source.toPath(), File(target, source.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        return project(target)
    }
    private fun project(dir: File): Project {
        require(dir.canonicalPath.startsWith(projectsDir.canonicalPath + File.separator)) { "Caminho fora do workspace" }
        val marker = File(dir, ".sandbox-project")
        val created = marker.readLines().firstOrNull { it.startsWith("createdAt=") }?.substringAfter("=")?.toLongOrNull() ?: dir.lastModified()
        return Project(dir.name, dir.name, dir, created, dir.lastModified())
    }
    private fun validateName(value: String) {
        require(value.isNotBlank() && !value.contains("..") && !value.contains('/') && !value.contains('\\')) { "Nome de projeto inválido" }
    }

    private fun extractZip(zip: File, target: File) {
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val output = File(target, entry.name)
                require(output.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "ZIP contém caminho inseguro" }
                if (entry.isDirectory) output.mkdirs() else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            ensureCapacity(read.toLong())
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }
    private fun ensureCapacity(incoming: Long) {
        require(incoming >= 0 && workspaceSize() <= maxWorkspaceBytes - incoming) { "quota agregada do workspace excedida" }
    }
    fun workspaceSize(): Long = root.walkTopDown().filter { it.isFile }.fold(0L) { total, file -> total + file.length() }
    private fun safeName(value: String): String = value.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').take(80).ifBlank { UUID.randomUUID().toString() }
}

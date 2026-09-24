package com.sandbox.sandbox

import org.junit.Test
import java.nio.file.Files

class WorkspaceQuotaTest {
    @Test(expected = IllegalArgumentException::class)
    fun `import rejeita quota agregada excedida`() {
        val root = Files.createTempDirectory("workspace-quota").toFile()
        val source = Files.createTempFile("project", ".txt").toFile().apply { writeText("conteudo maior que zero") }
        try {
            WorkspaceManager(root, maxWorkspaceBytes = 1).importProject(source, "demo")
        } finally {
            root.deleteRecursively()
            source.delete()
        }
    }
}

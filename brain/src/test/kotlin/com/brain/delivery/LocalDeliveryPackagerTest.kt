package com.brain.delivery

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDeliveryPackagerTest {
    @Test
    fun `empacota artefatos locais em zip verificável`() {
        val root = Files.createTempDirectory("delivery-root-").toFile()
        val output = File(root.parentFile, "delivery-${System.nanoTime()}.zip")
        try {
            File(root, "README.md").writeText("entrega local")
            File(root, "src/main.kt").apply { parentFile.mkdirs(); writeText("fun main() = Unit") }

            val result = LocalDeliveryPackager.packageRoot(root, output)

            assertTrue(result.isFile)
            ZipFile(result).use { zip ->
                assertNotNull(zip.getEntry("README.md"))
                assertNotNull(zip.getEntry("src/main.kt"))
                assertEquals("entrega local", zip.getInputStream(zip.getEntry("README.md")).bufferedReader().readText())
                assertTrue(zip.getEntry(result.name) == null)
            }
        } finally {
            output.delete()
            root.deleteRecursively()
        }
    }
}

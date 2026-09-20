package com.brain.delivery

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateDeliveryTest {
    @Test
    fun `delivery produz recibo com hashes e arquivo zip`() {
        val root = Files.createTempDirectory("create-delivery-").toFile()
        val zip = File(root.parentFile, "create-${System.nanoTime()}.zip")
        try {
            File(root, "SUMMARY.md").writeText("arquitetura, funcionalidades, testes e correções")
            File(root, "src/App.kt").apply { parentFile.mkdirs(); writeText("package app") }
            val receipt = ObservableDelivery().publish(root, "create-run", "session", "delivery", "zip", "event", "local-sandbox", Instant.now())
            LocalDeliveryPackager.packageRoot(root, zip)

            assertTrue(receipt.delivered)
            assertEquals(2, receipt.artifacts.size)
            assertTrue(receipt.artifacts.all { it.sha256.length == 64 && it.bytes > 0 })
            ZipFile(zip).use { archive ->
                assertTrue(archive.getEntry("SUMMARY.md") != null)
                assertTrue(archive.getEntry("src/App.kt") != null)
            }
        } finally {
            zip.delete()
            root.deleteRecursively()
        }
    }
}

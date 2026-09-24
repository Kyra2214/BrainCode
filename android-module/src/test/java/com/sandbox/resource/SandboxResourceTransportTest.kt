package com.sandbox.resource

import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxResourceTransportTest {
    private fun manifest(url: String) = RootfsManifest("1.0.0", "arm64-v8a", "test", url, 1, "0".repeat(64), "1.0.0")

    @Test
    fun `nao abre conexao para destino privado`() {
        val target = Files.createTempFile("rootfs-transport", ".tar.gz").toFile()
        val called = AtomicBoolean(false)
        try {
            val result = SandboxResourceManager(target, connectionFactory = { called.set(true); error("não deveria conectar") })
                .ensureAvailable(manifest("https://127.0.0.1/rootfs.tar.gz"))
            assertFalse(called.get())
            assert(result is SandboxResourceManager.DownloadResult.Failure)
        } finally { target.delete() }
    }

    @Test
    fun `segue redirect HTTPS e preserva validacao SHA256`() {
        val target = Files.createTempFile("rootfs-redirect", ".bin").toFile()
        val bytes = "asset".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val responses = ArrayDeque<HttpURLConnection>()
        responses += fake(URL("https://github.com/start"), 302, "/asset", ByteArray(0))
        responses += fake(URL("https://objects.githubusercontent.com/asset"), 200, null, bytes)
        try {
            val result = SandboxResourceManager(
                target,
                connectionFactory = { responses.removeFirst() },
                addressResolver = { arrayOf(java.net.InetAddress.getByName("93.184.216.34")) }
            ).ensureAvailable(RootfsManifest("1", "arm64-v8a", "test", "https://github.com/start", bytes.size.toLong(), sha, "1", "Apache-2.0", "", "1", "", false))
            assertTrue(result is SandboxResourceManager.DownloadResult.Success)
            assertTrue(target.readBytes().contentEquals(bytes))
        } finally { target.delete() }
    }

    @Test
    fun `rejeita redirect HTTPS para HTTP`() {
        val target = Files.createTempFile("rootfs-downgrade", ".bin").toFile()
        val response = fake(URL("https://github.com/start"), 302, "http://example.com/asset", ByteArray(0))
        try {
            val result = SandboxResourceManager(
                target,
                connectionFactory = { response },
                addressResolver = { arrayOf(java.net.InetAddress.getByName("93.184.216.34")) }
            ).ensureAvailable(RootfsManifest("1", "arm64-v8a", "test", "https://github.com/start", 0, "0".repeat(64), "1", "Apache-2.0", "", "1"))
            assertTrue(result is SandboxResourceManager.DownloadResult.Failure)
            assertTrue((result as SandboxResourceManager.DownloadResult.Failure).reason.contains("HTTP"))
        } finally { target.delete() }
    }

    private fun fake(url: URL, responseCode: Int, location: String?, bytes: ByteArray): HttpURLConnection =
        object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = responseCode
            override fun getHeaderField(name: String): String? = if (name.equals("Location", true)) location else null
            override fun getInputStream() = ByteArrayInputStream(bytes)
        }
}

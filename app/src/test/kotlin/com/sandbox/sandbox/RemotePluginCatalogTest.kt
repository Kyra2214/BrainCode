package com.sandbox.sandbox

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePluginCatalogTest {
    private val source = "trusted"
    private val url = "https://93.184.216.34/catalog.json"
    private val bytes = "plugin-artifact".toByteArray()

    private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    private fun manifest(id: String = "python") = RemoteComponentManifest(
        component = SandboxComponent(id, id, "remote component", ComponentKind.PLUGIN, version = "1.0.0"),
        sourceId = source,
        manifestUrl = url,
        artifactSha256 = digest(bytes),
        artifactBytes = bytes,
        officialSource = true
    )

    private fun snapshot(vararg manifests: RemoteComponentManifest) = RemoteCatalogSnapshot(source, url, manifests.toList(), 100L)

    @Test
    fun `importa manifesto oficial com hash verificado`() {
        val catalog = RemotePluginCatalog(setOf(source))
        val result = catalog.importSnapshot(snapshot(manifest()))

        assertEquals(listOf("python"), result.accepted.map { it.id })
        assertEquals(listOf("python"), catalog.components().map { it.id })
    }

    @Test
    fun `rejeita fonte fora da allowlist`() {
        val catalog = RemotePluginCatalog(setOf("other"))
        val result = catalog.importSnapshot(snapshot(manifest()))

        assertTrue(result.accepted.isEmpty())
        assertTrue(result.findings.single().reasons.single().contains("allowlist"))
    }

    @Test
    fun `rejeita hash divergente`() {
        val invalid = manifest().copy(artifactSha256 = "0".repeat(64))
        val result = RemotePluginCatalog(setOf(source)).importSnapshot(snapshot(invalid))

        assertEquals(RemoteCatalogDecision.REJECT, result.findings.single().decision)
        assertTrue(result.findings.single().reasons.single().contains("SHA-256"))
    }

    @Test
    fun `rejeita ids duplicados no mesmo snapshot`() {
        val result = RemotePluginCatalog(setOf(source)).importSnapshot(snapshot(manifest(), manifest()))

        assertTrue(result.accepted.isEmpty())
        assertTrue(result.findings.all { it.reasons.contains("identificador duplicado") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nao aceita catalogo HTTP`() {
        RemoteCatalogSnapshot(source, "http://93.184.216.34/catalog.json", emptyList(), 100L)
    }
}

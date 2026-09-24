package com.sandbox.sandbox

import java.net.URI
import java.security.MessageDigest

/** Manifesto de um componente recebido de uma fonte externa já coletada pelo chamador. */
data class RemoteComponentManifest(
    val component: SandboxComponent,
    val sourceId: String,
    val manifestUrl: String,
    val artifactSha256: String,
    val artifactBytes: ByteArray,
    val officialSource: Boolean
) {
    init {
        require(sourceId.matches(Regex("[A-Za-z0-9._-]+"))) { "Identificador de fonte inválido" }
        require(isSafeHttps(manifestUrl)) { "Manifesto remoto exige URL HTTPS segura" }
        require(artifactSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 inválido" }
    }

    fun contentDigest(): String = sha256(artifactBytes)
}

data class RemoteCatalogSnapshot(
    val sourceId: String,
    val sourceUrl: String,
    val manifests: List<RemoteComponentManifest>,
    val fetchedAtEpochMs: Long
) {
    init {
        require(sourceId.matches(Regex("[A-Za-z0-9._-]+")))
        require(isSafeHttps(sourceUrl)) { "Catálogo remoto exige URL HTTPS segura" }
        require(fetchedAtEpochMs >= 0)
        require(manifests.all { it.sourceId == sourceId }) { "Manifestos devem pertencer à fonte do snapshot" }
    }
}

enum class RemoteCatalogDecision { ACCEPT, REJECT }

data class RemoteCatalogFinding(
    val componentId: String,
    val decision: RemoteCatalogDecision,
    val reasons: List<String>
)

data class RemoteCatalogResult(
    val accepted: List<SandboxComponent>,
    val findings: List<RemoteCatalogFinding>
)

/**
 * Valida snapshots remotos antes que sejam expostos ao PluginManager.
 * A classe não faz rede, não instala pacotes e não executa comandos.
 */
class RemotePluginCatalog(
    private val trustedSourceIds: Set<String>,
    private val requireOfficialSource: Boolean = true
) {
    private var current: List<SandboxComponent> = emptyList()

    @Synchronized
    fun importSnapshot(snapshot: RemoteCatalogSnapshot): RemoteCatalogResult {
        val findings = snapshot.manifests.map { manifest ->
            val reasons = mutableListOf<String>()
            val duplicate = current.any { it.id == manifest.component.id } ||
                snapshot.manifests.count { it.component.id == manifest.component.id } > 1
            val accepted = when {
                snapshot.sourceId !in trustedSourceIds -> {
                    reasons += "fonte fora da allowlist"
                    false
                }
                requireOfficialSource && !snapshot.manifests.first { it.component.id == manifest.component.id }.officialSource -> {
                    reasons += "fonte não oficial"
                    false
                }
                duplicate -> {
                    reasons += "identificador duplicado"
                    false
                }
                manifest.contentDigest() != manifest.artifactSha256.lowercase() -> {
                    reasons += "hash SHA-256 do artefato não confere"
                    false
                }
                else -> true
            }
            if (accepted) reasons += "fonte confiável e integridade verificada"
            RemoteCatalogFinding(manifest.component.id, if (accepted) RemoteCatalogDecision.ACCEPT else RemoteCatalogDecision.REJECT, reasons)
        }
        val acceptedIds = findings.filter { it.decision == RemoteCatalogDecision.ACCEPT }.map { it.componentId }.toSet()
        val accepted = snapshot.manifests.filter { it.component.id in acceptedIds }.map { it.component }
        current = (current + accepted).distinctBy { it.id }
        return RemoteCatalogResult(accepted, findings)
    }

    @Synchronized
    fun components(): List<SandboxComponent> = current.toList()
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun isSafeHttps(value: String): Boolean = runCatching {
    val uri = URI(value.trim())
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
        isSafeHost(uri.host)
}.getOrDefault(false)

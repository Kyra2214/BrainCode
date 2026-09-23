package com.brain.workflow

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Manifesto de distribuição; não representa conteúdo baixado nem workflow habilitado. */
data class WorkflowPackageManifest(
    val id: String,
    val version: String,
    val sourceUrl: String,
    val license: String,
    val contentHash: String,
    val signature: String,
    val signatureKeyId: String,
    val signatureAlgorithm: String = "Ed25519"
)

/** Registry somente de manifests verificados e pinados. Não faz rede, instalação ou execução. */
class WorkflowMarketplaceRegistry(private val trustedSigningKeys: Map<String, ByteArray> = emptyMap()) {
    private val manifests = ConcurrentHashMap<String, WorkflowPackageManifest>()

    fun pin(manifest: WorkflowPackageManifest): WorkflowPackageManifest {
        validate(manifest)
        check(verify(manifest)) { "manifesto de workflow não possui assinatura confiável" }
        val key = "${manifest.id}@${manifest.version}"
        manifests[key] = manifest
        return manifest
    }

    fun resolve(id: String, version: String): WorkflowPackageManifest? = manifests["$id@$version"]
    fun list(): List<WorkflowPackageManifest> = manifests.values.sortedWith(compareBy({ it.id }, { it.version }))

    private fun validate(manifest: WorkflowPackageManifest) {
        require(Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches(manifest.id)) { "id de workflow inválido" }
        require(Regex("^\\d+\\.\\d+\\.\\d+$").matches(manifest.version)) { "versão inválida" }
        require(manifest.sourceUrl.startsWith("https://")) { "marketplace exige origem HTTPS" }
        require(manifest.license.isNotBlank() && manifest.contentHash.matches(Regex("[0-9a-fA-F]{64}"))) { "manifesto incompleto" }
    }

    private fun verify(manifest: WorkflowPackageManifest): Boolean {
        val keyBytes = trustedSigningKeys[manifest.signatureKeyId] ?: return false
        if (manifest.signatureAlgorithm != "Ed25519") return false
        return runCatching {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(keyBytes))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(payload(manifest).toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(manifest.signature))
            }
        }.getOrDefault(false)
    }

    private fun payload(manifest: WorkflowPackageManifest): String = listOf(
        manifest.id, manifest.version, manifest.sourceUrl, manifest.license, manifest.contentHash,
        manifest.signatureKeyId, manifest.signatureAlgorithm
    ).joinToString("|")
}

package com.brain.skill

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry
import com.brain.capability.CapabilityStatus
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class SkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val category: String,
    val capabilities: Set<String>,
    val triggers: Set<String> = emptySet(),
    val requiredPermissions: Set<String> = emptySet(),
    val trustLevel: TrustLevel = TrustLevel.UNTRUSTED,
    val enabled: Boolean = true,
    val sourceId: String = "builtin",
    val license: String? = null,
    val contentHash: String? = null,
    val signature: String? = null,
    val signatureKeyId: String? = null,
    val signatureAlgorithm: String? = null,
    val exclusions: Set<String> = emptySet(),
    val resources: Set<String> = emptySet(),
    val tools: Set<String> = emptySet(),
    val networkPolicy: String = "none"
)

enum class TrustLevel { CORE, VERIFIED, COMMUNITY, UNTRUSTED }

data class SkillRecord(
    val manifest: SkillManifest,
    val registeredAt: Instant,
    val revoked: Boolean = false,
    val revocationReason: String? = null
)

/**
 * Catálogo declarativo de Skills. Registrar uma Skill nunca concede autorização;
 * as permissões continuam sendo decididas pelo PolicyBroker no momento da execução.
 */
class SkillRegistry(
    private val trustedSigningKeys: Map<String, ByteArray> = emptyMap(),
    private val revocationFile: File? = null
) {
    private val records = ConcurrentHashMap<String, SkillRecord>()
    private val persistedRevocations = ConcurrentHashMap<String, String>()
    private val lock = Any()

    init {
        revocationFile?.takeIf { it.isFile }?.forEachLine { line ->
            val separator = line.indexOf('\t')
            if (separator > 0) persistedRevocations[line.substring(0, separator)] = line.substring(separator + 1)
        }
    }

    fun register(manifest: SkillManifest, content: String? = null): SkillRecord = synchronized(lock) {
        validate(manifest)
        val calculated = content?.let(::sha256)
        if (manifest.contentHash != null && calculated != null && manifest.contentHash != calculated) {
            throw SecurityException("hash da Skill não corresponde ao conteúdo")
        }
        val builtIn = manifest.sourceId == "builtin" || manifest.sourceId == "brain-builtin"
        if (manifest.enabled && !builtIn && !verifySignature(manifest, calculated ?: manifest.contentHash)) {
            throw SecurityException("Skill externa ativa exige assinatura Ed25519 verificável")
        }
        val existing = records[manifest.id]
        if (existing?.revoked == true || persistedRevocations.containsKey(manifest.id)) throw SecurityException("Skill revogada: ${manifest.id}")
        val record = SkillRecord(manifest.copy(contentHash = manifest.contentHash ?: calculated), Instant.now())
        records[manifest.id] = record
        record
    }

    fun revoke(id: String, reason: String): SkillRecord = synchronized(lock) {
        val current = records[id] ?: throw NoSuchElementException("Skill não encontrada: $id")
        val revoked = current.copy(revoked = true, revocationReason = reason)
        records[id] = revoked
        persistedRevocations[id] = reason
        revocationFile?.let { file ->
            file.parentFile?.mkdirs()
            file.appendText("$id\t$reason\n")
        }
        revoked
    }

    fun get(id: String): SkillRecord? = records[id]

    fun listEnabled(): List<SkillRecord> = records.values
        .filter { it.manifest.enabled && !it.revoked }
        .sortedBy { it.manifest.id }

    fun findForCapability(capability: String): List<SkillRecord> = listEnabled()
        .filter { capability in it.manifest.capabilities }

    fun isUsable(id: String): Boolean = get(id)?.let { it.manifest.enabled && !it.revoked } == true

    /**
     * Publica manifests no registry universal. A publicação é metadado para
     * discovery; assinatura, trust e revogação continuam sendo decididos por
     * este registry especializado antes de uma Skill ser considerada usável.
     */
    fun publishTo(registry: CapabilityRegistry) {
        listEnabled().forEach { record ->
            val manifest = record.manifest
            val trustScore = when (manifest.trustLevel) {
                TrustLevel.CORE -> 1.0
                TrustLevel.VERIFIED -> .9
                TrustLevel.COMMUNITY -> .6
                TrustLevel.UNTRUSTED -> .1
            }
            registry.register(
                CapabilityDefinition(
                    id = "skill.${manifest.id}",
                    name = manifest.name,
                    description = manifest.description,
                    category = CapabilityCategory.SKILL,
                    ownerId = manifest.sourceId,
                    origin = manifest.sourceId,
                    requiredCapabilities = manifest.capabilities,
                    providedCapabilities = manifest.capabilities,
                    requiredPermissions = manifest.requiredPermissions,
                    reliability = trustScore,
                    quality = trustScore,
                    availability = CapabilityAvailability.AVAILABLE,
                    version = manifest.version,
                    status = if (record.revoked) CapabilityStatus.REVOKED else CapabilityStatus.ACTIVE,
                    provenance = listOf(
                        CapabilityProvenance(
                            sourceId = manifest.sourceId,
                            sourceType = "skill-manifest",
                            evidence = manifest.contentHash ?: manifest.id
                        )
                    )
                )
            )
        }
    }

    private fun validate(manifest: SkillManifest) {
        require(Regex("^[a-z0-9][a-z0-9._-]+$").matches(manifest.id)) { "id de Skill inválido" }
        require(manifest.name.isNotBlank()) { "nome de Skill obrigatório" }
        require(Regex("^\\d+\\.\\d+\\.\\d+$").matches(manifest.version)) { "versão deve ser semver" }
        require(manifest.capabilities.isNotEmpty()) { "Skill deve declarar ao menos uma capability" }
        require(manifest.sourceId.isNotBlank()) { "proveniência da Skill é obrigatória" }
        if (manifest.trustLevel == TrustLevel.UNTRUSTED && manifest.enabled) {
            throw SecurityException("Skill externa não verificada não pode ser ativada")
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun verifySignature(manifest: SkillManifest, contentHash: String?): Boolean {
        if (contentHash == null || manifest.signature.isNullOrBlank() || manifest.signatureKeyId.isNullOrBlank()) return false
        if (manifest.signatureAlgorithm != "Ed25519") return false
        val encodedKey = trustedSigningKeys[manifest.signatureKeyId] ?: return false
        return runCatching {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encodedKey))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(canonicalPayload(manifest, contentHash).toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(manifest.signature))
            }
        }.getOrDefault(false)
    }

    private fun canonicalPayload(manifest: SkillManifest, contentHash: String): String =
        listOf(manifest.id, manifest.version, manifest.sourceId, contentHash).joinToString("|")
}

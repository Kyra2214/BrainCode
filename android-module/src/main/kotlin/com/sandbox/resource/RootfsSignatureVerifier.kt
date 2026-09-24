package com.sandbox.resource

import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Verifica uma assinatura Ed25519 detached sobre os bytes do arquivo RootFS. */
fun interface RootfsSignatureVerifier {
    fun verify(file: File, manifest: RootfsManifest): Boolean
}

class Ed25519RootfsSignatureVerifier(
    trustedKeys: Map<String, ByteArray>
) : RootfsSignatureVerifier {
    private val keys: Map<String, PublicKey> = trustedKeys.mapValues { (_, encoded) ->
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))
    }

    override fun verify(file: File, manifest: RootfsManifest): Boolean {
        if (manifest.signatureAlgorithm != "Ed25519" || manifest.signature.isBlank() || manifest.signatureKeyId.isBlank()) return false
        val key = keys[manifest.signatureKeyId] ?: return false
        return runCatching {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) verifier.update(buffer, 0, read)
            }
            verifier.verify(Base64.getDecoder().decode(manifest.signature))
        }.getOrDefault(false)
    }
}

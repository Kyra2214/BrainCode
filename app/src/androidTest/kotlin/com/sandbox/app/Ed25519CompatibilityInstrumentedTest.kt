package com.sandbox.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

@RunWith(AndroidJUnit4::class)
class Ed25519CompatibilityInstrumentedTest {
    @Test
    fun api_do_dispositivo_disponibiliza_ed25519() {
        // Ed25519/JCA não é uma capacidade uniforme entre imagens/dispositivos
        // Android. O teste valida a implementação quando ela existe, mas não
        // transforma a ausência de um provider opcional em falha do app.
        val keyPairGenerator = try {
            KeyPairGenerator.getInstance("Ed25519")
        } catch (e: NoSuchAlgorithmException) {
            assumeTrue(
                "Ed25519 JCA não disponível nesta imagem Android; providers=" +
                    Security.getProviders().joinToString { it.name },
                false
            )
            return
        }

        val keyPair = keyPairGenerator.generateKeyPair()
        val payload = "braincode-marketplace-compatibility".toByteArray()
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(payload)
        }.sign()
        val verified = Signature.getInstance("Ed25519").apply {
            initVerify(
                KeyFactory.getInstance("Ed25519")
                    .generatePublic(X509EncodedKeySpec(keyPair.public.encoded))
            )
            update(payload)
        }.verify(signature)

        assertTrue("Ed25519 deve assinar e verificar no dispositivo", verified)
    }
}

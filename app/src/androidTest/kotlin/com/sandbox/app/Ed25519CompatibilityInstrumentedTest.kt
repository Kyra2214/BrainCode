package com.sandbox.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.Signature

@RunWith(AndroidJUnit4::class)
class Ed25519CompatibilityInstrumentedTest {
    @Test
    fun api_do_dispositivo_disponibiliza_ed25519() {
        val keyPair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = "braincode-marketplace-compatibility".toByteArray()
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(payload)
        }.sign()
        val verified = Signature.getInstance("Ed25519").apply {
            initVerify(KeyFactory.getInstance("Ed25519").generatePublic(java.security.spec.X509EncodedKeySpec(keyPair.public.encoded)))
            update(payload)
        }.verify(signature)
        assertTrue("Ed25519 deve assinar e verificar no dispositivo", verified)
    }
}

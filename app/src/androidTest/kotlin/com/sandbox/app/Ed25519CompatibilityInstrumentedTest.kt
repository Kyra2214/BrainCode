package com.sandbox.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.Signature

@RunWith(AndroidJUnit4::class)
class Ed25519CompatibilityInstrumentedTest {
    @Test
    fun api_do_dispositivo_disponibiliza_ed25519() {
        // Ed25519 entrou na API Android 33. Em APIs anteriores, a ausência do
        // provider JCA é uma limitação da plataforma, não uma falha do app.
        assumeTrue(
            "Ed25519 JCA requer API 33+; API ${Build.VERSION.SDK_INT} não fornece o provider",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        )
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

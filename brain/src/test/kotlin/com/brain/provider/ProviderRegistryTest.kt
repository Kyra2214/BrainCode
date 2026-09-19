package com.brain.provider

import com.brain.capability.CostClass
import com.brain.router.JanelaLimite
import com.brain.router.PapelPipeline
import com.brain.router.ProviderModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderRegistryTest {
    private val client = object : ProviderClient {
        override fun complete(request: ProviderRequest): Result<ProviderResponse> =
            Result.failure(IllegalStateException("fake"))
    }

    private fun provider(
        id: String,
        enabled: Boolean = true,
        capabilities: Set<String> = setOf("prompt.generate")
    ) = ProviderRegistration(
        providerId = id,
        displayName = id,
        client = client,
        capabilities = capabilities,
        models = listOf(
            ProviderModel(
                providerId = id,
                modeloId = "$id-model",
                papeisSugeridos = listOf(PapelPipeline.ESCRITA_DE_PROMPT),
                janela = JanelaLimite(porMinuto = 10),
                cost = CostClass.FREE
            )
        ),
        enabled = enabled
    )

    @Test
    fun `registry registra lista filtra capability e modelos`() {
        val registry = InMemoryProviderRegistry()
        registry.register(provider("b"))
        registry.register(provider("a", capabilities = setOf("code.execute")))

        assertEquals(listOf("a", "b"), registry.list().map { it.providerId })
        assertEquals(listOf("b"), registry.findForCapability("prompt.generate").map { it.providerId })
        assertEquals(listOf("b-model"), registry.modelsForCapability("prompt.generate").map { it.modeloId })
    }

    @Test
    fun `providers desabilitados nao aparecem em discovery`() {
        val registry = InMemoryProviderRegistry()
        registry.register(provider("disabled", enabled = false))
        registry.register(provider("enabled"))

        assertEquals(listOf("enabled"), registry.list(enabledOnly = true).map { it.providerId })
        assertTrue(registry.findForCapability("prompt.generate").none { it.providerId == "disabled" })
    }

    @Test
    fun `registry rejeita modelo de outro provider e duplicidade`() {
        val model = ProviderModel("other", "model", emptyList(), JanelaLimite())
        assertFailsWith<IllegalArgumentException> {
            ProviderRegistration("provider", "Provider", client, models = listOf(model))
        }

        val registry = InMemoryProviderRegistry()
        registry.register(provider("one"))
        assertFailsWith<IllegalStateException> { registry.register(provider("one")) }
        assertFalse(registry.remove("missing"))
    }
}

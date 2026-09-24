package com.brain.provider

import com.brain.router.InMemoryApiCatalog
import com.brain.router.JanelaLimite
import com.brain.router.PapelPipeline
import com.brain.router.ProviderModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderDispatcherTest {
    @Test fun `dispatch registra sucesso no catalogo`() {
        val model = ProviderModel("p", "m", listOf(PapelPipeline.PLANEJAMENTO), JanelaLimite())
        val catalog = InMemoryApiCatalog(listOf(model))
        val client = object : ProviderClient {
            override fun complete(request: ProviderRequest) = Result.success(ProviderResponse(200, "ok", 3, "p"))
        }
        assertEquals(200, ProviderDispatcher(catalog).dispatch(model, client, ProviderRequest("ignored", "hello")).getOrThrow().statusCode)
        assertEquals(1.0, catalog.statsAtuais("p", "m")!!.taxaSucessoRecente!!, 0.001)
    }
}

package com.brain.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre o parsing do catálogo real reaproveitado do IaBrain
 * (brain/src/main/resources/catalog/ai_api_catalog.json) e a heurística
 * capability -> papel usada até a Fase D/G trazer dado real de uso.
 */
class ApiCatalogLoaderTest {

    private fun json(vararg providers: String) = """{"providers":[${providers.joinToString(",")}]}"""

    @Test
    fun `mapeia reasoning chat e coding para os papeis correspondentes`() {
        val modelos = ApiCatalogLoader.fromJson(
            json("""{"id":"deepseek","models":[{"id":"v4","capabilities":["chat","reasoning","coding","agent"]}]}""")
        )

        assertEquals(1, modelos.size)
        val modelo = modelos.single()
        assertEquals("deepseek", modelo.providerId)
        assertEquals("v4", modelo.modeloId)
        assertTrue(PapelPipeline.PLANEJAMENTO in modelo.papeisSugeridos)
        assertTrue(PapelPipeline.ESCRITA_DE_PROMPT in modelo.papeisSugeridos)
        assertTrue(PapelPipeline.EXECUCAO_CODIGO in modelo.papeisSugeridos)
    }

    @Test
    fun `sem capabilities declaradas serve todos os papeis`() {
        val modelos = ApiCatalogLoader.fromJson(
            json("""{"id":"generic","models":[{"id":"m1"}]}""")
        )

        assertEquals(PapelPipeline.values().toList(), modelos.single().papeisSugeridos)
    }

    @Test
    fun `provider sem modelos e ignorado`() {
        val modelos = ApiCatalogLoader.fromJson(
            json("""{"id":"empty"}""", """{"id":"real","models":[{"id":"m","capabilities":["chat"]}]}""")
        )

        assertEquals(1, modelos.size)
        assertEquals("real", modelos.single().providerId)
    }

    @Test
    fun `varios modelos do mesmo provider viram entradas separadas`() {
        val modelos = ApiCatalogLoader.fromJson(
            json("""{"id":"multi","models":[{"id":"a","capabilities":["chat"]},{"id":"b","capabilities":["coding"]}]}""")
        )

        assertEquals(listOf("a", "b"), modelos.map { it.modeloId })
    }
}

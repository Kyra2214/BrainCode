package com.sandbox.app

import com.brain.router.PapelPipeline
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeysCatalogRolesTest {
    @Test
    fun `capability chat roteia para conversacao e preserva escrita de prompt`() {
        assertEquals(
            listOf(PapelPipeline.ESCRITA_DE_PROMPT, PapelPipeline.CONVERSACAO),
            apiRolesForCapabilities(listOf("chat"))
        )
    }
}

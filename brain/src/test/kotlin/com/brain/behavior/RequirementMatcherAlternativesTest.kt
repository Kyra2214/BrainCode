package com.brain.behavior

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequirementMatcherTest {
    @Test
    fun `barra representa alternativas e nao conjuncao`() {
        assertTrue(RequirementMatcher.isPresent("interface/aplicativo", "aplicativo de notas offline"))
        assertTrue(RequirementMatcher.isPresent("interface/aplicativo", "interface simples"))
        assertFalse(RequirementMatcher.isPresent("interface/aplicativo", "backend somente"))
    }
}

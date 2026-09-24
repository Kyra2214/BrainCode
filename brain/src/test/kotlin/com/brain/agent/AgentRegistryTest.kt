package com.brain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRegistryTest {
    @Test
    fun `registry resolve por categoria e capability sem depender do nome externo`() {
        val registry = AgentRegistry.default()
        val conversation = registry.resolve(AgentCategory.CONVERSATION, "chat.respond")
        val research = registry.resolve(AgentCategory.RESEARCH, "network.research")
        val web = registry.resolve(AgentCategory.WEB, "web.search")
        assertNotNull(conversation)
        assertNotNull(research)
        assertNotNull(web)
        assertEquals("AGPL-3.0", conversation!!.license)
        assertTrue(research!!.provenance.contains("SearchClaw"))
        assertTrue(web!!.provenance.contains("Firecrawl"))
    }
}

package com.brain.secretary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorPolicyTest {
    @Test
    fun `chat nao permite producao nem execucao`() {
        val scope = DoorScope(Door.CHAT, CreatePhase.CHAT)

        assertTrue(DoorPolicy.allows(scope, "brain.analyze"))
        assertTrue(DoorPolicy.allows(scope, "network.research"))
        assertFalse(DoorPolicy.allows(scope, "workspace.write"))
        assertFalse(DoorPolicy.allows(scope, "sandbox.code"))
    }

    @Test
    fun `prompt permite biblioteca e pesquisa mas nao projeto`() {
        val scope = DoorScope(Door.PROMPT, CreatePhase.PROMPT)

        assertTrue(DoorPolicy.allows(scope, "prompt.library.write"))
        assertTrue(DoorPolicy.allows(scope, "network.research"))
        assertFalse(DoorPolicy.allows(scope, "workspace.write"))
        assertFalse(DoorPolicy.allows(scope, "sandbox.code"))
    }

    @Test
    fun `criacao so libera escrita e execucao depois da aprovacao`() {
        val discussion = DoorScope(Door.CREATE, CreatePhase.DISCUSSION)
        val approved = DoorScope(Door.CREATE, CreatePhase.APPROVED)

        assertFalse(DoorPolicy.allows(discussion, "workspace.write"))
        assertFalse(DoorPolicy.allows(discussion, "sandbox.code"))
        assertTrue(DoorPolicy.allows(approved, "workspace.write"))
        assertTrue(DoorPolicy.allows(approved, "sandbox.code"))
    }

    @Test
    fun `restricoes explicitas vencem a matriz da porta`() {
        val scope = DoorScope(
            Door.CREATE,
            CreatePhase.APPROVED,
            restrictions = setOf(Restriction.NO_WEB, Restriction.NO_EXECUTE, Restriction.NO_PRODUCE)
        )

        assertFalse(DoorPolicy.allows(scope, "network.research"))
        assertFalse(DoorPolicy.allows(scope, "workspace.write"))
        assertFalse(DoorPolicy.allows(scope, "sandbox.code"))
    }

    @Test
    fun `nenhuma porta permite contas externas nesta decisao`() {
        assertFalse(DoorPolicy.externalAccountsAllowed(Door.CHAT))
        assertFalse(DoorPolicy.externalAccountsAllowed(Door.PROMPT))
        assertFalse(DoorPolicy.externalAccountsAllowed(Door.CREATE))
    }
}

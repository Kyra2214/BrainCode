package com.brain.secretary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatePhaseMachineTest {
    private val secretary = DeterministicSecretary()

    @Test
    fun `aprovação explicita percorre discussão requisitos arquitetura plano e approved`() {
        val intent = secretary.classify("criar um aplicativo de notas")
        assertEquals(Door.CREATE, intent.door)
        assertEquals(CreatePhase.DISCUSSION, intent.phase)

        val approved = SecretaryState().designate(intent).approve()

        assertEquals(CreatePhase.APPROVED, approved.currentIntent!!.phase)
        assertTrue(approved.history.map { it.phase }.containsAll(listOf(CreatePhase.REQUIREMENTS, CreatePhase.ARCHITECTURE, CreatePhase.PLAN)))
    }

    @Test
    fun `nenhuma escrita ou execução antes de approved`() {
        val intent = secretary.classify("criar um aplicativo de notas")
        val preApproval = DoorScope(Door.CREATE, CreatePhase.DISCUSSION)
        val approved = intent.copy(phase = CreatePhase.APPROVED, scope = preApproval.copy(phase = CreatePhase.APPROVED))

        assertFalse(DoorPolicy.allows(preApproval, "workspace.write"))
        assertFalse(DoorPolicy.allows(preApproval, "sandbox.code"))
        assertTrue(DoorPolicy.allows(approved.scope, "workspace.write"))
        assertTrue(DoorPolicy.allows(approved.scope, "sandbox.code"))
    }

    @Test
    fun `salto de fase sem aprovação é rejeitado`() {
        val intent = secretary.classify("criar um sistema")
        assertFalse(CreatePhaseMachine.canTransition(intent.phase, CreatePhase.EXECUTION))
    }
}

package com.brain.policy

import com.brain.secretary.CreatePhase
import com.brain.secretary.Door
import com.brain.secretary.DoorScope
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyBrokerDoorTest {
    private val broker = PolicyBroker(
        allowedCapabilities = listOf("brain.analyze", "prompt.library.write", "workspace.write", "sandbox.code", "network.research"),
        actorCapabilities = mapOf("agent" to listOf("brain.analyze", "prompt.library.write", "workspace.write", "sandbox.code", "network.research"))
    )

    @Test
    fun `policy nega sandbox code na porta chat`() {
        val decision = broker.authorize(
            "agent",
            "sandbox.code",
            "task",
            PolicyContext("run", "task", "agent", doorScope = DoorScope(Door.CHAT, CreatePhase.CHAT))
        )

        assertEquals(Decision.DENY, decision.decision)
        assertEquals(Door.CHAT, decision.doorScope?.door)
    }

    @Test
    fun `policy nega workspace antes da aprovacao da criacao`() {
        val decision = broker.authorize(
            "agent",
            "workspace.write",
            "task",
            PolicyContext("run", "task", "agent", doorScope = DoorScope(Door.CREATE, CreatePhase.DISCUSSION))
        )

        assertEquals(Decision.DENY, decision.decision)
    }

    @Test
    fun `policy permite prompt na porta prompt e preserva escopo no token`() {
        val scope = DoorScope(Door.PROMPT, CreatePhase.PROMPT)
        val decision = broker.authorize("agent", "prompt.library.write", "task", PolicyContext("run", "task", "agent", doorScope = scope))

        assertEquals(Decision.ALLOW, decision.decision)
        assertEquals(scope, decision.doorScope)
        assertEquals(true, broker.check(decision))
    }

    @Test
    fun `conta externa e negada quando a porta nao permite APIs`() {
        val decision = broker.authorize(
            "agent",
            "network.research",
            "task",
            PolicyContext("run", "task", "agent", networkAllowed = true, authorizedAccountIds = setOf("external-1"), doorScope = DoorScope(Door.CHAT, CreatePhase.CHAT))
        )

        assertEquals(Decision.DENY, decision.decision)
    }

    @Test
    fun `workflow run exige Porta 3 aprovada`() {
        val workflowBroker = PolicyBroker(
            allowedCapabilities = listOf("workflow.run"),
            actorCapabilities = mapOf("agent" to listOf("workflow.run"))
        )
        val chat = workflowBroker.authorize(
            "agent", "workflow.run", "workflow:review",
            PolicyContext("run", "task", "agent", doorScope = DoorScope(Door.CHAT, CreatePhase.CHAT), sandboxRequired = true)
        )
        val discussion = workflowBroker.authorize(
            "agent", "workflow.run", "workflow:review",
            PolicyContext("run", "task", "agent", doorScope = DoorScope(Door.CREATE, CreatePhase.DISCUSSION), sandboxRequired = true)
        )
        val approved = workflowBroker.authorize(
            "agent", "workflow.run", "workflow:review",
            PolicyContext("run", "task", "agent", doorScope = DoorScope(Door.CREATE, CreatePhase.APPROVED), sandboxRequired = true)
        )

        assertEquals(Decision.DENY, chat.decision)
        assertEquals(Decision.DENY, discussion.decision)
        assertEquals(Decision.ALLOW, approved.decision)
    }
}

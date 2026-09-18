package com.sandbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionGuardTest {
    @Test
    fun failure_is_terminal_and_does_not_continue_to_next_capability() {
        val calls = mutableListOf<AgentCapability>()
        val context = object : AgentExecutionContext {
            override fun invokeCapability(capability: AgentCapability, parameters: Map<String, String>): AgentEvidence {
                calls += capability
                return if (capability == AgentCapability.WEB_SEARCH) {
                    AgentEvidence("test", "search failed", capability.name, ok = false)
                } else {
                    AgentEvidence("test", "should not execute", capability.name)
                }
            }
        }

        val mission = AgentMission(
            id = "guard-failure",
            objective = "Pesquisa",
            requiredCapabilities = setOf(AgentCapability.WEB_SEARCH, AgentCapability.GITHUB)
        )

        val result = ResearchAgent().execute(mission, context)

        assertFalse(result.success)
        assertEquals(listOf(AgentCapability.WEB_SEARCH), calls)
        assertEquals(1, result.calls)
    }

    @Test
    fun capability_call_budget_is_enforced() {
        val calls = mutableListOf<AgentCapability>()
        val context = object : AgentExecutionContext {
            override fun invokeCapability(capability: AgentCapability, parameters: Map<String, String>): AgentEvidence {
                calls += capability
                return AgentEvidence("test", "ok", capability.name)
            }
        }

        val mission = AgentMission(
            id = "guard-budget",
            objective = "Pesquisa",
            requiredCapabilities = setOf(AgentCapability.WEB_SEARCH, AgentCapability.GITHUB),
            maxCapabilityCalls = 2
        )

        val result = ResearchAgent().execute(mission, context)

        assertTrue(result.success)
        assertEquals(2, result.calls)
        assertEquals(2, calls.size)
    }

    @Test
    fun expired_deadline_blocks_execution_before_first_call() {
        var calls = 0
        val context = object : AgentExecutionContext {
            override fun invokeCapability(capability: AgentCapability, parameters: Map<String, String>): AgentEvidence {
                calls++
                return AgentEvidence("test", "should not execute", capability.name)
            }
        }

        val mission = AgentMission(
            id = "guard-deadline",
            objective = "Pesquisa",
            requiredCapabilities = setOf(AgentCapability.WEB_SEARCH),
            deadlineEpochMillis = System.currentTimeMillis() - 1
        )

        val result = ResearchAgent().execute(mission, context)

        assertFalse(result.success)
        assertEquals(0, calls)
        assertEquals(0, result.calls)
    }
}

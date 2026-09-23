package com.sandbox.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.brain.skill.RooftsSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RooftsSkillCatalogInstrumentedTest {
    private val sourcePath = "roofts/0.6/skills/planning-and-task-breakdown/SKILL.md"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun permissao_pendente_nao_carrega_corpo() {
        val metadata = RooftsSkill(
            id = "planning-and-task-breakdown",
            description = "planejar e dividir tarefas",
            body = "",
            triggers = setOf("planejar"),
            requiredPermissions = setOf("workspace.write"),
            sourcePath = sourcePath
        )
        val selection = RooftsSkillCatalog(context, listOf(metadata)).activate(
            runId = "instrumented-run",
            objective = "planejar uma entrega"
        )

        assertTrue(selection.skills.isEmpty())
        assertEquals("PENDING", selection.activationPlan.activations.single().approvalStatus.name)
    }

    @Test
    fun loadResource_exige_caminho_declarado_relativo_e_existente() {
        val skill = RooftsSkill(
            id = "planning-and-task-breakdown",
            description = "planejar",
            body = "",
            sourcePath = sourcePath,
            resources = setOf("SKILL.md")
        )
        val catalog = RooftsSkillCatalog(context, listOf(skill))

        assertTrue(catalog.loadResource(skill, "SKILL.md").contains("name: planning-and-task-breakdown"))
        assertRejected { catalog.loadResource(skill, "/etc/passwd") }
        assertRejected { catalog.loadResource(skill, "../LICENSE") }
        assertRejected { catalog.loadResource(skill, "LICENSE") }
    }

    private fun assertRejected(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("operação deveria ser rejeitada", failure is IllegalArgumentException)
    }
}

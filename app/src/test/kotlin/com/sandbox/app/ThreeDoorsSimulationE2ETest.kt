package com.sandbox.app

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptTemplate
import com.brain.secretary.CreatePhase
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.Door
import com.sandbox.agent.BrainSandboxController
import com.sandbox.agent.StatusPasso
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2E dirigido das três portas do Secretário.
 *
 * Os providers externos e o launcher do workspace são determinísticos e locais;
 * roteamento, Policy, ActionGateway, controller, biblioteca, pesquisa e aprovação
 * são os componentes reais de produção.
 */
class ThreeDoorsSimulationE2ETest {

    @Test
    fun `situacao 1 porta 1 chat normal nao cria nem executa projeto`() {
        withController(
            executors = mapOf(
                "chat.respond" to ActionExecutor { _, _, _ ->
                    ActionExecution(true, result = "Entendi a ideia e posso ajudar a organizá-la.", evidence = listOf("chat:e2e:local"))
                }
            )
        ) { root, controller ->
            val objective = "Quero conversar sobre uma ideia de aplicativo de lista de compras, sem criar nada ainda."
            val intent = DeterministicSecretary().classify(objective)
            val cycle = controller.executeObjective(objective, "three-doors-chat", intent = intent)
            val events = controller.localEvents("three-doors-chat")

            assertEquals(Door.CHAT, intent.door)
            assertEquals(CreatePhase.CHAT, intent.phase)
            assertTrue(cycle.aprovado)
            assertEquals(listOf("chat.respond"), cycle.passos.map { it.capacidade })
            assertTrue(cycle.resposta.orEmpty().contains("Entendi"))
            assertFalse(events.any { it.type == "PlanningArtifactCreated" || it.type == "CreateApprovalRequested" })
            assertFalse(cycle.passos.any { it.capacidade.orEmpty().startsWith("workspace.") || it.capacidade.orEmpty().startsWith("sandbox.") })
            assertTrue(events.any { it.type == "DoorDesignated" && it.payload["door"] == "CHAT" })
            assertTrue(events.any { it.type == "RouteDecided" && it.payload["route"] == "CONVERSATION" })
        }
    }

    @Test
    fun `situacao 2 porta 1 pesquisa entrega resposta com evidencia`() {
        withController(
            executors = mapOf(
                "sandbox.info" to ActionExecutor { _, _, _ ->
                    ActionExecution(
                        true,
                        result = "Pesquisa: listas de compras eficazes usam categorias, itens e prioridade.",
                        evidence = listOf("web-research:e2e:source=example.test")
                    )
                },
                "chat.respond" to ActionExecutor { request, _, _ ->
                    ActionExecution(
                        true,
                        result = "Síntese: use categorias, itens e prioridade para manter a lista simples.",
                        evidence = listOf("chat:e2e:synthesis", "chat:websearch:evidence", "context:${request.parameters.values.any { it.contains("categorias") }}")
                    )
                }
            )
        ) { _, controller ->
            val objective = "Pesquise como organizar uma lista de compras simples e explique as melhores práticas."
            val intent = DeterministicSecretary().classify(objective)
            val cycle = controller.executeObjective(objective, "three-doors-research", intent = intent)
            val events = controller.localEvents("three-doors-research")

            assertEquals(Door.CHAT, intent.door)
            assertTrue(cycle.aprovado)
            assertEquals(listOf("network.research", "chat.respond"), cycle.passos.map { it.capacidade })
            assertTrue(cycle.resposta.orEmpty().contains("Síntese"))
            assertTrue(cycle.passos.any { it.resultado.orEmpty().contains("categorias") })
            assertTrue(events.any { it.type == "DoorDesignated" && it.payload["door"] == "CHAT" })
            assertTrue(events.any { it.type == "RouteDecided" && it.payload["route"] == "CAPABILITY" })
        }
    }

    @Test
    fun `situacao 3 porta 2 reutiliza prompt compativel da biblioteca`() {
        val library = RecordingPromptLibrary(
            PromptTemplate(
                id = "seed:lista-compras",
                versao = 1,
                finalidade = "prompt de aplicativo de lista de compras",
                contextoDeUso = "crie um prompt de aplicativo de lista de compras android",
                skillRelacionada = "prompt-generation",
                agenteRelacionado = null,
                textoTemplate = "Projete uma interface Android simples para uma lista de compras com categorias e itens.",
                taxaSucesso = 0.9,
                custoMedio = 0.0,
                tempoMedioMs = 0L,
                amostrasObservadas = 10
            )
        )
        val promptExecutor = PromptGenerationExecutor(library, PromptImprover { _, _ -> error("IA não deve ser necessária") })
        withController(
            library = library,
            executors = mapOf("prompt.library.generate" to promptExecutor, "prompt.library.write" to promptExecutor)
        ) { _, controller ->
            val objective = "Crie um prompt de aplicativo de lista de compras Android com categorias e itens."
            val intent = DeterministicSecretary().classify(objective)
            val cycle = controller.executeObjective(objective, "three-doors-prompt-reuse", intent = intent)
            val step = cycle.passos.single()

            assertEquals(Door.PROMPT, intent.door)
            assertEquals(CreatePhase.PROMPT, intent.phase)
            assertTrue(cycle.aprovado)
            assertEquals("prompt.library.write", step.capacidade)
            assertTrue(step.resultado.orEmpty().contains("biblioteca"))
            assertTrue("evidence=${step.executionEvidence}", step.executionEvidence.any { it.contains("prompt-library:") })
            assertTrue("evidence=${step.executionEvidence}", step.executionEvidence.any { it == "prompt-library:saved-before-response" })
            assertNotNull(library.lastSaved)
        }
    }

    @Test
    fun `situacao 4 porta 2 pesquisa cria prompt e integra nova versao na biblioteca`() {
        val library = RecordingPromptLibrary()
        val promptExecutor = PromptGenerationExecutor(library, PromptImprover { _, _ -> error("IA indisponível: fallback local esperado") })
        withController(
            library = library,
            executors = mapOf(
                "sandbox.info" to ActionExecutor { _, _, _ ->
                    ActionExecution(
                        true,
                        result = "Pesquisa: para um app pequeno, use navegação simples, categorias e persistência local.",
                        evidence = listOf("web-research:e2e:source=mobile-patterns.test")
                    )
                },
                "prompt.library.generate" to promptExecutor,
                "prompt.library.write" to promptExecutor,
                "chat.respond" to ActionExecutor { _, _, _ -> ActionExecution(true, result = "Pesquisa concluída.", evidence = listOf("chat:e2e:research-summary")) }
            )
        ) { _, controller ->
            val researchObjective = "Pesquise padrões para um app pequeno de lista de compras e explique as recomendações."
            val researchIntent = DeterministicSecretary().classify(researchObjective)
            val researchCycle = controller.executeObjective(researchObjective, "three-doors-prompt-research", intent = researchIntent)

            val promptObjective = "Crie um prompt Android para um app pequeno de lista de compras com categorias, persistência local e navegação simples.\n" +
                "Referências resolvidas:\n- pesquisa aprovada: navegação simples, categorias e persistência local."
            val promptIntent = DeterministicSecretary().classify(promptObjective)
            val promptCycle = controller.executeObjective(promptObjective, "three-doors-prompt-create", intent = promptIntent)
            val events = controller.localEvents("three-doors-prompt-create")
            val generated = library.lastSaved

            assertEquals(Door.CHAT, researchIntent.door)
            assertTrue(researchCycle.aprovado)
            assertTrue(researchCycle.passos.any { it.capacidade == "network.research" })
            assertTrue(researchCycle.passos.any { it.executionEvidence.any { evidence -> evidence.startsWith("web-research:e2e:") } })
            assertEquals(Door.PROMPT, promptIntent.door)
            assertTrue("cycle=$promptCycle", promptCycle.aprovado)
            assertTrue(promptCycle.passos.any { it.capacidade == "prompt.library.write" })
            assertTrue(promptCycle.passos.any { it.executionEvidence.any { evidence -> evidence == "prompt-library:saved-before-response" } })
            assertNotNull("o prompt criado deve ser integrado à biblioteca", generated)
            assertTrue(generated!!.textoTemplate.contains("lista de compras"))
            assertTrue(events.any { it.type == "DoorDesignated" && it.payload["door"] == "PROMPT" })
        }
    }

    @Test
    fun `situacao 5 porta 3 cria app pequeno com aprovacao roadmap especialistas e workspace`() {
        withController(
            executors = mapOf(
                "workspace.generate" to ActionExecutor { _, _, _ ->
                    ActionExecution(true, result = "app lista de compras criado", evidence = listOf("workspace:e2e:created"))
                }
            )
        ) { _, controller ->
            val objective = "Crie um app pequeno de lista de compras Android com categorias, adicionar item e marcar como comprado."
            val intent = DeterministicSecretary().classify(objective)
            val waiting = controller.executeObjective(objective, "three-doors-create", intent = intent)
            val approvalId = requireNotNull(waiting.passos.single().approvalId)
            val beforeApprovalEvents = controller.localEvents("three-doors-create")

            assertEquals(Door.CREATE, intent.door)
            assertEquals(CreatePhase.DISCUSSION, intent.phase)
            assertEquals(StatusPasso.AGUARDANDO_APROVACAO, waiting.passos.single().status)
            assertTrue(beforeApprovalEvents.any { it.type == "CreateApprovalRequested" })
            assertTrue(controller.creationExecutions("three-doors-create").isEmpty())

            assertTrue(controller.approve(approvalId))
            val resumed = controller.resumePlan(waitingToPlan(waiting), "three-doors-create", approvalId)
            val events = controller.localEvents("three-doors-create")

            assertTrue(resumed.aprovado)
            assertTrue(events.any { it.type == "RoadmapCreated" })
            assertTrue(events.any { it.type == "CreationPromptsGenerated" })
            assertTrue(events.any { it.type == "TaskAssigned" })
            assertTrue(events.any { it.type == "SpecialistSelected" })
            assertTrue(events.any { it.type == "workflow.execution.started" })
            assertTrue(controller.creationExecutions("three-doors-create").isNotEmpty())
            assertTrue(controller.localEventsHealthy())
        }
    }

    private fun waitingToPlan(waiting: com.sandbox.agent.ResultadoCiclo): com.brain.planner.PlanoExecucao =
        com.brain.planner.PlanoExecucao(
            waiting.objetivo,
            listOf(com.brain.planner.PassoPlano("produzir", "workspace.write", "artefato do app criado"))
        )

    private fun withController(
        library: PromptLibrary? = null,
        executors: Map<String, ActionExecutor>,
        block: (File, BrainSandboxController) -> Unit
    ) {
        val root = Files.createTempDirectory("three-doors-e2e-").toFile()
        try {
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "three-doors-session"),
                rootfsDir = root,
                promptLibrary = library,
                capabilityExecutors = executors
            )
            block(root, controller)
        } finally {
            root.deleteRecursively()
        }
    }

    private class RecordingPromptLibrary(seed: PromptTemplate? = null) : PromptLibrary {
        private val templates = ConcurrentHashMap<String, PromptTemplate>().apply { seed?.let { put(it.id, it) } }
        var lastSaved: PromptTemplate? = null
            private set

        override suspend fun buscarPorContexto(contextoDeUso: String): List<PromptTemplate> = templates.values.toList()

        override suspend fun salvarNovaVersao(template: PromptTemplate): String {
            lastSaved = template
            templates[template.id] = template
            return template.id
        }

        override suspend fun registrarResultado(templateId: String, sucesso: Boolean, custo: Double, tempoMs: Long) = Unit

        override suspend fun aposentar(templateId: String): Boolean = templates.remove(templateId) != null
    }

    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process =
            ProcessBuilder(command).directory(File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }).start()
    }
}

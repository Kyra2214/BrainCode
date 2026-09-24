package com.brain.e2e

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityDiscovery
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry
import com.brain.dispatch.DispatchTask
import com.brain.dispatch.DispatchStatus
import com.brain.dispatch.Dispatcher
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionGateway
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.job.DurableJobRunner
import com.brain.job.JobStatus
import com.brain.job.JobStore
import com.brain.memory.ConservativeKnowledgeCritic
import com.brain.memory.InMemoryKnowledgeMemory
import com.brain.memory.KnowledgeCompiler
import com.brain.memory.KnowledgeEvidence
import com.brain.memory.KnowledgeLearningCycle
import com.brain.memory.KnowledgeSource
import com.brain.memory.SkillCandidate
import com.brain.observability.ExecutionTrace
import com.brain.observability.InMemoryTraceSink
import com.brain.planner.KeywordPlanner
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import com.brain.retrieval.Retrieval
import com.brain.retrieval.RetrievalHit
import com.brain.retrieval.RetrievalLayer
import com.brain.retrieval.RetrievalLookup
import com.brain.retrieval.RetrievalQuery
import com.brain.retrieval.RetrievalSource
import com.brain.skill.SkillManifest
import com.brain.skill.SkillRegistry
import com.brain.skill.SkillValidationEvidence
import com.brain.skill.SkillValidator
import com.brain.skill.TrustLevel
import com.brain.workflow.WorkflowEngine
import com.brain.workflow.WorkflowManifest
import com.brain.workflow.WorkflowNode
import com.brain.workflow.WorkflowStepResult
import java.nio.file.Files
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainEndToEndTest {
    @Test
    fun `fluxo completo atravessa brain retrieval policy gateway workflow job evidence knowledge e skill`() {
        val jobFile = Files.createTempFile("brain-e2e-job", ".json").toFile()
        try {
            val memory = InMemoryKnowledgeMemory()
            val capability = CapabilityDefinition(
                id = "tool.research",
                name = "Research Tool",
                description = "ferramenta bounded de pesquisa",
                category = CapabilityCategory.TOOL,
                ownerId = "tool.owner",
                origin = "e2e",
                providedCapabilities = setOf("network.research"),
                supportsWeb = true,
                availability = CapabilityAvailability.AVAILABLE,
                reliability = .95,
                quality = .9,
                provenance = listOf(CapabilityProvenance("e2e", "test"))
            )
            val registry = CapabilityRegistry(listOf(capability))
            val policy = PolicyBroker(actorCapabilities = mapOf("brain" to listOf("tool.research")))
                .withCapabilityRegistry(registry)
            val audit = InMemoryActionAuditLog()
            val traceSink = InMemoryTraceSink()
            val gateway = ActionGateway(
                registry = registry,
                policy = policy,
                executor = { _, _, _ ->
                    ActionExecution(
                        success = true,
                        result = "resultado de pesquisa",
                        evidence = listOf("source:https://example.test/research"),
                        provenance = listOf("provider:e2e")
                    )
                },
                audit = audit,
                trace = ExecutionTrace(traceSink)
            )
            val dispatcher = Dispatcher(CapabilityDiscovery(registry), gateway)
            val planner = KeywordPlanner()
            val plan = runSuspend { planner.planejar("pesquisar referências") }
            val context = PolicyContext(
                runId = "run-e2e",
                taskId = "task-e2e",
                actor = "brain",
                riskClass = plan.passos.single().riskClass,
                sandboxRequired = true,
                networkAllowed = true
            )

            val retrieval = Retrieval(
                listOf(
                    RetrievalSource("memory", RetrievalLayer.MEMORY, RetrievalLookup { query ->
                        memory.findValidated(query.objective)?.let { entry ->
                            listOf(RetrievalHit(entry.id, RetrievalLayer.MEMORY, entry.answer, entry.confidence, true, listOf("memory:${entry.id}")))
                        }.orEmpty()
                    }),
                    RetrievalSource("knowledge", RetrievalLayer.KNOWLEDGE, RetrievalLookup { emptyList() }),
                    RetrievalSource("skills", RetrievalLayer.SKILLS, RetrievalLookup { emptyList() }),
                    RetrievalSource("apis", RetrievalLayer.APIS, RetrievalLookup { emptyList() })
                )
            )
            val initialRetrieval = retrieval.retrieve(RetrievalQuery("pesquisar referências"))
            assertEquals(com.brain.retrieval.RetrievalStatus.NOT_FOUND, initialRetrieval.status)

            val jobStore = JobStore(jobFile)
            val runner = DurableJobRunner(jobStore, WorkflowEngine())
            val workflowResult = runner.run(
                jobId = "job-e2e",
                runId = "run-e2e",
                taskId = "task-e2e",
                manifest = WorkflowManifest("research-workflow", "1.0.0", listOf(WorkflowNode("research", "tool.research"))),
                authorize = { it == "tool.research" },
                execute = { node, attempt ->
                    val dispatched = dispatcher.dispatch(DispatchTask("task-e2e", plan.passos.single(), "brain", context))
                    assertEquals(DispatchStatus.DISPATCHED, dispatched.status)
                    WorkflowStepResult(node.id, dispatched.gateway?.success == true, attempt, evidence = dispatched.gateway?.execution?.evidence.orEmpty())
                }
            )
            assertEquals(JobStatus.SUCCEEDED, workflowResult.status)
            assertTrue(workflowResult.evidence.any { it.startsWith("source:") })

            val compiler = KnowledgeCompiler(KnowledgeLearningCycle(memory), ConservativeKnowledgeCritic())
            val compilation = compiler.compile(
                KnowledgeEvidence(
                    evidenceId = "evidence-e2e",
                    problem = "pesquisar referências",
                    answer = "resultado de pesquisa",
                    source = KnowledgeSource(type = "web", uri = "https://example.test/research"),
                    retrievalHints = listOf("pesquisa", "referências"),
                    providerConfidence = .9,
                    provenance = workflowResult.evidence
                )
            )
            assertEquals(com.brain.memory.KnowledgeCompilationStatus.VALIDATED, compilation.status)

            val skillRegistry = SkillRegistry()
            val manifest = SkillManifest(
                id = "e2e.research",
                name = "E2E Research",
                version = "1.0.0",
                description = "skill validada pelo fluxo E2E",
                category = "research",
                capabilities = setOf("tool.research"),
                trustLevel = TrustLevel.CORE
            )
            val candidate = compiler.proposeSkill(compilation, manifest)!!
            val validator = SkillValidator(
                sandbox = { skill, _ -> SkillValidationEvidence(skill.id, true, true, false, listOf("sandbox:e2e", "test:e2e")) },
                critic = { it.evidence.size == 2 }
            )
            val validation = validator.validate(candidate)
            assertTrue(validation.passed)
            validator.promote(candidate, validation, skillRegistry)
            assertTrue(skillRegistry.isUsable("e2e.research"))

            val finalRetrieval = Retrieval(
                listOf(
                    RetrievalSource("memory", RetrievalLayer.MEMORY, RetrievalLookup { emptyList() }),
                    com.brain.retrieval.RetrievalAdapters.knowledge { memory.all() },
                    RetrievalSource("skills", RetrievalLayer.SKILLS, RetrievalLookup { emptyList() }),
                    RetrievalSource("apis", RetrievalLayer.APIS, RetrievalLookup { emptyList() })
                )
            ).retrieve(RetrievalQuery("pesquisar referências"))
            assertEquals(com.brain.retrieval.RetrievalStatus.FOUND, finalRetrieval.status)
            assertEquals(RetrievalLayer.KNOWLEDGE, finalRetrieval.hit?.layer)
            assertTrue(finalRetrieval.attemptedLayers.contains(RetrievalLayer.KNOWLEDGE))
            assertTrue(!finalRetrieval.attemptedLayers.contains(RetrievalLayer.APIS))
            assertEquals(1, audit.all().size)
            assertTrue(traceSink.all().any { it.stage == com.brain.observability.TraceStage.POLICY })
            assertEquals(JobStatus.SUCCEEDED, JobStore(jobFile).get("job-e2e")?.status)
        } finally {
            jobFile.delete()
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                result.onSuccess { value = it }.onFailure { failure = it }
            }
        })
        failure?.let { throw it }
        return requireNotNull(value)
    }
}

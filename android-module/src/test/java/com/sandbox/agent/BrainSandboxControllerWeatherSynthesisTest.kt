package com.sandbox.agent

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityProvider
import com.brain.execution.RiskClass
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.secretary.UserResponse
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressão: clima usa `weather` e entrega a resposta do provider dedicado sem etapa de síntese. */
class BrainSandboxControllerWeatherSynthesisTest {
    @Test
    fun `pergunta de clima executa capability weather e entrega resposta deterministica`() {
        val root = Files.createTempDirectory("weather-synthesis-").toFile()
        try {
            var weatherCalls = 0
            var researchCalls = 0
            val fakeWeather = ActionExecutor { request, _, _ ->
                weatherCalls++
                ActionExecution(
                    success = true,
                    result = "Agora em Macaé - Rio de Janeiro: 28°C, parcialmente nublado.",
                    evidence = listOf("api:open-meteo", "url:https://api.open-meteo.com/v1/forecast"),
                    userResponse = UserResponse(
                        text = "Agora em Macaé - Rio de Janeiro: 28°C, parcialmente nublado.",
                        evidence = listOf("api:open-meteo"),
                        requestId = request.actionId
                    )
                )
            }
            val fakeResearch = ActionExecutor { _, _, _ -> researchCalls++; ActionExecution(success = true, result = "não deveria consultar") }
            val weatherDefinition = CapabilityDefinition(
                id = "weather", name = "Clima", description = "Clima determinístico via Open-Meteo",
                category = CapabilityCategory.API, ownerId = "test-weather", origin = "test-weather",
                providedCapabilities = setOf("weather"), risk = RiskClass.LOW,
                availability = CapabilityAvailability.AVAILABLE,
                provenance = listOf(CapabilityProvenance("test-weather", "unit-test"))
            )
            val weatherProvider = object : CapabilityProvider {
                override val providerId = "test-weather"
                override fun capabilities() = sequenceOf(weatherDefinition)
            }
            val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-weather")
            val controller = BrainSandboxController(
                runtime = runtime,
                rootfsDir = root,
                capabilityProviders = listOf(weatherProvider),
                capabilityExecutors = mapOf(
                    "weather" to fakeWeather,
                    "network.research" to fakeResearch
                )
            )

            val cycle = controller.executeObjective("Qual o tempo em Macaé RJ", "run-weather")

            assertTrue("ciclo deveria aprovar a consulta meteorológica", cycle.aprovado)
            assertEquals(listOf("weather"), cycle.passos.map { it.capacidade })
            assertNotNull("resposta meteorológica não pode ficar nula", cycle.resposta)
            assertEquals("Agora em Macaé - Rio de Janeiro: 28°C, parcialmente nublado.", cycle.resposta)
            assertEquals(1, weatherCalls)
            assertEquals(0, researchCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process {
            val hostDir = File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }
            return ProcessBuilder(command).directory(hostDir).start()
        }
    }
}

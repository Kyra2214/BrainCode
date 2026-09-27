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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressão: dados do provider são internos até a resposta passar pelo Secretário. */
class BrainSandboxControllerWeatherSynthesisTest {
    @Test
    fun `pergunta de clima executa capability weather e entrega resposta deterministica`() {
        val root = Files.createTempDirectory("weather-synthesis-").toFile()
        try {
            var weatherCalls = 0
            var researchCalls = 0
            val fakeWeather = ActionExecutor { _, _, _ ->
                weatherCalls++
                ActionExecution(
                    success = true,
                    result = "Agora em Macaé - Rio de Janeiro: 28°C, parcialmente nublado.",
                    evidence = listOf("api:open-meteo", "url:https://api.open-meteo.com/v1/forecast", "data:{\"temperature_2m\":28.0}")
                )
            }
            val fakeSecretary = ActionExecutor { request, _, _ ->
                val text = request.parameters["parameter.1"].orEmpty()
                val evidence = listOf("chat:request:${request.actionId}", "chat:secretary:accept")
                ActionExecution(true, result = text, evidence = evidence, userResponse = UserResponse(text, evidence, request.actionId))
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
                    "network.research" to fakeResearch,
                    "chat.respond" to fakeSecretary
                )
            )

            val cycle = controller.executeObjective("Qual o tempo em Macaé RJ", "run-weather")

            assertTrue(
                "ciclo deveria aprovar a consulta meteorológica: posExecucao=${cycle.posExecucao?.let { gate ->
                    "verification=${gate.verification.status}, critique=${gate.critique.status}, revision=${gate.revision.action}, readiness=${gate.readiness.status}, issues=${gate.issues}, findings=${gate.critique.findings.map { finding -> finding.code }}"
                }}",
                cycle.aprovado
            )
            assertEquals(listOf("weather", "chat.respond"), cycle.passos.map { it.capacidade })
            assertNotNull("resposta meteorológica não pode ficar nula", cycle.resposta)
            assertEquals("Agora em Macaé - Rio de Janeiro: 28°C, parcialmente nublado.", cycle.resposta)
            assertEquals(1, weatherCalls)
            assertEquals(0, researchCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resposta direta do provider nao chega a UI se o secretario nao aceitar`() {
        val root = Files.createTempDirectory("weather-secretary-block-").toFile()
        try {
            val directResponse = "Agora em Macaé - Rio de Janeiro: 28°C, parcialmente nublado."
            val fakeWeather = ActionExecutor { request, _, _ ->
                val evidence = listOf("api:open-meteo", "url:https://api.open-meteo.com/v1/forecast", "data:{\"temperature_2m\":28.0}")
                ActionExecution(true, result = directResponse, evidence = evidence,
                    userResponse = UserResponse(directResponse, evidence, request.actionId))
            }
            val rejectingSecretary = ActionExecutor { _, _, _ -> ActionExecution(false, error = "rejeitada") }
            val weatherDefinition = CapabilityDefinition(
                id = "weather", name = "Clima", description = "Clima determinístico",
                category = CapabilityCategory.API, ownerId = "test-weather", origin = "test-weather",
                providedCapabilities = setOf("weather"), risk = RiskClass.LOW,
                availability = CapabilityAvailability.AVAILABLE,
                provenance = listOf(CapabilityProvenance("test-weather", "unit-test"))
            )
            val provider = object : CapabilityProvider {
                override val providerId = "test-weather"
                override fun capabilities() = sequenceOf(weatherDefinition)
            }
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-weather-block"),
                rootfsDir = root,
                capabilityProviders = listOf(provider),
                capabilityExecutors = mapOf("weather" to fakeWeather, "chat.respond" to rejectingSecretary)
            )

            val cycle = controller.executeObjective("Qual o tempo em Macaé RJ", "run-weather-block")

            assertFalse(cycle.aprovado)
            assertEquals(null, cycle.resposta)
            val weatherStep = cycle.passos.single { it.capacidade == "weather" }
            assertEquals(directResponse, weatherStep.resultado)
            assertEquals(null, weatherStep.userResponse)
            assertTrue(cycle.passos.any { it.capacidade == "chat.respond" && it.status == StatusPasso.REPROVADO })
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

package com.brain.qa

import java.io.File

data class ComandosDescobertos(
    val stack: Stack,
    val comandos: List<String>,
    val indisponiveis: List<String> = emptyList()
)

/**
 * Descobre quais comandos de validação rodar, a partir da stack
 * detectada e das ferramentas realmente disponíveis no ambiente —
 * nunca assume que um comando existe só porque a stack sugere ele.
 * Adaptado de ProjectValidationCommandDiscovery (IaBrain, brain/ProjectValidationExecutor.kt).
 */
object DescobertaComandosValidacao {

    private val executaveisSeguros = setOf("./gradlew", "gradlew", "npm", "yarn", "pnpm", "pytest", "python", "python3", "mvn", "./mvnw")

    fun descobrir(raiz: File, ferramentasDisponiveis: Set<String> = detectarFerramentas()): ComandosDescobertos {
        val caminhos = raiz.walkTopDown().filter { it.isFile }.map { it.relativeTo(raiz).path }.toList()
        val deteccao = DetectorDeStack.detectar(caminhos)
        val comandos = mutableListOf<String>()
        val indisponiveis = mutableListOf<String>()

        when (deteccao.stack) {
            Stack.ANDROID_KOTLIN -> {
                val wrapper = File(raiz, "gradlew")
                if (wrapper.isFile && ("gradlew" in ferramentasDisponiveis || "./gradlew" in ferramentasDisponiveis)) {
                    comandos += "./gradlew test"
                } else {
                    indisponiveis += "./gradlew test"
                }
            }
            Stack.REACT_TYPESCRIPT -> {
                val packageJson = File(raiz, "package.json").takeIf { it.isFile }?.readText().orEmpty()
                val scripts = Regex("\"(test|build|typecheck|lint)\"\\s*:").findAll(packageJson).map { it.groupValues[1] }.toList()
                scripts.forEach { script ->
                    if ("npm" in ferramentasDisponiveis) comandos += "npm run $script" else indisponiveis += "npm run $script"
                }
            }
            Stack.MAVEN_JAVA -> when {
                File(raiz, "mvnw").isFile && "mvnw" in ferramentasDisponiveis -> comandos += "./mvnw test"
                "mvn" in ferramentasDisponiveis -> comandos += "mvn test"
                else -> indisponiveis += "mvn test"
            }
            Stack.PYTHON -> if ("pytest" in ferramentasDisponiveis && (File(raiz, "pytest.ini").isFile || File(raiz, "pyproject.toml").isFile)) {
                comandos += "pytest"
            } else {
                indisponiveis += "pytest"
            }
            Stack.DESCONHECIDA -> indisponiveis += "stack desconhecida; nenhum comando determinável"
        }

        return ComandosDescobertos(deteccao.stack, comandos.distinct(), indisponiveis.distinct())
    }

    fun detectarFerramentas(): Set<String> = executaveisSeguros.filter(::disponivel).toSet()

    private fun disponivel(comando: String): Boolean = try {
        val probe = if (comando.startsWith("./")) comando.removePrefix("./") else comando
        val processo = ProcessBuilder(probe, "--version").redirectErrorStream(true).start()
        aguardarSaidaDoProcesso(processo, 2_000L) == 0
    } catch (_: Exception) {
        false
    }
}

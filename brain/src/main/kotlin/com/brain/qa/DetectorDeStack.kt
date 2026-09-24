package com.brain.qa

/**
 * Pacote qa — Parte 7 do fluxo de 8 partes (QA/validação do resultado
 * antes de aprovar a tarefa). Ainda não existia no esqueleto original;
 * criado agora reaproveitando ProjectStackDetection.kt e
 * ProjectValidationExecutor.kt do IaBrain (brain/), que já eram lógica
 * pura de JVM (File/ProcessBuilder), sem dependência de Android.
 *
 * Detecção determinística baseada apenas em arquivos realmente
 * presentes no projeto — adaptado de ProjectStackDetector.
 */
enum class Stack { ANDROID_KOTLIN, REACT_TYPESCRIPT, MAVEN_JAVA, PYTHON, DESCONHECIDA }

data class DeteccaoStack(
    val stack: Stack,
    val marcadores: Set<String>,
    val comandosTeste: List<String>,
    val comandoBuild: String?
)

object DetectorDeStack {

    fun detectar(caminhos: Collection<String>): DeteccaoStack {
        val normalizados = caminhos.map { it.replace('\\', '/').lowercase() }.toSet()
        fun tem(nome: String) = normalizados.any { it.substringAfterLast('/') == nome }

        return when {
            tem("gradlew") || tem("build.gradle") || tem("build.gradle.kts") -> DeteccaoStack(
                Stack.ANDROID_KOTLIN,
                normalizados.filter {
                    it.substringAfterLast('/') in setOf("gradlew", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                }.toSet(),
                listOf("./gradlew test"),
                "./gradlew assembleDebug"
            )
            tem("package.json") -> DeteccaoStack(Stack.REACT_TYPESCRIPT, setOf("package.json"), listOf("npm test"), "npm run build")
            tem("pom.xml") -> DeteccaoStack(
                Stack.MAVEN_JAVA,
                setOf("pom.xml"),
                listOf("./mvnw test", "mvn test").filter { comando -> (comando.startsWith("./mvnw") && tem("mvnw")) || comando == "mvn test" },
                "mvn package"
            )
            tem("pyproject.toml") || tem("requirements.txt") -> DeteccaoStack(
                Stack.PYTHON,
                setOf("pyproject.toml", "requirements.txt").filter(::tem).toSet(),
                listOf("pytest").filter { tem("pytest.ini") || tem("pyproject.toml") },
                null
            )
            else -> DeteccaoStack(Stack.DESCONHECIDA, emptySet(), emptyList(), null)
        }
    }

    fun podeRodarComando(comando: String, executaveisDisponiveis: Set<String>): Boolean =
        comando.substringBefore(' ').substringAfterLast('/') in executaveisDisponiveis
}

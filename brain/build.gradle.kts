// :brain — código Kotlin do BraimCode (IaBrain), trazido do repositório
// BraimCode original (pasta app/src/main/kotlin/com/brain/). É Kotlin/JVM
// puro: nenhum arquivo aqui importa android.* (verificado na migração), por
// isso não precisa do plugin com.android.library — compila mais rápido e
// pode, em tese, ser reaproveitado fora do Android no futuro.
//
// Quem consome este módulo é :android-module, que implementa a ponte real
// entre o contrato Job/JobResult (com.brain.execution.SandboxContract) e o
// SandboxRuntime real (proot). Essa ponte é a Etapa 2/3 do plano de
// integração (docs/PLANO_INTEGRACAO_BRAIN_SANDBOX.md) e ainda não foi
// escrita nesta rodada — só a fundação de compilação.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // org.json é o que ApiCatalogLoader/PromptLibraryLoader já usavam no
    // BraimCode original. No Android ele vem embutido no SDK; aqui, como
    // módulo JVM puro, precisa ser declarado explicitamente.
    implementation("org.json:json:20240303")

    // PromptOutcomeTracker usa runBlocking para ligar o callback síncrono
    // do ciclo de execução à API suspend da PromptLibrary.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Alguns testes do módulo usam kotlin.test.*. Declarar kotlin-test aqui
    // evita que um teste introduza uma API de teste sem a dependência do
    // módulo JVM correspondente.
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

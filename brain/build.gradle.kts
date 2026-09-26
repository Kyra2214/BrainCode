// :brain — código Kotlin do BraimCode (IaBrain), trazido do repositório
// BraimCode original (pasta app/src/main/kotlin/com/brain/). É Kotlin/JVM
// puro: nenhum arquivo aqui importa android.* (verificado na migração), por
// isso não precisa do plugin com.android.library — compila mais rápido e
// pode, em tese, ser reaproveitado fora do Android no futuro.
//
// Quem consome este módulo é :android-module, que implementa a ponte real
// entre o contrato Job/JobResult (com.brain.execution.SandboxContract) e o
// SandboxRuntime real (proot). Essa ponte já está escrita (BrainSandboxController,
// ActionGateway, CicloExecucaoPlano etc. — ver docs/ARQUITETURA_ATUAL.md, §4);
// este comentário é histórico da fundação inicial de compilação do módulo.
// O plano de integração original (docs/PLANO_INTEGRACAO_BRAIN_SANDBOX.md) foi
// consolidado em docs/ARQUITETURA_ATUAL.md e docs/LEGADO_E_DECISOES.md e não
// existe mais como arquivo separado.
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
    // org.json é o que o ApiCatalogLoader (e outros loaders) já usavam no
    // BraimCode original. No Android ele vem embutido no SDK; aqui, como
    // módulo JVM puro, precisa ser declarado explicitamente.
    implementation("org.json:json:20240303")

    // PromptOutcomeTracker usa runBlocking para ligar o callback síncrono
    // do ciclo de execução à API suspend da PromptLibrary.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Alguns testes do módulo usam kotlin.test.*. Declarar kotlin-test aqui
    // evita que um teste introduza uma API de teste sem a dependência do
    // módulo JVM correspondente.
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

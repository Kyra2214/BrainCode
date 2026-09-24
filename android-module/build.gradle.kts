plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sandbox"
    compileSdk = 34

    defaultConfig {
        minSdk = 26 // ProcessBuilder + symlinks reais exigem API razoavelmente moderna
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libproot.so"
            keepDebugSymbols += "**/libapp_proot_loader.so"
            keepDebugSymbols += "**/libandroid-shmem.so"
            keepDebugSymbols += "**/libtalloc.so"
        }
    }

}

dependencies {
    // Só para extrair TAR (Android não tem suporte nativo). Apache 2.0,
    // biblioteca pública — não é código de terceiro proprietário.
    implementation("org.apache.commons:commons-compress:1.26.1")

    // :brain traz o contrato Job/JobResult (com.brain.execution) e a
    // lógica de Policy/Router/Prompt do BraimCode. A implementação real
    // de SandboxExecutor que chama SandboxRuntime.execute() por baixo
    // (Etapa 2/3 do plano de integração) ainda não foi escrita — esta
    // dependência só prepara o terreno de compilação para ela.
    implementation(project(":brain"))

    testImplementation("junit:junit:4.13.2")
}

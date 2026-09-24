// Versões dos plugins centralizadas aqui, aplicadas por módulo.
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("com.android.library") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Usado só pelo :brain (Kotlin/JVM puro, sem Android) — mesma versão
    // do plugin Kotlin já usada nos módulos Android, para evitar
    // divergência de versão do compilador Kotlin dentro do projeto.
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
}

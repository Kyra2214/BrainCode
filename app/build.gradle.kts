plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val configuredVersionCode = System.getenv("BRAINCODE_VERSION_CODE")?.toIntOrNull()
val resolvedVersionCode = maxOf(7, configuredVersionCode ?: ciVersionCode ?: 7)

android {
    namespace = "com.sandbox.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sandbox.app"
        minSdk = 26
        targetSdk = 28
        versionCode = resolvedVersionCode
        versionName = "0.5.0-dev.$resolvedVersionCode"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val signingStoreFile = System.getenv("BRAINCODE_KEYSTORE_FILE")
    val signingStorePassword = System.getenv("BRAINCODE_KEYSTORE_PASSWORD")
    val signingKeyAlias = System.getenv("BRAINCODE_KEY_ALIAS")
    val signingKeyPassword = System.getenv("BRAINCODE_KEY_PASSWORD")

    if (!signingStoreFile.isNullOrBlank() && !signingStorePassword.isNullOrBlank() && !signingKeyAlias.isNullOrBlank() && !signingKeyPassword.isNullOrBlank()) {
        signingConfigs.create("devCi") {
            storeFile = file(signingStoreFile)
            storePassword = signingStorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    }

    buildTypes {
        getByName("debug") {
            if (signingConfigs.findByName("devCi") != null) signingConfig = signingConfigs.getByName("devCi")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    lint { disable += "ExpiredTargetSdkVersion" }
}

dependencies {
    implementation(project(":android-module"))
    implementation(project(":brain"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

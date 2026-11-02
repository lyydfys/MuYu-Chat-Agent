plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muyuchat.api.dsh.gateway"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // The completion runtime-closure test hashes the packaged payload from the repository
            // root; the value is a path, never a credential.
            it.systemProperty(
                "dsh.completion.repo",
                providers.gradleProperty("dshCompletionRepo").orElse(rootDir.absolutePath).get()
            )
        }
    }
}

// R02/R03 internal DSH model gateway: a narrow OpenAI-compatible protocol adapter around an
// app-owned DshGatewayBackend. It owns no engine, no Room access and no cloud credential; the
// loopback HTTP server is plain ServerSocket because Android has no com.sun.net.httpserver.
dependencies {
    api(project(":core:dsh-contract"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

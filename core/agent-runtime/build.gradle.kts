plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muyuchat.core.agent.runtime"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Agent V1 runtime: pure Kotlin orchestration, state machine, budget, protocol and validation.
// No android/app/engine/native/Room/OkHttp dependencies are allowed here; see the architecture gate.
dependencies {
    api(project(":core:agent-contract"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

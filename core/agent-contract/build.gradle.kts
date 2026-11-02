plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muyuchat.core.agent.contract"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Agent V1 contract module: stable records, errors and ports only. It must stay platform-free
// (no android/androidx imports); see tools/architecture/check_architecture_rules.py.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

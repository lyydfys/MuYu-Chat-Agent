plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muyuchat.core.video.contract"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Video workflow contract module (proposal mca-native-video-workflow-v1 §3.1): versioned graph
// types, run/attempt identity, snapshot, state table, errors and the narrow VideoWorkflowPort
// shared by feature:video-workspace, the app-side owner and workers. It must stay platform-free
// (no android/androidx imports); see tools/architecture/check_architecture_rules.py Rule 18.
dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

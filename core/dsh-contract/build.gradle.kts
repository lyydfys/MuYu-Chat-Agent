plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muyuchat.core.dsh.contract"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// DSH V1 contract module: stable records and ports shared by the Android runtime owner,
// the model gateway, the workspace UI and the app composition root. It must stay
// platform-free (no android/androidx imports); see tools/architecture/check_architecture_rules.py.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    // JVM contract tests drive suspend capability calls with runBlocking/CompletableDeferred.
    testImplementation(libs.kotlinx.coroutines.core)
}

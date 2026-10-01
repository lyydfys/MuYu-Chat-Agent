plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.muyuchat.core.designsystem"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Shared Compose primitives that more than one module needs.
//
// It exists because two functions were duplicated byte for byte across modules that cannot
// depend on each other: SystemBackMotionHandler in app and feature:chat, and
// SmoothRightToLeftPage in feature:agent and feature:modelhub. feature modules do not depend
// on app, so there was nowhere for either to live.
//
// Dependencies are declared explicitly rather than inherited through material3, which is how
// the feature modules happen to get foundation and animation today.
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.muyuchat.feature.settings"
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

// G00/Q01: Compose compiler reports + metrics for stability/jank triage (diagnostics only; the
// emitted runtime behavior is unchanged). `-Pmca.composeCompilerReports=false` skips generating
// them for a build that only needs an artifact; the default keeps them enabled.
val composeCompilerReportsEnabled = providers.gradleProperty("mca.composeCompilerReports")
    .map { value -> !value.trim().equals("false", ignoreCase = true) }
    .orElse(true)
composeCompiler {
    if (composeCompilerReportsEnabled.get()) {
        reportsDestination = layout.buildDirectory.dir("compose-compiler/reports")
        metricsDestination = layout.buildDirectory.dir("compose-compiler/metrics")
    }
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:telemetry"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

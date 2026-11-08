plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Screenshot (golden) baselines for the settings hub. Same setup as :core:designsystem, which
    // owns the shared golden infrastructure (see its golden/GOLDEN.md). Kept module-local on purpose:
    // the plugin registers its tasks per module, so only this module gets
    // :feature:settings:recordRoborazziDebug / :feature:settings:verifyRoborazziDebug.
    alias(libs.plugins.roborazzi)
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

    testOptions {
        unitTests {
            // Robolectric renders the real Compose surface, which needs the merged resources and
            // manifest of this module's debug variant.
            isIncludeAndroidResources = true
        }
    }
}

// JDK 21 module access for Robolectric -- identical to :core:designsystem, where each flag was
// derived from a real failure trace (see that module's golden/GOLDEN.md section 5.2):
//  * --add-exports java.base/jdk.internal.access : Robolectric's FileDescriptorInterceptor calls
//    SharedSecrets.getJavaIOFileDescriptorAccess() reflectively. Since Android 16 (API 36) that path
//    is hit during application setup, so EVERY Robolectric test in the module fails without it, with
//    "Failed to interact with raw FileDescriptor internals; perhaps JRE has changed?".
//  * --add-opens java.base/java.io : the same interceptor's field-moving path reads JDK-private
//    FileDescriptor fields (fd/closed/parent/otherParents/handle) via setAccessible.
// Deliberately the same two flags as :core:designsystem so both modules' goldens run on one recipe.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED"
    )
}

// Committed baselines for this module. The plugin forwards this as `roborazzi.output.dir` to the
// test JVM, and SettingsGoldenHarness reads that property, so the harness never depends on the
// working directory.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

// G00/Q01: Compose compiler reports + metrics for stability/jank triage (diagnostics only; the
// emitted runtime behavior is unchanged). `-Pmca.composeCompilerReports=false` skips generating
// them for a build that only needs an artifact; the default keeps them enabled.
val composeCompilerReportsEnabled = providers.gradleProperty("mca.composeCompilerReports")
    .map { value -> !value.trim().equals("false", ignoreCase = true) }
    .orElse(true)
composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("config/compose/stability.conf"))
    if (composeCompilerReportsEnabled.get()) {
        reportsDestination = layout.buildDirectory.dir("compose-compiler/reports")
        metricsDestination = layout.buildDirectory.dir("compose-compiler/metrics")
    }
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:telemetry"))
    // Shared motion language (McaMotion / mcaPressScale) used by the settings surfaces.
    implementation(project(":core:designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Golden screenshots (see src/test/java/com/muyuchat/feature/settings/golden/GOLDEN.md).
    // Task names for this module:
    //   record : gradlew.bat :feature:settings:recordRoborazziDebug
    //   verify : gradlew.bat :feature:settings:verifyRoborazziDebug
    // As in :core:designsystem, the captures are inert under a plain :feature:settings:testDebugUnitTest:
    // Roborazzi only captures when one of those tasks sets roborazzi.test.record/verify.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

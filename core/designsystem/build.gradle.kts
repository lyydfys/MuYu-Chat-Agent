plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Screenshot (golden) baselines. Applied here and nowhere else, on purpose:
    //   * the plugin registers its tasks per module, so only this module gets
    //     :core:designsystem:recordRoborazziDebug / :core:designsystem:verifyRoborazziDebug and the
    //     other modules keep the exact task graph and build cost they had;
    //   * it also keeps the plugin classpath off the root build, so no other module resolves it.
    // The goldens cover the two stateless design-system surfaces that have no Activity or store
    // dependency (onboarding pages, appearance section). Workflow and review rules:
    // src/test/java/com/muyuchat/core/designsystem/golden/GOLDEN.md
    alias(libs.plugins.roborazzi)
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

    testOptions {
        unitTests {
            // Robolectric renders the real Compose surface, which needs the merged resources and
            // manifest of this module's debug variant. Without it the goldens fail to inflate.
            isIncludeAndroidResources = true
        }
    }
}

// Where the committed baselines live. This directory is the golden set itself: it is reviewed in a
// diff, not regenerated blindly. The plugin turns this into the `roborazzi.output.dir` system
// property it hands to the test JVM, and GoldenHarness reads that property so the harness never
// depends on the JVM working directory.
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
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Golden screenshots (see src/test/java/com/muyuchat/core/designsystem/golden/GOLDEN.md).
    //
    // How to run them, as whole-module tasks:
    //   record : gradlew.bat :core:designsystem:recordRoborazziDebug    (writes/updates the PNGs)
    //   verify : gradlew.bat :core:designsystem:verifyRoborazziDebug    (fails on pixel drift)
    //   diff   : gradlew.bat :core:designsystem:compareRoborazziDebug   (writes _compare images)
    //   reset  : gradlew.bat :core:designsystem:clearRoborazziDebug     (deletes every baseline)
    // A single test class can be narrowed with --tests, e.g.
    //   gradlew.bat :core:designsystem:recordRoborazziDebug --tests '*OnboardingGoldenTest'
    //
    // These captures are inert under a plain `:core:designsystem:testDebugUnitTest`: Roborazzi only
    // captures when one of the tasks above sets roborazzi.test.record/verify/compare, so a green
    // testDebugUnitTest does NOT mean the goldens matched.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

// G00/Q01: UI Macrobenchmark harness for the real production MainActivity.
//
// This module is OPT-IN at the settings level (`-Pmca.includeBenchmark=true` or
// MCA_INCLUDE_BENCHMARK=true) because its androidx.benchmark artifacts are not part of the
// default build's local dependency cache. It targets :app's release-like `benchmark` build type
// (non-debuggable, R8/shrinking enabled, debug-signed, applicationIdSuffix ".benchmark") and must
// never be wired into the default verify-code path.
android {
    namespace = "com.muyuchat.mca.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // Run the benchmark in its own process so the benchmark instrumentation cannot perturb the
    // process being measured (same setting the Android Studio Macrobenchmark template uses).
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // com.android.test exposes debug/release; initialise from release when present, else from
        // debug, so this module configures on both AGP test-module layouts. The test APK itself is
        // never shipped, so this choice does not change what gets measured in :app.
        val baseTestBuildType = findByName("release") ?: getByName("debug")
        create("benchmark") {
            initWith(baseTestBuildType)
            // Multi-module apps: match the target app's `benchmark` build type while other
            // library modules fall back to their release variants (AGP Macrobenchmark setup).
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    // androidx.test.uiautomator is supplied transitively by benchmark-macro: 1.5.0 depends on
    // 2.4.0, which is required by MacrobenchmarkScope (UiAutomatorTestScope). Do not pin the
    // catalog's local-cache version 2.3.0 here. First resolution of these artifacts requires
    // network access; see docs/proposals/audit/v9/MEASUREMENT-FACILITY.md.
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MuYuChatAgent"

include(":app")
include(":core:native")
include(":core:sd-native")
include(":core:inference-contract")
include(":core:engine")
include(":core:modelstore")
include(":core:download")
include(":core:telemetry")
include(":core:deviceprofile")
include(":core:tuning")
include(":core:advisor")
include(":core:benchmark")
include(":core:designsystem")
include(":api:local")
include(":feature:chat")
include(":feature:agent")
include(":feature:modelhub")
include(":feature:settings")

// G00/Q01: UI Macrobenchmark harness. It is a com.android.test module and targets :app's
// `benchmark` build type; it owns no production code and nothing may depend on it.
//
// Opt-in on purpose: the androidx.benchmark artifacts are not part of the default build's local
// dependency cache, so including this module in every configuration would make plain
// `verify-code.ps1` / offline builds fail while resolving them. Enable it explicitly:
//   .\gradlew.bat -Pmca.includeBenchmark=true :benchmark:connectedBenchmarkAndroidTest
// or set MCA_INCLUDE_BENCHMARK=true for the shell. See docs/proposals/audit/v9/MEASUREMENT-FACILITY.md.
val mcaIncludeBenchmark =
    gradle.startParameter.projectProperties["mca.includeBenchmark"]
        ?.trim()?.equals("true", ignoreCase = true) == true ||
        System.getenv("MCA_INCLUDE_BENCHMARK")
            ?.trim()?.equals("true", ignoreCase = true) == true
if (mcaIncludeBenchmark) {
    include(":benchmark")
}


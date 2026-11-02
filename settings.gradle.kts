// The developer host cannot reach dl.google.com / repo.maven.apache.org reliably, so it needs the
// Aliyun mirrors first. GitHub Actions can reach the public repositories, and there a mirror-first
// list made the KSP plugin marker resolve from the mirror while its implementation artifact did not,
// which fails plugin resolution instead of falling back. Keep both environments explicit.
val preferPublicRepositories = System.getenv("CI")?.equals("true", ignoreCase = true) == true

pluginManagement {
    repositories {
        if (preferPublicRepositories) {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        if (!preferPublicRepositories) {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (preferPublicRepositories) {
            google()
            mavenCentral()
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        if (!preferPublicRepositories) {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "MuYuChatAgent"

include(":app")
include(":core:native")
include(":core:sd-native")
include(":core:inference-contract")
// MediaTek NeuroPilot/Neuron contract. The module is SDK-gated and contains no
// vendor binary; native delegate integration is enabled only by a later
// explicitly provisioned SDK build.
include(":core:mediatek-native")
include(":core:agent-contract")
include(":core:agent-runtime")
include(":core:dsh-contract")
include(":core:dsh-runtime")
// DSH V2 (proposal agent-dsh-embedded-v1/completion-v2): the Android capability bridge and its
// five platform domains. Each module owns one OS surface (system actions + privileged channel,
// accessibility service, virtual display, independent browser process) so the Android execution
// layer can be compiled and gated without pulling app/UI code, and so no domain reaches into the
// model gateway or the official DSH business modules.
include(":core:dsh-platform")
include(":core:dsh-android-actions")
include(":core:dsh-a11y")
include(":core:dsh-vscreen")
include(":core:dsh-browser")
// Host-side maintenance (session recycle/repair, backup/restore, safe mode, whole-closure
// upgrade). It depends on the frozen contract only and owns no DSH business state.
include(":core:dsh-maintenance")
include(":api:dsh-gateway")
include(":feature:dsh-workspace")
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
include(":feature:agent-workspace")
include(":feature:modelhub")
include(":feature:settings")

// Native video workflow (proposal mca-native-video-workflow-v1). The contract module is the
// platform-free shared surface (graph types, run identity, snapshot, state table, port); the
// feature module is the Compose workspace (templates, node editor, run views). The process-level
// owner, Room persistence, workers and native adapters stay in app/core and are injected by the
// composition root — neither module here may reach an engine or native code.
include(":core:video-contract")
include(":feature:video-workspace")

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


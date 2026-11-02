plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muyuchat.core.dsh.runtime"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // D12 real PTY + typed command dispatcher. Pure C (no libc++), arm64 only, matching the
        // only ABI the pinned DSH payload ships. The JVM side loads the library best-effort and
        // reports NON_PTY when it is absent, so unit tests never depend on the native build.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The runtime module mixes pure JVM logic with android.util.Log diagnostics (owner, payload,
    // seeder, service). Plain JUnit tests must see the Android stubs as no-ops instead of throwing
    // "not mocked"; this changes test-environment behavior only, not production code.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Android DSH runtime host (R01/R03): payload installation/verification, Node exec host,
// readiness gate, lifecycle owner and the dedicated :dsh foreground/bound service.
// Dependency direction is intentionally narrow: this module may depend on the frozen
// :core:dsh-contract only, never on app/, feature/, engine, native or Room modules.
dependencies {
    api(project(":core:dsh-contract"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json is part of Android but a stub in JVM unit tests; the MCA gateway seeder parses
    // the OpenAI /v1/models catalog with it. A real artifact lets the seeder tests exercise the
    // production parsing code path instead of a mockable default-return stub.
    testImplementation("org.json:json:20240303")
}

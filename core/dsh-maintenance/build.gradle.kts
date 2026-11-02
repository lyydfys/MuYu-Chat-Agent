plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muyuchat.core.dsh.maintenance"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Decisions stay in pure Kotlin policy objects and the Android surface stays in a thin
    // adapter, so plain JUnit tests can exercise the policy without a device. Android stubs
    // returned by the unit-test runtime are no-ops; no test may assert on one as if real.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Opt-in acceptance fixtures (candidate staging) read their inputs from JVM system properties.
// Gradle does not forward arbitrary -D flags to the forked test worker, so pass the `mca.*` ones
// through explicitly; nothing here changes production behaviour.
tasks.withType<Test>().configureEach {
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("mca.") }
        .sorted()
        .forEach { systemProperty(it, System.getProperty(it)) }
}

// Dependency direction (MCA_PROJECT_HARD_LOCK section 4.3): this module may depend on the frozen
// DSH contract only. It must never depend on app/, a feature, the model gateway, the engine or the
// official DSH JavaScript tree.
dependencies {
    api(project(":core:dsh-contract"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
}

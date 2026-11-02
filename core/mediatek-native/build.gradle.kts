import java.io.File

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muyuchat.core.mediatek"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
}

dependencies {
    // LiteRT's MediaTek provider is the public runtime bridge. The matching Neuron
    // implementation is supplied by the device OS; no proprietary SDK binary is
    // bundled by MCA.
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    implementation("com.google.ai.edge.litert:litert-api:2.3.0")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.0")
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val mediatekNativeEnabled = providers.gradleProperty("mcaMediatekNativeEnabled")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val mediatekSdkRoot = providers.gradleProperty("mcaMediatekSdkRoot")
    .orElse(providers.environmentVariable("MCA_MEDIATEK_SDK_ROOT"))

/**
 * Provisioning gate only. The module's own JNI bridge links no vendor library:
 * the Neuron runtime is resolved with dlopen at execution time. A future
 * native implementation must still pass this gate before packaging any vendor
 * binary it decides to link.
 */
tasks.register("verifyMediaTekSdk") {
    group = "verification"
    description = "Reports MediaTek SDK provisioning without linking vendor libraries."
    doLast {
        val rawRoot = mediatekSdkRoot.orNull?.trim().orEmpty()
        if (rawRoot.isBlank()) {
            logger.lifecycle("MediaTek NeuroPilot SDK: BLOCKED (set MCA_MEDIATEK_SDK_ROOT or -PmcaMediatekSdkRoot)")
            if (mediatekNativeEnabled.get()) {
                throw GradleException("MediaTek native integration was enabled but no SDK root was provided.")
            }
            return@doLast
        }
        val root = File(rawRoot)
        val markers = listOf(
            root.resolve("neuron-sdk.properties"),
            root.resolve("VERSION"),
            root.resolve("version.txt"),
            root.resolve("sdk.version"),
            root.resolve("include/NeuroPilot/Neuron.h"),
            root.resolve("include/neuron/neuron.h"),
        )
        val markerFound = root.isDirectory && (
            markers.any(File::isFile) ||
                root.resolve("lib").listFiles().orEmpty().any {
                    it.isFile && it.name.startsWith("libneuron", ignoreCase = true)
                } ||
                root.walkTopDown().maxDepth(3).any {
                    it.isFile && it.extension.equals("aar", ignoreCase = true)
                }
            )
        if (!markerFound) {
            logger.lifecycle("MediaTek NeuroPilot SDK: BLOCKED (no recognised SDK marker under $rawRoot)")
            if (mediatekNativeEnabled.get()) {
                throw GradleException("MediaTek native integration was enabled but SDK marker validation failed.")
            }
            return@doLast
        }
        logger.lifecycle("MediaTek NeuroPilot SDK: provisioned marker found at $rawRoot; native execution remains unverified")
    }
}

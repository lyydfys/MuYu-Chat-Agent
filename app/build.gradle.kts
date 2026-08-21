import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = rootProject.file("signing.properties")
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun releaseSigningProperty(name: String): String? =
    releaseSigningProperties.getProperty(name)
        ?: System.getenv(
            "MCA_RELEASE_" + name
                .replace(Regex("([a-z])([A-Z])"), "$1_$2")
                .uppercase()
        )

val releaseSigningEnabled = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseSigningProperty(it).isNullOrBlank() }

val mcaAbiFilters = providers.gradleProperty("mca.abis")
    .orElse("arm64-v8a,x86_64")
    .get()
    .split(",")
    .map { it.trim() }
    .filter { it.isNotBlank() }
// Validation-only switch: put a coherent non-GenieX QNN host pair first when
// exercising an older context profile such as SM8550/V73. The normal APK
// keeps the existing GenieX QAIRT runtime order.
val mcaQnnRuntimeOverrideGenieX = providers.gradleProperty("mcaQnnRuntimeOverrideGenieX")
    .orNull
    ?.trim()
    ?.equals("true", ignoreCase = true) == true

android {
    namespace = "com.muyuchat.mca"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.muyuchat.mca"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 5
        versionName = "0.2.1"

        ndk {
            abiFilters += mcaAbiFilters
        }
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseSigningProperty("storeFile")))
                storePassword = requireNotNull(releaseSigningProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigningProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigningProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        debug {
            // Reuse the locally configured release signing key for on-device smoke builds.
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // These native libraries come from the local llama.cpp/CMake stack or
            // AndroidX native graphics dependency. Some NDK strip toolchains cannot
            // strip them cleanly, and AGP would package them as-is anyway, so make
            // that choice explicit and keep debug builds quiet.
            keepDebugSymbols += setOf(
                "**/libmca_native.so",
                "**/libmca_mnn_native.so",
                "**/libmca_prompt_handoff.so",
                "**/libmca_qnn_native.so",
                "**/libmca_sd_native.so",
                "**/libMNN.so",
                "**/libQnn*.so",
                "**/libggml*.so",
                "**/libllama*.so",
                "**/libmtmd*.so",
                "**/libkleidiai.so",
                "**/libomp.so",
                "**/libandroidx.graphics.path.so"
            )
            pickFirsts += setOf(
                "**/libggml.so",
                "**/libggml-base.so",
                "**/libllama.so",
                "**/libllama-common.so",
                "**/libmtmd.so",
                "**/libomp.so"
            )
            if (mcaQnnRuntimeOverrideGenieX) {
                pickFirsts += setOf(
                    "**/libQnnSystem.so",
                    "**/libQnnHtp.so"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The isolated image workers are instantiated in fresh processes, so every AIDL
// interface and Stub must be present in the packaged dex.  Gradle can otherwise
// accept a stale incremental Javac directory that is missing only some generated
// AIDL classes.  Declare the concrete files as Javac outputs so a missing Stub
// invalidates the task, then fail packaging if a rerun still did not produce it.
val requiredWorkerAidlInterfaces = listOf(
    "ILocalImageWorker",
    "ILocalImageWorkerCallback",
    "IQairtDryRunWorker",
    "IQairtDryRunWorkerCallback",
    "ILocalChatWorker",
    "ISdxlImagePhaseWorker",
    "ISdxlImagePhaseWorkerCallback",
    "ITuningProbeWorker",
    "ITuningProbeWorkerCallback"
)

androidComponents {
    onVariants(selector().all()) { variant ->
        val variantName = variant.name
        val variantTaskName = variantName.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
        val javacTaskName = "compile${variantTaskName}JavaWithJavac"
        val classOutputRoot =
            "intermediates/javac/$variantName/$javacTaskName/classes/com/muyuchat/mca"
        val expectedClasses = requiredWorkerAidlInterfaces.flatMap { interfaceName ->
            listOf(
                layout.buildDirectory.file("$classOutputRoot/$interfaceName.class"),
                layout.buildDirectory.file("$classOutputRoot/${interfaceName}\$Stub.class")
            )
        }

        tasks.configureEach {
            if (name == javacTaskName) {
                outputs.files(expectedClasses)
            }
        }

        val verifyWorkerAidl = tasks.register("verify${variantTaskName}WorkerAidlClasses") {
            group = "verification"
            description = "Verifies that every isolated worker AIDL interface and Stub was compiled."
            dependsOn(javacTaskName)
            inputs.files(expectedClasses)
            doLast {
                val missing = expectedClasses
                    .map { it.get().asFile }
                    .filterNot { it.isFile && it.length() > 0L }
                check(missing.isEmpty()) {
                    "Generated worker AIDL classes are missing: " +
                        missing.joinToString { it.name } +
                        ". Refusing to package an APK whose isolated worker will crash."
                }
            }
        }

        tasks.configureEach {
            if (name == "package$variantTaskName") {
                dependsOn(verifyWorkerAidl)
            }
        }
    }
}

dependencies {
    if (mcaQnnRuntimeOverrideGenieX) {
        implementation(project(":core:native"))
    }
    implementation(project(":core:engine"))
    if (!mcaQnnRuntimeOverrideGenieX) {
        implementation(project(":core:native"))
    }
    implementation(project(":core:sd-native"))
    implementation(project(":core:modelstore"))
    implementation(project(":core:download"))
    implementation(project(":core:telemetry"))
    implementation(project(":core:deviceprofile"))
    implementation(project(":core:advisor"))
    implementation(project(":core:benchmark"))
    implementation(project(":core:tuning"))
    implementation(project(":api:local"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:agent"))
    implementation(project(":feature:modelhub"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
}

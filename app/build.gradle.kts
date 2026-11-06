import java.util.Properties
import org.gradle.api.tasks.Sync
import groovy.json.JsonSlurper
import java.security.MessageDigest

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

// Keep native DWARF inside debug packages by default so on-device diagnostics
// remain useful. The release wrapper passes false and consumes the external
// debug-symbol archive produced by AGP instead.
val keepNativeDebugSymbols = providers.gradleProperty("mca.keepNativeSymbols")
    .orElse("true")
    .get()
    .equals("true", ignoreCase = true)

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

// LiteRT Qualcomm assets are staged into an app-private directory at runtime.
// Keeping them out of jniLibs avoids a SONAME collision with GenieX's QAIRT
// runtime, while shipping the Edge Gallery-compatible SM8550/V73, SM8650/V75,
// SM8750/V79, and SM8850/V81 transport profiles.
val vendoredLiteRtQualcommRuntimeAssets =
    rootProject.file("vendor/litert/qualcomm/runtime-assets")
val generatedLiteRtQualcommAssets =
    layout.buildDirectory.dir("generated/litert-qualcomm-assets")
val syncLiteRtQualcommAssets = tasks.register<Sync>("syncLiteRtQualcommAssets") {
    from(vendoredLiteRtQualcommRuntimeAssets) {
        into("litert-qualcomm")
        // Edge Gallery's Qualcomm APK ships the precompiled-runtime sets only:
        // dispatch + QNN host/system + the matching HTP transport pair. Do not
        // put the JIT compiler plugin into this directory because it is a
        // separate QAIRT-facing path and can make a precompiled model resolve
        // a second, incompatible plugin set.
        exclude("**/libLiteRtCompilerPlugin_Qualcomm.so")
    }
    into(generatedLiteRtQualcommAssets)
    doFirst {
        check(vendoredLiteRtQualcommRuntimeAssets.resolve("arm64-v8a").isDirectory) {
            "Missing LiteRT Qualcomm runtime assets: ${vendoredLiteRtQualcommRuntimeAssets.absolutePath}"
        }
    }
    doLast {
        val generatedRoot = generatedLiteRtQualcommAssets.get().asFile
        val stagedRoot = generatedRoot.resolve("litert-qualcomm/arm64-v8a")
        val v81Names = stagedRoot.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        val v79Root = generatedRoot.resolve("litert-qualcomm/v79/arm64-v8a")
        val v79Names = v79Root.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        val v73Root = generatedRoot.resolve("litert-qualcomm/v73/arm64-v8a")
        val v73Names = v73Root.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        val v75Root = generatedRoot.resolve("litert-qualcomm/v75/arm64-v8a")
        val v75Names = v75Root.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        check(v81Names == setOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so",
            "libQnnHtpV81Skel.so",
            "libQnnHtpV81Stub.so",
            "libQnnSystem.so"
        )) {
            "Unexpected LiteRT Qualcomm V81 staged asset set: ${v81Names.sorted()}"
        }
        check(v79Names == setOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so",
            "libQnnHtpV79Skel.so",
            "libQnnHtpV79Stub.so",
            "libQnnSystem.so"
        )) {
            "Unexpected LiteRT Qualcomm V79 staged asset set: ${v79Names.sorted()}"
        }
        check(v73Names == setOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so",
            "libQnnHtpV73Skel.so",
            "libQnnHtpV73Stub.so",
            "libQnnSystem.so"
        )) {
            "Unexpected LiteRT Qualcomm V73 staged asset set: ${v73Names.sorted()}"
        }
        check(v75Names == setOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so",
            "libQnnHtpV75Skel.so",
            "libQnnHtpV75Stub.so",
            "libQnnSystem.so"
        )) {
            "Unexpected LiteRT Qualcomm V75 staged asset set: ${v75Names.sorted()}"
        }
    }
}

// Qwen-Image-2.1 uses a private MNN SONAME and must be loaded only by its
// dedicated :qwen_image21 process. Stage these files as assets (not jniLibs),
// then extract and SHA-verify them in that process before System.load().
val vendoredQwenImage21RuntimeAssets =
    rootProject.file("vendor/qwen-image-2.1/arm64-v8a")
val generatedQwenImage21Assets =
    layout.buildDirectory.dir("generated/qwen-image-2.1-assets")
val syncQwenImage21RuntimeAssets = tasks.register<Sync>("syncQwenImage21RuntimeAssets") {
    from(vendoredQwenImage21RuntimeAssets) {
        into("qwen-image-2.1/arm64-v8a")
        include(
            "runtime-manifest.json",
            "libc++_shared.so",
            "libmca_qwenimage21_mnn.so",
            "libqwenimage21_jni.so"
        )
    }
    into(generatedQwenImage21Assets)
    doFirst {
        val expectedFiles = setOf(
            "runtime-manifest.json",
            "libc++_shared.so",
            "libmca_qwenimage21_mnn.so",
            "libqwenimage21_jni.so"
        )
        val actualFiles = vendoredQwenImage21RuntimeAssets.listFiles().orEmpty()
            .filter { it.isFile }
            .map { it.name }
            .toSet()
        check(actualFiles == expectedFiles) {
            "Unexpected Qwen-Image-2.1 arm64 runtime asset set: ${actualFiles.sorted()}"
        }
    }
    doLast {
        val stagedRoot = generatedQwenImage21Assets.get().asFile
            .resolve("qwen-image-2.1/arm64-v8a")
        val manifest = stagedRoot.resolve("runtime-manifest.json")
        check(manifest.isFile && manifest.length() in 1L..16_384L) {
            "Qwen-Image-2.1 runtime asset manifest is missing or too large."
        }
        val parsed = JsonSlurper().parseText(manifest.readText()) as? Map<*, *>
            ?: error("Qwen-Image-2.1 runtime asset manifest is not a JSON object.")
        check(parsed["schema"] == "mca.qwen-image-2.1.runtime.v1") {
            "Unsupported Qwen-Image-2.1 runtime asset schema."
        }
        val libraries = parsed["libraries"] as? List<*>
            ?: error("Qwen-Image-2.1 runtime asset manifest is missing libraries.")
        val expectedNames = setOf(
            "libc++_shared.so",
            "libmca_qwenimage21_mnn.so",
            "libqwenimage21_jni.so"
        )
        check(libraries.size == expectedNames.size) {
            "Qwen-Image-2.1 runtime asset manifest has the wrong library count."
        }
        val seen = mutableSetOf<String>()
        for (rawEntry in libraries) {
            val entry = rawEntry as? Map<*, *>
                ?: error("Qwen-Image-2.1 runtime asset manifest contains an invalid entry.")
            val name = entry["file"] as? String ?: ""
            check(name in expectedNames && seen.add(name)) {
                "Qwen-Image-2.1 runtime asset manifest contains an unexpected/duplicate library: $name"
            }
            val file = stagedRoot.resolve(name)
            val expectedSize = (entry["sizeBytes"] as? Number)?.toLong() ?: -1L
            check(file.isFile && file.length() == expectedSize) {
                "Qwen-Image-2.1 runtime asset size does not match its manifest: $name"
            }
            val actualSha256 = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            check(actualSha256 == entry["sha256"]) {
                "Qwen-Image-2.1 runtime asset SHA-256 does not match its manifest: $name"
            }
        }
        check(seen == expectedNames) { "Qwen-Image-2.1 runtime asset manifest is incomplete." }
    }
}

android {
    namespace = "com.muyuchat.mca"
    compileSdk = libs.versions.compileSdk.get().toInt()

    // BouncyCastle ships identical licence files in bctls/bcutil/bcprov; AGP refuses to merge the
    // same path from several jars. They are legal metadata, not runtime resources.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
                "META-INF/ASL2.0"
            )
        }
    }

    defaultConfig {
        // Iteration-only entry points read this and refuse to act in a real release.
        buildConfigField(
            "boolean",
            "MCA_ITERATION",
            ((project.findProperty("mcaIteration") as String?)?.toBoolean() == true).toString()
        )
        applicationId = "com.muyuchat.mca"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 20
        versionName = "0.4.1"

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
            // Debug builds use the Android toolchain's dedicated debug key. Never
            // reuse the release keystore for a testable artifact.
        }
        release {
            // Iteration switch: `-PmcaIteration=true` keeps the *release signing key* (so the
            // artifact still replaces the installed build with `adb install -r`) but skips the
            // three slow steps — R8, resource shrinking and full native symbol extraction — that
            // together dominate a release build. Never ship such an artifact: `scripts/release.ps1`
            // never passes this property, so a published APK is always the fully optimised one.
            val iteration = (project.findProperty("mcaIteration") as String?)?.toBoolean() == true
            isMinifyEnabled = !iteration
            isShrinkResources = !iteration
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = if (iteration) "NONE" else "FULL"
            }
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // G00/Q01: release-like target for :benchmark. It inherits R8/shrinking from release,
        // stays non-debuggable (Macrobenchmark refuses to measure a debuggable target), and is
        // signed with the local debug key only so it can be installed on a test device. The
        // ".benchmark" suffix keeps its app data completely separate from a real install.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".benchmark"
            isDebuggable = false
        }
        // Local-model debugging target. It matches release library variants (and therefore reuses
        // their native artifacts) while skipping R8/resource shrinking and staying installable
        // beside the production package under its own applicationId. Resource shrinking must be
        // disabled explicitly because initWith(release) copies shrinkResources=true and AGP rejects
        // resource shrinking without code shrinking. The release signing key is used only when it
        // is actually configured; otherwise this variant stays unsigned instead of failing
        // configuration.
        create("diagnostic") {
            initWith(getByName("release"))
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            // Same applicationId as release so this debuggable, fast-build variant can update the
            // already-installed signed app in place (MIUI blocks new-package installs). It must
            // never be published; release output still comes from assembleRelease.
            versionNameSuffix = "-diag"
            matchingFallbacks += listOf("release")
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Test-only installable variant. Some devices already carry a differently-signed
        // com.muyuchat.mca, and replacing it would erase user data. `-PmcaDeviceTestVariant=true`
        // adds a debuggable, debug-signed APK with a distinct applicationId so Room migration and
        // Compose instrumentation tests can run without touching the existing install. It is not
        // created in normal builds and does not change any production variant.
        if (providers.gradleProperty("mcaDeviceTestVariant").orNull == "true") {
            create("deviceTest") {
                initWith(getByName("debug"))
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks += listOf("debug")
                applicationIdSuffix = ".devicetest"
                versionNameSuffix = "-devicetest"
                isDebuggable = true
                isMinifyEnabled = false
            }
        }
        // Instrumentation is only generated for Android's single test build type. Redirect it to
        // the isolated deviceTest variant so migration/UI tests can run next to an existing install
        // without uninstalling (and wiping) the differently-signed production app.
        if (providers.gradleProperty("mcaDeviceTestVariant").orNull == "true") {
            testBuildType = "deviceTest"
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedLiteRtQualcommAssets)
            assets.srcDir(generatedQwenImage21Assets)
            // The official-DSH payload assets and jniLibs live in :core:dsh-runtime itself
            // (core/dsh-runtime/src/main/{assets,jniLibs}), staged by scripts/dsh/stage.ps1.
            // Keeping them module-local makes the payload part of the runtime AAR and avoids a
            // second app-level sourceSet that could silently diverge from the verified manifest.
        }
        // Room's MigrationTestHelper reads the exported schema from the instrumentation APK's
        // assets. The JSON under app/schemas is generated at compile time; packaging it into the
        // test assets is what lets the real 27 -> 28 migration be validated on a device.
        getByName("androidTest") {
            assets.srcDir(file("schemas"))
        }

        // The test-only deviceTest build type is initialized from debug, but Gradle does not
        // inherit the debug build type's source set. DSH's device staging instrumentation test
        // references a class that only exists in src/debug; include that source set so the
        // isolated deviceTest variant can compile androidTest without affecting production.
        findByName("deviceTest")?.java?.srcDir("src/debug/java")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // The bundled container runtime ships as native libraries because that is the only
            // directory the app may exec() from. proot is an executable wearing a lib*.so name and
            // the two others are its exact SONAMEs, copied byte-for-byte from the pinned packages:
            // stripping an executable is meaningless and can corrupt its entry point, so they are
            // kept as-is in every build type, independent of -Pmca.keepNativeSymbols.
            keepDebugSymbols += setOf(
                "**/libproot.so",
                // proot's DT_NEEDED is `libtalloc.so.2`; APK packaging only accepts `*.so`, so
                // the installer copies this file to that SONAME name on device.
                "**/libtalloc.so",
                "**/libandroid-shmem.so"
            )
            // These native libraries come from the local llama.cpp/CMake stack or
            // AndroidX native graphics dependency. Some NDK strip toolchains cannot
            // strip them cleanly, and AGP would package them as-is anyway, so make
            // that choice explicit and keep debug builds quiet.
            if (keepNativeDebugSymbols) {
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
                    // The packaged DSH Node payload deliberately ships on-device executables under
                    // lib*.so names so PackageManager extracts them into the non-writable
                    // nativeLibraryDir (targetSdk 36 forbids exec from the writable app home).
                    // Stripping them as if they were ordinary app libraries is not meaningful and
                    // can corrupt their entry points, so keep every DSH payload library as-is.
                    "**/libdsh*.so",
                    "**/libandroidx.graphics.path.so"
                )
            }
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

    // ManagedDownloadQnnInspectionTest drives the download-preflight owner on a plain JVM, where
    // android.util.Log is a stub. Return defaults instead of throwing "not mocked" so the
    // production logging on those paths stays exercised by the unit test.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
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

// A debug APK is the complete local validation artifact. Keep accidental
// arm64-only invocations from silently producing a smaller, incomplete APK;
// the intentional arm64 release profile remains documented in docs/RELEASE.md.
tasks.configureEach {
    if (name == "preDebugBuild") {
        doFirst {
            val expectedDebugAbis = setOf("arm64-v8a", "x86_64")
            check(mcaAbiFilters.toSet() == expectedDebugAbis) {
                "Debug APKs must include both arm64-v8a and x86_64. " +
                    "Use -Pmca.abis=arm64-v8a,x86_64; arm64-only is reserved for the release profile."
            }
        }
    }
}

// AGP creates variant-specific merge tasks lazily. Make the generated asset
// tree an explicit input so the APK always contains the expected
// assets/litert-qualcomm/arm64-v8a layout before packaging.
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(syncLiteRtQualcommAssets)
        dependsOn(syncQwenImage21RuntimeAssets)
    }
    // Lint model/report tasks also inspect the generated asset source set.
    // Declare the producer dependency explicitly so release validation cannot
    // race asset synchronization.
    if (name.contains("lint", ignoreCase = true)) {
        dependsOn(syncLiteRtQualcommAssets)
        dependsOn(syncQwenImage21RuntimeAssets)
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

// Test-only: Room 2.8.4's migration-testing schema bundles were compiled against the
// serialization 1.8 ABI, while Room's runtime dependency graph pins 1.7.3. The deviceTest
// variant (never shipped) aligns both sides so MigrationTestHelper can load a schema.
configurations.configureEach {
    if (name.contains("devicetest", ignoreCase = true)) {
        resolutionStrategy.force(
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1"
        )
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    // The engine library exposes GenieX-backed runners but cannot embed a
    // local AAR in its own AAR. Package the vendored, hash-verified artifact
    // directly in the final application instead.
    implementation(files(rootProject.file("vendor/geniex/geniex-android-0.10.0-mca1.aar")))
    if (mcaQnnRuntimeOverrideGenieX) {
        implementation(project(":core:native"))
    }
    implementation(project(":core:engine"))
    implementation(project(":core:agent-contract"))
    implementation(project(":core:agent-runtime"))
    // Independent official-DSH mode (proposal agent-dsh-embedded-v1). The app composition root
    // wires the runtime owner, the loopback model gateway and the workspace container; no DSH
    // feature may depend on the Kotlin Agent feature internals.
    implementation(project(":core:dsh-contract"))
    implementation(project(":core:dsh-runtime"))
    implementation(project(":core:dsh-platform"))
    implementation(project(":core:dsh-android-actions"))
    implementation(project(":core:dsh-a11y"))
    implementation(project(":core:dsh-vscreen"))
    implementation(project(":core:dsh-browser"))
    implementation(project(":core:dsh-maintenance"))
    implementation(project(":api:dsh-gateway"))
    implementation(project(":feature:dsh-workspace"))
    // Native video workflow (proposal mca-native-video-workflow-v1). The composition root wires
    // the process-level video owner and the workspace feature; the shared contract types are the
    // only new compile-surface the app gains here.
    implementation(project(":core:video-contract"))
    implementation(project(":feature:video-workspace"))
    if (!mcaQnnRuntimeOverrideGenieX) {
        implementation(project(":core:native"))
    }
    implementation(project(":core:sd-native"))
    implementation(project(":core:modelstore"))
    implementation(project(":core:download"))
    implementation(project(":core:mediatek-native"))
    implementation(project(":core:telemetry"))
    implementation(project(":core:deviceprofile"))
    implementation(project(":core:advisor"))
    implementation(project(":core:benchmark"))
    implementation(project(":core:tuning"))
    implementation(project(":api:local"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:agent"))
    implementation(project(":feature:agent-workspace"))
    implementation(project(":feature:modelhub"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    // Macrobenchmark needs ProfileInstaller 1.3+ inside the *target* app to install/reset
    // profiles and clear shader caches between iterations.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.okhttp)
    // WorkManager exposes ListenableFuture in worker APIs; AndroidX resolves
    // listenablefuture to an empty conflict artifact unless Guava is present.
    implementation(libs.guava.android)
    // N10/R2: real JavaScript execution for executable tool packages. Rhino is pure JVM (no
    // native ABI); the sandbox boundary is host-side and is NOT an isolatedProcess/UID sandbox.
    implementation(libs.rhino)
    // MCP Streamable HTTP wire messages are untrusted JSON; parse them with the same strict
    // streaming-core policy family as the Agent control protocol (no databind/reflection).
    implementation(libs.jackson.core)
    implementation("com.google.re2j:re2j:1.8")
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
    androidTestImplementation(libs.androidx.room.testing)
    // Room 2.8.4 migration-testing serializers were generated against serialization 1.7.x.
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

// Room schema export.
//
// Both databases used exportSchema = false, so nothing on disk recorded what structure each
// version expects. The migration tests build their own minimal tables and run the Migration
// objects against them, which proves data is preserved but cannot detect a mismatch between
// what the migration chain produces and what Room validates at runtime -- that mismatch
// surfaces only on a real upgrade, as "Room cannot verify the data integrity".
//
// Exporting the schemas makes every version reviewable in diffs and lets the migration tests
// compare their result against the exact structure Room expects.
//
// This changes no schema and no runtime behaviour; it only writes JSON during compilation.
ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
}

if ((project.findProperty("mcaIteration") as String?)?.toBoolean() == true) {
    // Lint-vital is a release gate, not part of the iteration loop.
    tasks.matching { it.name.startsWith("lintVital") }.configureEach { enabled = false }
}

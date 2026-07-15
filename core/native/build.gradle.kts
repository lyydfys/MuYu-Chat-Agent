import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.createTempFile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

fun strictBooleanProperty(value: String?, name: String): Boolean? = value
    ?.trim()
    ?.lowercase()
    ?.let { normalized ->
        when (normalized) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> throw GradleException("$name must be true or false, but was '$value'.")
        }
    }

fun String.withLfLineEndings(): String = replace("\r\n", "\n").replace('\r', '\n')

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

data class McaCommandResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
)

fun runMcaCommand(arguments: List<String>): McaCommandResult {
    val stderrFile = createTempFile("mca-command-", ".stderr").toFile()
    try {
        val process = try {
            ProcessBuilder(arguments)
                .redirectError(stderrFile)
                .start()
        } catch (error: Exception) {
            throw GradleException("Unable to run '${arguments.firstOrNull() ?: "command"}'.", error)
        }
        val stdout = process.inputStream.use { it.readBytes() }
        val exitCode = process.waitFor()
        val stderr = stderrFile.readText(StandardCharsets.UTF_8)
        return McaCommandResult(exitCode, stdout, stderr)
    } finally {
        stderrFile.delete()
    }
}

val configuredMnnSourceRoot = providers.gradleProperty("mcaMnnSourceRoot")
    .orElse(providers.environmentVariable("MCA_MNN_SOURCE_ROOT"))
val mcaMnnSourceRootCandidate = configuredMnnSourceRoot.orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::file)
    ?: rootProject.file("third_party/MNN")
val mcaMnnSourceRoot = mcaMnnSourceRootCandidate.takeIf { it.isDirectory }
val mcaMnnAndroidBuildRoot = providers.gradleProperty("mcaMnnAndroidBuildRoot")
    .orElse(providers.environmentVariable("MCA_MNN_ANDROID_BUILD_ROOT"))
val mcaMnnVendorManifest = providers.gradleProperty("mcaMnnVendorManifest")
    .orElse(providers.environmentVariable("MCA_MNN_VENDOR_MANIFEST"))
val mcaMnnVendorRequired = strictBooleanProperty(
    providers.gradleProperty("mcaMnnVendorRequired")
        .orElse(providers.environmentVariable("MCA_MNN_VENDOR_REQUIRED"))
        .orNull,
    "mcaMnnVendorRequired",
)
// Android library variant matching can invoke this module's `*Release*` CMake
// tasks while building an app Debug APK. Use the requested top-level task,
// rather than that implementation detail, to identify an isolated debug
// runtime experiment. A release invocation can never opt out of vendor
// verification.
val mcaMnnDebugRuntimeExperiment = run {
    val requestedTasks = gradle.startParameter.taskNames.map(String::lowercase)
    mcaMnnVendorRequired == false &&
        requestedTasks.any { "debug" in it } &&
        requestedTasks.none { "release" in it }
}
// MNN 3.5 is only used in an isolated chat/VLM compatibility experiment. Its
// diffusion API predates the local safe runner, so it must be possible to
// leave that unrelated shim out of this Debug APK. Never allow that switch to
// alter a normal/debug product build or any release invocation.
val mcaWithMnnDiffusionRequested = strictBooleanProperty(
    providers.gradleProperty("mcaWithMnnDiffusion")
        .orElse(providers.environmentVariable("MCA_WITH_MNN_DIFFUSION"))
        .orNull,
    "mcaWithMnnDiffusion",
)
val mcaWithMnnDiffusion = when (mcaWithMnnDiffusionRequested) {
    false -> {
        check(mcaMnnDebugRuntimeExperiment) {
            "mcaWithMnnDiffusion=false is only permitted for an isolated Debug MNN runtime experiment."
        }
        false
    }
    else -> true
}
val mcaQnnSdkRoot = providers.gradleProperty("mcaQnnSdkRoot")
    .orElse(providers.environmentVariable("MCA_QNN_SDK_ROOT"))
val mcaQnnRuntimeRoot = providers.gradleProperty("mcaQnnRuntimeRoot")
    .orElse(providers.environmentVariable("MCA_QNN_RUNTIME_ROOT"))
val mcaQnnRuntimeOverrideGenieX = strictBooleanProperty(
    providers.gradleProperty("mcaQnnRuntimeOverrideGenieX")
        .orElse(providers.environmentVariable("MCA_QNN_RUNTIME_OVERRIDE_GENIEX"))
        .orNull,
    "mcaQnnRuntimeOverrideGenieX",
) ?: false
val mcaQnnTypedBindingsRequired = strictBooleanProperty(
    providers.gradleProperty("mcaQnnTypedBindingsRequired")
        .orElse(providers.environmentVariable("MCA_QNN_TYPED_BINDINGS_REQUIRED"))
        .orNull,
    "mcaQnnTypedBindingsRequired",
)
val mcaWithLlamaCpp = strictBooleanProperty(
    providers.gradleProperty("mcaWithLlamaCpp")
        .orElse(providers.environmentVariable("MCA_WITH_LLAMA_CPP"))
        .orNull,
    "mcaWithLlamaCpp",
) ?: true
val mcaAbiFilters = providers.gradleProperty("mca.abis")
    .orElse("arm64-v8a,x86_64")
    .get()
    .split(",")
    .map { it.trim() }
    .filter { it.isNotBlank() }
// MNN's current Android CMake build emits a coherent split runtime at the
// build root.  Older build directories keep a second, nested `OFF/` layout.
// Do not mix those layouts: libllm is ABI-coupled to MNN/Express/OpenCL/CV/
// Audio, and selecting a fresh libllm with stale dependencies can crash Omni
// model loading.  Prefer the current layout as one atomic set and retain the
// legacy layout only as a compatibility fallback for older vendor builds.
val mcaMnnCurrentRuntimeRelativeLibs = listOf(
    "libMNN.so",
    "libMNN_Express.so",
    "libMNN_CL.so",
    "tools/cv/libMNNOpenCV.so",
    "tools/audio/libMNNAudio.so",
    "libllm.so",
)
val mcaMnnLegacyRuntimeRelativeLibs = listOf(
    "OFF/arm64-v8a/libMNN.so",
    "express/OFF/arm64-v8a/libMNN_Express.so",
    "source/backend/opencl/OFF/arm64-v8a/libMNN_CL.so",
    "tools/cv/OFF/arm64-v8a/libMNNOpenCV.so",
    "tools/audio/OFF/arm64-v8a/libMNNAudio.so",
    "OFF/arm64-v8a/libllm.so"
)
val mcaMnnRuntimeLayoutCandidates = listOf(
    "current" to mcaMnnCurrentRuntimeRelativeLibs,
    "legacy" to mcaMnnLegacyRuntimeRelativeLibs,
)
data class McaMnnRuntimeSelection(
    val root: File,
    val layout: String,
    val relativeLibs: List<String>,
)
val mcaMnnRuntimeSelection = run {
    val explicitBuildRoot = mcaMnnAndroidBuildRoot.orNull?.takeIf { it.isNotBlank() }?.let(::file)
    val sourceRoot = mcaMnnSourceRoot
    val candidateRoots = buildList {
        explicitBuildRoot?.let(::add)
        sourceRoot?.let { root ->
            add(root.resolve("project/android/build_64_mca_full"))
        }
    }
    candidateRoots.firstNotNullOfOrNull { root ->
        mcaMnnRuntimeLayoutCandidates.firstOrNull { (_, relativeLibs) ->
            relativeLibs.all { relativePath -> root.resolve(relativePath).isFile }
        }?.let { (layout, relativeLibs) ->
            McaMnnRuntimeSelection(root, layout, relativeLibs)
        }
    }
}
val mcaMnnRuntimeRoot = mcaMnnRuntimeSelection?.root
val mcaMnnRuntimeLibs = mcaMnnRuntimeSelection
    ?.let { selection -> selection.relativeLibs.map(selection.root::resolve) }
    .orEmpty()
val mcaMnnSharedLib = mcaMnnRuntimeLibs.firstOrNull()
val mcaMnnRuntimeStamp = mcaMnnRuntimeRoot?.resolve("mca-mnn-runtime.properties")
val mcaMnnGeneratedJniLibs = layout.buildDirectory.dir("generated/mcaMnnJniLibs/main")
val mcaQnnGeneratedJniLibs = layout.buildDirectory.dir("generated/mcaQnnJniLibs/main")
// A complete HTP profile has more than System/Htp plus a Skel/Stub pair on
// newer QAIRT releases: the versioned HTP transport (`libQnnHtpVxx.so`) and
// its calculator stub are also loaded on the DSP path.  Keep every versioned
// HTP companion from an explicitly selected, coherent runtime root together.
val qnnRuntimeLibraryName = Regex("""^libQnn(System|Saver|Htp|HtpPrepare|HtpV.*)\.so$""")
// The HTP host uses cdsprpc for the CDSP transport on several Snapdragon
// generations.  It is not a QNN-prefixed filename, but when an explicit
// coherent runtime root is requested it must travel with that same runtime
// into the app-private native library directory.  Relying on a shell-side or
// OEM copy is neither linkable nor reproducible for a regular app process.
val qnnRuntimeTransportLibraryNames = setOf("libcdsprpc.so")
val qnnRuntimeLibrariesProvidedByGenieX = buildSet {
    // The SM8550 validation profile uses the System/HTP pair from the same
    // runtime bundle as its V73 Skel/Stub. Keep this opt-in: the default APK
    // continues to use GenieX's QAIRT host runtime for its V79/V81 paths.
    if (!mcaQnnRuntimeOverrideGenieX) {
        add("libQnnSystem.so")
        add("libQnnHtp.so")
    }
    addAll(
        listOf(
            "libQnnSaver.so",
            "libQnnHtpPrepare.so",
            "libQnnHtpV79.so",
            "libQnnHtpV79CalculatorStub.so",
            "libQnnHtpV79Skel.so",
            "libQnnHtpV79Stub.so",
            "libQnnHtpV81.so",
            "libQnnHtpV81CalculatorStub.so",
            "libQnnHtpV81Skel.so",
            "libQnnHtpV81Stub.so"
        )
    )
}.toSet()
val mcaQnnRuntimeLibs = mcaQnnRuntimeRoot.orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::file)
    ?.takeIf { it.isDirectory }
    ?.walkTopDown()
    ?.filter {
        it.isFile &&
            (qnnRuntimeLibraryName.matches(it.name) || it.name in qnnRuntimeTransportLibraryNames) &&
            it.name !in qnnRuntimeLibrariesProvidedByGenieX
    }
    ?.distinctBy { it.name }
    ?.toList()
    .orEmpty()
val mcaQnnRequiredHeaders = listOf(
    "include/QNN/QnnInterface.h",
    "include/QNN/QnnBackend.h",
    "include/QNN/QnnContext.h",
    "include/QNN/QnnDevice.h",
    "include/QNN/QnnGraph.h",
    "include/QNN/QnnTensor.h",
    "include/QNN/HTP/QnnHtpDevice.h",
    "include/QNN/System/QnnSystemInterface.h"
)
val mcaQnnSdkRoots = listOfNotNull(
    mcaQnnSdkRoot.orNull?.takeIf(String::isNotBlank),
    providers.environmentVariable("QNN_SDK_ROOT").orNull?.takeIf(String::isNotBlank),
    providers.environmentVariable("QAIRT_SDK_ROOT").orNull?.takeIf(String::isNotBlank)
)
    .map(::file)
    .distinctBy { it.absoluteFile.normalize().path.lowercase() }
val mcaQnnCompleteSdkRoot = mcaQnnSdkRoots.firstOrNull { root ->
    mcaQnnRequiredHeaders.all { relativePath -> root.resolve(relativePath).isFile }
}
val mcaQnnSdkRootForCMake = mcaQnnCompleteSdkRoot
    ?: mcaQnnSdkRoots.firstOrNull { it.resolve("include/QNN/QnnInterface.h").isFile }
    ?: mcaQnnSdkRoots.firstOrNull()
val verifyMcaQnnSdkHeaders = tasks.register("verifyMcaQnnSdkHeaders") {
    group = "verification"
    description = "Fails when a QNN product build cannot compile typed QAIRT/QNN graph bindings."

    doLast {
        if (mcaQnnCompleteSdkRoot == null) {
            val inspected = if (mcaQnnSdkRoots.isEmpty()) {
                "  - no SDK root configured"
            } else {
                mcaQnnSdkRoots.joinToString(separator = "\n") { root ->
                    val missing = mcaQnnRequiredHeaders.filterNot { root.resolve(it).isFile }
                    "  - ${root.absolutePath}: missing ${missing.joinToString()}"
                }
            }
            throw GradleException(
                """
                Typed QAIRT/QNN bindings are required for this build, but a complete SDK header set was not found.
                Configure -PmcaQnnSdkRoot=<QAIRT SDK root> (or MCA_QNN_SDK_ROOT/QNN_SDK_ROOT/QAIRT_SDK_ROOT).
                Inspected SDK roots:
                $inspected
                Debug builds may intentionally use the native stub; release builds may not.
                """.trimIndent()
            )
        }
        logger.lifecycle("MCA typed QAIRT/QNN bindings verified at ${mcaQnnCompleteSdkRoot.absolutePath}")
    }
}

val verifyMcaMnnVendor = tasks.register("verifyMcaMnnVendor") {
    group = "verification"
    description = "Fails when the pinned MNN checkout differs from the repository-owned overlay."

    doLast {
        val manifestFile = mcaMnnVendorManifest.orNull
            ?.takeIf { it.isNotBlank() }
            ?.let(::file)
            ?: rootProject.file("vendor/mnn/mnn-vendor.properties")
        if (!manifestFile.isFile) {
            throw GradleException(
                "MNN vendor manifest is missing: ${manifestFile.absolutePath}. " +
                    "Restore repository vendor metadata before a product native build."
            )
        }
        val manifest = Properties().apply {
            manifestFile.inputStream().use(::load)
        }
        fun requiredManifestValue(name: String): String = manifest.getProperty(name)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw GradleException("MNN vendor manifest is missing required property '$name'.")

        val expectedCommit = requiredManifestValue("commit").lowercase()
        val expectedPatchSha = requiredManifestValue("patchSha256").lowercase()
        if (!Regex("[0-9a-f]{40}").matches(expectedCommit)) {
            throw GradleException("MNN vendor manifest commit must be a full 40-character SHA-1.")
        }
        if (!Regex("[0-9a-f]{64}").matches(expectedPatchSha)) {
            throw GradleException("MNN vendor manifest patchSha256 must be a full SHA-256.")
        }
        val patchFile = manifestFile.parentFile.resolve(requiredManifestValue("patch")).normalize()
        if (!patchFile.isFile) {
            throw GradleException("MNN vendor patch is missing: ${patchFile.absolutePath}")
        }
        val patchText = patchFile.readText(StandardCharsets.UTF_8).withLfLineEndings()
        val actualPatchSha = sha256Hex(patchText.toByteArray(StandardCharsets.UTF_8))
        if (actualPatchSha != expectedPatchSha) {
            throw GradleException(
                "MNN vendor patch checksum mismatch. Expected $expectedPatchSha, got $actualPatchSha."
            )
        }
        val expectedFiles = requiredManifestValue("files")
            .split('|')
            .map(String::trim)
            .filter(String::isNotEmpty)
        val patchFiles = Regex("(?m)^diff --git a/(.+) b/(.+)$")
            .findAll(patchText)
            .map { match ->
                val before = match.groupValues[1]
                val after = match.groupValues[2]
                if (before != after) {
                    throw GradleException("MNN vendor patch renames are not supported: $before -> $after")
                }
                before
            }
            .toList()
        if (patchFiles != expectedFiles) {
            throw GradleException(
                "MNN vendor manifest file list does not match the patch: ${patchFiles.joinToString()}."
            )
        }

        val sourceRoot = mcaMnnSourceRootCandidate.absoluteFile.normalize()
        if (!sourceRoot.isDirectory) {
            throw GradleException(
                "MNN vendor checkout is missing: ${sourceRoot.absolutePath}. " +
                    "Run tools/vendor/bootstrap-mnn-vendor.ps1."
            )
        }
        val git = providers.gradleProperty("mcaGitExecutable")
            .orElse(providers.environmentVariable("MCA_GIT_EXECUTABLE"))
            .orElse("git")
            .get()
        fun gitResult(vararg arguments: String): McaCommandResult = runMcaCommand(
            listOf(git, "-C", sourceRoot.absolutePath) + arguments
        )
        fun requireGit(result: McaCommandResult, action: String): McaCommandResult {
            if (result.exitCode != 0) {
                throw GradleException(
                    "Unable to $action for MNN vendor checkout (exit ${result.exitCode}): " +
                        result.stderr.trim().ifEmpty { "no diagnostic output" }
                )
            }
            return result
        }

        val head = String(
            requireGit(gitResult("rev-parse", "HEAD"), "read HEAD").stdout,
            StandardCharsets.UTF_8,
        ).trim().lowercase()
        if (head != expectedCommit) {
            throw GradleException("MNN vendor commit mismatch. Expected $expectedCommit, got $head.")
        }
        val status = String(
            requireGit(
                gitResult("status", "--porcelain=v1", "--untracked-files=all"),
                "read status",
            ).stdout,
            StandardCharsets.UTF_8,
        ).withLfLineEndings()
        val untracked = status.lineSequence().filter { it.startsWith("??") }.toList()
        if (untracked.isNotEmpty()) {
            throw GradleException(
                "MNN vendor checkout contains untracked drift:\n${untracked.joinToString("\n")}"
            )
        }
        val diffCommand = runMcaCommand(
            listOf(
                git, "-c", "core.safecrlf=false", "-C", sourceRoot.absolutePath,
                "diff", "--binary", "--full-index", "--no-ext-diff", "--no-color", "HEAD", "--",
            )
        )
        val currentDiff = String(
            requireGit(diffCommand, "compute canonical diff").stdout,
            StandardCharsets.UTF_8,
        ).withLfLineEndings()
        if (currentDiff.isBlank()) {
            throw GradleException("MNN vendor overlay patch is not applied.")
        }
        if (currentDiff != patchText) {
            val reverseCheck = gitResult(
                "apply", "--reverse", "--check", "--whitespace=nowarn", patchFile.absolutePath,
            )
            val reason = if (reverseCheck.exitCode == 0) {
                "the overlay is present, but additional tracked drift exists"
            } else {
                "the overlay is partial or modified"
            }
            throw GradleException(
                "MNN vendor diff mismatch: $reason.\n" + status.trim().ifEmpty { "(clean status)" }
            )
        }
        requireGit(
            gitResult("apply", "--reverse", "--check", "--whitespace=nowarn", patchFile.absolutePath),
            "reverse-check required overlay",
        )
        requireGit(gitResult("diff", "--check", "HEAD", "--"), "check overlay whitespace")
        logger.lifecycle(
            "MCA MNN vendor verified at $expectedCommit with ${patchFiles.size} patched files " +
                "(SHA-256 $actualPatchSha)."
        )
    }
}

val verifyMcaMnnRuntimeStamp = tasks.register("verifyMcaMnnRuntimeStamp") {
    group = "verification"
    description = "Fails when packaged MNN runtime libraries were built from a stale vendor overlay."
    dependsOn(verifyMcaMnnVendor)

    doLast {
        val manifestFile = mcaMnnVendorManifest.orNull
            ?.takeIf { it.isNotBlank() }
            ?.let(::file)
            ?: rootProject.file("vendor/mnn/mnn-vendor.properties")
        val vendor = Properties().apply { manifestFile.inputStream().use(::load) }
        val expectedPatchSha = vendor.getProperty("patchSha256")?.trim()?.lowercase()
            ?: throw GradleException("MNN vendor manifest is missing patchSha256: ${manifestFile.absolutePath}")
        val stampFile = mcaMnnRuntimeStamp
            ?: throw GradleException("MNN runtime build root is not configured or complete.")
        if (!stampFile.isFile) {
            throw GradleException(
                "MNN runtime provenance is missing: ${stampFile.absolutePath}. " +
                    "Run tools/build-mnn-runtime.ps1 before assembling the APK."
            )
        }
        val stamp = Properties().apply { stampFile.inputStream().use(::load) }
        val actualPatchSha = stamp.getProperty("vendorPatchSha256")?.trim()?.lowercase()
        if (actualPatchSha != expectedPatchSha) {
            throw GradleException(
                "MNN runtime is stale: built from patch ${actualPatchSha ?: "<missing>"}, " +
                    "current vendor patch is $expectedPatchSha. Run tools/build-mnn-runtime.ps1."
            )
        }
        if (stamp.getProperty("abi")?.trim() != "arm64-v8a") {
            throw GradleException("MNN runtime provenance does not describe arm64-v8a.")
        }
        mcaMnnRuntimeLibs.forEach { library ->
            val expectedHash = stamp.getProperty("lib.${library.name}.sha256")?.trim()?.lowercase()
                ?: throw GradleException("MNN runtime provenance is missing ${library.name} SHA-256.")
            val actualHash = sha256Hex(library.readBytes())
            if (actualHash != expectedHash) {
                throw GradleException(
                    "MNN runtime library changed after provenance was written: ${library.absolutePath}"
                )
            }
        }
        logger.lifecycle("MCA MNN runtime provenance verified for patch $expectedPatchSha")
    }
}

android {
    namespace = "com.muyuchat.core.nativebridge"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        ndk {
            abiFilters += mcaAbiFilters
        }

        externalNativeBuild {
            cmake {
                val cmakeArgs = mutableListOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_MESSAGE_LOG_LEVEL=STATUS",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DMCA_WITH_LLAMA_CPP=${if (mcaWithLlamaCpp) "ON" else "OFF"}",
                    "-DMCA_WITH_MNN_DIFFUSION=${if (mcaWithMnnDiffusion) "ON" else "OFF"}"
                )
                mcaMnnSourceRoot?.absolutePath?.let {
                    cmakeArgs += "-DMCA_MNN_SOURCE_ROOT=$it"
                }
                mcaMnnAndroidBuildRoot.orNull?.takeIf { it.isNotBlank() }?.let {
                    cmakeArgs += "-DMCA_MNN_ANDROID_BUILD_ROOT=$it"
                }
                mcaQnnSdkRootForCMake?.absolutePath?.let {
                    cmakeArgs += "-DMCA_QNN_SDK_ROOT=$it"
                }
                arguments += cmakeArgs
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(mcaMnnGeneratedJniLibs)
            jniLibs.srcDir(mcaQnnGeneratedJniLibs)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Product builds require both the typed QNN SDK and the exact pinned MNN overlay.
// Debug builds keep the existing stub-friendly workflow unless an explicit
// typed/vendor property opts into the corresponding gate. A debug-only runtime
// compatibility experiment may explicitly set mcaMnnVendorRequired=false; it
// must never weaken a top-level release invocation.
tasks.configureEach {
    val lowerName = name.lowercase()
    val isProductNativeBuildTask =
        lowerName == "prebuild" ||
            lowerName.startsWith("predebugbuild") ||
            lowerName.startsWith("prereleasebuild") ||
            lowerName.startsWith("externalnativebuild") ||
            lowerName.startsWith("configurecmake") ||
            lowerName.startsWith("buildcmake")
    val isReleaseTask = "release" in lowerName
    if (isProductNativeBuildTask) {
        if (isReleaseTask && !mcaMnnDebugRuntimeExperiment) {
            dependsOn(verifyMcaQnnSdkHeaders)
            dependsOn(verifyMcaMnnVendor)
        } else if (mcaQnnTypedBindingsRequired == true) {
            dependsOn(verifyMcaQnnSdkHeaders)
            if (mcaMnnVendorRequired != false) {
                dependsOn(verifyMcaMnnVendor)
            }
        } else if (mcaMnnVendorRequired == true) {
            dependsOn(verifyMcaMnnVendor)
        }
    }
}

// Always synchronize the generated MNN directory.  If a configuration starts
// while the external runtime is incomplete, leaving this task unregistered
// allows libraries from an older experiment to remain in generated/ and be
// silently packaged into a new APK.  Clear the directory first, then copy one
// verified coherent runtime set atomically from the selected build root.
val prepareMcaMnnJniLibs = tasks.register("prepareMcaMnnJniLibs") {
    inputs.property("mcaMnnRuntimeLayout", mcaMnnRuntimeSelection?.layout ?: "none")
    inputs.files(mcaMnnRuntimeLibs)
    outputs.dir(mcaMnnGeneratedJniLibs)
    if (mcaMnnRuntimeSelection != null) {
        dependsOn(verifyMcaMnnRuntimeStamp)
    }
    doLast {
        val outputRoot = mcaMnnGeneratedJniLibs.get().asFile
        delete(outputRoot)
        if (mcaMnnRuntimeLibs.isNotEmpty()) {
            copy {
                from(mcaMnnRuntimeLibs)
                into(outputRoot.resolve("arm64-v8a"))
            }
        }
    }
}
tasks.named("preBuild") {
    dependsOn(prepareMcaMnnJniLibs)
}

// Always synchronize this generated directory, including when no external QNN
// runtime is requested. Without an always-present task, a previous
// `mcaQnnRuntimeOverrideGenieX=true` validation build leaves its System/HTP
// libraries behind and a following standard build attempts to merge those
// stale libraries with GenieX's copies.
val prepareMcaQnnJniLibs = tasks.register("prepareMcaQnnJniLibs") {
    inputs.property("mcaQnnRuntimeOverrideGenieX", mcaQnnRuntimeOverrideGenieX)
    inputs.files(mcaQnnRuntimeLibs)
    outputs.dir(mcaQnnGeneratedJniLibs)
    doLast {
        val outputRoot = mcaQnnGeneratedJniLibs.get().asFile
        delete(outputRoot)
        if (mcaQnnRuntimeLibs.isNotEmpty()) {
            copy {
                from(mcaQnnRuntimeLibs)
                into(outputRoot.resolve("arm64-v8a"))
            }
        }
    }
}
tasks.named("preBuild") {
    dependsOn(prepareMcaQnnJniLibs)
}


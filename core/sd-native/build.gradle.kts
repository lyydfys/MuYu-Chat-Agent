import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val configuredNdkVersion = libs.versions.ndk.get()
val stableDiffusionDir = rootProject.layout.projectDirectory.dir("third_party/stable-diffusion.cpp")
val stableDiffusionPatch = rootProject.layout.projectDirectory.file("third_party/patches/stable-diffusion.cpp-mca-android.patch")
val stableDiffusionGgmlPropertiesFile = rootProject.file("vendor/stable-diffusion-ggml.properties")
require(stableDiffusionGgmlPropertiesFile.isFile) {
    "Nested ggml provenance manifest is missing at ${stableDiffusionGgmlPropertiesFile.absolutePath}."
}
val stableDiffusionGgmlProperties = Properties().apply {
    stableDiffusionGgmlPropertiesFile.inputStream().use { load(it) }
}
fun requiredStableDiffusionGgmlProperty(name: String): String =
    stableDiffusionGgmlProperties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: throw GradleException("Nested ggml provenance manifest is missing required '$name'.")

val stableDiffusionGgmlRepository = requiredStableDiffusionGgmlProperty("repository")
val stableDiffusionGgmlCommit = requiredStableDiffusionGgmlProperty("baseCommit")
val stableDiffusionGgmlPatchPath = requiredStableDiffusionGgmlProperty("patch")
val stableDiffusionGgmlPatchSha256 = requiredStableDiffusionGgmlProperty("patchSha256").uppercase()
require(stableDiffusionGgmlRepository.startsWith("https://")) {
    "Nested ggml repository must use an HTTPS URL."
}
require(stableDiffusionGgmlCommit.matches(Regex("[0-9a-fA-F]{40}"))) {
    "Nested ggml baseCommit must be a full 40-character Git revision."
}
require(stableDiffusionGgmlPatchSha256.matches(Regex("[0-9A-F]{64}"))) {
    "Nested ggml patchSha256 must be a 64-character SHA-256 value."
}
require(!File(stableDiffusionGgmlPatchPath).isAbsolute &&
    stableDiffusionGgmlPatchPath.split('/', '\\').none { it == ".." }) {
    "Nested ggml patch must be a repository-relative path without parent traversal."
}
val stableDiffusionGgmlPatch = rootProject.layout.projectDirectory.file(stableDiffusionGgmlPatchPath)
val mcaAbiFilters = providers.gradleProperty("mca.abis")
    .orElse("arm64-v8a,x86_64")
    .get()
    .split(",")
    .map { it.trim() }
    .filter { it.isNotBlank() }

val wanEnginePrebuiltDir = layout.projectDirectory.dir("prebuilt")
val wanEngineArchive = wanEnginePrebuiltDir.file("wan-engine-arm64-v8a.zip")
val wanEngineBaselineFile = wanEnginePrebuiltDir.file("WAN_ENGINE_BASELINE.properties")
require(wanEngineArchive.asFile.isFile) {
    "Pinned Wan engine archive is missing at ${wanEngineArchive.asFile.absolutePath}."
}
require(wanEngineBaselineFile.asFile.isFile) {
    "Wan engine baseline is missing at ${wanEngineBaselineFile.asFile.absolutePath}."
}
val wanEngineBaseline = Properties().apply {
    wanEngineBaselineFile.asFile.inputStream().use { load(it) }
}
val wanEngineArchiveSha256 = wanEngineBaseline.getProperty("archiveSha256")?.trim()?.lowercase()
    ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    ?: throw GradleException("Wan engine baseline is missing a valid archiveSha256.")
val wanEngineFiles = (1..3).map { index ->
    fun property(key: String): String = wanEngineBaseline.getProperty("file$index.$key")
        ?.trim()?.takeIf(String::isNotEmpty)
        ?: throw GradleException("Wan engine baseline is missing file$index.$key.")
    val path = property("path")
    require(!File(path).isAbsolute && path.split('/', '\\').none { it == ".." }) {
        "Wan engine baseline file$index.path must be a safe relative path."
    }
    val sha256 = property("sha256").lowercase()
    require(sha256.matches(Regex("[0-9a-f]{64}"))) {
        "Wan engine baseline file$index.sha256 is not a SHA-256."
    }
    Triple(path, property("bytes").toLong(), sha256)
}

fun wanEngineSha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

val unpackWanEngine by tasks.registering {
    group = "build setup"
    description = "Extracts and verifies the pinned external Wan engine before packaging."

    inputs.file(wanEngineArchive)
    inputs.file(wanEngineBaselineFile)
    outputs.files(wanEngineFiles.map { layout.projectDirectory.file("src/main/jniLibs/${it.first}").asFile })
    // The archive replaces a 162 MB shared library, so verify the shipped bytes on
    // every build but skip the 46 MB extraction when they already match.
    outputs.upToDateWhen { false }

    doLast {
        val archive = wanEngineArchive.asFile
        val actualArchiveSha256 = wanEngineSha256(archive)
        if (actualArchiveSha256 != wanEngineArchiveSha256) {
            throw GradleException(
                "Pinned Wan engine archive SHA-256 mismatch: expected $wanEngineArchiveSha256, " +
                    "got $actualArchiveSha256."
            )
        }
        val jniRoot = layout.projectDirectory.dir("src/main/jniLibs").asFile
        val needsExtract = wanEngineFiles.any { (path, bytes, sha256) ->
            val file = File(jniRoot, path)
            !file.isFile || file.length() != bytes || wanEngineSha256(file) != sha256
        }
        if (needsExtract) {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = File(jniRoot, entry.name).canonicalFile
                    if (!target.toPath().startsWith(jniRoot.canonicalFile.toPath())) {
                        throw GradleException("Wan engine archive entry escapes jniLibs: ${entry.name}")
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output -> zip.copyTo(output) }
                }
            }
        }
        wanEngineFiles.forEach { (path, bytes, sha256) ->
            val file = File(jniRoot, path)
            if (!file.isFile) {
                throw GradleException("Wan engine file was not extracted: ${file.absolutePath}")
            }
            if (file.length() != bytes) {
                throw GradleException("Wan engine size mismatch for $path: expected $bytes, got ${file.length()}.")
            }
            val actual = wanEngineSha256(file)
            if (actual != sha256) {
                throw GradleException("Wan engine SHA-256 mismatch for $path: expected $sha256, got $actual.")
            }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(unpackWanEngine)
}

val patchStableDiffusionCpp by tasks.registering {
    group = "build setup"
    description = "Applies MCA Android patches to the stable-diffusion.cpp submodule when needed."

    doLast {
        val sourceDir = stableDiffusionDir.asFile
        val patchFile = stableDiffusionPatch.asFile
        require(sourceDir.resolve("CMakeLists.txt").isFile) {
            "stable-diffusion.cpp source is missing at ${sourceDir.absolutePath}. Run git submodule update --init --recursive."
        }
        require(patchFile.isFile) {
            "MCA stable-diffusion.cpp patch is missing at ${patchFile.absolutePath}."
        }

        fun gitApply(vararg args: String) = exec {
            workingDir = sourceDir
            commandLine("git", "apply", *args)
            isIgnoreExitValue = true
        }

        val whitespaceArgs = arrayOf("--ignore-space-change", "--ignore-whitespace")
        val alreadyApplied = gitApply("--reverse", "--check", *whitespaceArgs, patchFile.absolutePath)
        if (alreadyApplied.exitValue == 0) return@doLast

        val canApply = gitApply("--check", *whitespaceArgs, patchFile.absolutePath)
        if (canApply.exitValue != 0) {
            throw GradleException(
                "stable-diffusion.cpp is neither cleanly patchable nor already patched. " +
                    "Reset/update the submodule, then rebuild."
            )
        }

        val applied = gitApply(patchFile.absolutePath)
        if (applied.exitValue != 0) {
            throw GradleException("Failed to apply MCA stable-diffusion.cpp patch.")
        }
    }
}

val patchStableDiffusionGgmlWan by tasks.registering {
    group = "build setup"
    description = "Applies the pinned MCA Wan 5D GGUF compatibility patch to nested ggml."
    dependsOn(patchStableDiffusionCpp)

    doLast {
        val ggmlDir = stableDiffusionDir.dir("ggml").asFile
        val patchFile = stableDiffusionGgmlPatch.asFile
        require(ggmlDir.resolve("src/gguf.cpp").isFile) {
            "nested ggml source is missing. Run git submodule update --init --recursive."
        }
        require(patchFile.isFile) {
            "MCA nested ggml patch is missing at ${patchFile.absolutePath}."
        }
        val actualPatchSha256 = MessageDigest.getInstance("SHA-256")
            .digest(patchFile.readBytes())
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        require(actualPatchSha256 == stableDiffusionGgmlPatchSha256) {
            "Nested ggml patch SHA-256 mismatch: expected $stableDiffusionGgmlPatchSha256, " +
                "found $actualPatchSha256; refusing to modify the submodule."
        }

        val revisionOutput = ByteArrayOutputStream()
        val revisionResult = exec {
            workingDir = ggmlDir
            commandLine("git", "rev-parse", "HEAD")
            standardOutput = revisionOutput
            isIgnoreExitValue = true
        }
        require(revisionResult.exitValue == 0) {
            "Unable to read nested ggml revision. Initialize stable-diffusion.cpp recursively."
        }
        val actualRevision = revisionOutput.toString().trim()
        require(actualRevision == stableDiffusionGgmlCommit) {
            "Nested ggml revision mismatch: expected $stableDiffusionGgmlCommit, found $actualRevision."
        }

        fun gitApply(vararg args: String) = exec {
            workingDir = ggmlDir
            commandLine("git", "apply", *args)
            isIgnoreExitValue = true
        }

        val alreadyApplied = gitApply(
            "--reverse", "--check", "--unidiff-zero", "--ignore-space-change",
            "--ignore-whitespace", patchFile.absolutePath,
        )
        if (alreadyApplied.exitValue == 0) return@doLast

        val canApply = gitApply(
            "--check", "--unidiff-zero", "--ignore-space-change",
            "--ignore-whitespace", patchFile.absolutePath,
        )
        if (canApply.exitValue != 0) {
            throw GradleException(
                "Nested ggml is neither at the expected patch baseline nor already patched. " +
                    "Restore the pinned submodule revision before rebuilding."
            )
        }

        val applied = gitApply("--unidiff-zero", patchFile.absolutePath)
        if (applied.exitValue != 0) {
            throw GradleException("Failed to apply the MCA nested ggml Wan 5D patch.")
        }
    }
}

android {
    namespace = "com.muyuchat.core.sdnative"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        ndk {
            abiFilters += mcaAbiFilters
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_MESSAGE_LOG_LEVEL=STATUS",
                    "-DSD_BUILD_EXAMPLES=OFF",
                    "-DSD_WEBP=OFF",
                    "-DSD_WEBM=OFF",
                    "-DSD_BUILD_SHARED_LIBS=OFF",
                    "-DSD_BUILD_SHARED_GGML_LIB=OFF",
                    "-DSD_VULKAN=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_VULKAN=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DGGML_LLAMAFILE=OFF"
                )
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
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") || name.startsWith("externalNativeBuild")) {
        dependsOn(patchStableDiffusionGgmlWan)
    }
}

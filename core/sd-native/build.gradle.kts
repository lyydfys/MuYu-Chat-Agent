plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val configuredNdkVersion = libs.versions.ndk.get()
val stableDiffusionDir = rootProject.layout.projectDirectory.dir("third_party/stable-diffusion.cpp")
val stableDiffusionPatch = rootProject.layout.projectDirectory.file("third_party/patches/stable-diffusion.cpp-mca-android.patch")
val mcaAbiFilters = providers.gradleProperty("mca.abis")
    .orElse("arm64-v8a,x86_64")
    .get()
    .split(",")
    .map { it.trim() }
    .filter { it.isNotBlank() }

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
        dependsOn(patchStableDiffusionCpp)
    }
}

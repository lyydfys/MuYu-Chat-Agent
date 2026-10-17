package com.muyuchat.core.engine

import java.io.File
import java.security.MessageDigest

internal fun LocalChatRuntime.usesCoordinatedParameters(): Boolean =
        true

internal fun McaInferenceService.defaultRuntimeIdentity(
        modelPath: String,
        runtime: LocalChatRuntime,
        params: LoadParams,
        artifactFingerprintOverride: String? = null
    ): ModelRuntimeIdentity {
        val model = File(modelPath)
        val artifactMetadata = buildString {
            append(model.name).append('\n')
            append(model.length()).append('\n')
            append(model.lastModified()).append('\n')
            if (model.isDirectory) {
                model.listFiles().orEmpty()
                    .sortedBy { it.name }
                    .take(128)
                    .forEach { file ->
                        append(file.name).append(':').append(file.length()).append(':')
                            .append(file.lastModified()).append('\n')
                    }
            }
        }
        val projectorFingerprint = params.visionProjectorPath
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                val projector = File(path)
                parameterSha256("${projector.name}:${projector.length()}:${projector.lastModified()}")
            }
            .orEmpty()
        return ModelRuntimeIdentity(
            modelId = model.name.ifBlank { "local-model" },
            artifactFingerprint = artifactFingerprintOverride ?: parameterSha256(artifactMetadata),
            runtime = runtime,
            runtimeVersion = when (runtime) {
                LocalChatRuntime.MNN_CPU -> "mnn-embedded"
                LocalChatRuntime.LLAMA_CPP -> "llama.cpp-embedded"
                LocalChatRuntime.GENIEX_LLAMA_CPP,
                LocalChatRuntime.GENIEX_QAIRT -> "geniex-embedded"
                LocalChatRuntime.LITERT_LM -> "litert-lm@0.16.1"
            },
            nativeLibrarySha256 = "embedded-unreported",
            backendFingerprint = listOfNotNull(
                runtime.backendId,
                params.geniexComputeUnit?.takeIf { it.isNotBlank() }
            ).joinToString(":"),
            projectorFingerprint = projectorFingerprint,
            deviceCapabilityFingerprint = parameterSha256(socInfo.toString()),
            installationScopeId = parameterInstallationScopeId,
            capabilities = if (runtime == LocalChatRuntime.MNN_CPU && isMnnVisualProfile(modelPath, params)) {
                setOf("vision", "mnn_vision_v1")
            } else {
                emptySet()
            }
        )
    }

internal fun McaInferenceService.isMnnVisualProfile(modelPath: String, params: LoadParams): Boolean {
        return isMnnVisualProfileForLoad(modelPath, params.advancedJson)
    }

internal fun parameterSha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

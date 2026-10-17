package com.muyuchat.core.modelstore

import java.io.File

/**
 * Selects the contract to apply before an MNN-backed engine is loaded.
 *
 * The standard root-level MNN LLM layout is intentionally only a chat contract.
 * Vision and image engines use their own manifests and component layouts.
 */
enum class MnnBundleLoadScope {
    CHAT_LLM,
    VISION_ENGINE,
    IMAGE_ENGINE
}

enum class MnnBundleReadinessStatus {
    READY,
    BLOCKED,
    NOT_APPLICABLE
}

enum class MnnBundleDiagnosticSeverity {
    INFO,
    ERROR
}

enum class MnnBundleDiagnosticCode {
    BUNDLE_DIRECTORY_MISSING,
    BUNDLE_PATH_NOT_DIRECTORY,
    REQUIRED_COMPONENT_MISSING,
    REQUIRED_COMPONENT_EMPTY,
    REQUIRED_COMPONENT_NOT_FILE,
    REQUIRED_COMPONENT_UNREADABLE,
    CONFIG_DECLARED_COMPONENT_PATH_INVALID,
    TIE_EMBEDDINGS_INVALID,
    LEGACY_VISUAL_GRAPH_RUNTIME_INCOMPATIBLE,
    CHAT_LLM_CONTRACT_NOT_APPLICABLE
}

data class MnnBundleDiagnostic(
    val code: MnnBundleDiagnosticCode,
    val severity: MnnBundleDiagnosticSeverity,
    val path: String? = null,
    val message: String
)

/**
 * A structured pre-load result for one MNN bundle contract.
 *
 * [canLoad] means this check does not block the caller. A NOT_APPLICABLE result
 * is non-blocking because a different engine-specific validator owns that layout.
 */
data class MnnBundleReadiness(
    val scope: MnnBundleLoadScope,
    val status: MnnBundleReadinessStatus,
    /**
     * Concrete component paths selected while evaluating the contract.
     *
     * Alternatives are resolved to the usable file that was found. Callers use
     * this list for the persisted bundle fingerprint, so a tokenizer or model
     * sidecar cannot disappear after a successful registration without being
     * noticed on the next preflight.
     */
    val requiredComponentPaths: List<String> = emptyList(),
    val missingRequiredComponents: List<String> = emptyList(),
    val invalidRequiredComponents: List<String> = emptyList(),
    val diagnostics: List<MnnBundleDiagnostic> = emptyList()
) {
    val canLoad: Boolean
        get() = status != MnnBundleReadinessStatus.BLOCKED

    val applies: Boolean
        get() = status != MnnBundleReadinessStatus.NOT_APPLICABLE

    fun diagnosticSummary(): String = when (status) {
        MnnBundleReadinessStatus.READY -> "MNN chat LLM bundle is ready."
        MnnBundleReadinessStatus.NOT_APPLICABLE ->
            "The standard MNN chat LLM bundle contract does not apply to ${scope.displayName()}."
        MnnBundleReadinessStatus.BLOCKED -> buildString {
            append("MNN chat LLM bundle is blocked")
            if (missingRequiredComponents.isNotEmpty()) {
                append(": missing ")
                append(missingRequiredComponents.joinToString(", "))
            }
            if (invalidRequiredComponents.isNotEmpty()) {
                append(if (missingRequiredComponents.isEmpty()) ": unusable " else "; unusable ")
                append(invalidRequiredComponents.joinToString(", "))
            }
            if (missingRequiredComponents.isEmpty() && invalidRequiredComponents.isEmpty()) {
                diagnostics.firstOrNull()?.message?.let { message ->
                    append(": ")
                    append(message)
                }
            }
            append('.')
        }
    }
}

/**
 * Checks only the standard MNN chat-LLM root layout before native loading.
 *
 * It deliberately does not validate vision or image MNN bundles: their components
 * can be nested and have engine-specific contracts.
 */
object MnnBundleReadinessAnalyzer {
    fun analyze(
        bundleDir: File,
        scope: MnnBundleLoadScope = MnnBundleLoadScope.CHAT_LLM,
        /**
         * Model manifests can declare extra assets without weakening the base
         * chat contract. This keeps the validator forward-compatible with
         * future config-driven visual/audio package layouts.
         */
        additionalRequiredComponents: List<String> = emptyList(),
        importMode: MnnImportMode = MnnImportMode.FULL
    ): MnnBundleReadiness {
        if (scope != MnnBundleLoadScope.CHAT_LLM) return notApplicable(scope)
        if (!bundleDir.exists()) {
            return blocked(
                diagnostic = MnnBundleDiagnostic(
                    code = MnnBundleDiagnosticCode.BUNDLE_DIRECTORY_MISSING,
                    severity = MnnBundleDiagnosticSeverity.ERROR,
                    path = bundleDir.absolutePath,
                    message = "MNN chat bundle directory does not exist."
                )
            )
        }
        if (!bundleDir.isDirectory) {
            return blocked(
                diagnostic = MnnBundleDiagnostic(
                    code = MnnBundleDiagnosticCode.BUNDLE_PATH_NOT_DIRECTORY,
                    severity = MnnBundleDiagnosticSeverity.ERROR,
                    path = bundleDir.absolutePath,
                    message = "MNN chat bundle path is not a directory."
                )
            )
        }

        val missing = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        val diagnostics = mutableListOf<MnnBundleDiagnostic>()
        val usable = mutableListOf<String>()
        val requirements = (
            configDrivenChatLlmRequirements(bundleDir, invalid, diagnostics, importMode) +
                additionalRequiredComponents.map(::declaredRequirement)
            ).distinctBy { requirement -> requirement.paths }
        requirements.forEach { requirement ->
            inspectRequirement(bundleDir, requirement, usable, missing, invalid, diagnostics)
        }
        if (importMode == MnnImportMode.FULL) legacyVisualGraphRuntimeDiagnostic(bundleDir)?.let(diagnostics::add)
        return if (diagnostics.isEmpty()) {
            MnnBundleReadiness(
                scope = MnnBundleLoadScope.CHAT_LLM,
                status = MnnBundleReadinessStatus.READY,
                requiredComponentPaths = usable.distinct()
            )
        } else {
            MnnBundleReadiness(
                scope = MnnBundleLoadScope.CHAT_LLM,
                status = MnnBundleReadinessStatus.BLOCKED,
                requiredComponentPaths = usable.distinct(),
                missingRequiredComponents = missing,
                invalidRequiredComponents = invalid,
                diagnostics = diagnostics
            )
        }
    }

    internal data class TieEmbeddingDescriptor(
        val weightOffset: Long,
        val alphaOffset: Long,
        val alphaSize: Long,
        val quantBit: Long,
        val quantBlock: Long,
        val alphaFp16: Boolean
    )

    internal data class MnnBundleRequirement(
        val paths: List<String>,
        val displayName: String
    ) {
        companion object {
            fun single(path: String): MnnBundleRequirement = MnnBundleRequirement(
                paths = listOf(path),
                displayName = path
            )

            fun alternatives(vararg paths: String): MnnBundleRequirement = MnnBundleRequirement(
                paths = paths.toList(),
                displayName = paths.joinToString(" or ")
            )
        }
    }

}

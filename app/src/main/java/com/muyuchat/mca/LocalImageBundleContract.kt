package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineIntegrityMetadataStatus
import com.muyuchat.core.download.ModelBundleComponentVerificationStatus
import com.muyuchat.core.download.ModelBundleInstaller
import java.io.File

internal object LocalImageBundleContract {
    val sanaRequiredComponentPaths: List<String> = listOf(
        "config.json",
        "llm/config.json",
        "llm/llm_config.json",
        "llm/llm.mnn",
        "llm/llm.mnn.weight",
        "llm/tokenizer.txt",
        "llm/meta_queries.mnn",
        "connector.mnn",
        "connector.mnn.weight",
        "projector.mnn",
        "projector.mnn.weight",
        "transformer.mnn",
        "transformer.mnn.weight",
        "vae_decoder.mnn",
        "vae_decoder.mnn.weight",
        "vae_encoder.mnn",
        "vae_encoder.mnn.weight"
    )

    private val stableDiffusion15RequiredComponentPaths = listOf(
        "text_encoder.mnn",
        "text_encoder.mnn.weight",
        "unet.mnn",
        "unet.mnn.weight",
        "vae_decoder.mnn",
        "vae_decoder.mnn.weight"
    )

    private const val TOKENIZER_REQUIREMENT =
        "tokenizer.mtok/tokenizer.txt or vocab.json + merges.txt"

    fun inspectMnnBundle(
        bundleRoot: File?,
        primaryFile: File,
        family: LocalImageModelFamily
    ): MnnBundleContractCheck {
        val candidates = buildList {
            primaryFile.parentFile?.takeIf { it.isDirectory }?.let(::add)
            bundleRoot?.takeIf { it.isDirectory }?.let(::add)
        }.distinctBy { root ->
            runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        }
        if (candidates.isEmpty()) {
            return MnnBundleContractCheck(
                root = null,
                missingComponents = expectedComponents(family)
            )
        }

        val checks = candidates.map { root ->
            MnnBundleContractCheck(
                root = root,
                missingComponents = missingComponents(root, family),
                integrityMessage = integrityMessage(root)
            )
        }
        return checks.firstOrNull { it.missingComponents.isEmpty() && it.integrityMessage == null }
            ?: checks.minWithOrNull(compareBy<MnnBundleContractCheck> { it.missingComponents.size }
                .thenBy { if (it.integrityMessage == null) 0 else 1 })
            ?: MnnBundleContractCheck(root = null, missingComponents = expectedComponents(family))
    }

    private fun integrityMessage(root: File): String? {
        val verification = ModelBundleInstaller().verifyInstalledBundle(root)
        if (!verification.auditFile.isFile) return null
        if (!verification.auditReadable) {
            return "Downloaded bundle integrity audit is unreadable; download it again."
        }
        val changed = verification.components.filter { component ->
            component.status !in setOf(
                ModelBundleComponentVerificationStatus.MATCHED_SOURCE_SHA256,
                ModelBundleComponentVerificationStatus.MATCHED_OBSERVED_DIGEST
            )
        }
        if (changed.isNotEmpty()) {
            return "Downloaded bundle integrity audit failed: " +
                changed.joinToString(", ") { "${it.audit.relativePath} (${it.status.name})" } + "."
        }
        val sourceUnknown = verification.components.filter {
            it.audit.sourceMetadataStatus != ImageEngineIntegrityMetadataStatus.SOURCE_SHA256
        }
        return sourceUnknown.takeIf { it.isNotEmpty() }?.let { unknown ->
            "Publisher SHA-256 is unavailable for: " +
                unknown.joinToString(", ") { it.audit.relativePath } +
                ". Local SHA-256 was recorded after download, but source verification is unavailable."
        }
    }

    private fun expectedComponents(family: LocalImageModelFamily): List<String> =
        if (family == LocalImageModelFamily.SANA) {
            sanaRequiredComponentPaths
        } else {
            stableDiffusion15RequiredComponentPaths + TOKENIZER_REQUIREMENT
        }

    private fun missingComponents(root: File, family: LocalImageModelFamily): List<String> {
        if (family == LocalImageModelFamily.SANA) {
            return sanaRequiredComponentPaths.filterNot { path -> root.hasNonEmptyFile(path) }
        }

        return buildList {
            addAll(stableDiffusion15RequiredComponentPaths.filterNot { path -> root.hasNonEmptyFile(path) })
            val hasTokenizer = root.hasNonEmptyFile("tokenizer.mtok") ||
                root.hasNonEmptyFile("tokenizer.txt") ||
                (root.hasNonEmptyFile("vocab.json") && root.hasNonEmptyFile("merges.txt"))
            if (!hasTokenizer) add(TOKENIZER_REQUIREMENT)
        }
    }

    private fun File.hasNonEmptyFile(relativePath: String): Boolean {
        val file = File(this, relativePath.replace('/', File.separatorChar))
        return file.isFile && file.length() > 0L
    }
}

internal data class MnnBundleContractCheck(
    val root: File?,
    val missingComponents: List<String>,
    val integrityMessage: String? = null
) {
    fun readinessMessage(family: LocalImageModelFamily): String? {
        if (root == null) {
            return "MNN-Diffusion image engine requires a complete resource directory."
        }
        integrityMessage?.let { return it }
        if (missingComponents.isEmpty()) return null
        return if (family == LocalImageModelFamily.SANA) {
            "MNN Sana bundle is incomplete: ${missingComponents.joinToString(", ")}."
        } else {
            "MNN-Diffusion Stable Diffusion 1.5 bundle is incomplete: " +
                "${missingComponents.joinToString(", ")}."
        }
    }
}

internal data class MnnVerificationRoute(
    val family: LocalImageModelFamily,
    val steps: Int,
    val width: Int,
    val height: Int,
    val requiresUnetPreflight: Boolean
)

internal fun mnnVerificationRoute(
    modelFamily: LocalImageModelFamily,
    manifest: LocalImageBundleManifest?
): MnnVerificationRoute {
    val family = manifest?.family ?: modelFamily
    val isSana = family == LocalImageModelFamily.SANA
    return MnnVerificationRoute(
        family = family,
        steps = if (isSana) manifest?.smokeSteps?.coerceIn(2, 50) ?: 2 else 20,
        width = if (isSana) manifest?.smokeWidth.toSmokeDimension() else 512,
        height = if (isSana) manifest?.smokeHeight.toSmokeDimension() else 512,
        requiresUnetPreflight = !isSana
    )
}

private fun Int?.toSmokeDimension(): Int =
    this?.takeIf { it > 0 }?.coerceIn(256, 1536) ?: 512

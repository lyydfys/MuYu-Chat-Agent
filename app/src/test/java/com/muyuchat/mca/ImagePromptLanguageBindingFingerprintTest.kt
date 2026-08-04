package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImagePromptLanguageBindingFingerprintTest {
    @Test
    fun `request scoped execution assets preserve the pre capture prompt fingerprint`() {
        val base = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = "qwen_image_2512_q2",
                modelFingerprint = "a".repeat(64)
            )
        )
        val baseFingerprint = base.promptLanguageBindingFingerprint
        val root = File(requireNotNull(System.getProperty("java.io.tmpdir"))).absoluteFile
        val binding = TextualInversionExecutionAssetBinding(
            runtime = TextualInversionRuntime.STABLE_DIFFUSION_CPP,
            bundleRoot = root.path,
            profilePromptFingerprint = baseFingerprint,
            assets = listOf(
                TextualInversionExecutionAssetDescriptor(
                    label = "model.bin",
                    path = File(root, "model.bin").absolutePath,
                    sizeBytes = 1L,
                    sha256 = "b".repeat(64),
                    fileKey = null,
                    lastModifiedMillis = 0L
                )
            )
        )
        val captured = base.copy(
            modelFingerprint = "b".repeat(64),
            tokenizer = base.tokenizer.copy(
                assets = listOf(ImageProfileAsset("model.bin", "b".repeat(64), 1L))
            ),
            textualInversionExecutionAssets = binding
        )

        assertEquals(baseFingerprint, captured.promptLanguageBindingFingerprint)
        assertNotEquals(
            baseFingerprint,
            captured.copy(textualInversionExecutionAssets = null)
                .promptLanguageBindingFingerprint
        )
    }

    @Test
    fun `prompt language binding changes when provenance authority changes`() {
        val builtIn = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = "qwen_image_2512_q2",
                modelFingerprint = "a".repeat(64)
            )
        )
        val manifest = builtIn.copy(
            provenance = ImageProfileProvenance(
                primarySource = ImageProfileSource.MANIFEST,
                sources = listOf(ImageProfileSource.MANIFEST)
            )
        )
        val overriddenBuiltIn = builtIn.copy(
            provenance = ImageProfileProvenance(
                primarySource = ImageProfileSource.USER_OVERRIDE,
                sources = listOf(ImageProfileSource.USER_OVERRIDE, ImageProfileSource.BUILT_IN)
            )
        )

        assertNotEquals(
            builtIn.promptLanguageBindingFingerprint,
            manifest.promptLanguageBindingFingerprint
        )
        assertNotEquals(
            builtIn.promptLanguageBindingFingerprint,
            overriddenBuiltIn.promptLanguageBindingFingerprint
        )
        assertNotEquals(
            overriddenBuiltIn.promptLanguageBindingFingerprint,
            overriddenBuiltIn.copy(
                provenance = overriddenBuiltIn.provenance.copy(
                    sources = overriddenBuiltIn.provenance.sources.reversed()
                )
            ).promptLanguageBindingFingerprint
        )
    }
}

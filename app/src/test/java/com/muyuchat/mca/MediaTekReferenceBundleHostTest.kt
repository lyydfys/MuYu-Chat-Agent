package com.muyuchat.mca

import com.muyuchat.core.mediatek.MediaTekAotConditioningMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host readiness checks for the real importable v4 bundles built by
 * tools/mediatek/build_v4_bundle.py. The bundles are large and local-only, so a
 * test is skipped when its bundle has not been built.
 */
class MediaTekReferenceBundleHostTest {
    @Test
    fun realFixedContextBundleIsReadyForTheProductionImporter() {
        assertBundle(
            path = "../build/v4-bundle-fixed",
            expected = MediaTekAotConditioningMode.FIXED_CONTEXT,
        )
    }

    @Test
    fun realDynamicTextBundleIsReadyForTheProductionImporter() {
        assertBundle(
            path = "../build/v4-bundle-dynamic",
            expected = MediaTekAotConditioningMode.DYNAMIC_TEXT,
        )
    }

    private fun assertBundle(path: String, expected: MediaTekAotConditioningMode) {
        val root = File(path)
        assumeTrue("v4 bundle is not present: $path", root.isDirectory)
        val inspection = inspectLocalImageBundleManifestFromRoot(root)
        assertTrue("expected a ready bundle, got $inspection", inspection is LocalImageBundleManifestInspection.Ready)
        val manifest = (inspection as LocalImageBundleManifestInspection.Ready).manifest
        assertEquals(LocalImageRuntime.MEDIATEK_NEURON, manifest.runtime)
        assertEquals(LocalImageModelFamily.ANIMA, manifest.family)
        assertEquals("TEXT_TO_IMAGE", manifest.task)
        val aot = requireNotNull(manifest.mediatekAotManifest)
        assertTrue("bundle reports blockers: ${aot.executionBlockers()}", aot.executionBlockers().isEmpty())
        assertEquals(4, aot.variants.size)
        val profile = aot.profiles.single()
        assertEquals(512, profile.widthPixels)
        assertEquals(512, profile.heightPixels)
        assertEquals(setOf(6), profile.supportedSteps)
        assertEquals(expected, aot.conditioning.mode)
        when (expected) {
            MediaTekAotConditioningMode.FIXED_CONTEXT -> assertTrue(aot.conditioning.contextAsset != null)
            MediaTekAotConditioningMode.DYNAMIC_TEXT -> assertTrue(aot.conditioning.textAssets.isNotEmpty())
        }
        assertTrue(manifest.components.isNotEmpty())
        manifest.components.forEach { component ->
            assertTrue(
                "component ${component.relativePath} is missing from the bundle",
                File(root, component.relativePath).isFile,
            )
        }
        val primary = File(root, manifest.declaredPrimaryPath ?: error("bundle has no declared primary"))
        val record = LocalImageModelRecord(
            displayName = "v4",
            path = primary.absolutePath,
            fileName = primary.name,
            sizeBytes = primary.length(),
            sha256 = observedLocalImageFileSha256(primary),
            runtime = LocalImageRuntime.MEDIATEK_NEURON,
            family = LocalImageModelFamily.ANIMA,
            bundleRoot = root.absolutePath,
        )
        val contract = requireNotNull(record.mediaTekImageProductContractOrNull()) {
            "the real v4 bundle must expose its declared product contract"
        }
        assertFalse(contract.supportsNegativePrompt)
        assertEquals(512, contract.defaultProfile.width)
        assertEquals(512, contract.defaultProfile.height)
        assertEquals(setOf(6), contract.defaultProfile.supportedSteps)
        assertEquals(1.0f, contract.defaultProfile.fixedCfgScale)
        val defaults = record.legacyExecutionDefaultsForUi()
        assertEquals(6, defaults.steps)
        assertEquals(1.0, defaults.cfgScale, 0.0)
        assertEquals("euler", defaults.sampler)
        val normalized = record.normalizedMediaTekImageOptions(
            LocalImageGenerationOptions(
                width = 512,
                height = 512,
                steps = 6,
                cfgScale = 1.0,
                sampleMethod = "euler",
            )
        )
        assertNull(normalized.sampleMethod)
    }
}

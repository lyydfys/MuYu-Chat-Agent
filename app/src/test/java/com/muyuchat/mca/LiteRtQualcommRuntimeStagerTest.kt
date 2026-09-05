package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtQualcommRuntimeStagerTest {
    @Test
    fun graphOnlyManifestSuppliesThePackagedRuntimeSdk() {
        assertEquals(
            "2.47.0.260601114230",
            qnnSdkForPackagedImageRuntime(
                LocalImageBundleManifest(executionProfileQnnSdk = "2.47.0.260601114230")
            )
        )
        assertEquals(
            "2.45",
            qnnSdkForPackagedImageRuntime(
                LocalImageBundleManifest(
                    executionProfileQnnSdk = "2.47",
                    requiredRuntimeProfile = LocalImageQnnRuntimeProfile(
                        qnnSdk = "2.45",
                        htpArch = 79,
                        completeBundleRuntime = false
                    )
                )
            )
        )
        assertNull(qnnSdkForPackagedImageRuntime(null))
    }

    @Test
    fun socHintsSelectOnlyExactPackagedTransports() {
        assertEquals("v79", LiteRtQualcommRuntimeStager.variantForSocModel("SM8750P"))
        assertEquals("v79", LiteRtQualcommRuntimeStager.variantForSocModel("sm8750"))
        assertEquals("v81", LiteRtQualcommRuntimeStager.variantForSocModel("SM8850"))
        assertEquals("v73", LiteRtQualcommRuntimeStager.variantForSocModel("SM8550P"))
        assertEquals("v73", LiteRtQualcommRuntimeStager.variantForSocModel("QCS8550"))
        assertEquals("v75", LiteRtQualcommRuntimeStager.variantForSocModel("SM8650"))
        assertEquals("v75", LiteRtQualcommRuntimeStager.variantForSocModel("SM8635"))
        assertNull(LiteRtQualcommRuntimeStager.variantForSocModel("SM9999"))
        assertNull(LiteRtQualcommRuntimeStager.variantForSocModel(null))
    }

    @Test
    fun packagedRuntimeOnlyAcceptsAnExplicitMatchingQnnSdk() {
        assertFalse(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk(null))
        assertFalse(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk(""))
        assertFalse(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk("   "))
        assertFalse(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk("2.47"))
        assertFalse(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk("2.47.0"))
        assertTrue(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk("v2.47.0.260601114230"))
        assertTrue(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk(" V2.47.0.260601114230 "))
        assertFalse(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk("v2.47.0.260326154327"))
        assertFalse(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk("2.47.1"))
        assertFalse(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk("2.45.0.260326154327"))
        assertFalse(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk("2.28"))
        assertFalse(LiteRtQualcommRuntimeStager.packagedRuntimeSupportsQnnSdk("not-a-version"))
    }

    @Test
    fun imageRuntimeSelectionMatrixOnlyChangesTransportChoice() {
        data class SocCase(val model: String?, val packagedVariant: String?)

        val socs = listOf(
            SocCase("SM8750", "v79"),
            SocCase("SM8850", "v81"),
            SocCase("SM8450", null),
            SocCase("SM9999", null),
            SocCase(null, null)
        )
        val sdkCases = listOf(
            "2.47.0.260601114230" to true,
            "2.47" to false,
            "2.28" to false,
            " " to false
        )

        listOf(false, true).forEach { completeBundleRuntime ->
            sdkCases.forEach { (qnnSdk, matchesPackagedRuntime) ->
                socs.forEach { soc ->
                    val expectedVariant = soc.packagedVariant.takeIf {
                        !completeBundleRuntime && matchesPackagedRuntime
                    }
                    assertEquals(
                        "complete=$completeBundleRuntime sdk=$qnnSdk soc=${soc.model}",
                        expectedVariant,
                        LiteRtQualcommRuntimeStager.packagedRuntimeVariantForImageBundle(
                            bundleRuntimeAlreadyStaged = completeBundleRuntime,
                            qnnSdk = qnnSdk,
                            rawSocModel = soc.model
                        )
                    )
                }
            }
        }
        // A null selection is the universal generic/native fallback, not a run denial.
        assertNull(
            LiteRtQualcommRuntimeStager.packagedRuntimeVariantForImageBundle(
                bundleRuntimeAlreadyStaged = false,
                qnnSdk = null,
                rawSocModel = "SM8750"
            )
        )
    }

    @Test
    fun knownOlderSocDoesNotReceiveTheV81TransportFallback() {
        assertFalse(LiteRtQualcommRuntimeStager.shouldUsePackagedRuntimeForSocModel("SM8250"))
        assertFalse(LiteRtQualcommRuntimeStager.shouldUsePackagedRuntimeForSocModel("SM8350P"))
        assertFalse(LiteRtQualcommRuntimeStager.shouldUsePackagedRuntimeForSocModel("SM8450"))
        assertFalse(LiteRtQualcommRuntimeStager.shouldUsePackagedRuntimeForSocModel("SM8475"))
        assertTrue(LiteRtQualcommRuntimeStager.shouldUsePackagedRuntimeForSocModel("SM8550"))
        assertFalse(LiteRtQualcommRuntimeStager.shouldUsePackagedRuntimeForSocModel("SM9999"))
        assertFalse(LiteRtQualcommRuntimeStager.shouldUsePackagedRuntimeForSocModel(null))
    }

    @Test
    fun sm8550StagesTheV73TransportPair() {
        assertStagesTransportPair("SM8550", "v73", V73_FILE_NAMES)
    }

    @Test
    fun sm8650StagesTheV75TransportPair() {
        assertStagesTransportPair("SM8650", "v75", V75_FILE_NAMES)
    }

    @Test
    fun sm8750pStagesTheV79TransportPair() {
        val sourceRoot = Files.createTempDirectory("litert-stage-v79-source").toFile()
        val destinationRoot = Files.createTempDirectory("litert-stage-v79-destination").toFile()
        try {
            val files = writeAssetSet(sourceRoot, variant = "v79")
            val expected = files.sha256()
            val staged = requireNotNull(
                LiteRtQualcommRuntimeStager.stageFromDirectory(
                    sourceRoot,
                    destinationRoot,
                    "SM8750P",
                    expected
                )
            )
            assertEquals("v79", staged.variant)
            assertEquals(
                setOf(
                    "libLiteRtDispatch_Qualcomm.so",
                    "libQnnHtp.so",
                    "libQnnHtpV79Skel.so",
                    "libQnnHtpV79Stub.so",
                    "libQnnSystem.so"
                ),
                staged.directory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
            )
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun knownOlderSocDoesNotStageTheGenericV81AssetSet() {
        val sourceRoot = Files.createTempDirectory("litert-stage-old-soc-source").toFile()
        val destinationRoot = Files.createTempDirectory("litert-stage-old-soc-destination").toFile()
        try {
            val files = writeAssetSet(sourceRoot)
            assertNull(
                LiteRtQualcommRuntimeStager.stageFromDirectory(
                    sourceRoot,
                    destinationRoot,
                    "SM8350",
                    files.sha256()
                )
            )
            assertTrue(destinationRoot.listFiles().orEmpty().isEmpty())
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun unknownSocDoesNotStageTheGenericV81AssetSet() {
        val sourceRoot = Files.createTempDirectory("litert-stage-unknown-soc-source").toFile()
        val destinationRoot = Files.createTempDirectory("litert-stage-unknown-soc-destination").toFile()
        try {
            val files = writeAssetSet(sourceRoot)
            assertNull(
                LiteRtQualcommRuntimeStager.stageFromDirectory(
                    sourceRoot,
                    destinationRoot,
                    "SM9999",
                    files.sha256()
                )
            )
            assertTrue(destinationRoot.listFiles().orEmpty().isEmpty())
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun missingAssetSetDoesNotPublishAPartialRuntime() {
        val sourceRoot = Files.createTempDirectory("litert-stage-missing-source").toFile()
        val destinationRoot = Files.createTempDirectory("litert-stage-missing-destination").toFile()
        try {
            val files = writeAssetSet(sourceRoot)
            val expected = files.sha256()
            files.getValue("libLiteRtDispatch_Qualcomm.so").delete()

            assertNull(LiteRtQualcommRuntimeStager.stageFromDirectory(sourceRoot, destinationRoot, expected))
            assertTrue(destinationRoot.listFiles().orEmpty().none { it.name.contains("staging") })
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun hashMismatchIsRejectedAndLeavesNoPublishedDirectory() {
        val sourceRoot = Files.createTempDirectory("litert-stage-hash-source").toFile()
        val destinationRoot = Files.createTempDirectory("litert-stage-hash-destination").toFile()
        try {
            val files = writeAssetSet(sourceRoot)
            val expected = files.sha256().toMutableMap()
            expected["libLiteRtDispatch_Qualcomm.so"] = "0".repeat(64)

            assertNull(LiteRtQualcommRuntimeStager.stageFromDirectory(sourceRoot, destinationRoot, expected))
            assertTrue(destinationRoot.listFiles().orEmpty().none { it.isDirectory && !it.name.startsWith(".") })
            assertTrue(destinationRoot.listFiles().orEmpty().none { it.name.contains("staging") })
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun precompiledDispatchSetDoesNotRequireTheOptionalCompilerPlugin() {
        val sourceRoot = Files.createTempDirectory("litert-stage-precompiled-source").toFile()
        val destinationRoot = Files.createTempDirectory("litert-stage-precompiled-destination").toFile()
        try {
            val files = writeAssetSet(sourceRoot)
            files.getValue("libLiteRtCompilerPlugin_Qualcomm.so").delete()
            val expected = files
                .filterKeys { it != "libLiteRtCompilerPlugin_Qualcomm.so" }
                .sha256()

            val staged = requireNotNull(
                LiteRtQualcommRuntimeStager.stageFromDirectory(sourceRoot, destinationRoot, expected)
            )
            assertEquals(expected.keys, staged.directory.listFiles().orEmpty().map(File::getName).toSet())
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun variantStageCanUseOptionalCompilerPluginFromGenericAssetRoot() {
        val sourceRoot = Files.createTempDirectory("litert-stage-optional-root-source").toFile()
        val destinationRoot = Files.createTempDirectory("litert-stage-optional-root-destination").toFile()
        try {
            val genericFiles = writeAssetSet(sourceRoot)
            val variantFiles = writeAssetSet(sourceRoot, variant = "v73")
            val expected = (genericFiles + variantFiles).sha256()

            val staged = requireNotNull(
                LiteRtQualcommRuntimeStager.stageFromDirectory(
                    sourceRoot,
                    destinationRoot,
                    "SM8550",
                    expected
                )
            )
            assertEquals("v73", staged.variant)
            assertTrue(File(staged.directory, "libLiteRtCompilerPlugin_Qualcomm.so").isFile)
            assertEquals(
                (V73_FILE_NAMES + "libLiteRtCompilerPlugin_Qualcomm.so").toSet(),
                staged.directory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
            )
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun successfulStagePublishesAtomicallyAndReusesVerifiedContent() {
        val sourceRoot = Files.createTempDirectory("litert-stage-success-source").toFile()
        val destinationRoot = Files.createTempDirectory("litert-stage-success-destination").toFile()
        try {
            val files = writeAssetSet(sourceRoot)
            val expected = files.sha256()
            val first = requireNotNull(
                LiteRtQualcommRuntimeStager.stageFromDirectory(sourceRoot, destinationRoot, expected)
            )
            assertFalse(first.reused)
            assertEquals(
                FILE_NAMES.toSet(),
                first.directory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
            )
            assertTrue(destinationRoot.listFiles().orEmpty().none { it.name.contains("staging") })

            val second = requireNotNull(
                LiteRtQualcommRuntimeStager.stageFromDirectory(sourceRoot, destinationRoot, expected)
            )
            assertTrue(second.reused)
            assertEquals(first.directory, second.directory)
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    private fun writeAssetSet(sourceRoot: File, variant: String = "v81"): Map<String, File> {
        val root = if (variant == "v81") {
            File(sourceRoot, "litert-qualcomm/arm64-v8a")
        } else {
            File(sourceRoot, "litert-qualcomm/$variant/arm64-v8a")
        }.apply { mkdirs() }
        val names = when (variant) {
            "v73" -> V73_FILE_NAMES
            "v75" -> V75_FILE_NAMES
            "v79" -> V79_FILE_NAMES
            else -> FILE_NAMES
        }
        return names.associateWith { name ->
            File(root, name).apply { writeText("test-$name") }
        }
    }

    private fun Map<String, File>.sha256(): Map<String, String> = mapValues { (_, file) ->
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun assertStagesTransportPair(
        soc: String,
        variant: String,
        expectedNames: List<String>
    ) {
        val sourceRoot = Files.createTempDirectory("litert-stage-$variant-source").toFile()
        val destinationRoot = Files.createTempDirectory("litert-stage-$variant-destination").toFile()
        try {
            val files = writeAssetSet(sourceRoot, variant)
            val staged = requireNotNull(
                LiteRtQualcommRuntimeStager.stageFromDirectory(
                    sourceRoot,
                    destinationRoot,
                    soc,
                    files.sha256()
                )
            )
            assertEquals(variant, staged.variant)
            assertEquals(
                expectedNames.toSet(),
                staged.directory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
            )
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    private companion object {
        val FILE_NAMES = listOf(
            "libLiteRtCompilerPlugin_Qualcomm.so",
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so",
            "libQnnHtpV81Skel.so",
            "libQnnHtpV81Stub.so",
            "libQnnSystem.so"
        )
        val V79_FILE_NAMES = listOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so",
            "libQnnHtpV79Skel.so",
            "libQnnHtpV79Stub.so",
            "libQnnSystem.so"
        )
        val V73_FILE_NAMES = listOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so",
            "libQnnHtpV73Skel.so",
            "libQnnHtpV73Stub.so",
            "libQnnSystem.so"
        )
        val V75_FILE_NAMES = listOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so",
            "libQnnHtpV75Skel.so",
            "libQnnHtpV75Stub.so",
            "libQnnSystem.so"
        )
    }
}

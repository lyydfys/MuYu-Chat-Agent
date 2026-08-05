package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QairtBundleRiskAnalyzerTest {
    @Test
    fun detectsHighRiskFullContextGraph() {
        val bundle = qairtBundle(
            modelName = "Qwen3-4B-Instruct-2507",
            precision = "w4a16",
            kvSpan = 4_095
        )

        val profile = QairtBundleRiskAnalyzer.analyze(bundle)

        assertEquals(QairtGraphRiskLevel.HIGH, profile.riskLevel)
        assertEquals(4_095, profile.maxKvSpan)
        assertEquals(2, profile.kvInputTensorCount)
        assertEquals(2, profile.estimatedKvInputTensorCount)
        assertTrue(profile.kvByteEstimateComplete)
        assertEquals("Qwen3-4B-Instruct-2507", profile.modelName)
        assertEquals("w4a16", profile.precision)
        assertTrue(profile.estimatedKvInputBytes > 0L)
        assertNull(profile.blockerForTotalRam(12L * 1024L * 1024L * 1024L))
        assertNull(profile.blockerForTotalRam(16L * 1024L * 1024L * 1024L))
        assertNull(
            profile.blockerForDeviceMemory(
                totalRamBytes = 16L * 1024L * 1024L * 1024L,
                availableRamBytes = 1L * 1024L * 1024L * 1024L
            )
        )
        assertNull(
            profile.blockerForDeviceMemory(
                totalRamBytes = 16L * 1024L * 1024L * 1024L,
                availableRamBytes = 4L * 1024L * 1024L * 1024L
            )
        )

        val unknown = profile.admissionForDeviceMemory(
            totalRamBytes = 12L * 1024L * 1024L * 1024L,
            availableRamBytes = 1L * 1024L * 1024L * 1024L
        )
        assertEquals(QairtExecutionAdmissionMode.ISOLATED_DRY_RUN, unknown.mode)
        assertTrue(unknown.canAttempt)
        assertTrue(unknown.recommendsIsolatedDryRun)
        assertTrue(unknown.message.contains("does not block download, load, or execution"))
        assertTrue(unknown.message.contains("concrete native execution decides compatibility"))
        assertNotNull(unknown.memoryAdvisory)

        val identity = QairtBundleRuntimeIdentity(
            bundleSha256 = "bundle-sha",
            chipset = "SM8750P",
            runtimeFingerprint = "qairt-runtime-sha"
        )
        val verified = profile.admissionForDeviceMemory(
            totalRamBytes = 12L * 1024L * 1024L * 1024L,
            availableRamBytes = 1L * 1024L * 1024L * 1024L,
            observedIdentity = identity,
            verifiedIdentities = setOf(identity)
        )
        assertEquals(QairtExecutionAdmissionMode.VERIFIED_ALLOW, verified.mode)
        assertTrue(verified.canAttempt)
        assertFalse(verified.recommendsIsolatedDryRun)

        val runtimeMismatch = profile.admissionForDeviceMemory(
            totalRamBytes = 12L * 1024L * 1024L * 1024L,
            availableRamBytes = 4L * 1024L * 1024L * 1024L,
            observedIdentity = identity.copy(runtimeFingerprint = "other-runtime-sha"),
            verifiedIdentities = setOf(identity)
        )
        assertEquals(QairtExecutionAdmissionMode.ISOLATED_DRY_RUN, runtimeMismatch.mode)
    }

    @Test
    fun allowsSegmentedKvGraphOnTwelveGbDevice() {
        val bundle = qairtBundle(
            modelName = "Qwen3-VL-4B-Instruct",
            precision = "w4a16",
            kvSpan = 1_023
        )

        val profile = QairtBundleRiskAnalyzer.analyze(bundle)

        assertEquals(QairtGraphRiskLevel.LOW, profile.riskLevel)
        assertEquals(1_023, profile.maxKvSpan)
        assertNull(profile.blockerForTotalRam(12L * 1024L * 1024L * 1024L))
        assertNull(
            profile.blockerForDeviceMemory(
                totalRamBytes = 12L * 1024L * 1024L * 1024L,
                availableRamBytes = 4L * 1024L * 1024L * 1024L
            )
        )
    }

    @Test
    fun malformedMetadataUsesIsolatedDryRunInsteadOfStaticBlock() {
        val bundle = Files.createTempDirectory("qairt-risk-invalid").toFile()
        File(bundle, "metadata.json").writeText("{broken")

        val profile = QairtBundleRiskAnalyzer.analyze(bundle)

        assertEquals(QairtGraphRiskLevel.UNKNOWN, profile.riskLevel)
        assertEquals(1, profile.metadataFileCount)
        assertEquals(0, profile.parsedMetadataFileCount)
        assertTrue(profile.parseErrors.isNotEmpty())
        assertNull(profile.blockerForTotalRam(12L * 1024L * 1024L * 1024L))
        assertNull(profile.blockerForTotalRam(16L * 1024L * 1024L * 1024L)
        )
        val admission = profile.admissionForDeviceMemory(
            totalRamBytes = 12L * 1024L * 1024L * 1024L,
            availableRamBytes = 4L * 1024L * 1024L * 1024L
        )
        assertEquals(QairtExecutionAdmissionMode.ISOLATED_DRY_RUN, admission.mode)
    }

    @Test
    fun oversizedSegmentedGraphRemainsAdvisory() {
        val bundle = qairtBundle(
            modelName = "oversized-segmented",
            precision = "w4a16",
            kvSpan = 1_024,
            kvTensorPairs = 9,
            hiddenSize = 1_024,
            dtype = "uint16"
        )

        val profile = QairtBundleRiskAnalyzer.analyze(bundle)

        assertEquals(18, profile.kvInputTensorCount)
        assertTrue(profile.estimatedKvInputBytes > 256L * 1024L * 1024L)
        assertEquals(QairtGraphRiskLevel.HIGH, profile.riskLevel)
        assertNull(profile.blockerForTotalRam(12L * 1024L * 1024L * 1024L))
    }

    @Test
    fun incompleteKvByteEstimateRemainsAdvisory() {
        val bundle = qairtBundle(
            modelName = "unknown-dtype",
            precision = "w4a16",
            kvSpan = 1_023,
            dtype = "packed_custom"
        )

        val profile = QairtBundleRiskAnalyzer.analyze(bundle)

        assertEquals(2, profile.kvInputTensorCount)
        assertEquals(0, profile.estimatedKvInputTensorCount)
        assertFalse(profile.kvByteEstimateComplete)
        assertEquals(QairtGraphRiskLevel.UNKNOWN, profile.riskLevel)
        assertNull(profile.blockerForTotalRam(16L * 1024L * 1024L * 1024L))
    }

    @Test
    fun unknownMemoryMetricsRecommendIsolatedDryRun() {
        val profile = QairtBundleRiskAnalyzer.analyze(
            qairtBundle(
                modelName = "segmented",
                precision = "w4a16",
                kvSpan = 1_023
            )
        )

        assertNull(profile.blockerForTotalRam(0L))
        assertNull(
            profile.blockerForDeviceMemory(
                totalRamBytes = 16L * 1024L * 1024L * 1024L,
                availableRamBytes = 0L
            )
        )
        val admission = profile.admissionForDeviceMemory(
            totalRamBytes = 16L * 1024L * 1024L * 1024L,
            availableRamBytes = 0L
        )
        assertEquals(QairtExecutionAdmissionMode.ISOLATED_DRY_RUN, admission.mode)
        assertNotNull(admission.memoryAdvisory)
    }

    private fun qairtBundle(
        modelName: String,
        precision: String,
        kvSpan: Int,
        kvTensorPairs: Int = 1,
        hiddenSize: Int = 128,
        dtype: String = "uint8"
    ): File {
        val bundle = Files.createTempDirectory("qairt-risk").toFile()
        val tensors = buildList {
            repeat(kvTensorPairs) { index ->
                add("\"past_key_${index}_in\": {\"shape\": [8, 1, $hiddenSize, $kvSpan], \"dtype\": \"$dtype\"}")
                add("\"past_value_${index}_in\": {\"shape\": [8, 1, $kvSpan, $hiddenSize], \"dtype\": \"$dtype\"}")
            }
        }.joinToString(",\n")
        File(bundle, "metadata.json").writeText(
            """
            {
              "model_name": "$modelName",
              "precision": "$precision",
              "model_files": {
                "part2_of_4.bin": {
                  "inputs": {
                    $tensors
                  }
                }
              }
            }
            """.trimIndent()
        )
        return bundle
    }
}

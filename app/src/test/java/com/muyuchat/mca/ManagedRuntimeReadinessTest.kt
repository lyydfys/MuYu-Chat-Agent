package com.muyuchat.mca

import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedRuntimeReadinessTest {
    @Test
    fun unknownMnnAndStableDiffusionCppRemainManuallySelectableInUi() {
        val mnnRoot = Files.createTempDirectory("mnn-ui-unknown").toFile()
        val sdRoot = Files.createTempDirectory("sdcpp-ui-unknown").toFile()
        try {
            val mnnPrimary = File(mnnRoot, "unet.mnn").apply { writeText("x") }
            listOf(
                "text_encoder.mnn",
                "text_encoder.mnn.weight",
                "unet.mnn.weight",
                "vae_decoder.mnn",
                "vae_decoder.mnn.weight",
                "tokenizer.mtok"
            ).forEach { File(mnnRoot, it).writeText("x") }
            val stablePrimary = File(sdRoot, "sd_turbo.safetensors").apply { writeText("x") }
            val mnn = LocalImageModelRecord(
                id = "mnn-unknown",
                displayName = "MNN unknown",
                path = mnnPrimary.absolutePath,
                fileName = mnnPrimary.name,
                sizeBytes = mnnPrimary.length(),
                sha256 = "mnn",
                runtime = LocalImageRuntime.MNN_DIFFUSION,
                family = LocalImageModelFamily.SD15,
                bundleRoot = mnnRoot.absolutePath,
                verificationStatus = LocalImageVerificationStatus.UNKNOWN
            )
            val stable = LocalImageModelRecord(
                id = "sdcpp-unknown",
                displayName = "stable unknown",
                path = stablePrimary.absolutePath,
                fileName = stablePrimary.name,
                sizeBytes = stablePrimary.length(),
                sha256 = "stable",
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD_TURBO,
                bundleRoot = sdRoot.absolutePath,
                verificationStatus = LocalImageVerificationStatus.UNKNOWN
            )

            assertNull(mnn.localImageReadinessForUi(qnnVerificationCurrent = null))
            assertNull(stable.localImageReadinessForUi(qnnVerificationCurrent = null))
            assertTrue(mnn.localImageVerificationDiagnosticMessage()!!.contains("可直接尝试"))
            assertTrue(stable.localImageVerificationDiagnosticMessage()!!.contains("可直接尝试"))
        } finally {
            mnnRoot.deleteRecursively()
            sdRoot.deleteRecursively()
        }
    }

    @Test
    fun failedStableDiffusionCppRecordRemainsSelectableForRetry() {
        val root = Files.createTempDirectory("sdcpp-ui-failed").toFile()
        try {
            val primary = File(root, "sd_turbo.safetensors").apply { writeText("x") }
            val record = LocalImageModelRecord(
                id = "sdcpp-failed",
                displayName = "stable failed",
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = primary.length(),
                sha256 = "stable",
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD_TURBO,
                bundleRoot = root.absolutePath,
                verificationStatus = LocalImageVerificationStatus.FAILED,
                verificationMessage = "native execution failed",
                verifiedAt = System.currentTimeMillis()
            )

            assertNull(record.localImageReadinessForUi(qnnVerificationCurrent = null))
            assertTrue(record.localImageVerificationDiagnosticMessage()!!.contains("直接重试"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleQnnStampIsDiagnosticAndNeverBlocksAStructurallyCompleteBundle() {
        val root = Files.createTempDirectory("qnn-stale-stamp").toFile()
        try {
            val primary = File(root, "unet_qnn_context.bin").apply { writeText("x") }
            File(root, "vae_decoder_qnn_context.bin").writeText("x")
            File(root, "clip_text_encoder_qnn_context.bin").writeText("x")
            File(root, "tokenizer.json").writeText("{}")
            val record = LocalImageModelRecord(
                id = "qnn",
                displayName = "QNN image",
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = primary.length(),
                sha256 = "test",
                runtime = LocalImageRuntime.QNN_HTP,
                bundleRoot = root.absolutePath,
                verificationStatus = LocalImageVerificationStatus.PASSED,
                qnnVerificationStamp = "old-stamp"
            )

            assertNull(record.localImageReadinessForUi(qnnVerificationCurrent = false))
            assertTrue(record.localImageReadinessLabelForUi(false).contains("可直接尝试"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun qairtDiagnosticEvidenceRequiresExactBundleChipsetAndRuntimeIdentity() {
        val verified = QairtBundleRuntimeIdentity(
            bundleSha256 = "bundle-a",
            chipset = "qualcomm/sm8750",
            runtimeFingerprint = "app-1"
        )

        assertTrue(isExactQairtExecutionVerified(verified, setOf(verified)))
        assertFalse(
            isExactQairtExecutionVerified(
                verified.copy(chipset = "qualcomm/s8850"),
                setOf(verified)
            )
        )
        assertFalse(
            isExactQairtExecutionVerified(
                verified.copy(runtimeFingerprint = "app-2"),
                setOf(verified)
            )
        )
    }

}

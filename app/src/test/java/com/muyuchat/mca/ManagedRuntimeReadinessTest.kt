package com.muyuchat.mca

import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedRuntimeReadinessTest {
    @Test
    fun staleQnnStampNeverMakesAPassedRecordReadyInTheUi() {
        val record = LocalImageModelRecord(
            id = "qnn",
            displayName = "QNN image",
            path = File("/missing/qnn.bin").absolutePath,
            fileName = "qnn.bin",
            sizeBytes = 1L,
            sha256 = "test",
            runtime = LocalImageRuntime.QNN_HTP,
            verificationStatus = LocalImageVerificationStatus.PASSED,
            qnnVerificationStamp = "old-stamp"
        )

        val readiness = record.localImageReadinessForUi(qnnVerificationCurrent = false)

        assertTrue(readiness!!.contains("已过期"))
        assertTrue(record.localImageReadinessLabelForUi(false).contains("过期"))
    }

    @Test
    fun qairtAllowListRequiresExactBundleChipsetAndRuntimeIdentity() {
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

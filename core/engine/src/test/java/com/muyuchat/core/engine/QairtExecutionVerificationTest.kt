package com.muyuchat.core.engine

import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QairtExecutionVerificationTest {
    @Test
    fun runtimeBinaryFingerprintChangesWhenPatchedJniChanges() {
        val first = Files.createTempDirectory("qairt-runtime-first").toFile()
        val second = Files.createTempDirectory("qairt-runtime-second").toFile()
        try {
            File(first, "libnpu_jni.so").writeBytes(byteArrayOf(1, 2, 3))
            File(second, "libnpu_jni.so").writeBytes(byteArrayOf(1, 2, 4))

            val firstFingerprint = qairtRuntimeBinaryFingerprint(first)
            val repeatedFingerprint = qairtRuntimeBinaryFingerprint(first)
            val secondFingerprint = qairtRuntimeBinaryFingerprint(second)

            assertEquals(firstFingerprint, repeatedFingerprint)
            assertNotEquals(firstFingerprint, secondFingerprint)
            assertEquals(64, firstFingerprint.length)
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun qairtFingerprintIgnoresLiteRtQualcommDispatchAssets() {
        val base = Files.createTempDirectory("qairt-runtime-base").toFile()
        val withLiteRt = Files.createTempDirectory("qairt-runtime-litert").toFile()
        try {
            listOf("libnpu_jni.so", "libgeniex.so", "libgeniex_core.so", "libgeniex_plugin_qairt.so")
                .forEach { name ->
                    File(base, name).writeText("geniex-$name")
                    File(withLiteRt, name).writeText("geniex-$name")
            }
            File(withLiteRt, "libLiteRtDispatch_Qualcomm.so").writeText("litert")

            assertEquals(
                qairtRuntimeBinaryFingerprint(base),
                qairtRuntimeBinaryFingerprint(withLiteRt)
            )
            assertTrue(hasGenieXQairtBridge(base))
            assertTrue(hasGenieXQairtBridge(withLiteRt))
        } finally {
            base.deleteRecursively()
            withLiteRt.deleteRecursively()
        }
    }

    @Test
    fun litertOnlyNativeDirectoryCannotProvideQairtBridgeEvidence() {
        val directory = Files.createTempDirectory("litert-only-native").toFile()
        try {
            File(directory, "libLiteRtDispatch_Qualcomm.so").writeText("litert")
            File(directory, "libQnnSystem.so").writeText("qnn")
            File(directory, "libQnnHtpV73Skel.so").writeText("v73")
            assertTrue(!hasGenieXQairtBridge(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun persistsOnlyCompleteExactBundleChipsetRuntimeIdentities() {
        val root = Files.createTempDirectory("mca-qairt-verifications").toFile()
        val store = QairtExecutionVerificationStore(File(root, "verifications.json"))
        val identity = QairtBundleRuntimeIdentity(
            bundleSha256 = "sha-qwen3-vl-4b",
            chipset = "Qualcomm/SM8750P",
            runtimeFingerprint = "geniex-qairt|app=test#1|build=test|abi=arm64-v8a"
        )

        store.recordVerified(identity)
        store.recordVerified(identity)

        assertEquals(setOf(identity), store.verifiedIdentities())
    }

    @Test
    fun ignoresCorruptVerificationFileInsteadOfTurningItIntoAnExecutionBlock() {
        val root = Files.createTempDirectory("mca-qairt-verifications-corrupt").toFile()
        val file = File(root, "verifications.json").apply { writeText("not-json") }
        val store = QairtExecutionVerificationStore(file)

        assertTrue(store.verifiedIdentities().isEmpty())
    }
}

package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class QnnImageVerificationStampTest {
    @Test
    fun bundleIdentityIsStableAcrossFileCreationOrder() {
        val first = Files.createTempDirectory("qnn-stamp-first").toFile()
        val second = Files.createTempDirectory("qnn-stamp-second").toFile()
        try {
            first.writeBundleFiles("a.ctx" to "context", "nested/tokenizer.json" to "tokenizer")
            second.writeBundleFiles("nested/tokenizer.json" to "tokenizer", "a.ctx" to "context")
            val modifiedAt = FileTime.fromMillis(1_700_000_000_000L)
            first.setAllFileModificationTimes(modifiedAt)
            second.setAllFileModificationTimes(modifiedAt)

            val firstIdentity = QnnImageBundleIdentity.fromDirectory(first)
            val secondIdentity = QnnImageBundleIdentity.fromDirectory(second)

            assertEquals(QnnImageBundleIdentityStatus.AVAILABLE, firstIdentity.status)
            assertEquals(firstIdentity, secondIdentity)
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun missingDirectoryHasExplicitUnavailableIdentity() {
        val missing = File(Files.createTempDirectory("qnn-stamp-missing").toFile(), "missing")
        try {
            val identity = QnnImageBundleIdentity.fromDirectory(missing)

            assertEquals(QnnImageBundleIdentityStatus.MISSING, identity.status)
            assertNull(identity.sha256)
        } finally {
            missing.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun fileModificationMakesOldStampNotMatch() {
        val bundle = Files.createTempDirectory("qnn-stamp-change").toFile()
        try {
            val model = File(bundle, "model.ctx").also { it.writeText("version-one") }
            Files.setLastModifiedTime(model.toPath(), FileTime.fromMillis(1_700_000_000_000L))
            val stamp = QnnImageVerificationStamp.create(DEVICE, RUNTIME, bundle)

            model.writeText("version-two-with-different-length")
            Files.setLastModifiedTime(model.toPath(), FileTime.fromMillis(1_700_000_001_000L))

            assertFalse(stamp.matchesCurrent(DEVICE, RUNTIME, bundle))
        } finally {
            bundle.deleteRecursively()
        }
    }

    @Test
    fun sameSizeContentReplacementInvalidatesOldStampEvenWhenTimestampIsUnchanged() {
        val bundle = Files.createTempDirectory("qnn-stamp-content-change").toFile()
        try {
            val model = File(bundle, "model.ctx").also { it.writeText("aaaa") }
            val fixedTime = FileTime.fromMillis(1_700_000_000_000L)
            Files.setLastModifiedTime(model.toPath(), fixedTime)
            val stamp = QnnImageVerificationStamp.create(DEVICE, RUNTIME, bundle)

            model.writeText("bbbb")
            Files.setLastModifiedTime(model.toPath(), fixedTime)

            assertFalse(stamp.matchesCurrent(DEVICE, RUNTIME, bundle))
        } finally {
            bundle.deleteRecursively()
        }
    }

    @Test
    fun stampSurvivesJsonRoundTripAndMatchesCurrentIdentity() {
        val bundle = Files.createTempDirectory("qnn-stamp-json").toFile()
        try {
            bundle.writeBundleFiles("model.ctx" to "context", "config.json" to "{}")
            val stamp = QnnImageVerificationStamp.create(DEVICE, RUNTIME, bundle)

            val restored = QnnImageVerificationStamp.fromJson(stamp.toJsonString())

            assertEquals(stamp, restored)
            assertTrue(restored.matchesCurrent(DEVICE, RUNTIME, bundle))
        } finally {
            bundle.deleteRecursively()
        }
    }

    @Test
    fun runtimeIdentityBindsSelectedLibraryContents() {
        val runtime = Files.createTempDirectory("qnn-runtime-identity").toFile()
        try {
            val qnnSystem = File(runtime, "libQnnSystem.so").also { it.writeText("aaaa") }
            val fixedTime = FileTime.fromMillis(1_700_000_000_000L)
            Files.setLastModifiedTime(qnnSystem.toPath(), fixedTime)
            val probe = JSONObject()
                .put("ready", true)
                .put("loadable", true)
                .put("qnnInterfacePresent", true)
                .put("qnnSystemInterfacePresent", true)
                .put("qnnSystemLibraryPath", qnnSystem.absolutePath)
                .put("compile", JSONObject().put("typedGraphBindingsCompiled", true))

            val first = qnnRuntimeIdentityJson(probe.toString())
            qnnSystem.writeText("bbbb")
            Files.setLastModifiedTime(qnnSystem.toPath(), fixedTime)
            val second = qnnRuntimeIdentityJson(probe.toString())

            assertNotEquals(first, second)
            val selected = JSONObject(second).getJSONArray("selectedLibraries").getJSONObject(0)
            assertEquals(qnnSystem.canonicalPath, selected.getString("path"))
            assertEquals("available", selected.getString("status"))
            assertEquals(64, selected.getString("sha256").length)
        } finally {
            runtime.deleteRecursively()
        }
    }

    @Test
    fun legacyStampNeverBecomesFullRegressionDuringMigration() {
        val original = QnnImageVerificationStamp.create(DEVICE, RUNTIME, null).toJson()
            .put("version", 2)
            .put("execution", imageEvidence(QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION).toJson())
        val restored = QnnImageVerificationStamp.fromJson(original)

        assertEquals(2, restored.version)
        assertEquals(QnnImageEvidenceLevel.LEGACY_UNSCOPED, restored.evidenceLevel)
        assertNull(restored.execution)
    }

    @Test
    fun smokeAndFullRegressionKeepDistinctParametersAndOrigins() {
        val smoke = imageEvidence(QnnImageEvidenceLevel.PRODUCT_IMAGE_SMOKE, steps = 1)
        val full = imageEvidence(QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION)
        val graph = QnnImageExecutionEvidence(
            QnnImageEvidenceLevel.GRAPH_SMOKE, QnnImageEvidenceEntryPoint.DIAGNOSTIC,
            "graph-request", "attempt-one", "native-graph", 3L, 10L, 20L
        )

        assertEquals(QnnImageEvidenceLevel.PRODUCT_IMAGE_SMOKE, smoke.level)
        assertEquals(1, smoke.parameters?.steps)
        assertEquals(QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION, full.level)
        assertEquals(20, full.parameters?.steps)
        assertEquals(full, QnnImageExecutionEvidence.fromJson(full.toJson()))
        assertEquals(QnnImageEvidenceLevel.GRAPH_SMOKE, QnnImageExecutionEvidence.fromJson(graph.toJson()).level)
        assertNull(graph.outputSha256)
        assertTrue(runCatching { imageEvidence(QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION, steps = 1) }.isFailure)
        assertTrue(runCatching {
            full.copy(entryPoint = QnnImageEvidenceEntryPoint.DIAGNOSTIC)
        }.isFailure)
    }

    @Test
    fun fullRegressionRequiresApkIdentityAndRoundTripsWithoutChangingIt() {
        val full = imageEvidence(QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION)
        assertTrue(runCatching { QnnImageVerificationStamp.create(DEVICE, RUNTIME, null, full) }.isFailure)
        val runtime = RUNTIME.copy(apkSha256 = "a".repeat(64))
        assertTrue(runCatching { QnnImageVerificationStamp.create(DEVICE, runtime, null, full) }.isFailure)
        val stamp = QnnImageVerificationStamp(
            device = DEVICE, runtime = runtime,
            bundle = QnnImageBundleIdentity(QnnImageBundleIdentityStatus.AVAILABLE, "c".repeat(64)),
            execution = full
        )
        val restored = QnnImageVerificationStamp.fromJson(stamp.toJsonString())

        assertEquals(stamp, restored)
        assertEquals(QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION, restored.evidenceLevel)
        assertFalse(restored.matchesCurrent(DEVICE, runtime.copy(apkSha256 = "b".repeat(64)), null))
    }

    @Test
    fun imageEvidenceRejectsNativeFallbackParameterDriftAndUncommittedOutput() {
        for ((field, value) in listOf(
            "fallback" to true,
            "cancelled" to true,
            "cancelRequested" to true,
            "steps" to 1,
            "seed" to 99,
            "outputAtomicCommit" to false,
            "outputBytes" to 9L,
            "outputSha256" to "b".repeat(64),
            "nativeGenerationSequence" to 0,
            "nativeStartedAtMonotonicMs" to 0
        )) {
            assertTrue("Expected rejection for $field", runCatching {
                imageEvidence(QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION, mutation = { put(field, value) })
            }.isFailure)
        }
    }

    @Test
    fun ordinaryNativeOutputNeedsAnExplicitAtomicCommitAndUsesOutputBytes() {
        val evidence = imageEvidence(QnnImageEvidenceLevel.PRODUCT_IMAGE_SMOKE, steps = 1)
        assertEquals(4L, evidence.outputBytes)
        assertTrue(evidence.outputAtomicCommit)
        assertTrue(runCatching {
            imageEvidence(QnnImageEvidenceLevel.PRODUCT_IMAGE_SMOKE, mutation = {
                remove("outputAtomicCommit")
            })
        }.isFailure)
        assertTrue(runCatching {
            imageEvidence(QnnImageEvidenceLevel.PRODUCT_IMAGE_SMOKE, mutation = {
                put("outputSizeBytes", 4L)
                remove("outputBytes")
            })
        }.isFailure)
    }

    @Test
    fun runtimeFingerprintSurvivesApiPathSanitizationAndBindsFullLibraryIdentity() {
        val runtimeJson = JSONObject().put("selectedLibraries", JSONObject()
            .put("path", "/data/user/0/com.muyuchat.mca/lib/libQnnHtp.so")
            .put("sha256", "a".repeat(64))).toString()
        val fingerprint = qnnImageNativeRuntimeFingerprint(runtimeJson)
        val runtime = RUNTIME.copy(nativeRuntime = fingerprint, apkSha256 = "b".repeat(64))
        val stamp = QnnImageVerificationStamp.create(DEVICE, runtime, null)
        val api = sanitizedLocalImageApiExecution(JSONObject()
            .put("qnnVerificationStamp", stamp.toJson()).toString())

        assertEquals(stamp, QnnImageVerificationStamp.fromJson(api.getJSONObject("qnnVerificationStamp")))
        assertNotEquals(fingerprint, qnnImageNativeRuntimeFingerprint(runtimeJson.replace("libQnnHtp.so", "libQnnSystem.so")))
        assertNotEquals(fingerprint, qnnImageNativeRuntimeFingerprint(runtimeJson.replace("a".repeat(64), "c".repeat(64))))
        assertFalse(api.toString().contains("/data/user/"))
    }

    private fun imageEvidence(
        level: QnnImageEvidenceLevel,
        steps: Int = 20,
        mutation: JSONObject.() -> Unit = {}
    ): QnnImageExecutionEvidence {
        val output = byteArrayOf(1, 2, 3, 4)
        val parameters = QnnImageRunParameters(512, 512, steps, 1234L)
        val hash = MessageDigest.getInstance("SHA-256").digest(output)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val native = parameters.toJson()
            .put("nativeRequestId", "native-one").put("nativeGenerationSequence", 9L)
            .put("nativeStartedAtMonotonicMs", 100L).put("npuActive", true)
            .put("qnnGraphExecution", true).put("nativeExecution", true).put("fallback", false)
            .put("outputSha256", hash).put("outputBytes", output.size).put("outputAtomicCommit", true)
            .apply(mutation)
        return QnnImageExecutionEvidence.fromNativeImageExecution(
            level, QnnImageEvidenceEntryPoint.MAIN_ACTIVITY, "request-one", "attempt-one",
            parameters, native.toString(), output, 200L
        )
    }

    private fun File.writeBundleFiles(vararg files: Pair<String, String>) {
        files.forEach { (relativePath, contents) ->
            File(this, relativePath).also { file ->
                file.parentFile?.mkdirs()
                file.writeText(contents)
            }
        }
    }

    private fun File.setAllFileModificationTimes(time: FileTime) {
        walkTopDown().filter { it.isFile }.forEach { file ->
            Files.setLastModifiedTime(file.toPath(), time)
        }
    }

    private companion object {
        val DEVICE = QnnImageDeviceIdentity(
            soc = "SM8750",
            abi = "arm64-v8a",
            buildFingerprint = "vendor/device/device:16/test/release-keys"
        )
        val RUNTIME = QnnImageRuntimeIdentity(
            app = "mca/0.2.0-alpha",
            nativeRuntime = "QNN/2.28"
        )
    }
}

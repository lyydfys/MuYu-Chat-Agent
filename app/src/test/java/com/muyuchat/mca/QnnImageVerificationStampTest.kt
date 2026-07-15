package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
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

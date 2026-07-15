package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.QnnRuntimeProbeState
import com.muyuchat.core.deviceprofile.QnnRuntimeStatus
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnImageRuntimeResolverTest {
    @Test
    fun completeBundleProfileComesBeforeGenericApkRuntime() {
        val root = Files.createTempDirectory("qnn-bundle-runtime").toFile()
        val generic = Files.createTempDirectory("qnn-generic-runtime").toFile()
        try {
            val bundleRuntime = File(root, "runtime").apply { mkdirs() }
            completeProfile(bundleRuntime, 73)
            completeProfile(generic, 81)

            val selection = qnnImageSmokeRuntimeSelection(root, statusFor(generic, 81))

            assertEquals(bundleRuntime.canonicalPath, selection.directories.first())
            assertEquals(73, selection.bundleProfile?.htpArchVersion)
            assertEquals(bundleRuntime.canonicalPath, selection.bundleProfile?.directory?.canonicalPath)
            assertTrue(selection.directories.contains(generic.canonicalPath))
        } finally {
            root.deleteRecursively()
            generic.deleteRecursively()
        }
    }

    @Test
    fun incompleteBundleProfileIsNotPromotedAheadOfVerifiedRuntime() {
        val root = Files.createTempDirectory("qnn-bundle-runtime-missing-stub").toFile()
        val generic = Files.createTempDirectory("qnn-generic-runtime-fallback").toFile()
        try {
            val bundleRuntime = File(root, "runtime").apply { mkdirs() }
            File(bundleRuntime, "libQnnSystem.so").writeText("x")
            File(bundleRuntime, "libQnnHtp.so").writeText("x")
            File(bundleRuntime, "libQnnHtpV73Skel.so").writeText("x")
            completeProfile(generic, 79)

            val selection = qnnImageSmokeRuntimeSelection(root, statusFor(generic, 79))

            assertNull(selection.bundleProfile)
            assertEquals(generic.canonicalPath, selection.directories.first())
            assertFalse(selection.directories.contains(bundleRuntime.canonicalPath))
        } finally {
            root.deleteRecursively()
            generic.deleteRecursively()
        }
    }

    @Test
    fun versionedNestedBundleRuntimeIsAcceptedOnlyWhenMatchingStubExists() {
        val root = Files.createTempDirectory("qnn-bundle-runtime-abi").toFile()
        val generic = Files.createTempDirectory("qnn-generic-runtime-abi").toFile()
        try {
            val abiRuntime = File(root, "runtime/arm64-v8a").apply { mkdirs() }
            completeProfile(abiRuntime, 73)
            completeProfile(generic, 81)

            val selection = qnnImageSmokeRuntimeSelection(root, statusFor(generic, 81))

            assertEquals(abiRuntime.canonicalPath, selection.directories.first())
            assertEquals(73, selection.bundleProfile?.htpArchVersion)
        } finally {
            root.deleteRecursively()
            generic.deleteRecursively()
        }
    }

    @Test
    fun requiredV73ProfileDoesNotAcceptCompleteV81Fallback() {
        val root = Files.createTempDirectory("qnn-required-v73").toFile()
        try {
            completeProfile(File(root, "runtime").apply { mkdirs() }, 81)

            val message = qnnRequiredBundleRuntimeReadinessMessage(
                root,
                LocalImageQnnRuntimeProfile("2.28", 73, completeBundleRuntime = true)
            )

            assertPublicRuntimeMessage(message, "骁龙 8 Gen 2 NPU 运行环境")
            assertNull(qnnImageBundleRuntimeProfileForArchOrNull(root, 73))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun requiredV73ProfileAcceptsExactFourFileRuntimeEvenWhenV81AlsoExists() {
        val root = Files.createTempDirectory("qnn-required-v73-with-v81").toFile()
        try {
            val runtime = File(root, "runtime").apply { mkdirs() }
            completeProfile(runtime, 73)
            completeProfile(runtime, 81)
            writeRuntimeMetadata(runtime, qnnSdk = "2.28", arch = 73)

            val message = qnnRequiredBundleRuntimeReadinessMessage(
                root,
                LocalImageQnnRuntimeProfile("2.28", 73, completeBundleRuntime = true)
            )

            assertNull(message)
            assertEquals(73, qnnImageBundleRuntimeProfileForArchOrNull(root, 73)?.htpArchVersion)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun requiredRuntimeRejectsMissingMetadataEvenWhenFourLibrariesExist() {
        val root = Files.createTempDirectory("qnn-required-metadata-missing").toFile()
        try {
            completeProfile(File(root, "runtime").apply { mkdirs() }, 73)

            val message = qnnRequiredBundleRuntimeReadinessMessage(
                root,
                LocalImageQnnRuntimeProfile("2.28", 73, completeBundleRuntime = true)
            )

            assertPublicRuntimeMessage(message, "骁龙 8 Gen 2 NPU 运行环境")
            assertTrue(message?.contains("校验信息缺失") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun requiredRuntimeRejectsWrongSdkAndTamperedLibrary() {
        val root = Files.createTempDirectory("qnn-required-sdk-hash").toFile()
        try {
            val runtime = File(root, "runtime").apply { mkdirs() }
            completeProfile(runtime, 73)
            writeRuntimeMetadata(runtime, qnnSdk = "2.35", arch = 73)
            val wrongSdkMessage = qnnRequiredBundleRuntimeReadinessMessage(
                root,
                LocalImageQnnRuntimeProfile("2.28", 73, completeBundleRuntime = true)
            )
            assertPublicRuntimeMessage(wrongSdkMessage, "骁龙 8 Gen 2 NPU 运行环境")
            assertTrue(wrongSdkMessage?.contains("版本不兼容") == true)

            writeRuntimeMetadata(runtime, qnnSdk = "2.28", arch = 73)
            File(runtime, "libQnnHtp.so").appendText("tampered")
            val tamperedMessage = qnnRequiredBundleRuntimeReadinessMessage(
                root,
                LocalImageQnnRuntimeProfile("2.28", 73, completeBundleRuntime = true)
            )
            assertPublicRuntimeMessage(tamperedMessage, "骁龙 8 Gen 2 NPU 运行环境")
            assertTrue(tamperedMessage?.contains("SHA-256") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun architectureMismatchUsesPublicChipsetNameOnly() {
        val root = Files.createTempDirectory("qnn-required-public-arch-mismatch").toFile()
        try {
            val runtime = File(root, "runtime").apply { mkdirs() }
            completeProfile(runtime, 73)
            completeProfile(runtime, 81)
            writeRuntimeMetadata(runtime, qnnSdk = "2.28", arch = 81)

            val message = qnnRequiredBundleRuntimeReadinessMessage(
                root,
                LocalImageQnnRuntimeProfile("2.28", 73, completeBundleRuntime = true)
            )

            assertPublicRuntimeMessage(message, "骁龙 8 Gen 2 NPU 运行环境")
            assertTrue(message?.contains("NPU 运行环境") == true)
            assertTrue(message?.contains("不匹配") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publicRuntimeMessagesCoverSupportedSnapdragonGenerationsWithoutInternalCodes() {
        val expectedNames = mapOf(
            68 to "骁龙 888 / 骁龙 888+ NPU 运行环境",
            69 to "骁龙 8 Gen 1 / 骁龙 8+ Gen 1 NPU 运行环境",
            73 to "骁龙 8 Gen 2 NPU 运行环境",
            75 to "骁龙 8 Gen 3 NPU 运行环境",
            79 to "骁龙 8 Elite NPU 运行环境",
            81 to "骁龙 8 Elite Gen 5 NPU 运行环境"
        )

        expectedNames.forEach { (arch, expectedName) ->
            val root = Files.createTempDirectory("qnn-public-runtime-$arch").toFile()
            try {
                val message = qnnRequiredBundleRuntimeReadinessMessage(
                    root,
                    LocalImageQnnRuntimeProfile("2.46", arch, completeBundleRuntime = true)
                )

                assertPublicRuntimeMessage(message, expectedName)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun assertPublicRuntimeMessage(message: String?, expectedName: String) {
        val publicMessage = requireNotNull(message)
        assertTrue(publicMessage.contains(expectedName))
        assertFalse(INTERNAL_RUNTIME_CODE.containsMatchIn(publicMessage))
    }

    private fun completeProfile(directory: File, arch: Int) {
        File(directory, "libQnnSystem.so").writeText("x")
        File(directory, "libQnnHtp.so").writeText("x")
        File(directory, "libQnnHtpV${arch}Skel.so").writeText("x")
        File(directory, "libQnnHtpV${arch}Stub.so").writeText("x")
    }

    private fun writeRuntimeMetadata(directory: File, qnnSdk: String, arch: Int) {
        val files = org.json.JSONObject()
        qnnImageRuntimeFileNames(arch).forEach { name ->
            files.put(name, File(directory, name).resolverTestSha256())
        }
        File(directory, QNN_RUNTIME_METADATA_FILE).writeText(
            org.json.JSONObject()
                .put("schema", QNN_RUNTIME_METADATA_SCHEMA)
                .put("qnnSdk", qnnSdk)
                .put("htpArch", arch)
                .put("files", files)
                .toString(2),
            Charsets.UTF_8
        )
    }

    private fun File.resolverTestSha256(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun statusFor(directory: File, arch: Int): QnnRuntimeStatus = QnnRuntimeStatus(
        qnnSystemLibraryPresent = true,
        qnnHtpLibraryPresent = true,
        htpSkelLibraryPresent = true,
        htpStubLibraryPresent = true,
        qnnSystemLibraryPath = File(directory, "libQnnSystem.so").absolutePath,
        qnnHtpLibraryPath = File(directory, "libQnnHtp.so").absolutePath,
        htpSkelLibraryPath = File(directory, "libQnnHtpV${arch}Skel.so").absolutePath,
        htpStubLibraryPath = File(directory, "libQnnHtpV${arch}Stub.so").absolutePath,
        searchDirectories = listOf(directory.absolutePath),
        probeState = QnnRuntimeProbeState.LOADABLE
    )
}

private val INTERNAL_RUNTIME_CODE = Regex("""(?i)SM\d{4}|HTP\s*V\d+|soc_model|libQnnHtpV\d+""")

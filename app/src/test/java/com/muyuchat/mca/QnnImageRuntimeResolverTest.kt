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
    fun packagedRuntimeCandidatesRankPreferredKeepSameArchProfilesAndRequireStub() {
        val futureA = Files.createTempDirectory("qnn-packaged-v83-a").toFile()
        val futureB = Files.createTempDirectory("qnn-packaged-v83-b").toFile()
        val preferred = Files.createTempDirectory("qnn-packaged-v79").toFile()
        val missingStub = Files.createTempDirectory("qnn-packaged-v83-no-stub").toFile()
        try {
            completeProfile(futureA, 83)
            completeProfile(futureB, 83)
            completeProfile(preferred, 79)
            File(missingStub, "libQnnSystem.so").writeText("x")
            File(missingStub, "libQnnHtp.so").writeText("x")
            File(missingStub, "libQnnHtpV83Skel.so").writeText("x")

            val candidates = qnnImagePackagedRuntimeCandidates(
                searchDirectories = listOf(futureA, missingStub, preferred, futureB),
                preferredHtpArchVersion = 79
            )

            assertEquals(listOf(79, 83, 83), candidates.map(QnnImagePackagedRuntimeCandidate::htpArchVersion))
            assertEquals(
                listOf(preferred, futureA, futureB).map { directory -> listOf(directory.canonicalPath) },
                candidates.map(QnnImagePackagedRuntimeCandidate::directories)
            )
        } finally {
            futureA.deleteRecursively()
            futureB.deleteRecursively()
            preferred.deleteRecursively()
            missingStub.deleteRecursively()
        }
    }

    @Test
    fun unusablePreferredSplitCannotBlockLaterCoherentPackagedRuntime() {
        val coherent = Files.createTempDirectory("qnn-packaged-coherent-v79").toFile()
        val sideLoadedDsp = Files.createTempDirectory("qnn-packaged-split-v83").toFile()
        try {
            completeProfile(coherent, 79)
            File(sideLoadedDsp, "libQnnHtpV83Skel.so").writeText("x")
            File(sideLoadedDsp, "libQnnHtpV83Stub.so").writeText("x")

            val candidates = qnnImagePackagedRuntimeCandidates(
                searchDirectories = listOf(coherent, sideLoadedDsp),
                preferredHtpArchVersion = 83
            )

            assertEquals(listOf(79), candidates.map(QnnImagePackagedRuntimeCandidate::htpArchVersion))
            assertEquals(listOf(coherent.canonicalPath), candidates.single().directories)
        } finally {
            coherent.deleteRecursively()
            sideLoadedDsp.deleteRecursively()
        }
    }

    @Test
    fun kotlinAndNativePlatformRuntimePrefixesStayInLockstep() {
        val kotlinSource = projectFile(
            "core/deviceprofile/src/main/java/com/muyuchat/core/deviceprofile/" +
                "DeviceAccelerationProfile.kt"
        ).readText()
        val nativeSource = projectFile("core/native/src/main/cpp/qnn_native_bridge.cpp").readText()
        val kotlinBlock = kotlinSource
            .substringAfter("QNN_PLATFORM_RUNTIME_DIRECTORY_PREFIXES = listOf(")
            .substringBefore("\n)")
        val nativeBlock = nativeSource
            .substringAfter("kQnnPlatformRuntimeDirectoryPrefixes{{")
            .substringBefore("}};")
        val expected = listOf("/vendor/", "/odm/", "/system/", "/system_ext/", "/product/")

        assertEquals(expected, PLATFORM_PREFIX.findAll(kotlinBlock).map { it.groupValues[1] }.toList())
        assertEquals(expected, PLATFORM_PREFIX.findAll(nativeBlock).map { it.groupValues[1] }.toList())
    }

    @Test
    fun pinnedGenericArchiveCreatesVerifiableRuntimeMetadataForAllDeviceProfiles() {
        val root = Files.createTempDirectory("qnn-pinned-generic-runtime").toFile()
        try {
            val runtime = File(root, "runtime").apply { mkdirs() }
            listOf(68, 69, 73, 75, 79, 81).forEach { completeProfile(runtime, it) }

            val metadataFile = writePinnedQnnRuntimeMetadata(
                bundleRoot = root,
                qnnSdk = "2.28",
                contextHtpArch = 68,
                sourceArchiveSha256 = "a".repeat(64)
            )
            val metadata = org.json.JSONObject(metadataFile.readText(Charsets.UTF_8))

            assertEquals("2.28", metadata.getString("qnnSdk"))
            assertEquals(68, metadata.getInt("htpArch"))
            assertEquals(listOf(68, 69, 73, 75, 79, 81), buildList {
                val values = metadata.getJSONArray("availableHtpArchs")
                for (index in 0 until values.length()) add(values.getInt(index))
            })
            assertNull(
                qnnRequiredBundleRuntimeReadinessMessage(
                    root,
                    LocalImageQnnRuntimeProfile("2.28", 68, completeBundleRuntime = true)
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

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

    private fun projectFile(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("..", relativePath)
    ).map { it.canonicalFile }.firstOrNull(File::isFile)
        ?: error("Unable to locate project source file: $relativePath")

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
private val PLATFORM_PREFIX = Regex(""""(/[a-z_]+/)"""")

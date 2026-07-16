package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnImageRuntimeStagerTest {
    @Test
    fun stagesGenericQnn228ContextOnExactV79DeviceTransportWithoutVersionedHostLibrary() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-228-v68-v79-source").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-228-v68-v79-destination").toFile()
        try {
            val runtime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(runtime, arch = 68, buildId = QNN_228_BUILD)
            writeProfile(runtime, arch = 79, buildId = QNN_228_BUILD)
            File(runtime, "libQnnHtpV79.so").delete()
            writeRuntimeMetadata(runtime, qnnSdk = "2.28", arch = 68)

            val staged = requireNotNull(stagePlan(sourceRoot, destinationRoot, 68, 79).runtime)

            assertTrue(staged.directory.name.startsWith("v68-on-v79-"))
            assertEquals(68, staged.htpArchVersion)
            assertEquals(79, staged.transportHtpArchVersion)
            assertEquals(6, staged.files.size)
            assertFalse(File(staged.directory, "libQnnHtpV79.so").exists())
            assertTrue(File(staged.directory, "libQnnHtpV79Skel.so").isFile)
            assertTrue(File(staged.directory, "libQnnHtpV79Stub.so").isFile)
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun stagesV75ContextWithV79PhysicalTransportFromOneSdkBuild() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-v75-v79-source").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-v75-v79-destination").toFile()
        try {
            val runtime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(runtime, arch = 75, buildId = QNN_239_BUILD)
            writeProfile(runtime, arch = 79, buildId = QNN_239_BUILD)

            val staged = requireNotNull(
                QnnImageRuntimeStager(destinationRoot).stage(
                    QnnImageRuntimeStagePlan(
                        contextProfile = requireNotNull(qnnImageBundleRuntimeProfileForArchOrNull(sourceRoot, 75)),
                        transportProfile = requireNotNull(qnnImageBundleRuntimeProfileForArchOrNull(sourceRoot, 79))
                    )
                ).runtime
            )

            assertTrue(staged.directory.name.startsWith("v75-on-v79-"))
            assertEquals(75, staged.htpArchVersion)
            assertEquals(79, staged.transportHtpArchVersion)
            assertEquals(QNN_239_BUILD, staged.qnnSdkBuildId)
            assertEquals(7, staged.files.size)
            assertTrue(File(staged.directory, "libQnnHtpV75Skel.so").isFile)
            assertTrue(File(staged.directory, "libQnnHtpV75Stub.so").isFile)
            assertTrue(File(staged.directory, "libQnnHtpV79.so").isFile)
            assertTrue(File(staged.directory, "libQnnHtpV79Skel.so").isFile)
            assertTrue(File(staged.directory, "libQnnHtpV79Stub.so").isFile)
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun stagesV73ContextWithV79PhysicalTransportFromOneSdkBuild() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-v73-v79-source").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-v73-v79-destination").toFile()
        try {
            val runtime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(runtime, arch = 73, buildId = QNN_239_BUILD)
            writeProfile(runtime, arch = 79, buildId = QNN_239_BUILD)

            val staged = requireNotNull(
                QnnImageRuntimeStager(destinationRoot).stage(
                    QnnImageRuntimeStagePlan(
                        contextProfile = requireNotNull(qnnImageBundleRuntimeProfileForArchOrNull(sourceRoot, 73)),
                        transportProfile = requireNotNull(qnnImageBundleRuntimeProfileForArchOrNull(sourceRoot, 79))
                    )
                ).runtime
            )

            assertTrue(staged.directory.name.startsWith("v73-on-v79-"))
            assertEquals(7, staged.files.size)
            assertTrue(File(staged.directory, "libQnnHtpV73Stub.so").isFile)
            assertTrue(File(staged.directory, "libQnnHtpV79Stub.so").isFile)
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun acceptsOfficial245BuildIdWithoutSerialSuffix() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-245-build-source").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-245-build-destination").toFile()
        try {
            val runtime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(runtime, arch = 75, buildId = QNN_245_BUILD)
            writeProfile(runtime, arch = 79, buildId = QNN_245_BUILD)

            val staged = requireNotNull(stagePlan(sourceRoot, destinationRoot, 75, 79).runtime)

            assertEquals(QNN_245_BUILD, staged.qnnSdkBuildId)
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun missingTransportStubFailsClosed() {
        assertMissingTransportFileFailsClosed("libQnnHtpV79Stub.so")
    }

    @Test
    fun missingTransportSkelFailsClosed() {
        assertMissingTransportFileFailsClosed("libQnnHtpV79Skel.so")
    }

    @Test
    fun missingVersionedTransportLibraryFailsClosed() {
        assertMissingTransportFileFailsClosed("libQnnHtpV79.so")
    }

    @Test
    fun hostBuildMismatchFailsClosed() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-host-version-mismatch").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-host-version-mismatch-destination").toFile()
        try {
            val runtime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(runtime, arch = 75, buildId = QNN_239_BUILD)
            writeProfile(runtime, arch = 79, buildId = QNN_239_BUILD)
            writeRuntimeFile(File(runtime, "libQnnSystem.so"), "system", QNN_245_BUILD)

            val result = stagePlan(sourceRoot, destinationRoot, contextArch = 75, transportArch = 79)

            assertNull(result.runtime)
            assertNotNull(result.error)
            assertTrue(destinationRoot.listFiles().isNullOrEmpty())
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun contextAndTransportBuildMismatchFailsClosed() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-context-version-mismatch").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-context-version-mismatch-destination").toFile()
        try {
            val runtime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(runtime, arch = 75, buildId = QNN_245_BUILD)
            writeProfile(runtime, arch = 79, buildId = QNN_239_BUILD)

            val result = stagePlan(sourceRoot, destinationRoot, contextArch = 75, transportArch = 79)

            assertNull(result.runtime)
            assertNotNull(result.error)
            assertTrue(destinationRoot.listFiles().isNullOrEmpty())
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun transportDigestChangesV2Fingerprint() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-transport-fingerprint").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-transport-fingerprint-destination").toFile()
        try {
            val runtime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(runtime, arch = 75, buildId = QNN_239_BUILD)
            writeProfile(runtime, arch = 79, buildId = QNN_239_BUILD)
            val first = requireNotNull(stagePlan(sourceRoot, destinationRoot, 75, 79).runtime)
            File(runtime, "libQnnHtpV79Stub.so").appendText("changed", Charsets.ISO_8859_1)

            val second = requireNotNull(stagePlan(sourceRoot, destinationRoot, 75, 79).runtime)

            assertNotEquals(first.fingerprint, second.fingerprint)
            assertNotEquals(first.directory, second.directory)
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun strictStageDoesNotReuseLegacyContextOnlyCache() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-no-legacy-reuse").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-no-legacy-reuse-destination").toFile()
        try {
            val runtime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(runtime, arch = 75, buildId = QNN_239_BUILD)
            writeProfile(runtime, arch = 79, buildId = QNN_239_BUILD)
            val context = requireNotNull(qnnImageBundleRuntimeProfileForArchOrNull(sourceRoot, 75))
            val legacy = requireNotNull(QnnImageRuntimeStager(destinationRoot).stage(context).runtime)

            val strict = requireNotNull(stagePlan(sourceRoot, destinationRoot, 75, 79).runtime)

            assertNotEquals(legacy.directory, strict.directory)
            assertFalse(strict.reused)
            assertFalse(File(legacy.directory, "libQnnHtpV79Stub.so").exists())
            assertTrue(File(strict.directory, "libQnnHtpV79Stub.so").isFile)
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun sameContextAndTransportArchDeduplicatesRuntimeFiles() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-same-arch").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-same-arch-destination").toFile()
        try {
            val runtime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(runtime, arch = 73, buildId = QNN_239_BUILD)

            val staged = requireNotNull(stagePlan(sourceRoot, destinationRoot, 73, 73).runtime)

            assertEquals(4, staged.files.size)
            assertEquals(staged.files.map { it.name }.distinct().size, staged.files.size)
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun stagesOneCompleteProfileWithVerifiedImmutableCopy() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-source").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-destination").toFile()
        try {
            val sourceRuntime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(sourceRuntime, arch = 73)
            val sourceSnapshot = sourceRuntime.listFiles()!!.associate { it.name to it.readBytes().toList() }
            val profile = requireNotNull(coherentQnnImageBundleRuntimeProfileOrNull(sourceRoot))

            val first = QnnImageRuntimeStager(destinationRoot).stage(profile)
            val staged = requireNotNull(first.runtime)
            val second = QnnImageRuntimeStager(destinationRoot).stage(profile)

            assertNull(first.error)
            assertTrue(staged.directory.isDirectory)
            assertTrue(staged.directory.name.startsWith("v73-"))
            assertEquals(4, staged.files.size)
            assertEquals(sourceSnapshot, sourceRuntime.listFiles()!!.associate { it.name to it.readBytes().toList() })
            staged.files.forEach { file ->
                assertEquals(file.sizeBytes, File(staged.directory, file.name).length())
                assertEquals(file.sha256, File(staged.directory, file.name).stageTestSha256())
            }
            assertNotNull(second.runtime)
            assertTrue(second.runtime!!.reused)
            assertFalse(destinationRoot.listFiles().orEmpty().any { it.name.contains(".staging-") })
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun corruptExistingContentAddressedStageIsNotReplacedNonAtomically() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-source-corrupt").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-destination-corrupt").toFile()
        try {
            val sourceRuntime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(sourceRuntime, arch = 73)
            val profile = requireNotNull(coherentQnnImageBundleRuntimeProfileOrNull(sourceRoot))
            val stager = QnnImageRuntimeStager(destinationRoot)
            val first = requireNotNull(stager.stage(profile).runtime)
            val corrupt = File(first.directory, "libQnnHtp.so")
            corrupt.writeBytes(byteArrayOf(9, 9, 9))

            val retry = stager.stage(profile)

            assertNull(retry.runtime)
            assertNotNull(retry.error)
            assertTrue(retry.error!!.contains("骁龙 8 Gen 2 NPU 运行环境"))
            assertFalse(Regex("""(?i)SM\d{4}|HTP\s*V\d+|soc_model|libQnnHtpV\d+""").containsMatchIn(retry.error!!))
            assertEquals(listOf<Byte>(9, 9, 9), corrupt.readBytes().toList())
            assertFalse(destinationRoot.listFiles().orEmpty().any { it.name.contains(".staging-") })
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun incompleteProfileCannotBeStaged() {
        val root = Files.createTempDirectory("qnn-stage-incomplete").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-incomplete-destination").toFile()
        try {
            File(root, "libQnnSystem.so").writeText("system")
            File(root, "libQnnHtp.so").writeText("htp")
            File(root, "libQnnHtpV73Skel.so").writeText("skel")

            assertNull(coherentQnnImageBundleRuntimeProfileOrNull(root))
            assertTrue(destinationRoot.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun contractResolutionStagesExactV73WhenV81AlsoExists() {
        val sourceRoot = Files.createTempDirectory("qnn-stage-exact-contract").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-exact-contract-destination").toFile()
        try {
            val sourceRuntime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(sourceRuntime, arch = 73)
            writeProfile(sourceRuntime, arch = 81)
            writeRuntimeMetadata(sourceRuntime, qnnSdk = "2.28", arch = 73)
            val resolution = qnnRequiredBundleRuntimeResolution(
                sourceRoot,
                LocalImageQnnRuntimeProfile("2.28", 73, completeBundleRuntime = true)
            )

            assertNull(resolution.error)
            assertEquals(73, resolution.runtimeProfile?.htpArchVersion)
            val staged = requireNotNull(
                QnnImageRuntimeStager(destinationRoot).stage(requireNotNull(resolution.runtimeProfile)).runtime
            )
            assertTrue(staged.directory.name.startsWith("v73-"))
            assertTrue(File(staged.directory, "libQnnHtpV73Skel.so").isFile)
            assertFalse(File(staged.directory, "libQnnHtpV81Skel.so").exists())
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    private fun assertMissingTransportFileFailsClosed(missingName: String) {
        val sourceRoot = Files.createTempDirectory("qnn-stage-missing-transport").toFile()
        val destinationRoot = Files.createTempDirectory("qnn-stage-missing-transport-destination").toFile()
        try {
            val runtime = File(sourceRoot, "runtime").apply { mkdirs() }
            writeProfile(runtime, arch = 75, buildId = QNN_239_BUILD)
            writeProfile(runtime, arch = 79, buildId = QNN_239_BUILD)
            File(runtime, missingName).delete()
            val context = requireNotNull(qnnImageBundleRuntimeProfileForArchOrNull(sourceRoot, 75))
            val result = QnnImageRuntimeStager(destinationRoot).stage(
                QnnImageRuntimeStagePlan(
                    contextProfile = context,
                    transportProfile = QnnImageBundleRuntimeProfile(runtime, 79)
                )
            )

            assertNull(result.runtime)
            assertNotNull(result.error)
            assertTrue(destinationRoot.listFiles().isNullOrEmpty())
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    private fun stagePlan(
        sourceRoot: File,
        destinationRoot: File,
        contextArch: Int,
        transportArch: Int
    ): QnnImageRuntimeStagingResult = QnnImageRuntimeStager(destinationRoot).stage(
        QnnImageRuntimeStagePlan(
            contextProfile = requireNotNull(qnnImageBundleRuntimeProfileForArchOrNull(sourceRoot, contextArch)),
            transportProfile = requireNotNull(qnnImageBundleRuntimeProfileForArchOrNull(sourceRoot, transportArch))
        )
    )

    private fun writeProfile(directory: File, arch: Int, buildId: String? = null) {
        val systemBuildId = buildId?.substringAfterLast('.')
        writeRuntimeFile(File(directory, "libQnnSystem.so"), "system", systemBuildId)
        writeRuntimeFile(File(directory, "libQnnHtp.so"), "htp", buildId)
        writeRuntimeFile(File(directory, "libQnnHtpV${arch}.so"), "transport-$arch", buildId)
        writeRuntimeFile(File(directory, "libQnnHtpV${arch}Skel.so"), "skel-$arch", buildId)
        writeRuntimeFile(File(directory, "libQnnHtpV${arch}Stub.so"), "stub-$arch", buildId)
    }

    private fun writeRuntimeFile(file: File, marker: String, buildId: String?) {
        if (buildId == null) {
            file.writeText(marker, Charsets.ISO_8859_1)
        } else {
            file.writeText("$marker\u0000$buildId\u0000", Charsets.ISO_8859_1)
        }
    }

    private fun writeRuntimeMetadata(directory: File, qnnSdk: String, arch: Int) {
        val files = org.json.JSONObject()
        qnnImageRuntimeFileNames(arch).forEach { name ->
            files.put(name, File(directory, name).stageTestSha256())
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

    private fun File.stageTestSha256(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val QNN_228_BUILD = "v2.28.0.240101010101_1"
        const val QNN_239_BUILD = "v2.39.0.250925215840_163802"
        const val QNN_245_BUILD = "v2.45.0.260326154327"
    }
}

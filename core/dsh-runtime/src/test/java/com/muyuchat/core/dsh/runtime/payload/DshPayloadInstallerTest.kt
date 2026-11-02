package com.muyuchat.core.dsh.runtime.payload

import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshJsonTest {

    @Test
    fun parsesObjectsArraysEscapesAndNumbers() {
        val value = DshJson.parse(
            """{"a":[1,2,{"b":"x\"y\nz"}],"ok":true,"none":null,"n":-1.5e2}"""
        )
        val root = DshJson.asObject(value, "root")
        val array = DshJson.asArray(root["a"], "a")
        assertEquals(1L, (array[0] as Long))
        assertEquals(2L, (array[1] as Long))
        assertEquals("x\"y\nz", DshJson.asString(DshJson.asObject(array[2], "obj"), "b", "obj"))
        assertTrue(root["ok"] as Boolean)
        org.junit.Assert.assertNull(root["none"])
    }

    @Test(expected = DshJson.DshJsonException::class)
    fun rejectsTrailingContent() {
        DshJson.parse("{} x")
    }

    @Test(expected = DshJson.DshJsonException::class)
    fun rejectsControlCharacterInString() {
        DshJson.parse("\"a\u0001b\"")
    }
}

class DshPayloadManifestTest {

    private val sha = "a".repeat(64)

    private fun manifestJson(
        archiveSha: String = sha,
        fileCount: Long = 2,
        criticalSha: String = sha
    ): String = """
        {
          "manifestVersion": 1,
          "payloadVersion": "dsh-test",
          "archive": {"asset": "dsh/payload.zip", "size": 10, "sha256": "$archiveSha"},
          "node": {"jniLib": "libdshnode.so", "size": 5, "sha256": "$sha"},
          "kernelEntry": "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
          "runtimeLibraryDir": "runtime/lib",
          "fileCount": $fileCount,
          "criticalFiles": [
            {"path": "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js", "size": 3, "sha256": "$criticalSha"}
          ],
          "source": {"officialCommit": "687f282e", "referenceRepo": "https://example.invalid", "referenceVersion": "v"},
          "licenses": [{"name": "DSH", "license": "MIT", "source": "https://example.invalid"}]
        }
    """.trimIndent()

    @Test
    fun parsesManifestAndMarkerRoundTrips() {
        val manifest = DshPayloadManifest.parse(manifestJson())
        assertEquals(1, manifest.manifestVersion)
        assertEquals(2L, manifest.fileCount)
        assertEquals(1, manifest.criticalFiles.size)
        val marker = DshPayloadMarker(1, "dsh-test", sha, 2, 123L)
        assertTrue(marker.matches(manifest))
        assertEquals(marker, DshPayloadMarker.parse(marker.toJson()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonSha256Hash() {
        DshPayloadManifest.parse(manifestJson(archiveSha = "not-a-hash"))
    }

    @Test
    fun wrongFileCountOrHashPreventsMarkerMatch() {
        val manifest = DshPayloadManifest.parse(manifestJson())
        assertFalse(DshPayloadMarker(1, "dsh-test", "b".repeat(64), 2, 1L).matches(manifest))
        assertFalse(DshPayloadMarker(1, "dsh-test", sha, 3, 1L).matches(manifest))
    }
}

class DshPayloadInstallerTest {

    private val kernelEntry = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
    private val files = linkedMapOf(
        kernelEntry to "console.log('kernel')",
        "dshhome/profiles/web/package.json" to "{\"name\":\"dsh-profile-web\"}",
        "runtime/lib/libz.so.1" to "zlib-bytes",
        "runtime/lib/libc++_shared.so" to "c++-bytes",
        "runtime/lib/libsqlite3.so" to "sqlite-bytes",
        "extra.txt" to "extra"
    )

    private fun sha256(bytes: ByteArray): String =
        (MessageDigest.getInstance("SHA-256").digest(bytes)).joinToString("") { "%02x".format(it) }

    private fun buildZip(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun buildManifest(archive: ByteArray, forceArchiveSha: String? = null): Pair<String, DshPayloadManifest> {
        val archiveSha = forceArchiveSha ?: sha256(archive)
        val critical = listOf(
            DshPayloadCriticalFile(kernelEntry, files.getValue(kernelEntry).toByteArray().size.toLong(), sha256(files.getValue(kernelEntry).toByteArray())),
            DshPayloadCriticalFile(
                "runtime/lib/libz.so.1",
                files.getValue("runtime/lib/libz.so.1").toByteArray().size.toLong(),
                sha256(files.getValue("runtime/lib/libz.so.1").toByteArray())
            )
        )
        val json = DshJson.write(
            linkedMapOf(
                "manifestVersion" to 1,
                "payloadVersion" to "dsh-test",
                "archive" to linkedMapOf("asset" to "dsh/payload.zip", "size" to archive.size, "sha256" to archiveSha),
                "node" to linkedMapOf("jniLib" to "libdshnode.so", "size" to 1, "sha256" to "b".repeat(64)),
                "kernelEntry" to kernelEntry,
                "runtimeLibraryDir" to "runtime/lib",
                "fileCount" to files.size,
                "criticalFiles" to critical.map { it.toTree() },
                "source" to linkedMapOf("officialCommit" to "x", "referenceRepo" to "y", "referenceVersion" to "z"),
                "licenses" to listOf(linkedMapOf("name" to "DSH", "license" to "MIT"))
            )
        )
        return json to DshPayloadManifest.parse(json)
    }

    private fun source(manifestJson: String, archive: ByteArray): DshPayloadSource = object : DshPayloadSource {
        override val description = "test"
        override fun openManifest() = ByteArrayInputStream(manifestJson.toByteArray())
        override fun openArchive() = ByteArrayInputStream(archive)
    }

    private fun installer(dataRoot: File, manifestJson: String, archive: ByteArray, clock: () -> Long = { 1L }) =
        DshPayloadInstaller(
            config = DshRuntimeConfig(dataRoot = dataRoot).validated(),
            source = source(manifestJson, archive),
            clock = clock
        )

    @Test
    fun installsVerifiesAndReusesPayload() {
        val root = createTempDir()
        val archive = buildZip()
        val (manifestJson, _) = buildManifest(archive)
        val installer = installer(root, manifestJson, archive)

        val first = installer.ensureInstalled()
        assertTrue(first is DshPayloadInstallResult.Installed)
        assertFalse((first as DshPayloadInstallResult.Installed).reused)
        assertTrue(File(root, "payload/$kernelEntry").isFile)
        assertTrue(File(root, "payload.marker.json").isFile)
        assertEquals(6, File(root, "payload").walkTopDown().count { it.isFile })

        val second = installer.ensureInstalled()
        assertTrue(second is DshPayloadInstallResult.Installed)
        assertTrue((second as DshPayloadInstallResult.Installed).reused)
    }

    @Test
    fun archiveHashMismatchFailsClosedWithoutPayloadTree() {
        val root = createTempDir()
        val archive = buildZip()
        val (manifestJson, _) = buildManifest(archive, forceArchiveSha = "c".repeat(64))
        val result = installer(root, manifestJson, archive).ensureInstalled()
        assertTrue(result is DshPayloadInstallResult.Integrity)
        assertFalse(File(root, "payload").exists())
    }

    @Test
    fun corruptCriticalFileTriggersReinstall() {
        val root = createTempDir()
        val archive = buildZip()
        val (manifestJson, _) = buildManifest(archive)
        val installer = installer(root, manifestJson, archive)
        assertTrue(installer.ensureInstalled() is DshPayloadInstallResult.Installed)
        File(root, "payload/$kernelEntry").writeText("tampered")
        val repaired = installer.ensureInstalled()
        assertTrue(repaired is DshPayloadInstallResult.Installed)
        assertFalse((repaired as DshPayloadInstallResult.Installed).reused)
        assertEquals(files.getValue(kernelEntry), File(root, "payload/$kernelEntry").readText())
    }

    @Test
    fun pathTraversalEntryIsRejected() {
        val root = createTempDir()
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../evil.txt"))
            zip.write("evil".toByteArray())
            zip.closeEntry()
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val archive = out.toByteArray()
        val (manifestJson, _) = buildManifest(archive)
        // file count in manifest is 6 but this archive has 7 entries; the traversal is rejected
        // before the count is compared, so the result is still a fail-closed Integrity error.
        val result = installer(root, manifestJson, archive).ensureInstalled()
        assertTrue(result is DshPayloadInstallResult.Integrity)
        assertFalse(File(root.parentFile, "evil.txt").exists())
        assertFalse(File(root, "payload").exists())
    }

    @Test
    fun missingAssetsReportMissing() {
        val root = createTempDir()
        val empty = object : DshPayloadSource {
            override val description = "test"
            override fun openManifest() = null
            override fun openArchive() = null
        }
        val result = DshPayloadInstaller(DshRuntimeConfig(dataRoot = root).validated(), empty).ensureInstalled()
        assertTrue(result is DshPayloadInstallResult.Missing)
        assertNotNull((result as DshPayloadInstallResult.Missing).detail)
    }
}

private fun createTempDir(): File = java.nio.file.Files.createTempDirectory("dsh-test").toFile()

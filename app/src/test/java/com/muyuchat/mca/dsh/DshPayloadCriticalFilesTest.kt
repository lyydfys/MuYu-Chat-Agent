package com.muyuchat.mca.dsh

import com.muyuchat.core.dsh.maintenance.DshMaintenanceIo
import com.muyuchat.core.dsh.maintenance.DshManagedFileRoot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection rules of the managed repair inventory.
 *
 * The inventory is a projection of the build's payload manifest, so these tests feed it a manifest
 * with a valid entry, a traversal attempt, a short hash, a duplicate, a user-data root and a
 * path outside the payload tree. Only the valid, unique, payload-internal entry may survive.
 */
class DshPayloadCriticalFilesTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        roots.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun root(): File = File(
        System.getProperty("java.io.tmpdir"),
        "mca-payload-repair-" + System.nanoTime()
    ).also {
        it.mkdirs()
        roots += it
    }

    private fun hash(bytes: ByteArray) = DshMaintenanceIo.sha256(bytes)

    private fun manifest(vararg entries: Pair<String, String>): JSONObject {
        val files = JSONArray()
        entries.forEach { (path, sha) ->
            files.put(JSONObject().put("path", path).put("sha256", sha).put("size", 1))
        }
        return JSONObject().put("manifestVersion", 1).put("criticalFiles", files)
    }

    @Test
    fun onlySafeUniquePayloadPathsWithAFullHashAreAccepted() {
        val valid = hash("kernel".toByteArray())
        val parsed = DshPayloadCriticalFiles.parse(
            manifest(
                "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" to valid,
                "../escape.js" to valid,
                "/absolute/path.js" to valid,
                "dshroot/short-hash.js" to "abc",
                "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" to valid,
                "sessions/main/session.v4.jsonl.zstd" to valid,
                "workspace/tool.js" to valid,
                "runtime/lib/libz.so.1" to valid.uppercase()
            )
        )
        assertEquals(2, parsed.size)
        assertEquals(
            "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
            parsed[0].relativePath
        )
        assertEquals(DshManagedFileRoot.PAYLOAD, parsed[0].root)
        assertEquals(valid, parsed[0].expectedSha256)
        // Hashes are normalized so a manifest that upper-cases them still compares correctly.
        assertEquals("runtime/lib/libz.so.1", parsed[1].relativePath)
        assertEquals(valid.lowercase(), parsed[1].expectedSha256)
    }

    @Test
    fun theInventoryIsBoundedEvenWhenTheManifestIsNot() {
        val sha = hash("x".toByteArray())
        val entries = (0 until DshPayloadCriticalFiles.MAX_MANAGED_FILES + 20).map {
            "dshroot/entry-$it.js" to sha
        }
        val parsed = DshPayloadCriticalFiles.parse(manifest(*entries.toTypedArray()))
        assertEquals(DshPayloadCriticalFiles.MAX_MANAGED_FILES, parsed.size)
    }

    @Test
    fun anUnreadableOrEmptyManifestYieldsNoInventory() {
        assertTrue(DshPayloadCriticalFiles.parse(null).isEmpty())
        assertTrue(DshPayloadCriticalFiles.parse("").isEmpty())
        assertTrue(DshPayloadCriticalFiles.parse("{not json").isEmpty())
        assertTrue(DshPayloadCriticalFiles.parse("""{"manifestVersion":1}""").isEmpty())
    }

    @Test
    fun acceptedPathsStayInsideTheKernelAndRuntimeRoots() {
        assertTrue(DshPayloadCriticalFiles.isRepairablePayloadPath("dshroot/bin.js"))
        assertTrue(DshPayloadCriticalFiles.isRepairablePayloadPath("runtime/lib/libz.so.1"))
        assertFalse(DshPayloadCriticalFiles.isRepairablePayloadPath("dshroot/../etc/passwd"))
        assertFalse(DshPayloadCriticalFiles.isRepairablePayloadPath("dshroot\\bin.js"))
        assertFalse(DshPayloadCriticalFiles.isRepairablePayloadPath("C:/dshroot/bin.js"))
        assertFalse(DshPayloadCriticalFiles.isRepairablePayloadPath("home/profiles/web/cordis.yml"))
        assertFalse(DshPayloadCriticalFiles.isRepairablePayloadPath("sessions/x.v4.jsonl.zstd"))
        assertFalse(DshPayloadCriticalFiles.isRepairablePayloadPath("dshroot/"))
    }

    @Test
    fun theRepairSourceIsExtractedFromTheArchiveAndHashVerified() {
        val content = "kernel bytes".toByteArray()
        val archive = zip(mapOf("dshroot/lib/bin.js" to content, "other/file.txt" to "ignore".toByteArray()))
        val root = root()
        val declaration = com.muyuchat.core.dsh.maintenance.DshManagedFile(
            relativePath = "dshroot/lib/bin.js",
            expectedSha256 = hash(content),
            root = DshManagedFileRoot.PAYLOAD
        )
        val source = DshPayloadRepairSource({ ByteArrayInputStream(archive) }, root)
            .extract(listOf(declaration))
        assertNotNull(source)
        val extracted = File(source!!, "dshroot/lib/bin.js")
        assertTrue(extracted.isFile)
        assertEquals("kernel bytes", extracted.readText())
        assertFalse(File(source, "other/file.txt").exists())
        source.deleteRecursively()
    }

    @Test
    fun aMismatchingOrMissingArchiveEntryProducesNoRepairSource() {
        val content = "kernel bytes".toByteArray()
        val root = root()
        val mismatched = com.muyuchat.core.dsh.maintenance.DshManagedFile(
            relativePath = "dshroot/lib/bin.js",
            expectedSha256 = hash("different".toByteArray()),
            root = DshManagedFileRoot.PAYLOAD
        )
        val archive = zip(mapOf("dshroot/lib/bin.js" to content))
        assertNull(
            DshPayloadRepairSource({ ByteArrayInputStream(archive) }, root)
                .extract(listOf(mismatched))
        )

        val missing = com.muyuchat.core.dsh.maintenance.DshManagedFile(
            relativePath = "dshroot/lib/missing.js",
            expectedSha256 = hash(content),
            root = DshManagedFileRoot.PAYLOAD
        )
        assertNull(
            DshPayloadRepairSource({ ByteArrayInputStream(archive) }, root)
                .extract(listOf(missing))
        )
        // A missing archive asset (staging build) is never treated as "nothing to repair".
        assertNull(DshPayloadRepairSource({ null }, root).extract(listOf(mismatched)))
        // Nothing is left behind by a failed extraction.
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("payload-repair-") })
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

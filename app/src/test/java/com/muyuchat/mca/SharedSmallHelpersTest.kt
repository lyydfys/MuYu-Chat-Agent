package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two helpers consolidated out of LocalQnnSmokeSpec, QnnNativeSmokeBridge,
 * LocalImageProvider and LocalVisionBundle.
 *
 * Both pairs were identical before, so this does not cover behaviour that was untested. It
 * matters because both sit where a wrong answer is quiet: a dropped integer changes a native
 * smoke request's shape, and a missed descendant file makes a downloaded bundle look
 * incomplete.
 */
class SharedSmallHelpersTest {

    private val temporaryDirectory: File = Files.createTempDirectory("mca-helpers").toFile()

    @After
    fun cleanUp() {
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun `a missing array yields an empty list`() {
        val absent: JSONArray? = null
        assertEquals(emptyList<Int>(), absent.toIntList())
    }

    @Test
    fun `only positive entries survive`() {
        // Zero and negatives are dropped rather than passed through to the native request.
        assertEquals(listOf(1, 5), JSONArray("""[1,0,-3,5]""").toIntList())
    }

    @Test
    fun `order is preserved and non-numeric entries read as zero`() {
        assertEquals(listOf(3, 7), JSONArray("""[3,"x",7]""").toIntList())
    }

    @Test
    fun `a descendant is found anywhere in the tree, ignoring case`() {
        val nested = File(temporaryDirectory, "a/b/c").apply { mkdirs() }
        val target = File(nested, "TARGET.bin").apply { writeText("x") }

        assertEquals(target, temporaryDirectory.findDescendantFile("target.BIN"))
    }

    @Test
    fun `a missing descendant is null, not an exception`() {
        assertNull(temporaryDirectory.findDescendantFile("absent.bin"))
    }

    @Test
    fun `directories with a matching name are not returned`() {
        // The predicate requires isFile, so a directory called like the target does not match.
        File(temporaryDirectory, "model.mnn").apply { mkdirs() }
        assertTrue(temporaryDirectory.findDescendantFile("model.mnn") == null)
    }
}

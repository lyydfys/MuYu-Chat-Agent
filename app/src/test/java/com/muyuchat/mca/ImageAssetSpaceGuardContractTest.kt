package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the storage checks that gate writing an image asset into the library.
 *
 * `ImageAssetFiles` measures the free space of the asset root before it publishes a generated
 * image and again for every chunk it copies while importing one. Nothing covered those checks:
 * no test referenced `MIN_IMAGE_ASSET_FREE_SPACE_BYTES`, and the only test that reads this file
 * asserts the presence of `copyImageAssetStreamAtomically` instead. Removing or weakening a
 * check would therefore have failed no test, even though the consequence is writing images onto
 * a nearly full volume.
 *
 * Scope, stated plainly: this is a source contract, not a behavioural proof. The file measures
 * `File.usableSpace` directly, so there is no injectable seam to drive a low-space path from a
 * JVM test; a behavioural test needs that seam to exist first. These assertions fail if a guard
 * is deleted, reordered behind the write, or stops including the reserve.
 */
class ImageAssetSpaceGuardContractTest {

    @Test
    fun `the safety reserve stays at 64 MiB`() {
        val source = sourceFile(IMAGE_ASSET_FILES)

        assertTrue(
            "the asset safety reserve must remain a named constant",
            source.contains(
                "private const val MIN_IMAGE_ASSET_FREE_SPACE_BYTES = 64L * 1024L * 1024L"
            )
        )
    }

    @Test
    fun `publishing a generated image requires the bytes plus the reserve`() {
        val source = sourceFile(IMAGE_ASSET_FILES)
        val body = functionBody(source, "fun writeImageAssetBytesAtomically(")

        assertTrue(
            "the required space must include the reserve, not just the payload size",
            body.contains(
                "val requiredSpace = Math.addExact(bytes.size.toLong(), " +
                    "MIN_IMAGE_ASSET_FREE_SPACE_BYTES)"
            )
        )
        assertTrue(
            "the publish guard must accept a zero/unknown reading and otherwise demand the reserve",
            body.contains("require(usableSpace <= 0L || usableSpace >= requiredSpace)")
        )
        assertTrue(
            "an insufficient-space publish must fail with an actionable message",
            body.contains("Insufficient storage to publish the generated image")
        )
    }

    @Test
    fun `importing an image rechecks the reserve for every chunk and keeps the library cap`() {
        val source = sourceFile(IMAGE_ASSET_FILES)
        val body = functionBody(source, "suspend fun copyImageAssetStreamAtomically(")

        assertTrue(
            "the import path must re-measure free space as it copies",
            body.contains("val remainingSpace = root.usableSpace")
        )
        assertTrue(
            "each chunk must be charged against the reserve, not against the total once",
            body.contains(
                "remainingSpace >= Math.addExact(read.toLong(), MIN_IMAGE_ASSET_FREE_SPACE_BYTES)"
            )
        )
        assertTrue(
            "an insufficient-space import must fail with an actionable message",
            body.contains("Insufficient storage to import the image")
        )
        assertTrue(
            "the per-asset library limit must stay enforced",
            body.contains("require(copied <= MAX_IMAGE_ASSET_FILE_BYTES)")
        )
    }

    @Test
    fun `the library cap and reserve are ordered so the reserve can actually be honoured`() {
        val source = sourceFile(IMAGE_ASSET_FILES)
        val cap = constantValue(source, "MAX_IMAGE_ASSET_FILE_BYTES")
        val reserve = constantValue(source, "MIN_IMAGE_ASSET_FREE_SPACE_BYTES")

        assertEquals("the library cap must stay at 96 MiB", 96L * 1024L * 1024L, cap)
        assertEquals("the safety reserve must stay at 64 MiB", 64L * 1024L * 1024L, reserve)
        assertTrue(
            "the reserve must be smaller than the per-asset cap, otherwise every import fails",
            reserve < cap
        )
    }

    /**
     * Reads a `private const val NAME = <a * b * c>` declaration and evaluates its literal
     * product. Only the multiplication form is accepted so an accidental edit to a plain number,
     * a subtraction, or a unit change cannot slip through as a passing value.
     */
    private fun constantValue(source: String, name: String): Long {
        val match = Regex("const val $name = ([0-9L *]+)").find(source)
        requireNotNull(match) { "Unable to find the $name declaration in $IMAGE_ASSET_FILES" }
        val factors = match.groupValues[1]
            .split('*')
            .map { it.trim().removeSuffix("L") }
        assertTrue("$name must be written as a product of literal factors", factors.size >= 2)
        return factors.fold(1L) { product, factor ->
            Math.multiplyExact(product, factor.toLongOrNull() ?: return@fold -1L)
        }
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }

    /**
     * Returns the body of the first declaration whose text contains [signature].
     *
     * The body is delimited by brace depth rather than by searching for the first closing brace
     * on its own line: an earlier version did the latter and stopped at the closing brace of a
     * nested `require` block, so the assertions below silently inspected a truncated body.
     */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Unable to locate `$signature` in $IMAGE_ASSET_FILES" }
        val open = source.indexOf('{', start)
        require(open >= 0) { "`$signature` has no body in $IMAGE_ASSET_FILES" }
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        error("Unbalanced braces after `$signature` in $IMAGE_ASSET_FILES")
    }

    private companion object {
        const val IMAGE_ASSET_FILES = "app/src/main/java/com/muyuchat/mca/ImageAssetFiles.kt"
    }
}

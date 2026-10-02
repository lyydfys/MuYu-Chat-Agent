package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the publish-path guards in `ImageAssetFiles` that run before any byte is written.
 *
 * `writeImageAssetBytesAtomically` refuses six conditions before it stages anything: empty bytes,
 * an unsafe leaf name, a directory it cannot create, an escaped output path, an output that
 * already exists, and insufficient free space. Only the last one had a test, and it needed a new
 * seam to reach. The others are driven by the filesystem rather than by free space, so they can be
 * exercised directly, and none of their messages appeared anywhere in the suite.
 *
 * These are behavioural, not source-text assertions: each one drives the real function and checks
 * what it refuses, what it leaves behind, and what it writes when it accepts.
 *
 * The free-space guard has its own file, `ImageAssetSpaceGuardContractTest`, because reaching it
 * required adding the `usableSpaceOverride` parameter.
 */
class ImageAssetPublishGuardTest {

    @Test
    fun `empty bytes are refused before any directory work happens`() {
        withTempPath { path ->
            val refusal = runCatching {
                writeImageAssetBytesAtomically(
                    directory = path,
                    fileName = "empty.png",
                    bytes = ByteArray(0),
                    parentDirectorySyncer = NO_SYNCER
                )
            }
            assertEquals(
                "Generated image bytes must not be empty.",
                refusal.exceptionOrNull()?.message
            )
            assertFalse(
                "a refused publish must not create the asset directory",
                path.exists()
            )
        }
    }

    @Test
    fun `file names that are not plain leaf names are refused`() {
        val bytes = byteArrayOf(1, 2, 3)
        val unsafe = listOf(
            "" to "a blank name",
            "   " to "a whitespace-only name",
            "../escape.png" to "a parent escape",
            "nested/inside.png" to "a nested path",
            "..\\windows-escape.png" to "a backslash parent escape",
            "/absolute.png" to "an absolute path"
        )
        withTempPath { path ->
            unsafe.forEach { (name, description) ->
                val refusal = runCatching {
                    writeImageAssetBytesAtomically(
                        directory = path,
                        fileName = name,
                        bytes = bytes,
                        parentDirectorySyncer = NO_SYNCER
                    )
                }
                assertTrue(
                    "$description must be refused, got $refusal",
                    refusal.exceptionOrNull() is IllegalArgumentException
                )
                assertTrue(
                    "$description must be refused as an unsafe leaf name",
                    refusal.exceptionOrNull()?.message.orEmpty().contains("safe leaf name")
                )
            }
            assertFalse(
                "no refused name may create the asset directory",
                path.exists()
            )
            assertFalse(
                "no refused name may escape into the parent directory",
                File(path.parentFile, "escape.png").exists() ||
                    File(path.parentFile, "windows-escape.png").exists() ||
                    File(path.parentFile, "absolute.png").exists()
            )
        }
    }

    @Test
    fun `a directory that cannot be created is refused instead of failing later`() {
        withTempPath { path ->
            // Occupy the asset-directory path with a regular file so mkdirs() cannot succeed.
            path.writeText("occupied")
            val bytes = byteArrayOf(9, 9, 9)

            val refusal = runCatching {
                writeImageAssetBytesAtomically(
                    directory = path,
                    fileName = "result.png",
                    bytes = bytes,
                    parentDirectorySyncer = NO_SYNCER
                )
            }
            assertEquals(
                "Unable to create the image asset directory.",
                refusal.exceptionOrNull()?.message
            )
            assertEquals(
                "the occupying file must be left exactly as it was",
                "occupied",
                path.readText()
            )
        }
    }

    @Test
    fun `an existing destination is refused and the original bytes are kept`() {
        val original = byteArrayOf(1, 1, 1, 1)
        val replacement = byteArrayOf(2, 2, 2, 2)
        withTempDirectory { directory ->
            val first = writeImageAssetBytesAtomically(
                directory = directory,
                fileName = "keep.png",
                bytes = original,
                parentDirectorySyncer = NO_SYNCER
            )
            assertArrayEquals(original, first.readBytes())

            val refusal = runCatching {
                writeImageAssetBytesAtomically(
                    directory = directory,
                    fileName = "keep.png",
                    bytes = replacement,
                    parentDirectorySyncer = NO_SYNCER
                )
            }
            assertEquals(
                "Generated image destination already exists.",
                refusal.exceptionOrNull()?.message
            )
            assertArrayEquals(
                "a refused overwrite must not modify the existing asset",
                original,
                first.readBytes()
            )
            assertTrue(
                "a refused overwrite must leave no staging file",
                directory.listFiles().orEmpty().none { it.name.endsWith(".part") }
            )
        }
    }

    @Test
    fun `a successful publish writes exactly the given bytes and cleans up staging`() {
        val bytes = ByteArray(4_096) { (it % 251).toByte() }
        withTempDirectory { directory ->
            val published = writeImageAssetBytesAtomically(
                directory = directory,
                fileName = "exact.png",
                bytes = bytes,
                parentDirectorySyncer = NO_SYNCER
            )

            assertEquals("exact.png", published.name)
            assertEquals(directory.canonicalFile, published.parentFile)
            assertArrayEquals(bytes, published.readBytes())
            assertEquals(
                "the published file must be the only entry left behind",
                listOf("exact.png"),
                directory.listFiles().orEmpty().map { it.name }.sorted()
            )
        }
    }

    @Test
    fun `the published path never leaves the asset directory`() {
        val bytes = byteArrayOf(5, 5)
        withTempDirectory { directory ->
            val published = writeImageAssetBytesAtomically(
                directory = directory,
                fileName = "inside.png",
                bytes = bytes,
                parentDirectorySyncer = NO_SYNCER
            )
            assertEquals(
                "the canonical parent must be the canonical asset root",
                directory.canonicalFile,
                published.parentFile
            )
        }
    }

    private fun withTempPath(block: (File) -> Unit) {
        val root = Files.createTempDirectory("mca-image-publish-guard").toFile()
        try {
            block(File(root, "assets"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("mca-image-publish-ok").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        val NO_SYNCER = ParentDirectorySyncer { }
    }
}

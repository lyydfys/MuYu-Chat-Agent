package com.muyuchat.mca

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageBundleZipExtractionTest {
    @Test
    fun `prefix collision traversal cannot escape the bundle root`() {
        val root = Files.createTempDirectory("image-bundle-prefix").toFile()
        val sibling = File(requireNotNull(root.parentFile), "${root.name}-evil")
        try {
            val source = zipOf(
                ArchiveEntry("../${sibling.name}/pwn.bin", byteArrayOf(1, 2, 3))
            )

            assertRejected {
                extractBoundedImageBundleZip(ByteArrayInputStream(source), root)
            }

            assertFalse(File(sibling, "pwn.bin").exists())
        } finally {
            root.deleteRecursively()
            sibling.deleteRecursively()
        }
    }

    @Test
    fun `non canonical and absolute entry paths are rejected`() {
        listOf(
            "/absolute.bin",
            "C:/absolute.bin",
            "a/./model.bin",
            "a//model.bin"
        ).forEach { unsafePath ->
            val root = Files.createTempDirectory("image-bundle-path").toFile()
            try {
                assertRejected {
                    extractBoundedImageBundleZip(
                        ByteArrayInputStream(zipOf(ArchiveEntry(unsafePath, byteArrayOf(1)))),
                        root
                    )
                }
                assertTrue(root.listFiles().orEmpty().isEmpty())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `separator aliases and parent file conflicts are rejected`() {
        listOf(
            listOf(
                ArchiveEntry("a/model.bin", byteArrayOf(1)),
                ArchiveEntry("a\\model.bin", byteArrayOf(2))
            ),
            listOf(
                ArchiveEntry("model.bin", byteArrayOf(1)),
                ArchiveEntry("model.bin/child.bin", byteArrayOf(2))
            )
        ).forEach { entries ->
            val root = Files.createTempDirectory("image-bundle-conflict").toFile()
            try {
                assertRejected {
                    extractBoundedImageBundleZip(ByteArrayInputStream(zipOf(*entries.toTypedArray())), root)
                }
                assertTrue(root.listFiles().orEmpty().isEmpty())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `entry count expanded entry and expanded total limits are enforced`() {
        val cases = listOf(
            ZipLimitCase(
                entries = listOf(
                    ArchiveEntry("one.bin", byteArrayOf(1)),
                    ArchiveEntry("two.bin", byteArrayOf(2))
                ),
                limits = ImageBundleZipExtractionLimits(
                    maxEntryCount = 1,
                    maxEntryBytes = 8,
                    maxTotalBytes = 8
                )
            ),
            ZipLimitCase(
                entries = listOf(ArchiveEntry("large.bin", byteArrayOf(1, 2, 3, 4))),
                limits = ImageBundleZipExtractionLimits(
                    maxEntryCount = 2,
                    maxEntryBytes = 3,
                    maxTotalBytes = 8
                )
            ),
            ZipLimitCase(
                entries = listOf(
                    ArchiveEntry("one.bin", byteArrayOf(1, 2, 3)),
                    ArchiveEntry("two.bin", byteArrayOf(4, 5, 6))
                ),
                limits = ImageBundleZipExtractionLimits(
                    maxEntryCount = 2,
                    maxEntryBytes = 4,
                    maxTotalBytes = 5
                )
            )
        )

        cases.forEach { case ->
            val root = Files.createTempDirectory("image-bundle-limit").toFile()
            try {
                assertRejected {
                    extractBoundedImageBundleZip(
                        ByteArrayInputStream(zipOf(*case.entries.toTypedArray())),
                        root,
                        case.limits
                    )
                }
                assertTrue(root.listFiles().orEmpty().isEmpty())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `failed extraction removes only files and directories created by that attempt`() {
        val root = Files.createTempDirectory("image-bundle-rollback").toFile()
        val sentinel = File(root, "sentinel.txt").apply { writeText("keep", Charsets.UTF_8) }
        try {
            val source = zipOf(
                ArchiveEntry("nested/good.bin", byteArrayOf(1, 2, 3)),
                ArchiveEntry("nested/too-large.bin", byteArrayOf(4, 5, 6, 7, 8))
            )

            assertRejected {
                extractBoundedImageBundleZip(
                    ByteArrayInputStream(source),
                    root,
                    ImageBundleZipExtractionLimits(
                        maxEntryCount = 4,
                        maxEntryBytes = 4,
                        maxTotalBytes = 16
                    )
                )
            }

            assertEquals("keep", sentinel.readText(Charsets.UTF_8))
            assertFalse(File(root, "nested").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `preexisting targets are rejected without overwriting their bytes`() {
        val root = Files.createTempDirectory("image-bundle-existing-target").toFile()
        val existing = File(root, "model.bin").apply { writeText("original", Charsets.UTF_8) }
        try {
            assertRejected {
                extractBoundedImageBundleZip(
                    ByteArrayInputStream(
                        zipOf(ArchiveEntry("model.bin", "replacement".toByteArray(Charsets.UTF_8)))
                    ),
                    root
                )
            }

            assertEquals("original", existing.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `skipped existing targets are still charged against expanded byte limits`() {
        val root = Files.createTempDirectory("image-bundle-skip-limit").toFile()
        val manifest = File(root, "manifest.json").apply { writeText("original", Charsets.UTF_8) }
        try {
            val source = zipOf(
                ArchiveEntry("manifest.json", byteArrayOf(1, 2, 3, 4, 5))
            )

            assertRejected {
                extractBoundedImageBundleZip(
                    ByteArrayInputStream(source),
                    root,
                    ImageBundleZipExtractionLimits(
                        maxEntryCount = 1,
                        maxEntryBytes = 4,
                        maxTotalBytes = 4
                    ),
                    shouldSkipTarget = { it == manifest.canonicalFile }
                )
            }

            assertEquals("original", manifest.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `free space reserve accepts the exact boundary and rejects one byte less`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val limits = ImageBundleZipExtractionLimits(
            maxEntryCount = 1,
            maxEntryBytes = payload.size.toLong(),
            maxTotalBytes = payload.size.toLong(),
            minFreeSpaceReserveBytes = 64
        )
        val exactRoot = Files.createTempDirectory("image-bundle-space-exact").toFile()
        val shortRoot = Files.createTempDirectory("image-bundle-space-short").toFile()
        try {
            extractBoundedImageBundleZip(
                ByteArrayInputStream(zipOf(ArchiveEntry("model.bin", payload))),
                exactRoot,
                limits,
                usableSpaceProvider = { limits.minFreeSpaceReserveBytes + payload.size }
            )
            assertArrayEquals(payload, File(exactRoot, "model.bin").readBytes())

            assertRejected {
                extractBoundedImageBundleZip(
                    ByteArrayInputStream(zipOf(ArchiveEntry("model.bin", payload))),
                    shortRoot,
                    limits,
                    usableSpaceProvider = {
                        limits.minFreeSpaceReserveBytes + payload.size - 1L
                    }
                )
            }
            assertTrue(shortRoot.listFiles().orEmpty().isEmpty())
        } finally {
            exactRoot.deleteRecursively()
            shortRoot.deleteRecursively()
        }
    }

    @Test
    fun `constant usable space probe cannot reuse the same budget across chunks`() {
        val root = Files.createTempDirectory("image-bundle-space-cumulative").toFile()
        val reserve = 64L
        try {
            assertRejected {
                extractBoundedImageBundleZip(
                    ByteArrayInputStream(
                        zipOf(ArchiveEntry("model.bin", byteArrayOf(1, 2, 3, 4, 5, 6)))
                    ),
                    root,
                    ImageBundleZipExtractionLimits(
                        maxEntryCount = 1,
                        maxEntryBytes = 6,
                        maxTotalBytes = 6,
                        minFreeSpaceReserveBytes = reserve
                    ),
                    usableSpaceProvider = { reserve + 5L },
                    copyBufferSize = 3
                )
            }

            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `skipped targets do not consume the physical storage budget`() {
        val root = Files.createTempDirectory("image-bundle-skip-space").toFile()
        val manifest = File(root, "manifest.json").apply { writeText("original", Charsets.UTF_8) }
        try {
            val result = extractBoundedImageBundleZip(
                ByteArrayInputStream(
                    zipOf(ArchiveEntry("manifest.json", byteArrayOf(1, 2, 3, 4, 5)))
                ),
                root,
                ImageBundleZipExtractionLimits(
                    maxEntryCount = 1,
                    maxEntryBytes = 5,
                    maxTotalBytes = 5,
                    minFreeSpaceReserveBytes = Long.MAX_VALUE
                ),
                shouldSkipTarget = { it == manifest.canonicalFile },
                usableSpaceProvider = { error("Skipped entries must not query physical space.") }
            )

            assertEquals(5L, result.extractedBytes)
            assertTrue(result.extractedFiles.isEmpty())
            assertEquals("original", manifest.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `valid nested archive extracts all files and reports actual counters`() {
        val root = Files.createTempDirectory("image-bundle-valid").toFile()
        try {
            val result = extractBoundedImageBundleZip(
                ByteArrayInputStream(
                    zipOf(
                        ArchiveEntry("models/", directory = true),
                        ArchiveEntry("models/unet.bin", byteArrayOf(1, 2, 3)),
                        ArchiveEntry("tokenizer/vocab.json", byteArrayOf(4, 5))
                    )
                ),
                root,
                ImageBundleZipExtractionLimits(
                    maxEntryCount = 3,
                    maxEntryBytes = 3,
                    maxTotalBytes = 5
                )
            )

            assertEquals(3, result.entryCount)
            assertEquals(5L, result.extractedBytes)
            assertEquals(
                setOf("models/unet.bin", "tokenizer/vocab.json"),
                result.extractedFiles.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
            )
            assertArrayEquals(byteArrayOf(1, 2, 3), File(root, "models/unet.bin").readBytes())
            assertArrayEquals(byteArrayOf(4, 5), File(root, "tokenizer/vocab.json").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertRejected(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private fun zipOf(vararg entries: ArchiveEntry): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { entry ->
                val name = if (entry.directory && !entry.name.endsWith('/')) {
                    "${entry.name}/"
                } else {
                    entry.name
                }
                zip.putNextEntry(ZipEntry(name))
                if (!entry.directory) zip.write(entry.payload)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private data class ArchiveEntry(
        val name: String,
        val payload: ByteArray = byteArrayOf(),
        val directory: Boolean = false
    )

    private data class ZipLimitCase(
        val entries: List<ArchiveEntry>,
        val limits: ImageBundleZipExtractionLimits
    )
}

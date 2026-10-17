package com.muyuchat.mca

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableFileOpsTest {
    @Test
    fun `staged image publishes from child directory and syncs both parents`() {
        val root = Files.createTempDirectory("durable-staged-image").toFile()
        try {
            val staging = root.resolve(".backup-import-qa").apply { mkdirs() }
            val source = staging.resolve("image.part").apply { writeText("verified-image") }
            val target = root.resolve("restored-image.png")
            val synced = mutableListOf<File>()

            durableMoveFromChildDirectory(
                source = source,
                target = target,
                move = { staged, published -> assertTrue(staged.renameTo(published)) },
                parentDirectorySyncer = ParentDirectorySyncer { directory ->
                    assertFalse(source.exists())
                    assertEquals("verified-image", target.readText())
                    synced += directory
                }
            )

            assertEquals(listOf(root.canonicalFile, staging.canonicalFile), synced)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `staged move rejects an unrelated destination before touching files`() {
        val root = Files.createTempDirectory("durable-staged-boundary").toFile()
        try {
            val staging = root.resolve(".backup-import-qa").apply { mkdirs() }
            val unrelated = root.resolve("other").apply { mkdirs() }
            val source = staging.resolve("image.part").apply { writeText("verified-image") }
            val target = unrelated.resolve("image.png")
            var moved = false
            var synced = false

            val failure = runCatching {
                durableMoveFromChildDirectory(
                    source = source,
                    target = target,
                    move = { _, _ -> moved = true },
                    parentDirectorySyncer = ParentDirectorySyncer { synced = true }
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(moved)
            assertFalse(synced)
            assertEquals("verified-image", source.readText())
            assertFalse(target.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `staged directory sync failure leaves the published file visible for rollback`() {
        val root = Files.createTempDirectory("durable-staged-failure").toFile()
        try {
            val staging = root.resolve(".backup-import-qa").apply { mkdirs() }
            val source = staging.resolve("image.part").apply { writeText("verified-image") }
            val target = root.resolve("restored-image.png")
            val synced = mutableListOf<File>()

            val failure = runCatching {
                durableMoveFromChildDirectory(
                    source = source,
                    target = target,
                    move = { staged, published -> assertTrue(staged.renameTo(published)) },
                    parentDirectorySyncer = ParentDirectorySyncer { directory ->
                        synced += directory
                        if (directory == staging.canonicalFile) throw IOException("source fsync failed")
                    }
                )
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(listOf(root.canonicalFile, staging.canonicalFile), synced)
            assertFalse(source.exists())
            assertEquals("verified-image", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `move publishes target before syncing its parent`() {
        val root = Files.createTempDirectory("durable-move").toFile()
        try {
            val source = root.resolve("output.part").apply { writeText("bytes") }
            val target = root.resolve("output.png")
            val events = mutableListOf<String>()

            durableMoveWithinParent(
                source = source,
                target = target,
                move = { staged, published ->
                    events += "move"
                    assertTrue(staged.renameTo(published))
                },
                parentDirectorySyncer = ParentDirectorySyncer { directory ->
                    assertTrue(target.isFile)
                    assertEquals(root.canonicalFile, directory)
                    events += "sync"
                }
            )

            assertEquals(listOf("move", "sync"), events)
            assertFalse(source.exists())
            assertEquals("bytes", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `directory sync failure is reported without deleting the published target`() {
        val root = Files.createTempDirectory("durable-move-failure").toFile()
        try {
            val source = root.resolve("journal.tmp").apply { writeText("complete-json") }
            val target = root.resolve("journal.json")

            val failure = runCatching {
                durableMoveWithinParent(
                    source = source,
                    target = target,
                    move = { staged, published -> assertTrue(staged.renameTo(published)) },
                    parentDirectorySyncer = ParentDirectorySyncer {
                        throw IOException("directory fsync failed")
                    }
                )
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertFalse(source.exists())
            assertTrue(target.isFile)
            assertEquals("complete-json", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}

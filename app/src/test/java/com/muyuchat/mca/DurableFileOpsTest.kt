package com.muyuchat.mca

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableFileOpsTest {
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

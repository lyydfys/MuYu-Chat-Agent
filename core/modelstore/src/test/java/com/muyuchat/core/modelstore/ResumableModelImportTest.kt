package com.muyuchat.core.modelstore

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CancellationException

class ResumableModelImportTest {
    @get:Rule val folder = TemporaryFolder()
    private fun transaction(key: String = "task", cancelled: () -> Unit = {}) = ResumableModelImport(
        folder.root, "same-source-and-mode", key, { File(folder.root, "installed") }, checkCancelled = cancelled)

    @Test fun restartReusesOnlyHashVerifiedCompletedComponents() {
        var opens = 0
        val first = ModelImportSource("uri-a", "子目录/权重.bin", 5, 1) { opens++; ByteArrayInputStream("hello".toByteArray()) }
        val cancelled = ModelImportSource("uri-b", "b", 3, 1) { throw CancellationException() }
        assertThrows(CancellationException::class.java) { transaction().copySources(listOf(first, cancelled)) }
        assertFalse(File(folder.root, "installed").exists())
        val retry = transaction()
        retry.copySources(listOf(first, cancelled.copy(open = { ByteArrayInputStream("new".toByteArray()) })))
        assertEquals(1, opens)
        File(retry.contentRoot, first.path).writeText("xxxxx")
        transaction().copySources(listOf(first))
        assertEquals(2, opens)
    }

    @Test fun unknownSourceVersionIsRecopiedInsteadOfAssumingItDidNotChange() {
        var text = "old"
        val source = ModelImportSource("uri", "weight") { ByteArrayInputStream(text.toByteArray()) }
        transaction().copySources(listOf(source))
        text = "new"
        assertEquals("new", transaction().copySources(listOf(source)).single().readText())
    }

    @Test fun changedSourceMetadataInvalidatesCompletedComponent() {
        val old = ModelImportSource("uri", "weight", 3, 1) { ByteArrayInputStream("old".toByteArray()) }
        transaction().copySources(listOf(old))
        val changed = old.copy(modified = 2, open = { ByteArrayInputStream("new".toByteArray()) })
        assertEquals("new", transaction().copySources(listOf(changed)).single().readText())
    }

    @Test fun copiedButUncommittedFileCannotAppearAsInstalledAfterCancellation() {
        var cancelled = false
        val tx = transaction(cancelled = { if (cancelled) throw CancellationException() })
        val copied = tx.copySources(listOf(ModelImportSource("uri", "weight", 3, 1) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })).single()
        cancelled = true
        assertThrows(CancellationException::class.java) { tx.commit(copied) }
        assertFalse(tx.destination.exists())
        assertTrue(copied.isFile)
    }

    @Test fun committedFileResumesAfterCrashButRejectsChangedDestination() {
        val tx = transaction()
        val copied = tx.copySources(listOf(ModelImportSource("uri", "weight", 3, 1) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })).single()
        tx.commit(copied)
        transaction().requireOwnedDestination()
        tx.destination.writeBytes(byteArrayOf(4, 5, 6))
        assertThrows(IllegalArgumentException::class.java) { transaction().requireOwnedDestination() }
    }

    @Test fun foreignDestinationIsNeverOverwrittenOrRegistered() {
        val tx = transaction()
        tx.destination.writeText("another task")
        assertThrows(IllegalStateException::class.java) { tx.requireOwnedDestination() }
        assertEquals("another task", tx.destination.readText())
    }
}

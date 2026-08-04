package com.muyuchat.feature.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptTagAutocompleteStoreTest {
    @Test
    fun `only content uris without traversal and fixed snapshot names are admitted`() = withTempDirectory { root ->
        assertTrue(isSafeImagePromptTagContentUri("content://dictionary.provider/document/42"))
        assertFalse(isSafeImagePromptTagContentUri("file:///sdcard/tags.csv"))
        assertFalse(isSafeImagePromptTagContentUri("content://dictionary.provider/../tags.csv"))
        assertFalse(isSafeImagePromptTagContentUri("content://dictionary.provider/%2e%2e/tags.csv"))
        assertFalse(isSafeImagePromptTagContentUri("content://dictionary.provider/folder%5ctags.csv"))
        assertFalse(isSafeImagePromptTagContentUri("content:///missing-authority"))

        assertEquals("tags.csv", ImagePromptTagDictionaryKind.TAGS.fileName)
        assertEquals("translations.csv", ImagePromptTagDictionaryKind.TRANSLATIONS.fileName)
        assertEquals(root.canonicalFile, imagePromptTagStoreFile(root, ImagePromptTagDictionaryKind.TAGS).parentFile)
        assertNull(safeImagePromptTagStoreChild(root, "../tags.csv"))
        assertNull(safeImagePromptTagStoreChild(root, "user-selected.csv"))
    }

    @Test
    fun `bounded stream copy accepts exact boundary and rejects one extra byte`() {
        val exact = byteArrayOf(1, 2, 3, 4)
        val exactOutput = ByteArrayOutputStream()
        assertEquals(
            4L,
            copyImagePromptTagStreamBounded(
                ByteArrayInputStream(exact),
                exactOutput,
                maximumBytes = 4
            )
        )
        assertArrayEquals(exact, exactOutput.toByteArray())

        val error = assertThrows(ImagePromptTagStoreValidationException::class.java) {
            copyImagePromptTagStreamBounded(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                ByteArrayOutputStream(),
                maximumBytes = 4
            )
        }
        assertEquals(ImagePromptTagStoreIssue.FILE_TOO_LARGE, error.issue)

        assertThrows(CancellationException::class.java) {
            copyImagePromptTagStreamBounded(
                ByteArrayInputStream(exact),
                ByteArrayOutputStream(),
                maximumBytes = 4,
                checkCancelled = { throw CancellationException("cancelled") }
            )
        }
    }

    @Test
    fun `preparing one final dictionary invokes index construction exactly once`() {
        val tags = listOf(
            ImagePromptTagRecord("red_hair", 0, 10, listOf("scarlet_hair")),
            ImagePromptTagRecord("blue_hair", 0, 5)
        )
        val translations = mapOf("red_hair" to "红发")
        var buildCount = 0

        val prepared = prepareImagePromptTagAutocomplete(
            tags = tags,
            translations = translations,
            indexFactory = { finalTags, finalTranslations, _ ->
                buildCount++
                ImagePromptTagAutocomplete.create(finalTags, finalTranslations)
            }
        )

        assertEquals(1, buildCount)
        assertTrue(prepared.translationsAccepted)
        assertEquals("red_hair", prepared.autocomplete.suggest("红").single().replacementTag)
    }

    @Test
    fun `cancellation during index construction never reaches publication`() = withTempDirectory { root ->
        val target = File(root, "tags.csv").apply { writeText("old-usable") }
        val staged = File(root, ".tags.incoming.tmp").apply { writeText("candidate") }
        val tags = List(4_096) { index ->
            ImagePromptTagRecord("tag_$index", 0, index.toLong())
        }
        var checkpoints = 0
        var publishCalls = 0

        assertThrows(CancellationException::class.java) {
            buildAndCommitImagePromptTagSnapshot(
                staged = staged,
                target = target,
                checkCancelled = {},
                build = {
                    prepareImagePromptTagAutocomplete(
                        tags = tags,
                        translations = emptyMap(),
                        indexFactory = { finalTags, finalTranslations, _ ->
                            ImagePromptTagAutocomplete.create(
                                finalTags,
                                finalTranslations
                            ) {
                                checkpoints++
                                if (checkpoints >= 3) {
                                    throw CancellationException("cancel during index")
                                }
                            }
                        }
                    )
                },
                publish = { source, destination ->
                    publishCalls++
                    replaceImagePromptTagSnapshot(
                        source,
                        destination,
                        syncDirectory = {}
                    )
                }
            )
        }

        assertEquals(0, publishCalls)
        assertEquals("old-usable", target.readText())
        assertFalse(staged.exists())
        assertTrue(root.listFiles().orEmpty().none { "incoming" in it.name || "rollback" in it.name })
    }

    @Test
    fun `cancellation at the publication boundary leaves the old dictionary intact`() = withTempDirectory { root ->
        val target = File(root, "tags.csv").apply { writeText("old-usable") }
        val staged = File(root, ".tags.incoming.tmp").apply { writeText("candidate") }
        var prepared = false
        var publishCalls = 0

        assertThrows(CancellationException::class.java) {
            buildAndCommitImagePromptTagSnapshot(
                staged = staged,
                target = target,
                checkCancelled = {
                    if (prepared) throw CancellationException("cancel before publication")
                },
                build = { "prepared".also { prepared = true } },
                publish = { _, _ -> publishCalls++ }
            )
        }

        assertEquals(0, publishCalls)
        assertEquals("old-usable", target.readText())
        assertFalse(staged.exists())
    }

    @Test
    fun `cancellation after publication returns the committed dictionary result`() = withTempDirectory { root ->
        val target = File(root, "tags.csv").apply { writeText("old-usable") }
        val staged = File(root, ".tags.incoming.tmp").apply { writeText("candidate") }
        val operationJob = AtomicReference<Job?>(null)
        val returned = AtomicReference<String?>(null)

        runBlocking {
            val operation = launch(start = CoroutineStart.LAZY) {
                returned.set(
                    withImagePromptTagCommittedMutation(
                        Dispatchers.Default
                    ) { checkCancelled, markCommitted ->
                        buildAndCommitImagePromptTagSnapshot(
                            staged = staged,
                            target = target,
                            checkCancelled = checkCancelled,
                            build = { "committed" },
                            publish = { source, destination ->
                                replaceImagePromptTagSnapshot(
                                    source,
                                    destination,
                                    syncDirectory = {}
                                )
                                operationJob.get()?.cancel()
                            },
                            onCommitted = markCommitted
                        )
                    }
                )
            }
            operationJob.set(operation)
            operation.start()
            operation.join()
        }

        assertEquals("committed", returned.get())
        assertEquals("candidate", target.readText())
        assertFalse(staged.exists())
    }

    @Test
    fun `virtual machine errors during index construction are never translated to store failures`() {
        val failure = assertThrows(OutOfMemoryError::class.java) {
            prepareImagePromptTagAutocomplete(
                tags = listOf(ImagePromptTagRecord("tag", 0, 1)),
                translations = emptyMap(),
                indexFactory = { _, _, _ -> throw OutOfMemoryError("synthetic memory pressure") }
            )
        }

        assertEquals("synthetic memory pressure", failure.message)
    }

    @Test
    fun `atomic publication replaces old snapshot and removes staging files`() = withTempDirectory { root ->
        val target = File(root, "tags.csv").apply { writeText("old") }
        val staged = File(root, ".tags.incoming.tmp").apply { writeText("new") }

        replaceImagePromptTagSnapshot(
            staged = staged,
            target = target,
            syncDirectory = {}
        )

        assertEquals("new", target.readText())
        assertFalse(staged.exists())
        assertTrue(root.listFiles().orEmpty().none { ".rollback.tmp" in it.name })
    }

    @Test
    fun `unsupported atomic move uses same-directory fallback`() = withTempDirectory { root ->
        val target = File(root, "tags.csv").apply { writeText("old") }
        val staged = File(root, ".tags.incoming.tmp").apply { writeText("new") }
        var fallbackMoves = 0

        replaceImagePromptTagSnapshot(
            staged = staged,
            target = target,
            moveFile = { source, destination, atomic ->
                if (atomic) {
                    throw AtomicMoveNotSupportedException(source.path, destination.path, "test")
                }
                fallbackMoves++
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            },
            syncDirectory = {}
        )

        assertEquals("new", target.readText())
        assertEquals(1, fallbackMoves)
    }

    @Test
    fun `failed fallback restores old usable snapshot and cleans candidate`() = withTempDirectory { root ->
        val target = File(root, "tags.csv").apply { writeText("old-usable") }
        val staged = File(root, ".tags.incoming.tmp").apply { writeText("candidate") }

        assertThrows(IOException::class.java) {
            replaceImagePromptTagSnapshot(
                staged = staged,
                target = target,
                moveFile = { source, destination, atomic ->
                    if (atomic) {
                        throw AtomicMoveNotSupportedException(source.path, destination.path, "test")
                    }
                    if (source.canonicalFile == staged.canonicalFile) {
                        throw IOException("simulated publication failure")
                    }
                    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                },
                syncDirectory = {}
            )
        }

        assertEquals("old-usable", target.readText())
        assertFalse(staged.exists())
        assertTrue(root.listFiles().orEmpty().none { ".rollback.tmp" in it.name })
    }

    @Test
    fun `failed first publication leaves no target`() = withTempDirectory { root ->
        val target = File(root, "tags.csv")
        val staged = File(root, ".tags.incoming.tmp").apply { writeText("candidate") }

        assertThrows(IOException::class.java) {
            replaceImagePromptTagSnapshot(
                staged = staged,
                target = target,
                moveFile = { _, _, atomic ->
                    if (atomic) {
                        throw AtomicMoveNotSupportedException("source", "target", "test")
                    }
                    throw IOException("simulated publication failure")
                },
                syncDirectory = {}
            )
        }

        assertFalse(target.exists())
        assertFalse(staged.exists())
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("mca-tag-store-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

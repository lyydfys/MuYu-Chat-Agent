package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the chat-session write path (P4).
 *
 * The full write semantics (coalescing sequence, run gates, reconcile/publish, world-book
 * cleanup, durable boundary) now live in two named owner functions in
 * `ChatSessionPersistenceOperations.kt`. Three guarded direct `chatSessionStore.save(` sites
 * remain in `MainViewModel` on purpose, when this record was written:
 *
 *  1. startup bootstrap normalization (`runCatching { chatSessionStore.save(snapshots) }`) --
 *     runs during init, before the runtime owners can observe state;
 *  2. and 3. two suspend-IO blocks that hold the persistence mutex and bump the coalescing
 *     sequence themselves, carrying reconcile/publish duties in their surrounding functions.
 *
 * This test is an accounting guard, not a behaviour claim: a new direct site fails here and
 * forces a deliberate decision -- converge it into an owner function, or extend this record
 * with the reason it must stay.
 */
class ChatSessionWritePathContractTest {
    @Test
    fun theDirectSessionSaveSitesAreTheRecordedOnes() {
        assertEquals(
            "A new direct chatSessionStore.save( site needs a deliberate owner decision; update this record's KDoc.",
            mapOf(
                "AssistantProfileOperations.kt" to 1,
                "ChatSessionPersistenceOperations.kt" to 2,
                "MainViewModel.kt" to 2
            ),
            sessionSaveSites()
        )
    }

    @Test
    fun theResidualViewModelSitesKeepTheirExactGuardedShapes() {
        val vm = sourceFile("MainViewModel.kt")
        val assistantOps = sourceFile("AssistantProfileOperations.kt")

        assertEquals(
            1,
            Regex(Regex.escape("runCatching { chatSessionStore.save(snapshots) }")).findAll(vm).count()
        )
        assertEquals(
            1,
            Regex(Regex.escape("chatSessionStore.save(snapshot, bindings)")).findAll(assistantOps).count()
        )
        assertEquals(
            1,
            Regex(Regex.escape("chatSessionStore.save(snapshot.chatSessions, bindings)")).findAll(vm).count()
        )
    }

    private fun sessionSaveSites(): Map<String, Int> {
        val dir = packageDirectory()
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val count = Regex("""chatSessionStore\.save\(""").findAll(file.readText(Charsets.UTF_8)).count()
                if (count > 0) file.name to count else null
            }
            .toMap()
    }

    private fun packageDirectory(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca"),
                File(root, "app/src/main/java/com/muyuchat/mca")
            )
            candidates.firstOrNull(File::isDirectory)?.let { return it }
            directory = root.parentFile
        }
        error("com/muyuchat/mca not found from ${System.getProperty("user.dir")}")
    }

    private fun sourceFile(name: String): String {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/$name"),
                File(root, "app/src/main/java/com/muyuchat/mca/$name")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("$name not found from ${System.getProperty("user.dir")}")
    }
}

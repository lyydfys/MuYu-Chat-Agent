package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parts of the image-library commit chain in `enqueueImageGeneration` that
 * `ImageUpscaleProductClosureTest` does not already read: the serialization order against full
 * snapshot writes, the atomic chat commit plus its durable alignment, the journal confirmation,
 * and the rollback when a cancelled job must not become visible.
 *
 * Written as the behaviour net for the planned P5 extraction of this chain (converge the direct
 * `chatSessionStore` image writers into the named owners); the assertions move with the body when
 * it lands and must not be weakened.
 */
class ChatImageCommitChainContractTest {
    @Test
    fun theCommitChainKeepsSerializationDurableAlignmentAndRollback() {
        val body = functionBody(sourceFile("MainViewModel.kt"), "private fun enqueueImageGeneration(")

        val sequenceBump = body.indexOf("chatSessionPersistenceSequence.incrementAndGet()")
        val persistenceLock = body.indexOf("chatSessionPersistenceMutex.withLock", sequenceBump)
        assertTrue("the image/request transaction must bump the chat persistence sequence", sequenceBump >= 0)
        assertTrue(
            "the sequence bump must happen before taking the persistence mutex",
            persistenceLock > sequenceBump
        )

        assertTrue(body.contains("Image generation lost ownership before library commit."))
        assertTrue(body.contains("chatSessionStore.saveGeneratedImageResult(snapshot.sessions, generatedImages)"))
        assertTrue(body.contains("durableChatSessions = requireNotNull(chatSnapshot).sessions"))
        assertTrue(body.contains("chatSessionStore.upsertImages(generatedImages)"))
        assertTrue(body.contains("durableCommitConfirmed = true"))
        assertTrue(body.contains("chatSessionStore.deleteImages(generatedIds)"))
        assertTrue(body.contains("Image generation was cancelled during library commit."))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
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

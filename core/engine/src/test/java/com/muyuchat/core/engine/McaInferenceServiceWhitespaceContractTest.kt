package com.muyuchat.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract for preserving whitespace-only model deltas. */
class McaInferenceServiceWhitespaceContractTest {
    @Test
    fun generationLoopKeepsWhitespaceChunksAndOnlyRejectsEmptyChunks() {
        val source = sourceFile("core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceService.kt")
        assertTrue(source.contains("if (rawChunk.isEmpty()) continue"))
        assertTrue(source.contains("if (chunk.isEmpty()) {"))
        assertFalse(source.contains("if (rawChunk.isBlank()) continue"))
        assertFalse(source.contains("if (chunk.isBlank()) continue"))
        assertTrue(source.contains("filtered.visible.isNotEmpty()"))
        assertTrue(source.contains("remaining.visible.isNotEmpty()"))
        assertTrue(source.contains("runner.invalidateConversationContext()"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $relativePath")
    }
}

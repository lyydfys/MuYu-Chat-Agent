package com.muyuchat.mca.debug

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatSmokePromptFileTest {
    @Test
    fun readsLargePromptInsideTheAllowedRoot() {
        val root = Files.createTempDirectory("mca-long-prompt").toFile()
        try {
            val prompt = "token ".repeat(16_000)
            val file = File(root, "inputs/long.txt").apply {
                parentFile?.mkdirs()
                writeText(prompt, Charsets.UTF_8)
            }

            assertEquals(prompt, readLocalChatSmokePromptFile(file.absolutePath, root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsPromptOutsideTheAllowedRoot() {
        val root = Files.createTempDirectory("mca-prompt-root").toFile()
        val outside = Files.createTempFile("mca-prompt-outside", ".txt").toFile().apply {
            writeText("secret", Charsets.UTF_8)
        }
        try {
            val failure = runCatching {
                readLocalChatSmokePromptFile(outside.absolutePath, root)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }
}

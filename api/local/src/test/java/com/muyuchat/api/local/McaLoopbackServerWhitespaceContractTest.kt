package com.muyuchat.api.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract for lossless Local API chat streaming/aggregation. */
class McaLoopbackServerWhitespaceContractTest {
    @Test
    fun sseAndJsonAggregationDoNotDropWhitespaceOnlyDeltas() {
        val source = sourceFile("api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServer.kt")
        assertTrue(source.contains("if (event.text.isNotEmpty())"))
        assertTrue(source.contains("if (event.reasoning.isNotEmpty())"))
        assertFalse(source.contains("if (event.text.isNotBlank())"))
        assertFalse(source.contains("if (event.reasoning.isNotBlank())"))
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

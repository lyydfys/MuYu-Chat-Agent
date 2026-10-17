package com.muyuchat.api.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract for lossless Local API chat streaming/aggregation. */
class McaLoopbackServerWhitespaceContractTest {
    @Test
    fun sseAndJsonAggregationDoNotDropWhitespaceOnlyDeltas() {
        val source = ROUTER_SOURCE_FILES.joinToString("\n") { sourceFile(it) }
        assertTrue(source.contains("if (event.text.isNotEmpty())"))
        assertTrue(source.contains("if (event.reasoning.isNotEmpty())"))
        assertFalse(source.contains("if (event.text.isNotBlank())"))
        assertFalse(source.contains("if (event.reasoning.isNotBlank())"))
    }

    private companion object {
        // T2B8/R7-B3: the whitespace aggregation contract moved to the chat SSE successor;
        // the read is the union of the loopback router files, assertions unchanged.
        val ROUTER_SOURCE_FILES = listOf(
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServer.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerHttpTransport.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerChatSse.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerResponsesSse.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerImageGeneration.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerTuningControl.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerProtocolCodec.kt"
        )
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

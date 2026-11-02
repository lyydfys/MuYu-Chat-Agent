package com.muyuchat.mca.agent.extensions.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSseParserTest {
    @Test
    fun emitsOneEventAcrossChunkBoundary() {
        val events = ArrayList<String>()
        val parser = McpSseParser()
        parser.feed("data: {\"jsonrpc\":", events::add)
        parser.feed("\"2.0\",\"id\":1}\n\n", events::add)
        assertTrue(parser.finish() == null)
        assertEquals(listOf("""{"jsonrpc":"2.0","id":1}"""), events)
    }

    @Test
    fun handlesCrLfSplitAcrossChunks() {
        val events = ArrayList<String>()
        val parser = McpSseParser()
        parser.feed("data: a\r", events::add)
        parser.feed("\ndata: b\r\n\r\n", events::add)
        assertTrue(parser.finish() == null)
        assertEquals(listOf("a\nb"), events)
    }

    @Test
    fun ignoresCommentsAndNonDataFields() {
        val events = ArrayList<String>()
        val parser = McpSseParser()
        parser.feed(": keepalive\nevent: message\nid: 7\ndata: payload\n\n", events::add)
        assertTrue(parser.finish() == null)
        assertEquals(listOf("payload"), events)
    }

    @Test
    fun discardsTruncatedFinalEventAndReportsIt() {
        val events = ArrayList<String>()
        val parser = McpSseParser()
        parser.feed("data: partial", events::add)
        assertNotNull(parser.finish())
        assertTrue(events.isEmpty())
    }

    @Test
    fun enforcesPerEventLimit() {
        val events = ArrayList<String>()
        val parser = McpSseParser(maxEventBytes = 16, maxTotalBytes = 1024)
        parser.feed("data: " + "x".repeat(64), events::add)
        assertTrue(parser.finish() is McpFailure.SizeLimit)
        assertTrue(events.isEmpty())
    }

    @Test
    fun enforcesTotalLimit() {
        val events = ArrayList<String>()
        val parser = McpSseParser(maxEventBytes = 64, maxTotalBytes = 32)
        parser.feed("data: abcdefghij\n\n", events::add)
        parser.feed("data: abcdefghij\n\n", events::add)
        parser.feed("data: abcdefghij\n\n", events::add)
        assertTrue(events.size <= 1)
    }
}

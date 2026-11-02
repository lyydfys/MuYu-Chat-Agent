package com.muyuchat.mca.agent.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeIsolationTest {

    private val status = listOf(
        "Name:\tcom.muyuchat.mca",
        "Uid:\t10346\t10346\t10346\t10346",
        "VmRSS:\t  412345 kB",
        "Threads:\t57"
    )
    private val tcp = listOf(
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
        "   0: 0100007F:2CAF 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10346 0 12345 1",
        "   1: 0100007F:9C40 0100007F:2CAF 01 00000000:00000000 00:00000000 00000000 10346 0 12346 1",
        ""
    )

    @Test
    fun readsUidDescriptorSocketAndMemoryFacts() {
        val snapshot = readAgentRuntimeIsolation(
            files = { path -> when (path) { "/proc/self/status" -> status; "/proc/self/net/tcp" -> tcp; else -> null } },
            directories = { path -> if (path == "/proc/self/fd") listOf("0", "1", "2", "7") else null }
        )
        assertEquals(10346, snapshot.uid)
        assertEquals(4, snapshot.openFileDescriptors)
        assertEquals(2, snapshot.tcpSockets)
        assertEquals(412345, snapshot.residentKb)
        assertEquals("uid=10346 fds=4 tcp=2 rssKb=412345", snapshot.describe())
    }

    @Test
    fun anUnreadableProcEntryIsUnknownNotZero() {
        val snapshot = readAgentRuntimeIsolation(
            files = { null },
            directories = { null }
        )
        assertNull(snapshot.uid)
        assertNull(snapshot.openFileDescriptors)
        assertNull(snapshot.tcpSockets)
        assertNull(snapshot.residentKb)
        assertEquals("uid=unknown fds=unknown tcp=unknown rssKb=unknown", snapshot.describe())
    }

    @Test
    fun headerAndBlankLinesAreNotCountedAsSockets() {
        val snapshot = readAgentRuntimeIsolation(
            files = { path -> if (path == "/proc/self/net/tcp") tcp.take(1) + listOf("", "   ") else null },
            directories = { null }
        )
        assertEquals(0, snapshot.tcpSockets)
        assertTrue(snapshot.describe().contains("fds=unknown"))
    }
}

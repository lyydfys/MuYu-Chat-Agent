package com.muyuchat.mca.runtime

import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the persisted runtime settings and the real loopback port probe.
 *
 * The probe is exercised against real loopback sockets (no device needed): a held listener must read as
 * unavailable, a freed ephemeral port as available, so the fallback ladder is proven against the same
 * code the runtime uses.
 */
class McaRuntimeSettingsTest {

    @Test
    fun `an empty store yields the defaults`() {
        val settings = McaRuntimeSettingsCodec.read(FakeStore())

        assertEquals(McaRuntimeSettings.DEFAULT_WEB_PORT, settings.webPort)
        assertEquals(FrameRatePreference.FOLLOW_SYSTEM, settings.frameRate)
    }

    @Test
    fun `settings round trip through the store`() {
        val store = FakeStore()

        assertNull(McaRuntimeSettingsCodec.write(store, webPort = 14_321, frameRate = FrameRatePreference.HZ_120))

        val settings = McaRuntimeSettingsCodec.read(store)
        assertEquals(14_321, settings.webPort)
        assertEquals(FrameRatePreference.HZ_120, settings.frameRate)
        assertEquals("14321", store.read(McaRuntimeSettingsCodec.KEY_WEB_PORT))
        assertEquals("HZ_120", store.read(McaRuntimeSettingsCodec.KEY_FRAME_RATE))
    }

    @Test
    fun `an invalid port is refused on write and never persisted`() {
        val store = FakeStore()

        listOf(80, 1023, 65_536, -1).forEach { port ->
            val reason = McaRuntimeSettingsCodec.write(store, port, FrameRatePreference.FOLLOW_SYSTEM)
            assertTrue("port=$port", reason != null)
            assertTrue(reason!!.contains("1024–65535"))
        }
        assertNull("nothing invalid reached the store", store.read(McaRuntimeSettingsCodec.KEY_WEB_PORT))
        // 0 is the documented "let the runtime pick" value and must stay writable.
        assertNull(McaRuntimeSettingsCodec.write(store, 0, FrameRatePreference.HZ_60))
        assertEquals("0", store.read(McaRuntimeSettingsCodec.KEY_WEB_PORT))
    }

    @Test
    fun `a corrupted stored port falls back instead of failing`() {
        val store = FakeStore().apply { write(McaRuntimeSettingsCodec.KEY_WEB_PORT, "not-a-port") }

        val settings = McaRuntimeSettingsCodec.read(store)

        assertEquals(McaRuntimeSettings.DEFAULT_WEB_PORT, settings.webPort)
        assertEquals(1024, McaRuntimeSettingsCodec.parseWebPort("1024"))
        assertNull(McaRuntimeSettingsCodec.parseWebPort("99999"))
        assertEquals(FrameRatePreference.FOLLOW_SYSTEM, McaRuntimeSettingsCodec.parseFrameRate("144"))
    }

    @Test
    fun `a held loopback port reads as unavailable and a freed one as available`() {
        val held = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val released = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val releasedPort = released.localPort
        released.close()
        try {
            val probe = McaRuntimePortProbe()
            assertFalse("a bound listener must never be offered", probe.isAvailable(held.localPort))
            assertTrue("a freed ephemeral port must be offered", probe.isAvailable(releasedPort))
            assertTrue("0 means 'runtime picks', which is always acceptable", probe.isAvailable(0))
            assertFalse("out of range is never available", probe.isAvailable(80))
        } finally {
            runCatching { held.close() }
        }
    }

    @Test
    fun `the probe feeds the fallback planner with its real answer`() {
        val occupied = setOf(15_000)

        val probe = McaRuntimePortProbe(bind = { port, _ -> port !in occupied })
        val choice = probe.choose(15_000) as RuntimePortChoice.Chosen

        assertEquals(15_001, choice.port)
        assertTrue(choice.fellBack)
        assertEquals("原端口 15000 被占用，已回退到 15001", choice.fallbackNotice)
    }

    @Test
    fun `a throwing bind is treated as unavailable instead of crashing`() {
        val probe = McaRuntimePortProbe(bind = { _, _ -> error("bind blew up") })

        assertFalse(probe.isAvailable(16_000))
        assertTrue(probe.choose(16_000) is RuntimePortChoice.Rejected)
    }

    @Test
    fun `the tcp probe reports a live listener and a dead one`() {
        val (port, close) = McaRuntimeTcpProbe.startLoopbackListener()
        try {
            assertTrue(McaRuntimeTcpProbe().isReachable(port, timeoutMs = 1_000))
            assertFalse("an unused high port must not look alive", McaRuntimeTcpProbe().isReachable(1, timeoutMs = 200))
        } finally {
            close()
        }
    }

    private class FakeStore : McaRuntimeSettingsStore {
        private val values = LinkedHashMap<String, String?>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String?) {
            values[key] = value
        }
    }
}

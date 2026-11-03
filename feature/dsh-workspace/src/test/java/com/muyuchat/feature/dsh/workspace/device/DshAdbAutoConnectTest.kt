package com.muyuchat.feature.dsh.workspace.device

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Auto-reconnect policy: the delays, the attempt budget and the cases where retrying is wrong.
 */
class DshAdbAutoConnectTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `the backoff sequence is one two four eight eight seconds`() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L),
            DshAdbAutoConnectPolicy.BACKOFF_MILLIS
        )
        assertEquals(1_000L, DshAdbAutoConnectPolicy.backoffMillis(0))
        assertEquals(2_000L, DshAdbAutoConnectPolicy.backoffMillis(1))
        assertEquals(4_000L, DshAdbAutoConnectPolicy.backoffMillis(2))
        assertEquals(8_000L, DshAdbAutoConnectPolicy.backoffMillis(3))
        assertEquals(8_000L, DshAdbAutoConnectPolicy.backoffMillis(4))
        assertEquals("values beyond the table keep the last delay", 8_000L, DshAdbAutoConnectPolicy.backoffMillis(99))
    }

    @Test
    fun `five failures walk the whole backoff and then stop`() {
        var state = DshAdbAutoConnectState(enabled = true)
        val delays = mutableListOf<Long>()
        repeat(DshAdbAutoConnectPolicy.MAX_ATTEMPTS) {
            val decision = dshAdbRetryDecision(state, ENDPOINT, awaitingPairing = false)
            assertTrue("attempt $it must be a retry", decision is DshAdbRetryDecision.Retry)
            delays += (decision as DshAdbRetryDecision.Retry).delayMillis
            state = dshAdbAfterFailure(state, DshDeviceCommandFailure.CONNECT_FAILED)
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), delays)
        assertTrue(state.exhausted)
        assertEquals(
            DshAdbRetryDecision.Stop(DshAdbRetryBlock.EXHAUSTED),
            dshAdbRetryDecision(state, ENDPOINT, awaitingPairing = false)
        )
    }

    @Test
    fun `a successful connection resets the counter`() {
        var state = DshAdbAutoConnectState(enabled = true)
        repeat(3) { state = dshAdbAfterFailure(state, DshDeviceCommandFailure.TIMEOUT) }
        assertEquals(3, state.attempts)

        state = dshAdbAfterSuccess(state)

        assertEquals(0, state.attempts)
        assertNull(state.lastFailure)
        assertEquals(
            DshAdbRetryDecision.Retry(attempt = 0, delayMillis = 1_000L),
            dshAdbRetryDecision(state, ENDPOINT, awaitingPairing = false)
        )
    }

    @Test
    fun `a manual disconnect blocks automatic retries`() {
        val state = dshAdbAfterManualDisconnect(DshAdbAutoConnectState(enabled = true))

        assertTrue(state.manualDisconnect)
        assertEquals(
            DshAdbRetryDecision.Stop(DshAdbRetryBlock.MANUAL_DISCONNECT),
            dshAdbRetryDecision(state, ENDPOINT, awaitingPairing = false)
        )
    }

    @Test
    fun `turning the switch back on clears the manual disconnect`() {
        val disconnected = dshAdbAfterManualDisconnect(DshAdbAutoConnectState(enabled = true))

        val enabledAgain = dshAdbAfterEnable(disconnected, enabled = true)

        assertTrue(enabledAgain.enabled)
        assertEquals(false, enabledAgain.manualDisconnect)
        assertEquals(0, enabledAgain.attempts)
        assertTrue(dshAdbRetryDecision(enabledAgain, ENDPOINT, false) is DshAdbRetryDecision.Retry)
    }

    @Test
    fun `no endpoint means no retry`() {
        val state = DshAdbAutoConnectState(enabled = true)

        assertEquals(
            DshAdbRetryDecision.Stop(DshAdbRetryBlock.NO_ENDPOINT),
            dshAdbRetryDecision(state, endpoint = null, awaitingPairing = false)
        )
        assertEquals(
            DshAdbRetryDecision.Stop(DshAdbRetryBlock.NO_ENDPOINT),
            dshAdbRetryDecision(state, endpoint = "   ", awaitingPairing = false)
        )
    }

    @Test
    fun `waiting for key authorisation is not retried`() {
        val state = DshAdbAutoConnectState(enabled = true)

        assertEquals(
            DshAdbRetryDecision.Stop(DshAdbRetryBlock.AWAITING_PAIRING),
            dshAdbRetryDecision(state, ENDPOINT, awaitingPairing = true)
        )
    }

    @Test
    fun `a disabled switch and an already connected link stop the loop`() {
        val disabled = DshAdbAutoConnectState(enabled = false)
        assertEquals(
            DshAdbRetryDecision.Stop(DshAdbRetryBlock.DISABLED),
            dshAdbRetryDecision(disabled, ENDPOINT, awaitingPairing = false)
        )
        assertEquals(
            DshAdbRetryDecision.Stop(DshAdbRetryBlock.ALREADY_CONNECTED),
            dshAdbRetryDecision(DshAdbAutoConnectState(enabled = true), ENDPOINT, false, connected = true)
        )
    }

    @Test
    fun `a failure records the attempt and the reason`() {
        val state = dshAdbAfterFailure(
            DshAdbAutoConnectState(enabled = true),
            DshDeviceCommandFailure.HANDSHAKE_FAILED
        )

        assertEquals(1, state.attempts)
        assertEquals(DshDeviceCommandFailure.HANDSHAKE_FAILED, state.lastFailure)
    }

    @Test
    fun `the endpoint store remembers and clears the last endpoint`() {
        val file = File(temporaryFolder.newFolder("store"), "adb_endpoint")
        val store = FileDshAdbEndpointStore(file)

        assertNull(store.savedEndpoint())

        store.saveEndpoint("192.168.1.7:5037")
        assertEquals("192.168.1.7:5037", store.savedEndpoint())

        store.clearEndpoint()
        assertNull(store.savedEndpoint())
    }

    private companion object {
        const val ENDPOINT = "127.0.0.1:5555"
    }
}

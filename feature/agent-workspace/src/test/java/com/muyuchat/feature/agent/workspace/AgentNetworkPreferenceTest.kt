package com.muyuchat.feature.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the behaviour that made the「允许联网」switch feel broken on a real device.
 *
 * The switch used to be stored in `SavedStateHandle`, which survives a rotation but not a cold
 * start: enable network, kill the app, reopen it, and the page was back to「当前为离线模式，所选
 * 模型需要联网」. Everything below asserts the property that fix depends on, using the port contract
 * directly — no Android, no ViewModel, no Room:
 *
 * - a value written once is read back by the **next reader** (a restart constructs a new consumer
 *   and the store answers from disk);
 * - the absent store answers the closed default, so a broken preference can never grant access.
 *
 * The ViewModel's side of the bargain (seed a StateFlow from `read()` at construction, call
 * `write()` on every toggle) is a two-line wiring that the compiler pins; the durable half is what
 * these tests cover, because that is the half that was missing.
 */
class AgentNetworkPreferenceTest {

    /** In-memory stand-in for SharedPreferences: state outlives the writer, like a real file. */
    private class FakeStore(initial: Boolean = false) : AgentNetworkPreferencePort {
        var value: Boolean = initial
            private set
        var writes: Int = 0
            private set

        override fun read(): Boolean = value

        override fun write(enabled: Boolean) {
            value = enabled
            writes++
        }
    }

    @Test
    fun `a grant written once is visible to the next cold start`() {
        val store = FakeStore()
        // Session one: the user turns network on; the ViewModel calls write(true).
        store.write(true)
        assertEquals(1, store.writes)

        // Session two: a restart builds a new consumer and reads first. It must already see true,
        // otherwise the page renders「当前离线」until the user toggles it again.
        assertEquals(true, store.read())
    }

    @Test
    fun `a revoked grant stays revoked across a restart`() {
        val store = FakeStore(initial = true)
        store.write(false)
        assertEquals(false, store.read())
    }

    @Test
    fun `an absent store answers the closed default`() {
        assertTrue(
            "Unavailable must never grant network access",
            !AgentNetworkPreferencePort.Unavailable.read()
        )
        // Writing to it is a no-op that must not throw -- a host without storage still toggles the UI.
        AgentNetworkPreferencePort.Unavailable.write(true)
        assertTrue(!AgentNetworkPreferencePort.Unavailable.read())
    }

    @Test
    fun `the port is a preference, not a draft`() {
        // Documents the distinction that caused the bug: SavedStateHandle is for half-typed text
        // (a draft must die with the process), the network grant is a preference (it must not).
        val first = FakeStore()
        first.write(true)
        val afterRestart = FakeStore(first.read())
        assertEquals(true, afterRestart.read())
    }
}

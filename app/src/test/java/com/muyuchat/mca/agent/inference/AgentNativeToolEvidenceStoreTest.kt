package com.muyuchat.mca.agent.inference

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N05d: NATIVE is negotiated by recorded real-round evidence, not a user toggle. */
class AgentNativeToolEvidenceStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = AgentNativeToolEvidenceStore(folder.root)

    @Test
    fun `an empty store verifies nothing`() {
        assertFalse(store().isVerified("cloud:r1", "gpt-test"))
    }

    @Test
    fun `a recorded round verifies only that provider and model`() {
        val store = store()
        store.recordVerified("cloud:r1", "gpt-test", "n05d-2026-10-21", 1_000L)

        assertTrue(store.isVerified("cloud:r1", "gpt-test"))
        assertTrue("a second store instance reads the same durable file", store().isVerified("cloud:r1", "gpt-test"))
        assertFalse(store.isVerified("cloud:r2", "gpt-test"))
        assertFalse(store.isVerified("cloud:r1", "other-model"))
    }

    @Test
    fun `a corrupt evidence file fails closed`() {
        val store = store()
        store.recordVerified("cloud:r1", "gpt-test", "n05d-2026-10-21", 1_000L)
        File(folder.root, "native-tools/evidence.json").writeText("{broken")

        assertFalse(store.isVerified("cloud:r1", "gpt-test"))
    }

    @Test
    fun `blank identifiers are rejected instead of poisoning the store`() {
        val store = store()
        assertTrue(runCatching { store.recordVerified(" ", "gpt-test", "v1", 1_000L) }.isFailure)
        assertTrue(runCatching { store.recordVerified("cloud:r1", " ", "v1", 1_000L) }.isFailure)
    }
}

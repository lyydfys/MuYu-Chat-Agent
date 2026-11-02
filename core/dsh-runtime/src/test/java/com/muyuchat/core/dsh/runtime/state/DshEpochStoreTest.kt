package com.muyuchat.core.dsh.runtime.state

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DshEpochStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `absent file means no history`() {
        val store = DshEpochStore(temporaryFolder.root.resolve("runtime-epoch.json"))
        assertEquals(0L, store.lastEpoch())
    }

    @Test
    fun `recorded epoch survives a new store instance`() {
        val file = temporaryFolder.root.resolve("runtime-epoch.json")
        DshEpochStore(file).record(7L)
        assertEquals(7L, DshEpochStore(file).lastEpoch())
    }

    @Test
    fun `malformed and negative state never raises`() {
        val file = temporaryFolder.root.resolve("runtime-epoch.json")
        file.writeText("not-a-number")
        assertEquals(0L, DshEpochStore(file).lastEpoch())
        file.writeText("-4")
        assertEquals(0L, DshEpochStore(file).lastEpoch())
        DshEpochStore(file).record(-1L)
        assertEquals(0L, DshEpochStore(file).lastEpoch())
    }

    @Test
    fun `later record advances the high-water mark`() {
        val file = temporaryFolder.root.resolve("runtime-epoch.json")
        val store = DshEpochStore(file)
        store.record(3L)
        store.record(4L)
        assertEquals(4L, store.lastEpoch())
    }
}

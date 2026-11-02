package com.muyuchat.mca

import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DSH ball model panel projection: it must show exactly MCA's downloaded local models with
 * the same loaded id and loading stage as the chat screen, and its only write intent must be the
 * production select owner. It must never invent a loaded state.
 */
class DshModelSwitchAdapterTest {

    @Test
    fun mapsTheProductionModelStateAndForwardsOnlySelection() = runTest(UnconfinedTestDispatcher()) {
        val ui = MutableStateFlow(
            MainUiState(
                models = listOf(model("alpha"), model("beta")),
                loadedModelId = "alpha"
            )
        )
        val selected = mutableListOf<String>()
        val adapter = DshModelSwitchAdapter(ui, { selected += it }, backgroundScope)

        val initial = adapter.state.value
        assertEquals(listOf("alpha", "beta"), initial.choices.map { it.modelId })
        assertEquals("Alpha", initial.choices[0].label)
        assertEquals("Q4_K_M · 2.3 GB", initial.choices[0].detail)
        assertTrue(initial.choices[0].loaded)
        assertFalse(initial.choices[1].loaded)
        assertEquals("alpha", initial.activeModelId)
        assertFalse(initial.busy)

        adapter.select("beta")
        assertEquals(listOf("beta"), selected)

        ui.value = ui.value.copy(loadedModelId = "beta", modelLoadStage = "正在加载 beta")
        val switched = adapter.state.value
        assertFalse(switched.choices[0].loaded)
        assertTrue(switched.choices[1].loaded)
        assertEquals("beta", switched.activeModelId)
        assertTrue(switched.busy)
        assertEquals("正在加载 beta", switched.status)
    }

    private fun model(id: String): ModelManifest = ModelManifest(
        id = id,
        displayName = id.replaceFirstChar { it.uppercase() },
        path = "/models/$id.gguf",
        runtime = ChatModelRuntime.LLAMA_CPP,
        source = ModelSource.LOCAL,
        fileName = "$id.gguf",
        sizeBytes = 2_330_000_000L,
        sha256 = "0".repeat(64),
        quant = "Q4_K_M",
        createdAt = 1L
    )
}

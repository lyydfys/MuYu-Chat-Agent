package com.muyuchat.mca.presentation

import com.muyuchat.mca.AppTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiIntentEffectTest {

    @Test
    fun `MainUiIntent carries typed payload invariants`() {
        val sendIntent = MainUiIntent.SendChatMessage(prompt = "Hello AI", sessionId = "sess-1")
        assertEquals("Hello AI", sendIntent.prompt)
        assertEquals("sess-1", sendIntent.sessionId)

        val cancelIntent = MainUiIntent.CancelGeneration(requestId = "req-1", reason = "Timeout")
        assertEquals("req-1", cancelIntent.requestId)
        assertEquals("Timeout", cancelIntent.reason)

        val imgIntent = MainUiIntent.StartImageGeneration(prompt = "Futuristic city", width = 1024, height = 768)
        assertEquals("Futuristic city", imgIntent.prompt)
        assertEquals(1024, imgIntent.width)
        assertEquals(768, imgIntent.height)

        val tabIntent = MainUiIntent.SelectTab(AppTab.API)
        assertEquals(AppTab.API, tabIntent.tab)

        val modelIntent = MainUiIntent.SelectModel(modelId = "qwen2.5-7b")
        assertEquals("qwen2.5-7b", modelIntent.modelId)
    }

    @Test
    fun `MainUiEffect encapsulates ephemeral presentation events`() {
        val toast = MainUiEffect.ShowToast(message = "Operation succeeded")
        assertEquals("Operation succeeded", toast.message)

        val snackbar = MainUiEffect.ShowSnackbar(message = "Network error", actionLabel = "Retry")
        assertEquals("Network error", snackbar.message)
        assertEquals("Retry", snackbar.actionLabel)

        val nav = MainUiEffect.NavigateToTab(AppTab.MODELS)
        assertEquals(AppTab.MODELS, nav.tab)

        val export = MainUiEffect.ExportComplete(filePath = "/sdcard/export.md", mimeType = "text/markdown")
        assertEquals("/sdcard/export.md", export.filePath)
        assertEquals("text/markdown", export.mimeType)

        val error = MainUiEffect.ErrorNotification(errorCode = "VAL_INVALID_INPUT", message = "Bad prompt")
        assertEquals("VAL_INVALID_INPUT", error.errorCode)
        assertEquals("Bad prompt", error.message)
    }
}

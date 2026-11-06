package com.muyuchat.mca

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime runtime for long running generations.
 *
 * Chat generation used to run on `viewModelScope`, so leaving the chat page (the character-card
 * screen, a tab switch, an Activity recreation) cancelled it mid-stream — the "切后台就断了" report.
 * A generation is a background task owned by the app, not by whichever screen happens to be
 * visible, so it runs here instead.
 *
 * Cancellation still happens on purpose only: the stop button, a new generation superseding the
 * old one, or process death. The [SupervisorJob] keeps one failed generation from killing the
 * others.
 */
internal object McaGenerationRuntime {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

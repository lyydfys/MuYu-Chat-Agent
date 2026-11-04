package com.muyuchat.mca

/**
 * Rich-block display switch: the one user-facing control for the safe rich-text renderer.
 *
 * The switch is display-only. It never changes message persistence, and turning it off simply
 * restores the previous Markdown rendering. Writes go through the existing reviewed chat tool
 * settings authority so the field stays inside the D01/D02/D03 whitelist; the preference is
 * persisted after the state write succeeds.
 */
internal fun MainViewModel.setRichBlocksEnabled(enabled: Boolean) {
    if (uiStateStore.current.richBlocksEnabled == enabled) return
    GenerationPreferences.persistRichBlocksEnabled(getApplication(), enabled)
    chatToolSettingsWriter.update {
        it.copy(
            richBlocksEnabled = enabled,
            statusMessage = if (enabled) "已开启富文本块显示" else "已关闭富文本块显示",
        )
    }
}

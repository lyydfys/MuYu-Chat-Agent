package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.mca.presentation.settingsUiState
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 富文本块开关的端到端接线合同（设置 → MainUiState → 字段权威 → SettingsUiState / ChatUiState）。
 *
 * 纯 JVM：状态投影与字段权威可在离线断言；Compose 设置卡与开关只做源码锚点，真机视觉另行验证。
 */
class RichBlocksSettingWiringTest {

    @Test
    fun `the display flag defaults on in state`() {
        assertTrue(MainUiState().richBlocksEnabled)
        assertTrue(MainUiState().toChatToolSettingsFields().richBlocksEnabled)
    }

    @Test
    fun `the fields authority writes and merges only the display flag`() {
        val off = MainUiState(richBlocksEnabled = false)
        val fields = off.toChatToolSettingsFields()

        assertFalse(fields.richBlocksEnabled)
        val merged = fields.mergeInto(off.copy(richBlocksEnabled = true))
        assertFalse(merged.richBlocksEnabled)
        // 未授权字段保持快照值，不会被 fields 对象意外改写。
        assertTrue(merged.chatLocalToolsEnabled)
    }

    @Test
    fun `chat projection forwards the switch into ChatUiState`() {
        assertFalse(projectChat(MainUiState(richBlocksEnabled = false)).richBlocksEnabled)
        assertTrue(projectChat(MainUiState(richBlocksEnabled = true)).richBlocksEnabled)
    }

    @Test
    fun `settings projection forwards the switch into SettingsUiState`() {
        assertFalse(MainUiState(richBlocksEnabled = false).settingsUiState().richBlocksEnabled)
        assertTrue(MainUiState(richBlocksEnabled = true).settingsUiState().richBlocksEnabled)
    }

    @Test
    fun `the settings card and its callback chain are wired`() {
        val card = repoFile(
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsLogsPrivacyScreens.kt"
        ).readText()
        assertTrue(card.contains("RichBlocksCard("))
        assertTrue(card.contains("富文本块显示"))
        assertTrue(card.contains("脚本与样式永不执行"))
        assertTrue(card.contains("查看原文"))

        val hub = repoFile("feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt").readText()
        assertTrue(hub.contains("onRichBlocksEnabledChanged: (Boolean) -> Unit"))
        assertTrue(hub.contains("onRichBlocksEnabledChanged = onRichBlocksEnabledChanged"))

        val activity = repoFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt").readText()
        assertTrue(
            activity.contains(
                "onRichBlocksEnabledChanged = { enabled -> viewModel.setRichBlocksEnabled(enabled) }"
            )
        )
    }

    @Test
    fun `the view model delegate persists and writes through the reviewed authority`() {
        val delegate = repoFile("app/src/main/java/com/muyuchat/mca/MainViewModelChatDisplayDelegates.kt").readText()
        assertTrue(delegate.contains("fun MainViewModel.setRichBlocksEnabled(enabled: Boolean)"))
        assertTrue(delegate.contains("uiStateStore.current.richBlocksEnabled == enabled"))
        assertTrue(delegate.contains("GenerationPreferences.persistRichBlocksEnabled(getApplication(), enabled)"))
        assertTrue(delegate.contains("chatToolSettingsWriter.update"))
        assertTrue(delegate.contains("richBlocksEnabled = enabled"))

        val fields = repoFile("app/src/main/java/com/muyuchat/mca/ChatToolSettingsFields.kt").readText()
        assertTrue(fields.contains("val richBlocksEnabled: Boolean = snapshot.richBlocksEnabled"))
        assertTrue(fields.contains("richBlocksEnabled = richBlocksEnabled"))
    }

    @Test
    fun `bootstrap reads the persisted preference instead of a hard coded value`() {
        val bootstrap = repoFile("app/src/main/java/com/muyuchat/mca/MainUiBootstrapFactory.kt").readText()
        assertTrue(bootstrap.contains("GenerationPreferences.loadRichBlocksEnabled(application)"))
        assertTrue(bootstrap.contains("richBlocksEnabled = initialRichBlocksEnabled"))
    }

    private fun projectChat(state: MainUiState) = buildChatUiState(
        state = state,
        sessionPresentations = emptyMap(),
        timestampLocale = java.util.Locale.US,
        imageGenerationHistoryById = emptyMap(),
        assistantImageToolPermission = ChatImageToolPermissionUiState(
            available = false,
            unavailableReason = "",
            autoApproval = false,
            canChange = false
        ),        preparedImageUi = emptyMap(),
        generationParamsForChatModel = { GenerationParams() }
    )

    private fun repoFile(relative: String): File {
        var root: File? = File(System.getProperty("user.dir")).absoluteFile
        while (root != null) {
            val candidate = File(root, relative)
            if (candidate.isFile) return candidate
            root = root.parentFile
        }
        error("repo file not found: $relative from ${System.getProperty("user.dir")}")
    }
}

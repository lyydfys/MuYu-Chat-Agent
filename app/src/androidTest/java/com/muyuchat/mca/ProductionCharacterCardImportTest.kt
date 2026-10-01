package com.muyuchat.mca

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses production MainActivity, its ViewModel, ContentResolver, preview and Room store.
 * Only the external system document picker is substituted with an ActivityMonitor result.
 * This test proves context wiring/persistence, not a model's actual generated answer.
 */
@RunWith(AndroidJUnit4::class)
class ProductionCharacterCardImportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun pngPreviewCancelImportEditAndRecreatePreservePersona() {
        compose.waitUntil(45_000L) {
            compose.onNodeWithTag("chat.editor").runCatching { fetchSemanticsNode() }.isSuccess
        }
        val original = model().uiState.value
        assumeTrue("Do not interrupt a user's pending task or import", !original.isGenerating &&
            original.imageJobs.none { !it.status.terminal } && original.contentImportPreview == null)
        val originalSessionId = original.activeChatSessionId
        assumeTrue("An existing session is required for nondestructive restoration", originalSessionId != null)
        val name = "PNG回归旅人-${UUID.randomUUID().toString().take(8)}"
        val greeting = "你终于回来了，茶还温着。"
        val boilerplate = GenerationParams().systemPrompt
        val raw = JSONObject().put("spec", "chara_card_v2").put("spec_version", "2.0")
            .put("data", JSONObject().put("name", name).put("system_prompt", boilerplate)
                .put("description", "你是$name，记得三年前一起走过的海边。")
                .put("personality", "温柔，回答时保持角色身份。")
                .put("scenario", "傍晚的海边小屋。")
                .put("first_mes", greeting)
                .put("post_history_instructions", "不要把自己称作 MCA 离线助手。"))
            .toString()
        val fixture = File(compose.activity.cacheDir, "shared_images/$name.png").apply {
            parentFile?.mkdirs()
            writeBytes(validCharacterPng(raw))
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uri = FileProvider.getUriForFile(compose.activity,
            "${compose.activity.packageName}.fileprovider", fixture)
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val monitor = instrumentation.addMonitor(filter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
        var importedId: String? = null
        try {
            openAssistantManager()
            compose.onNodeWithText("导入角色卡").performClick()
            compose.onNodeWithText("选择 PNG / JSON 文件").performClick()
            waitForPreview(name)
            assertPreview(raw, boilerplate, name)
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(15_000L) { model().uiState.value.contentImportPreview == null }
            assertFalse(model().uiState.value.assistants.any { it.name == name })

            compose.onNodeWithText("选择 PNG / JSON 文件").performClick()
            waitForPreview(name)
            assertPreview(raw, boilerplate, name)
            importedId = requireNotNull(model().uiState.value.contentImportPreview).id
            compose.onNodeWithText("确认导入").performClick()
            compose.waitUntil(20_000L) {
                val state = model().uiState.value
                state.contentImportPreview == null && !state.contentImportCommitting &&
                    state.assistants.any { it.id == importedId } && state.selectedAssistantId == importedId
            }
            val record = model().uiState.value.assistants.single { it.id == importedId }
            assertPersonaConsistent(record.id, record.systemPrompt, raw)
            assertTrue(model().uiState.value.messages.any { it.role == Role.ASSISTANT && it.content == greeting })
            assertTrue("The actual ACTION_OPEN_DOCUMENT launch must have been intercepted", monitor.hits >= 2)

            compose.onNodeWithContentDescription("返回助手列表").performClick()
            compose.onAllNodesWithText("编辑").onFirst().performClick()
            compose.onNodeWithText("编辑助手").assertExists()
            val promptMatcher = hasSetTextAction() and hasText("系统提示词")
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(promptMatcher)
            val edited = "你是$name。用户编辑的海边角色提示词。请记住蓝色茶杯并保持角色身份。"
            compose.onNode(promptMatcher).performTextReplacement(edited)
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(15_000L) {
                model().uiState.value.assistants.firstOrNull { it.id == importedId }?.systemPrompt == edited
            }
            assertPersonaConsistent(record.id, edited, raw)
            compose.waitUntil(15_000L) {
                AssistantStore(compose.activity).loadAssistants(GenerationParams())
                    .firstOrNull { it.id == record.id }?.systemPrompt == edited &&
                    ChatSessionStore(compose.activity).load().any {
                        it.assistantId == record.id && it.assistantSnapshot?.systemPrompt == edited
                    }
            }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(45_000L) {
                runCatching { model().uiState.value.selectedAssistantId == record.id }.getOrDefault(false)
            }
            assertPersonaConsistent(record.id, edited, raw)
            assertFalse(compose.activity.isFinishing)
            File(compose.activity.filesDir, "diagnostics/repair-character-card-import-ui.txt").apply {
                parentFile?.mkdirs()
                writeText("PASS activity=MainActivity source=PNG_CHARA\n" +
                    "cancelNoImport=true confirmImport=true edited=true roomReload=true activityRecreate=true\n" +
                    "recordAndParamsAndActiveSnapshotConsistent=true originalCardPreserved=true\n" +
                    "modelInference=NOT_TESTED processColdStart=NOT_TESTED\n")
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            val currentModel = model()
            val preview = currentModel.uiState.value.contentImportPreview
            if (preview?.title == name) {
                compose.runOnIdle { currentModel.cancelContentImportPreview(preview.id) }
                compose.waitUntil(15_000L) { currentModel.uiState.value.contentImportPreview == null }
            }
            // Restore the original real session before deleting only records created by this test.
            compose.waitUntil(15_000L) {
                compose.runOnIdle { currentModel.selectChatSession(requireNotNull(originalSessionId)) }
                currentModel.uiState.value.activeChatSessionId == originalSessionId
            }
            val ids = currentModel.uiState.value.chatSessions
                .filter { it.assistantId == importedId && importedId != null }.map { it.id }
            ids.forEach { id ->
                compose.waitUntil(15_000L) {
                    compose.runOnIdle { currentModel.deleteChatSession(id) }
                    currentModel.uiState.value.chatSessions.none { it.id == id } &&
                        ChatSessionStore(compose.activity).load().none { it.id == id }
                }
            }
            importedId?.let { id ->
                compose.waitUntil(15_000L) {
                    compose.runOnIdle { currentModel.deleteAssistant(id) }
                    currentModel.uiState.value.assistants.none { it.id == id }
                }
            }
            compose.runOnIdle {
                currentModel.onInputChange(original.input)
                currentModel.selectTab(original.tab)
            }
            fixture.delete()
        }
    }

    private fun model(): MainViewModel {
        var result: MainViewModel? = null
        compose.runOnIdle {
            result = ViewModelProvider(compose.activity)[AppStartupViewModel::class.java].state.value.model
        }
        return requireNotNull(result) { "Production MainActivity's startup model is not ready" }
    }

    private fun openAssistantManager() {
        compose.onNodeWithContentDescription("打开历史").performClick()
        // The drawer's logo menu follows its 34dp search IconButton in the same row.
        val search = compose.onNodeWithContentDescription("搜索").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            click(Offset(search.center.x + search.width, search.center.y))
        }
        compose.onNodeWithText("助手与角色").performClick()
    }

    private fun waitForPreview(name: String) {
        compose.waitUntil(15_000L) { model().uiState.value.contentImportPreview?.title == name }
        compose.onNodeWithText("导入预览：$name").assertExists()
    }

    private fun assertPreview(raw: String, boilerplate: String, name: String) {
        val preview = requireNotNull(model().uiState.value.contentImportPreview)
        assertEquals(CharacterCardSource.PNG_CHARA, preview.sourceKind)
        assertEquals(raw, preview.rawSource)
        assertEquals(boilerplate, preview.fields.single { it.first == "原卡 system_prompt" }.second)
        val prompt = preview.fields.single { it.first == "对话提示词" }.second
        assertTrue(prompt.contains(name))
        assertTrue(prompt.contains("海边"))
        assertFalse(isMcaDefaultAssistantPrompt(prompt))
    }

    private fun assertPersonaConsistent(id: String, prompt: String, raw: String) {
        val state = model().uiState.value
        val record = state.assistants.single { it.id == id }
        assertEquals(prompt, record.systemPrompt)
        assertEquals(raw, record.characterCardJson)
        assertEquals(prompt, GenerationParams.fromJson(record.paramsJson).systemPrompt)
        assertEquals(prompt, state.params.systemPrompt)
        val active = state.chatSessions.single { it.id == state.activeChatSessionId }
        assertEquals(id, active.assistantId)
        assertEquals(prompt, active.assistantSnapshot?.systemPrompt)
    }

    private fun validCharacterPng(raw: String): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val image = ByteArrayOutputStream()
        try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, image)) } finally { bitmap.recycle() }
        val png = image.toByteArray()
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val data = "chara\u0000${Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}"
            .toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }
        val result = ByteArrayOutputStream()
        result.write(png, 0, png.size - 12)
        DataOutputStream(result).apply {
            writeInt(data.size); write(type); write(data); writeInt(crc.value.toInt()); flush()
        }
        result.write(png, png.size - 12, 12)
        return result.toByteArray()
    }
}








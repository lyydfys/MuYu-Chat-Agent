package com.muyuchat.mca

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run with the real verified Hy-MT2 bundle installed and all Internet transports
 * disconnected. These tests verify that condition and never silently substitute
 * cloud translation. Each method uses its own Activity and restores its prompt.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ProductionOfflineTranslationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var main: MainViewModel
    private lateinit var device: UiDevice
    private lateinit var diagnosticFile: File
    private lateinit var diagnostic: JSONObject
    private var originalPrompt: String? = null

    @Test
    fun installedHyMt2TranslatesIntoEditableDraftOnImagePage() = withDiagnostics("editable-draft") {
        openOfflineImagePage()
        val source = "一只蓝色的小鸟站在枝头，背景是清晨的天空。"
        val translated = translateThroughImagePage(source)
        assertTrue("The English draft should still describe a bird", translated.contains("bird", ignoreCase = true))
        val edited = "$translated, soft morning light"
        compose.onNodeWithTag("image.prompt.translation.draft").performTextReplacement(edited)
        compose.onNodeWithTag("image.prompt.translation.apply").performClick()
        assertEquals("Explicit use must apply the editable draft", edited, promptText())
        diagnostic.put("editedDraft", edited).put("explicitApply", true)
    }

    @Test
    fun offlinePositiveAndNegativeTextDraftsPreserveControlSyntax() = withDiagnostics("structure-and-negative-text") {
        openOfflineImagePage()
        val positive = "两只蓝色的小鸟站在枝头，清晨的天空，(soft light:1.25), <lora:portrait_style:0.65>, embedding:detail_style"
        val positiveDraft = translateThroughImagePage(positive)
        assertTrue("The positive draft must preserve the subject", positiveDraft.contains("bird", ignoreCase = true))
        assertTrue("The quantity constraint must survive", positiveDraft.contains("two", ignoreCase = true) ||
            Regex("\\b2\\b").containsMatchIn(positiveDraft))
        assertProtectedFragments(positive, positiveDraft)
        compose.onNodeWithTag("image.prompt.translation.apply").performClick()
        assertEquals(positiveDraft, promptText())

        // There is currently no separate negative-field translation button on
        // the production page. Verify negative-style text through the existing
        // editable-draft entry; this does not certify automatic negative routing.
        val negative = "模糊，低清晰度，多余的手指，水印，(low quality:1.4), <lora:negative_style:0.20>"
        val negativeDraft = translateThroughImagePage(negative)
        assertProtectedFragments(negative, negativeDraft)
        assertTrue("Negative text must retain blur rather than silently drop it",
            negativeDraft.contains("blur", ignoreCase = true))
        assertTrue("Negative text must retain the watermark restriction",
            negativeDraft.contains("watermark", ignoreCase = true) || negativeDraft.contains("water mark", ignoreCase = true))
        compose.onNodeWithTag("image.prompt.translation.apply").performClick()
        assertEquals(negativeDraft, promptText())
        diagnostic.put("positiveSource", positive).put("positiveDraft", positiveDraft)
            .put("negativeTextSource", negative).put("negativeTextDraft", negativeDraft)
            .put("negativeCoverage", "editable text through prompt preview; dedicated negative-field action unavailable")
    }

    @Test
    fun cancelledOfflineTranslationPreservesOriginalAndCanRetry() = withDiagnostics("cancel-and-retry") {
        openOfflineImagePage()
        val source = "一只蓝色的小鸟站在枝头，背景是清晨的天空。".repeat(32)
        beginTranslation(source)
        val cancel = device.wait(Until.findObject(By.desc("取消翻译")), 15_000L)
            ?: throw AssertionError("Production page did not expose cancellation during translation")
        cancel.click()
        awaitStatus("confirmed cancellation and worker release", 180_000L) { status ->
            status == LOCAL_IMAGE_PROMPT_TRANSLATION_CANCELLED_STATUS
        }
        device.wait(Until.hasObject(By.desc("翻译为英文")), 15_000L)
        assertEquals("Cancellation must preserve the original source", source, promptText())
        compose.onNodeWithTag("image.prompt.translation.draft").assertDoesNotExist()
        assertFalse("Cancelling translation must keep the Activity alive", compose.activity.isFinishing)
        diagnostic.put("cancelledStatus", main.uiState.value.offlineTranslationStatus)
            .put("cancelledOriginalPreserved", true)
        saveDiagnostics("cancelled-worker-released")

        val retrySource = "一朵黄色的小花放在玻璃花瓶里。"
        val retry = translateThroughImagePage(retrySource)
        assertTrue("The fresh retry must describe its own source", retry.contains("flower", ignoreCase = true))
        compose.onNodeWithTag("image.prompt.translation.apply").performClick()
        assertEquals(retry, promptText())
        diagnostic.put("retrySource", retrySource).put("retryDraft", retry)
    }

    private fun openOfflineImagePage() {
        assertOffline()
        compose.waitUntil(45_000L) {
            compose.onNodeWithTag("chat.editor").runCatching { fetchSemanticsNode() }.isSuccess
        }
        compose.runOnUiThread {
            main = requireNotNull(ViewModelProvider(compose.activity)[AppStartupViewModel::class.java].state.value.model)
        }
        assertTrue("A verified real offline translator must already be installed", main.uiState.value.offlineTranslationInstalled)
        assertTrue("An image task must not overlap the translation test", main.uiState.value.imageJobs.none { !it.status.terminal })
        compose.onNodeWithContentDescription("打开历史").performClick()
        compose.onNodeWithText("图片").performClick()
        compose.waitUntil(15_000L) {
            compose.onNodeWithTag("image.prompt.editor").runCatching { fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithTag("image.gallery").performScrollToNode(hasTestTag("image.translation.installed"))
        compose.onNodeWithTag("image.translation.installed").assertExists()
        compose.onNodeWithTag("image.translation.download").assertDoesNotExist()
        originalPrompt = promptText()
        diagnostic.put("installed", true).put("downloadButtonAbsent", true)
        saveDiagnostics("image-page-ready")
    }

    private fun beginTranslation(source: String) {
        assertOffline()
        compose.onNodeWithTag("image.prompt.editor").performTextReplacement(source)
        diagnostic.put("currentSource", source)
        // Only this initial UI click uses Compose. The runtime can take minutes;
        // reading StateFlow or accessibility below never asks Compose to idle.
        compose.onNodeWithTag("image.prompt.translate").performClick()
        awaitStatus("translation started", 20_000L) { it.startsWith("Hy-MT2 正在翻译") }
        saveDiagnostics("translation-running")
    }

    private fun translateThroughImagePage(source: String): String {
        beginTranslation(source)
        awaitStatus("offline translation result", 180_000L) { status ->
            if (status.contains("翻译失败") || status.contains("安全回收")) {
                throw AssertionError("Real offline translation failed: $status")
            }
            status.startsWith("英文译文：")
        }
        assertTrue("The production page must expose a draft for explicit application",
            device.wait(Until.hasObject(By.text("使用英文")), 15_000L))
        val translated = compose.onNodeWithTag("image.prompt.translation.draft")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertTrue("Real Hy-MT2 output must be nonempty", translated.isNotBlank())
        assertFalse("The English draft must not retain untranslated Han text",
            translated.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN })
        assertEquals("Translation must not apply itself without user confirmation", source, promptText())
        assertFalse("Real translation must keep the Activity alive", compose.activity.isFinishing)
        diagnostic.put("translations", (diagnostic.optJSONArray("translations") ?: JSONArray()).put(
            JSONObject().put("source", source).put("draft", translated).put("originalPreserved", true)))
        saveDiagnostics("editable-translation-ready")
        return translated
    }

    private fun assertProtectedFragments(source: String, translated: String) {
        val protected = extractOfflinePromptProtectedTokens(source)
        assertTrue("The fixture must include actual control syntax", protected.isNotEmpty())
        assertEquals("Weights, LoRA and embedding fragments must retain exact order and multiplicity",
            protected, extractOfflinePromptProtectedTokens(translated))
    }

    private fun promptText(): String = compose.onNodeWithTag("image.prompt.editor")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun assertOffline() {
        val manager = compose.activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = manager.allNetworks.mapNotNull { network ->
            manager.getNetworkCapabilities(network)?.let { caps ->
                JSONObject().put("network", network.toString())
                    .put("internet", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                    .put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    .put("wifi", caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                    .put("cellular", caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                    .put("ethernet", caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
        }
        if (::diagnostic.isInitialized) diagnostic.put("networks", JSONArray(networks))
        assertTrue("Disconnect Wi-Fi, cellular and other Internet transports before this real offline test: $networks",
            networks.none { it.optBoolean("internet") })
    }

    private fun awaitStatus(label: String, timeoutMs: Long, condition: (String) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var nextReport = SystemClock.elapsedRealtime() + 15_000L
        while (!condition(main.uiState.value.offlineTranslationStatus)) {
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) throw AssertionError("Timed out waiting for $label: ${main.uiState.value.offlineTranslationStatus}")
            assertOffline()
            if (now >= nextReport) {
                saveDiagnostics("waiting: $label")
                nextReport = now + 15_000L
            }
            SystemClock.sleep(100L)
        }
        assertOffline()
    }

    private fun saveDiagnostics(stage: String) {
        diagnostic.put("stage", stage).put("updatedAtMillis", System.currentTimeMillis())
        if (::main.isInitialized) diagnostic.put("runtimeStatus", main.uiState.value.offlineTranslationStatus)
        diagnosticFile.writeText(diagnostic.toString(2))
    }

    private fun withDiagnostics(name: String, block: () -> Unit) {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        configurator.setWaitForIdleTimeout(0L)
        diagnosticFile = File(compose.activity.filesDir, "diagnostics/repair-hy-mt2-image-ui-$name.json").apply {
            parentFile?.mkdirs()
        }
        val packageInfo = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, 0)
        diagnostic = JSONObject().put("test", name).put("runId", UUID.randomUUID().toString())
            .put("apkVersion", packageInfo.versionName.orEmpty())
            .put("apkVersionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong())
            .put("deviceModel", Build.MODEL).put("startedAtMillis", System.currentTimeMillis())
        val started = SystemClock.elapsedRealtime()
        saveDiagnostics("START")
        var failure: Throwable? = null
        try {
            block()
            diagnostic.put("result", "PASS").put("elapsedMs", SystemClock.elapsedRealtime() - started)
            saveDiagnostics("PASS")
            device.takeScreenshot(File(diagnosticFile.parentFile, "repair-hy-mt2-image-ui-$name.png"))
        } catch (error: Throwable) {
            failure = error
            diagnostic.put("result", "FAIL").put("failure", error.stackTraceToString())
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            saveDiagnostics("FAIL")
            runCatching { device.takeScreenshot(File(diagnosticFile.parentFile, "repair-hy-mt2-image-ui-$name.png")) }
            throw error
        } finally {
            try {
                runCatching {
                    if (::main.isInitialized && main.uiState.value.offlineTranslationStatus.startsWith("Hy-MT2 正在翻译")) {
                        requireNotNull(device.findObject(By.desc("取消翻译"))) { "Cannot cancel the test-owned translation" }.click()
                        awaitStatus("cleanup worker release", 180_000L) {
                            it == LOCAL_IMAGE_PROMPT_TRANSLATION_CANCELLED_STATUS
                        }
                    }
                    originalPrompt?.let { compose.onNodeWithTag("image.prompt.editor").performTextReplacement(it) }
                }.onFailure { cleanup ->
                    diagnostic.put("cleanupFailure", cleanup.stackTraceToString())
                    saveDiagnostics(if (failure == null) "cleanup-failed" else "FAIL")
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            } finally {
                configurator.setWaitForIdleTimeout(previousIdleTimeout)
            }
        }
    }
}
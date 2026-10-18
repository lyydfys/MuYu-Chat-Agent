package com.muyuchat.mca.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assume

// Cross-process UI helpers.
//
// Macrobenchmark runs in its own process and cannot use Compose's onNodeWithTag: the
// testTagsAsResourceId bridge is not enabled on the production semantics tree. These helpers
// therefore use only strings/roles that already exist in the real production UI (content
// descriptions and visible text). If one of those selectors changes, the benchmark fails loudly
// instead of touching coordinates that happen to still work.

internal fun MacrobenchmarkScope.requireObject(
    selector: BySelector,
    description: String,
    timeoutMs: Long = BenchmarkTarget.UI_TIMEOUT_MS,
): UiObject2 = device.wait(Until.findObject(selector), timeoutMs)
    ?: throw AssertionError(
        "Timed out after ${timeoutMs}ms waiting for $description " +
            "(selector=$selector, package=$packageName). " +
            "The production UI text/semantics this benchmark depends on may have changed."
    )

internal fun MacrobenchmarkScope.waitForChatSurface(
    timeoutMs: Long = BenchmarkTarget.UI_TIMEOUT_MS,
) {
    val device = device
    val ready = device.wait(Until.hasObject(By.desc("打开历史")), timeoutMs) ||
        device.wait(Until.hasObject(By.text("问问 MCA")), 1_500)
    check(ready) {
        "Chat surface did not become interactive within ${timeoutMs}ms. " +
            "A startup/frame measurement against a blank or crashed screen is invalid."
    }
}

/**
 * A streaming benchmark needs a usable chat backend before the measured frame window starts.
 *
 * The check is deliberately a JUnit assumption, not a pass: without a selected/ready local or
 * cloud model this scenario is NOT_RUN. It must never be reported as a performance result.
 *
 * Readiness is inferred from the production model-header subtitle instead of a test seam:
 * `待加载`, the `选择模型` placeholder, loading/reloading/stopping states, `需要重载` and
 * `加载失败` all mean "not ready". If a state string changes, the benchmark skips instead of
 * driving a model-less screen and calling it a measurement.
 */
internal fun MacrobenchmarkScope.requireChatBackendFixture() {
    val device = device
    val headerPresent = device.wait(Until.hasObject(By.desc("选择模型")), BenchmarkTarget.UI_TIMEOUT_MS)
    Assume.assumeTrue(
        "Streaming fixture missing: the chat model header is not visible. " +
            "Install ${packageName} and open the chat screen first.",
        headerPresent
    )
    val unavailableMarkers = listOf(
        "待加载", "选择模型", "加载中", "重新加载中", "恢复参数中", "停止中", "需要重载", "加载失败"
    )
    val unavailable = unavailableMarkers.any { marker -> device.hasObject(By.textContains(marker)) }
    Assume.assumeTrue(
        "Streaming fixture missing: no ready chat backend is visible in the header. " +
            "Install ${packageName}, load a local chat model (or configure a cloud chat model) " +
            "once, then re-run. A skipped run is NOT_RUN, not a performance result.",
        !unavailable
    )
}

/** Prefer the explicit new-chat action; a restored session is an acceptable fallback. */
internal fun MacrobenchmarkScope.startFreshChatIfAvailable() {
    val device = device
    val newChat = device.findObject(By.desc("新建聊天"))
    if (newChat != null && newChat.isEnabled) {
        newChat.click()
        device.waitForIdle()
    }
}

internal fun MacrobenchmarkScope.focusChatEditor(): UiObject2 {
    val device = device
    device.wait(Until.findObject(By.clazz("android.widget.EditText")), 2_000)?.let { return it }
    // Blank composer: the hint text is a separate node, so tap it once and re-resolve the field.
    requireObject(By.text("问问 MCA"), "chat composer placeholder").click()
    device.waitForIdle()
    return requireObject(
        By.clazz("android.widget.EditText"),
        "chat composer text field after placeholder tap",
        3_000
    )
}

internal fun MacrobenchmarkScope.typePrompt(prompt: String) {
    val editor = focusChatEditor()
    editor.click()
    editor.setText(prompt)
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.pressSend() {
    val send = requireObject(By.desc("发送"), "send button")
    check(send.isEnabled) {
        "Send button is present but disabled; the benchmark fixture did not reach a sendable state."
    }
    send.click()
}

/** Returns true when the stop state was observed; false means generation never started. */
internal fun MacrobenchmarkScope.awaitGenerationStart(): Boolean =
    device.wait(Until.hasObject(By.desc("停止")), BenchmarkTarget.GENERATION_START_TIMEOUT_MS)

internal fun MacrobenchmarkScope.awaitGenerationTerminal() {
    val timeoutMs = BenchmarkArguments.generationTimeoutMs()
    check(device.wait(Until.gone(By.desc("停止")), timeoutMs)) {
        "Streaming generation did not reach a terminal state within ${timeoutMs}ms. " +
            "This iteration is invalid, not a slow-but-successful sample."
    }
}

/**
 * Navigates from the chat surface to the image gallery through the production entry points:
 * history drawer -> 图片 feature button -> gallery. It is a no-op when the gallery is already
 * visible (activity restore can keep the overlay open).
 */
internal fun MacrobenchmarkScope.openImageGallery() {
    val device = device
    if (device.wait(Until.hasObject(By.text("我的图片")), 1_000)) return

    device.findObject(By.desc("打开历史"))?.click()
    device.waitForIdle()

    val galleryEntry = device.wait(Until.findObject(By.text("图片")), BenchmarkTarget.UI_TIMEOUT_MS)
        ?: throw AssertionError(
            "Could not open the history drawer; the 图片 gallery entry is unavailable."
        )
    galleryEntry.click()
    device.waitForIdle()

    check(device.wait(Until.hasObject(By.text("我的图片")), BenchmarkTarget.UI_TIMEOUT_MS)) {
        "Image gallery did not open within ${BenchmarkTarget.UI_TIMEOUT_MS}ms."
    }
}

/**
 * Picks the tallest scrollable node. The gallery is a vertical LazyColumn that also contains
 * horizontal LazyRows (model switcher / prompt templates); choosing by visible height targets the
 * outer vertical list without relying on coordinates.
 */
internal fun MacrobenchmarkScope.primaryVerticalScrollable(): UiObject2 {
    val device = device
    val candidates = device.findObjects(By.scrollable(true))
        .filter { it.visibleBounds.height() > it.visibleBounds.width() }
    return candidates.maxByOrNull { it.visibleBounds.height() }
        ?: throw AssertionError(
            "No vertical scrollable node found; the image gallery fixture is missing."
        )
}

internal fun MacrobenchmarkScope.resetGalleryToTop() {
    val list = primaryVerticalScrollable()
    list.setGestureMargin(device.displayWidth / 5)
    repeat(4) { list.scroll(Direction.UP, 1.0f) }
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.flingGalleryDown() {
    val list = primaryVerticalScrollable()
    list.setGestureMargin(device.displayWidth / 5)
    repeat(BenchmarkTarget.GALLERY_FLINGS_PER_ITERATION) {
        list.fling(Direction.DOWN)
    }
}

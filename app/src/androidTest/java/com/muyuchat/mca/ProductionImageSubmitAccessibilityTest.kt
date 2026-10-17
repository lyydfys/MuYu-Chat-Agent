package com.muyuchat.mca

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.os.Build
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production accessibility path, independent of Espresso and ActivityScenario internals. */
@RunWith(AndroidJUnit4::class)
class ProductionImageSubmitAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val resumedActivity = AtomicReference<Activity?>()

    private fun mainState(): MainUiState {
        var state: MainUiState? = null
        instrumentation.runOnMainSync {
            val activity = requireNotNull(resumedActivity.get())
            val getter = MainActivity::class.java.getDeclaredMethod("getViewModel").apply { isAccessible = true }
            state = (getter.invoke(activity) as MainViewModel).uiState.value
        }
        return requireNotNull(state)
    }

    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        return automation.rootInActiveWindow
    }

    private fun shell(command: String) {
        automation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        Log.i("MCA-UiProbe", "tapBounds=$bounds")
        // Dispatch real pointer events through the production window. This avoids OEM
        // shell-input filtering and does not call product intents or replace its owner.
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        instrumentation.runOnMainSync {
            val decor = requireNotNull(resumedActivity.get()).window.decorView
            val location = IntArray(2).also(decor::getLocationOnScreen)
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    (bounds.centerX() - location[0]).toFloat(),
                    (bounds.centerY() - location[1]).toFloat(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(150L)
        }
        SystemClock.sleep(500L)
    }

    private fun find(label: String, description: Boolean = false): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val value = if (description) node.contentDescription else node.text
            if (value?.toString() == label) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            }
            return null
        }
        return root()?.let(::visit)
    }

    private fun click(label: String, description: Boolean = false): Boolean {
        var node = find(label, description) ?: return false
        while (!node.isClickable) node = node.parent ?: return false
        Log.i("MCA-UiProbe", "clickLabel=$label")
        tap(node)
        automation.takeScreenshot()?.let { bitmap ->
            val directory = File(instrumentation.targetContext.filesDir, "production_ui_probe").apply { mkdirs() }
            File(directory, "${System.currentTimeMillis()}.png").outputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
        }
        return true
    }

    private fun waitFor(label: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            assertTrue("Timed out: $label", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(250L)
        }
    }

    private fun promptEditor(): AccessibilityNodeInfo? {
        val send = find("生成图片", true) ?: return null
        val sendBounds = Rect().also(send::getBoundsInScreen)
        val fields = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isEditable) fields.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        root()?.let(::visit)
        // The gallery search box is also editable. Choose the field in the composer row
        // next to the send control using current accessibility bounds.
        return fields.minByOrNull { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            kotlin.math.abs(bounds.centerY() - sendBounds.centerY())
        }
    }

    private fun journalCount(): Int = File(
        instrumentation.targetContext.filesDir,
        "image_execution_journal"
    ).listFiles()?.count { it.isFile } ?: 0

    @Test
    fun productionImageSubmitReachesTheRealImageOwner() {
        val app = instrumentation.targetContext.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivity.set(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
        // OEM background-activity restrictions can reject a launch from the instrumented
        // app UID even when instrumentation is allowed. Launch the real activity through
        // the shell, as a user opening the installed application would do.
        shell("am start -W -n com.muyuchat.mca/.MainActivity")
        waitFor("MCA foreground", 20_000L) {
            resumedActivity.get() is MainActivity && root()?.packageName?.toString() == "com.muyuchat.mca"
        }
        waitFor("startup completed", 30_000L) { find("生成方式") != null || find("打开历史", true) != null }
        if (find("生成方式") == null) {
            waitFor("history control", 30_000L) { find("打开历史", true) != null }
            assertTrue(click("打开历史", true))
            waitFor("image page entry", 10_000L) { find("图片") != null }
            assertTrue(click("图片"))
        }
        waitFor("image page", 20_000L) { find("生成方式") != null }
        find("文生图")?.let { click("文生图") }
        SystemClock.sleep(1_000L)

        val field = requireNotNull(promptEditor()) { "Production image editor is absent" }
        Log.i("MCA-UiProbe", "editorBounds=${Rect().also(field::getBoundsInScreen)}")
        tap(field)
        SystemClock.sleep(500L)
        val prompt = "accessibility owner recovery ${System.currentTimeMillis()} red apple"
        val textAccepted = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt) })
        if (!textAccepted) instrumentation.runOnMainSync {
            val decor = requireNotNull(resumedActivity.get()).window.decorView
            val time = SystemClock.uptimeMillis()
            decor.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON))
            decor.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON))
            for (event in requireNotNull(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(prompt.toCharArray()))) {
                decor.dispatchKeyEvent(event)
            }
        }
        waitFor("prompt text", 10_000L) { promptEditor()?.text?.toString()?.contains(prompt) == true }
        SystemClock.sleep(1_000L)

        val before = journalCount()
        val jobsBefore = mainState().imageJobs.map { it.id }.toSet()
        var send = requireNotNull(find("生成图片", true))
        while (!send.isClickable) send = requireNotNull(send.parent)
        Log.i("MCA-UiProbe", "semanticClick=${send.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
        SystemClock.sleep(1_000L)
        if (mainState().imageJobs.none { it.id !in jobsBefore }) {
            assertTrue("Production send action was not accepted", click("生成图片", true))
        }
        Log.i("MCA-UiProbe", "jobs=${mainState().imageJobs.size} status=${mainState().statusMessage}")
        waitFor("real image owner admission", 30_000L) {
            journalCount() > before ||
                mainState().imageJobs.any { it.id !in jobsBefore } ||
                find("正在生成图片") != null ||
                find("正在生成") != null ||
                find("图片任务已排队") != null ||
                find("图片生成失败") != null
        }

        // Keep verification bounded and safe: if native work started, use the production stop
        // control. This proves admission/dispatch; it is not a long-running image benchmark.
        if (InstrumentationRegistry.getArguments().getString("complete") == "true") {
            waitFor("new image task terminal", 600_000L) {
                mainState().imageJobs.any { it.id !in jobsBefore && it.status.terminal }
            }
            val job = mainState().imageJobs.first { it.id !in jobsBefore }
            Log.i("MCA-UiProbe", "jobTerminal=${job.status} message=${job.message}")
            assertTrue("Image task did not publish an asset: ${job.status}", job.imageAssetIds.isNotEmpty() || job.imageAssetId != null)
        } else if (find("停止生成", true) != null) {
            assertTrue(click("停止生成", true))
        } else if (find("取消生成") != null) {
            assertTrue(click("取消生成"))
        }
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}

package com.muyuchat.mca

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in UI driver for the installed production Activity. No business intents are invoked. */
@RunWith(AndroidJUnit4::class)
class ProductionUiSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val activity = AtomicReference<Activity?>()

    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        return automation.rootInActiveWindow
    }

    private fun state(): MainUiState {
        var result: MainUiState? = null
        instrumentation.runOnMainSync {
            val getter = MainActivity::class.java.getDeclaredMethod("getViewModel").apply { isAccessible = true }
            result = (getter.invoke(requireNotNull(activity.get())) as MainViewModel).uiState.value
        }
        return requireNotNull(result)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        root()?.let(::visit)
        return result
    }

    private fun target(command: JSONObject): AccessibilityNodeInfo {
        val all = nodes()
        val matching = if (command.has("editor")) all.filter { it.isEditable } else all.filter {
            it.text?.toString() == command.optString("label") ||
                it.contentDescription?.toString() == command.optString("label")
        }
        val index = if (command.has("editor")) command.getInt("editor") else command.optInt("index", 0)
        return requireNotNull(matching.getOrNull(index)) { "UI target is absent" }
    }

    private fun pointer(x: Float, y: Float, toX: Float = x, toY: Float = y, swipe: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        val count = if (swipe) 12 else 1
        for (step in 0..count + 1) {
            instrumentation.runOnMainSync {
                val decor = requireNotNull(activity.get()).window.decorView
                val location = IntArray(2).also(decor::getLocationOnScreen)
                val fraction = (step.toFloat() / count).coerceIn(0f, 1f)
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; count + 1 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                    x + (toX - x) * fraction - location[0],
                    y + (toY - y) * fraction - location[1], 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            SystemClock.sleep(if (swipe) 30L else 75L)
        }
        SystemClock.sleep(400L)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        pointer(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun composeNodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun semantics(node: SemanticsNode) {
            result.add(node)
            node.children.forEach(::semantics)
        }
        fun visit(view: View) {
            val getter = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (getter != null) semantics((getter.invoke(view) as SemanticsOwner).rootSemanticsNode)
            else if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(requireNotNull(activity.get()).window.decorView)
        return result
    }

    private fun execute(command: JSONObject): JSONObject {
        when (command.getString("action")) {
            "composeSnapshot", "composeClick" -> {
                var response: JSONObject? = null
                instrumentation.runOnMainSync {
                    val nodes = composeNodes()
                    if (command.getString("action") == "composeClick") {
                        val node = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == command.getString("tag") }
                        val click = requireNotNull(node?.config?.getOrNull(SemanticsActions.OnClick)?.action)
                        response = JSONObject().put("accepted", click())
                    } else {
                        val result = JSONArray()
                        nodes.forEach { node ->
                            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
                            val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
                            if (tag != null || description != null) result.put(JSONObject()
                                .put("tag", tag).put("description", description?.joinToString())
                                .put("click", node.config.getOrNull(SemanticsActions.OnClick)?.action != null))
                        }
                        response = JSONObject().put("nodes", result)
                    }
                }
                return requireNotNull(response)
            }
            "snapshot" -> {
                val result = JSONArray()
                nodes().forEachIndexed { index, node ->
                    if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() || node.isEditable) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        result.put(JSONObject().put("index", index)
                            .put("text", if (node.isPassword) "[redacted]" else node.text?.toString().orEmpty())
                            .put("description", node.contentDescription?.toString().orEmpty())
                            .put("editable", node.isEditable).put("clickable", node.isClickable)
                            .put("enabled", node.isEnabled).put("bounds", bounds.toShortString()))
                    }
                }
                return JSONObject().put("nodes", result)
            }
            "tap" -> {
                var node = target(command)
                if (!node.isEditable) while (!node.isClickable) node = node.parent ?: break
                tap(node)
            }
            "semanticClick" -> {
                var node = target(command)
                while (!node.isClickable) node = requireNotNull(node.parent)
                return JSONObject().put("accepted", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            "type" -> {
                val node = target(command)
                require(node.isEditable) { "UI target is not editable" }
                tap(node)
                val value = command.getString("value")
                val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
                if (!accepted) instrumentation.runOnMainSync {
                    val decor = requireNotNull(activity.get()).window.decorView
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                        decor.dispatchKeyEvent(KeyEvent(time, time, action, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON))
                    }
                    for (event in requireNotNull(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray()))) {
                        decor.dispatchKeyEvent(event)
                    }
                }
            }
            "back" -> instrumentation.runOnMainSync {
                val time = SystemClock.uptimeMillis()
                val decor = requireNotNull(activity.get()).window.decorView
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    decor.dispatchKeyEvent(KeyEvent(time, time, action, KeyEvent.KEYCODE_BACK, 0))
                }
            }
            "swipe" -> pointer(command.getDouble("x").toFloat(), command.getDouble("y").toFloat(),
                command.getDouble("toX").toFloat(), command.getDouble("toY").toFloat(), true)
            "state" -> {
                val state = state()
                val jobs = JSONArray()
                state.imageJobs.forEach { job ->
                    jobs.put(JSONObject().put("id", job.id).put("status", job.status.toString())
                        .put("message", job.message).put("assetIds", JSONArray(job.imageAssetIds))
                        .put("assetId", job.imageAssetId))
                }
                return JSONObject().put("status", state.statusMessage).put("jobs", jobs)
                    .put("imageBackend", state.selectedImageBackend.toString())
                    .put("chatBackend", state.selectedChatBackend.toString())
                    .put("generating", state.isGenerating)
            }
            "imageIdentity" -> {
                // Read-only diagnostic for an explicitly selected QA package. It cannot
                // import, alter a registry, rebind a profile, or certify native execution.
                val suffix = command.getString("bundleSuffix")
                require(suffix.startsWith("sd15-gen5-catalog-selfcontained-"))
                val root = File(instrumentation.targetContext.getExternalFilesDir(null), "image_models/$suffix")
                val manifest = JSONObject(File(root, "manifest.json").readText())
                val primary = requireNotNull(findPrimaryImageModel(root))
                val model = LocalImageModelRecord(
                    displayName = "QA identity observation", fileName = primary.name,
                    sizeBytes = primary.length(), sha256 = "",
                    path = primary.canonicalPath, bundleRoot = root.canonicalPath,
                    runtime = LocalImageRuntime.valueOf(manifest.getString("runtime"))
                )
                val observed = captureLocalImageContentSnapshot(model, force = true)
                return JSONObject().put("runtime", model.runtime.name)
                    .put("observedFingerprint", observed.fingerprint)
                    .put("declaredFingerprint", manifest.getJSONObject("executionProfile").getString("modelFingerprint"))
                    .put("components", JSONArray().apply { observed.components.forEach { put(it.toJson()) } })
                    .put("quoteSample", JSONObject.quote("tokenizer/merges.txt"))
                    .put("numberSamples", JSONArray().put(JSONObject.numberToString(1.0))
                        .put(JSONObject.numberToString(0.00085)).put(JSONObject.numberToString(0.012)))
            }
            else -> error("Unknown UI action")
        }
        SystemClock.sleep(500L)
        return JSONObject().put("accepted", true)
    }

    @Test
    fun operateProductionUi() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("interactiveUI") == "true")
        val app = instrumentation.targetContext.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(value: Activity) { activity.set(value) }
            override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityPaused(value: Activity) = Unit
            override fun onActivityStopped(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(value: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        val portFile = File(instrumentation.targetContext.cacheDir, "production_ui_session.port")
        try {
            automation.executeShellCommand("am start -W -n com.muyuchat.mca/.MainActivity").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            while (activity.get() !is MainActivity) {
                check(SystemClock.elapsedRealtime() < deadline) { "Production Activity did not resume" }
                SystemClock.sleep(250L)
            }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 1_800_000
                portFile.writeText(server.localPort.toString())
                // Instrumentation can exercise a signed non-debuggable production APK.
                // Expose only the loopback test port, so the host never needs run-as access.
                instrumentation.sendStatus(0, Bundle().apply {
                    putInt("productionUiPort", server.localPort)
                })
                server.accept().use { socket ->
                    socket.soTimeout = 1_800_000
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()
                    while (true) {
                        val line = input.readLine() ?: break
                        val command = JSONObject(line)
                        if (command.optString("action") == "finish") break
                        val response = try { execute(command).put("ok", true) } catch (failure: Throwable) {
                            // Do not echo command arguments: they can contain test credentials.
                            JSONObject().put("ok", false).put("error", failure.javaClass.simpleName)
                        }
                        output.write(response.toString())
                        output.newLine()
                        output.flush()
                    }
                }
            }
        } finally {
            portFile.delete()
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}

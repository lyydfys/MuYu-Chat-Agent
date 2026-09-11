package com.muyuchat.mca

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class AppStartupState(
    val model: MainViewModel? = null,
    val loading: Boolean = true,
    val slow: Boolean = false,
    val problem: String? = null,
    val diagnostic: String = ""
)

/** The launcher stays usable even when a model directory or database cannot be read. */
class AppStartupViewModel(application: Application) : AndroidViewModel(application) {
    private val children = ViewModelStore()
    private val mutableState = MutableStateFlow(AppStartupState())
    internal val state = mutableState.asStateFlow()
    private var starting = false
    private var cleared = false
    private var startupJob: Job? = null
    private val pendingActions = ArrayDeque<(MainViewModel) -> Unit>()

    init { start(checkPreviousAttempt = true) }

    internal fun whenReady(action: (MainViewModel) -> Unit) {
        state.value.model?.let(action) ?: pendingActions.addLast(action)
    }

    internal fun start(skipModelDiscovery: Boolean = false, checkPreviousAttempt: Boolean = false) {
        if (starting || cleared || state.value.model != null) return
        starting = true
        mutableState.value = AppStartupState()
        startupJob = viewModelScope.launch {
            launch {
                delay(5_000L)
                if (starting && state.value.model == null) {
                    mutableState.value = state.value.copy(slow = true)
                }
            }
            var prepared: MainViewModel? = null
            var published = false
            try {
                val app = getApplication<Application>()
                val previous = withContext(Dispatchers.IO) { StartupDiagnostics.read(app) }
                if (checkPreviousAttempt && previous.optBoolean("starting")) {
                    mutableState.value = AppStartupState(loading = false,
                        problem = "上次启动未完成。可以暂缓扫描模型后进入应用，模型和聊天记录都会保留。",
                        diagnostic = previous.toString(2))
                    return@launch
                }
                withContext(Dispatchers.IO) { StartupDiagnostics.record(app, true, null) }
                // Retain the instance even if the Activity is closed during construction;
                // finally clears its ViewModelStore and cancels any owned work.
                withContext(Dispatchers.IO) {
                    prepared = MainViewModel(app, deferStartup = true, skipInitialModelDiscovery = skipModelDiscovery)
                }
                val main = requireNotNull(prepared)
                ViewModelProvider(children, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = main as T
                })[MainViewModel::class.java]
                withContext(Dispatchers.IO) { main.startAfterConstruction() }
                withContext(Dispatchers.IO) { StartupDiagnostics.record(app, false, null) }
                mutableState.value = AppStartupState(model = main, loading = false)
                published = true
                while (pendingActions.isNotEmpty()) pendingActions.removeFirst()(main)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val diagnostic = withContext(Dispatchers.IO) {
                    StartupDiagnostics.record(getApplication(), false, error)
                }
                mutableState.value = AppStartupState(loading = false,
                    problem = "启动未完成：${error.message.orEmpty().take(300)}。请检查剩余存储空间，释放空间后重试；也可以暂缓模型扫描。",
                    diagnostic = diagnostic)
            } finally {
                if (!published) {
                    // A cancelled constructor may finish before registration in the store.
                    prepared?.let { main ->
                        ViewModelProvider(children, object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T = main as T
                        })[MainViewModel::class.java]
                    }
                    children.clear()
                }
                starting = false
                startupJob = null
            }
        }
    }

    /** Cancels a slow startup safely before retrying with discovery deferred. */
    internal fun skipModelDiscovery() {
        if (!starting || cleared || state.value.model != null) {
            start(skipModelDiscovery = true)
            return
        }
        val current = startupJob ?: return
        viewModelScope.launch {
            current.cancelAndJoin()
            if (!cleared && state.value.model == null) {
                start(skipModelDiscovery = true)
            }
        }
    }

    override fun onCleared() {
        cleared = true
        // A synchronous constructor/start block on IO can outlive cancellation.
        // Its finally block owns disposal, after all initialization has stopped.
        if (!starting) children.clear()
        pendingActions.clear()
        super.onCleared()
    }
}

internal object StartupDiagnostics {
    private fun file(app: Application) = File(app.filesDir, "diagnostics/startup.json")
    fun read(app: Application): JSONObject = runCatching {
        android.util.AtomicFile(file(app)).openRead().bufferedReader().use { JSONObject(it.readText()) }
    }.getOrElse { JSONObject() }

    fun record(app: Application, starting: Boolean, error: Throwable?): String {
        val json = JSONObject().put("starting", starting).put("timeMs", System.currentTimeMillis())
            .put("availableBytes", app.filesDir.usableSpace)
            .put("error", error?.stackTraceToString()?.take(24_000))
            .put("downloadSchedulerError", BackgroundDownloadHealth.failure.value)
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val activityManager = app.getSystemService(ActivityManager::class.java)
                val exits = JSONArray()
                activityManager.getHistoricalProcessExitReasons(app.packageName, 0, 8)
                    .filter { it.processName == app.packageName }
                    .forEach { exit -> exits.put(JSONObject()
                        .put("reason", exit.reason).put("timestamp", exit.timestamp)
                        .put("description", exit.description).put("status", exit.status)) }
                json.put("mainProcessExits", exits)
            }
        }
        val text = json.toString(2)
        // Diagnostics must still be displayable when the disk itself is full.
        runCatching {
            val destination = file(app)
            destination.parentFile?.mkdirs()
            val atomic = android.util.AtomicFile(destination)
            val stream = atomic.startWrite()
            try { stream.write(text.toByteArray()); atomic.finishWrite(stream) }
            catch (failure: Exception) { atomic.failWrite(stream); throw failure }
        }
        return text
    }
}

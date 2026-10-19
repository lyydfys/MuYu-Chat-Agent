package com.muyuchat.mca

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.util.Log
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
import com.muyuchat.mca.composition.McaAppContainer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One startup log channel and one time base for the whole launch path.
 *
 * [AppStartupViewModel] owns the phase stamps and [MainPostStartupCoordinator] reports the
 * post-startup work; keeping both halves on this tag and this T0 is what makes a cold start
 * filterable (`adb logcat -s McaStartup`) and comparable as one TTID -> TTFD timeline. The base
 * is restarted per startup attempt, so every logged `t=` is elapsed time since that attempt began.
 */
internal object StartupLog {
    const val TAG = "McaStartup"

    @Volatile
    private var baseElapsedMs = 0L

    /** Marks the shared T0. Called once when an attempt starts. */
    fun begin() {
        baseElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * Logs [label] relative to the shared T0. [opStartedAt], when supplied, adds the duration of
     * the individual step as `op=`; [thread] keeps the existing thread attribution.
     */
    fun stamp(label: String, opStartedAt: Long? = null, thread: String? = null) {
        val now = SystemClock.elapsedRealtime()
        val base = baseElapsedMs
        val relative = if (base == 0L) "no base" else "t=${now - base}ms"
        val operation = opStartedAt?.let { " op=${now - it}ms" }.orEmpty()
        val caller = thread?.let { " thread=$it" }.orEmpty()
        Log.i(TAG, "$label $relative$operation$caller")
    }
}

/**
 * Real, observable startup phases. The label is what the launch surface shows verbatim, so it
 * must stay truthful: a phase is only entered when the corresponding work actually starts.
 */
internal enum class AppStartupPhase(val label: String) {
    PREPARING("正在准备本地引擎…"),
    MIGRATING("正在整理本地数据…"),
    ASSEMBLING("正在装载应用组件…"),
    READING("正在读取本地记录…"),
    FINISHING("即将完成…"),
}

internal data class AppStartupState(
    val model: MainViewModel? = null,
    val loading: Boolean = true,
    val slow: Boolean = false,
    val problem: String? = null,
    val diagnostic: String = "",
    val phase: AppStartupPhase = AppStartupPhase.PREPARING,
    val progress: Float = 0f,
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

    /**
     * Low-volume startup timing, one line per real phase. Kept in release on purpose: startup
     * attribution is a user-facing performance property and adb logcat is the only way to read it
     * on a non-debuggable build. Routed through [StartupLog] so phase stamps and post-startup
     * work share one tag and one T0.
     */
    private fun stamp(label: String) = StartupLog.stamp(label)

    /** Publishes a real phase boundary; ignored once the app model is ready. */
    private fun mark(phase: AppStartupPhase, progress: Float) {
        if (state.value.model != null) return
        mutableState.value = state.value.copy(
            loading = true,
            phase = phase,
            progress = progress.coerceIn(0f, 1f),
        )
    }

    init { start(checkPreviousAttempt = true) }

    internal fun whenReady(action: (MainViewModel) -> Unit) {
        state.value.model?.let(action) ?: pendingActions.addLast(action)
    }

    internal fun start(skipModelDiscovery: Boolean = false, checkPreviousAttempt: Boolean = false) {
        if (starting || cleared || state.value.model != null) return
        starting = true
        mutableState.value = AppStartupState()
        StartupLog.begin()
        stamp("begin")
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
                stamp("diagnostics-read")
                mark(AppStartupPhase.PREPARING, 0.05f)
                if (checkPreviousAttempt && previous.optBoolean("starting")) {
                    mutableState.value = AppStartupState(loading = false,
                        problem = "上次启动未完成。可以暂缓扫描模型后进入应用，模型和聊天记录都会保留。",
                        diagnostic = previous.toString(2))
                    return@launch
                }
                withContext(Dispatchers.IO) { StartupDiagnostics.record(app, true, null) }
                stamp("diagnostics-record")
                mark(AppStartupPhase.MIGRATING, 0.12f)
                // Retain the instance even if the Activity is closed during construction;
                // finally clears its ViewModelStore and cancels any owned work.
                val migrated = withContext(Dispatchers.IO) {
                    ModelIdentityReferenceMigration(app).migratePendingReferences()
                }
                stamp("migration")
                mark(AppStartupPhase.ASSEMBLING, 0.22f)
                prepared = withContext(Dispatchers.IO) {
                    val container = McaAppContainer(app)
                    stamp("container")
                    val installationScopeId = container.runtimeProfileStore.installationScopeId()
                    MainViewModel(
                        app,
                        deferStartup = true,
                        skipInitialModelDiscovery = skipModelDiscovery,
                        injectedStartupContext = MainViewModelStartupContext(
                            container = container,
                            installationScopeId = installationScopeId,
                            modelIdentityReferencesMigrated = migrated
                        )
                    )
                }
                stamp("viewmodel")
                mark(AppStartupPhase.READING, 0.35f)
                val main = requireNotNull(prepared)
                ViewModelProvider(children, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = main as T
                })[MainViewModel::class.java]
                withContext(Dispatchers.IO) { main.startAfterConstruction() }
                stamp("post-startup")
                mark(AppStartupPhase.FINISHING, 0.62f)
                stamp("ready")
                mutableState.value = AppStartupState(
                    model = main,
                    loading = false,
                    phase = AppStartupPhase.FINISHING,
                    progress = 1f,
                )
                published = true
                // The success diagnostics record serializes process-exit history and fsyncs a
                // JSON file; none of it is needed for the first app frame. Publish ready first
                // and write it off the startup path. The failure branch below still records on
                // the path because its text is shown on the retry card. The tiny window between
                // ready and this write only means a process death there would still be reported
                // as an incomplete previous start on the next launch.
                viewModelScope.launch(Dispatchers.IO) {
                    StartupDiagnostics.record(app, false, null)
                    stamp("diagnostics-final")
                }
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

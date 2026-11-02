package com.muyuchat.mca

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.muyuchat.mca.composition.McaProcessRuntimeOwner
import com.muyuchat.mca.composition.ProcessRuntimeSettlement
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal enum class ProcessUiLifecycleEvent {
    FOREGROUNDED,
    BACKGROUNDED
}

internal class ProcessUiLifecycleEventRelay {
    private val mutableEvents = MutableSharedFlow<ProcessUiLifecycleEvent>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var currentEvent: ProcessUiLifecycleEvent? = null
    val events: SharedFlow<ProcessUiLifecycleEvent> = mutableEvents.asSharedFlow()

    @Synchronized
    fun publish(event: ProcessUiLifecycleEvent): Boolean {
        if (currentEvent == event) return false
        currentEvent = event
        check(mutableEvents.tryEmit(event)) { "Unable to publish process UI lifecycle state." }
        return true
    }

    @Synchronized
    fun current(): ProcessUiLifecycleEvent? = currentEvent
}

internal object ProcessUiLifecycleEvents {
    private val relay = ProcessUiLifecycleEventRelay()
    val events: SharedFlow<ProcessUiLifecycleEvent> = relay.events

    fun publish(event: ProcessUiLifecycleEvent) {
        relay.publish(event)
    }

    fun current(): ProcessUiLifecycleEvent? = relay.current()
}

internal object BackgroundDownloadHealth {
    val failure = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

class McaApplication : Application(), DefaultLifecycleObserver, androidx.work.Configuration.Provider {
    /**
     * Composition-owned single-flight holder for the application-only container and the one
     * Agent/engine execution root (ADR-0008). The Application only owns this holder; it never
     * names the container type or a second runtime.
     */
    internal val processRuntimeOwner = McaProcessRuntimeOwner(this)
    private val activityTracker = ProcessActivityTracker()
    private val runtimeSettlement = ProcessRuntimeSettlement(
        hasLiveActivities = activityTracker::hasLiveActivities,
        closeRoot = processRuntimeOwner::close,
        hasLiveProcessService = LocalApiForegroundService::isForegroundReady
    )

    /**
     * Settles the process root when the last Activity finishes (user removed the task) or the app
     * process terminates. A plain background transition does not destroy the Activity and keeps the
     * root alive, which is what background Agent work relies on. An active Worker also keeps it.
     */
    internal fun closeProcessRuntimeRoot() = runtimeSettlement.forceClose()

    /** A worker-only process has no page to finish, so it settles its own root after the wake. */
    internal fun beginBackgroundWork() = runtimeSettlement.beginBackgroundWork()

    internal fun finishBackgroundWork() = runtimeSettlement.finishBackgroundWork()

    internal fun closeProcessRuntimeRootIfUnused() = runtimeSettlement.settleIfIdle()

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setInitializationExceptionHandler { error ->
                android.util.Log.e("McaBackgroundDownload", "Download scheduler initialization failed", error)
                BackgroundDownloadHealth.failure.value =
                    "后台下载暂不可用：${error.message.orEmpty()}。请检查剩余存储空间并重新打开应用；模型文件已保留。"
            }
            .build()

    override fun onCreate() {
        super<Application>.onCreate()
        // The signer set is process-local and fail-closed if Android cannot expose it.
        ImagePromptLanguageProofTrust.initialize(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                activityTracker.started()
            }

            override fun onActivityStopped(activity: Activity) {
                activityTracker.stopped()
            }

            override fun onActivityDestroyed(activity: Activity) {
                activityTracker.destroyed()
                if (activityTracker.shouldCloseOnDestroyed(
                        isFinishing = activity.isFinishing,
                        isChangingConfigurations = activity.isChangingConfigurations
                    )
                ) {
                    closeProcessRuntimeRootIfUnused()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activityTracker.created()
            }

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    override fun onTerminate() {
        closeProcessRuntimeRoot()
        super.onTerminate()
    }

    override fun onStart(owner: LifecycleOwner) {
        ProcessUiLifecycleEvents.publish(ProcessUiLifecycleEvent.FOREGROUNDED)
    }

    override fun onStop(owner: LifecycleOwner) {
        ProcessUiLifecycleEvents.publish(ProcessUiLifecycleEvent.BACKGROUNDED)
    }
}


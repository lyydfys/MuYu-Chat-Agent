package com.muyuchat.mca

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
    }

    override fun onStart(owner: LifecycleOwner) {
        ProcessUiLifecycleEvents.publish(ProcessUiLifecycleEvent.FOREGROUNDED)
    }

    override fun onStop(owner: LifecycleOwner) {
        ProcessUiLifecycleEvents.publish(ProcessUiLifecycleEvent.BACKGROUNDED)
    }
}


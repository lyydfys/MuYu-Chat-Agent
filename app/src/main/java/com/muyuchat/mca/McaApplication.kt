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
}

internal object ProcessUiLifecycleEvents {
    private val relay = ProcessUiLifecycleEventRelay()
    val events: SharedFlow<ProcessUiLifecycleEvent> = relay.events

    fun publish(event: ProcessUiLifecycleEvent) {
        relay.publish(event)
    }
}

class McaApplication : Application(), DefaultLifecycleObserver {
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


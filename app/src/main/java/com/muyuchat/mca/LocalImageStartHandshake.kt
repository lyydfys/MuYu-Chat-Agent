package com.muyuchat.mca

internal class LocalImageStartHandshake {
    private val lock = Any()
    private var state = State.PREPARING
    private var cancelRequested = false

    fun requestCancel(): CancelAction = synchronized(lock) {
        cancelRequested = true
        when (state) {
            State.PREPARING -> {
                state = State.FINISHED
                CancelAction.COMPLETE_LOCALLY
            }
            State.STARTING -> CancelAction.DEFER_UNTIL_REGISTERED
            State.STARTED -> CancelAction.CANCEL_REMOTE
            State.FINISHED -> CancelAction.NONE
        }
    }

    fun tryBeginRemoteStart(): Boolean = synchronized(lock) {
        if (state != State.PREPARING || cancelRequested) {
            state = State.FINISHED
            false
        } else {
            state = State.STARTING
            true
        }
    }

    fun completeRemoteStart(accepted: Boolean): Boolean = synchronized(lock) {
        if (!accepted) {
            state = State.FINISHED
            false
        } else {
            state = State.STARTED
            cancelRequested
        }
    }

    fun markFinished() {
        synchronized(lock) { state = State.FINISHED }
    }

    internal enum class CancelAction {
        COMPLETE_LOCALLY,
        DEFER_UNTIL_REGISTERED,
        CANCEL_REMOTE,
        NONE
    }

    private enum class State {
        PREPARING,
        STARTING,
        STARTED,
        FINISHED
    }
}

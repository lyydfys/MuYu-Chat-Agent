package com.muyuchat.core.engine

/**
 * Lifecycle of a model's visual component. This is deliberately separate
 * from download/import/readiness flags: a projector file on disk is not proof
 * that the native runner consumed it.
 */
internal enum class VisionBindingState(val wireName: String) {
    UNBOUND("unbound"),
    BOUND_PENDING_RELOAD("bound_pending_reload"),
    LOADING("loading"),
    READY("ready"),
    LOAD_FAILED("load_failed")
}

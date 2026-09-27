package com.muyuchat.core.engine

/** Serializes cancel/close ownership without making the caller enter a potentially blocked JNI cancel. */
internal class NativeCancellationGate {
    @Volatile private var pending: Thread? = null
    @Volatile private var exclusive = false

    @Synchronized fun request(action: () -> (() -> Unit)?) {
        // An idle close/telemetry call already owns the handle. The caller has set its stop
        // flag; never queue a stale cancel behind that call or block the watchdog on its mutex.
        if (pending?.isAlive == true || exclusive) return
        val cancel = action() ?: return
        pending = Thread({ runCatching(cancel) }, "mca-litert-native-cancel").apply { isDaemon = true }
        pending!!.start()
    }

    fun isPending(): Boolean = pending?.isAlive == true || exclusive

    fun await(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1) * 1_000_000L
        while (isPending()) {
            if (pending === Thread.currentThread()) return false
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return false
            val task = pending
            if (task?.isAlive == true) task.join(remaining.coerceAtLeast(1))
            else Thread.sleep(minOf(remaining, 5L))
        }
        return true
    }

    fun whenIdle(action: () -> Unit): Boolean {
        synchronized(this) {
            if (pending?.isAlive == true || exclusive) return false
            exclusive = true
        }
        return try { action(); true } finally { synchronized(this) { exclusive = false } }
    }
}

package com.muyuchat.core.engine.contract

/**
 * Value class representing the monotonic lifecycle epoch of the native inference runtime.
 *
 * Incremented whenever the native runtime host, dynamic library loader, or device context
 * is restarted or reloaded. Any incoming callback or command carrying an older epoch is
 * treated as stale and immediately dropped or rejected.
 */
@JvmInline
value class RuntimeEpoch(val value: Long) : Comparable<RuntimeEpoch> {
    init {
        require(value >= 0L) { "RuntimeEpoch must be non-negative: $value" }
    }

    /**
     * True if this is the zero initial epoch before any restart.
     */
    val isInitial: Boolean
        get() = value == 0L

    /**
     * Advances to the next monotonic epoch upon backend restart or recovery.
     */
    fun next(): RuntimeEpoch = RuntimeEpoch(value + 1L)

    /**
     * Checks whether this epoch is stale relative to the active target epoch.
     */
    fun isStale(activeEpoch: RuntimeEpoch): Boolean = value < activeEpoch.value

    /**
     * Checks whether this epoch matches the active epoch.
     */
    fun matches(activeEpoch: RuntimeEpoch): Boolean = value == activeEpoch.value

    override fun compareTo(other: RuntimeEpoch): Int = value.compareTo(other.value)

    override fun toString(): String = "Epoch($value)"

    companion object {
        val INITIAL = RuntimeEpoch(0L)

        fun of(epoch: Long): RuntimeEpoch = RuntimeEpoch(epoch)
    }
}

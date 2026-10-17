package com.muyuchat.core.engine.contract

/**
 * Value class representing the monotonic generation of an engine session context.
 *
 * Incremented whenever a session is reset, context is cleared, or session parameters
 * are fundamentally reconfigured. Protects streaming inference pipelines against
 * delayed tokens originating from a prior generation of the same session ID.
 */
@JvmInline
value class SessionGeneration(val value: Long) : Comparable<SessionGeneration> {
    init {
        require(value >= 0L) { "SessionGeneration must be non-negative: $value" }
    }

    /**
     * True if this is the zero initial generation.
     */
    val isInitial: Boolean
        get() = value == 0L

    /**
     * Advances to the next monotonic generation upon session reset or invalidation.
     */
    fun next(): SessionGeneration = SessionGeneration(value + 1L)

    /**
     * Checks whether this generation is stale relative to the active target generation.
     */
    fun isStale(activeGeneration: SessionGeneration): Boolean = value < activeGeneration.value

    /**
     * Checks whether this generation matches the active target generation.
     */
    fun matches(activeGeneration: SessionGeneration): Boolean = value == activeGeneration.value

    override fun compareTo(other: SessionGeneration): Int = value.compareTo(other.value)

    override fun toString(): String = "Generation($value)"

    companion object {
        val INITIAL = SessionGeneration(0L)

        fun of(generation: Long): SessionGeneration = SessionGeneration(generation)
    }
}

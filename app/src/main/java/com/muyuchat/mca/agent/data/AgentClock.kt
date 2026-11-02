package com.muyuchat.mca.agent.data

/** Monotonic-enough wall clock seam used only for persisted timestamps (never for TTL decisions). */
fun interface AgentClock {
    fun nowMillis(): Long

    companion object {
        fun system(): AgentClock = AgentClock { System.currentTimeMillis() }
    }
}

package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.runtime.auth.DshLaunchEntry
import com.muyuchat.core.dsh.runtime.log.DshOutputRing
import java.io.File

/** Outcome of a stop request. [fenceRequired] means the process tree survived SIGKILL. */
data class DshStopOutcome(
    val stopped: Boolean,
    val escalated: Boolean,
    val fenceRequired: Boolean,
    val detail: String,
    /**
     * Every PID this stop attempted to settle: the root, descendants captured before SIGTERM,
     * descendants re-scanned before SIGKILL, and same-UID orphan matches that still referenced
     * the verified payload / native host. Empty means "not reported" (e.g. no PID available).
     */
    val trackedPids: List<Int> = emptyList(),
    /**
     * PIDs still alive after the stop attempt. Non-empty forbids a STOPPED claim; the owner keeps
     * these as the stop fence and refuses to launch a second host until every one is gone.
     */
    val survivingPids: List<Int> = emptyList()
)

/**
 * Host-process seam for the official Node Web profile.
 *
 * One instance owns exactly one Node process generation. It captures stdout/stderr through a
 * bounded redacted ring buffer, exposes the parsed authenticated launch entry only in memory,
 * and stops the whole process tree (Node spawns tool children) with SIGTERM then SIGKILL.
 */
interface DshNodeHost {
    val isAlive: Boolean
    val exitCode: Int?
    val processId: Int?

    /** Bounded, redacted output capture (also used to build failure diagnostics). */
    val output: DshOutputRing

    fun start(
        executable: DshNodeExecutable,
        arguments: List<String>,
        environment: Map<String, String>,
        workingDir: File
    )

    /**
     * Waits for the official `dsh web: ...` launch line. Returns null when the timeout elapses,
     * the process exits, or [shouldAbort] becomes true.
     */
    fun awaitLaunchEntry(timeoutMillis: Long, shouldAbort: () -> Boolean): DshLaunchEntry?

    /** SIGTERM -> wait [graceMillis] -> SIGKILL; reports whether a stop fence is required. */
    fun stop(graceMillis: Long, killMillis: Long): DshStopOutcome

    /** Bounded diagnostics tail (already redacted). */
    fun tail(lines: Int): String
}

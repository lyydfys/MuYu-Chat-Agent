package com.muyuchat.core.dsh.runtime.process

import java.io.File

/**
 * Read/signal seam over `/proc` for job processes.
 *
 * Every method is fail-closed: `null` means the fact could not be read, never "no match". The
 * interface exists so census/recovery policy (polling, identity checks, SIGTERM/SIGKILL order)
 * is JVM-testable without real processes; production delegates to [DshProcessTree].
 *
 * All reads are same-UID by construction: `/proc/<pid>/cmdline` and `/proc/<pid>/environ` are
 * unreadable for another UID, so an unreadable value also proves the process is not this
 * installation's child.
 */
internal interface DshJobProcessSource {
    fun descendantsOf(rootPid: Int): List<Int>

    fun isAlive(pid: Int): Boolean

    /** Field 22 (`starttime`) of `/proc/<pid>/stat`; the PID-reuse guard. */
    fun startTime(pid: Int): Long?

    /** Raw `/proc/<pid>/cmdline` bytes; recovery compares its SHA-256 with the ledger. */
    fun cmdline(pid: Int): ByteArray?

    /** One entry of `/proc/<pid>/environ`, or null when absent/unreadable. */
    fun environmentValue(pid: Int, name: String): String?

    fun signal(pid: Int, signal: Int): Boolean

    /** Children first, then the root; returns the number of delivered signals. */
    fun signalTree(rootPid: Int, signal: Int): Int
}

/**
 * Parses field 22 of `/proc/<pid>/stat`. `comm` can contain spaces and parentheses, so fields are
 * read after the last `)`: index 0 is the state (field 3), index 19 is `starttime` (field 22).
 */
internal fun parseProcStartTime(stat: String): Long? {
    val close = stat.lastIndexOf(')')
    if (close < 0) return null
    val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
    if (fields.size <= START_TIME_FIELD_INDEX) return null
    return fields[START_TIME_FIELD_INDEX].toLongOrNull()
}

/** Exact `NAME=value` lookup over NUL-separated `/proc/<pid>/environ` bytes. */
internal fun parseProcEnvironmentValue(bytes: ByteArray, name: String): String? {
    val prefix = "$name="
    var start = 0
    var index = 0
    while (index <= bytes.size) {
        if (index == bytes.size || bytes[index] == 0.toByte()) {
            if (index > start) {
                val entry = String(bytes, start, index - start, Charsets.UTF_8)
                if (entry.startsWith(prefix)) return entry.substring(prefix.length)
            }
            start = index + 1
        }
        index++
    }
    return null
}

/** Production source: the proven [DshProcessTree] primitives plus bounded `/proc` reads. */
internal class DshProcJobProcessSource : DshJobProcessSource {

    override fun descendantsOf(rootPid: Int): List<Int> =
        if (rootPid <= 0) emptyList() else DshProcessTree.descendantsOf(rootPid)

    override fun isAlive(pid: Int): Boolean = pid > 0 && DshProcessTree.isAlive(pid)

    override fun startTime(pid: Int): Long? {
        if (pid <= 0) return null
        val text = readCapped(File("/proc/$pid/stat"), MAX_STAT_BYTES)?.toString(Charsets.UTF_8)
            ?: return null
        return parseProcStartTime(text)
    }

    override fun cmdline(pid: Int): ByteArray? =
        if (pid <= 0) null else readCapped(File("/proc/$pid/cmdline"), MAX_CMDLINE_BYTES)

    override fun environmentValue(pid: Int, name: String): String? {
        if (pid <= 0 || name.isBlank()) return null
        val bytes = readCapped(File("/proc/$pid/environ"), MAX_ENVIRON_BYTES) ?: return null
        return parseProcEnvironmentValue(bytes, name)
    }

    override fun signal(pid: Int, signal: Int): Boolean =
        pid > 0 && DshProcessTree.signal(pid, signal)

    override fun signalTree(rootPid: Int, signal: Int): Int =
        if (rootPid <= 0) 0 else DshProcessTree.signalTree(rootPid, signal)

    private fun readCapped(file: File, maxBytes: Int): ByteArray? = try {
        val bytes = file.readBytes()
        if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
    } catch (t: Throwable) {
        null
    }

    companion object {
        const val MAX_STAT_BYTES = 4 * 1024
        const val MAX_CMDLINE_BYTES = 8 * 1024
        const val MAX_ENVIRON_BYTES = 64 * 1024
    }
}

private const val START_TIME_FIELD_INDEX = 19

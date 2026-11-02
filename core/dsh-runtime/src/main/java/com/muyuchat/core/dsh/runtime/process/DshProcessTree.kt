package com.muyuchat.core.dsh.runtime.process

import java.io.File

/**
 * Pure parser for `/proc/<pid>/stat` parent-pid extraction.
 *
 * The `comm` field is free-form and can itself contain spaces and parentheses, so the state and
 * ppid fields are read after the last `)` (kernel format: `pid (comm) state ppid ...`).
 */
object DshProcStat {
    fun parsePpid(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val rest = stat.substring(close + 1).trim()
        if (rest.isEmpty()) return null
        val fields = rest.split(Regex("\\s+"))
        if (fields.size < 2) return null
        return fields[1].toIntOrNull()
    }

    fun childPids(statContents: Map<Int, String>, rootPid: Int): List<Int> {
        val childrenByParent = HashMap<Int, MutableList<Int>>()
        for ((pid, stat) in statContents) {
            val ppid = parsePpid(stat) ?: continue
            childrenByParent.getOrPut(ppid) { ArrayList() }.add(pid)
        }
        val out = ArrayList<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(rootPid)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in childrenByParent[current].orEmpty()) {
                if (out.add(child)) queue.add(child)
            }
        }
        return out
    }
}

/**
 * Process-tree control for the host child. Node spawns tool subprocesses (shell, git, plugin
 * commands); stopping only the direct child would leave orphans holding ports, locks or writes.
 */
object DshProcessTree {
    const val SIGTERM = 15
    const val SIGKILL = 9

    fun descendantsOf(rootPid: Int): List<Int> {
        val stats = HashMap<Int, String>()
        val proc = File("/proc")
        val entries = proc.list() ?: return emptyList()
        for (name in entries) {
            val pid = name.toIntOrNull() ?: continue
            val stat = File(proc, "$name/stat")
            val text = try {
                stat.readText()
            } catch (t: Throwable) {
                continue
            }
            stats[pid] = text
        }
        return DshProcStat.childPids(stats, rootPid)
    }

    fun signal(pid: Int, signal: Int): Boolean = try {
        android.os.Process.sendSignal(pid, signal)
        true
    } catch (t: Throwable) {
        false
    }

    fun signalTree(rootPid: Int, signal: Int): Int {
        var count = 0
        // Children first, then the parent, so a Node shutdown handler cannot spawn replacements.
        for (pid in descendantsOf(rootPid)) {
            if (signal(pid, signal)) count++
        }
        if (signal(rootPid, signal)) count++
        return count
    }

    fun isAlive(pid: Int): Boolean = File("/proc/$pid").exists()

    fun anyAlive(pids: Collection<Int>): List<Int> = pids.filter { isAlive(it) }.sorted()

    /**
     * Same-UID processes that still reference one of [needles] in their `/proc/<pid>/cmdline`
     * or `/proc/<pid>/exe`. Used only after a host stop to catch children that were reparented
     * when the root exited.
     *
     * Safety bounds: the caller's own UID is required, the scan is capped at [maxScannedPids]
     * process entries and [maxMatches] results, `cmdline` reading is capped at
     * [MAX_CMDLINE_BYTES], and both this process and [excludePids] are skipped. Needles must be
     * absolute verified-payload / nativeLibraryDir paths provided by the host, so an unrelated
     * app process can never match.
     */
    /**
     * Same-UID processes that still reference one of [needles] in their `/proc/<pid>/cmdline`
     * or `/proc/<pid>/exe`.
     *
     * **null means the scan is unavailable/incomplete and must be treated as fail-closed**:
     * the caller cannot prove the host tree is gone, so it must not claim STOPPED and must not
     * start another host. An empty list is the only "scan succeeded, nothing matched" result.
     *
     * Safety bounds: the caller's own UID is required, the scan is capped at [maxScannedPids]
     * process entries and [maxMatches] results, `cmdline` reading is capped at
     * [MAX_CMDLINE_BYTES], and both this process and [excludePids] are skipped. Needles must be
     * absolute verified-payload / nativeLibraryDir paths provided by the host, so an unrelated
     * app process can never match.
     */
    fun matchingSameUid(
        needles: Collection<String>,
        excludePids: Collection<Int> = emptyList(),
        maxScannedPids: Int = MAX_SCANNED_PIDS,
        maxMatches: Int = MAX_MATCHES
    ): List<Int>? {
        val cleaned = needles.map { it.trim() }.filter { it.length > 1 && it.startsWith("/") }.distinct()
        if (cleaned.isEmpty()) return null
        val ownUid = try {
            android.os.Process.myUid()
        } catch (t: Throwable) {
            return null
        }
        val selfPid = try {
            android.os.Process.myPid()
        } catch (t: Throwable) {
            -1
        }
        val excluded = excludePids.toSet()
        val matches = ArrayList<Int>()
        val names = File("/proc").list() ?: return null
        var scanned = 0
        var truncated = false
        for (name in names) {
            if (scanned >= maxScannedPids || matches.size >= maxMatches) {
                truncated = true
                break
            }
            val pid = name.toIntOrNull() ?: continue
            scanned++
            if (pid == selfPid || pid in excluded) continue
            val uid = uidOf(pid)
            if (uid == null) {
                // Raced with process exit -> harmless skip; still alive but unreadable -> fail closed.
                if (isAlive(pid)) return null
                continue
            }
            if (uid != ownUid) continue
            val cmdline = cmdlineOf(pid)
            if (cmdline == null) {
                if (isAlive(pid)) return null
                continue
            }
            val exe = exeOf(pid) ?: ""
            if (matchesNeedle(cmdline, cleaned) || matchesNeedle(exe, cleaned)) {
                matches.add(pid)
            }
        }
        // Hitting either bound means the picture is incomplete; never return a partial allow.
        if (truncated) return null
        return matches.sorted()
    }

    private fun matchesNeedle(text: String, needles: List<String>): Boolean =
        text.isNotEmpty() && needles.any { needle -> text.contains(needle) }

    /** First field of `/proc/<pid>/status` `Uid:` (real UID). */
    private fun uidOf(pid: Int): Int? = try {
        val line = File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }
        } ?: return null
        line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
    } catch (t: Throwable) {
        null
    }

    /** null means the cmdline could not be read; the caller decides whether the pid is still alive. */
    private fun cmdlineOf(pid: Int): String? = try {
        var bytes = File("/proc/$pid/cmdline").readBytes()
        if (bytes.size > MAX_CMDLINE_BYTES) bytes = bytes.copyOf(MAX_CMDLINE_BYTES)
        bytes.toString(Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }

    /** Best-effort resolved executable path; a read failure alone does not fail the scan. */
    private fun exeOf(pid: Int): String? = try {
        File("/proc/$pid/exe").canonicalPath
    } catch (t: Throwable) {
        null
    }

    const val MAX_CMDLINE_BYTES = 8 * 1024
    const val MAX_SCANNED_PIDS = 4_096
    const val MAX_MATCHES = 64
}

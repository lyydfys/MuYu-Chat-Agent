package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.runtime.auth.DshAuthEntryParser
import com.muyuchat.core.dsh.runtime.auth.DshLaunchEntry
import com.muyuchat.core.dsh.runtime.log.DshOutputRing
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Production [DshNodeHost]: starts the packaged Node ELF, captures merged stdout/stderr into a
 * bounded redacted ring, parses the official readiness line in memory, and stops the whole
 * process tree.
 *
 * The launch token never leaves this object except through [awaitLaunchEntry] and is never
 * written to logs, disk or notifications.
 */
class AndroidDshNodeHost(
    ringCapacity: Int = 400,
    maxLineChars: Int = 4_096,
    private val clock: () -> Long = System::currentTimeMillis
) : DshNodeHost {

    override val output = DshOutputRing(ringCapacity, maxLineChars)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var pid: Int? = null

    @Volatile
    private var exit: Int? = null

    @Volatile
    private var launchEntry: DshLaunchEntry? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var monitorThread: Thread? = null

    /**
     * Absolute paths that identify this host generation for the reparented-child scan: the
     * verified payload root and the native Node executable. Derived only from the launch argv.
     */
    @Volatile
    private var scanNeedles: List<String> = emptyList()
    override val isAlive: Boolean
        get() = process?.isAlive == true

    override val exitCode: Int?
        get() = exit

    override val processId: Int?
        get() = pid

    override fun start(
        executable: DshNodeExecutable,
        arguments: List<String>,
        environment: Map<String, String>,
        workingDir: File
    ) {
        check(process == null) { "dsh: node host already started" }
        launchEntry = null
        exit = null
        val kernelEntry = arguments.firstOrNull { it.endsWith("/lib/bin.js") }
        val payloadRoot = kernelEntry
            ?.substringBefore("/dshroot/")
            ?.takeIf { root -> root != kernelEntry && root.startsWith("/") }
        scanNeedles = listOfNotNull(
            payloadRoot,
            executable.argvPrefix.lastOrNull()?.takeIf { it.startsWith("/") }
        ).distinct()
        val started = newProcessBuilder(arguments, environment, workingDir).start()
        process = started
        pid = pidOf(started)
        output.add("dsh node started pid=${pid ?: "?"} strategy=${executable.strategy}")

        readerThread = Thread({ pump(started) }, "dsh-node-output").apply {
            isDaemon = true
            start()
        }
        monitorThread = Thread({ awaitExit(started) }, "dsh-node-exit").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Builds the child-process launcher. The caller supplies the isolated user workspace as the
     * working directory; the verified payload tree is only an asset/tool source, never the cwd.
     * Kept internal so a JVM test can prove `ProcessBuilder.directory` and the isolated
     * environment without spawning the Android Node ELF.
     */
    internal fun newProcessBuilder(
        arguments: List<String>,
        environment: Map<String, String>,
        workingDir: File
    ): ProcessBuilder {
        val builder = ProcessBuilder(arguments)
        builder.redirectErrorStream(true)
        builder.directory(workingDir)
        builder.environment().putAll(environment)
        return builder
    }

    private fun pump(started: Process) {
        try {
            BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    output.add(line)
                    if (launchEntry == null) {
                        val parsed = DshAuthEntryParser.parseLine(line)
                        if (parsed != null) {
                            launchEntry = parsed
                            output.add("dsh: authenticated launch entry captured for ${parsed.origin}")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            output.add("dsh node output reader ended: ${t.javaClass.simpleName}")
        }
    }

    private fun awaitExit(started: Process) {
        val code = try {
            started.waitFor()
        } catch (t: Throwable) {
            Int.MIN_VALUE
        }
        exit = code
        output.add("dsh node exited code=$code")
    }

    override fun awaitLaunchEntry(timeoutMillis: Long, shouldAbort: () -> Boolean): DshLaunchEntry? {
        val deadline = clock() + timeoutMillis
        while (clock() < deadline) {
            launchEntry?.let { return it }
            if (exit != null || shouldAbort()) return null
            try {
                Thread.sleep(100)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return launchEntry
    }

    override fun stop(graceMillis: Long, killMillis: Long): DshStopOutcome {
        val started = process ?: return DshStopOutcome(true, false, false, "no process")
        val needles = scanNeedles
        val rootPid = pid
        if (rootPid == null) {
            started.destroy()
            val stopped = started.waitFor(graceMillis, TimeUnit.MILLISECONDS)
            val orphans = orphanMatches(needles)
            val survivors = if (stopped) aliveOf(orphans.orEmpty()) else orphans.orEmpty()
            // A null scan means the surviving set cannot be verified: never claim STOPPED.
            val verified = orphans != null
            val settled = stopped && verified && survivors.isEmpty()
            return DshStopOutcome(
                stopped = settled,
                escalated = false,
                fenceRequired = !settled,
                detail = when {
                    !stopped -> "pid unavailable"
                    orphans == null -> "pid unavailable; orphan scan unavailable"
                    survivors.isNotEmpty() -> "pid unavailable; orphan pids=$survivors"
                    else -> "exited"
                },
                trackedPids = orphans.orEmpty(),
                survivingPids = survivors
            )
        }

        val descendants = DshProcessTree.descendantsOf(rootPid)
        val tracked = linkedSetOf<Int>()
        tracked.addAll(descendants)
        tracked.add(rootPid)
        output.add("dsh stop: SIGTERM tree root=$rootPid descendants=${descendants.size}")
        DshProcessTree.signalTree(rootPid, DshProcessTree.SIGTERM)
        runCatching { started.destroy() }
        val graceDeadline = clock() + graceMillis
        while (clock() < graceDeadline && started.isAlive) {
            try {
                started.waitFor(minOf(200L, maxOf(1L, graceDeadline - clock())), TimeUnit.MILLISECONDS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!started.isAlive) {
            val hanging = DshProcessTree.anyAlive(tracked)
            // Children can be reparented to init the moment the root exits; scan the verified
            // payload paths before claiming STOPPED, so an orphan cannot escape the fence.
            val reparented = orphanMatches(needles, tracked)
            if (hanging.isEmpty() && reparented != null && reparented.isEmpty()) {
                output.add("dsh stop: exited after SIGTERM")
                return DshStopOutcome(
                    stopped = true,
                    escalated = false,
                    fenceRequired = false,
                    detail = "SIGTERM",
                    trackedPids = tracked.toList()
                )
            }
            if (reparented == null) {
                output.add("dsh stop: parent exited, orphan scan unavailable; escalating")
            } else {
                tracked.addAll(reparented)
                if (reparented.isNotEmpty()) {
                    output.add("dsh stop: parent exited, ${reparented.size} reparented payload processes remain; escalating")
                }
            }
            if (hanging.isNotEmpty()) {
                output.add("dsh stop: parent exited, ${hanging.size} child processes remain; escalating")
            }
        }

        output.add("dsh stop: escalating to SIGKILL")
        val rescan = DshProcessTree.descendantsOf(rootPid)
        val additional = rescan.filterNot { it in tracked }
        tracked.addAll(additional)
        if (additional.isNotEmpty()) {
            output.add("dsh stop: re-scanned tree root=$rootPid additional=${additional.size}")
        }
        // A child can lose its parent between the scan above and the kill signal; the same-UID
        // payload-path scan catches that case without ever inspecting unrelated apps.
        val lateOrphans = orphanMatches(needles, tracked)
        if (lateOrphans != null) {
            tracked.addAll(lateOrphans)
        } else {
            output.add("dsh stop: pre-SIGKILL orphan scan unavailable")
        }
        for (extra in tracked) {
            if (extra != rootPid) {
                runCatching { DshProcessTree.signal(extra, DshProcessTree.SIGKILL) }
            }
        }
        DshProcessTree.signalTree(rootPid, DshProcessTree.SIGKILL)
        runCatching { started.destroyForcibly() }
        val killDeadline = clock() + killMillis
        while (clock() < killDeadline) {
            if (!started.isAlive && DshProcessTree.anyAlive(tracked).isEmpty()) break
            try {
                Thread.sleep(100)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        // Final bounded re-scan: kill anything reparented during the SIGKILL window, then decide
        // the fence from a fresh alive check (never from the pre-kill snapshot).
        val finalOrphans = orphanMatches(needles, tracked)
        if (finalOrphans != null && finalOrphans.isNotEmpty()) {
            tracked.addAll(finalOrphans)
            output.add("dsh stop: ${finalOrphans.size} reparented payload processes survived SIGKILL; re-killing")
            for (pid in finalOrphans) {
                runCatching { DshProcessTree.signal(pid, DshProcessTree.SIGKILL) }
            }
            val rekillDeadline = clock() + killMillis
            while (clock() < rekillDeadline && DshProcessTree.anyAlive(finalOrphans).isNotEmpty()) {
                try {
                    Thread.sleep(100)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        val verification = orphanMatches(needles, tracked)
        val survivors = aliveOf(tracked + rootPid + verification.orEmpty())
        // A null verification scan means the tree cannot be proven gone: keep the fence up.
        val stillAlive = started.isAlive || survivors.isNotEmpty() || verification == null
        if (stillAlive) {
            output.add(
                "dsh stop: process tree not proven settled; stop fence raised pids=$survivors" +
                    if (verification == null) " (orphan scan unavailable)" else ""
            )
        }
        return DshStopOutcome(
            stopped = !stillAlive,
            escalated = true,
            fenceRequired = stillAlive,
            detail = when {
                verification == null -> "SIGKILL verification unavailable (orphan scan failed) root=$rootPid"
                stillAlive -> "SIGKILL did not settle pids=$survivors root=$rootPid"
                else -> "SIGKILL"
            },
            trackedPids = tracked.toList(),
            survivingPids = survivors
        )
    }

    /**
     * Same-UID payload processes for [needles]. null means the scan itself is unavailable:
     * callers must fail closed and must not treat it as "no orphans".
     */
    private fun orphanMatches(needles: List<String>, exclude: Collection<Int> = emptyList()): List<Int>? =
        if (needles.isEmpty()) null
        else runCatching { DshProcessTree.matchingSameUid(needles, exclude) }.getOrNull()

    private fun aliveOf(pids: Collection<Int>): List<Int> = DshProcessTree.anyAlive(pids)
    override fun tail(lines: Int): String = output.tail(lines)

    /**
     * Android's Process implementation is `java.lang.ProcessManager$ProcessImpl`, which exposes
     * the OS pid through a private `pid` field. Reflection is the only platform-neutral way to
     * reach it; a null pid degrades to stop-without-tree-verification and is reported honestly.
     */
    private fun pidOf(process: Process): Int? = try {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    } catch (t: Throwable) {
        null
    }
}

package com.muyuchat.core.dsh.runtime.process

/**
 * Deterministic `/proc` stand-in for the job census/recovery tests.
 *
 * Processes are modelled as parent links plus the three identity facts the production code reads
 * (start time, command line, generation token). Signalling only changes the fake's own state, so
 * the tests can prove exactly which PIDs received which signal without spawning anything.
 */
internal class FakeJobProcesses : DshJobProcessSource {

    private val processes = LinkedHashMap<Int, FakeJobProcess>()

    /** Every delivered signal as (pid, signal), in delivery order. */
    val signals = mutableListOf<Pair<Int, Int>>()

    fun add(
        pid: Int,
        ppid: Int,
        startTime: Long,
        cmdline: String,
        generationToken: String?,
        alive: Boolean = true,
        ignoresSigterm: Boolean = false,
        ignoresSigkill: Boolean = false
    ): FakeJobProcess {
        val process = FakeJobProcess(
            pid = pid,
            ppid = ppid,
            startTime = startTime,
            cmdline = cmdline,
            generationToken = generationToken,
            alive = alive,
            ignoresSigterm = ignoresSigterm,
            ignoresSigkill = ignoresSigkill
        )
        processes[pid] = process
        return process
    }

    fun process(pid: Int): FakeJobProcess? = processes[pid]

    override fun descendantsOf(rootPid: Int): List<Int> {
        val byParent = processes.values.filter { it.alive }.groupBy { it.ppid }
        val out = ArrayList<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(rootPid)
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            for (child in byParent[parent].orEmpty()) {
                if (out.add(child.pid)) queue.add(child.pid)
            }
        }
        return out
    }

    override fun isAlive(pid: Int): Boolean = processes[pid]?.alive == true

    override fun startTime(pid: Int): Long? = processes[pid]?.takeIf { it.alive }?.startTime

    override fun cmdline(pid: Int): ByteArray? =
        processes[pid]?.takeIf { it.alive }?.cmdline?.toByteArray(Charsets.UTF_8)

    override fun environmentValue(pid: Int, name: String): String? {
        if (name != DshJobIdentity.GENERATION_ENV) return null
        return processes[pid]?.takeIf { it.alive }?.generationToken
    }

    override fun signal(pid: Int, signal: Int): Boolean {
        signals.add(pid to signal)
        val process = processes[pid]?.takeIf { it.alive } ?: return false
        when (signal) {
            DshProcessTree.SIGTERM -> if (!process.ignoresSigterm) process.alive = false
            DshProcessTree.SIGKILL -> if (!process.ignoresSigkill) process.alive = false
        }
        return true
    }

    override fun signalTree(rootPid: Int, signal: Int): Int {
        var delivered = 0
        for (pid in descendantsOf(rootPid).asReversed()) {
            if (signal(pid, signal)) delivered++
        }
        if (signal(rootPid, signal)) delivered++
        return delivered
    }

    fun signalsFor(pid: Int): List<Int> = signals.filter { it.first == pid }.map { it.second }
}

internal class FakeJobProcess(
    val pid: Int,
    var ppid: Int,
    var startTime: Long,
    var cmdline: String,
    var generationToken: String?,
    var alive: Boolean,
    var ignoresSigterm: Boolean = false,
    var ignoresSigkill: Boolean = false
)

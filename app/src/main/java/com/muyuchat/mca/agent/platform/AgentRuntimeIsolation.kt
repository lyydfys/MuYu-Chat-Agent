package com.muyuchat.mca.agent.platform

/**
 * Read-only isolation evidence for one Agent execution.
 *
 * The Agent runs in the app process, so the honest facts to publish are the ones the kernel already
 * knows: which uid the execution runs as, how many file descriptors and TCP sockets the process
 * holds, and how much resident memory it uses. This is a measurement probe, never an enforcement
 * mechanism — it records what the process really is instead of claiming a sandbox.
 *
 * [reader] returns the lines of a proc file, or null when the file cannot be read (a denied or
 * absent proc entry must degrade to "unknown", never to a zero that looks like isolation).
 */
data class AgentRuntimeIsolationSnapshot(
    val uid: Int?,
    val openFileDescriptors: Int?,
    val tcpSockets: Int?,
    val residentKb: Int?
) {
    /** Stable one-line form used in diagnostics; unknown fields stay explicitly unknown. */
    fun describe(): String = buildString {
        append("uid=").append(uid ?: "unknown")
        append(" fds=").append(openFileDescriptors ?: "unknown")
        append(" tcp=").append(tcpSockets ?: "unknown")
        append(" rssKb=").append(residentKb ?: "unknown")
    }
}

fun interface AgentProcFileReader {
    fun read(path: String): List<String>?
}

object SystemAgentProcFileReader : AgentProcFileReader {
    override fun read(path: String): List<String>? = runCatching {
        java.io.File(path).readLines()
    }.getOrNull()
}

/**
 * @param listDirectory returns the entry names of a proc directory, or null when unreadable.
 */
fun interface AgentProcDirectoryLister {
    fun list(path: String): List<String>?
}

object SystemAgentProcDirectoryLister : AgentProcDirectoryLister {
    override fun list(path: String): List<String>? = runCatching {
        java.io.File(path).list()?.toList()
    }.getOrNull()
}

fun readAgentRuntimeIsolation(
    files: AgentProcFileReader = SystemAgentProcFileReader,
    directories: AgentProcDirectoryLister = SystemAgentProcDirectoryLister,
    selfRoot: String = "/proc/self"
): AgentRuntimeIsolationSnapshot {
    val status = files.read("$selfRoot/status")
    return AgentRuntimeIsolationSnapshot(
        uid = status?.firstNotNullOfOrNull { line -> parseUid(line) },
        openFileDescriptors = directories.list("$selfRoot/fd")?.size,
        tcpSockets = files.read("$selfRoot/net/tcp")?.let { lines ->
            // The first line is the column header; every other non-blank line is one socket entry.
            lines.drop(1).count { it.isNotBlank() }
        },
        residentKb = status?.firstNotNullOfOrNull { line -> parseVmRssKb(line) }
    )
}

private fun parseUid(line: String): Int? {
    if (!line.startsWith("Uid:")) return null
    return line.removePrefix("Uid:").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
}

private fun parseVmRssKb(line: String): Int? {
    if (!line.startsWith("VmRSS:")) return null
    return line.removePrefix("VmRSS:").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
}

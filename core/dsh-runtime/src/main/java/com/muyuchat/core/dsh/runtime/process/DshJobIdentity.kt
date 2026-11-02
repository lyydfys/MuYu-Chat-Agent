package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.runtime.log.DshLogRedactor
import java.security.MessageDigest

/**
 * Identity of one tool/job process across a Node host death.
 *
 * A bare PID is never an identity: Android recycles PIDs, so a later generation could signal an
 * unrelated process. The durable record binds the host generation, the PID and the kernel start
 * time (field 22 of `/proc/<pid>/stat`) into the task id, and keeps the generation token, the
 * command-line hash and the readable command as separate re-verification facts.
 *
 * [GENERATION_ENV] is the token every child of one host generation inherits from the Node
 * environment. It is the only ownership fact that survives a host death for a process whose
 * parent is already gone.
 */
object DshJobIdentity {

    /** Environment variable carrying the durable host generation into every job process. */
    const val GENERATION_ENV = "MCA_DSH_GENERATION"

    /** Prefix of every ledger task id. */
    const val TASK_ID_PREFIX = "mca-job"

    /** Upper bound of the readable command kept in the ledger. */
    const val MAX_COMMAND_CHARS = 512

    /** `mca-job-<generation>-<pid>-<startTime>`: one process identity, never a bare PID. */
    fun taskId(generation: Long, pid: Int, startTime: Long): String =
        "$TASK_ID_PREFIX-$generation-$pid-$startTime"

    /** SHA-256 of the raw `/proc/<pid>/cmdline` bytes; recovery must re-read the same hash. */
    fun cmdlineHash(cmdline: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cmdline)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    /**
     * Bounded, one-line rendering of a command line for the console. Auxiliary evidence only:
     * it is never file content, and the shared log redactor removes token/bearer/password
     * shapes before the string can reach the ledger.
     */
    fun readableCommand(cmdline: ByteArray, maxChars: Int = MAX_COMMAND_CHARS): String? {
        if (cmdline.isEmpty()) return null
        val argv = cmdline.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        if (argv.isEmpty()) return null
        val line = DshLogRedactor.redactLine(argv.joinToString(" "), maxChars)
        return line.ifBlank { null }
    }

    private const val HEX = "0123456789abcdef"
}

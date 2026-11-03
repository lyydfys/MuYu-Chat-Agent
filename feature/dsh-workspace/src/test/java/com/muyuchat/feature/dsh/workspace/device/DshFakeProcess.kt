package com.muyuchat.feature.dsh.workspace.device

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Fake child process for channel tests.
 *
 * Nothing here spawns a real process: the channel's launcher seam is injected with this object, so a
 * test can simulate "no su", a denied prompt, a hung probe and a huge output without a device.
 */
internal class FakeDshProcess(
    stdoutText: String = "",
    stderrText: String = "",
    private val exitCode: Int = 0,
    private val exits: Boolean = true
) : DshBackgroundProcess {

    private val stdoutStream = ByteArrayInputStream(stdoutText.toByteArray(Charsets.UTF_8))
    private val stderrStream = ByteArrayInputStream(stderrText.toByteArray(Charsets.UTF_8))

    var destroyed: Boolean = false
        private set

    override fun stdout(): InputStream = stdoutStream

    override fun stderr(): InputStream = stderrStream

    override fun waitFor(timeoutMs: Long): Boolean = exits

    override fun exitCode(): Int = exitCode

    override fun destroy() {
        destroyed = true
    }
}

/** Records every spawned argv so a test can assert that a blocked command never reached `su`. */
internal class RecordingLauncher(
    private val respond: (invocationIndex: Int, argv: List<String>) -> DshBackgroundProcess
) : DshProcessLauncher {

    val invocations: MutableList<List<String>> = mutableListOf()

    override fun start(argv: List<String>): DshBackgroundProcess {
        val index = invocations.size
        invocations += argv
        return respond(index, argv)
    }
}

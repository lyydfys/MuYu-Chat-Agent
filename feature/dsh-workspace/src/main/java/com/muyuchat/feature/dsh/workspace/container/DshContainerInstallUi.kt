package com.muyuchat.feature.dsh.workspace.container

import com.muyuchat.feature.dsh.workspace.DshConsoleTone

/** One rendered line of the install log; the tone decides the colour, never the text. */
data class DshContainerInstallLine(val text: String, val tone: DshConsoleTone)

/**
 * UI state of the six-step install pipeline.
 *
 * This is a pure value: [reduce] and [finished] are the only ways it changes, so the composition
 * never computes step text itself and the mapping is unit-testable without Compose.
 */
data class DshContainerInstallUi(
    val running: Boolean = false,
    /** True once a pipeline run reached a terminal result (success or failure). */
    val done: Boolean = false,
    /** 0..1 while extracting; null while a step has no measurable denominator. */
    val fraction: Float? = null,
    val lines: List<DshContainerInstallLine> = emptyList()
) {
    fun reduce(event: DshContainerInstaller.Event): DshContainerInstallUi = when (event) {
        is DshContainerInstaller.Event.Started -> copy(
            running = true,
            fraction = event.step.speedFraction(),
            lines = lines + DshContainerInstallLine(
                "${event.step.position}/6 ${event.step.title}…",
                DshConsoleTone.NEUTRAL
            )
        )

        is DshContainerInstaller.Event.Progress -> copy(
            running = true,
            fraction = event.fraction ?: fraction,
            lines = if (event.step == DshContainerInstaller.Step.EXTRACT_ROOTFS) {
                lines.dropLastWhile { it.text.startsWith("    ") } +
                    DshContainerInstallLine("    ${event.detail}", DshConsoleTone.NEUTRAL)
            } else {
                lines + DshContainerInstallLine("    ${event.detail}", DshConsoleTone.NEUTRAL)
            }
        )

        is DshContainerInstaller.Event.Succeeded -> copy(
            fraction = if (event.step == DshContainerInstaller.Step.EXTRACT_ROOTFS) 1f else fraction,
            lines = lines + DshContainerInstallLine(
                "✓ ${event.step.position}/6 ${event.step.title}：${event.detail}",
                DshConsoleTone.POSITIVE
            )
        )

        is DshContainerInstaller.Event.Failed -> copy(
            running = false,
            fraction = null,
            lines = lines + DshContainerInstallLine(
                "✗ ${event.step.position}/6 ${event.step.title}：${event.reason}（${event.detail}）",
                DshConsoleTone.NEGATIVE
            )
        )
    }

    fun finished(result: DshContainerInstaller.Result): DshContainerInstallUi {
        val summary = when (result) {
            is DshContainerInstaller.Result.Installed -> DshContainerInstallLine(
                if (result.reused) "容器已就绪（复用已有 rootfs）" else "容器已就绪：${result.probeOutput}",
                DshConsoleTone.POSITIVE
            )
            is DshContainerInstaller.Result.AssetsMissing -> DshContainerInstallLine(
                "本次构建没有打入容器资产：${result.detail}",
                DshConsoleTone.WARNING
            )
            is DshContainerInstaller.Result.Integrity -> DshContainerInstallLine(
                "资产校验失败：${result.detail}",
                DshConsoleTone.NEGATIVE
            )
            is DshContainerInstaller.Result.Failed -> DshContainerInstallLine(
                "安装未完成：${result.reason}",
                DshConsoleTone.NEGATIVE
            )
        }
        return copy(running = false, done = true, fraction = null, lines = lines + summary)
    }

    companion object {
        /** Fresh run: the log is cleared so a retry never mixes with the previous attempt. */
        fun installing(): DshContainerInstallUi =
            DshContainerInstallUi(running = true, done = false, fraction = null, lines = emptyList())
    }
}

private fun DshContainerInstaller.Step.speedFraction(): Float? = when (this) {
    DshContainerInstaller.Step.EXTRACT_ROOTFS -> 0f
    else -> null
}

/**
 * Adapts the module-internal process runner to the `(argv, timeout) -> run` seam the installer
 * takes. Kept separate so the installer itself has no dependency on Android's ProcessBuilder.
 */
object DshContainerProcessRunnerBridge {
    private val runner = DshContainerProcessRunner()

    operator fun invoke(argv: List<String>, timeoutMillis: Long): DshContainerRun =
        runner.run(argv, timeoutMillis)
}

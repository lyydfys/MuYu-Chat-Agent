package com.muyuchat.core.dsh.contract.console

/**
 * One host generation's shell-confinement fact, written by the seeder and read by the console.
 *
 * It is deliberately not a boolean named "sandbox": on Android kernels without Landlock the
 * official `workspace-write` preset is only a permission label. [enforced] is true **only** when
 * the functional probe proved a real kernel write denial; otherwise [advisoryReason] names the
 * closed reason and the console must show high-trust execution, never "workspace isolated".
 */
data class DshShellSandboxStatus(
    val enforced: Boolean,
    val landlockAbi: Int? = null,
    val partial: Boolean = false,
    val advisoryReason: String? = null,
    val updatedAtMillis: Long = 0L
) {
    init {
        require(updatedAtMillis >= 0L) { "shell sandbox status time must be non-negative" }
        require(!enforced || (landlockAbi != null && landlockAbi >= 1)) {
            "an enforced shell sandbox needs a positive Landlock ABI"
        }
        require(enforced || advisoryReason != null) {
            "an advisory shell sandbox needs a typed reason"
        }
        require(advisoryReason == null || isPromptFreeToken(advisoryReason, 64)) {
            "shell sandbox advisory reason must be a short stable token"
        }
    }
}

/** App-facing projection of [DshShellSandboxStatus]; default is "not probed", never "enforced". */
data class DshConsoleShellSandboxProjection(
    val known: Boolean = false,
    val enforced: Boolean = false,
    val landlockAbi: Int? = null,
    val partial: Boolean = false,
    val advisoryReason: String? = null
) {
    init {
        require(!known || enforced || advisoryReason != null) {
            "a known advisory projection needs a reason"
        }
    }
}

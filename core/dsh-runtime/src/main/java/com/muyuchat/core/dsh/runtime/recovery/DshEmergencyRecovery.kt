package com.muyuchat.core.dsh.runtime.recovery

/**
 * What the app can do when the official DSH host will not start.
 *
 * This is the pure decision half of the "独立应急 DSH" recovery lane. It never touches the
 * filesystem and never claims a repair happened: it maps the *facts* of a failed start onto the
 * smallest ordered repair plan, and every step it can propose is one the owner already implements
 * (reinstall the packaged payload, start from the committed closure, or move a poisoned home aside).
 *
 * The ordering matters and is deliberate: cheapest and least destructive first.
 *  1. a poisoned home (config/plugin damage) is moved aside, because re-downloading the payload
 *     cannot fix a home that makes the loader throw;
 *  2. a broken payload tree is reinstalled from the APK, because the home is fine;
 *  3. a committed closure is abandoned last, because it is the user's newest verified tree and the
 *     previous one may already be gone.
 */
enum class DshRecoveryStep {
    /** Move the current home to `home.corrupt-<timestamp>` and seed a fresh one. */
    RESET_HOME,

    /** Reinstall the packaged payload over the installed tree. */
    REINSTALL_PAYLOAD,

    /** Stop serving the committed closure and start from the installed payload instead. */
    FALL_BACK_TO_PACKAGED_PAYLOAD,

    /** Nothing systematic is left: report the concrete failure and let the user export diagnostics. */
    REPORT_ONLY
}

/** Facts a caller must be able to answer before a plan exists. All are reads, never guesses. */
data class DshRecoveryFacts(
    /** The failure code the runtime owner reported for the last start, if it reported one. */
    val failureCode: String? = null,
    /** The packaged payload marker/hashes verified in the APK this build. */
    val packagedPayloadUsable: Boolean = true,
    /** A payload is already installed on disk and passed its own verification. */
    val installedPayloadUsable: Boolean = true,
    /** The DSH home exists and its required seed files are present. */
    val homeSeeded: Boolean = true,
    /** A closure was committed and the owner currently serves it. */
    val closureCommitted: Boolean = false,
    /** The user explicitly asked for an emergency start (console action). */
    val userRequestedRecovery: Boolean = false
)

/** One ordered recovery plan, with the reason it was chosen. */
data class DshRecoveryPlan(
    val steps: List<DshRecoveryStep>,
    /** Human reason shown next to the action; never contains a path or a token. */
    val reason: String
) {
    val actionable: Boolean get() = steps.any { it != DshRecoveryStep.REPORT_ONLY }
}

object DshEmergencyRecovery {

    /** Failure codes whose cause is the home/config rather than the payload bytes. */
    private val HOME_SCOPED_FAILURES = setOf(
        "CONFIG_INVALID",
        "SEED_FAILED",
        "PLUGIN_RESOLVE_FAILED",
        "HOME_UNWRITABLE",
        "TOKEN_WRITE_FAILED"
    )

    /** Failure codes whose cause is the payload tree on disk. */
    private val PAYLOAD_SCOPED_FAILURES = setOf(
        "PAYLOAD_MISSING",
        "PAYLOAD_HASH_MISMATCH",
        "KERNEL_ENTRY_MISSING",
        "NODE_EXEC_FAILED"
    )

    /**
     * The ordered plan for [facts].
     *
     * A plan is never empty: when no systematic repair is available the answer is
     * [DshRecoveryStep.REPORT_ONLY], so the console always has one honest row to show.
     */
    fun plan(facts: DshRecoveryFacts): DshRecoveryPlan {
        val steps = mutableListOf<DshRecoveryStep>()
        val reasons = mutableListOf<String>()
        val code = facts.failureCode

        // 1. A home-scoped failure is repaired by moving the home aside, not by reinstalling bytes
        // that are provably fine.
        val homeBroken = code in HOME_SCOPED_FAILURES || (!facts.homeSeeded && facts.userRequestedRecovery)
        if (homeBroken) {
            steps += DshRecoveryStep.RESET_HOME
            reasons += if (code in HOME_SCOPED_FAILURES) {
                "上次启动失败于配置/插件（$code）：把当前 DSH 家目录移到一旁后重新播种。"
            } else {
                "DSH 家目录缺少必需的播种文件：重新播种一份干净的。"
            }
        }

        // 2. A payload-scoped failure needs the bytes on disk replaced.
        val payloadBroken = code in PAYLOAD_SCOPED_FAILURES || !facts.installedPayloadUsable
        if (payloadBroken && facts.packagedPayloadUsable) {
            steps += DshRecoveryStep.REINSTALL_PAYLOAD
            reasons += "运行时负载在校验中失败：用 APK 内已签名的那份重装。"
        }

        // 3. A committed closure is only abandoned when the packaged payload can take over.
        if (facts.closureCommitted && facts.packagedPayloadUsable && steps.isEmpty()) {
            steps += DshRecoveryStep.FALL_BACK_TO_PACKAGED_PAYLOAD
            reasons += "当前启用的是已提交的闭包负载：先退回 APK 自带的负载再判断问题在谁身上。"
        }

        if (steps.isEmpty()) {
            steps += DshRecoveryStep.REPORT_ONLY
            reasons += when {
                !facts.packagedPayloadUsable ->
                    "本构建没有可用的内置负载：无法在应用内修复，请导出诊断并反馈。"
                code == null ->
                    "没有失败码可依据：先导出诊断，再按具体原因处理。"
                else ->
                    "失败码 $code 不对应任何自动化修复步骤：请导出诊断并反馈。"
            }
        }

        return DshRecoveryPlan(steps = steps, reason = reasons.joinToString(" "))
    }

    /** True when the plan touches user data (the home), so the console must ask for confirmation. */
    fun requiresConfirmation(plan: DshRecoveryPlan): Boolean =
        plan.steps.contains(DshRecoveryStep.RESET_HOME)
}

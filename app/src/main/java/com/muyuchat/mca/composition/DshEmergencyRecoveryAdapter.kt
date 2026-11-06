package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.console.DshEmergencyRecoveryPort
import com.muyuchat.core.dsh.contract.console.DshRecoveryAdvice
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.recovery.DshEmergencyRecovery
import com.muyuchat.core.dsh.runtime.recovery.DshEmergencyRecoveryRunner
import com.muyuchat.core.dsh.runtime.recovery.DshRecoveryFacts
import com.muyuchat.core.dsh.runtime.recovery.DshRecoveryOutcome
import com.muyuchat.core.dsh.runtime.recovery.DshRecoveryPlan
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * App-side effects of the emergency recovery lane.
 *
 * The decision ([DshEmergencyRecovery]) and the ordering ([DshEmergencyRecoveryRunner]) are pure
 * and unit tested; this class only supplies the four real effects and the facts they depend on.
 * Every effect reports failure as a message instead of throwing, so the runner can name the step
 * that stopped.
 *
 * `RESET_HOME` renames the DSH home; it never deletes anything, and the console must confirm it
 * before the runner is called (see `DshEmergencyRecovery.requiresConfirmation`).
 */
class DshEmergencyRecoveryAdapter(
    private val client: DshRuntimeClient,
    private val config: DshRuntimeConfig
) : DshEmergencyRecoveryPort {

    /** Facts read from the live runtime and the filesystem; never guessed. */
    fun facts(): DshRecoveryFacts {
        val snapshot = client.snapshot.value
        val home = config.homeDir
        val profile = File(home, "profiles/web")
        return DshRecoveryFacts(
            failureCode = snapshot.failure?.code?.name,
            packagedPayloadUsable = config.kernelEntry().isFile || config.payloadDir.isDirectory,
            installedPayloadUsable = config.payloadDir.isDirectory && config.kernelEntry().isFile,
            homeSeeded = profile.isDirectory && File(profile, "package.json").isFile,
            closureCommitted = false,
            userRequestedRecovery = true
        )
    }

    fun plan(): DshRecoveryPlan = DshEmergencyRecovery.plan(facts())

    override fun advice(): DshRecoveryAdvice {
        val plan = plan()
        return if (plan.actionable) {
            DshRecoveryAdvice.Actionable(
                steps = plan.steps.map { it.name },
                reason = plan.reason,
                needsConfirmation = DshEmergencyRecovery.requiresConfirmation(plan)
            )
        } else {
            DshRecoveryAdvice.Unavailable(plan.reason)
        }
    }

    override suspend fun run(confirmed: Boolean): String {
        val plan = plan()
        if (DshEmergencyRecovery.requiresConfirmation(plan) && !confirmed) {
            return "需要确认：该计划会移动 DSH 家目录。"
        }
        return run(plan).summary()
    }

    suspend fun run(plan: DshRecoveryPlan = plan()): DshRecoveryOutcome {
        val runner = DshEmergencyRecoveryRunner(
            stopHost = {
                runBlocking { client.stop() }
                if (client.snapshot.value.phase == DshRuntimePhase.STOPPED) null else "运行时未停止"
            },
            resetHome = {
                val home = config.homeDir
                if (!home.exists()) return@DshEmergencyRecoveryRunner null
                val moved = File(home.parentFile, "home.corrupt-" + System.currentTimeMillis())
                if (home.renameTo(moved)) null else "无法移动家目录"
            },
            reinstallPayload = {
                val snapshot = runBlocking { client.ensureStarted() }
                if (snapshot.phase == DshRuntimePhase.READY) {
                    null
                } else {
                    snapshot.failure?.message ?: "重装后仍未就绪"
                }
            },
            fallBackToPackagedPayload = {
                val snapshot = runBlocking { client.ensureStartedFrom(null) }
                if (snapshot.phase == DshRuntimePhase.READY) {
                    null
                } else {
                    snapshot.failure?.message ?: "退回内置负载后仍未就绪"
                }
            }
        )
        return runner.run(plan)
    }
}

package com.muyuchat.mca.agent.platform

import com.muyuchat.mca.agent.workflow.WorkflowIntentDispatchResult
import com.muyuchat.mca.agent.workflow.WorkflowIntentEnvelope
import com.muyuchat.mca.agent.workflow.WorkflowIntentTriggerRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Android-free half of the external workflow trigger entry.
 *
 * `WorkflowIntentReceiver` is only an adapter: it copies `Intent` fields into [handle] and returns.
 * Everything that decides whether a broadcast may start a run — and everything that can fail — lives
 * here, so it is exercised by plain JVM tests with no `android.jar`, no Robolectric and no
 * instrumented device.
 *
 * Safety boundary, in order:
 *  1. [WorkflowIntentReception.classify] drops a broadcast outside `com.muyuchat.mca.action.` before
 *     the workflow package is consulted;
 *  2. the action must be registered and enabled in [WorkflowIntentReceiverDependencies.registry];
 *  3. the registry's strict parser decides on the URI, the extras allowlist and the exact
 *     definition/version, and requires that version to still be published.
 *
 * A refusal is logged and dropped; a dispatch failure is logged inside the launched coroutine. Nothing
 * ever throws out of [handle], because its caller is `BroadcastReceiver.onReceive`, where an escaping
 * exception crashes the host app.
 */
class WorkflowIntentIntake(
    private val dependencies: WorkflowIntentReceiverDependencies
) {
    /**
     * Classifies and, when accepted, schedules the dispatch on [WorkflowIntentReceiverDependencies.scope].
     *
     * @return the launched dispatch job, or null when the broadcast was ignored or refused. A caller
     *   that must not outlive the receiver can join it; `onReceive` deliberately does not, because the
     *   dispatch creates a task and can run for minutes.
     */
    fun handle(
        action: String?,
        dataUri: String?,
        extras: Map<String, String>,
        senderPackage: String?
    ): Job? = try {
        when (val decision = WorkflowIntentReception.classify(
            action = action,
            registry = dependencies.registry,
            dataUri = dataUri,
            extras = extras,
            senderPackage = senderPackage
        )) {
            is WorkflowIntentReceptionResult.Ignored -> {
                dependencies.log(WorkflowIntentLogLevel.DEBUG, "ignored: ${decision.reason}", null)
                null
            }

            is WorkflowIntentReceptionResult.Rejected -> {
                dependencies.log(
                    WorkflowIntentLogLevel.WARN,
                    "refused ${decision.code}: ${decision.safeMessage}",
                    null
                )
                null
            }

            is WorkflowIntentReceptionResult.Accepted -> dispatch(decision.envelope)
        }
    } catch (failure: Throwable) {
        // A hostile broadcast must never be able to crash the host process through this path.
        dependencies.log(WorkflowIntentLogLevel.ERROR, "failed before dispatch", failure)
        null
    }

    private fun dispatch(envelope: WorkflowIntentEnvelope): Job = dependencies.scope.launch {
        try {
            when (val result = dependencies.dispatch(envelope)) {
                is WorkflowIntentDispatchResult.Started -> dependencies.log(
                    WorkflowIntentLogLevel.INFO,
                    "started ${result.request.definitionId} v${result.request.version} " +
                        "run=${result.request.runId.orEmpty()}",
                    null
                )

                is WorkflowIntentDispatchResult.Rejected -> dependencies.log(
                    WorkflowIntentLogLevel.WARN,
                    "rejected ${result.code}: ${result.safeMessage}",
                    null
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            dependencies.log(WorkflowIntentLogLevel.ERROR, "dispatch failed", failure)
        }
    }
}

/** Log severity, kept platform-free so the intake needs no `android.util.Log`. */
enum class WorkflowIntentLogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Everything one received broadcast needs, resolved once per process.
 *
 * [registry] must be readable synchronously: a cold process has to refuse an unknown action without
 * building the Agent root. [dispatch] is the only member that touches the root, and it is suspend
 * because starting a run awaits the one owner. [log] is injected so this class stays Android-free
 * while production still writes to logcat.
 */
class WorkflowIntentReceiverDependencies(
    val registry: WorkflowIntentTriggerRegistry,
    val scope: CoroutineScope,
    val dispatch: suspend (WorkflowIntentEnvelope) -> WorkflowIntentDispatchResult,
    val log: (WorkflowIntentLogLevel, String, Throwable?) -> Unit
)

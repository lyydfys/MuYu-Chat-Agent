package com.muyuchat.mca.agent.platform

import com.muyuchat.mca.agent.workflow.WorkflowIntentEnvelope
import com.muyuchat.mca.agent.workflow.WorkflowIntentParseResult
import com.muyuchat.mca.agent.workflow.WorkflowIntentRequest
import com.muyuchat.mca.agent.workflow.WorkflowIntentTriggerRegistry

/**
 * Platform-side admission rules for an externally broadcast workflow trigger.
 *
 * Everything here is plain Kotlin on purpose: [classify] is the whole security decision, so it can be
 * unit-tested on the JVM without Robolectric and without an `Intent`. The Android receiver is a thin
 * adapter that copies `action`/`data`/extras into [classify] and logs the typed answer.
 *
 * Safety boundary, in order:
 *  1. the action must exist and start with [ACTION_PREFIX] — an arbitrary system or third-party
 *     broadcast is dropped before the workflow package is consulted at all;
 *  2. the action must be registered and enabled in [WorkflowIntentTriggerRegistry] — a user decision,
 *     never inferred from the intent;
 *  3. the envelope must survive the registry's strict parser: bounded extras, a `mca-workflow:` data
 *     URI only, and an explicit definition/version that matches the registration and is still
 *     published.
 *
 * A refusal never throws and never starts a task.
 */
object WorkflowIntentReception {

    /** Every action MCA is willing to own must live under this prefix. */
    const val ACTION_PREFIX: String = "com.muyuchat.mca.action."

    /** A broadcast carrying more string extras than this is refused before the parser runs. */
    const val MAX_EXTRAS: Int = 16

    /**
     * Classifies one received broadcast.
     *
     * A missing or foreign action is [WorkflowIntentReceptionResult.Ignored] — the broadcast is not
     * addressed to this feature, so it deserves no error path. A MCA-prefixed action that fails a
     * rule is [WorkflowIntentReceptionResult.Rejected], so the log names the concrete rule.
     */
    fun classify(
        action: String?,
        registry: WorkflowIntentTriggerRegistry,
        dataUri: String? = null,
        extras: Map<String, String> = emptyMap(),
        senderPackage: String? = null
    ): WorkflowIntentReceptionResult {
        val trimmed = action?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return WorkflowIntentReceptionResult.Ignored("the broadcast has no action")
        }
        if (!trimmed.startsWith(ACTION_PREFIX)) {
            return WorkflowIntentReceptionResult.Ignored("action '$trimmed' is outside $ACTION_PREFIX")
        }
        if (extras.size > MAX_EXTRAS) {
            return WorkflowIntentReceptionResult.Rejected(
                "WF_INTENT_EXTRA_NOT_ALLOWED",
                "The intent carries too many extras."
            )
        }
        val envelope = WorkflowIntentEnvelope(
            action = trimmed,
            dataUri = dataUri,
            extras = extras,
            senderPackage = senderPackage
        )
        return when (val parsed = registry.parse(envelope)) {
            is WorkflowIntentParseResult.Accepted ->
                WorkflowIntentReceptionResult.Accepted(envelope, parsed.request)

            is WorkflowIntentParseResult.Rejected ->
                WorkflowIntentReceptionResult.Rejected(parsed.code, parsed.safeMessage)
        }
    }
}

sealed interface WorkflowIntentReceptionResult {
    /** Not addressed to this feature; dropped without a user-visible trace. */
    data class Ignored(val reason: String) : WorkflowIntentReceptionResult

    /** Addressed to MCA but refused by a concrete rule; logged and dropped. */
    data class Rejected(val code: String, val safeMessage: String) : WorkflowIntentReceptionResult

    /** Admitted; the caller may dispatch exactly this envelope. */
    data class Accepted(
        val envelope: WorkflowIntentEnvelope,
        val request: WorkflowIntentRequest
    ) : WorkflowIntentReceptionResult
}

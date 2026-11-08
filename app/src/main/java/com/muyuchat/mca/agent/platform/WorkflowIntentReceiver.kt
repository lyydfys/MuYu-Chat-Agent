package com.muyuchat.mca.agent.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.muyuchat.mca.McaApplication
import com.muyuchat.mca.agent.workflow.WorkflowIntentTriggerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Broadcast entry for external workflow triggers.
 *
 * It lives in `agent/platform` because `agent/workflow` is contractually platform-free:
 * `AgentWorkflowSourceContractTest` forbids `android.` and `BroadcastReceiver` there.
 *
 * The receiver itself is only an adapter — it copies `action`/`dataString`/string extras into the
 * Android-free [WorkflowIntentIntake], which owns every decision and every failure path. That is what
 * makes the security boundary testable on the JVM instead of only on a device.
 *
 * `onReceive` runs on the main thread and must return immediately, so an accepted dispatch is
 * launched on the application scope, never awaited. No throwable escapes: an exception out of
 * `onReceive` crashes the host, and a malformed broadcast from a third-party app must not be able to
 * do that.
 */
class WorkflowIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val dependencies = WorkflowIntentPlatform.dependencies(context.applicationContext)
        if (dependencies == null) {
            Log.w(TAG, "workflow intent ignored: the MCA process is unavailable")
            return
        }
        WorkflowIntentIntake(dependencies).handle(
            action = intent.action,
            dataUri = intent.dataString,
            extras = stringExtrasOf(intent),
            senderPackage = senderPackageOf(intent)
        )
    }

    /** Only string extras are forwarded: nothing else has a form the strict parser accepts. */
    private fun stringExtrasOf(intent: Intent): Map<String, String> {
        val bundle = intent.extras ?: return emptyMap()
        val values = LinkedHashMap<String, String>()
        for (key in bundle.keySet()) {
            bundle.getString(key)?.let { values[key] = it }
        }
        return values
    }

    /**
     * `getPackage()` names the sender only for an explicit broadcast; on a plain implicit send it is
     * null and the trigger derives its idempotency key without it.
     */
    private fun senderPackageOf(intent: Intent): String? =
        runCatching { intent.`package` }.getOrNull()

    companion object {
        /** Kept in sync with the `<intent-filter>` in `AndroidManifest.xml`. */
        const val ACTION_RUN_WORKFLOW: String = "com.muyuchat.mca.action.RUN_WORKFLOW"

        private const val TAG = "MCA-WF-INTENT"
    }
}

/**
 * Process-wide holder of the intent trigger table.
 *
 * Where the registry comes from (the question this change had to answer): `WorkflowIntentTriggerRegistry`
 * has no persisted owner anywhere in the repo — `WorkflowIntentTrigger.kt` only defines the class, and
 * `AgentHostAssembly` composes the facade but never a registry. So this application-level singleton is
 * the one table for the process.
 *
 * Its initial table is **empty** on purpose, and that is the whole security model: a broadcast can
 * only name an action somebody registered explicitly through [registry]. Nothing is derived from the
 * manifest, the installed workflows or the recipes, so installing a workflow never makes it
 * externally callable, and the `<intent-filter>` action alone is not authority.
 */
object WorkflowIntentPlatform {

    private val registry = WorkflowIntentTriggerRegistry()

    @Volatile
    private var dependencies: WorkflowIntentReceiverDependencies? = null

    /** The one table for this process. The settings/admin surface registers and revokes here. */
    fun registry(): WorkflowIntentTriggerRegistry = registry

    /**
     * Dependencies for [context], or null outside a real MCA process (a test harness or a foreign
     * context), in which case the receiver logs and returns without touching the workflow code.
     */
    fun dependencies(context: Context?): WorkflowIntentReceiverDependencies? {
        dependencies?.let { return it }
        val app = context as? McaApplication ?: return null
        return synchronized(this) {
            dependencies ?: WorkflowIntentReceiverDependencies(
                registry = registry,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                dispatch = { envelope -> app.processRuntimeOwner.dispatchWorkflowIntent(envelope) },
                log = { level, message, failure -> logTo(level, message, failure) }
            ).also { dependencies = it }
        }
    }

    private fun logTo(level: WorkflowIntentLogLevel, message: String, failure: Throwable?) {
        val text = "workflow intent $message"
        when (level) {
            WorkflowIntentLogLevel.DEBUG -> Log.d(TAG, text)
            WorkflowIntentLogLevel.INFO -> Log.i(TAG, text)
            WorkflowIntentLogLevel.WARN -> Log.w(TAG, text)
            WorkflowIntentLogLevel.ERROR -> Log.e(TAG, text, failure)
        }
    }

    private const val TAG = "MCA-WF-INTENT"
}

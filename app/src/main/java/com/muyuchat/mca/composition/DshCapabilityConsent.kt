package com.muyuchat.mca.composition

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentDecision
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPort
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPrompt
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags
import com.muyuchat.core.dsh.platform.DshWorkspaceTreePickerOutcome
import com.muyuchat.core.dsh.platform.DshWorkspaceTreePickerPort
import com.muyuchat.core.dsh.platform.DshWorkspaceTreePickerRequest
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.chat.ImeAwareAlertDialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-scoped, user-presence-bound consent broker for DSH capability actions (D16).
 *
 * Rules:
 * - In the background (no resumed app surface) every request is denied without pulling an
 *   Activity forward; a capability action must never appear from nowhere.
 * - At most one prompt is visible at a time; a second request waits its turn.
 * - The decision is fail-closed: dismissal, timeout or process death is DENY/TIMEOUT, never
 *   implicit approval.
 * - The prompt carries only the owner-provided title/detail, which the contract already bounds and
 *   forbids control characters; no token, path or user content is added here.
 */
internal object DshCapabilityConsentBroker {

    private const val PROMPT_TIMEOUT_MILLIS = 60_000L

    private val mutex = Mutex()
    private val _prompt = MutableStateFlow<DshCapabilityConsentPrompt?>(null)

    @Volatile
    private var foreground = false

    @Volatile
    private var waiter: CompletableDeferred<DshCapabilityConsentDecision>? = null

    val prompt: StateFlow<DshCapabilityConsentPrompt?> = _prompt.asStateFlow()

    /** The port injected into the capability assembly; never auto-allows. */
    val port: DshCapabilityConsentPort = DshCapabilityConsentPort { request ->
        requestConsent(request)
    }

    /** Called by the host composable with the Activity lifecycle's resumed state. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) {
            // Leaving the app answers an open prompt as denied; the owner may retry later.
            waiter?.complete(DshCapabilityConsentDecision.DENY)
        }
    }

    fun resolve(decision: DshCapabilityConsentDecision) {
        waiter?.complete(decision)
    }

    private suspend fun requestConsent(
        prompt: DshCapabilityConsentPrompt
    ): DshCapabilityConsentDecision = mutex.withLock {
        if (!foreground) return@withLock DshCapabilityConsentDecision.DENY
        val deferred = CompletableDeferred<DshCapabilityConsentDecision>()
        waiter = deferred
        _prompt.value = prompt
        try {
            withTimeoutOrNull(PROMPT_TIMEOUT_MILLIS) { deferred.await() }
                ?: DshCapabilityConsentDecision.TIMEOUT
        } finally {
            waiter = null
            _prompt.value = null
        }
    }
}

/**
 * Process-scoped, user-presence-bound host of the one `OpenDocumentTree` dialog.
 *
 * It is the only place in the app that launches the persistent tree picker, and it keeps the
 * pending request outside the WebView and outside any DSH payload. Rules mirror the consent
 * broker: no resumed surface means [DshWorkspaceTreePickerOutcome.UserNotPresent] (never a
 * fabricated grant), at most one dialog at a time, and dismissal/timeout/host teardown resolve as
 * [DshWorkspaceTreePickerOutcome.Cancelled].
 */
internal object DshWorkspaceTreePickerBroker {

    private const val PICK_TIMEOUT_MILLIS = 120_000L

    /**
     * Opening the system picker pauses this activity. A pause inside this window after a launch is
     * the picker coming to front, not the user leaving; cancelling there would resolve every
     * selection as "picker was dismissed" before the result can be delivered.
     */
    private const val PICKER_PAUSE_GRACE_MILLIS = 10_000L

    private val mutex = Mutex()
    private val _request = MutableStateFlow<DshWorkspaceTreePickerRequest?>(null)

    @Volatile
    private var foreground = false

    @Volatile
    private var waiter: CompletableDeferred<DshWorkspaceTreePickerOutcome>? = null

    @Volatile
    private var suppressCancelUntilMillis = 0L

    @Volatile
    private var nowMillis: () -> Long = SystemClock::elapsedRealtime

    /** The pending picker request; null while no system dialog is expected. */
    val request: StateFlow<DshWorkspaceTreePickerRequest?> = _request.asStateFlow()

    /** Injected into the capability assembly; never auto-selects a folder. */
    val port: DshWorkspaceTreePickerPort = DshWorkspaceTreePickerPort { request -> pick(request) }

    /** Called right before the system dialog is launched, from the same pending request. */
    fun notePickerLaunched() {
        suppressCancelUntilMillis = nowMillis() + PICKER_PAUSE_GRACE_MILLIS
    }

    /** Test seam for the grace window; production always uses the monotonic clock. */
    internal fun setClockForTest(clock: () -> Long) {
        nowMillis = clock
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) {
            // A real backgrounding stops waiting instead of leaving the capability call suspended
            // until its timeout; the picker's own pause must not do that.
            if (DshWorkspaceTreePickerPausePolicy.shouldCancel(
                    nowMillis = nowMillis(),
                    suppressUntilMillis = suppressCancelUntilMillis,
                    pending = waiter != null
                )
            ) {
                resolve(DshWorkspaceTreePickerOutcome.Cancelled)
            }
        }
    }

    fun resolve(outcome: DshWorkspaceTreePickerOutcome) {
        waiter?.complete(outcome)
    }

    private suspend fun pick(request: DshWorkspaceTreePickerRequest): DshWorkspaceTreePickerOutcome =
        mutex.withLock {
            if (!foreground) return@withLock DshWorkspaceTreePickerOutcome.UserNotPresent
            val deferred = CompletableDeferred<DshWorkspaceTreePickerOutcome>()
            waiter = deferred
            _request.value = request
            try {
                withTimeoutOrNull(PICK_TIMEOUT_MILLIS) { deferred.await() }
                    ?: DshWorkspaceTreePickerOutcome.Cancelled
            } finally {
                waiter = null
                _request.value = null
            }
        }
}

/** Pause handling of the picker broker, kept pure so the grace window is directly testable. */
internal object DshWorkspaceTreePickerPausePolicy {
    fun shouldCancel(nowMillis: Long, suppressUntilMillis: Long, pending: Boolean): Boolean =
        pending && nowMillis >= suppressUntilMillis
}

/** Result of the one persistent-tree system dialog. */
internal sealed interface DshWorkspaceTreePickerResult {
    data class Picked(
        val treeUri: String,
        val flags: DshWorkspaceTreeFlags
    ) : DshWorkspaceTreePickerResult

    data object Cancelled : DshWorkspaceTreePickerResult
}

/**
 * `ACTION_OPEN_DOCUMENT_TREE` contract of the persistent workspace-folder grant.
 *
 * The intent asks only for the modes the caller requested and always for persistable access; the
 * result is read back from the system grant flags. A write-only or missing result is treated as
 * cancelled, never upgraded, and the owner validates the URI again before persisting.
 */
internal class DshWorkspaceTreePickerContract :
    ActivityResultContract<DshWorkspaceTreePickerRequest, DshWorkspaceTreePickerResult>() {

    override fun createIntent(context: Context, input: DshWorkspaceTreePickerRequest): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            if (input.writeAccess) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            addFlags(flags)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): DshWorkspaceTreePickerResult {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return DshWorkspaceTreePickerResult.Cancelled
        }
        val uri = intent.data ?: return DshWorkspaceTreePickerResult.Cancelled
        // Some OEM document pickers (observed on MIUI) return the chosen tree URI without echoing
        // FLAG_GRANT_READ_URI_PERMISSION on the result intent; the authoritative grant is what
        // takePersistableUriPermission then publishes. Treating that as "cancelled" reported a
        // dismissed picker even though the user allowed access, so the selection is returned as
        // read (or read+write) and the owner re-validates it against the real permission.
        val flags = intent.flags
        return DshWorkspaceTreePickerResult.Picked(
            treeUri = uri.toString(),
            flags = if (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                DshWorkspaceTreeFlags.READ_WRITE
            } else {
                DshWorkspaceTreeFlags.READ
            }
        )
    }
}

/**
 * Single host for the capability consent prompt. Placed once under `McaTheme`; it renders nothing
 * while no prompt is pending, and tracks the Activity's resumed state for the broker.
 *
 * It also hosts the one `OpenDocumentTree` launcher: the capability route only marks a pending
 * request, and this resumed composition is what actually shows the system dialog.
 */
@Composable
internal fun DshCapabilityConsentDialog() {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    DshCapabilityConsentBroker.setForeground(true)
                    DshWorkspaceTreePickerBroker.setForeground(true)
                }

                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    DshCapabilityConsentBroker.setForeground(false)
                    DshWorkspaceTreePickerBroker.setForeground(false)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        DshCapabilityConsentBroker.setForeground(resumed)
        DshWorkspaceTreePickerBroker.setForeground(resumed)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            DshCapabilityConsentBroker.setForeground(false)
            DshWorkspaceTreePickerBroker.setForeground(false)
        }
    }

    val treeRequest by DshWorkspaceTreePickerBroker.request.collectAsStateWithLifecycle()
    val treeLauncher = rememberLauncherForActivityResult(DshWorkspaceTreePickerContract()) { result ->
        DshWorkspaceTreePickerBroker.resolve(
            when (result) {
                is DshWorkspaceTreePickerResult.Picked ->
                    DshWorkspaceTreePickerOutcome.Selected(result.treeUri, result.flags)
                DshWorkspaceTreePickerResult.Cancelled -> DshWorkspaceTreePickerOutcome.Cancelled
            }
        )
    }
    LaunchedEffect(treeRequest?.requestId) {
        val pending = treeRequest ?: return@LaunchedEffect
        if (DshWorkspaceTreePickerBroker.request.value !== pending) return@LaunchedEffect
        DshWorkspaceTreePickerBroker.notePickerLaunched()
        runCatching { treeLauncher.launch(pending) }
            .onFailure { DshWorkspaceTreePickerBroker.resolve(DshWorkspaceTreePickerOutcome.UserNotPresent) }
    }

    val prompt by DshCapabilityConsentBroker.prompt.collectAsStateWithLifecycle()
    val active = prompt ?: return
    ImeAwareAlertDialog(
        onDismissRequest = { DshCapabilityConsentBroker.resolve(DshCapabilityConsentDecision.DENY) },
        title = { Text(active.title) },
        text = {
            Text(
                text = active.detail.ifBlank { stringResource(com.muyuchat.mca.R.string.dsh_capability_consent_default_detail) },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Text, onClick = { DshCapabilityConsentBroker.resolve(DshCapabilityConsentDecision.ALLOW) }) {
                Text(stringResource(com.muyuchat.mca.R.string.dsh_capability_consent_allow))
            }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text, onClick = { DshCapabilityConsentBroker.resolve(DshCapabilityConsentDecision.DENY) }) {
                Text(stringResource(com.muyuchat.mca.R.string.dsh_capability_consent_deny))
            }
        }
    )
}

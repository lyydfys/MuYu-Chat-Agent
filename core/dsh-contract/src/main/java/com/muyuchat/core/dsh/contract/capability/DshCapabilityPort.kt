package com.muyuchat.core.dsh.contract.capability

/**
 * The only typed surface a DSH capability server may call.
 *
 * Implementations live in the app/platform layer and own Activity-result registration,
 * `NotificationManager`, `AccessibilityService`, SAF and the workspace staging directory. This
 * interface deliberately imports no Android type, no `Uri`, no `Intent` and no app UI type.
 *
 * Authorization is a separate step: the server first validates the `dshc_` bearer token and the
 * `X-MCA-DSH-Generation` epoch with [DshCapabilityAuthority], then calls this port. The port is
 * therefore post-authorization and never receives a credential.
 *
 * Idempotency: [DshCapabilityRequest.requestId] is executed at most once per generation. A
 * duplicate after a terminal result may replay that result. A duplicate while the original is
 * pending returns `REQUEST_IN_PROGRESS`. A duplicate whose outcome is unknown returns
 * `OUTCOME_UNKNOWN` and must never be re-executed, because replay could post a second
 * notification, repeat a device action or overwrite a user file.
 */
interface DshCapabilityPort {
    /** Current usability of one capability. DSH registers a tool only when the state is GRANTED. */
    suspend fun status(capability: DshCapability): DshCapabilityStatus

    /** Shows the system picker and stages the selected documents for Node tools. */
    suspend fun pickFiles(request: DshPickFilesRequest): DshPickFilesResult

    /** Shows the system create-document picker and writes a workspace file or inline payload. */
    suspend fun saveFile(request: DshSaveFileRequest): DshSaveFileResult

    /** Posts one bounded notification. Duplicate request ids must not post twice. */
    suspend fun notify(request: DshNotifyRequest): DshNotifyResult

    /** Performs one typed, user-consented device action with target verification. */
    suspend fun performDeviceAction(request: DshDeviceActionRequest): DshDeviceActionResult
}

/**
 * Cancellation is a separate port because a cancel call must be servable while another
 * capability call is still suspended on user interaction. Production implementations normally
 * implement this interface on the same app-level owner as [DshCapabilityPort].
 */
fun interface DshCapabilityCancellationPort {
    suspend fun cancel(request: DshCapabilityCancelRequest): DshCapabilityCancelResult
}

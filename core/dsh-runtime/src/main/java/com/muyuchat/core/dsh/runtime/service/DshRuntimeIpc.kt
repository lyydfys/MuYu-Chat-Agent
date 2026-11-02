package com.muyuchat.core.dsh.runtime.service

import android.os.Bundle
import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshTrialHealthSnapshot
import com.muyuchat.core.dsh.contract.DshTrialPhase
import com.muyuchat.core.dsh.contract.DshTrialSnapshot
import com.muyuchat.core.dsh.contract.capability.DshCapabilityBridgeEndpoint
import com.muyuchat.core.dsh.runtime.state.DshApplyRequest
import com.muyuchat.core.dsh.runtime.state.DshApplyResult
import com.muyuchat.core.dsh.runtime.state.DshApplyStatus
import com.muyuchat.core.dsh.runtime.state.DshBindingId
import com.muyuchat.core.dsh.runtime.state.DshHostConfig

/**
 * Messenger protocol between the app-side client and the `:dsh` service process.
 *
 * The authenticated entry travels as a plain Bundle field over Binder (in-memory IPC) and is
 * never turned into an Intent extra, a persisted preference or a log line. Snapshot replies
 * carry only secret-free fields.
 */
object DshRuntimeIpc {
    const val ACTION_START = "com.muyuchat.core.dsh.runtime.action.START"
    const val ACTION_STOP = "com.muyuchat.core.dsh.runtime.action.STOP"

    const val MSG_REGISTER = 1
    const val MSG_ENSURE_STARTED = 2
    const val MSG_RESTART = 3
    const val MSG_STOP = 4
    const val MSG_GET_ENTRY = 5
    const val MSG_RELEASE = 6

    /**
     * One-way app-process -> host report that the owning WebView committed its first visible
     * frame for the generation in [KEY_EPOCH]. There is no reply and no authority: the host
     * records the startup observation only when that epoch is the current READY generation, so a
     * stale, early or duplicated report can never change the snapshot or fabricate readiness.
     */
    const val MSG_REPORT_FIRST_FRAME = 7

    /**
     * App-process -> host binding reconciliation. Unlike [MSG_ENSURE_STARTED] this carries the
     * caller's in-flight observation and an explicit force flag, and the host answers with a typed
     * apply result (unchanged / applied / deferred / rolled back / failed) instead of only a
     * snapshot. The desired binding itself travels in the same gateway/capability/host-build fields
     * the start messages already use.
     */
    const val MSG_APPLY_CONFIG = 8

    /**
     * Isolated closure trial (D24). The trial is a *non-publishing* generation of the same owner,
     * so its messages never carry gateway/capability coordinates and never publish a snapshot push:
     * the app-side `DshTrialHostClient` reads exactly one typed reply per call, and the frozen
     * production projection is never written from a trial reply.
     */
    const val MSG_TRIAL_START = 9
    const val MSG_TRIAL_STOP = 13
    const val MSG_TRIAL_STATUS = 14
    const val MSG_TRIAL_HEALTH = 15

    const val REPLY_SNAPSHOT = 10
    const val REPLY_ENTRY = 11
    const val REPLY_ENTRY_NONE = 12

    /**
     * Dedicated reply id for [MSG_TRIAL_START]/[MSG_TRIAL_STOP]/[MSG_TRIAL_STATUS]/[MSG_TRIAL_HEALTH].
     * Kept distinct from [REPLY_SNAPSHOT] on purpose: no trial reply may ever be mistaken (by a
     * client or a snapshot push) for the production lifecycle projection.
     */
    const val REPLY_TRIAL = 16

    const val KEY_REQUEST_ID = "req"
    const val KEY_PHASE = "phase"
    const val KEY_EPOCH = "epoch"
    const val KEY_ORIGIN = "origin"
    const val KEY_CHANGED_AT = "changedAt"
    const val KEY_FAILURE_PRESENT = "failurePresent"
    const val KEY_FAILURE_CODE = "failureCode"
    const val KEY_FAILURE_MESSAGE = "failureMessage"
    const val KEY_FAILURE_RETRYABLE = "failureRetryable"
    const val KEY_FAILURE_DIAGNOSTICS = "failureDiagnostics"
    const val KEY_ENTRY_URL = "entryUrl"
    const val KEY_GATEWAY_BASE_URL = "gwBaseUrl"
    const val KEY_GATEWAY_BEARER = "gwBearer"
    const val KEY_GATEWAY_CATALOG = "gwCatalog"

    /**
     * App-composition identity for the desired binding. Token-free; a main-process rebuild that
     * keeps the same catalog still reloads the host because this value changes.
     */
    const val KEY_HOST_BUILD = "hostBuild"

    // Binding reconciliation (MSG_APPLY_CONFIG). The request side carries only the caller's
    // observation and intent; the binding fields above carry what the host must run.
    const val KEY_APPLY_FORCE = "applyForce"
    const val KEY_APPLY_IN_FLIGHT = "applyInFlight"
    const val KEY_APPLY_SOURCE = "applySource"

    /**
     * Absolute path of a committed closure `payload` tree the next generation must run instead of
     * the installed payload. Absent means "no instruction"; an empty string asks the owner to
     * return to the installed payload. The owner re-admits the path before it is used.
     */
    const val KEY_APPLY_PAYLOAD_ROOT = "applyPayloadRoot"

    // Typed apply reply. Ids are the token-free digests from DshBindingId; no secret crosses here
    // beyond the per-generation gateway/bridge coordinates the start messages already carried.
    const val KEY_APPLY_STATUS = "applyStatus"
    const val KEY_APPLY_REQUESTED = "applyRequested"
    const val KEY_APPLY_APPLIED = "applyApplied"
    const val KEY_APPLY_RETAINED = "applyRetained"
    const val KEY_APPLY_CHANGE = "applyChange"
    const val KEY_APPLY_TERMINATED = "applyTerminated"
    const val KEY_APPLY_UNOBSERVABLE = "applyUnobservable"
    const val KEY_APPLY_DETAIL = "applyDetail"

    /** Sentinel for "the caller could not observe in-flight work" over an int-only Bundle field. */
    const val IN_FLIGHT_UNOBSERVABLE = -1

    /** Mirrors DshApplyRequest's own bound so a malformed wire label is truncated, not trusted. */
    private const val APPLY_SOURCE_MAX_CHARS = 64

    // Capability bridge coordinates of the same DSH generation (D15). The official mobile tool
    // packages read these as environment variables, so they must reach the Node child of the
    // :dsh process; the token itself is Binder-only and never persisted or logged.
    const val KEY_CAP_APP_PORT = "capAppPort"
    const val KEY_CAP_A11Y_PORT = "capA11yPort"
    const val KEY_CAP_GENERATION = "capGeneration"
    const val KEY_CAP_TOKEN = "capToken"
    const val KEY_CAP_ROOT = "capRoot"
    const val KEY_CAP_SHIZUKU = "capShizuku"
    const val KEY_CAP_SHIZUKU_APP_ID = "capShizukuAppId"
    const val KEY_CAP_SHIZUKU_DEX = "capShizukuDex"
    const val KEY_CAP_APPROVAL_ASK = "capApprovalAsk"

    fun snapshotBundle(snapshot: DshRuntimeSnapshot): Bundle = Bundle().apply {
        putString(KEY_PHASE, snapshot.phase.name)
        putLong(KEY_EPOCH, snapshot.epoch)
        putString(KEY_ORIGIN, snapshot.origin)
        putLong(KEY_CHANGED_AT, snapshot.changedAtMillis)
        val failure = snapshot.failure
        putBoolean(KEY_FAILURE_PRESENT, failure != null)
        if (failure != null) {
            putString(KEY_FAILURE_CODE, failure.code.name)
            putString(KEY_FAILURE_MESSAGE, failure.message)
            putBoolean(KEY_FAILURE_RETRYABLE, failure.retryable)
            putString(KEY_FAILURE_DIAGNOSTICS, failure.diagnostics)
        }
    }

    fun snapshotFrom(bundle: Bundle): DshRuntimeSnapshot {
        val phase = bundle.getString(KEY_PHASE)?.let { name ->
            DshRuntimePhase.entries.firstOrNull { it.name == name }
        } ?: DshRuntimePhase.FAILED
        val failure = if (bundle.getBoolean(KEY_FAILURE_PRESENT, false)) {
            DshRuntimeFailure(
                code = bundle.getString(KEY_FAILURE_CODE)?.let { name ->
                    DshFailureCode.entries.firstOrNull { it.name == name }
                } ?: DshFailureCode.INTERNAL,
                message = bundle.getString(KEY_FAILURE_MESSAGE) ?: "DSH host failure",
                retryable = bundle.getBoolean(KEY_FAILURE_RETRYABLE, true),
                diagnostics = bundle.getString(KEY_FAILURE_DIAGNOSTICS)
            )
        } else {
            null
        }
        return DshRuntimeSnapshot(
            phase = phase,
            epoch = bundle.getLong(KEY_EPOCH, 0L),
            origin = bundle.getString(KEY_ORIGIN),
            failure = failure,
            changedAtMillis = bundle.getLong(KEY_CHANGED_AT, 0L)
        )
    }

    fun entryBundle(epoch: Long, authenticatedUrl: String): Bundle = Bundle().apply {
        putLong(KEY_EPOCH, epoch)
        putString(KEY_ENTRY_URL, authenticatedUrl)
    }

    /**
     * Minimal payload of [MSG_REPORT_FIRST_FRAME]: only the observed generation. No URL, page
     * text or page-derived identity travels over Binder for this observation.
     */
    fun firstFrameBundle(epoch: Long): Bundle = Bundle().apply {
        putLong(KEY_EPOCH, epoch)
    }

    /** Reads the reported generation; null when absent, malformed or non-positive. */
    fun firstFrameEpochFrom(bundle: Bundle?): Long? {
        if (bundle == null || !bundle.containsKey(KEY_EPOCH)) return null
        return bundle.getLong(KEY_EPOCH, 0L).takeIf { it > 0L }
    }

    /**
     * Carries the per-generation MCA model gateway coordinates from the app process to the
     * `:dsh` service over Binder. This Bundle is in-memory IPC only: never written to an
     * Intent extra, preference or log.
     */
    fun gatewayBundle(endpoint: DshGatewayEndpoint): Bundle = Bundle().apply {
        putString(KEY_GATEWAY_BASE_URL, endpoint.baseUrl)
        putString(KEY_GATEWAY_BEARER, endpoint.bearerToken)
        putString(KEY_GATEWAY_CATALOG, endpoint.catalogRevision)
    }

    /** Reads the gateway coordinates; null when absent or malformed (caller fails closed). */
    fun gatewayFrom(bundle: Bundle?): DshGatewayEndpoint? {
        if (bundle == null) return null
        val baseUrl = bundle.getString(KEY_GATEWAY_BASE_URL)?.trim().orEmpty()
        val bearer = bundle.getString(KEY_GATEWAY_BEARER)?.trim().orEmpty()
        if (!baseUrl.startsWith("http://127.0.0.1") && !baseUrl.startsWith("http://[::1]") &&
            !baseUrl.startsWith("http://localhost")
        ) {
            return null
        }
        if (bearer.isEmpty()) return null
        return DshGatewayEndpoint(
            baseUrl = baseUrl,
            bearerToken = bearer,
            catalogRevision = bundle.getString(KEY_GATEWAY_CATALOG).orEmpty()
        )
    }

    /**
     * Carries the per-generation App-local capability bridge coordinates to the `:dsh` process.
     * The token is in-memory Binder data only: never an Intent extra, preference, log line or
     * file. An absent endpoint is valid (no mobile tool packages register); a malformed one is
     * dropped by [capabilityFrom] so the host never starts with half a bridge.
     */
    fun capabilityBundle(endpoint: DshCapabilityBridgeEndpoint): Bundle = Bundle().apply {
        putInt(KEY_CAP_APP_PORT, endpoint.appLocalPort)
        endpoint.accessibilityPort?.let { putInt(KEY_CAP_A11Y_PORT, it) }
        putLong(KEY_CAP_GENERATION, endpoint.generation)
        putString(KEY_CAP_TOKEN, endpoint.localToken)
        putBoolean(KEY_CAP_ROOT, endpoint.rootAvailable)
        putBoolean(KEY_CAP_SHIZUKU, endpoint.shizukuAvailable)
        endpoint.shizukuAppId?.let { putString(KEY_CAP_SHIZUKU_APP_ID, it) }
        endpoint.shizukuDexPath?.let { putString(KEY_CAP_SHIZUKU_DEX, it) }
        putBoolean(KEY_CAP_APPROVAL_ASK, endpoint.approvalAsk)
    }

    /** Reads the capability bridge coordinates; null when absent or malformed (fail closed). */
    fun capabilityFrom(bundle: Bundle?): DshCapabilityBridgeEndpoint? {
        if (bundle == null || !bundle.containsKey(KEY_CAP_TOKEN)) return null
        return runCatching {
            DshCapabilityBridgeEndpoint(
                appLocalPort = bundle.getInt(KEY_CAP_APP_PORT, 0),
                accessibilityPort = bundle.getInt(KEY_CAP_A11Y_PORT, 0).takeIf { it > 0 },
                generation = bundle.getLong(KEY_CAP_GENERATION, 0L),
                localToken = bundle.getString(KEY_CAP_TOKEN).orEmpty(),
                rootAvailable = bundle.getBoolean(KEY_CAP_ROOT, false),
                shizukuAvailable = bundle.getBoolean(KEY_CAP_SHIZUKU, false),
                shizukuAppId = bundle.getString(KEY_CAP_SHIZUKU_APP_ID),
                shizukuDexPath = bundle.getString(KEY_CAP_SHIZUKU_DEX),
                approvalAsk = bundle.getBoolean(KEY_CAP_APPROVAL_ASK, true)
            )
        }.getOrNull()
    }

    // ------------------------------------------------------------------ binding reconciliation

    /**
     * Builds the payload of [MSG_APPLY_CONFIG]: the desired gateway/capability coordinates (the
     * same in-memory Binder fields the start messages use) plus the caller's in-flight observation.
     */
    fun applyRequestBundle(
        endpoint: DshGatewayEndpoint?,
        capability: DshCapabilityBridgeEndpoint?,
        hostBuild: String,
        request: DshApplyRequest
    ): Bundle = Bundle().apply {
        if (endpoint != null) putAll(gatewayBundle(endpoint))
        if (capability != null) putAll(capabilityBundle(capability))
        putString(KEY_HOST_BUILD, hostBuild)
        // Echoed back by the host so a failed/timed-out call still names what was requested.
        putString(KEY_APPLY_REQUESTED, DshHostConfig(endpoint, capability, hostBuild).id.value)
        putBoolean(KEY_APPLY_FORCE, request.force)
        putInt(KEY_APPLY_IN_FLIGHT, request.inFlightCalls ?: IN_FLIGHT_UNOBSERVABLE)
        putString(KEY_APPLY_SOURCE, request.source)
        request.payloadRoot?.let { putString(KEY_APPLY_PAYLOAD_ROOT, it) }
    }

    /** Reads the caller's intent; a malformed/absent observation stays `null` (unobservable). */
    fun applyRequestFrom(bundle: Bundle?): DshApplyRequest {
        if (bundle == null) return DshApplyRequest()
        val observed = bundle.getInt(KEY_APPLY_IN_FLIGHT, IN_FLIGHT_UNOBSERVABLE)
        val source = bundle.getString(KEY_APPLY_SOURCE)?.trim().orEmpty()
        // Never let a wire value break the request contract.
        val safeSource = source.takeIf { it.isNotEmpty() }?.take(APPLY_SOURCE_MAX_CHARS) ?: "apply"
        // null = no instruction, "" = return to the installed payload, else an absolute path.
        val payloadRoot = bundle.getString(KEY_APPLY_PAYLOAD_ROOT)?.trim()
        return DshApplyRequest(
            inFlightCalls = observed.takeIf { it >= 0 },
            force = bundle.getBoolean(KEY_APPLY_FORCE, false),
            source = safeSource,
            payloadRoot = payloadRoot
        )
    }

    /** Reads the app-composition identity; empty when the composition did not supply one. */
    fun hostBuildFrom(bundle: Bundle?): String = bundle?.getString(KEY_HOST_BUILD)?.trim().orEmpty()

    /** Reply payload: the ordinary snapshot fields plus the typed apply result. */
    fun applyResultBundle(result: DshApplyResult): Bundle = snapshotBundle(result.snapshot).apply {
        putString(KEY_APPLY_STATUS, result.status.name)
        putString(KEY_APPLY_REQUESTED, result.requested.value)
        result.applied?.let { putString(KEY_APPLY_APPLIED, it.value) }
        result.retained?.let { putString(KEY_APPLY_RETAINED, it.value) }
        result.change?.let { putString(KEY_APPLY_CHANGE, it) }
        result.inFlightCalls?.let { putInt(KEY_APPLY_IN_FLIGHT, it) }
        putInt(KEY_APPLY_TERMINATED, result.inFlightTerminated)
        putBoolean(KEY_APPLY_UNOBSERVABLE, result.inFlightUnobservable)
        result.detail?.let { putString(KEY_APPLY_DETAIL, it) }
    }

    /**
     * Reads a typed apply result. Inconsistent wire data is downgraded to FAILED instead of
     * producing a success claim the host never made.
     */
    fun applyResultFrom(bundle: Bundle): DshApplyResult {
        val status = bundle.getString(KEY_APPLY_STATUS)
            ?.let { name -> DshApplyStatus.entries.firstOrNull { it.name == name } }
            ?: DshApplyStatus.FAILED
        val requested = DshBindingId.fromWire(bundle.getString(KEY_APPLY_REQUESTED))
        val applied = bundle.getString(KEY_APPLY_APPLIED)?.let(DshBindingId::fromWire)
        val retained = bundle.getString(KEY_APPLY_RETAINED)?.let(DshBindingId::fromWire)
        val consistent = when (status) {
            DshApplyStatus.APPLIED,
            DshApplyStatus.UNCHANGED -> applied == requested

            DshApplyStatus.ROLLED_BACK ->
                applied != null && applied == retained && applied != requested

            DshApplyStatus.DEFERRED_IN_FLIGHT,
            DshApplyStatus.DEFERRED_GATEWAY_UNAVAILABLE,
            DshApplyStatus.FAILED -> applied == null
        }
        val observed = bundle.getInt(KEY_APPLY_IN_FLIGHT, IN_FLIGHT_UNOBSERVABLE).takeIf { it >= 0 }
        return DshApplyResult(
            status = if (consistent && requested != DshBindingId.Unknown) {
                status
            } else {
                DshApplyStatus.FAILED
            },
            requested = requested,
            applied = if (consistent && requested != DshBindingId.Unknown) applied else null,
            retained = retained,
            epoch = bundle.getLong(KEY_EPOCH, 0L),
            snapshot = snapshotFrom(bundle),
            change = bundle.getString(KEY_APPLY_CHANGE),
            inFlightCalls = observed,
            inFlightTerminated = bundle.getInt(KEY_APPLY_TERMINATED, 0).coerceAtLeast(0),
            inFlightUnobservable = bundle.getBoolean(KEY_APPLY_UNOBSERVABLE, false) || observed == null,
            detail = bundle.getString(KEY_APPLY_DETAIL)
        )
    }

    // ------------------------------------------------------------------ isolated closure trial (D24)

    // Request side: the app-provided trial roots, already validated by the app's maintenance
    // transaction. Absolute paths travel over in-memory Binder only; the owner re-validates their
    // containment and never echoes them back into a reply, log line or notification.
    const val KEY_TRIAL_ROOT = "trialRoot"
    const val KEY_TRIAL_FINGERPRINT = "trialFingerprint"
    const val KEY_TRIAL_HOME = "trialHome"
    const val KEY_TRIAL_WORKSPACE = "trialWorkspace"
    const val KEY_TRIAL_TMP = "trialTmp"

    // Reply side: typed, secret-free and path-free. The owner emits categorical reasons only; the
    // helpers below enforce the byte bound and printable characters.
    const val KEY_TRIAL_PHASE = "trialPhase"
    const val KEY_TRIAL_PID = "trialPid"
    const val KEY_TRIAL_GENERATION = "trialGeneration"
    const val KEY_TRIAL_DETAIL = "trialDetail"
    const val KEY_TRIAL_HOST_READY = "trialHostReady"
    const val KEY_TRIAL_HOME_READABLE = "trialHomeReadable"
    const val KEY_TRIAL_READER_READY = "trialReaderReady"

    /** Upper bound of every trial detail string that crosses Binder. */
    const val TRIAL_DETAIL_MAX_CHARS = 200

    /** One typed trial start request. All five paths are absolute app-private paths. */
    data class TrialStartRequest(
        val candidateRoot: String,
        val candidateFingerprint: String,
        val homeRoot: String,
        val workspaceRoot: String,
        val tmpRoot: String
    )

    fun trialRequestBundle(request: TrialStartRequest): Bundle = Bundle().apply {
        putString(KEY_TRIAL_ROOT, request.candidateRoot)
        putString(KEY_TRIAL_FINGERPRINT, request.candidateFingerprint)
        putString(KEY_TRIAL_HOME, request.homeRoot)
        putString(KEY_TRIAL_WORKSPACE, request.workspaceRoot)
        putString(KEY_TRIAL_TMP, request.tmpRoot)
    }

    /**
     * Reads the trial request; null when any required field is absent or blank. The service must
     * answer a malformed request with a typed FAILED, never by starting anything.
     */
    fun trialRequestFrom(bundle: Bundle?): TrialStartRequest? {
        if (bundle == null) return null
        val candidateRoot = bundle.getString(KEY_TRIAL_ROOT)?.trim().orEmpty()
        val fingerprint = bundle.getString(KEY_TRIAL_FINGERPRINT)?.trim().orEmpty()
        val home = bundle.getString(KEY_TRIAL_HOME)?.trim().orEmpty()
        val workspace = bundle.getString(KEY_TRIAL_WORKSPACE)?.trim().orEmpty()
        val tmp = bundle.getString(KEY_TRIAL_TMP)?.trim().orEmpty()
        if (listOf(candidateRoot, fingerprint, home, workspace, tmp).any { it.isEmpty() }) return null
        return TrialStartRequest(candidateRoot, fingerprint, home, workspace, tmp)
    }

    fun trialSnapshotBundle(snapshot: DshTrialSnapshot): Bundle = Bundle().apply {
        putString(KEY_TRIAL_PHASE, snapshot.phase.name)
        putLong(KEY_TRIAL_PID, snapshot.pid)
        putLong(KEY_TRIAL_GENERATION, snapshot.generation)
        putString(KEY_TRIAL_DETAIL, sanitizeTrialDetail(snapshot.detail))
    }

    /**
     * Reads a trial snapshot. An absent or unrecognized phase maps to UNKNOWN — **never** to
     * STOPPED: a truncated, corrupted or foreign reply must not be able to report a released trial.
     */
    fun trialSnapshotFrom(bundle: Bundle): DshTrialSnapshot {
        val phase = trialPhaseFrom(bundle.getString(KEY_TRIAL_PHASE))
        return DshTrialSnapshot(
            phase = phase,
            pid = bundle.getLong(KEY_TRIAL_PID, 0L).coerceAtLeast(0L),
            generation = bundle.getLong(KEY_TRIAL_GENERATION, 0L).coerceAtLeast(0L),
            detail = sanitizeTrialDetail(bundle.getString(KEY_TRIAL_DETAIL))
        )
    }

    /**
     * Maps one wire phase name. Pure and total: anything that is not a known phase is UNKNOWN, so
     * a missing/foreign value can never be interpreted as a released trial.
     */
    fun trialPhaseFrom(wire: String?): DshTrialPhase =
        wire?.let { name -> DshTrialPhase.entries.firstOrNull { it.name == name } }
            ?: DshTrialPhase.UNKNOWN

    fun trialHealthBundle(health: DshTrialHealthSnapshot): Bundle = Bundle().apply {
        putBoolean(KEY_TRIAL_HOST_READY, health.hostReady)
        putBoolean(KEY_TRIAL_HOME_READABLE, health.homeReadable)
        putBoolean(KEY_TRIAL_READER_READY, health.sessionReaderReady)
        putString(KEY_TRIAL_DETAIL, sanitizeTrialDetail(health.detail))
    }

    /**
     * Reads trial health. Every missing/absent field is false: health is only ever built from
     * positive observations, so a partial bundle can never claim one.
     */
    fun trialHealthFrom(bundle: Bundle): DshTrialHealthSnapshot = DshTrialHealthSnapshot(
        hostReady = bundle.getBoolean(KEY_TRIAL_HOST_READY, false),
        homeReadable = bundle.getBoolean(KEY_TRIAL_HOME_READABLE, false),
        sessionReaderReady = bundle.getBoolean(KEY_TRIAL_READER_READY, false),
        detail = sanitizeTrialDetail(bundle.getString(KEY_TRIAL_DETAIL))
    )

    /**
     * Bounds and neutralises a trial detail. The owner only produces categorical reasons (no path
     * and no token); this additionally guarantees the IPC bound and drops control characters so a
     * foreign string cannot reshape a notification or a log line.
     */
    fun sanitizeTrialDetail(raw: String?): String =
        raw.orEmpty().filter { it >= ' ' }.take(TRIAL_DETAIL_MAX_CHARS)

}

package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshGatewayModel
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.dsh.contract.DshModelRequest
import kotlinx.coroutines.flow.Flow

/**
 * App-owned inference port behind the internal DSH model gateway.
 *
 * The gateway owns HTTP/SSE and OpenAI wire fidelity; the implementing adapter owns the mapping
 * to MCA's existing model execution owner (admission, lease, chat template, precise cancel and
 * native settle evidence). The gateway never executes DSH tools, never reads MCA cloud keys and
 * never rewrites the DSH prompt or transcript.
 *
 * Contract for implementations:
 * - [catalog] is the authoritative list of models this gateway instance may serve. It is
 *   requested on `/v1/models` and before every chat completion, so a model that is no longer
 *   loaded must disappear from it instead of being served silently.
 * - [stream] must be a cold flow that emits any number of [DshModelEvent.TextDelta],
 *   [DshModelEvent.ReasoningDelta] and [DshModelEvent.ToolCallDelta] events and ends with
 *   exactly one [DshModelEvent.Completed] or [DshModelEvent.Failed]. The gateway enforces that
 *   terminal contract and reports a missing terminal as `backend_stream_closed` (never as
 *   `stop`). A thrown exception is reported as `backend_error`.
 * - Tool call deltas must carry a stable `index`; the first delta for an index must carry the
 *   function `name` (and should carry the `id`). Argument fragments are concatenated verbatim
 *   and must eventually form the complete JSON argument string.
 * - [cancel] is invoked at most once per call id when the HTTP peer disconnects, the host calls
 *   the explicit cancel route, or the gateway stops. It must cancel only that call and must
 *   dispatch the precise native stop before returning: for local models that is
 *   `ChatExecutionFacade.stopGenerationIfActive(requestId)`, which is the only path that sends
 *   `runner.requestStop()` out of band. The gateway awaits this dispatch so a cancel is only
 *   acknowledged after the stop signal exists, but the wait is bounded by
 *   [DshGatewayLimits.cancelDispatchTimeoutMillis]; [cancel] must not wait for native settle
 *   evidence (that belongs to the adapter's own terminal bookkeeping). The collecting flow is
 *   also cancelled structurally; [cancel] is the out-of-band signal for adapters that need to stop
 *   native work they own.
 */
interface DshGatewayBackend {
    suspend fun catalog(): List<DshGatewayModel>

    fun stream(request: DshModelRequest): Flow<DshModelEvent>

    /** Cancels exactly one in-flight call. A no-op for unknown or already terminal calls. */
    suspend fun cancel(callId: String) {
        // Adapters without an out-of-band cancel still get structural Flow cancellation.
    }
}

package com.muyuchat.mca

import org.json.JSONObject

/**
 * Recovers the request id from a payload that could not be parsed.
 *
 * A malformed request still has to be attributed to the request that sent it, so before
 * failing, the worker services try to pull `requestId` out of the raw JSON. That is part of
 * preserving request identity across the IPC boundary, which the plan requires be kept and
 * verified.
 *
 * This existed as three byte-identical private copies, one each in LocalImageWorkerService,
 * QairtDryRunWorkerService and TuningProbeWorkerService. Three copies of an identity-recovery
 * rule means an error can be attributed one way in the image worker and another way in the
 * tuning probe, which is precisely the kind of divergence that makes a bug hard to trace.
 * There is now one rule.
 *
 * The contract, unchanged from all three originals:
 *   - parsing failure is not an error here; it yields the empty string
 *   - a payload that parses but carries no requestId also yields the empty string
 *   - callers treat the empty string as "attribution unknown" rather than as a request id
 *
 * Note this does not apply the `REQUEST_ID_PATTERN` check that McaLoopbackServer uses on
 * client-supplied ids. That check guards an untrusted HTTP boundary; these ids arrive over
 * our own IPC from the app process, so the originals did not validate them either and this
 * keeps that behaviour rather than tightening it in a refactor.
 */
internal fun requestIdFromMalformedPayload(raw: String): String =
    runCatching { JSONObject(raw).optString("requestId") }.getOrDefault("")

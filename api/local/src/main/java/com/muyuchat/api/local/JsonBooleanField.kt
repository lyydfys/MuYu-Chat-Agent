package com.muyuchat.api.local

import org.json.JSONObject

/**
 * Reads the first present, non-null boolean among several alternative field names.
 *
 * One implementation, in one place. This same five-line function existed twice in this module,
 * private and byte-identical, as `firstBoolean` in LocalApiRuntime and as `explicitBoolean` in
 * McaLoopbackServer. Both sit on the Local API's reading side, so a divergence would mean the
 * same JSON field is interpreted one way by the runtime state endpoint and another way by the
 * loopback server -- a protocol inconsistency between two endpoints of the same API.
 *
 * The aliases are the point: the API accepts both snake_case and camelCase for the same
 * concept, so `visionReadyFlag` asks for "vision_ready", "visionReady", "supports_vision" and
 * "supportsVision" in that order.
 *
 * Semantics, unchanged from both copies:
 *   - the first key that is present and not JSON null wins, and its value is read with
 *     optBoolean, so a non-boolean value for that key reads as false rather than falling
 *     through to the next alias
 *   - if no key is present, the result is null, which callers distinguish from false
 */
internal fun JSONObject.firstBoolean(vararg keys: String): Boolean? {
    for (key in keys) {
        if (has(key) && !isNull(key)) return optBoolean(key)
    }
    return null
}

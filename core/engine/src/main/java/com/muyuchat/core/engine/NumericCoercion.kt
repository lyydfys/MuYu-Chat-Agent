package com.muyuchat.core.engine

/**
 * Coerces a loosely typed value into an Int, or null when it is not an integer.
 *
 * One implementation, in one place. This same three-branch function existed twice in this
 * module, private and byte-identical, in LocalChatRunner and RuntimeParameters. Those two are
 * the chat generation path and the runtime parameter path, so a divergence between them would
 * mean the same supplied parameter is accepted on one path and dropped on the other -- the
 * request would still succeed, with a different effective value. That is the kind of
 * difference that shows up as "the setting did not apply" rather than as an error.
 *
 * Accepted, unchanged from both copies:
 *   - Number, narrowed with toInt()
 *   - String, trimmed, parsed with toIntOrNull() -- so " 512 " works and "512.0" does not
 *   - anything else, including null and JSONObject.NULL, is null
 *
 * Note that a String is only accepted if it parses as a whole integer. That is deliberate and
 * load-bearing for the JSON paths: a client sending `"n_predict": "512"` gets 512, while
 * `"n_predict": "512.5"` gets null and the caller falls back to its default rather than
 * silently truncating.
 */
internal fun integerValue(value: Any?): Int? = when (value) {
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}

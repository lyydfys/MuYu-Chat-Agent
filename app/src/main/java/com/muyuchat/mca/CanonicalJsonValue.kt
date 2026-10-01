package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical text for a JSON value, used to decide whether an execution profile changed.
 *
 * `executionProfileDiffers` compares governed fields of the requested parameters against the
 * fields stored on the active profile, and a difference triggers a reload. The two sides do
 * not come from the same place: the requested side is built fresh from Kotlin values, while
 * the effective side was serialised earlier and parsed back. The same logical number can
 * therefore arrive as `1024`, `1024.0` or `1.024E3`.
 *
 * Normalising through `BigDecimal.stripTrailingZeros()` is what makes those compare equal.
 * Without it the app would decide a reload is needed when nothing changed -- and, on the
 * other side of the same comparison, could miss a real change.
 *
 * ## What this does not do
 *
 * Normalisation is applied only to the value handed in. A nested object or array is compared
 * as whatever `toString()` emits, and org.json formats numbers itself on the way out -- it
 * writes a whole double as an integer and a `BigDecimal` exponent form in plain notation --
 * so most nested numbers happen to agree too.
 *
 * It is not a full guarantee. Negative zero is a demonstrable gap: at the top level
 * `BigDecimal` has no signed zero so `-0.0` and `0` agree, while nested org.json strips the
 * trailing zero from `-0.0` and emits `-0`, which differs from `0`. That is preserved
 * behaviour. Both sides of the comparison are produced by the same serialiser, so it does
 * not bite in practice, and making the comparison structural would change when reloads
 * trigger.
 *
 * Extracted verbatim from `MainViewModel` during P2; no branch was changed.
 */
internal fun canonicalJsonValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is Number -> value.toString().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value.toString()
    is Boolean -> value.toString()
    is JSONObject -> value.toString()
    is JSONArray -> value.toString()
    else -> value.toString().trim()
}

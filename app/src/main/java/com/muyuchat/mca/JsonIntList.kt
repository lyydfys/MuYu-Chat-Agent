package com.muyuchat.mca

import org.json.JSONArray

/**
 * Reads a JSON array of positive integers, tolerating a missing array.
 *
 * One implementation, in one place. The same function existed twice in this package, private
 * and byte-identical, in LocalQnnSmokeSpec and QnnNativeSmokeBridge. Both describe the shape of
 * a native smoke request or response, so a divergence would mean the two ends of that protocol
 * accept a different set of integers.
 *
 * Kept in this package: both callers are here, so no import, dependency or visibility question
 * arises.
 *
 * Semantics, unchanged from both copies:
 *   - a null array yields an empty list rather than throwing
 *   - entries are read with optInt(index, 0), so a non-numeric entry reads as 0
 *   - an entry is kept only when it is greater than zero, so 0 and negative values are dropped
 *   - order is preserved
 */
internal fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optInt(index, 0)
            if (value > 0) add(value)
        }
    }
}

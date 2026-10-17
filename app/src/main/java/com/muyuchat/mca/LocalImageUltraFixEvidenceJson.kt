package com.muyuchat.mca

import org.json.JSONObject

    internal fun JSONObject.requireExactKeys(expected: Set<String>, layer: String) {
        require(keys().asSequence().toSet() == expected) {
            "$layer UltraFix evidence fields do not match the strict schema."
        }
    }

    internal fun JSONObject.exactLong(field: String, layer: String): Long {
        require(has(field) && !isNull(field)) { "$layer is missing $field." }
        val raw = get(field)
        require(raw is Byte || raw is Short || raw is Int || raw is Long) {
            "$layer $field must be an exact integer."
        }
        return (raw as Number).toLong()
    }

    internal fun JSONObject.exactBoolean(field: String, layer: String): Boolean {
        require(has(field) && !isNull(field) && get(field) is Boolean) {
            "$layer $field must be boolean."
        }
        return getBoolean(field)
    }

    internal fun JSONObject.exactString(field: String, layer: String): String {
        require(has(field) && !isNull(field) && get(field) is String) {
            "$layer $field must be a string."
        }
        return getString(field)
    }

    internal fun JSONObject.exactDouble(field: String, layer: String): Double {
        require(has(field) && !isNull(field) && get(field) is Number) {
            "$layer $field must be numeric."
        }
        return (get(field) as Number).toDouble().also { value ->
            require(value.isFinite()) { "$layer $field must be finite." }
        }
    }

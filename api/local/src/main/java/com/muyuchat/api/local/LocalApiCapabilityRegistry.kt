package com.muyuchat.api.local

import org.json.JSONArray
import org.json.JSONObject

/**
 * The public contract advertised by the authenticated Local API.
 *
 * Keep this registry close to the router so documentation and clients can inspect the same
 * method/path, authentication, streaming and field-subset semantics that the production server
 * actually exposes.  A capability describes protocol scope; model/runtime readiness remains
 * dynamic and is reported by /v1/models and /v1/mca/runtime.
 */
enum class LocalApiCapabilitySupport {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED
}

data class LocalApiEndpointCapability(
    val method: String,
    val path: String,
    val support: LocalApiCapabilitySupport,
    val authRequired: Boolean,
    val streaming: Boolean,
    val description: String,
    val acceptedFields: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val errorCodes: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("method", method)
        .put("path", path)
        .put("support", support.name.lowercase())
        .put("auth_required", authRequired)
        .put("stream", streaming)
        .put("description", description)
        .put("fields", JSONArray(acceptedFields))
        .put("notes", JSONArray(notes))
        .put("error_codes", JSONArray(errorCodes))
}

object LocalApiCapabilityRegistry {
    const val SCHEMA = "mca.local-api.capabilities.v1"
    const val PATH = "/v1/mca/capabilities"

    private val entries = mcaLocalApiCapabilityEntries
    fun all(): List<LocalApiEndpointCapability> = entries

    fun json(): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", 1)
        .put("generated_by", "McaLoopbackServer")
        .put("endpoints", JSONArray().also { array -> entries.forEach { array.put(it.toJson()) } })
        .put(
            "support_values",
            JSONArray(LocalApiCapabilitySupport.entries.map { it.name.lowercase() })
        )
        .toString()
}

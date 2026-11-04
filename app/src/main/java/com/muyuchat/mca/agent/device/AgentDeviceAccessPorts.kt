package com.muyuchat.mca.agent.device

import com.muyuchat.mca.agent.device.sensors.AgentSensorId
import com.muyuchat.mca.agent.device.sensors.AgentSensorResult
import com.muyuchat.mca.agent.device.sms.AgentSmsRawQueryResult

/**
 * Ports of the DSH device-access lane (sensors/location/torch and read-only SMS).
 *
 * Why ports at all: the route table, the decision matrix and the privacy rules are pure JVM code
 * that must be testable without a device; only [AndroidAgentSensorReader] /
 * [AndroidAgentSmsReader] touch `android.*`. The composition root injects the Android
 * implementations, exactly like the other capability families.
 *
 * Consent is read from [AgentDeviceAccessStore] (SharedPreferences, default OFF for everything).
 * Nothing in this lane writes a file, a log line or a backup row: readings and SMS previews exist
 * only in the response to one request.
 */
interface AgentDeviceConsentGate {
    fun sensorEnabled(id: AgentSensorId): Boolean
    fun smsReadEnabled(): Boolean

    /**
     * True when the user switched off the per-query SMS confirmation for the current app process.
     * The store clears the persisted flag when the process starts, so a restart re-arms the prompt.
     */
    fun smsSkipConfirmationThisSession(): Boolean
}

/** Runtime-permission facts; `null` permission (light, torch, …) is always granted. */
fun interface AgentDevicePermissionGate {
    fun granted(permission: String?): Boolean
}

/** Single-shot platform reading. Platform problems are returned as typed refusals, never thrown. */
interface AgentSensorSource {
    suspend fun read(id: AgentSensorId): AgentSensorResult
    suspend fun setTorch(on: Boolean): AgentSensorResult
}

/** Raw read-only SMS query; [AgentSmsRawQueryResult] keeps "no rows" distinct from "no access". */
fun interface AgentSmsSource {
    suspend fun query(limit: Int, keyword: String?): AgentSmsRawQueryResult
}

/**
 * One per-query confirmation shown outside the WebView (the existing capability consent dialog).
 * Returning false denies the query; there is no silent default.
 */
fun interface AgentSmsConfirmationPort {
    suspend fun confirm(detail: String): Boolean
}

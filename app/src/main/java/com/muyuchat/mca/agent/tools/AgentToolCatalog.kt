package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import org.json.JSONObject

/**
 * Immutable snapshot of the V1 built-in tools (architecture 9.1).
 *
 * Construction is the last point where a bad catalog can be rejected: duplicate keys, a schema
 * outside the supported subset, a non-canonical schema text or a stale schema hash fail immediately
 * instead of becoming a run-time surprise. [snapshot] is deterministic (sorted by tool key) and the
 * instance is never mutated afterwards, so an extension update cannot change a live run's tool set.
 */
class BuiltInAgentToolCatalog(executors: List<AgentToolExecutor>) : AgentToolCatalog {
    private val executorsByKey: Map<String, AgentToolExecutor>
    private val descriptors: List<AgentToolDescriptor>

    init {
        require(executors.isNotEmpty()) { "built-in tool catalog must not be empty" }
        val byKey = LinkedHashMap<String, AgentToolExecutor>(executors.size)
        executors.forEach { executor ->
            val descriptor = executor.descriptor
            require(!byKey.containsKey(descriptor.toolKey)) {
                "duplicate built-in tool key: ${descriptor.toolKey}"
            }
            val canonical = AgentToolSchemaGuard.canonicalForm(descriptor.schemaJson)
            require(canonical == descriptor.schemaJson) {
                "built-in tool schema must be canonical JSON: ${descriptor.toolKey}"
            }
            require(AgentToolJson.sha256Hex(descriptor.schemaJson) == descriptor.schemaHash) {
                "built-in tool schema hash is stale: ${descriptor.toolKey}"
            }
            require(JSONObject(descriptor.schemaJson).opt("additionalProperties") == false) {
                "built-in tool schema root must forbid additional properties: ${descriptor.toolKey}"
            }
            byKey[descriptor.toolKey] = executor
        }
        executorsByKey = byKey
        descriptors = byKey.keys.sorted().map { key -> byKey.getValue(key).descriptor }
    }

    override fun find(toolKey: String): AgentToolExecutor? = executorsByKey[toolKey]

    override fun snapshot(): List<AgentToolDescriptor> = descriptors
}

/**
 * Production construction point for the four K08 tools.
 *
 * [filePort] is the app-owned task workspace implementation; it owns encoding, hashing, staging and
 * commit journaling. No executor receives a Context, a SAF handle or an absolute path.
 */
fun builtInAgentToolCatalog(filePort: AgentWorkspaceFilePort): AgentToolCatalog =
    BuiltInAgentToolCatalog(builtInAgentToolExecutors(filePort))

/** The frozen V1 executor list; also the base of [extendedAgentToolCatalog]. */
fun builtInAgentToolExecutors(filePort: AgentWorkspaceFilePort): List<AgentToolExecutor> =
    listOf(
        WorkspaceListTool(filePort),
        WorkspaceReadTextTool(filePort),
        WorkspaceSearchTextTool(filePort),
        ArtifactWriteTextTool(filePort)
    )

/**
 * Construction point for the extended built-in family
 * (`workspace.write_text`, `workspace.edit_text`, `http.get`, `terminal.exec`).
 *
 * Registration is explicit and degradation is honest:
 * - the two workspace write tools only need the existing file port and are always registered;
 * - `http.get` is registered only when a transport is supplied (the tool itself still requires an
 *   [AgentHttpGetGate] authorization per call; the default gate refuses everything);
 * - `terminal.exec` is registered only when the sandbox reports itself available, so a build with no
 *   executable sandbox does not advertise a tool that can never run.
 *
 * The catalog inherits every construction check of [BuiltInAgentToolCatalog] (duplicate keys,
 * canonical schema, schema hash, `additionalProperties=false` at the root). Passing the result to
 * [com.muyuchat.mca.agent.security.AgentFrozenCommandFactory] requires the matching
 * [AgentExtendedToolArgumentNormalizer]; composition-root wiring is out of this slice's scope.
 */
fun extendedAgentToolCatalog(
    filePort: AgentWorkspaceFilePort,
    deviceStatusSource: DeviceStatusSource? = null,
    deviceNotificationSink: DeviceNotificationSink? = null,
    deviceUrlLauncher: DeviceUrlLauncher? = null,
    deviceClipboardSink: DeviceClipboardSink? = null,
    deviceSettingsLauncher: DeviceAppSettingsLauncher? = null,
    /**
     * Screen lane. Registered whenever supplied; a disabled accessibility service answers
     * `SCREEN_SERVICE_DISABLED` per call instead of the tool disappearing from the catalog, so the
     * model can tell the user what to enable.
     */
    deviceScreenPort: AgentScreenPort? = null,
    httpClient: AgentHttpTextClient? = null,
    httpGate: AgentHttpGetGate = AgentHttpGetGate.DENY_ALL,
    terminalSandbox: AgentTerminalSandbox = AgentTerminalSandbox.UNAVAILABLE,
    terminalWhitelist: Set<String> = AgentTerminalWhitelist.DEFAULT
): AgentToolCatalog {
    val executors = ArrayList<AgentToolExecutor>(builtInAgentToolExecutors(filePort))
    executors += WorkspaceWriteTextTool(filePort)
    executors += WorkspaceEditTextTool(filePort)
    // device.info needs no Android permission and no workspace file; it is always available.
    executors += AgentDeviceInfoTool()
    if (deviceStatusSource != null) executors += AgentDeviceStatusTool(deviceStatusSource)
    if (deviceNotificationSink != null) executors += AgentDeviceNotificationTool(deviceNotificationSink)
    if (deviceUrlLauncher != null) executors += AgentDeviceOpenUrlTool(deviceUrlLauncher)
    if (deviceClipboardSink != null) executors += AgentDeviceClipboardTool(deviceClipboardSink)
    if (deviceSettingsLauncher != null) {
        executors += AgentDeviceNotificationSettingsTool(deviceSettingsLauncher)
    }
    if (deviceScreenPort != null) executors += AgentScreenTool(deviceScreenPort)
    if (httpClient != null) {
        executors += AgentHttpGetTool(httpClient, httpGate)
        // Same transport and the same per-run egress gate; `web.fetch` only changes the projection
        // (HTML → plain text). Registered together so a run never has the reader without the fetcher.
        executors += AgentWebFetchTool(httpClient, httpGate)
    }
    if (terminalSandbox.isAvailable) executors += AgentTerminalExecTool(terminalSandbox, terminalWhitelist)
    return BuiltInAgentToolCatalog(executors)
}

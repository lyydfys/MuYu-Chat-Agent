package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parameter schemas and descriptors of the second built-in tool family
 * (`workspace.write_text`, `workspace.edit_text`, `http.get`, `terminal.exec`).
 *
 * They live next to [AgentToolSchemas] and reuse the same construction-time guard
 * ([AgentToolSchemaGuard.requireSupported]) and the same canonical writer ([AgentToolJson]), so the
 * frozen-schema rules of the V1 catalog apply unchanged: root object, `additionalProperties=false`,
 * no remote `$ref`, no unsupported keyword, no schema above the 64 KiB budget.
 *
 * The schemas are deliberately flat (string/integer/null only) because the strict frozen-argument
 * parser ([com.muyuchat.mca.agent.security.AgentCanonicalJson]) does not accept nested JSON for tool
 * arguments. `terminal.exec` therefore takes one `command` token plus an unquoted `args` string that
 * the executor splits into argv without ever invoking a shell.
 */
internal object AgentExtendedToolSchemas {
    val WORKSPACE_WRITE_TEXT: BuiltInToolSchema = build(writeTextSchema())
    val WORKSPACE_EDIT_TEXT: BuiltInToolSchema = build(editTextSchema())
    val HTTP_GET: BuiltInToolSchema = build(httpGetSchema())
    val TERMINAL_EXEC: BuiltInToolSchema = build(terminalExecSchema())

    private fun build(schema: JSONObject): BuiltInToolSchema {
        val json = AgentToolJson.canonical(schema)
        AgentToolSchemaGuard.requireSupported(json)
        return BuiltInToolSchema(json, AgentToolJson.sha256Hex(json))
    }

    private fun writeTextSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put(
            "required",
            JSONArray().put("content").put("expectedOldHash").put("path")
        )
        .put(
            "properties",
            JSONObject()
                .put("path", stringProperty(minLength = 1, maxLength = 512))
                .put("content", stringProperty(maxLength = 32_768))
                .put("expectedOldHash", nullableStringProperty(minLength = 64, maxLength = 64))
        )

    private fun editTextSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put(
            "required",
            JSONArray().put("expectedOldHash").put("newText").put("oldText").put("path")
        )
        .put(
            "properties",
            JSONObject()
                .put("path", stringProperty(minLength = 1, maxLength = 512))
                .put("oldText", stringProperty(minLength = 1, maxLength = 8_192))
                .put("newText", stringProperty(maxLength = 8_192))
                .put("expectedOldHash", stringProperty(minLength = 64, maxLength = 64))
        )

    private fun httpGetSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put("required", JSONArray().put("url"))
        .put(
            "properties",
            JSONObject()
                .put("url", stringProperty(minLength = 9, maxLength = 2_048))
                .put("maxBytes", integerProperty(minimum = 1, maximum = 131_072))
                .put("timeoutMillis", integerProperty(minimum = 1_000, maximum = 15_000))
        )

    private fun terminalExecSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put("required", JSONArray().put("command"))
        .put(
            "properties",
            JSONObject()
                .put("command", stringProperty(minLength = 1, maxLength = 64))
                .put("args", stringProperty(maxLength = 1_024))
                .put("timeoutMillis", integerProperty(minimum = 1_000, maximum = 30_000))
        )

    private fun stringProperty(minLength: Int = 0, maxLength: Int): JSONObject {
        val node = JSONObject().put("type", "string").put("maxLength", maxLength)
        if (minLength > 0) node.put("minLength", minLength)
        return node
    }

    private fun nullableStringProperty(minLength: Int, maxLength: Int): JSONObject = JSONObject()
        .put("type", JSONArray().put("string").put("null"))
        .put("minLength", minLength)
        .put("maxLength", maxLength)

    private fun integerProperty(minimum: Int, maximum: Int): JSONObject = JSONObject()
        .put("type", "integer")
        .put("minimum", minimum)
        .put("maximum", maximum)
}

internal object AgentExtendedToolDescriptors {
    val WORKSPACE_WRITE_TEXT = AgentToolDescriptor(
        toolKey = "workspace.write_text",
        version = "1",
        displayName = "Write Workspace Text",
        description = "Create or replace a UTF-8 text file below artifacts/. " +
            "expectedOldHash=null is create-only; replacing an existing file requires its current " +
            "SHA-256. Content is limited to 32 KiB of UTF-8 and the logical path must stay inside " +
            "the task workspace.",
        schemaJson = AgentExtendedToolSchemas.WORKSPACE_WRITE_TEXT.json,
        schemaHash = AgentExtendedToolSchemas.WORKSPACE_WRITE_TEXT.schemaHash,
        sideEffect = AgentToolSideEffect.WORKSPACE_WRITE,
        dataClass = AgentDataClass.PRIVATE
    )

    val WORKSPACE_EDIT_TEXT = AgentToolDescriptor(
        toolKey = "workspace.edit_text",
        version = "1",
        displayName = "Edit Workspace Text",
        description = "Replace exactly one whole-line occurrence of oldText with newText inside a " +
            "workspace text file. expectedOldHash must be the SHA-256 of the file the edit was " +
            "approved against; the write fails if the content changed. The edited file is limited " +
            "to 32 KiB of UTF-8.",
        schemaJson = AgentExtendedToolSchemas.WORKSPACE_EDIT_TEXT.json,
        schemaHash = AgentExtendedToolSchemas.WORKSPACE_EDIT_TEXT.schemaHash,
        sideEffect = AgentToolSideEffect.WORKSPACE_WRITE,
        dataClass = AgentDataClass.PRIVATE
    )

    val HTTP_GET = AgentToolDescriptor(
        toolKey = "http.get",
        version = "1",
        displayName = "Fetch HTTPS Text",
        description = "Perform one bounded HTTPS GET. Only https origins are accepted, loopback/" +
            "private hosts are refused, redirects are never followed automatically, the response " +
            "is capped at the requested byte budget (128 KiB maximum) and the call requires an " +
            "active run egress authorization for the exact origin.",
        schemaJson = AgentExtendedToolSchemas.HTTP_GET.json,
        schemaHash = AgentExtendedToolSchemas.HTTP_GET.schemaHash,
        sideEffect = AgentToolSideEffect.NETWORK,
        dataClass = AgentDataClass.PRIVATE,
        timeoutMillis = 26_000L
    )

    val TERMINAL_EXEC = AgentToolDescriptor(
        toolKey = "terminal.exec",
        version = "1",
        displayName = "Run Whitelisted Command",
        description = "Run one command from a host-owned whitelist inside the app's executable " +
            "sandbox (no shell, no command substitution). The call is bounded by a timeout and an " +
            "output budget and is cancellable. Without a sandbox implementation the tool reports " +
            "TOOL_NOT_SUPPORTED and never executes anything.",
        schemaJson = AgentExtendedToolSchemas.TERMINAL_EXEC.json,
        schemaHash = AgentExtendedToolSchemas.TERMINAL_EXEC.schemaHash,
        sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
        dataClass = AgentDataClass.PRIVATE,
        timeoutMillis = 33_000L
    )
}

package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.security.AgentArgValue
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import com.muyuchat.mca.agent.security.AgentArgumentsParse
import com.muyuchat.mca.agent.security.AgentCanonicalJson
import com.muyuchat.mca.agent.security.AgentSecurityErrors
import com.muyuchat.mca.agent.security.AgentToolArgumentNormalizer
import org.json.JSONArray
import org.json.JSONObject

/** Host-owned clipboard writer; the tool never touches ClipboardManager directly. */
fun interface DeviceClipboardSink {
    fun write(text: String): Boolean

    companion object {
        fun android(context: android.content.Context): DeviceClipboardSink = DeviceClipboardSink { text ->
            runCatching {
                val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: return@runCatching false
                manager.setPrimaryClip(android.content.ClipData.newPlainText("MCA Agent", text))
                true
            }.getOrDefault(false)
        }
    }
}

/** `device.clipboard_write`: copy one approved text to the clipboard. */
class AgentDeviceClipboardTool(
    private val sink: DeviceClipboardSink
) : AgentToolExecutor {

    override val descriptor: AgentToolDescriptor = DESCRIPTOR

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("text"))
            val text = AgentToolArguments.requiredText(fields, "text", MAX_TEXT_CHARS)
            if (!sink.write(text)) {
                return@runBuiltInTool AgentToolResults.failure(
                    command,
                    descriptor,
                    "DEVICE_CLIPBOARD_WRITE_FAILED",
                    "系统没有接受剪贴板写入；本次没有可见效果。"
                )
            }
            AgentToolResults.success(
                command = command,
                descriptor = descriptor,
                summary = "已写入剪贴板。",
                data = JSONObject().put("written", true).put("chars", text.length)
            )
        }

    companion object {
        const val TOOL_KEY: String = "device.clipboard_write"
        const val MAX_TEXT_CHARS: Int = 4_096

        private val SCHEMA_JSON: String = AgentToolJson.canonical(
            JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("text"))
                .put(
                    "properties",
                    JSONObject().put(
                        "text",
                        JSONObject().put("type", "string")
                            .put("minLength", 1).put("maxLength", MAX_TEXT_CHARS)
                    )
                )
        )

        val DESCRIPTOR: AgentToolDescriptor = AgentToolDescriptor(
            toolKey = TOOL_KEY,
            version = "1",
            displayName = "Copy To Clipboard",
            description = "Copy one approved text to the system clipboard. The text is not observed " +
                "after writing and no clipboard content is read.",
            schemaJson = SCHEMA_JSON,
            schemaHash = AgentToolJson.sha256Hex(SCHEMA_JSON),
            sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
            dataClass = AgentDataClass.PRIVATE
        )
    }
}

/** `device.clipboard_write` takes exactly one non-empty text field. */
class AgentDeviceClipboardArgumentNormalizer : AgentToolArgumentNormalizer {
    override fun normalize(descriptor: AgentToolDescriptor, rawArgumentsJson: String): AgentArgumentNormalization {
        if (descriptor.toolKey != AgentDeviceClipboardTool.TOOL_KEY) {
            return AgentArgumentNormalization.Rejected(AgentSecurityErrors.toolNotRegistered())
        }
        val fields = when (val parsed = AgentCanonicalJson.parseArguments(rawArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> parsed.fields
            is AgentArgumentsParse.Rejected -> return AgentArgumentNormalization.Rejected(parsed.error)
        }
        if (fields.keys != setOf("text")) {
            return AgentArgumentNormalization.Rejected(
                AgentSecurityErrors.invalidArguments("device.clipboard_write needs exactly text.")
            )
        }
        val text = (fields["text"] as? AgentArgValue.Text)?.value
            ?.takeIf { it.isNotBlank() && it.length <= AgentDeviceClipboardTool.MAX_TEXT_CHARS }
            ?: return AgentArgumentNormalization.Rejected(
                AgentSecurityErrors.invalidArguments("device.clipboard_write text is invalid.")
            )
        return AgentArgumentNormalization.Normalized(
            normalizedArgumentsJson = AgentCanonicalJson.writeObject(
                linkedMapOf("text" to AgentArgValue.Text(text))
            ),
            logicalPaths = emptyList(),
            expectedOldHash = null
        )
    }
}

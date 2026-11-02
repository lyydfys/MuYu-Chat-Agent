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
import java.net.URI

/** Host-owned launcher for an approved web URL. */
fun interface DeviceUrlLauncher {
    fun open(url: String): Boolean

    companion object {
        fun android(context: android.content.Context): DeviceUrlLauncher = DeviceUrlLauncher { url ->
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}

/** `device.open_url`: open one approved http/https URL in the user's browser. */
class AgentDeviceOpenUrlTool(
    private val launcher: DeviceUrlLauncher
) : AgentToolExecutor {

    override val descriptor: AgentToolDescriptor = DESCRIPTOR

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("url"))
            val url = AgentToolArguments.requiredText(fields, "url", MAX_URL_CHARS)
            val destination = AgentDeviceUrls.check(url)
            if (destination == null) {
                return@runBuiltInTool AgentToolResults.denied(
                    command,
                    descriptor,
                    "DEVICE_URL_UNSUPPORTED",
                    "只允许 http/https 的普通链接。"
                )
            }
            if (!launcher.open(destination.canonicalUrl)) {
                return@runBuiltInTool AgentToolResults.failure(
                    command,
                    descriptor,
                    "DEVICE_URL_LAUNCH_FAILED",
                    "系统没有可处理该链接的应用，或前台限制阻止了启动。"
                )
            }
            AgentToolResults.success(
                command = command,
                descriptor = descriptor,
                summary = "已请求系统打开链接。",
                data = JSONObject().put("opened", true).put("origin", destination.origin)
            )
        }

    companion object {
        const val TOOL_KEY: String = "device.open_url"
        const val MAX_URL_CHARS: Int = 2_048

        private val SCHEMA_JSON: String = AgentToolJson.canonical(
            JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("url"))
                .put(
                    "properties",
                    JSONObject().put(
                        "url",
                        JSONObject().put("type", "string")
                            .put("minLength", 8).put("maxLength", MAX_URL_CHARS)
                    )
                )
        )

        val DESCRIPTOR: AgentToolDescriptor = AgentToolDescriptor(
            toolKey = TOOL_KEY,
            version = "1",
            displayName = "Open URL",
            description = "Ask the system to open one http/https URL. Requires an approved device scope; " +
                "the browser is a separate app and its result is not observed.",
            schemaJson = SCHEMA_JSON,
            schemaHash = AgentToolJson.sha256Hex(SCHEMA_JSON),
            sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
            dataClass = AgentDataClass.PRIVATE
        )
    }
}

/** Canonical URL policy shared by the normalizer and the executor. */
internal object AgentDeviceUrls {
    data class Destination(val canonicalUrl: String, val origin: String)

    fun check(raw: String): Destination? {
        val url = raw.trim()
        if (url.isEmpty() || url.length > AgentDeviceOpenUrlTool.MAX_URL_CHARS) return null
        if (url.any { it == '\u0000' || it.isISOControl() }) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isBlank()) return null
        if (uri.userInfo != null) return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val originHost = if (host.contains(':')) "[$host]" else host
        return Destination(canonicalUrl = uri.toString(), origin = "$scheme://$originHost$port")
    }
}

/** `device.open_url` takes exactly one URL string. */
class AgentDeviceOpenUrlArgumentNormalizer : AgentToolArgumentNormalizer {
    override fun normalize(descriptor: AgentToolDescriptor, rawArgumentsJson: String): AgentArgumentNormalization {
        if (descriptor.toolKey != AgentDeviceOpenUrlTool.TOOL_KEY) {
            return AgentArgumentNormalization.Rejected(AgentSecurityErrors.toolNotRegistered())
        }
        val fields = when (val parsed = AgentCanonicalJson.parseArguments(rawArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> parsed.fields
            is AgentArgumentsParse.Rejected -> return AgentArgumentNormalization.Rejected(parsed.error)
        }
        if (fields.keys != setOf("url")) {
            return AgentArgumentNormalization.Rejected(
                AgentSecurityErrors.invalidArguments("device.open_url needs exactly url.")
            )
        }
        val raw = (fields["url"] as? AgentArgValue.Text)?.value
            ?: return AgentArgumentNormalization.Rejected(
                AgentSecurityErrors.invalidArguments("device.open_url url is invalid.")
            )
        val destination = AgentDeviceUrls.check(raw)
            ?: return AgentArgumentNormalization.Rejected(
                AgentSecurityErrors.invalidArguments("device.open_url only supports http/https URLs.")
            )
        return AgentArgumentNormalization.Normalized(
            normalizedArgumentsJson = AgentCanonicalJson.writeObject(
                linkedMapOf("url" to AgentArgValue.Text(destination.canonicalUrl))
            ),
            logicalPaths = emptyList(),
            expectedOldHash = null
        )
    }
}

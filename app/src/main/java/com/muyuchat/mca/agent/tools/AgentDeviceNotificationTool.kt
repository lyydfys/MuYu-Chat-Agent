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
import java.util.concurrent.atomic.AtomicInteger

/** One user-visible notification requested by an approved Agent call. */
data class DeviceNotification(val title: String, val body: String)

/**
 * Host-owned notification sink. The tool never touches NotificationManager directly; tests and the
 * production Android adapter implement this seam. `post` returns false when the system refused the
 * post (for example a revoked permission discovered at notify time).
 */
interface DeviceNotificationSink {
    fun canPost(): Boolean

    fun post(notification: DeviceNotification): Boolean

    companion object {
        fun android(context: android.content.Context): DeviceNotificationSink =
            object : DeviceNotificationSink {
                private val nextId = AtomicInteger(1)

                override fun canPost(): Boolean =
                    android.os.Build.VERSION.SDK_INT < 33 ||
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED

                override fun post(notification: DeviceNotification): Boolean = runCatching {
                    val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                        as? android.app.NotificationManager ?: return@runCatching false
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        val channel = android.app.NotificationChannel(
                            "mca_agent_notifications",
                            "Agent 通知",
                            android.app.NotificationManager.IMPORTANCE_DEFAULT
                        )
                        manager.createNotificationChannel(channel)
                    }
                    val builder = androidx.core.app.NotificationCompat.Builder(context, "mca_agent_notifications")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(notification.title)
                        .setContentText(notification.body)
                        .setStyle(
                            androidx.core.app.NotificationCompat.BigTextStyle()
                                .bigText(notification.body)
                        )
                        .setAutoCancel(true)
                    androidx.core.app.NotificationManagerCompat.from(context)
                        .notify(nextId.getAndIncrement(), builder.build())
                    true
                }.getOrDefault(false)
            }
    }
}

/** `device.notify`: post one approved user-visible notification. */
class AgentDeviceNotificationTool(
    private val sink: DeviceNotificationSink
) : AgentToolExecutor {

    override val descriptor: AgentToolDescriptor = DESCRIPTOR

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("title", "body"))
            val title = AgentToolArguments.requiredText(fields, "title", MAX_TITLE_CHARS)
            val body = AgentToolArguments.requiredText(fields, "body", MAX_BODY_CHARS)
            if (!sink.canPost()) {
                return@runBuiltInTool AgentToolResults.denied(
                    command,
                    descriptor,
                    "DEVICE_NOTIFICATION_PERMISSION_DENIED",
                    "通知权限未授权；本次没有发送任何通知。"
                )
            }
            val posted = sink.post(DeviceNotification(title, body))
            if (!posted) {
                return@runBuiltInTool AgentToolResults.failure(
                    command,
                    descriptor,
                    "DEVICE_NOTIFICATION_FAILED",
                    "系统拒绝了通知；本次没有可见效果。"
                )
            }
            AgentToolResults.success(
                command = command,
                descriptor = descriptor,
                summary = "已发送通知。",
                data = JSONObject().put("posted", true).put("title", title)
            )
        }

    companion object {
        const val TOOL_KEY: String = "device.notify"
        const val MAX_TITLE_CHARS: Int = 80
        const val MAX_BODY_CHARS: Int = 500

        private val SCHEMA_JSON: String = AgentToolJson.canonical(
            JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("body").put("title"))
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "title",
                            JSONObject().put("type", "string")
                                .put("minLength", 1).put("maxLength", MAX_TITLE_CHARS)
                        )
                        .put(
                            "body",
                            JSONObject().put("type", "string")
                                .put("minLength", 1).put("maxLength", MAX_BODY_CHARS)
                        )
                )
        )

        val DESCRIPTOR: AgentToolDescriptor = AgentToolDescriptor(
            toolKey = TOOL_KEY,
            version = "1",
            displayName = "Post Notification",
            description = "Post one user-visible notification with a title and body. Requires the " +
                "user-granted notification permission and an approved device scope.",
            schemaJson = SCHEMA_JSON,
            schemaHash = AgentToolJson.sha256Hex(SCHEMA_JSON),
            sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
            dataClass = AgentDataClass.PRIVATE
        )
    }
}

/** Strict `device.notify` arguments: title and body, both required, nothing else. */
class AgentDeviceNotificationArgumentNormalizer : AgentToolArgumentNormalizer {
    override fun normalize(descriptor: AgentToolDescriptor, rawArgumentsJson: String): AgentArgumentNormalization {
        if (descriptor.toolKey != AgentDeviceNotificationTool.TOOL_KEY) {
            return AgentArgumentNormalization.Rejected(AgentSecurityErrors.toolNotRegistered())
        }
        val fields = when (val parsed = AgentCanonicalJson.parseArguments(rawArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> parsed.fields
            is AgentArgumentsParse.Rejected -> return AgentArgumentNormalization.Rejected(parsed.error)
        }
        if (fields.keys != setOf("title", "body")) {
            return AgentArgumentNormalization.Rejected(
                AgentSecurityErrors.invalidArguments("device.notify needs exactly title and body.")
            )
        }
        val title = (fields["title"] as? AgentArgValue.Text)?.value
            ?.takeIf { it.isNotBlank() && it.length <= AgentDeviceNotificationTool.MAX_TITLE_CHARS }
            ?: return AgentArgumentNormalization.Rejected(
                AgentSecurityErrors.invalidArguments("device.notify title is invalid.")
            )
        val body = (fields["body"] as? AgentArgValue.Text)?.value
            ?.takeIf { it.isNotBlank() && it.length <= AgentDeviceNotificationTool.MAX_BODY_CHARS }
            ?: return AgentArgumentNormalization.Rejected(
                AgentSecurityErrors.invalidArguments("device.notify body is invalid.")
            )
        return AgentArgumentNormalization.Normalized(
            normalizedArgumentsJson = AgentCanonicalJson.writeObject(
                linkedMapOf(
                    "title" to AgentArgValue.Text(title),
                    "body" to AgentArgValue.Text(body)
                )
            ),
            logicalPaths = emptyList(),
            expectedOldHash = null
        )
    }
}

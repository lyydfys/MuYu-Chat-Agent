package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import org.json.JSONObject

/** Host-owned launcher for the app's notification settings screen. */
fun interface DeviceAppSettingsLauncher {
    fun open(): Boolean

    companion object {
        fun android(context: android.content.Context): DeviceAppSettingsLauncher =
            DeviceAppSettingsLauncher {
                runCatching {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    )
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
            }
    }
}

/** `device.open_notification_settings`: send the user to this app's notification settings. */
class AgentDeviceNotificationSettingsTool(
    private val launcher: DeviceAppSettingsLauncher
) : AgentToolExecutor {

    override val descriptor: AgentToolDescriptor = DESCRIPTOR

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            if (!launcher.open()) {
                return@runBuiltInTool AgentToolResults.failure(
                    command,
                    descriptor,
                    "DEVICE_SETTINGS_LAUNCH_FAILED",
                    "系统没有打开通知设置。"
                )
            }
            AgentToolResults.success(
                command = command,
                descriptor = descriptor,
                summary = "已打开本应用的通知设置。",
                data = JSONObject().put("opened", true)
            )
        }

    companion object {
        const val TOOL_KEY: String = "device.open_notification_settings"

        private val SCHEMA_JSON: String = AgentToolJson.canonical(
            JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("properties", JSONObject())
        )

        val DESCRIPTOR: AgentToolDescriptor = AgentToolDescriptor(
            toolKey = TOOL_KEY,
            version = "1",
            displayName = "Open Notification Settings",
            description = "Open this app's notification settings so the user can grant or revoke the " +
                "notification permission. No setting is changed by the tool itself.",
            schemaJson = SCHEMA_JSON,
            schemaHash = AgentToolJson.sha256Hex(SCHEMA_JSON),
            sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
            dataClass = AgentDataClass.PRIVATE
        )
    }
}

package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentEffectKind
import com.muyuchat.core.agent.contract.AgentResourceScope
import com.muyuchat.core.agent.contract.AgentScopePrecondition
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import com.muyuchat.mca.agent.security.AgentArgumentsParse
import com.muyuchat.mca.agent.security.AgentCanonicalJson
import com.muyuchat.mca.agent.security.AgentSecurityErrors
import com.muyuchat.mca.agent.security.AgentToolArgumentNormalizer
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot of non-identifying device facts; no serial, account, location or package list. */
data class DeviceInfoSnapshot(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val supportedAbis: List<String>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("androidRelease", androidRelease)
        .put("sdkInt", sdkInt)
        .put("supportedAbis", JSONArray(supportedAbis))
}

/** Read-only device information source. The production default reads `android.os.Build` only. */
fun interface DeviceInfoSource {
    fun read(): DeviceInfoSnapshot

    companion object {
        fun androidBuild(): DeviceInfoSource = DeviceInfoSource {
            DeviceInfoSnapshot(
                manufacturer = android.os.Build.MANUFACTURER.orEmpty().take(64),
                model = android.os.Build.MODEL.orEmpty().take(64),
                androidRelease = android.os.Build.VERSION.RELEASE.orEmpty().take(32),
                sdkInt = android.os.Build.VERSION.SDK_INT,
                supportedAbis = android.os.Build.SUPPORTED_ABIS.orEmpty().take(4)
            )
        }
    }
}

/**
 * The first Android-native tool: `device.info`.
 *
 * It is read-only and exposes only coarse Build facts. It still goes through the normal approval /
 * frozen-scope path with [AgentResourceScopeDevice]; the executor never receives a Context, an
 * account, a location or the installed-package list.
 */
class AgentDeviceInfoTool(
    private val source: DeviceInfoSource = DeviceInfoSource.androidBuild()
) : AgentToolExecutor {

    override val descriptor: AgentToolDescriptor = DESCRIPTOR

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            val snapshot = source.read()
            AgentToolResults.success(
                command = command,
                descriptor = descriptor,
                summary = "已读取设备型号与系统版本。",
                data = snapshot.toJson()
            )
        }

    companion object {
        const val TOOL_KEY: String = "device.info"

        private val SCHEMA_JSON: String = AgentToolJson.canonical(
            JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("properties", JSONObject())
        )

        val DESCRIPTOR: AgentToolDescriptor = AgentToolDescriptor(
            toolKey = TOOL_KEY,
            version = "1",
            displayName = "Device Information",
            description = "Read coarse device facts (manufacturer, model, Android release, SDK, ABIs). " +
                "No serial, account, location or installed-package data.",
            schemaJson = SCHEMA_JSON,
            schemaHash = AgentToolJson.sha256Hex(SCHEMA_JSON),
            sideEffect = AgentToolSideEffect.READ_ONLY,
            dataClass = AgentDataClass.PRIVATE
        )
    }
}

/** `device.info` takes no arguments; unknown fields are rejected before freezing. */
class AgentDeviceInfoArgumentNormalizer : AgentToolArgumentNormalizer {
    override fun normalize(descriptor: AgentToolDescriptor, rawArgumentsJson: String): AgentArgumentNormalization {
        if (descriptor.toolKey != AgentDeviceInfoTool.TOOL_KEY &&
            descriptor.toolKey != AgentDeviceStatusTool.TOOL_KEY &&
            descriptor.toolKey != AgentDeviceNotificationSettingsTool.TOOL_KEY
        ) {
            return AgentArgumentNormalization.Rejected(AgentSecurityErrors.toolNotRegistered())
        }
        val parsed = when (val result = AgentCanonicalJson.parseArguments(rawArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> result.fields
            is AgentArgumentsParse.Rejected -> return AgentArgumentNormalization.Rejected(result.error)
        }
        if (parsed.isNotEmpty()) {
            return AgentArgumentNormalization.Rejected(
                AgentSecurityErrors.invalidArguments("device read tools take no arguments.")
            )
        }
        return AgentArgumentNormalization.Normalized(
            normalizedArgumentsJson = "{}",
            logicalPaths = emptyList(),
            expectedOldHash = null
        )
    }
}

/** Coarse power/network/storage facts; no identifiers, SSIDs, accounts or app usage. */
data class DeviceStatusSnapshot(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val networkType: String?,
    val storageFreeBytes: Long?,
    val storageTotalBytes: Long?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("batteryPercent", batteryPercent ?: JSONObject.NULL)
        .put("charging", charging ?: JSONObject.NULL)
        .put("networkType", networkType ?: JSONObject.NULL)
        .put("storageFreeBytes", storageFreeBytes ?: JSONObject.NULL)
        .put("storageTotalBytes", storageTotalBytes ?: JSONObject.NULL)
}

fun interface DeviceStatusSource {
    fun read(): DeviceStatusSnapshot

    companion object {
        fun android(context: android.content.Context): DeviceStatusSource = DeviceStatusSource {
            val battery = runCatching {
                val intent = context.registerReceiver(
                    null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                )
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
                val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                percent to (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0)
            }.getOrNull()
            val network = runCatching {
                val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager ?: return@runCatching null
                val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching null
                when {
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "other"
                }
            }.getOrNull()
            val storage = runCatching {
                val stats = android.os.StatFs(context.filesDir.path)
                stats.availableBytes to stats.totalBytes
            }.getOrNull()
            DeviceStatusSnapshot(
                batteryPercent = battery?.first,
                charging = battery?.second,
                networkType = network,
                storageFreeBytes = storage?.first,
                storageTotalBytes = storage?.second
            )
        }
    }
}

/** Read-only `device.status`: battery/charging, active transport kind and data-partition storage. */
class AgentDeviceStatusTool(
    private val source: DeviceStatusSource
) : AgentToolExecutor {

    override val descriptor: AgentToolDescriptor = DESCRIPTOR

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            AgentToolResults.success(
                command = command,
                descriptor = descriptor,
                summary = "已读取电量、充电状态、网络类型与存储余量。",
                data = source.read().toJson()
            )
        }

    companion object {
        const val TOOL_KEY: String = "device.status"

        private val SCHEMA_JSON: String = AgentToolJson.canonical(
            JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("properties", JSONObject())
        )

        val DESCRIPTOR: AgentToolDescriptor = AgentToolDescriptor(
            toolKey = TOOL_KEY,
            version = "1",
            displayName = "Device Status",
            description = "Read coarse device status (battery percent/charging, active network transport, " +
                "data-partition free/total bytes). No identifiers, SSIDs, accounts or app usage.",
            schemaJson = SCHEMA_JSON,
            schemaHash = AgentToolJson.sha256Hex(SCHEMA_JSON),
            sideEffect = AgentToolSideEffect.READ_ONLY,
            dataClass = AgentDataClass.PRIVATE
        )
    }
}

/**
 * Typed device scopes.
 *
 * `AgentScopePrecondition.requiredEffects(Device)` is `DEVICE_ACTION`; using LOCAL_READ would make
 * the precondition constructor throw at freeze time, so the effect set is defined once here and
 * covered by a unit test.
 */
object AgentDeviceScopes {
    fun info(): AgentScopePrecondition = precondition(
        operation = AgentDeviceInfoTool.TOOL_KEY,
        summary = "读取设备型号、Android 版本与 ABI（只读）"
    )

    fun status(): AgentScopePrecondition = precondition(
        operation = AgentDeviceStatusTool.TOOL_KEY,
        summary = "读取电量、充电状态、网络类型与存储余量（只读）"
    )

    fun notificationSettings(): AgentScopePrecondition = precondition(
        operation = AgentDeviceNotificationSettingsTool.TOOL_KEY,
        summary = "打开本应用的通知设置"
    )

    /** Clipboard write binds the exact text hash; the summary never repeats the text. */
    fun clipboardWrite(normalizedArgumentsJson: String): AgentScopePrecondition? {
        val parsed = com.muyuchat.mca.agent.security.AgentCanonicalJson
            .parseArguments(normalizedArgumentsJson)
        val fields = (parsed as? com.muyuchat.mca.agent.security.AgentArgumentsParse.Parsed)
            ?.fields ?: return null
        val text = (fields["text"] as? com.muyuchat.mca.agent.security.AgentArgValue.Text)?.value
            ?: return null
        val hash = com.muyuchat.mca.agent.security.AgentCanonicalJson.sha256Hex(
            text.toByteArray(Charsets.UTF_8)
        )
        return AgentScopePrecondition(
            scopes = listOf(
                AgentResourceScope.Device(
                    operation = AgentDeviceClipboardTool.TOOL_KEY,
                    selectorHash = hash,
                    userVisibleSummary = "复制 ${text.length} 个字符到剪贴板"
                )
            ),
            effects = setOf(AgentEffectKind.DEVICE_ACTION)
        )
    }

    /** URL scope binds the exact URL hash; only origin is surfaced as the summary. */
    fun openUrl(normalizedArgumentsJson: String): AgentScopePrecondition? {
        val parsed = com.muyuchat.mca.agent.security.AgentCanonicalJson
            .parseArguments(normalizedArgumentsJson)
        val fields = (parsed as? com.muyuchat.mca.agent.security.AgentArgumentsParse.Parsed)
            ?.fields ?: return null
        val raw = (fields["url"] as? com.muyuchat.mca.agent.security.AgentArgValue.Text)?.value
            ?: return null
        val destination = AgentDeviceUrls.check(raw) ?: return null
        val hash = com.muyuchat.mca.agent.security.AgentCanonicalJson.sha256Hex(
            destination.canonicalUrl.toByteArray(Charsets.UTF_8)
        )
        return AgentScopePrecondition(
            scopes = listOf(
                AgentResourceScope.Device(
                    operation = AgentDeviceOpenUrlTool.TOOL_KEY,
                    selectorHash = hash,
                    userVisibleSummary = "打开链接：" + destination.origin
                )
            ),
            effects = setOf(AgentEffectKind.DEVICE_ACTION)
        )
    }

    /** Notification scope binds the exact title/body hash; model text is summarized, not trusted. */
    fun notification(normalizedArgumentsJson: String): AgentScopePrecondition? {
        val parsed = com.muyuchat.mca.agent.security.AgentCanonicalJson
            .parseArguments(normalizedArgumentsJson)
        val fields = (parsed as? com.muyuchat.mca.agent.security.AgentArgumentsParse.Parsed)
            ?.fields ?: return null
        val title = (fields["title"] as? com.muyuchat.mca.agent.security.AgentArgValue.Text)?.value
            ?: return null
        val body = (fields["body"] as? com.muyuchat.mca.agent.security.AgentArgValue.Text)?.value
            ?: return null
        val hash = com.muyuchat.mca.agent.security.AgentCanonicalJson.sha256Hex(
            (title + "\n" + body).toByteArray(Charsets.UTF_8)
        )
        return AgentScopePrecondition(
            scopes = listOf(
                AgentResourceScope.Device(
                    operation = AgentDeviceNotificationTool.TOOL_KEY,
                    selectorHash = hash,
                    userVisibleSummary = "发送通知：" + title.take(40)
                )
            ),
            effects = setOf(AgentEffectKind.DEVICE_ACTION)
        )
    }

    private fun precondition(operation: String, summary: String): AgentScopePrecondition =
        AgentScopePrecondition(
            scopes = listOf(
                AgentResourceScope.Device(operation = operation, userVisibleSummary = summary)
            ),
            effects = setOf(AgentEffectKind.DEVICE_ACTION)
        )
}

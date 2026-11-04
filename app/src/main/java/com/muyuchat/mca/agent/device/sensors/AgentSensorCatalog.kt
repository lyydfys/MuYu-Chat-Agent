package com.muyuchat.mca.agent.device.sensors

/**
 * Catalogue of the device sensors the DSH bridge may read on behalf of the official mobile tool
 * packages.
 *
 * Design rules (from the user's "DSH 100% parity" request and the project hard lock):
 *  - every capability is **off by default** and is switched on one by one by the user in Settings;
 *  - a read is a **single sample**, never a subscription: no listener stays registered, so an
 *    authorised capability cannot turn into a background battery drain;
 *  - a capability that needs a runtime permission declares it here, so both the settings card and
 *    the route handler ask the platform with the same string;
 *  - nothing here touches `android.*`: the catalogue is data, and the Android reader is a separate
 *    class, which keeps the whole decision matrix unit-testable on the JVM.
 */
enum class AgentSensorId {
    LIGHT,
    ACCELEROMETER,
    GYROSCOPE,
    MAGNETOMETER,
    BAROMETER,
    STEP_COUNTER,
    LOCATION,
    TORCH
}

/** How one capability is read. The route answers with exactly this shape. */
enum class AgentSensorReadMode {
    /** One sample of a numeric sensor, with the capture timestamp. */
    SAMPLE,

    /** The platform's "steps since boot" counter, presented as today's count. */
    DAILY_TOTAL,

    /** Last known fix only; the reader never requests a fresh location. */
    LAST_KNOWN_LOCATION,

    /** A switch, not a sample. */
    TOGGLE
}

/**
 * One selectable capability.
 *
 * [permission] is the exact platform permission string (or null when the capability needs none —
 * the torch is controlled through the camera service without `CAMERA`). [permissionLabel] is the
 * user-facing name used by the "去系统设置" hint.
 */
data class AgentSensorCapability(
    val id: AgentSensorId,
    val displayName: String,
    val permission: String?,
    val permissionLabel: String?,
    val readMode: AgentSensorReadMode,
    val unit: String,
    val description: String
)

object AgentSensorCatalog {

    const val ACTIVITY_RECOGNITION: String = "android.permission.ACTIVITY_RECOGNITION"
    const val FINE_LOCATION: String = "android.permission.ACCESS_FINE_LOCATION"

    /** Every sensor the bridge can expose, in the order the settings card renders them. */
    val all: List<AgentSensorCapability> = listOf(
        AgentSensorCapability(
            id = AgentSensorId.LIGHT,
            displayName = "光线",
            permission = null,
            permissionLabel = null,
            readMode = AgentSensorReadMode.SAMPLE,
            unit = "lux",
            description = "读取一次环境光强度，用于让智能体判断当前是明亮还是昏暗。"
        ),
        AgentSensorCapability(
            id = AgentSensorId.ACCELEROMETER,
            displayName = "加速度",
            permission = null,
            permissionLabel = null,
            readMode = AgentSensorReadMode.SAMPLE,
            unit = "m/s²",
            description = "读取一次三轴加速度合矢量（含重力），可判断手机是否静止或拿起。"
        ),
        AgentSensorCapability(
            id = AgentSensorId.GYROSCOPE,
            displayName = "陀螺仪",
            permission = null,
            permissionLabel = null,
            readMode = AgentSensorReadMode.SAMPLE,
            unit = "rad/s",
            description = "读取一次三轴角速度合矢量，可判断是否正在转动。"
        ),
        AgentSensorCapability(
            id = AgentSensorId.MAGNETOMETER,
            displayName = "磁力计",
            permission = null,
            permissionLabel = null,
            readMode = AgentSensorReadMode.SAMPLE,
            unit = "µT",
            description = "读取一次三轴磁场合矢量，可粗略判断朝向或附近强磁干扰。"
        ),
        AgentSensorCapability(
            id = AgentSensorId.BAROMETER,
            displayName = "气压",
            permission = null,
            permissionLabel = null,
            readMode = AgentSensorReadMode.SAMPLE,
            unit = "hPa",
            description = "读取一次大气压，可粗略推算高度变化。"
        ),
        AgentSensorCapability(
            id = AgentSensorId.STEP_COUNTER,
            displayName = "步数",
            permission = ACTIVITY_RECOGNITION,
            permissionLabel = "身体活动（活动识别）",
            readMode = AgentSensorReadMode.DAILY_TOTAL,
            unit = "步",
            description = "读取系统当日累计步数（不是本次读取的步数增量）；需要「身体活动」权限。"
        ),
        AgentSensorCapability(
            id = AgentSensorId.LOCATION,
            displayName = "定位",
            permission = FINE_LOCATION,
            permissionLabel = "精确位置",
            readMode = AgentSensorReadMode.LAST_KNOWN_LOCATION,
            unit = "°",
            description = "只读取最近一次已知位置，不主动发起新定位（避免唤醒 GPS 耗电）；需要「位置」权限。"
        ),
        AgentSensorCapability(
            id = AgentSensorId.TORCH,
            displayName = "手电筒",
            permission = null,
            permissionLabel = null,
            readMode = AgentSensorReadMode.TOGGLE,
            unit = "",
            description = "开关后置闪光灯；这是开关而不是采样值，DSH 必须显式指定开或关。"
        )
    )

    private val byId: Map<AgentSensorId, AgentSensorCapability> = all.associateBy { it.id }

    fun capability(id: AgentSensorId): AgentSensorCapability = byId.getValue(id)

    /** Parses the wire name used by the `/sensors?id=` route; null when it is not a capability. */
    fun fromWireName(raw: String?): AgentSensorId? {
        val value = raw?.trim()?.uppercase() ?: return null
        return AgentSensorId.entries.firstOrNull { it.name == value }
    }

    fun wireName(id: AgentSensorId): String = id.name.lowercase()
}

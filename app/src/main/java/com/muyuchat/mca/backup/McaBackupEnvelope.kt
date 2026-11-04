package com.muyuchat.mca.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Identity of the user-initiated backup file.
 *
 * Android auto-backup and device transfer deliberately exclude `mca.db`, the agent workspaces, the
 * DSH home and every credential SharedPreferences (see `res/xml/backup_rules.xml` and
 * `res/xml/data_extraction_rules.xml`), so this file is the only cross-device artifact the app can
 * hand a user today. It is therefore versioned, bounded and verifiable on its own.
 */
internal object McaBackupFormat {

    /** Stable marker; a foreign JSON file must fail with [McaBackupRejection.FOREIGN_FILE]. */
    const val KIND: String = "mca-backup"

    /** Bump together with a documented migration; [McaBackupVerify] rejects anything newer. */
    const val VERSION: Int = 1

    /** A backup is read into memory before it is verified; keep the ceiling small. */
    const val MAX_TEXT_BYTES: Int = 4 * 1024 * 1024

    /** Settings keys in a manifest are a short, explicit list, not a dump. */
    const val MAX_SETTING_ENTRIES: Int = 64

    /** Longest accepted app-version label. */
    const val MAX_APP_VERSION_CHARS: Int = 64
}

/**
 * The logical data families a manifest names.
 *
 * These are the rows the MCA library is made of. The list is the *whitelist*: an envelope missing one
 * of them is not a partial backup, it is an incompatible one, because a restore that silently keeps
 * a table from the old library is worse than refusing it.
 */
internal enum class McaBackupTable(val wireId: String, val label: String) {
    CHAT_SESSIONS("chat_sessions", "对话"),
    CHAT_MESSAGES("chat_messages", "消息"),
    ASSISTANTS("assistants", "助手"),
    WORLD_BOOKS("world_books", "世界书"),
    KNOWLEDGE_BASES("knowledge_bases", "知识库"),
    KNOWLEDGE_DOCUMENTS("knowledge_documents", "知识库文档"),
    SETTINGS("settings", "设置项");

    companion object {
        /** Every manifest must name exactly these families, once each. */
        val REQUIRED: List<McaBackupTable> = entries.toList()

        fun fromWireId(raw: String): McaBackupTable? = entries.firstOrNull { it.wireId == raw }
    }
}

/** One manifest row: how many rows of [table] the backup claims to carry. */
internal data class McaBackupTableCount(val table: McaBackupTable, val rows: Int) {
    init {
        require(rows >= 0) { "backup row count must be non-negative, was $rows" }
    }
}

/**
 * The user-facing backup envelope.
 *
 * [settings] is an explicit, already-sanitised map: [McaBackupSecrets] removes every credential-like
 * key before an envelope can be built, and both encoding and verification drop or reject such a key,
 * so a backup can never carry an API key, token or password by accident.
 */
internal data class McaBackupEnvelope(
    val version: Int = McaBackupFormat.VERSION,
    val createdAtMillis: Long,
    val appVersionName: String,
    val tables: List<McaBackupTableCount>,
    val settings: Map<String, String>
) {
    /** Total rows the manifest accounts for; used by the UI and by the verifier's summary. */
    val totalRows: Int get() = tables.sumOf { it.rows }

    fun rowCountOf(table: McaBackupTable): Int =
        tables.firstOrNull { it.table == table }?.rows ?: 0

    /** Wire form. Sensitive settings keys are removed here as a last line of defence. */
    fun toJson(): String {
        val safeSettings = McaBackupSecrets.sanitizeSettings(settings)
        val tableArray = JSONArray()
        tables
            .sortedBy { it.table.ordinal }
            .forEach { entry ->
                tableArray.put(
                    JSONObject()
                        .put("id", entry.table.wireId)
                        .put("rows", entry.rows)
                )
            }
        val settingsJson = JSONObject()
        safeSettings.toSortedMap().forEach { (key, value) -> settingsJson.put(key, value) }
        return JSONObject()
            .put("kind", McaBackupFormat.KIND)
            .put("version", version)
            .put("createdAt", createdAtMillis)
            .put("appVersion", appVersionName)
            .put("tables", tableArray)
            .put("settings", settingsJson)
            .toString(2)
    }
}

/** Outcome of reading a JSON document as a backup envelope. */
internal sealed interface McaBackupDecodeResult {
    data class Decoded(val envelope: McaBackupEnvelope) : McaBackupDecodeResult

    /** Structural problem; [reason] is human-readable and safe to show in the settings page. */
    data class Malformed(val rejection: McaBackupRejection, val reason: String) : McaBackupDecodeResult
}

/**
 * Parses a backup document.
 *
 * Only structure is checked here (kind, numeric fields, table ids, setting types). Semantic rules —
 * version support, whitelist completeness, duplicate tables, sensitive keys, timestamps — belong to
 * [McaBackupVerify] so the two concerns can be tested and reported separately.
 */
internal fun decodeMcaBackupEnvelope(raw: String): McaBackupDecodeResult {
    if (raw.isBlank()) {
        return McaBackupDecodeResult.Malformed(McaBackupRejection.FOREIGN_FILE, "文件为空")
    }
    if (raw.toByteArray(Charsets.UTF_8).size > McaBackupFormat.MAX_TEXT_BYTES) {
        return McaBackupDecodeResult.Malformed(
            McaBackupRejection.TOO_LARGE,
            "备份文件超过 ${McaBackupFormat.MAX_TEXT_BYTES / 1024 / 1024} MB 上限"
        )
    }
    val root = runCatching { JSONObject(raw) }.getOrElse {
        return McaBackupDecodeResult.Malformed(McaBackupRejection.FOREIGN_FILE, "不是有效的 JSON 对象")
    }
    val kind = root.optString("kind", "")
    if (kind != McaBackupFormat.KIND) {
        return McaBackupDecodeResult.Malformed(
            McaBackupRejection.FOREIGN_FILE,
            "不是 MCA 备份文件（kind=$kind）"
        )
    }
    val version = root.optInt("version", -1)
    if (version <= 0) {
        return McaBackupDecodeResult.Malformed(McaBackupRejection.VERSION_UNSUPPORTED, "缺少备份版本号")
    }
    val createdAt = root.optLong("createdAt", 0L)
    val appVersion = root.optString("appVersion", "").trim()
    if (appVersion.length > McaBackupFormat.MAX_APP_VERSION_CHARS) {
        return McaBackupDecodeResult.Malformed(
            McaBackupRejection.TIMESTAMP_INVALID,
            "应用版本标签过长"
        )
    }
    val tableArray = root.optJSONArray("tables")
        ?: return McaBackupDecodeResult.Malformed(McaBackupRejection.TABLE_MISSING, "缺少表清单")
    val tables = ArrayList<McaBackupTableCount>(tableArray.length())
    for (index in 0 until tableArray.length()) {
        val entry = tableArray.optJSONObject(index) ?: continue
        val id = entry.optString("id", "")
        val table = McaBackupTable.fromWireId(id)
            ?: return McaBackupDecodeResult.Malformed(
                McaBackupRejection.TABLE_UNKNOWN,
                "未知表：${id.ifBlank { "(空)" }}"
            )
        val rows = entry.optInt("rows", -1)
        if (rows < 0) {
            return McaBackupDecodeResult.Malformed(
                McaBackupRejection.ROW_COUNT_INVALID,
                "表 ${table.wireId} 的行数无效"
            )
        }
        tables.add(McaBackupTableCount(table, rows))
    }
    val settingsJson = root.optJSONObject("settings") ?: JSONObject()
    val settings = LinkedHashMap<String, String>()
    settingsJson.keys().forEach { key ->
        val value = settingsJson.opt(key)
        settings[key] = when (value) {
            null, JSONObject.NULL -> ""
            is String -> value
            else -> value.toString()
        }
    }
    return McaBackupDecodeResult.Decoded(
        McaBackupEnvelope(
            version = version,
            createdAtMillis = createdAt,
            appVersionName = appVersion,
            tables = tables,
            settings = settings
        )
    )
}

/** Why a file is not accepted as a backup. Rendered verbatim in the settings page. */
internal enum class McaBackupRejection(val message: String) {
    FOREIGN_FILE("不是 MCA 备份文件"),
    TOO_LARGE("备份文件过大"),
    VERSION_UNSUPPORTED("备份版本不受支持"),
    TABLE_MISSING("备份缺少必要的数据表"),
    TABLE_UNKNOWN("备份包含未知的数据表"),
    TABLE_DUPLICATE("备份重复声明了数据表"),
    ROW_COUNT_INVALID("备份的行数不合法"),
    TIMESTAMP_INVALID("备份的时间戳不合法"),
    SENSITIVE_SETTING("备份包含敏感设置项"),
    SETTING_LIMIT_EXCEEDED("备份的设置项过多"),
    ENVELOPE_INCOMPATIBLE("备份与当前版本不兼容")
}

/**
 * Credential filter for backup settings.
 *
 * A backup is a file a user copies to another device, so it must never contain a key, token or
 * password. Only these explicit, non-secret settings are allowed in a manifest; everything else is
 * dropped before encoding and rejected when found in a decoded file.
 */
internal object McaBackupSecrets {

    private val DENIED_KEY_FRAGMENTS = listOf(
        "apikey",
        "api_key",
        "token",
        "secret",
        "password",
        "passwd",
        "credential",
        "authorization",
        "cookie",
        "privatekey",
        "bearer"
    )

    /** True when [key] looks like a credential. Case-insensitive and separator-insensitive. */
    fun isSensitive(key: String): Boolean {
        val normalized = key.lowercase().replace("-", "").replace("_", "").replace(".", "")
        if (normalized.isEmpty()) return true
        return DENIED_KEY_FRAGMENTS.any { fragment ->
            normalized.contains(fragment.replace("_", ""))
        }
    }

    /** Drops every sensitive key. Never rewrites a value, so a manifest cannot be half-redacted. */
    fun sanitizeSettings(settings: Map<String, String>): Map<String, String> =
        settings.filterKeys { !isSensitive(it) }

    /** Allowed keys still must not look like a secret once they are placed in the file. */
    fun findSensitiveKey(settings: Map<String, String>): String? = settings.keys.firstOrNull(::isSensitive)
}

/** Exposed for the "空库" case: a brand-new installation still produces a valid manifest. */
internal fun emptyMcaBackupEnvelope(
    createdAtMillis: Long,
    appVersionName: String
): McaBackupEnvelope = McaBackupEnvelope(
    createdAtMillis = createdAtMillis,
    appVersionName = appVersionName,
    tables = McaBackupTable.REQUIRED.map { McaBackupTableCount(it, 0) },
    settings = emptyMap()
)

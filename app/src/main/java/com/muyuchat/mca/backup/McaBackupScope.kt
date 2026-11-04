package com.muyuchat.mca.backup

/**
 * What a backup may cover.
 *
 * DSHA offers scoped backups (everything / conversations only / plugins only / settings only); MCA
 * only has the conversation, assistant, knowledge and settings data that lives in `mca.db`, so a
 * scope decides **which tables are exported** — and, on restore, which ones may be replaced. Anything
 * outside the scope is left exactly as it is; the restore engine only ever writes the planned tables.
 */
internal enum class McaBackupScope(val wireName: String, val label: String) {
    /** Everything this build can back up (the historical behaviour, also the default of old files). */
    FULL("full", "全量"),

    /** Conversations, their messages and the assistant cards they reference. */
    CHAT("chat", "只对话"),

    /** DSH plugins live in the official DSH home, not in `mca.db`; this scope is a manifest-only note. */
    PLUGINS("plugins", "只插件"),

    /** Non-secret settings only; nothing in this scope is written back into `mca.db`. */
    SETTINGS("settings", "只设置");

    companion object {
        /** Old packages have no `scope` key and mean [FULL]; an unknown value is refused later. */
        fun fromWireName(raw: String?): McaBackupScope? = when {
            raw.isNullOrBlank() -> FULL
            else -> entries.firstOrNull { it.wireName.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

/**
 * The scope → content matrix.
 *
 * `tablesFor` is also the restore whitelist: the engine only deletes and rewrites the tables named
 * here, which is what makes "只对话" safe for a database that also holds knowledge bases and assistants.
 */
internal object McaBackupScopePlan {

    fun tablesFor(scope: McaBackupScope): List<McaBackupTable> = when (scope) {
        // World books are a JSON file store and settings travel in the settings map, so neither is a
        // table here; only the database-backed families are restorable in any scope.
        McaBackupScope.FULL -> McaBackupTable.RESTORABLE
        McaBackupScope.CHAT -> listOf(
            McaBackupTable.CHAT_SESSIONS,
            McaBackupTable.CHAT_MESSAGES,
            McaBackupTable.ASSISTANTS
        )
        McaBackupScope.PLUGINS -> emptyList()
        McaBackupScope.SETTINGS -> emptyList()
    }

    /** Manifest families for a scope: everything for FULL, the scope's tables otherwise. */
    fun manifestTables(scope: McaBackupScope): List<McaBackupTable> = when (scope) {
        McaBackupScope.FULL -> McaBackupTable.REQUIRED
        else -> tablesFor(scope)
    }

    /** True when [scope] carries the non-secret settings map. */
    fun includesSettings(scope: McaBackupScope): Boolean =
        scope == McaBackupScope.FULL || scope == McaBackupScope.SETTINGS

    /** True when [scope] promises to export the given family (used by the coverage tests). */
    fun covers(scope: McaBackupScope, table: McaBackupTable): Boolean =
        when (scope) {
            McaBackupScope.FULL -> true
            else -> table in tablesFor(scope)
        }

    /**
     * Scope-specific explanation for a restore that has nothing to write, so the UI can say *why*
     * a plugins/settings package cannot be restored instead of reporting a generic failure.
     */
    fun restoreUnavailableReason(scope: McaBackupScope): String? = when (scope) {
        McaBackupScope.PLUGINS ->
            "插件备份只记录清单：DSH 插件在官方运行目录，不在本机数据库，无法从这里恢复"
        McaBackupScope.SETTINGS ->
            "设置备份只包含非敏感设置项，不写入数据库；恢复请使用全量或只对话备份"
        else -> null
    }
}

package com.muyuchat.mca.backup

/**
 * Result of checking a candidate backup against this installation's format.
 *
 * The settings page renders exactly one of the two branches, so a user never reads "restored" for a
 * file that was only parsed.
 */
internal sealed interface McaBackupVerification {
    /** The manifest is complete, version-compatible and free of credentials. */
    data class Compatible(
        val envelope: McaBackupEnvelope,
        val totalRows: Int
    ) : McaBackupVerification

    /** The file is a backup of something this build cannot safely consume. */
    data class Incompatible(
        val rejection: McaBackupRejection,
        val reason: String
    ) : McaBackupVerification

    val isCompatible: Boolean get() = this is Compatible
}

/**
 * Verifies a backup envelope.
 *
 * Rules, in the order they run so the first failure names the most specific problem:
 *  1. the document must decode at all (kind/JSON/shape) — [decodeMcaBackupEnvelope];
 *  2. the version must be exactly the supported one; a newer file is never "mostly readable";
 *  3. every whitelisted family must be declared exactly once, and nothing outside the whitelist;
 *  4. row counts must be non-negative and the timestamp plausible;
 *  5. no setting may look like a credential, and the settings map must stay small.
 *
 * An empty library (all counts zero) is explicitly *compatible*: a fresh installation must be able
 * to export and re-verify its own backup.
 */
internal object McaBackupVerify {

    fun verify(raw: String): McaBackupVerification = when (val decoded = decodeMcaBackupEnvelope(raw)) {
        is McaBackupDecodeResult.Malformed ->
            McaBackupVerification.Incompatible(decoded.rejection, decoded.reason)

        is McaBackupDecodeResult.Decoded -> verify(decoded.envelope)
    }

    fun verify(envelope: McaBackupEnvelope): McaBackupVerification {
        if (envelope.version != McaBackupFormat.VERSION) {
            return McaBackupVerification.Incompatible(
                McaBackupRejection.VERSION_UNSUPPORTED,
                "备份版本 ${envelope.version}，当前只支持 ${McaBackupFormat.VERSION}"
            )
        }
        if (envelope.createdAtMillis <= 0L) {
            return McaBackupVerification.Incompatible(
                McaBackupRejection.TIMESTAMP_INVALID,
                "备份缺少创建时间"
            )
        }
        if (envelope.tables.isEmpty()) {
            return McaBackupVerification.Incompatible(
                McaBackupRejection.ENVELOPE_INCOMPATIBLE,
                "备份没有声明任何数据表"
            )
        }
        val duplicates = envelope.tables
            .groupingBy { it.table }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicates.isNotEmpty()) {
            return McaBackupVerification.Incompatible(
                McaBackupRejection.TABLE_DUPLICATE,
                "重复声明：${duplicates.joinToString(", ") { it.wireId }}"
            )
        }
        val missing = McaBackupTable.REQUIRED.filter { required ->
            envelope.tables.none { it.table == required }
        }
        if (missing.isNotEmpty()) {
            return McaBackupVerification.Incompatible(
                McaBackupRejection.TABLE_MISSING,
                "缺少：${missing.joinToString(", ") { it.label }}"
            )
        }
        val negative = envelope.tables.firstOrNull { it.rows < 0 }
        if (negative != null) {
            return McaBackupVerification.Incompatible(
                McaBackupRejection.ROW_COUNT_INVALID,
                "表 ${negative.table.wireId} 的行数为负"
            )
        }
        if (envelope.settings.size > McaBackupFormat.MAX_SETTING_ENTRIES) {
            return McaBackupVerification.Incompatible(
                McaBackupRejection.SETTING_LIMIT_EXCEEDED,
                "设置项 ${envelope.settings.size} 条，超过 ${McaBackupFormat.MAX_SETTING_ENTRIES} 条上限"
            )
        }
        val sensitive = McaBackupSecrets.findSensitiveKey(envelope.settings)
        if (sensitive != null) {
            return McaBackupVerification.Incompatible(
                McaBackupRejection.SENSITIVE_SETTING,
                "备份包含疑似凭据的设置项：$sensitive"
            )
        }
        return McaBackupVerification.Compatible(envelope, envelope.totalRows)
    }

    /** One-line summary for the settings page; never echoes raw JSON or settings values. */
    fun describe(verification: McaBackupVerification): String = when (verification) {
        is McaBackupVerification.Compatible -> buildString {
            append("可恢复：")
            append(verification.envelope.tables.size).append(" 张表 / ")
            append(verification.totalRows).append(" 行")
            val appVersion = verification.envelope.appVersionName
            if (appVersion.isNotBlank()) append("；导出自 ").append(appVersion)
        }

        is McaBackupVerification.Incompatible ->
            "${verification.rejection.message}：${verification.reason}"
    }
}

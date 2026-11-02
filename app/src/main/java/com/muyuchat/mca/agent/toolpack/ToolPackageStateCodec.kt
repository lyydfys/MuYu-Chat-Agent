package com.muyuchat.mca.agent.toolpack

import org.json.JSONObject

/** One in-flight install transaction, persisted only so a crash can be repaired. */
internal data class ToolPackageJournalEntry(
    val txnId: String,
    val contentHash: String,
    val packageId: String,
    val phase: String
)

/**
 * Durable encoding of the activation pointer and install journal.
 *
 * Keeping the (small) wire format in one place means the store has exactly one reader/writer for its
 * state, a v1 record stays readable after the rollback/revocation fields were added, and a malformed
 * file can only ever produce `null` (treated as unreadable) instead of a half-parsed record.
 */
internal object ToolPackageStateCodec {
    /** Oldest durable state format this build still reads. */
    const val MIN_STATE_VERSION: Int = 1

    /** Current durable state format (v2 added `previousContentHash` + `revokedAtMillis`). */
    const val STATE_VERSION: Int = 2

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun encode(record: ToolPackageInstalledRecord): String = JSONObject()
        .put("formatVersion", STATE_VERSION)
        .put("packageId", record.packageId)
        .put("packageVersion", record.packageVersion)
        .put("contentHash", record.contentHash)
        .put("enabled", record.enabled)
        .put("installedAtMillis", record.installedAtMillis)
        .put("revision", record.revision)
        .put("previousContentHash", record.previousContentHash ?: JSONObject.NULL)
        .put("revokedAtMillis", record.revokedAtMillis ?: JSONObject.NULL)
        .toString()

    fun decode(text: String): ToolPackageInstalledRecord? = try {
        val json = JSONObject(text)
        val formatVersion = json.optInt("formatVersion", -1)
        if (formatVersion !in MIN_STATE_VERSION..STATE_VERSION) null else decodeRecord(json)
    } catch (_: Exception) {
        null
    }

    private fun decodeRecord(json: JSONObject): ToolPackageInstalledRecord? {
        val packageId = json.optString("packageId").ifBlank { return null }
        val contentHash = json.optString("contentHash").ifBlank { return null }
        if (!SHA256.matches(contentHash)) return null
        val revision = json.optLong("revision", 0L)
        if (revision <= 0L) return null
        val previousContentHash = json.optString("previousContentHash")
            .takeIf { SHA256.matches(it) && it != contentHash }
        val revokedAtMillis = json.optLong("revokedAtMillis", 0L).takeIf { it > 0L }
        return ToolPackageInstalledRecord(
            packageId = packageId,
            packageVersion = json.optString("packageVersion"),
            contentHash = contentHash,
            enabled = json.optBoolean("enabled", false),
            installedAtMillis = json.optLong("installedAtMillis", 0L),
            revision = revision,
            previousContentHash = previousContentHash,
            revokedAtMillis = revokedAtMillis
        )
    }

    fun encodeJournal(entry: ToolPackageJournalEntry): String = JSONObject()
        .put("txnId", entry.txnId)
        .put("contentHash", entry.contentHash)
        .put("packageId", entry.packageId)
        .put("phase", entry.phase)
        .toString()

    fun decodeJournal(text: String): ToolPackageJournalEntry? = try {
        val json = JSONObject(text)
        ToolPackageJournalEntry(
            txnId = json.optString("txnId"),
            contentHash = json.optString("contentHash"),
            packageId = json.optString("packageId"),
            phase = json.optString("phase")
        ).takeIf { it.txnId.isNotBlank() && it.contentHash.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

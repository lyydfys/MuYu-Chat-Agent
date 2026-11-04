package com.muyuchat.mca.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the compatibility gate in front of a restore.
 *
 * A wrong answer here is expensive in both directions: accepting a truncated or newer package loses
 * user data later, and refusing a valid one makes the export useless. Each rule therefore has its
 * own case, including the empty library a fresh installation produces.
 */
class McaBackupVerifyTest {

    @Test
    fun `a complete version one manifest is compatible`() {
        val verification = McaBackupVerify.verify(envelope().toJson())

        assertTrue(verification is McaBackupVerification.Compatible)
        val compatible = verification as McaBackupVerification.Compatible
        assertEquals(4 + 40 + 2 + 1 + 1 + 3 + 1, compatible.totalRows)
        assertTrue(McaBackupVerify.describe(compatible).startsWith("可恢复："))
    }

    @Test
    fun `a newer version is refused instead of being read optimistically`() {
        val json = envelope().toJson().replace("\"version\": 1", "\"version\": 2")

        val verification = McaBackupVerify.verify(json)

        assertTrue(verification is McaBackupVerification.Incompatible)
        assertEquals(
            McaBackupRejection.VERSION_UNSUPPORTED,
            (verification as McaBackupVerification.Incompatible).rejection
        )
        assertTrue(McaBackupVerify.describe(verification).contains("2"))
    }

    @Test
    fun `a manifest missing a whitelisted table names the missing family`() {
        val incomplete = envelope().copy(
            tables = envelope().tables.filterNot { it.table == McaBackupTable.WORLD_BOOKS }
        )

        val verification = McaBackupVerify.verify(incomplete)

        assertTrue(verification is McaBackupVerification.Incompatible)
        val incompatible = verification as McaBackupVerification.Incompatible
        assertEquals(McaBackupRejection.TABLE_MISSING, incompatible.rejection)
        assertTrue(incompatible.reason.contains("世界书"))
    }

    @Test
    fun `a table declared twice is refused rather than merged`() {
        val duplicated = envelope().let { base ->
            base.copy(tables = base.tables + McaBackupTableCount(McaBackupTable.CHAT_SESSIONS, 9))
        }

        val verification = McaBackupVerify.verify(duplicated) as McaBackupVerification.Incompatible

        assertEquals(McaBackupRejection.TABLE_DUPLICATE, verification.rejection)
    }

    @Test
    fun `a fresh installation with zero rows is compatible`() {
        val verification = McaBackupVerify.verify(
            emptyMcaBackupEnvelope(createdAtMillis = 1_700_000_000_000L, appVersionName = "1.0")
        )

        assertTrue(verification is McaBackupVerification.Compatible)
        assertEquals(0, (verification as McaBackupVerification.Compatible).totalRows)
    }

    @Test
    fun `a crafted file carrying a credential in settings is refused`() {
        val raw = """
            {"kind":"mca-backup","version":1,"createdAt":1700000000000,"appVersion":"1.0",
             "tables":[{"id":"chat_sessions","rows":0},{"id":"chat_messages","rows":0},
                       {"id":"assistants","rows":0},{"id":"world_books","rows":0},
                       {"id":"knowledge_bases","rows":0},{"id":"knowledge_documents","rows":0},
                       {"id":"settings","rows":1}],
             "settings":{"api_key":"sk-hand-written"}}
        """.trimIndent()

        val verification = McaBackupVerify.verify(raw) as McaBackupVerification.Incompatible

        assertEquals(McaBackupRejection.SENSITIVE_SETTING, verification.rejection)
        assertTrue(verification.reason.contains("api_key"))
        // The diagnostic copy must never echo the secret value itself.
        assertFalse(McaBackupVerify.describe(verification).contains("sk-hand-written"))
    }

    @Test
    fun `a missing creation timestamp is refused`() {
        val verification = McaBackupVerify.verify(elapsedEnvelope(createdAtMillis = 0L))

        assertEquals(
            McaBackupRejection.TIMESTAMP_INVALID,
            (verification as McaBackupVerification.Incompatible).rejection
        )
    }

    @Test
    fun `an oversized settings map is refused`() {
        val tooMany = (0 until McaBackupFormat.MAX_SETTING_ENTRIES + 1).associate { index ->
            "generation.custom_$index" to "$index"
        }
        val verification = McaBackupVerify.verify(envelope().copy(settings = tooMany))

        assertEquals(
            McaBackupRejection.SETTING_LIMIT_EXCEEDED,
            (verification as McaBackupVerification.Incompatible).rejection
        )
    }

    @Test
    fun `a non backup json is reported as such and not as a schema problem`() {
        val verification = McaBackupVerify.verify("""{"tables":[]}""")

        assertEquals(
            McaBackupRejection.FOREIGN_FILE,
            (verification as McaBackupVerification.Incompatible).rejection
        )
        assertFalse(verification.reason.isBlank())
        assertNull(verification.reason.takeIf { it.contains("Exception") })
    }

    private fun elapsedEnvelope(createdAtMillis: Long): McaBackupEnvelope =
        envelope().copy(createdAtMillis = createdAtMillis)

    private fun envelope(): McaBackupEnvelope = McaBackupEnvelope(
        createdAtMillis = 1_700_000_000_000L,
        appVersionName = "1.2.3",
        tables = listOf(
            McaBackupTableCount(McaBackupTable.CHAT_SESSIONS, 4),
            McaBackupTableCount(McaBackupTable.CHAT_MESSAGES, 40),
            McaBackupTableCount(McaBackupTable.ASSISTANTS, 2),
            McaBackupTableCount(McaBackupTable.WORLD_BOOKS, 1),
            McaBackupTableCount(McaBackupTable.KNOWLEDGE_BASES, 1),
            McaBackupTableCount(McaBackupTable.KNOWLEDGE_DOCUMENTS, 3),
            McaBackupTableCount(McaBackupTable.SETTINGS, 1)
        ),
        settings = mapOf("preference.mode" to "BALANCED")
    )
}

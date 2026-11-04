package com.muyuchat.mca.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the backup document format that the settings page exports and verifies.
 *
 * The format is the only thing standing between a user's "导出备份包" and a file that could carry a
 * credential or be silently unreadable later, so both the round trip and the hostile inputs are
 * pinned here. Pure JVM: no Android type, no Compose, no device.
 */
class McaBackupEnvelopeTest {

    @Test
    fun `a manifest round trips through json`() {
        val envelope = envelope(
            settings = mapOf(
                "generation.temperature" to "0.7",
                "preference.mode" to "BALANCED"
            )
        )

        val decoded = decoded(envelope.toJson())

        assertEquals(McaBackupFormat.VERSION, decoded.version)
        assertEquals(envelope.createdAtMillis, decoded.createdAtMillis)
        assertEquals("1.2.3", decoded.appVersionName)
        assertEquals(McaBackupTable.REQUIRED.size, decoded.tables.size)
        assertEquals(4, decoded.rowCountOf(McaBackupTable.CHAT_SESSIONS))
        assertEquals("0.7", decoded.settings["generation.temperature"])
    }

    @Test
    fun `a foreign json file is not a backup`() {
        val result = decodeMcaBackupEnvelope("""{"hello":"world"}""")
        assertTrue(result is McaBackupDecodeResult.Malformed)
        assertEquals(
            McaBackupRejection.FOREIGN_FILE,
            (result as McaBackupDecodeResult.Malformed).rejection
        )
    }

    @Test
    fun `broken json and blank input are reported as unreadable instead of throwing`() {
        listOf("", "   ", "{ not json", """{"kind":"mca-backup","version":1}""").forEach { raw ->
            val result = decodeMcaBackupEnvelope(raw)
            assertTrue("input=$raw", result is McaBackupDecodeResult.Malformed)
        }
    }

    @Test
    fun `an unknown table id is rejected with that id in the reason`() {
        val raw = """
            {"kind":"mca-backup","version":1,"createdAt":1000,"appVersion":"1.0",
             "tables":[{"id":"agent_secrets","rows":1}],"settings":{}}
        """.trimIndent()

        val result = decodeMcaBackupEnvelope(raw) as McaBackupDecodeResult.Malformed
        assertEquals(McaBackupRejection.TABLE_UNKNOWN, result.rejection)
        assertTrue(result.reason.contains("agent_secrets"))
    }

    @Test
    fun `credentials never reach the encoded file`() {
        val envelope = envelope(
            settings = mapOf(
                "cloud.apiKey" to "sk-live-should-never-be-written",
                "local_api.token" to "abc",
                "db.password" to "hunter2",
                "generation.top_p" to "0.95"
            )
        )

        val json = envelope.toJson()

        assertFalse(json.contains("sk-live-should-never-be-written"))
        assertFalse(json.contains("hunter2"))
        assertFalse(json.contains("cloud.apiKey"))
        val decoded = decoded(json)
        assertEquals(setOf("generation.top_p"), decoded.settings.keys)
    }

    @Test
    fun `sensitive key detection covers the credential spellings used in settings`() {
        listOf(
            "apiKey",
            "api_key",
            "openai.api-key",
            "accessToken",
            "auth_token",
            "clientSecret",
            "dbPassword",
            "Authorization",
            "cookie",
            "privateKey"
        ).forEach { key ->
            assertTrue("key=$key must be treated as a credential", McaBackupSecrets.isSensitive(key))
        }
        listOf(
            "generation.temperature",
            "generation.n_ctx",
            "preference.mode",
            "local_api.enabled",
            "web_search.enabled"
        ).forEach { key ->
            assertFalse("key=$key must stay exportable", McaBackupSecrets.isSensitive(key))
        }
        assertNull(McaBackupSecrets.findSensitiveKey(mapOf("generation.top_k" to "20")))
    }

    @Test
    fun `an empty library still produces a complete manifest`() {
        val empty = emptyMcaBackupEnvelope(createdAtMillis = 1_700_000_000_000L, appVersionName = "1.0")

        assertEquals(McaBackupTable.REQUIRED.size, empty.tables.size)
        assertEquals(0, empty.totalRows)
        assertFalse(empty.toJson().contains("null"))
        assertEquals(empty.tables.size, decoded(empty.toJson()).tables.size)
    }

    @Test
    fun `suggested file name is stable and bounded`() {
        val name = McaBackupCoordinator.suggestedFileName(nowMillis = 1_700_000_000_000L)

        assertEquals("mca-backup-1700000000000.json", name)
        assertTrue(name.endsWith(".json"))
        assertFalse(McaBackupSecrets.isSensitive("name"))
    }

    private fun decoded(json: String): McaBackupEnvelope =
        (decodeMcaBackupEnvelope(json) as McaBackupDecodeResult.Decoded).envelope

    private fun envelope(settings: Map<String, String> = emptyMap()): McaBackupEnvelope =
        McaBackupEnvelope(
            createdAtMillis = 1_700_000_000_000L,
            appVersionName = "1.2.3",
            tables = listOf(
                McaBackupTableCount(McaBackupTable.CHAT_SESSIONS, 4),
                McaBackupTableCount(McaBackupTable.CHAT_MESSAGES, 40),
                McaBackupTableCount(McaBackupTable.ASSISTANTS, 2),
                McaBackupTableCount(McaBackupTable.WORLD_BOOKS, 1),
                McaBackupTableCount(McaBackupTable.KNOWLEDGE_BASES, 1),
                McaBackupTableCount(McaBackupTable.KNOWLEDGE_DOCUMENTS, 3),
                McaBackupTableCount(McaBackupTable.SETTINGS, settings.size)
            ),
            settings = settings
        )
}

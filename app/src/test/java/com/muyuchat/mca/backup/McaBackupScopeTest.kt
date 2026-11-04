package com.muyuchat.mca.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards scoped backups: what each scope exports, what it may overwrite, and how old files behave.
 *
 * A scope is a safety boundary, so the matrix is asserted directly instead of being inferred from the
 * export code: "只对话" must never be able to replace knowledge bases or assistants it did not carry.
 */
class McaBackupScopeTest {

    @Test
    fun `full covers every database backed family`() {
        assertEquals(McaBackupTable.RESTORABLE, McaBackupScopePlan.tablesFor(McaBackupScope.FULL))
        McaBackupTable.entries.forEach { table ->
            assertTrue(table.wireId, McaBackupScopePlan.covers(McaBackupScope.FULL, table))
        }
        assertEquals(McaBackupTable.REQUIRED, McaBackupScopePlan.manifestTables(McaBackupScope.FULL))
        assertTrue(McaBackupScopePlan.includesSettings(McaBackupScope.FULL))
    }

    @Test
    fun `chat scope carries conversations messages and assistants only`() {
        val tables = McaBackupScopePlan.tablesFor(McaBackupScope.CHAT)

        assertEquals(
            listOf(McaBackupTable.CHAT_SESSIONS, McaBackupTable.CHAT_MESSAGES, McaBackupTable.ASSISTANTS),
            tables
        )
        assertFalse(McaBackupScopePlan.covers(McaBackupScope.CHAT, McaBackupTable.KNOWLEDGE_BASES))
        assertFalse(McaBackupScopePlan.covers(McaBackupScope.CHAT, McaBackupTable.KNOWLEDGE_DOCUMENTS))
        assertFalse("chat scope does not carry the settings map", McaBackupScopePlan.includesSettings(McaBackupScope.CHAT))
        assertFalse(McaBackupScopePlan.covers(McaBackupScope.CHAT, McaBackupTable.WORLD_BOOKS))
    }

    @Test
    fun `plugins and settings scopes have nothing to write into the database`() {
        listOf(McaBackupScope.PLUGINS, McaBackupScope.SETTINGS).forEach { scope ->
            assertTrue(scope.wireName, McaBackupScopePlan.tablesFor(scope).isEmpty())
            assertNotNull(McaBackupScopePlan.restoreUnavailableReason(scope))
        }
        assertTrue(McaBackupScopePlan.includesSettings(McaBackupScope.SETTINGS))
        assertNull(McaBackupScopePlan.restoreUnavailableReason(McaBackupScope.FULL))
    }

    @Test
    fun `a package without a scope field means full`() {
        val raw = fullPackageJson(scopeField = null)

        val decoded = decodeMcaBackupEnvelope(raw) as McaBackupDecodeResult.Decoded

        assertEquals(McaBackupScope.FULL, decoded.envelope.scope)
        assertTrue(McaBackupVerify.verify(decoded.envelope).isCompatible)
        assertTrue(McaBackupRows.plan(decoded.envelope, decoded.envelope.payload) is McaBackupPayloadPlan.Ready)
    }

    @Test
    fun `an unknown scope is refused before it can drive a restore`() {
        val raw = fullPackageJson(scopeField = "\"scope\":\"everything\",")

        val malformed = decodeMcaBackupEnvelope(raw) as McaBackupDecodeResult.Malformed

        assertEquals(McaBackupRejection.UNKNOWN_SCOPE, malformed.rejection)
        assertTrue(malformed.reason.contains("everything"))
    }

    @Test
    fun `a chat package carrying knowledge rows is refused as a scope mismatch`() {
        val envelope = chatEnvelope(scope = McaBackupScope.CHAT)
        val smuggled = envelope.payload + McaBackupRows.build(
            McaBackupTable.KNOWLEDGE_BASES,
            listOf("id"),
            listOf(listOf("kh-1"))
        )

        val rejected = McaBackupRows.plan(envelope, smuggled) as McaBackupPayloadPlan.Rejected

        assertEquals(McaBackupRejection.SCOPE_MISMATCH, rejected.rejection)
        assertTrue(rejected.reason.contains("只对话"))
        assertTrue(rejected.reason.contains("知识库"))
    }

    @Test
    fun `a chat package plans only the chat tables`() {
        val plan = McaBackupRows.plan(chatEnvelope(scope = McaBackupScope.CHAT), chatPayload())

        assertTrue("expected Ready, was $plan", plan is McaBackupPayloadPlan.Ready)
        val ready = plan as McaBackupPayloadPlan.Ready
        assertEquals(
            listOf(McaBackupTable.CHAT_SESSIONS, McaBackupTable.CHAT_MESSAGES, McaBackupTable.ASSISTANTS),
            ready.tables.map { it.table }
        )
        assertEquals(3, ready.totalRows)
    }

    @Test
    fun `a settings package explains why it cannot be restored`() {
        val envelope = McaBackupEnvelope(
            createdAtMillis = 1_700_000_000_000L,
            appVersionName = "1.0",
            tables = emptyList(),
            settings = mapOf("preference.mode" to "BALANCED"),
            scope = McaBackupScope.SETTINGS,
            payload = emptyList()
        )

        val rejected = McaBackupRows.plan(envelope, emptyList()) as McaBackupPayloadPlan.Rejected

        assertEquals(McaBackupRejection.PAYLOAD_MISSING, rejected.rejection)
        assertTrue(rejected.reason.contains("设置"))
    }

    @Test
    fun `the scope is written to the file and survives a round trip`() {
        val envelope = chatEnvelope(scope = McaBackupScope.CHAT)

        val json = envelope.toJson()
        val decoded = decodeMcaBackupEnvelope(json) as McaBackupDecodeResult.Decoded

        assertTrue(json.contains("\"scope\": \"chat\""))
        assertEquals(McaBackupScope.CHAT, decoded.envelope.scope)
        assertEquals(3, decoded.envelope.payload.size)
    }

    private fun chatPayload(): List<McaBackupTableRows> = listOf(
        McaBackupRows.build(McaBackupTable.CHAT_SESSIONS, listOf("id"), listOf(listOf("s1"))),
        McaBackupRows.build(McaBackupTable.CHAT_MESSAGES, listOf("id"), listOf(listOf("m1"))),
        McaBackupRows.build(McaBackupTable.ASSISTANTS, listOf("id"), listOf(listOf("a1")))
    )

    private fun chatEnvelope(scope: McaBackupScope): McaBackupEnvelope {
        val payload = chatPayload()
        return McaBackupEnvelope(
            createdAtMillis = 1_700_000_000_000L,
            appVersionName = "1.0",
            tables = McaBackupTable.REQUIRED.map { table ->
                McaBackupTableCount(table, payload.firstOrNull { it.table == table }?.rowCount ?: 0)
            },
            settings = emptyMap(),
            scope = scope,
            payload = payload
        )
    }

    /** A full package JSON; [scopeField] is inserted verbatim so a null means "old file". */
    private fun fullPackageJson(scopeField: String?): String {
        val payload = McaBackupTable.RESTORABLE.map { table ->
            McaBackupRows.build(table, listOf("id"), listOf(listOf("${table.wireId}-1")))
        }
        val envelope = McaBackupEnvelope(
            createdAtMillis = 1_700_000_000_000L,
            appVersionName = "1.0",
            tables = McaBackupTable.REQUIRED.map { table ->
                McaBackupTableCount(table, payload.firstOrNull { it.table == table }?.rowCount ?: 0)
            },
            settings = mapOf("preference.mode" to "BALANCED"),
            scope = McaBackupScope.FULL,
            payload = payload
        )
        val json = org.json.JSONObject(envelope.toJson().toString())
        if (scopeField == null) {
            json.remove("scope")
        } else {
            val parsed = org.json.JSONObject("{" + scopeField.trim().trimEnd(',') + "}")
            parsed.keys().forEach { key -> json.put(key, parsed.get(key)) }
        }
        return json.toString()
    }
}

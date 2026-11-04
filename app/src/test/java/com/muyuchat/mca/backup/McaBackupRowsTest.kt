package com.muyuchat.mca.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the row payload of a backup: what a restore is allowed to consume.
 *
 * The manifest says "how many rows"; this layer carries the rows and refuses a file whose payload and
 * manifest disagree — a half-usable backup is exactly the state a restore must never start from.
 * Pure JVM, real org.json, no database.
 */
class McaBackupRowsTest {

    @Test
    fun `rows round trip with their types and nulls`() {
        val table = McaBackupRows.build(
            table = McaBackupTable.CHAT_SESSIONS,
            columns = listOf("id", "title", "updatedAt", "pinned", "projectId"),
            rows = listOf(
                listOf("s1", "第一段对话", 1_700_000_000_000L, true, null),
                listOf("s2", "第二段对话", 1_700_000_000_001L, false, "project-1")
            )
        )

        val decoded = McaBackupRows.decode(McaBackupRows.encode(listOf(table)))

        assertNotNull(decoded)
        val restored = decoded!!.single()
        assertEquals(table.columns, restored.columns)
        assertEquals(2, restored.rowCount)
        assertEquals("s1", restored.rows[0][0])
        assertEquals(1_700_000_000_000L, restored.rows[0][2])
        assertEquals(true, restored.rows[0][3])
        assertNull("a NULL cell must stay null, not become the text 'null'", restored.rows[0][4])
        assertEquals("project-1", restored.rows[1][4])
    }

    @Test
    fun `credential-like columns are dropped and recorded`() {
        val table = McaBackupRows.build(
            table = McaBackupTable.ASSISTANTS,
            columns = listOf("id", "name", "apiKey", "cloudToken", "paramsJson"),
            rows = listOf(listOf("a1", "阿米娅", "sk-secret", "tok", "{}"))
        )

        assertEquals(listOf("id", "name", "paramsJson"), table.columns)
        assertEquals(listOf("apiKey", "cloudToken"), table.redactedColumns)
        assertEquals(listOf("a1", "阿米娅", "{}"), table.rows.single())
        assertFalse(McaBackupRows.encode(listOf(table)).toString().contains("sk-secret"))
    }

    @Test
    fun `the payload encodes only for present tables`() {
        val envelope = envelope(payload = listOf(table(McaBackupTable.CHAT_SESSIONS, 1)))

        val json = JSONObject(envelope.toJson())

        assertTrue(json.has("payload"))
        assertEquals(1, json.getJSONArray("payload").length())
    }

    @Test
    fun `a manifest-only backup keeps the old file shape`() {
        val envelope = envelope()

        val json = JSONObject(envelope.toJson())

        assertFalse("an old-style backup must not grow an empty payload array", json.has("payload"))
    }

    @Test
    fun `a complete payload is accepted and reports its counts and redactions`() {
        val tables = McaBackupTable.REQUIRED.map { table ->
            McaBackupRows.build(
                table = table,
                columns = listOf("id", "apiKey"),
                rows = listOf(listOf("${table.wireId}-1", "sk-x"))
            )
        }
        val envelope = envelope(counts = McaBackupTable.REQUIRED.associateWith { 1 })

        val plan = McaBackupRows.plan(envelope, tables)

        assertTrue("expected Ready, was $plan", plan is McaBackupPayloadPlan.Ready)
        val ready = plan as McaBackupPayloadPlan.Ready
        assertEquals(McaBackupTable.REQUIRED.size, ready.tables.size)
        assertEquals(McaBackupTable.REQUIRED.size, ready.totalRows)
        assertTrue(ready.redacted.keys.contains("chat_sessions"))
        assertEquals(listOf("apiKey"), ready.redacted.values.first())
    }

    @Test
    fun `a payload without rows is refused`() {
        val plan = McaBackupRows.plan(envelope(), payload = emptyList())

        assertEquals(
            McaBackupRejection.PAYLOAD_MISSING,
            (plan as McaBackupPayloadPlan.Rejected).rejection
        )
    }

    @Test
    fun `a manifest that disagrees with its payload is refused`() {
        val tables = McaBackupTable.REQUIRED.map { table ->
            // Every table claims one row in the manifest, but the second has two.
            val rows = if (table == McaBackupTable.CHAT_MESSAGES) 2 else 1
            McaBackupRows.build(table, listOf("id"), List(rows) { listOf("x") })
        }
        val envelope = envelope(counts = McaBackupTable.REQUIRED.associateWith { 1 })

        val plan = McaBackupRows.plan(envelope, tables)

        val rejected = plan as McaBackupPayloadPlan.Rejected
        assertEquals(McaBackupRejection.ROW_COUNT_MISMATCH, rejected.rejection)
        assertTrue(rejected.reason.contains("消息"))
        assertTrue(rejected.reason.contains("清单 1 行"))
        assertTrue(rejected.reason.contains("数据 2 行"))
    }

    @Test
    fun `a payload missing a whitelisted family is refused and names it`() {
        val tables = McaBackupTable.REQUIRED
            .filterNot { it == McaBackupTable.CHAT_MESSAGES }
            .map { McaBackupRows.build(it, listOf("id"), listOf(listOf("x"))) }
        val envelope = envelope(counts = McaBackupTable.REQUIRED.associateWith { 1 })

        val rejected = McaBackupRows.plan(envelope, tables) as McaBackupPayloadPlan.Rejected

        assertEquals(McaBackupRejection.TABLE_MISSING, rejected.rejection)
        assertTrue(rejected.reason.contains("消息"))
    }

    @Test
    fun `file-backed families are optional in the payload`() {
        // world_books is a JSON file store and settings live in the settings map: a payload never
        // carries them, and their absence must not block a restore.
        val tables = McaBackupTable.RESTORABLE.map { McaBackupRows.build(it, listOf("id"), emptyList()) }
        val envelope = envelope(counts = McaBackupTable.REQUIRED.associateWith { 0 })

        val plan = McaBackupRows.plan(envelope, tables)

        assertTrue("expected Ready, was $plan", plan is McaBackupPayloadPlan.Ready)
        assertEquals(McaBackupTable.RESTORABLE.size, (plan as McaBackupPayloadPlan.Ready).tables.size)
    }

    @Test
    fun `a table declared twice in the payload is refused`() {
        val duplicated = McaBackupTable.REQUIRED.map { table ->
            McaBackupRows.build(table, listOf("id"), listOf(listOf("x")))
        } + McaBackupRows.build(McaBackupTable.CHAT_SESSIONS, listOf("id"), listOf(listOf("y")))
        val envelope = envelope(counts = McaBackupTable.REQUIRED.associateWith { 1 })

        val rejected = McaBackupRows.plan(envelope, duplicated) as McaBackupPayloadPlan.Rejected

        assertEquals(McaBackupRejection.TABLE_DUPLICATE, rejected.rejection)
    }

    @Test
    fun `a row that does not match the column count cannot be built`() {
        val failure = runCatching {
            McaBackupTableRows(
                table = McaBackupTable.CHAT_SESSIONS,
                columns = listOf("id", "title"),
                rows = listOf(listOf("only-one"))
            )
        }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `a structurally broken payload array decodes to null`() {
        assertNull(McaBackupRows.decode(null))
        assertNull(McaBackupRows.decode(JSONArray().put(JSONObject().put("id", "nope"))))
        assertNull(
            McaBackupRows.decode(
                JSONArray().put(
                    JSONObject()
                        .put("id", "chat_sessions")
                        .put("columns", JSONArray().put("id").put("id"))
                        .put("rows", JSONArray())
                )
            )
        )
    }

    @Test
    fun `a nested cell is refused instead of silently stringified`() {
        val raw = JSONArray().put(
            JSONObject()
                .put("id", "chat_sessions")
                .put("columns", JSONArray().put("id"))
                .put("rows", JSONArray().put(JSONObject().put("id", JSONObject().put("nested", 1))))
        )

        assertNull(McaBackupRows.decode(raw))
    }

    @Test
    fun `an oversized cell is refused by the plan`() {
        val tables = McaBackupTable.REQUIRED.map { table ->
            val cell = if (table == McaBackupTable.CHAT_SESSIONS) "x".repeat(McaBackupRows.MAX_CELL_CHARS + 1) else "x"
            McaBackupTableRows(table, listOf("id"), listOf(listOf(cell)))
        }
        val envelope = envelope(counts = McaBackupTable.REQUIRED.associateWith { 1 })

        val rejected = McaBackupRows.plan(envelope, tables) as McaBackupPayloadPlan.Rejected

        assertEquals(McaBackupRejection.CELL_TOO_LARGE, rejected.rejection)
    }

    @Test
    fun `a backup carrying rows round trips through the envelope codec`() {
        val tables = McaBackupTable.REQUIRED.map { table ->
            McaBackupRows.build(table, listOf("id", "body"), listOf(listOf("${table.wireId}-1", null)))
        }
        val envelope = envelope(counts = McaBackupTable.REQUIRED.associateWith { 1 }, payload = tables)

        val decoded = decodeMcaBackupEnvelope(envelope.toJson()) as McaBackupDecodeResult.Decoded

        assertEquals(McaBackupTable.REQUIRED.size, decoded.envelope.payload.size)
        assertNull(decoded.envelope.payload.first().rows.single()[1])
        assertTrue(McaBackupVerify.verify(decoded.envelope).isCompatible)
        assertTrue(McaBackupRows.plan(decoded.envelope, decoded.envelope.payload) is McaBackupPayloadPlan.Ready)
    }

    @Test
    fun `a corrupt payload section is reported instead of ignored`() {
        val raw = """
            {"kind":"mca-backup","version":1,"createdAt":1,"appVersion":"1",
             "tables":[{"id":"chat_sessions","rows":1}],"settings":{},
             "payload":[{"id":"chat_sessions","columns":["id"],"rows":"not-an-array"}]}
        """.trimIndent()

        val result = decodeMcaBackupEnvelope(raw)

        assertTrue(result is McaBackupDecodeResult.Malformed)
        assertEquals(
            McaBackupRejection.PAYLOAD_MISSING,
            (result as McaBackupDecodeResult.Malformed).rejection
        )
    }

    private fun table(table: McaBackupTable, rows: Int) = McaBackupRows.build(
        table = table,
        columns = listOf("id"),
        rows = List(rows) { listOf("${table.wireId}-$it") }
    )

    private fun envelope(
        counts: Map<McaBackupTable, Int> = McaBackupTable.REQUIRED.associateWith { 0 },
        payload: List<McaBackupTableRows> = emptyList()
    ): McaBackupEnvelope = McaBackupEnvelope(
        createdAtMillis = 1_700_000_000_000L,
        appVersionName = "1.0",
        tables = McaBackupTable.REQUIRED.map { McaBackupTableCount(it, counts[it] ?: 0) },
        settings = emptyMap(),
        payload = payload
    )
}

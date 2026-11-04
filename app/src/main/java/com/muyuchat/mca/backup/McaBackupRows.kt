package com.muyuchat.mca.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Row payload of a backup file.
 *
 * The manifest ([McaBackupEnvelope]) says *how many* rows each whitelisted family had; this is the
 * data itself: one entry per table with its column order and rows of typed cells. Cells keep their
 * type (string / number / boolean / null) so a round trip does not turn `null` into `"null"` or a
 * number into text.
 *
 * Credential-like columns are removed here, at the edge, and recorded in
 * [McaBackupTableRows.redactedColumns] so the manifest states what was dropped instead of silently
 * shipping a narrower table.
 */
internal data class McaBackupTableRows(
    val table: McaBackupTable,
    val columns: List<String>,
    val rows: List<List<Any?>>,
    /** Columns removed because they look like credentials; empty for a clean table. */
    val redactedColumns: List<String> = emptyList()
) {
    init {
        require(columns.isNotEmpty()) { "a backed-up table must declare its columns" }
        require(rows.all { it.size == columns.size }) { "every row must match the column count" }
    }

    val rowCount: Int get() = rows.size
}

/** What a restore may consume, after the payload has been checked against its own manifest. */
internal sealed interface McaBackupPayloadPlan {
    data class Ready(
        val tables: List<McaBackupTableRows>,
        val totalRows: Int,
        /** table wire id → columns that were stripped at export time. */
        val redacted: Map<String, List<String>>
    ) : McaBackupPayloadPlan

    data class Rejected(
        val rejection: McaBackupRejection,
        val reason: String
    ) : McaBackupPayloadPlan
}

/**
 * Codec and consistency rules for the row payload.
 *
 * This layer is deliberately SQL-free: it decides whether the file describes a complete, internally
 * consistent set of rows. Writing those rows into a database and swapping the live file is the
 * restore engine's job.
 */
internal object McaBackupRows {

    /** A table with more rows than this is refused rather than half-imported. */
    const val MAX_ROWS_PER_TABLE: Int = 200_000

    /** One cell larger than this is refused: a backup file must not carry an unbounded blob. */
    const val MAX_CELL_CHARS: Int = 1_000_000

    private val ALLOWED_CELL_TYPES = setOf(String::class.java, Int::class.java, Long::class.java,
        Double::class.java, Boolean::class.java)

    /**
     * Builds a table payload from raw column names/cells, dropping credential-like columns.
     *
     * The dropped names are returned in the payload (not just logged) so the UI and the manifest can
     * say "已剔除 1 个敏感列" instead of the user discovering the gap after a restore.
     */
    fun build(
        table: McaBackupTable,
        columns: List<String>,
        rows: List<List<Any?>>
    ): McaBackupTableRows {
        val keptIndices = columns.indices.filterNot { McaBackupSecrets.isSensitive(columns[it]) }
        val redacted = columns.indices
            .filter { McaBackupSecrets.isSensitive(columns[it]) }
            .map { columns[it] }
        val keptColumns = keptIndices.map { columns[it] }
        val keptRows = rows.map { row ->
            keptIndices.map { index -> normalizeCell(row.getOrNull(index)) }
        }
        return McaBackupTableRows(
            table = table,
            columns = keptColumns,
            rows = keptRows,
            redactedColumns = redacted
        )
    }

    fun encode(tables: List<McaBackupTableRows>): JSONArray = JSONArray().apply {
        tables.sortedBy { it.table.ordinal }.forEach { table ->
            val rowArray = JSONArray()
            table.rows.forEach { row ->
                val json = JSONObject()
                table.columns.forEachIndexed { index, column ->
                    // JSON null is meaningful here: "the column was NULL", not "absent".
                    json.put(column, row.getOrNull(index) ?: JSONObject.NULL)
                }
                rowArray.put(json)
            }
            put(
                JSONObject()
                    .put("id", table.table.wireId)
                    .put("columns", JSONArray(table.columns))
                    .put("rows", rowArray)
                    .put("redactedColumns", JSONArray(table.redactedColumns))
            )
        }
    }

    /** Decodes the payload array; null when it is structurally unusable. */
    fun decode(array: JSONArray?): List<McaBackupTableRows>? {
        if (array == null) return null
        if (array.length() > McaBackupTable.entries.size) return null
        val tables = ArrayList<McaBackupTableRows>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: return null
            val table = McaBackupTable.fromWireId(entry.optString("id", "")) ?: return null
            val columnArray = entry.optJSONArray("columns") ?: return null
            val columns = ArrayList<String>(columnArray.length())
            for (columnIndex in 0 until columnArray.length()) {
                val column = columnArray.optString(columnIndex, "")
                if (column.isBlank()) return null
                columns += column
            }
            if (columns.isEmpty() || columns.distinct().size != columns.size) return null
            val rowArray = entry.optJSONArray("rows") ?: return null
            if (rowArray.length() > MAX_ROWS_PER_TABLE) return null
            val rows = ArrayList<List<Any?>>(rowArray.length())
            for (rowIndex in 0 until rowArray.length()) {
                val rowJson = rowArray.optJSONObject(rowIndex) ?: return null
                val cells = ArrayList<Any?>(columns.size)
                columns.forEach { column ->
                    if (!rowJson.has(column)) return null
                    val cell = decodeCell(rowJson.opt(column))
                    if (cell === UNSUPPORTED_CELL) return null
                    cells += cell
                }
                rows += cells
            }
            val redactedArray = entry.optJSONArray("redactedColumns")
            val redacted = ArrayList<String>()
            if (redactedArray != null) {
                for (redactedIndex in 0 until redactedArray.length()) {
                    redactedArray.optString(redactedIndex, "").takeIf { it.isNotBlank() }?.let(redacted::add)
                }
            }
            tables += McaBackupTableRows(table, columns, rows, redacted)
        }
        return tables
    }

    /**
     * Checks that the payload can be handed to a restore engine.
     *
     * Rules: every whitelisted family present exactly once, row counts equal to the manifest (a file
     * whose manifest and payload disagree is corrupt, not "partially usable"), and no cell oversized.
     */
    fun plan(envelope: McaBackupEnvelope, payload: List<McaBackupTableRows>?): McaBackupPayloadPlan {
        if (payload == null || payload.isEmpty()) {
            // A settings/plugins package legitimately has no row data; say *why* it cannot be
            // restored instead of the generic "manifest only" message.
            return McaBackupPayloadPlan.Rejected(
                McaBackupRejection.PAYLOAD_MISSING,
                McaBackupScopePlan.restoreUnavailableReason(envelope.scope)
                    ?: "备份包只包含清单，没有可恢复的行数据"
            )
        }
        val duplicated = payload.groupingBy { it.table }.eachCount().filterValues { it > 1 }.keys
        if (duplicated.isNotEmpty()) {
            return McaBackupPayloadPlan.Rejected(
                McaBackupRejection.TABLE_DUPLICATE,
                "重复的数据表：${duplicated.joinToString(", ") { it.wireId }}"
            )
        }
        // Scope first: a package only carries the families its scope covers, and a restore only ever
        // writes those. The plugins/settings scopes have nothing to write into the database at all.
        val requiredTables = McaBackupScopePlan.tablesFor(envelope.scope)
        if (requiredTables.isEmpty()) {
            return McaBackupPayloadPlan.Rejected(
                McaBackupRejection.PAYLOAD_MISSING,
                McaBackupScopePlan.restoreUnavailableReason(envelope.scope)
                    ?: "备份没有可恢复的行数据"
            )
        }
        val outsideScope = payload.filter { it.table.restorable && it.table !in requiredTables }
        if (outsideScope.isNotEmpty()) {
            return McaBackupPayloadPlan.Rejected(
                McaBackupRejection.SCOPE_MISMATCH,
                "备份范围「${envelope.scope.label}」不含：" +
                    outsideScope.joinToString(", ") { it.table.label }
            )
        }
        val missing = requiredTables.filter { required -> payload.none { it.table == required } }
        if (missing.isNotEmpty()) {
            return McaBackupPayloadPlan.Rejected(
                McaBackupRejection.TABLE_MISSING,
                "缺少数据表：${missing.joinToString(", ") { it.label }}"
            )
        }
        payload.filter { it.table.restorable }.forEach { table ->
            val expected = envelope.rowCountOf(table.table)
            if (table.rowCount != expected) {
                return McaBackupPayloadPlan.Rejected(
                    McaBackupRejection.ROW_COUNT_MISMATCH,
                    "表 ${table.table.label} 行数不一致：清单 $expected 行，数据 ${table.rowCount} 行"
                )
            }
        }
        payload.forEach { table ->
            if (table.rowCount > MAX_ROWS_PER_TABLE) {
                return McaBackupPayloadPlan.Rejected(
                    McaBackupRejection.ROW_COUNT_INVALID,
                    "表 ${table.table.label} 超过 $MAX_ROWS_PER_TABLE 行上限"
                )
            }
            table.rows.forEach { row ->
                row.forEach { cell ->
                    val text = cell as? String
                    if (text != null && text.length > MAX_CELL_CHARS) {
                        return McaBackupPayloadPlan.Rejected(
                            McaBackupRejection.CELL_TOO_LARGE,
                            "表 ${table.table.label} 存在超过 $MAX_CELL_CHARS 字符的单元格"
                        )
                    }
                }
            }
        }
        return McaBackupPayloadPlan.Ready(
            tables = payload.sortedBy { it.table.ordinal },
            totalRows = payload.sumOf { it.rowCount },
            redacted = payload
                .filter { it.redactedColumns.isNotEmpty() }
                .associate { it.table.wireId to it.redactedColumns }
        )
    }

    private fun normalizeCell(value: Any?): Any? = when (value) {
        null -> null
        is String -> value.takeIf { it.length <= MAX_CELL_CHARS }
        is Byte, is Short, is Int -> (value as Number).toInt()
        is Long -> value
        is Float, is Double -> (value as Number).toDouble()
        is Boolean -> value
        else -> value.toString()
    }

    /** JSON null → null; nested JSON is refused instead of silently stringified. */
    private fun decodeCell(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is Int, is Long, is Double, is Boolean -> value
        else -> UNSUPPORTED_CELL
    }

    /** Sentinel: a cell shape the row format does not allow (nested object/array, unknown type). */
    private val UNSUPPORTED_CELL = Any()
}

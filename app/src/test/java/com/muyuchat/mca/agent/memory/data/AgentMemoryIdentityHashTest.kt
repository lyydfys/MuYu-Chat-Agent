package com.muyuchat.mca.agent.memory.data

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recomputes the exported Room schema identity hash from the schema's own content.
 *
 * Room validates an upgraded database against the identity hash baked into the generated
 * `RoomOpenDelegate`; the exported JSON must carry the same value. A hand-edited `29.json` (a column
 * added to the export but not to the entities, or the reverse) would otherwise only fail on a real
 * upgrade with "Room cannot verify the data integrity".
 *
 * The formula is the one used by Room 2.8.4's `SchemaIdentityKey`/`Entity.getIdKey` (MD5 over
 * `?:?`-separated per-entity keys). It is pinned against two independently generated exports:
 * `27.json` and `ModelRuntimeProfileDatabase/1.json`. The first KSP build after this change is still
 * the authority for `29.json`; this test fails if the checked-in file and its hash drift apart.
 */
class AgentMemoryIdentityHashTest {

    @Test
    fun `29 json identity hash matches its own entities`() {
        val schema = readSchema("29.json")
        val database = schema.getJSONObject("database")
        assertEquals(29, database.getInt("version"))
        assertEquals(
            "29.json identityHash must equal the hash of its exported entities",
            databaseIdentityHash(database.getJSONArray("entities")),
            database.getString("identityHash")
        )
    }

    @Test
    fun `the identity hash formula reproduces previously generated exports`() {
        assertEquals("8f79b14f8fd663f5c85301a53e254d5f", identityOf("27.json"))
        assertEquals(
            "f13cfd69926f658e2b8f3a5305e48ec8",
            databaseIdentityHash(
                readSchemaAt("app/schemas/com.muyuchat.mca.ModelRuntimeProfileDatabase/1.json")
                    .getJSONObject("database")
                    .getJSONArray("entities")
            )
        )
    }

    @Test
    fun `the export contains the four memory tables with the declared columns`() {
        val entities = readSchema("29.json").getJSONObject("database").getJSONArray("entities")
        val byTable = (0 until entities.length()).associate { index ->
            val entity = entities.getJSONObject(index)
            entity.getString("tableName") to entity
        }
        val expected = mapOf(
            "agent_memory_spaces" to listOf(
                "spaceId", "displayName", "createdAt", "updatedAt", "revision", "schemaVersion"
            ),
            "agent_memory_items" to listOf(
                "memoryId", "spaceId", "title", "text", "contentHash", "sourceKind", "sourceRef",
                "tokenCount", "chunkCount", "createdAt", "updatedAt", "revision", "revoked", "revokedAt", "schemaVersion"
            ),
            "agent_memory_chunks" to listOf(
                "chunkId", "memoryId", "spaceId", "position", "text", "contentHash",
                "estimatedTokens", "createdAt", "schemaVersion"
            ),
            "agent_memory_task_selections" to listOf(
                "taskId", "spaceIdsJson", "selectionHash", "createdAt", "revoked", "revokedAt", "schemaVersion"
            )
        )
        expected.forEach { (table, columns) ->
            val entity = requireNotNull(byTable[table]) { "29.json is missing $table" }
            val fields = entity.getJSONArray("fields")
            assertEquals(
                "$table columns",
                columns,
                (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
            )
        }
        assertTrue(byTable.containsKey("chat_sessions"))
        assertTrue(byTable.keys.containsAll(AgentMemorySchema.TABLE_NAMES))
    }

    private fun identityOf(file: String): String =
        readSchema(file).getJSONObject("database").getString("identityHash")

    private fun readSchema(file: String): JSONObject =
        readSchemaAt("app/schemas/com.muyuchat.mca.McaRoomDatabase/$file")

    private fun readSchemaAt(path: String): JSONObject =
        JSONObject(File(projectRoot(), path).readText())

    private fun databaseIdentityHash(entities: JSONArray): String {
        val keys = (0 until entities.length()).map { index -> entityIdentityKey(entities.getJSONObject(index)) }
        return md5(keys.sortedWith(ENGLISH_SORT).joinToString(separator = "") { key -> key + SEPARATOR })
    }

    private fun entityIdentityKey(entity: JSONObject): String {
        val parts = ArrayList<String>()
        parts += entity.getString("tableName")
        parts += primaryKeyIdentityKey(entity.getJSONObject("primaryKey"))
        parts += sortedKeys(entity.optJSONArray("fields"), ::propertyIdentityKey)
        parts += sortedKeys(entity.optJSONArray("indices"), ::indexIdentityKey)
        parts += sortedKeys(entity.optJSONArray("foreignKeys"), ::foreignKeyIdentityKey)
        return md5(parts.joinToString(separator = "") { part -> part + SEPARATOR })
    }

    private fun sortedKeys(array: JSONArray?, key: (JSONObject) -> String): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length())
            .map { index -> key(array.getJSONObject(index)) }
            .sortedWith(ENGLISH_SORT)
    }

    private fun propertyIdentityKey(field: JSONObject): String {
        val affinity = if (field.has("affinity")) field.getString("affinity") else "TEXT"
        val notNull = if (field.optBoolean("notNull", false)) "true" else "false"
        val base = "${field.getString("columnName")}-$affinity-$notNull"
        return if (field.has("defaultValue") && !field.isNull("defaultValue")) {
            base + "-defaultValue=" + field.getString("defaultValue")
        } else {
            base
        }
    }

    private fun primaryKeyIdentityKey(primaryKey: JSONObject): String {
        val columns = columnNames(primaryKey.getJSONArray("columnNames")).joinToString(", ")
        val autoGenerate = if (primaryKey.optBoolean("autoGenerate", false)) "true" else "false"
        return "$autoGenerate-[$columns]"
    }

    private fun indexIdentityKey(index: JSONObject): String {
        val unique = if (index.optBoolean("unique", false)) "true" else "false"
        val columns = columnNames(index.getJSONArray("columnNames")).joinToString(",")
        val base = "$unique-${index.getString("name")}-$columns"
        val orders = index.optJSONArray("orders")
        return if (orders == null || orders.length() == 0) {
            base
        } else {
            base + "-" + (0 until orders.length()).joinToString(",") { orders.optString(it) }
        }
    }

    private fun foreignKeyIdentityKey(foreignKey: JSONObject): String {
        val parentTable = foreignKey.getString("table")
        val parentColumns = columnNames(foreignKey.getJSONArray("referencedColumns")).joinToString(",")
        val childColumns = columnNames(foreignKey.getJSONArray("columns")).joinToString(",")
        val onDelete = foreignKey.getString("onDelete")
        val onUpdate = foreignKey.getString("onUpdate")
        val deferred = if (foreignKey.optBoolean("deferred", false)) "true" else "false"
        return "$parentTable-$parentColumns-$childColumns-$onDelete-$onUpdate-$deferred"
    }

    private fun columnNames(array: JSONArray): List<String> =
        (0 until array.length()).map { index -> array.getString(index) }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("unable to locate the MCA project root")
    }

    private companion object {
        const val SEPARATOR = "?:?"
        val ENGLISH_SORT: Comparator<String> = Comparator { left, right ->
            left.lowercase(Locale.ENGLISH).compareTo(right.lowercase(Locale.ENGLISH))
        }
    }
}

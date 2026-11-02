package com.muyuchat.mca.schema

import com.muyuchat.mca.schemacheck.SchemaJsonSupport
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins every KSP-exported Room schema 27..32 to Room 2.8.4's identity-hash formula.
 *
 * `AgentMemoryIdentityHashTest` already pins `29.json` (and proves the formula against two
 * independently generated exports). The 30/31/32 exports carry the video chain and were previously
 * unpinned: a hand-edited table/column/index would only surface on a device migration as
 * "Room cannot verify the data integrity". This test extends the same formula to all six exports and
 * fixes the table counts recorded by the 29→32 audit.
 */
class McaSchemaIdentityHashTest {

    @Test
    fun `every exported schema identity hash matches its own content`() {
        val expectedTableCounts = mapOf(
            27 to 11,
            28 to 26,
            29 to 30,
            30 to 33,
            31 to 34,
            32 to 34,
            // S4 added agent_park_tokens; additive only, every earlier table is unchanged.
            33 to 35,
            // N03 follow-up added agent_dispatch_outbox; additive only.
            34 to 36,
            // N19 follow-up moved workflow frames into agent_workflow_checkpoints; additive only.
            35 to 37
        )
        expectedTableCounts.forEach { (version, tables) ->
            val database = SchemaJsonSupport.loadDatabase(version)
            assertEquals("$version.json version field", version, database.getInt("version"))
            assertEquals("$version.json table count", tables, database.getJSONArray("entities").length())
            assertEquals(
                "$version.json identityHash must equal the hash of its exported entities",
                databaseIdentityHash(database.getJSONArray("entities")),
                database.getString("identityHash")
            )
        }
    }

    @Test
    fun `highest exported schema matches the declared database version`() {
        val files = SchemaJsonSupport.schemaDirectory().listFiles { file -> file.extension == "json" }
            ?: error("no exported schemas under ${SchemaJsonSupport.schemaDirectory()}")
        assertTrue("27..32 must all stay exported for historical fixture creation", files.size >= 6)
        val exportedVersions = files.map { it.nameWithoutExtension.toInt() }.toSet()
        val highest = exportedVersions.max()

        val source = java.io.File(
            SchemaJsonSupport.projectRoot(),
            "app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt"
        ).readText(Charsets.UTF_8)
        val declaredVersion = Regex("@Database\\([\\s\\S]*?version\\s*=\\s*(\\d+)")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("no @Database version found in ChatSessionStoreRoomDatabase.kt")

        assertEquals(
            "the highest exported schema must equal the @Database source version",
            declaredVersion,
            highest
        )
        assertEquals(
            "every historical version 27..$highest must stay exported",
            (27..highest).toSet(),
            exportedVersions
        )
    }

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

    private companion object {
        const val SEPARATOR = "?:?"
        val ENGLISH_SORT: Comparator<String> = Comparator { left, right ->
            left.lowercase(Locale.ENGLISH).compareTo(right.lowercase(Locale.ENGLISH))
        }
    }
}

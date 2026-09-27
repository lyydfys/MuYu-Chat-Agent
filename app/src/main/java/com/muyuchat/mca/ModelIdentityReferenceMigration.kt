package com.muyuchat.mca

import android.content.Context
import androidx.room.withTransaction
import com.muyuchat.core.modelstore.ModelIdentityReconciliation
import com.muyuchat.core.modelstore.ModelStoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Runs after ModelStoreRepository has durably journaled its id mapping. Each
 * step is idempotent, so a process death can replay the journal on startup.
 */
internal class ModelIdentityReferenceMigration(context: Context) {
    private val appContext = context.applicationContext
    private val modelStore = ModelStoreRepository(appContext)

    suspend fun reconcileAndMigrate(): ModelIdentityReconciliation = withContext(Dispatchers.IO) {
        val result = modelStore.reconcileStableIdentities()
        migratePendingReferences()
        result
    }

    suspend fun migratePendingReferences(): Boolean = withContext(Dispatchers.IO) {
        val mapping = modelStore.pendingIdentityReferenceMigration()
            .filter { (old, new) -> old.isNotBlank() && new.isNotBlank() && old != new }
        if (mapping.isEmpty()) {
            modelStore.acknowledgeIdentityReferenceMigration()
            return@withContext false
        }
        val database = McaRoomDatabase.get(appContext)
        database.withTransaction {
            val dao = database.chatSessionDao()
            mapping.forEach { (old, new) ->
                dao.migrateSessionModelId(old, new)
                dao.migrateAssistantDefaultModelId(old, new)
            }
        }
        ModelGenerationProfileStore(appContext).migrateLocalModelIds(mapping)
        migrateLegacyAssistantMirror(mapping)
        modelStore.acknowledgeIdentityReferenceMigration()
        true
    }

    private fun migrateLegacyAssistantMirror(mapping: Map<String, String>) {
        val preferences = appContext.getSharedPreferences("mca_assistants", Context.MODE_PRIVATE)
        val raw = preferences.getString("assistants_json", null) ?: return
        val records = JSONArray(raw)
        var changed = false
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val old = record.optString("defaultModelId")
            val new = mapping[old] ?: continue
            record.put("defaultModelId", new)
            changed = true
        }
        if (changed) check(preferences.edit().putString("assistants_json", records.toString()).commit()) {
            "Legacy assistant model binding migration was not persisted."
        }
    }
}

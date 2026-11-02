package com.muyuchat.mca.agent.cloud

import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.mca.ChatBackend
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudApiStore
import com.muyuchat.mca.CloudModelKind
import com.muyuchat.mca.CloudModelRecord
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Device-only helper: seeds an OpenAI-Responses cloud engine from an instrumentation argument.
 *
 * The key is never checked in; run with
 * `am instrument ... -e mimoKey <key>`. Skips when the argument is absent.
 */
class SeedCloudModelDeviceTest {

    @Test
    fun seedMimoResponsesEngine() {
        val args = InstrumentationRegistry.getArguments()
        val key = args.getString("mimoKey").orEmpty()
        assumeTrue("mimoKey instrumentation argument is required", key.isNotBlank())
        val baseUrl = args.getString("mimoBaseUrl") ?: "https://api.xiaomimimo.com/v1"
        val model = args.getString("mimoModel") ?: "mimo-v2.6-flash"
        val displayName = args.getString("mimoDisplay") ?: "MiMo"

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CloudApiStore(context)
        val config = CloudApiConfig(
            enabled = true,
            apiFormat = CloudApiFormat.OPENAI_RESPONSES,
            providerName = "MiMo",
            displayName = displayName,
            baseUrl = baseUrl,
            apiKey = key,
            chatModel = model,
            supportsTools = true
        )
        val saved = store.save(config)
        assertTrue("credential save failed: $saved", saved is com.muyuchat.mca.CredentialSaveResult.Stored)
        val record = CloudModelRecord(
            kind = CloudModelKind.CHAT,
            apiFormat = CloudApiFormat.OPENAI_RESPONSES,
            providerName = "MiMo",
            displayName = displayName,
            baseUrl = baseUrl,
            apiKey = key,
            modelName = model,
            supportsTools = true
        )
        store.upsertModel(record)
        store.saveSelectedCloudChatModelId(record.id)
        store.saveSelectedBackend(ChatBackend.CLOUD)
        // SharedPreferences.apply() is asynchronous; keep the process alive long enough for the
        // file write to reach disk before the instrumentation process exits.
        Thread.sleep(2_000)
    }
}

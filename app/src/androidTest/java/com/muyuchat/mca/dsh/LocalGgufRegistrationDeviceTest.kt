package com.muyuchat.mca.dsh

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.modelstore.ModelStoreRepository
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Registers the GGUFs that were pushed into the app-owned external files directory.
 *
 * This is setup, not a DSH feature test: it calls the production download registration entry
 * (`registerDownloadedModel`) so the picker sees the same manifest the real download path writes.
 * The files stay in place; nothing is copied or fabricated.
 */
@RunWith(AndroidJUnit4::class)
class LocalGgufRegistrationDeviceTest {

    @Test
    fun registerPreparedGgufsInPlace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir("models")) {
            "the app has no external models directory"
        }
        val store = ModelStoreRepository(context)
        val prepared = listOf(
            "qwen-test.gguf",
            "qwen4b.gguf",
            "qwen15b.gguf",
            "qwen3-4b-instruct.gguf"
        )
            .map { File(directory, it) }
            .filter { it.isFile }
        assertTrue("no prepared GGUF under ${directory.absolutePath}", prepared.isNotEmpty())

        val registered = prepared.map { file ->
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val existing = store.listModels().firstOrNull { model ->
                runCatching { File(model.path).canonicalPath }.getOrDefault(model.path) == canonical
            }
            val manifest = if (existing != null && existing.sizeBytes == file.length()) {
                existing
            } else {
                // A push that was still settling when a previous run registered produced a stale
                // size; replace that record through the production delete/register entry points.
                existing?.let { store.deleteModel(it.id) }
                store.registerDownloadedModel(
                    file = file,
                    repoId = "local/${file.nameWithoutExtension}",
                    revision = "seed-20261026"
                )
            }
            Log.i(
                "McaLocalSeed",
                "registered name=${file.name} id=${manifest.id} runtime=${manifest.runtime} " +
                    "quant=${manifest.quant} arch=${manifest.architecture} " +
                    "sha=${manifest.sha256.take(12)} path=${manifest.path}"
            )
            manifest
        }

        val ids = store.listModels().map { it.id }.toSet()
        assertTrue("every registered manifest must be listed", registered.all { it.id in ids })
    }
}

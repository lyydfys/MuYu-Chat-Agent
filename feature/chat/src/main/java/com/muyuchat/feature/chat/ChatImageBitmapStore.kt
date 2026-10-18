package com.muyuchat.feature.chat

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-scoped owner for decoded chat images. The cache stores only evictable bitmap references;
 * visible Compose state remains the active owner and no eviction path recycles a bitmap.
 */
internal object ChatImageBitmapStore {
    private const val MAX_CONCURRENT_DECODES = 2

    @Volatile
    private var instance: Store? = null

    suspend fun load(
        context: Context,
        uriString: String,
        maxDimensionPx: Int?,
        revisionKey: Any?
    ): Bitmap? {
        val appContext = context.applicationContext ?: context
        val store = instance ?: synchronized(this) {
            instance ?: Store(appContext).also { instance = it }
        }
        return store.load(appContext, uriString, maxDimensionPx, revisionKey)
    }

    @Suppress("DEPRECATION")
    private class Store(context: Context) : ComponentCallbacks2 {
        private val appContext = context.applicationContext ?: context
        private val cache = ByteSizedLruCache<ChatImageDecodeKey, Bitmap>(
            maxBytes = chatImageDecodedCacheBudgetBytes(
                Runtime.getRuntime().maxMemory()
            ).toLong(),
            sizeOf = { bitmap -> bitmap.allocationByteCount.toLong() }
        )
        private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val coordinator = ChatImageDecodeCoordinator(
            cache = cache,
            maxConcurrentDecodes = MAX_CONCURRENT_DECODES,
            scope = decodeScope,
            decode = { key ->
                val bitmap = loadImageBitmap(
                    appContext,
                    key.resourceId,
                    key.maxDimensionPx
                )
                if (bitmap != null &&
                    readSourceRevision(appContext, Uri.parse(key.resourceId)) == key.sourceRevision
                ) {
                    bitmap
                } else {
                    // A source overwritten during decode is stale. Do not cache or publish it.
                    null
                }
            }
        )

        init {
            appContext.registerComponentCallbacks(this)
        }

        suspend fun load(
            context: Context,
            uriString: String,
            maxDimensionPx: Int?,
            revisionKey: Any?
        ): Bitmap? {
            val key = withContext(Dispatchers.IO) {
                ChatImageDecodeKey.create(
                    resourceId = uriString,
                    revisionKey = revisionKey,
                    sourceRevision = readSourceRevision(context, Uri.parse(uriString)),
                    maxDimensionPx = maxDimensionPx
                )
            }
            return coordinator.load(key)
        }

        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                coordinator.clearCache()
            }
        }

        override fun onLowMemory() {
            coordinator.clearCache()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }
}

private fun readSourceRevision(context: Context, uri: Uri): String? = when {
    uri.scheme.equals("file", ignoreCase = true) -> {
        val path = uri.path
        if (path.isNullOrBlank()) {
            null
        } else {
            runCatching {
                val file = File(path)
                if (file.exists()) {
                    "file:${file.length()}:${file.lastModified()}"
                } else {
                    "file:missing"
                }
            }.getOrNull()
        }
    }
    uri.scheme.equals("content", ignoreCase = true) -> {
        val fullProjection = arrayOf(
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        queryContentRevision(context, uri, fullProjection)
            ?: queryContentRevision(context, uri, arrayOf(OpenableColumns.SIZE))
    }
    else -> null
}

private fun queryContentRevision(
    context: Context,
    uri: Uri,
    projection: Array<String>
): String? = runCatching {
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            projection.mapNotNull { column ->
                val index = cursor.getColumnIndex(column)
                if (index < 0) return@mapNotNull null
                val value = if (cursor.isNull(index)) "null" else cursor.getString(index)
                "$column=$value"
            }.joinToString("|").ifBlank { null }
        }
    }
}.getOrNull()
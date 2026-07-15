package com.muyuchat.mca

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Opens every document type without EXTRA_MIME_TYPES. Several OEM file
 * managers incorrectly hide unknown extensions such as .gguf when AndroidX's
 * multi-MIME contract supplies a mixed filter list, even when that list also
 * contains the wildcard MIME type.
 */
internal class OpenModelDocumentsContract : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        return collectSelectedUris(intent.data, intent.clipData)
    }
}

internal fun collectSelectedUris(data: Uri?, clipData: ClipData?): List<Uri> = buildList {
    data?.let(::add)
    if (clipData != null) {
        for (index in 0 until clipData.itemCount) {
            clipData.getItemAt(index).uri?.let(::add)
        }
    }
}.distinct()

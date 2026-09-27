package com.muyuchat.mca

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Opens one arbitrary document without EXTRA_MIME_TYPES. Some OEM document
 * providers hide files when a mixed MIME list is supplied, even with a wildcard MIME type.
 */
internal class OpenAnyDocumentContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            type = "*/*"
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
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

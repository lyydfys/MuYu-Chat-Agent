package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class ImageExecutionProfileJsonException(
    val code: String,
    val field: String,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException("$code:$field:$message", cause)

/**
 * Strict JSON boundary for versioned image execution profiles and standard
 * package sidecars. Missing sidecars are advisory; malformed present files are
 * concrete package errors and are never converted into a device restriction.
 */
internal object ImageExecutionProfileJson
private const val MANIFEST_PROFILE_FIELD = "executionProfile"

internal fun ImageExecutionProfileJson.parseManifest(manifest: JSONObject): ImageExecutionProfile? {
    if (!manifest.has(MANIFEST_PROFILE_FIELD) || manifest.isNull(MANIFEST_PROFILE_FIELD)) return null
    val profileJson = manifest.strictObject(MANIFEST_PROFILE_FIELD)
    return parseProfile(profileJson)
}

internal fun ImageExecutionProfileJson.parseManifestFile(manifestFile: File): ImageExecutionProfile? {
    if (!manifestFile.isFile) return null
    return parseManifest(readJsonFile(manifestFile, "manifest"))
}


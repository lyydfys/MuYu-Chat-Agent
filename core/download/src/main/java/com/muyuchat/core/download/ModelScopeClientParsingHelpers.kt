package com.muyuchat.core.download

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON/URL parsing helpers for the ModelScope catalog client (P6/M1). These were private
 * member extensions of ModelScopeClient; they moved here as internal top-level helpers so the
 * catalog client keeps resolving them unqualified (same package) while one file owns the parsing
 * logic. Bodies are verbatim; only the declaration visibility of the stateless helpers widened.
 * QairtReleaseAsset rides along because only these QNN asset selectors construct it.
 */

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = opt(index)
            when (value) {
                is String -> value.takeIf { it.isNotBlank() }?.let(::add)
                is JSONObject -> value.firstString("name", "label", "value")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}

internal fun JSONObject.firstArray(vararg keys: String): JSONArray? =
    keys.firstNotNullOfOrNull { key -> optJSONArray(key) }

internal fun JSONObject.firstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() } }

internal fun JSONObject.firstLong(vararg keys: String): Long =
    keys.firstNotNullOfOrNull { key -> optLong(key).takeIf { it > 0L } } ?: 0L

internal fun String.urlEncode(): String =
    URLEncoder.encode(this, "UTF-8").replace("+", "%20")

internal fun String.urlEncodePath(): String =
    split('/').joinToString("/") { it.urlEncode() }

internal data class QairtReleaseAsset(
    val chipset: String,
    val downloadUrl: String
)

internal fun JSONObject.selectQnnContextAsset(
    preferredChipsets: List<String>,
    catalogTargetChipset: String? = null
): QairtReleaseAsset {
    val chipsetAssets = optJSONObject("precisions")
        ?.optJSONObject("w8a16")
        ?.optJSONObject("chipset_assets")
        ?: error("release_assets.json 缺少 w8a16 chipset_assets。")

    fun assetFor(chipset: String): QairtReleaseAsset? = chipsetAssets.optJSONObject(chipset)
        ?.optJSONObject("qnn_context_binary")
        ?.optString("download_url")
        ?.takeIf { it.startsWith("http") }
        ?.let { QairtReleaseAsset(chipset, it) }

    catalogTargetChipset
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { target ->
            // A catalog entry names one executable context target. Device
            // discovery may rank that entry, but downloading it must not
            // silently substitute a context archive for another chipset.
            val exactTargetFirst = if (target.endsWith("-for-galaxy")) {
                listOf(target)
            } else {
                // Legacy callers that name a generic target may still use
                // the publisher's vendor variant when no generic asset exists.
                listOf(target, "$target-for-galaxy")
            }
            return exactTargetFirst
                .firstNotNullOfOrNull(::assetFor)
                ?: error("release_assets.json 缺少目录声明的 QNN context 目标：$target。")
        }

    val requested = preferredChipsets
        .map { it.trim() }
        .filter { it.isNotBlank() }
        // A chipset match does not imply a vendor-specific device build.
        // Prefer the generic asset and use the Galaxy variant only when
        // the publisher exposes no generic package for that chipset.
        .flatMap { chipset -> listOf(chipset, "$chipset-for-galaxy") }
        .distinct()
    for (chipset in requested) {
        assetFor(chipset)?.let { return it }
    }
    val available = buildList {
        val keys = chipsetAssets.keys()
        while (keys.hasNext()) add(keys.next())
    }
    return available
        .sortedWith(qairtFallbackChipsetComparator())
        .firstNotNullOfOrNull(::assetFor)
        ?: error("release_assets.json 没有可下载的 qnn_context_binary w8a16 芯片包。")
}

internal fun JSONObject.selectGenieXQairtAsset(preferredChipsets: List<String>): QairtReleaseAsset {
    val chipsetAssets = optJSONObject("precisions")
        ?.optJSONObject("w4a16")
        ?.optJSONObject("chipset_assets")
        ?: error("release_assets.json 缺少 w4a16 chipset_assets。")
    val available = buildList {
        val keys = chipsetAssets.keys()
        while (keys.hasNext()) add(keys.next())
    }
    val chipsetOrder = preferredChipsets
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    for (chipset in chipsetOrder) {
        val asset = chipsetAssets.optJSONObject(chipset)
            ?.optJSONObject("geniex_qairt")
            ?: continue
        val downloadUrl = asset.optString("download_url").takeIf { it.startsWith("http") }
            ?: continue
        return QairtReleaseAsset(chipset, downloadUrl)
    }
    return available
        .sortedWith(qairtFallbackChipsetComparator())
        .firstNotNullOfOrNull { chipset ->
            chipsetAssets.optJSONObject(chipset)
                ?.optJSONObject("geniex_qairt")
                ?.optString("download_url")
                ?.takeIf { it.startsWith("http") }
                ?.let { QairtReleaseAsset(chipset, it) }
        }
        ?: error("release_assets.json 没有可下载的 geniex_qairt w4a16 芯片包。")
}

private fun qairtFallbackChipsetComparator(): Comparator<String> =
    compareBy<String> { chipset ->
        // Generic packages are preferable to vendor-bin variants when no
        // exact device key exists.
        if (chipset.endsWith("-for-galaxy")) 1 else 0
    }.thenBy { chipset ->
        // Prefer the oldest published 8-series target as the broadest
        // forward-compatible baseline; exact requested matches already
        // won above this fallback.
        when {
            "elite-gen5" in chipset -> 2
            "elite" in chipset -> 1
            else -> 0
        }
    }.thenBy { it }

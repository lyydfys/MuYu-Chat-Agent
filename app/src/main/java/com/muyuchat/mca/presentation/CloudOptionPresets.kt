package com.muyuchat.mca.presentation

import com.muyuchat.feature.modelhub.CloudProviderPresetUi
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudImageApiFormat

/**
 * The option lists the cloud model screens offer.
 *
 * Moved here verbatim from MainActivity during P2. No model name, size, label or order was
 * edited; these are user-visible strings and lists, and changing one would be a product change
 * rather than a refactor.
 *
 * Note what these are not. They are suggestions, not admissions: cloudApiFormats and
 * cloudImageApiFormats enumerate what the UI offers, and imageModelPresetsFor and
 * imageSizePresetsFor fill a picker. Nothing here decides whether a request is allowed.
 *
 * imageModelPresetsFor returns an empty list for CUSTOM_PATH on purpose -- a custom endpoint has
 * no model to suggest, so the field starts empty rather than pre-filled with someone else's
 * model name.
 */
internal fun imageModelPresetsFor(format: CloudImageApiFormat): List<String> =
    when (format) {
        CloudImageApiFormat.OPENAI_IMAGES -> listOf("gpt-image-1.5", "gpt-image-1", "dall-e-3")
        CloudImageApiFormat.DASHSCOPE_IMAGE -> listOf("qwen-image-2.0-pro", "qwen-image-2.0", "qwen-image-plus", "qwen-image")
        CloudImageApiFormat.CUSTOM_PATH -> emptyList()
    }

internal fun cloudApiFormats(): List<CloudApiFormat> =
    CloudApiFormat.entries.toList()

internal fun cloudImageApiFormats(): List<CloudImageApiFormat> =
    listOf(CloudImageApiFormat.OPENAI_IMAGES, CloudImageApiFormat.DASHSCOPE_IMAGE, CloudImageApiFormat.CUSTOM_PATH)

/**
 * The three provider presets the settings screen shows.
 *
 * Not to be confused with MainViewModel's `cloudProviderPresets`, which has the same name and is
 * a different thing: a list of CloudProviderPreset carrying a CloudApiFormat and empty endpoint
 * fields, used as the runtime model. This one is the UI preset list. They were checked against
 * each other and are not duplicates, and neither was changed.
 */
internal fun cloudProviderPresets(): List<CloudProviderPresetUi> = listOf(
    CloudProviderPresetUi("openai", "OpenAI 协议", "自定义 OpenAI-compatible 接口"),
    CloudProviderPresetUi("responses", "OpenAI Responses", "自定义 Responses 接口"),
    CloudProviderPresetUi("anthropic", "Anthropic 协议", "自定义 Anthropic Messages 接口")
)

internal fun imageSizePresetsFor(format: CloudImageApiFormat): List<String> =
    when (format) {
        CloudImageApiFormat.OPENAI_IMAGES -> listOf("1024x1024", "1024x1536", "1536x1024", "1024x1792", "1792x1024")
        CloudImageApiFormat.DASHSCOPE_IMAGE -> listOf("1024x1024", "1024x1536", "1536x1024", "1328x1328", "1664x928", "928x1664")
        CloudImageApiFormat.CUSTOM_PATH -> listOf("1024x1024", "1024x1536", "1536x1024", "16:9", "9:16", "1:1")
    }

package com.muyuchat.core.modelstore

import java.io.File

internal val NESTED_COMPONENT_KEYS = setOf(
        "llm_config",
        "llm_model",
        "llm_weight",
        "lm_model",
        "embedding_file",
        "embedding_model",
        "tokenizer_file",
        "visual_model",
        "visual_weight",
        "audio_model",
        "audio_weight",
        "context_file",
        "ple_model",
        "ple_weight",
        "projector_model",
        "projector_weight"
    )

internal val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:($|/)")

internal const val DEFAULT_VISUAL_MODEL_PATH = "visual.mnn"
internal const val DEFAULT_GRAPH_METADATA_PATH = "llm.mnn.json"
internal const val LEGACY_VISUAL_GRAPH_VERSION = "3.5.0"
    // Keep this in lock-step with the pinned vendor runtime in vendor/mnn.
internal const val PRODUCT_RUNTIME_VERSION = "3.6"

internal fun File.isUsableBundleComponent(): Boolean =
    isFile && length() > 0L && canRead()

internal fun MnnBundleLoadScope.displayName(): String =
    name.lowercase().replace('_', ' ')

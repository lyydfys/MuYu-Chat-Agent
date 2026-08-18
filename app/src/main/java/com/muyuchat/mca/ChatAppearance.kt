package com.muyuchat.mca

import android.content.Context
import org.json.JSONObject

/** Persisted chat background appearance. Paths must point to an app-private copy. */
enum class ChatBackgroundScaleMode {
    CROP,
    FIT,
    CENTER;

    companion object {
        fun fromWire(value: String?): ChatBackgroundScaleMode =
            values().firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: CROP
    }
}

/** Global appearance fallback used when neither a session nor its role overrides it. */
class GlobalChatAppearanceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "mca_chat_appearance",
        Context.MODE_PRIVATE
    )

    fun load(): ChatAppearance =
        ChatAppearance.fromJsonOrNull(prefs.getString(KEY_APPEARANCE_JSON, null)) ?: ChatAppearance()

    fun save(appearance: ChatAppearance) {
        prefs.edit().putString(KEY_APPEARANCE_JSON, appearance.toJsonString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_APPEARANCE_JSON).apply()
    }

    private companion object {
        const val KEY_APPEARANCE_JSON = "appearance_json"
    }
}

/** Resolves the documented session > role > global appearance precedence. */
fun resolveChatAppearance(
    sessionOverride: ChatAppearance?,
    assistantAppearance: ChatAppearance?,
    globalAppearance: ChatAppearance
): ChatAppearance = sessionOverride
    ?: assistantAppearance?.takeUnless { it.isDefault }
    ?: globalAppearance

data class ChatAppearance(
    val backgroundImagePath: String? = null,
    val backgroundAlpha: Float = DEFAULT_ALPHA,
    val backgroundBlur: Float = DEFAULT_BLUR,
    val backgroundScaleMode: ChatBackgroundScaleMode = ChatBackgroundScaleMode.CROP
) {
    init {
        require(backgroundImagePath == null || backgroundImagePath.length <= MAX_PATH_CHARS) {
            "Background image path is too long."
        }
        require(backgroundAlpha in 0f..1f) { "Background alpha must be between 0 and 1." }
        require(backgroundBlur in 0f..MAX_BLUR) { "Background blur must be between 0 and $MAX_BLUR." }
    }

    val isDefault: Boolean
        get() = backgroundImagePath.isNullOrBlank() &&
            backgroundAlpha == DEFAULT_ALPHA &&
            backgroundBlur == DEFAULT_BLUR &&
            backgroundScaleMode == ChatBackgroundScaleMode.CROP

    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put("backgroundImagePath", backgroundImagePath)
        .put("backgroundAlpha", backgroundAlpha.toDouble())
        .put("backgroundBlur", backgroundBlur.toDouble())
        .put("backgroundScaleMode", backgroundScaleMode.name)

    fun toJsonString(): String = toJson().toString()

    companion object {
        const val SCHEMA = "mca.chat.appearance"
        const val VERSION = 1
        const val DEFAULT_ALPHA = 0.22f
        const val DEFAULT_BLUR = 0f
        const val MAX_BLUR = 32f
        private const val MAX_PATH_CHARS = 4096

        fun fromJsonOrNull(raw: String?): ChatAppearance? {
            if (raw.isNullOrBlank()) return null
            return fromJsonOrNull(runCatching { JSONObject(raw) }.getOrNull())
        }

        fun fromJsonOrNull(json: JSONObject?): ChatAppearance? {
            if (json == null) return null
            return runCatching {
                val schema = json.optString("schema").trim()
                require(schema.isBlank() || schema == SCHEMA) { "Unsupported appearance schema." }
                val version = if (json.has("version")) json.optInt("version", -1) else VERSION
                require(version == VERSION) { "Unsupported appearance version." }
                ChatAppearance(
                    backgroundImagePath = json.optString("backgroundImagePath")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?.take(MAX_PATH_CHARS),
                    backgroundAlpha = json.optDouble("backgroundAlpha", DEFAULT_ALPHA.toDouble())
                        .toFloat().coerceIn(0f, 1f),
                    backgroundBlur = json.optDouble("backgroundBlur", DEFAULT_BLUR.toDouble())
                        .toFloat().coerceIn(0f, MAX_BLUR),
                    backgroundScaleMode = ChatBackgroundScaleMode.fromWire(
                        json.optString("backgroundScaleMode", ChatBackgroundScaleMode.CROP.name)
                    )
                )
            }.getOrNull()
        }
    }
}

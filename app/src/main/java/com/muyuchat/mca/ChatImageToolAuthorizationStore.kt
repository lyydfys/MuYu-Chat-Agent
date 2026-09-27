package com.muyuchat.mca

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest

/** Whether a chat session requires approval before a character's image-tool request runs. */
enum class ChatImageToolAuthorizationMode {
    ASK_EVERY_TIME,
    AUTO_APPROVE;

    companion object {
        internal fun fromStoredValue(value: String?): ChatImageToolAuthorizationMode =
            entries.firstOrNull { it.name == value } ?: ASK_EVERY_TIME
    }
}

/**
 * Stores only the per-session image-tool authorization choice. It deliberately does not own
 * chat messages, image attachments, generated images, or tool-call history.
 *
 * [prune] should be called with the IDs of sessions that still exist when the session catalog is
 * refreshed. The store omits the default value, so a missing entry always means ask each time.
 */
class ChatImageToolAuthorizationStore internal constructor(
    private val preferences: ChatImageToolAuthorizationPreferences
) {
    constructor(context: Context) : this(
        SharedPreferencesChatImageToolAuthorizationPreferences(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        )
    )

    /**
     * Auto-approval is bound to the exact session, chat configuration, and image configuration.
     * Identity values are hashed before persistence; callers should still avoid including secrets.
     * Missing or changed identities revoke the stored grant and use the safe default.
     */
    fun getMode(
        chatSessionId: String?,
        currentChatModelIdentity: String?,
        currentImageModelIdentity: String?
    ): ChatImageToolAuthorizationMode {
        val normalizedId = normalizeSessionId(chatSessionId)
            ?: return ChatImageToolAuthorizationMode.ASK_EVERY_TIME
        val chatIdentity = hashIdentity(currentChatModelIdentity, "chat")
        val imageIdentity = hashIdentity(currentImageModelIdentity, "image")
        return synchronized(lock) {
            val values = readMap()
            val stored = values[normalizedId]
            if (chatIdentity == null || imageIdentity == null || stored == null ||
                stored.chatIdentitySha256 != chatIdentity ||
                stored.imageIdentitySha256 != imageIdentity
            ) {
                if (stored != null) values.remove(normalizedId)
                val storedEntries = countStoredEntries()
                if (values.size != storedEntries || countValidStoredEntries() != storedEntries) {
                    writeMap(values)
                }
                ChatImageToolAuthorizationMode.ASK_EVERY_TIME
            } else {
                ChatImageToolAuthorizationMode.fromStoredValue(stored.mode)
            }
        }
    }

    /**
     * Persist a session's choice. Returns false when the session ID is blank/invalid or the
     * preference backend cannot confirm the write. Persisting ASK_EVERY_TIME removes the entry.
     */
    fun setMode(
        chatSessionId: String?,
        chatModelIdentity: String?,
        imageModelIdentity: String?,
        mode: ChatImageToolAuthorizationMode
    ): Boolean {
        val normalizedId = normalizeSessionId(chatSessionId) ?: return false
        val chatIdentity = hashIdentity(chatModelIdentity, "chat")
        val imageIdentity = hashIdentity(imageModelIdentity, "image")
        if (mode == ChatImageToolAuthorizationMode.AUTO_APPROVE &&
            (chatIdentity == null || imageIdentity == null)
        ) return false
        return synchronized(lock) {
            val values = readMap()
            if (mode == ChatImageToolAuthorizationMode.ASK_EVERY_TIME) {
                values.remove(normalizedId)
            } else {
                values[normalizedId] = StoredAuthorization(
                    chatIdentitySha256 = requireNotNull(chatIdentity),
                    imageIdentitySha256 = requireNotNull(imageIdentity),
                    mode = mode.name
                )
            }
            writeMap(values)
        }
    }

    /** Remove one session's override; a blank ID is ignored and returns false. */
    fun remove(chatSessionId: String?): Boolean {
        val normalizedId = normalizeSessionId(chatSessionId) ?: return false
        return synchronized(lock) {
            val values = readMap()
            val removed = values.remove(normalizedId) != null
            val storedEntries = countStoredEntries()
            if (!removed && values.size == storedEntries &&
                countValidStoredEntries() == storedEntries
            ) return@synchronized false
            writeMap(values) && removed
        }
    }

    /** Remove entries for deleted sessions and malformed keys; returns the number removed. */
    fun prune(activeChatSessionIds: Collection<String?>): Int = synchronized(lock) {
        val activeIds = activeChatSessionIds.mapNotNull(::normalizeSessionId).toSet()
        val values = readMap()
        val before = countStoredEntries()
        values.keys.removeAll { storedId ->
            normalizeSessionId(storedId) == null || storedId !in activeIds
        }
        val removed = (before - values.size).coerceAtLeast(0)
        if ((removed > 0 || values.size != countValidStoredEntries()) && !writeMap(values)) {
            return@synchronized 0
        }
        removed
    }

    private fun readMap(): MutableMap<String, StoredAuthorization> {
        val raw = preferences.getString(STORAGE_KEY) ?: return mutableMapOf()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        val result = mutableMapOf<String, StoredAuthorization>()
        json.keys().forEach { key ->
            val normalizedId = normalizeSessionId(key) ?: return@forEach
            val entry = json.optJSONObject(key) ?: return@forEach
            if (entry.optInt("version", -1) != FORMAT_VERSION) return@forEach
            val chatIdentity = normalizeDigest(entry.optString("chatIdentitySha256")) ?: return@forEach
            val imageIdentity = normalizeDigest(entry.optString("imageIdentitySha256")) ?: return@forEach
            val mode = entry.optString("mode").takeUnless { it == "null" } ?: return@forEach
            // Keep unknown wire values readable as the safe default, then discard them when any
            // mutation rewrites the map instead of perpetuating unsupported values.
            if (ChatImageToolAuthorizationMode.fromStoredValue(mode) !=
                ChatImageToolAuthorizationMode.ASK_EVERY_TIME
            ) {
                result[normalizedId] = StoredAuthorization(chatIdentity, imageIdentity, mode)
            }
        }
        return result
    }

    private fun writeMap(values: Map<String, StoredAuthorization>): Boolean {
        val json = JSONObject()
        values.forEach { (sessionId, authorization) ->
            val normalizedId = normalizeSessionId(sessionId) ?: return@forEach
            val chatIdentity = normalizeDigest(authorization.chatIdentitySha256) ?: return@forEach
            val imageIdentity = normalizeDigest(authorization.imageIdentitySha256) ?: return@forEach
            if (ChatImageToolAuthorizationMode.fromStoredValue(authorization.mode) !=
                ChatImageToolAuthorizationMode.ASK_EVERY_TIME
            ) {
                json.put(normalizedId, JSONObject()
                    .put("version", FORMAT_VERSION)
                    .put("chatIdentitySha256", chatIdentity)
                    .put("imageIdentitySha256", imageIdentity)
                    .put("mode", authorization.mode))
            }
        }
        return preferences.putString(STORAGE_KEY, json.toString())
    }

    private fun normalizeSessionId(sessionId: String?): String? = sessionId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_SESSION_ID_CHARS }

    private fun hashIdentity(identity: String?, domain: String): String? {
        val normalized = identity
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_IDENTITY_CHARS }
            ?: return null
        val bytes = "$domain\u0000$normalized".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun normalizeDigest(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.matches(SHA256_HEX) }

    private fun countStoredEntries(): Int {
        val raw = preferences.getString(STORAGE_KEY) ?: return 0
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return 1
        return json.length()
    }

    /** Number of entries accepted by the current versioned schema. */
    private fun countValidStoredEntries(): Int = readMap().size

    private data class StoredAuthorization(
        val chatIdentitySha256: String,
        val imageIdentitySha256: String,
        val mode: String
    )

    private companion object {
        const val PREFERENCES_NAME = "mca_chat_image_tool_authorization"
        const val STORAGE_KEY = "session_authorization_json"
        const val MAX_SESSION_ID_CHARS = 512
        const val MAX_IDENTITY_CHARS = 8_192
        const val FORMAT_VERSION = 2
        val SHA256_HEX = Regex("^[a-f0-9]{64}$")
        val lock = Any()
    }
}

/** Narrow string-only seam so storage behavior can be unit-tested without Android runtime state. */
internal interface ChatImageToolAuthorizationPreferences {
    fun getString(key: String): String?
    fun putString(key: String, value: String): Boolean
}

private class SharedPreferencesChatImageToolAuthorizationPreferences(
    private val preferences: SharedPreferences
) : ChatImageToolAuthorizationPreferences {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String): Boolean =
        preferences.edit().putString(key, value).commit()
}

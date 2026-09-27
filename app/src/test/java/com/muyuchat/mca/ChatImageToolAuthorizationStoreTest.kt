package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageToolAuthorizationStoreTest {
    @Test
    fun absentSessionOrEitherIdentityDefaultsToAskEveryTime() {
        val store = store()

        assertEquals(ASK, store.getMode("session-1", CHAT_A, IMAGE_A))
        assertEquals(ASK, store.getMode(null, CHAT_A, IMAGE_A))
        assertEquals(ASK, store.getMode("   ", CHAT_A, IMAGE_A))
        assertEquals(ASK, store.getMode("session-1", null, IMAGE_A))
        assertEquals(ASK, store.getMode("session-1", CHAT_A, null))
        assertEquals(ASK, store.getMode("session-1", "  ", IMAGE_A))
        assertEquals(ASK, store.getMode("session-1", CHAT_A, "  "))
    }

    @Test
    fun autoApprovalIsPerSessionAndBoundToBothExactIdentities() {
        val preferences = MemoryPreferences()
        val firstStore = ChatImageToolAuthorizationStore(preferences)
        assertTrue(firstStore.setMode("session-1", CHAT_A, IMAGE_A, AUTO))

        // A new store instance represents reopening the app against the same preferences.
        val reopenedStore = ChatImageToolAuthorizationStore(preferences)
        assertEquals(AUTO, reopenedStore.getMode("session-1", CHAT_A, IMAGE_A))
        assertEquals(ASK, reopenedStore.getMode("session-1", CHAT_B, IMAGE_A))
        assertEquals(ASK, reopenedStore.getMode("session-2", CHAT_A, IMAGE_A))
    }

    @Test
    fun changingEitherIdentityRevokesGrantInsteadOfResurrectingItLater() {
        val store = store()
        assertTrue(store.setMode("session-1", CHAT_A, IMAGE_A, AUTO))

        assertEquals(ASK, store.getMode("session-1", CHAT_B, IMAGE_A))
        assertEquals(ASK, store.getMode("session-1", CHAT_A, IMAGE_A))

        assertTrue(store.setMode("session-1", CHAT_A, IMAGE_A, AUTO))
        assertEquals(ASK, store.getMode("session-1", CHAT_A, IMAGE_B))
        assertEquals(ASK, store.getMode("session-1", CHAT_A, IMAGE_A))
    }

    @Test
    fun missingIdentitiesCannotCreateAutomaticAuthorization() {
        val store = store()

        assertFalse(store.setMode("", CHAT_A, IMAGE_A, AUTO))
        assertFalse(store.setMode("session-1", null, IMAGE_A, AUTO))
        assertFalse(store.setMode("session-1", "  ", IMAGE_A, AUTO))
        assertFalse(store.setMode("session-1", CHAT_A, null, AUTO))
        assertFalse(store.setMode("session-1", CHAT_A, "  ", AUTO))
        assertEquals(ASK, store.getMode("session-1", CHAT_A, IMAGE_A))
    }

    @Test
    fun settingAskEveryTimeRemovesTheSessionOverride() {
        val preferences = MemoryPreferences()
        val store = ChatImageToolAuthorizationStore(preferences)
        store.setMode("session-1", CHAT_A, IMAGE_A, AUTO)

        assertTrue(store.setMode("session-1", null, null, ASK))
        assertEquals(ASK, store.getMode("session-1", CHAT_A, IMAGE_A))
        assertEquals(0, JSONObject(preferences.value()).length())
    }

    @Test
    fun legacyOrUnknownStoredValuesSafelyInvalidateAndAreRewritten() {
        val preferences = MemoryPreferences()
        preferences.seed(
            JSONObject()
                .put("session-1", JSONObject()
                    .put("modelId", "cloud-model-a")
                    .put("mode", "AUTO_APPROVE"))
                .toString()
        )

        assertEquals(ASK, ChatImageToolAuthorizationStore(preferences)
            .getMode("session-1", CHAT_A, IMAGE_A))
        assertEquals(0, JSONObject(preferences.value()).length())

        preferences.seed(
            JSONObject()
                .put("session-1", JSONObject()
                    .put("version", 2)
                    .put("chatIdentitySha256", "a".repeat(64))
                    .put("imageIdentitySha256", "b".repeat(64))
                    .put("mode", "APPROVE_ALL_FUTURE_TOOLS"))
                .toString()
        )
        assertEquals(ASK, ChatImageToolAuthorizationStore(preferences)
            .getMode("session-1", CHAT_A, IMAGE_A))
        assertEquals(0, JSONObject(preferences.value()).length())
    }

    @Test
    fun persistedAuthorizationContainsOnlyIdentityDigests() {
        val preferences = MemoryPreferences()
        val store = ChatImageToolAuthorizationStore(preferences)
        val chatIdentity = "provider/chat-model/endpoint-without-key"
        val imageIdentity = "local/backend/model-config"

        assertTrue(store.setMode("session-1", chatIdentity, imageIdentity, AUTO))

        val raw = preferences.value()
        assertFalse(raw.contains(chatIdentity))
        assertFalse(raw.contains(imageIdentity))
        assertTrue(raw.contains("chatIdentitySha256"))
        assertTrue(raw.contains("imageIdentitySha256"))
        assertEquals(AUTO, store.getMode("session-1", chatIdentity, imageIdentity))
    }

    @Test
    fun removeAndPruneOnlyAffectMatchingSessionOverrides() {
        val preferences = MemoryPreferences()
        val store = ChatImageToolAuthorizationStore(preferences)
        listOf("session-1", "session-2", "session-3").forEach { sessionId ->
            assertTrue(store.setMode(sessionId, CHAT_A, IMAGE_A, AUTO))
        }

        assertFalse(store.remove("missing"))
        assertTrue(store.remove("session-1"))
        assertEquals(1, store.prune(listOf("session-2", "session-2", " ", null)))
        assertEquals(ASK, store.getMode("session-1", CHAT_A, IMAGE_A))
        assertEquals(AUTO, store.getMode("session-2", CHAT_A, IMAGE_A))
        assertEquals(ASK, store.getMode("session-3", CHAT_A, IMAGE_A))
    }

    @Test
    fun corruptedPreferenceFallsBackAndCanBeRewritten() {
        val preferences = MemoryPreferences().apply { seed("not-json") }
        val store = ChatImageToolAuthorizationStore(preferences)

        assertEquals(ASK, store.getMode("session-1", CHAT_A, IMAGE_A))
        assertTrue(store.setMode("session-1", CHAT_A, IMAGE_A, AUTO))
        assertEquals(AUTO, store.getMode("session-1", CHAT_A, IMAGE_A))
    }

    private fun store() = ChatImageToolAuthorizationStore(MemoryPreferences())

    private class MemoryPreferences : ChatImageToolAuthorizationPreferences {
        private var raw: String? = null

        override fun getString(key: String): String? = raw

        override fun putString(key: String, value: String): Boolean {
            raw = value
            return true
        }

        fun seed(value: String) {
            raw = value
        }

        fun value(): String = requireNotNull(raw)
    }

    private companion object {
        const val CHAT_A = "chat-config-a"
        const val CHAT_B = "chat-config-b"
        const val IMAGE_A = "image-config-a"
        const val IMAGE_B = "image-config-b"
        val ASK = ChatImageToolAuthorizationMode.ASK_EVERY_TIME
        val AUTO = ChatImageToolAuthorizationMode.AUTO_APPROVE
    }
}

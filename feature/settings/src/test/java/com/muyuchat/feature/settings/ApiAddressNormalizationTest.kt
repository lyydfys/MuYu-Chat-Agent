package com.muyuchat.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiAddressNormalizationTest {
    @Test
    fun keepsCanonicalV1BaseUrl() {
        assertEquals(
            "http://127.0.0.1:11435/v1",
            "http://127.0.0.1:11435/v1".normalizedApiBase()
        )
    }

    @Test
    fun normalizesTrailingSlashWithoutDuplicatingV1() {
        assertEquals(
            "http://127.0.0.1:11435/v1",
            "http://127.0.0.1:11435/v1/".normalizedApiBase()
        )
    }

    @Test
    fun normalizesFullChatEndpointToBaseUrl() {
        assertEquals(
            "http://192.168.1.6:11435/v1",
            "http://192.168.1.6:11435/v1/chat/completions".normalizedApiBase()
        )
    }

    @Test
    fun normalizesModelsEndpointToBaseUrl() {
        assertEquals(
            "http://192.168.1.6:11435/v1",
            "http://192.168.1.6:11435/v1/models".normalizedApiBase()
        )
    }
}

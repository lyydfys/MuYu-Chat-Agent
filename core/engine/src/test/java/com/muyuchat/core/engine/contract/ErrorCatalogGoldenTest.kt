package com.muyuchat.core.engine.contract

import com.muyuchat.core.engine.contract.error.ErrorCatalogRegistry
import com.muyuchat.core.engine.contract.error.StableErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ErrorCatalogGoldenTest {

    @Test
    fun `error_catalog json file matches kotlin registry codes and count`() {
        // Locate tools/error-catalog/error_catalog.json relative to repository root
        var catalogFile: File? = null
        var current: File? = File(".").canonicalFile
        while (current != null) {
            val candidate = File(current, "tools/error-catalog/error_catalog.json")
            if (candidate.isFile) {
                catalogFile = candidate
                break
            }
            current = current.parentFile
        }

        assertNotNull("error_catalog.json should exist in tools/error-catalog/", catalogFile)
        val jsonText = catalogFile!!.readText(Charsets.UTF_8)

        val allCodes = StableErrorCode.entries
        for (code in allCodes) {
            assertTrue(
                "JSON catalog must contain code '${code.name}'",
                jsonText.contains("\"code\": \"${code.name}\"")
            )
            val entry = ErrorCatalogRegistry.get(code)
            assertTrue(
                "JSON catalog must contain userMessageKey '${entry.userMessageKey}'",
                jsonText.contains(entry.userMessageKey)
            )
        }

        assertEquals(allCodes.size, ErrorCatalogRegistry.allEntries().size)
    }
}

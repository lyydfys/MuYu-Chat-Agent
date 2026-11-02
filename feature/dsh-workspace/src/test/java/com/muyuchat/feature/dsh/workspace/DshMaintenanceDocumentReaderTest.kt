package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DSH_MAINTENANCE_MAX_DOCUMENT_CHARS
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounds of the user-selected patch/theme document.
 *
 * The picker is a trust boundary: an arbitrary provider can stream anything. These tests pin that
 * an empty, oversized or over-long document is rejected before an intent is constructed (the intent
 * itself refuses an oversized payload, so constructing one with a too-long string would crash), and
 * that the document is attached only to the two intents that accept one.
 */
class DshMaintenanceDocumentReaderTest {

    @Test
    fun emptyAndMissingDocumentsAreRejected() {
        assertNull(DshMaintenanceDocumentReader.decodeBounded(null))
        assertNull(DshMaintenanceDocumentReader.decodeBounded(ByteArray(0)))
        assertNull(DshMaintenanceDocumentReader.decodeBounded("   \n\t ".toByteArray()))
    }

    @Test
    fun aNormalJsonDocumentIsReturnedAsIs() {
        val text = """{"files":[{"path":"index.js","content":"x"}]}"""
        assertEquals(text, DshMaintenanceDocumentReader.decodeBounded(text.toByteArray()))
    }

    @Test
    fun anOversizedStreamIsNotReadAtAll() {
        val oversized = ByteArray(DshMaintenanceDocumentReader.MAX_BYTES + 1) { 'x'.code.toByte() }
        assertNull(DshMaintenanceDocumentReader.readBounded(ByteArrayInputStream(oversized)))

        val atLimit = ByteArray(DshMaintenanceDocumentReader.MAX_BYTES) { 'x'.code.toByte() }
        assertEquals(atLimit.size, DshMaintenanceDocumentReader.readBounded(ByteArrayInputStream(atLimit))?.size)
    }

    @Test
    fun aDocumentLongerThanTheContractBoundIsRejected() {
        val tooLong = "x".repeat(DSH_MAINTENANCE_MAX_DOCUMENT_CHARS + 1)
        assertNull(DshMaintenanceDocumentReader.decodeBounded(tooLong.toByteArray()))
    }

    @Test
    fun theDocumentIsAttachedOnlyToTheIntentsThatAcceptOne() {
        val patch = DshMaintenanceConsoleIntent.ApplyConsolePatch(confirmed = true)
        val attachedPatch = withDocument(patch, "{}") as DshMaintenanceConsoleIntent.ApplyConsolePatch
        assertEquals("{}", attachedPatch.payload)
        assertTrue(attachedPatch.confirmed)

        val theme = DshMaintenanceConsoleIntent.ImportTheme(confirmed = true)
        assertEquals(
            "{}",
            (withDocument(theme, "{}") as DshMaintenanceConsoleIntent.ImportTheme).payload
        )

        // An intent without a document field is returned unchanged, never silently replaced.
        assertEquals(
            DshMaintenanceConsoleIntent.RunSelfCheck,
            withDocument(DshMaintenanceConsoleIntent.RunSelfCheck, "{}")
        )
        assertTrue(requiresUserDocument(patch))
        assertTrue(requiresUserDocument(theme))
        assertFalse(requiresUserDocument(DshMaintenanceConsoleIntent.ResetTheme))
        assertFalse(requiresUserDocument(DshMaintenanceConsoleIntent.RepairManagedFiles()))
    }
}

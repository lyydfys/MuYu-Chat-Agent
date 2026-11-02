package com.muyuchat.feature.dsh.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshDownloadsTest {

    @Test
    fun httpDownloadKeepsFileNameAndMime() {
        val inspection = DshDownloadPolicy.classify(
            DshDownloadRequest(
                url = "http://127.0.0.1:34567/files/report.pdf?download=1",
                userAgent = "Mozilla/5.0",
                contentDisposition = "attachment; filename=\"Quarterly Report.pdf\"",
                mimeType = "application/pdf",
                contentLengthBytes = 1024
            )
        )
        assertTrue(inspection is DshDownloadInspection.Http)
        inspection as DshDownloadInspection.Http
        assertEquals("Quarterly Report.pdf", inspection.suggestedFileName)
        assertEquals("application/pdf", inspection.mimeType)
        assertTrue(inspection.url.startsWith("http://127.0.0.1:34567/"))
    }

    @Test
    fun contentDispositionRfc5987AndUrlFallbacksProduceSafeNames() {
        assertEquals(
            "näme.txt",
            DshDownloadPolicy.suggestedFileName(
                "attachment; filename*=UTF-8''n%C3%A4me.txt",
                null,
                "text/plain"
            )
        )
        assertEquals(
            "artifact.json",
            DshDownloadPolicy.suggestedFileName(
                null,
                "http://127.0.0.1:34567/api/artifact.json",
                "application/json"
            )
        )
        assertEquals(
            "dsh-download.zip",
            DshDownloadPolicy.suggestedFileName(null, null, "application/zip")
        )
    }

    @Test
    fun fileNamesCannotEscapeOrContainReservedCharacters() {
        assertEquals("evil.txt", DshDownloadPolicy.sanitizeFileName("../../evil.txt"))
        assertEquals("evil.txt", DshDownloadPolicy.sanitizeFileName("..\\..\\evil.txt"))
        assertEquals("abc.txt", DshDownloadPolicy.sanitizeFileName("a:b*c?.txt"))
        assertEquals("dsh-download.bin", DshDownloadPolicy.sanitizeFileName("   "))
        assertEquals("report.bin", DshDownloadPolicy.sanitizeFileName("report", ".bin"))
        assertFalse(DshDownloadPolicy.sanitizeFileName("..", ".bin").startsWith("."))
    }

    @Test
    fun dataUrlBase64AndPlainAreDecodedWithinTheBound() {
        val base64 = DshDownloadPolicy.parseDataUrl(
            "data:text/plain;base64,aGVsbG8gd29ybGQ=",
            maxBytes = 64
        )
        assertTrue(base64 is DshDownloadPolicy.DataUrlResult.Parsed)
        base64 as DshDownloadPolicy.DataUrlResult.Parsed
        assertArrayEquals("hello world".toByteArray(), base64.bytes)
        assertEquals("text/plain", base64.mimeType)

        val plain = DshDownloadPolicy.parseDataUrl(
            "data:application/json,%7B%22a%22%3A1%7D",
            maxBytes = 64
        )
        assertTrue(plain is DshDownloadPolicy.DataUrlResult.Parsed)
        assertEquals(
            "{\"a\":1}",
            (plain as DshDownloadPolicy.DataUrlResult.Parsed).bytes.toString(Charsets.UTF_8)
        )
        // A literal '+' in a data URL is a plus, not a space.
        val plus = DshDownloadPolicy.parseDataUrl("data:text/plain,a+b", maxBytes = 64)
        assertEquals(
            "a+b",
            (plus as DshDownloadPolicy.DataUrlResult.Parsed).bytes.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun dataUrlOversizeAndMalformedAreRejected() {
        val oversize = DshDownloadPolicy.parseDataUrl(
            "data:application/octet-stream;base64," + "A".repeat(200),
            maxBytes = 8
        )
        assertEquals(
            DshDownloadRejection.DATA_URL_TOO_LARGE,
            rejectedDataUrlReason(oversize)
        )
        val noComma = DshDownloadPolicy.parseDataUrl("data:text/plain;base64", maxBytes = 8)
        assertEquals(
            DshDownloadRejection.DATA_URL_MALFORMED,
            rejectedDataUrlReason(noComma)
        )
    }

    @Test
    fun unsupportedSchemesAndCredentialUrlsAreRejected() {
        assertEquals(
            DshDownloadRejection.UNSUPPORTED_SCHEME,
            rejectedInspectionReason(
                DshDownloadPolicy.classify(
                    DshDownloadRequest("blob:http://127.0.0.1:34567/uuid", null, null, null)
                )
            )
        )
        listOf("javascript:alert(1)", "file:///sdcard/x", "content://x/y").forEach { url ->
            assertEquals(
                DshDownloadRejection.UNSUPPORTED_SCHEME,
                rejectedInspectionReason(
                    DshDownloadPolicy.classify(DshDownloadRequest(url, null, null, null))
                )
            )
        }
        assertEquals(
            DshDownloadRejection.CREDENTIALS_IN_URL,
            rejectedInspectionReason(
                DshDownloadPolicy.classify(
                    DshDownloadRequest("http://user:pass@example.com/x", null, null, null)
                )
            )
        )
        assertEquals(
            DshDownloadRejection.URL_TOO_LONG,
            rejectedInspectionReason(
                DshDownloadPolicy.classify(
                    DshDownloadRequest(
                        "https://example.com/" + "a".repeat(DshDownloadPolicy.MAX_HTTP_URL_LENGTH),
                        null,
                        null,
                        null
                    )
                )
            )
        )
    }

    @Test
    fun headerValuesCannotInjectHeaders() {
        assertEquals("Mozilla/5.0", DshDownloadPolicy.safeHeaderValue("Mozilla/5.0\r\nX-Evil: 1"))
        assertNull(DshDownloadPolicy.safeHeaderValue("  "))
        assertNull(DshDownloadPolicy.safeHeaderValue(null))
        assertFalse(DshDownloadPolicy.safeHeaderValue("a\nb")!!.contains('\n'))
    }

    @Test
    fun rejectionMessagesArePromptFree() {
        DshDownloadRejection.values().forEach { reason ->
            val message = DshDownloadPolicy.messageFor(reason)
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("http"))
        }
    }
}

private fun rejectedDataUrlReason(result: DshDownloadPolicy.DataUrlResult): DshDownloadRejection? =
    (result as? DshDownloadPolicy.DataUrlResult.Rejected)?.reason

private fun rejectedInspectionReason(inspection: DshDownloadInspection): DshDownloadRejection? =
    (inspection as? DshDownloadInspection.Rejected)?.reason

package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

class ImageGenerationBitmapEditingContractTest {
    private val authority = "com.muyuchat.mca.fileprovider"
    private val firstName = "00000000-0000-0000-0000-000000000001.png"
    private val secondName = "00000000-0000-0000-0000-000000000002.png"

    @Test
    fun `owned URI classifier accepts only the restricted provider path and UUID PNG`() {
        assertEquals(
            firstName,
            ownedGenerationImageFileNameOrNull(
                scheme = "content",
                authority = authority,
                pathSegments = listOf(GENERATION_IMAGE_INPUT_PROVIDER_PATH, firstName),
                expectedAuthority = authority
            )
        )
        assertNull(
            ownedGenerationImageFileNameOrNull(
                "content",
                "other.fileprovider",
                listOf(GENERATION_IMAGE_INPUT_PROVIDER_PATH, firstName),
                authority
            )
        )
        assertNull(
            ownedGenerationImageFileNameOrNull(
                "content",
                authority,
                listOf("shared_images", firstName),
                authority
            )
        )
        assertNull(
            ownedGenerationImageFileNameOrNull(
                "content",
                authority,
                listOf(GENERATION_IMAGE_INPUT_PROVIDER_PATH, "../$firstName"),
                authority
            )
        )
    }

    @Test
    fun `encoded input and output size contract is nonempty and capped`() {
        assertFalse(isGenerationImageEncodedFileSizeAllowed(0L))
        assertTrue(isGenerationImageEncodedFileSizeAllowed(1L))
        assertTrue(isGenerationImageEncodedFileSizeAllowed(MAX_EDITABLE_ENCODED_IMAGE_BYTES))
        assertFalse(isGenerationImageEncodedFileSizeAllowed(MAX_EDITABLE_ENCODED_IMAGE_BYTES + 1L))
    }

    @Test
    fun `snapshot copy accepts exact limit and rejects on the very next byte`() {
        val exactInput = CountingInputStream(MAX_EDITABLE_ENCODED_IMAGE_BYTES)
        val exactOutput = CountingOutputStream()
        assertEquals(
            MAX_EDITABLE_ENCODED_IMAGE_BYTES,
            copyGenerationImageSnapshot(exactInput, exactOutput)
        )
        assertEquals(MAX_EDITABLE_ENCODED_IMAGE_BYTES, exactOutput.written)

        val oversizedInput = CountingInputStream(MAX_EDITABLE_ENCODED_IMAGE_BYTES + 10L)
        val oversizedOutput = CountingOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            copyGenerationImageSnapshot(oversizedInput, oversizedOutput)
        }
        assertEquals(MAX_EDITABLE_ENCODED_IMAGE_BYTES + 1L, oversizedInput.delivered)
        assertEquals(MAX_EDITABLE_ENCODED_IMAGE_BYTES, oversizedOutput.written)
    }

    @Test
    fun `snapshot copy advances a provider zero read and propagates cancellation checks`() {
        val payload = byteArrayOf(0, 1, 2, 3)
        val zeroThenData = object : InputStream() {
            private val delegate = ByteArrayInputStream(payload)
            private var returnedZero = false

            override fun read(): Int = delegate.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!returnedZero) {
                    returnedZero = true
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }
        }
        val output = CountingOutputStream()
        assertEquals(payload.size.toLong(), copyGenerationImageSnapshot(zeroThenData, output))
        assertEquals(payload.size.toLong(), output.written)

        var checks = 0
        val cancellation = assertThrows(IllegalStateException::class.java) {
            copyGenerationImageSnapshot(
                input = CountingInputStream(1_000_000L),
                output = CountingOutputStream(),
                checkCancelled = {
                    checks += 1
                    if (checks == 3) error("cancelled")
                }
            )
        }
        assertEquals("cancelled", cancellation.message)
        assertEquals(3, checks)
    }

    @Test
    fun `EXIF orientations one through eight map an asymmetric image exactly`() {
        val source = listOf("ABC", "DEF")
        val expected = mapOf(
            1 to listOf("ABC", "DEF"),
            2 to listOf("CBA", "FED"),
            3 to listOf("FED", "CBA"),
            4 to listOf("DEF", "ABC"),
            5 to listOf("AD", "BE", "CF"),
            6 to listOf("DA", "EB", "FC"),
            7 to listOf("FC", "EB", "DA"),
            8 to listOf("CF", "BE", "AD")
        )

        expected.forEach { (orientation, expectedRows) ->
            val transform = exifOrientationTransform(orientation)
            val output = List(transform.outputHeight(3, 2)) {
                CharArray(transform.outputWidth(3, 2)) { '?' }
            }
            source.forEachIndexed { y, row ->
                row.forEachIndexed { x, character ->
                    val (outputX, outputY) = transform.mapSourcePixel(x, y, 3, 2)
                    output[outputY][outputX] = character
                }
            }
            assertEquals("orientation $orientation", expectedRows, output.map { String(it) })
        }
        assertEquals(ExifOrientationTransform(90, true), exifOrientationTransform(5))
        assertEquals(ExifOrientationTransform(-90, true), exifOrientationTransform(7))
    }

    @Test
    fun `reconciliation deletes only stale unreferenced owned PNGs and temporary files`() {
        val now = 100_000L
        val grace = 10_000L
        val files = listOf(
            GenerationOwnedInputFileSnapshot(firstName, isFile = true, lastModifiedMillis = 1L),
            GenerationOwnedInputFileSnapshot(secondName, isFile = true, lastModifiedMillis = 1L),
            GenerationOwnedInputFileSnapshot(
                secondName + ".tmp",
                isFile = true,
                lastModifiedMillis = 1L
            ),
            GenerationOwnedInputFileSnapshot(
                "00000000-0000-0000-0000-000000000003.png",
                isFile = true,
                lastModifiedMillis = now
            ),
            GenerationOwnedInputFileSnapshot("notes.txt", isFile = true, lastModifiedMillis = 1L),
            GenerationOwnedInputFileSnapshot(
                "00000000-0000-0000-0000-000000000004.png",
                isFile = false,
                lastModifiedMillis = 1L
            )
        )

        assertEquals(
            setOf(secondName, secondName + ".tmp"),
            generationOwnedInputFileNamesToDelete(
                files = files,
                referencedNames = setOf(firstName),
                nowMillis = now,
                graceMillis = grace
            )
        )
    }

    private class CountingInputStream(totalBytes: Long) : InputStream() {
        private var remaining = totalBytes
        var delivered: Long = 0L
            private set

        override fun read(): Int {
            if (remaining == 0L) return -1
            remaining -= 1L
            delivered += 1L
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            remaining -= count
            delivered += count
            return count
        }
    }

    private class CountingOutputStream : OutputStream() {
        var written: Long = 0L
            private set

        override fun write(value: Int) {
            written += 1L
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            written += length
        }
    }
}

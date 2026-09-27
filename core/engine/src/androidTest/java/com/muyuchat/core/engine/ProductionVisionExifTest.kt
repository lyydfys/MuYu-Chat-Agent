package com.muyuchat.core.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionVisionExifTest {
    @Test
    fun allExifOrientationsAreCorrectedBeforeNativeInputAndReleasedAfterward() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheDir = File(context.cacheDir, "vision-exif-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(cacheDir, "source.jpg")
        try {
            writeQuadrantJpeg(source)
            val expectedCorners = mapOf(
                2 to listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
                3 to listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                4 to listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
                5 to listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
                6 to listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                7 to listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
                8 to listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
            )
            expectedCorners.forEach { (orientation, corners) ->
                ExifInterface(source.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val original = ChatRequest(messages = listOf(ChatMessage(
                    role = Role.USER,
                    content = "Describe this image",
                    imageAttachments = listOf(ChatImageAttachment(
                        name = "source.jpg",
                        uriString = source.absolutePath,
                        mimeType = "image/jpeg",
                        width = 100,
                        height = 60
                    ))
                )))
                val prepared = LocalVisionInputPreparer.prepare(original, cacheDir)
                val attachment = prepared.messages.single().imageAttachments.single()
                val output = File(attachment.uriString)
                assertTrue(output.isFile)
                assertTrue(output.canonicalPath != source.canonicalPath)
                assertTrue(source.isFile)
                val expectedWidth = if (orientation in 5..8) 60 else 100
                val expectedHeight = if (orientation in 5..8) 100 else 60
                assertEquals(expectedWidth, attachment.width)
                assertEquals(expectedHeight, attachment.height)
                val bitmap = requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
                try {
                    assertEquals(expectedWidth, bitmap.width)
                    assertEquals(expectedHeight, bitmap.height)
                    val positions = listOf(
                        bitmap.width / 4 to bitmap.height / 4,
                        bitmap.width * 3 / 4 to bitmap.height / 4,
                        bitmap.width / 4 to bitmap.height * 3 / 4,
                        bitmap.width * 3 / 4 to bitmap.height * 3 / 4
                    )
                    positions.zip(corners).forEach { (position, expectedColor) ->
                        assertColorNear(expectedColor, bitmap.getPixel(position.first, position.second))
                    }
                } finally {
                    bitmap.recycle()
                }
                LocalVisionInputPreparer.releasePreparedInputs(original, prepared, cacheDir)
                assertFalse(output.exists())
                assertTrue(source.isFile)
            }
        } finally {
            source.delete()
            cacheDir.deleteRecursively()
        }
    }

    private fun writeQuadrantJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, when {
                    y < 30 && x < 50 -> Color.RED
                    y < 30 -> Color.GREEN
                    x < 50 -> Color.BLUE
                    else -> Color.YELLOW
                })
            }
            file.outputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 45)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 45)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 45)
    }
}

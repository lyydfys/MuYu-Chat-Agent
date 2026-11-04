package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生图输入体检的纯函数合同。
 *
 * 这些分支对应真实用户可见的失败：文件被删（MISSING）、权限失效（NOT_READABLE）、0 字节
 * （EMPTY）、超大（TOO_LARGE）、损坏/不支持（UNSUPPORTED_FORMAT）、尺寸非法
 * （INVALID_DIMENSIONS）。提交前命中任意一项都必须给出可操作原因，而不是等 worker 打开文件时
 * 抛出 `image_assets/... open failed: ENOENT`。
 */
class ImageAssetPreflightTest {

    private val limits = ImageAssetPreflightLimits(maxBytes = 1_024L, maxSide = 1_920)

    @Test
    fun `missing file wins over every other check`() {
        val reason = classifyImageAssetProbe(
            probe(exists = false, canRead = false, sizeBytes = 0L, header = ByteArray(0)),
            limits,
        )
        assertEquals(ImageAssetPreflightReason.MISSING, reason)
        assertEquals("输入图片已丢失，请重新选择图片。", imageAssetPreflightReasonMessage(reason))
    }

    @Test
    fun `unreadable file is not reported as missing`() {
        val reason = classifyImageAssetProbe(probe(canRead = false), limits)
        assertEquals(ImageAssetPreflightReason.NOT_READABLE, reason)
        assertTrue(imageAssetPreflightReasonMessage(reason)!!.contains("无法读取"))
    }

    @Test
    fun `empty and oversized files are rejected before decoding`() {
        assertEquals(
            ImageAssetPreflightReason.EMPTY,
            classifyImageAssetProbe(probe(sizeBytes = 0L), limits),
        )
        assertEquals(
            ImageAssetPreflightReason.TOO_LARGE,
            classifyImageAssetProbe(probe(sizeBytes = 2_048L), limits),
        )
    }

    @Test
    fun `unknown or corrupt headers are unsupported`() {
        assertEquals(
            ImageAssetPreflightReason.UNSUPPORTED_FORMAT,
            classifyImageAssetProbe(probe(header = "not-an-image".toByteArray()), limits),
        )
    }

    @Test
    fun `zero or over-limit dimensions are rejected only when known`() {
        assertEquals(
            ImageAssetPreflightReason.INVALID_DIMENSIONS,
            classifyImageAssetProbe(probe(width = 0, height = 0), limits),
        )
        assertEquals(
            ImageAssetPreflightReason.INVALID_DIMENSIONS,
            classifyImageAssetProbe(probe(width = 4_096, height = 600), limits),
        )
        // data URL 未解码时尺寸未知：只要字节数合法就可以进入显式解码阶段。
        assertEquals(
            ImageAssetPreflightReason.OK,
            classifyImageAssetProbe(
                probe(width = 0, height = 0, dimensionsKnown = false),
                limits,
            ),
        )
    }

    @Test
    fun `jpeg png and webp headers are recognised`() {
        assertEquals(ImageAssetFormat.JPEG, detectImageHeaderFormat(jpegHeader()))
        assertEquals(ImageAssetFormat.PNG, detectImageHeaderFormat(pngHeader()))
        assertEquals(ImageAssetFormat.WEBP, detectImageHeaderFormat(webpHeader()))
        assertNull(detectImageHeaderFormat(ByteArray(0)))
    }

    @Test
    fun `a valid image passes`() {
        assertEquals(ImageAssetPreflightReason.OK, classifyImageAssetProbe(probe(), limits))
        assertNull(imageAssetPreflightReasonMessage(ImageAssetPreflightReason.OK))
    }

    @Test
    fun `data url references are classified without decoding`() {
        assertEquals(
            ImageAssetPreflightReason.OK,
            classifyImageDataUrlReference("data:image/png;base64,AAAA", limits),
        )
        assertEquals(
            ImageAssetPreflightReason.UNSUPPORTED_FORMAT,
            classifyImageDataUrlReference("data:text/plain;base64,AAAA", limits),
        )
        assertEquals(
            ImageAssetPreflightReason.EMPTY,
            classifyImageDataUrlReference("data:image/png;base64,", limits),
        )
        val huge = "data:image/png;base64," + "A".repeat(2_000)
        assertEquals(
            ImageAssetPreflightReason.TOO_LARGE,
            classifyImageDataUrlReference(huge, limits),
        )
    }

    @Test
    fun `role specific messages name the field that must be re-picked`() {
        val mask = imageAssetPreflightReasonMessage(
            ImageAssetPreflightReason.MISSING,
            ImageInputRole.MASK,
        )!!
        assertTrue(mask.contains("蒙版"))
        assertTrue(mask.contains("重新选择"))
        val control = imageAssetPreflightReasonMessage(
            ImageAssetPreflightReason.UNSUPPORTED_FORMAT,
            ImageInputRole.CONTROL,
        )!!
        assertTrue(control.contains("控制图片"))
        assertTrue(control.contains("JPG"))
    }

    private fun probe(
        exists: Boolean = true,
        canRead: Boolean = true,
        sizeBytes: Long = 512L,
        header: ByteArray = pngHeader(),
        width: Int = 800,
        height: Int = 600,
        dimensionsKnown: Boolean = true,
    ): ImageAssetProbe = ImageAssetProbe(
        exists = exists,
        canRead = canRead,
        sizeBytes = sizeBytes,
        header = header,
        width = width,
        height = height,
        dimensionsKnown = dimensionsKnown,
    )

    private fun pngHeader(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun jpegHeader(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
    )

    private fun webpHeader(): ByteArray = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
        0x57, 0x45, 0x42, 0x50,
    )
}

package com.muyuchat.mca

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAssetDeletionLeaseTest {
    @Test
    fun `rollback restores staged bytes to their original path`() {
        val root = Files.createTempDirectory("image-asset-rollback").toFile()
        try {
            val original = root.resolve("image.png")
            val staged = root.resolve(".delete-11111111-1111-4111-8111-111111111111--image.png")
            original.writeText("original-bytes")
            assertTrue(original.renameTo(staged))

            val lease = ImageAssetDeletionLease(original = original, staged = staged)

            assertTrue(lease.rollback())
            assertTrue(original.isFile)
            assertEquals("original-bytes", original.readText())
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `commit removes staged bytes without recreating the original`() {
        val root = Files.createTempDirectory("image-asset-commit").toFile()
        try {
            val original = root.resolve("image.png")
            val staged = root.resolve(".delete-22222222-2222-4222-8222-222222222222--image.png")
            staged.writeText("staged-bytes")

            val lease = ImageAssetDeletionLease(original = original, staged = staged)

            assertTrue(lease.commit())
            assertFalse(original.exists())
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}

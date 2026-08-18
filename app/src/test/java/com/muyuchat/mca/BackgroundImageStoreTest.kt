package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundImageStoreTest {
    @Test
    fun ownedPathMustBeADirectCanonicalChild() {
        val temporary = Files.createTempDirectory("mca-background-store").toFile()
        try {
            val root = File(temporary, "background_images").apply { mkdirs() }
            assertTrue(isBackgroundImagePathOwned(root, File(root, "image.jpg")))
            assertFalse(isBackgroundImagePathOwned(root, File(root, "nested/image.jpg")))
            assertFalse(isBackgroundImagePathOwned(root, File(temporary, "outside.jpg")))
            assertFalse(isBackgroundImagePathOwned(root, File(root, "../outside.jpg")))
        } finally {
            temporary.deleteRecursively()
        }
    }
}

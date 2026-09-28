package com.muyuchat.mca

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoadedVisionProjectorStampTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun loadedProjectorRejectsReplacementOrDifferentIdentity() {
        val projector = folder.newFile("mmproj.gguf")
        projector.writeBytes(byteArrayOf(1, 2, 3))
        val stamp = LoadedVisionProjectorStamp.capture(projector, "loaded-sha")
        assertNotNull(stamp)
        assertTrue(stamp!!.matches(projector, "loaded-sha"))
        assertFalse(stamp.matches(projector, "other-sha"))

        projector.writeBytes(byteArrayOf(4, 5, 6, 7))
        assertFalse(stamp.matches(projector, "loaded-sha"))
    }

    @Test fun missingProjectorCannotBeCapturedOrReused() {
        val projector = folder.root.resolve("missing.gguf")
        assertTrue(LoadedVisionProjectorStamp.capture(projector, "loaded-sha") == null)
        projector.writeBytes(byteArrayOf(1))
        val stamp = LoadedVisionProjectorStamp.capture(projector, "loaded-sha")!!
        assertTrue(projector.delete())
        assertFalse(stamp.matches(projector, "loaded-sha"))
    }
}

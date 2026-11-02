package com.muyuchat.mca.video.settings

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoDraftFileStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("draft-store").toFile()

    @Test
    fun `missing file loads as null`() {
        val dir = tempDir()
        assertNull(VideoDraftFileStore(File(dir, "video/draft.txt")).load())
    }

    @Test
    fun `save then load round-trips the encoded draft`() {
        val dir = tempDir()
        val store = VideoDraftFileStore(File(dir, "video/draft.txt"))
        store.save("draft1|wf|rev|MCA_NATIVE")
        assertEquals("draft1|wf|rev|MCA_NATIVE", store.load())
    }

    @Test
    fun `blank content is treated as no draft`() {
        val dir = tempDir()
        val file = File(dir, "video/draft.txt")
        file.parentFile!!.mkdirs()
        file.writeText("   ")
        assertNull(VideoDraftFileStore(file).load())
    }

    @Test
    fun `overwriting keeps the newest draft and leaves no temp file`() {
        val dir = tempDir()
        val file = File(dir, "video/draft.txt")
        val store = VideoDraftFileStore(file)
        store.save("first")
        store.save("second")
        assertEquals("second", store.load())
        assertNull(File(file.parentFile, "draft.txt.tmp").takeIf { it.exists() })
    }
}

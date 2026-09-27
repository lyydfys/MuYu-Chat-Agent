package com.muyuchat.core.modelstore

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStoreManagedBundlePathTest {
    @Test
    fun resolvingPublicBundlePathDoesNotCreateAnEmptyInstalledDirectory() {
        val root = Files.createTempDirectory("mca-managed-bundle-path").toFile()
        try {
            val repository = ModelStoreRepository(TestFilesContext(root))

            val bundleDir = repository.managedBundleDirFor("downloaded-model")

            assertTrue(requireNotNull(bundleDir.parentFile).isDirectory)
            assertFalse("The public bundle path must appear only after an atomic install", bundleDir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private class TestFilesContext(private val root: File) : ContextWrapper(null) {
        private val files = File(root, "files")
        private val externalFiles = File(root, "external-files")

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files.apply { mkdirs() }
        override fun getExternalFilesDir(type: String?): File = externalFiles.apply { mkdirs() }
    }
}

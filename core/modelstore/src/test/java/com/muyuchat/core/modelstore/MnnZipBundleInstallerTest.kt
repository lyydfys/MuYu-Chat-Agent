package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MnnZipBundleInstallerTest {
    @Test
    fun installsSingleWrappedBundleWithoutFlatteningNestedComponents() {
        val root = Files.createTempDirectory("mca-mnn-zip").toFile()
        try {
            val archive = zip(
                "wrapper/config.json" to nestedConfig(),
                "wrapper/config/llm.json" to "{}",
                "wrapper/models/llm.mnn" to "model",
                "wrapper/models/llm.mnn.weight" to "weight",
                "wrapper/tokenizer/tokenizer.mtok" to "tokenizer",
                "wrapper/embeddings/embeddings_bf16.bin" to "embeddings",
                "wrapper/docs/config.json" to "not-the-root"
            )
            val target = File(root, "imported")

            val result = MnnZipBundleInstaller().install(
                ByteArrayInputStream(archive),
                target,
                archive.size.toLong()
            )

            assertEquals(target.canonicalFile, result.bundleRoot)
            assertTrue(File(target, "models/llm.mnn").isFile)
            assertTrue(File(target, "config/llm.json").isFile)
            assertTrue(File(target, "docs/config.json").isFile)
            assertFalse(File(target, "llm.mnn").exists())
            assertTrue(MnnBundleReadinessAnalyzer.analyze(target).canLoad)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalAndLeavesDestinationAbsent() {
        val root = Files.createTempDirectory("mca-mnn-zip-traversal").toFile()
        try {
            val archive = zip("../outside.bin" to "bad")
            val target = File(root, "imported")

            val error = runCatching {
                MnnZipBundleInstaller().install(ByteArrayInputStream(archive), target, archive.size.toLong())
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertFalse(target.exists())
            assertFalse(File(root.parentFile, "outside.bin").exists())
            assertTrue(root.listFiles().orEmpty().none { it.name.contains(".importing-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsCaseInsensitiveDuplicatePaths() {
        val root = Files.createTempDirectory("mca-mnn-zip-duplicate").toFile()
        try {
            val archive = zip(
                "bundle/config.json" to "{}",
                "bundle/CONFIG.JSON" to "{}"
            )

            val error = runCatching {
                MnnZipBundleInstaller().install(
                    ByteArrayInputStream(archive),
                    File(root, "imported"),
                    archive.size.toLong()
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertFalse(File(root, "imported").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun boundedExtractionDoesNotReplaceExistingDestination() {
        val root = Files.createTempDirectory("mca-mnn-zip-limit").toFile()
        try {
            val target = File(root, "imported").apply { mkdirs() }
            File(target, "marker.txt").writeText("old")
            val archive = zip("bundle/large.bin" to "12345")

            val error = runCatching {
                MnnZipBundleInstaller(MnnZipInstallLimits(maxEntryBytes = 4L)).install(
                    ByteArrayInputStream(archive),
                    target,
                    archive.size.toLong()
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertEquals("old", File(target, "marker.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun nestedConfig(): String = """
        {
          "llm_config": "config/llm.json",
          "llm_model": "models/llm.mnn",
          "llm_weight": "models/llm.mnn.weight",
          "tokenizer_file": "tokenizer/tokenizer.mtok",
          "embedding_file": "embeddings/embeddings_bf16.bin"
        }
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}

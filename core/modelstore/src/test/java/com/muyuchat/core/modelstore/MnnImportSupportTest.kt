package com.muyuchat.core.modelstore

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MnnImportSupportTest {
    @get:Rule val folder = TemporaryFolder()

    private fun write(root: File, path: String, value: String) = File(root, path).apply {
        parentFile!!.mkdirs(); writeText(value)
    }
    private fun bundle(prefix: String = ""): File {
        val root = folder.newFolder()
        write(root, "config.json", JSONObject().put("base_dir", prefix.ifBlank { "/export/old/model/" }).toString())
        write(root, prefix + "llm_config.json", "{}")
        write(root, prefix + "llm.mnn", "model")
        write(root, prefix + "llm.mnn.weight", "weights")
        write(root, prefix + "tokenizer.mtok", "tokenizer")
        write(root, prefix + "embeddings_bf16.bin", "embedding")
        return root
    }

    @Test fun preservesUnicodeSpacesAndHarmlessDotSegments() {
        assertEquals("子目录/模型 1.mnn", normalizeMnnImportPath("./子目录/./模型 1.mnn"))
        listOf("../outside", "x/../outside", "C:/outside", "/outside", "x\u0000y").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizeMnnImportPath(it) }
        }
    }

    @Test fun exporterAbsoluteBaseIsRelocatedButRelativeBaseIsPreserved() {
        assertEquals("", mnnImportBasePrefix(JSONObject().put("base_dir", "C:\\export\\model\\")))
        assertEquals("子目录", mnnImportBasePrefix(JSONObject().put("base_dir", "./子目录/")))
        assertThrows(IllegalArgumentException::class.java) { mnnImportBasePrefix(JSONObject().put("base_dir", "/export/../x")) }
    }

    @Test fun readinessAndPreparedConfigAgreeForNestedMtokBundle() {
        val root = bundle("子目录/")
        assertTrue(MnnBundleReadinessAnalyzer.analyze(root).canLoad)
        prepareImportedMnnConfiguration(root, MnnImportMode.FULL)
        val normalized = JSONObject(File(root, "config.json").readText())
        assertFalse(normalized.has("base_dir"))
        assertEquals("子目录/tokenizer.mtok", normalized.getString("tokenizer_file"))
        assertTrue(File(root, "config.json.mca-import-original").isFile)
        assertTrue(MnnBundleReadinessAnalyzer.analyze(root).canLoad)
    }

    @Test fun textOnlyIsExplicitAndDoesNotRelaxChatRequirements() {
        val root = bundle()
        val source = JSONObject(File(root, "config.json").readText()).put("is_visual", true).put("visual_model", "vision/visual.mnn")
        write(root, "config.json", source.toString())
        assertFalse(MnnBundleReadinessAnalyzer.analyze(root).canLoad)
        assertTrue(MnnBundleReadinessAnalyzer.analyze(root, importMode = MnnImportMode.TEXT_ONLY).canLoad)
        prepareImportedMnnConfiguration(root, MnnImportMode.TEXT_ONLY)
        assertTrue(MnnBundleReadinessAnalyzer.analyze(root).canLoad)
        assertEquals("text_only", JSONObject(File(root, "config.json").readText()).getString("mca_import_mode"))
        File(root, "llm.mnn.weight").delete()
        assertFalse(MnnBundleReadinessAnalyzer.analyze(root).canLoad)
    }

    @Test fun flatSelectionMapsUniqueNamesBackToReferencedSubdirectories() {
        fun source(name: String, contents: String) = ModelImportSource(name, name) { ByteArrayInputStream(contents.toByteArray()) }
        val config = """{"llm_config":"./cfg/settings.json","llm_model":"模型/聊天 1.mnn","tokenizer_file":"词典/tokenizer.mtok"}"""
        val sources = listOf(source("config.json", config), source("settings.json", "{}"),
            source("聊天 1.mnn", "model"), source("tokenizer.mtok", "tokenizer"))
        val mapped = mapMnnSelectedComponents(sources, MnnImportMode.FULL)
        assertEquals(listOf("config.json", "cfg/settings.json", "模型/聊天 1.mnn", "词典/tokenizer.mtok"), mapped.map { it.path })
        assertThrows(IllegalArgumentException::class.java) { mapMnnSelectedComponents(sources + sources.last(), MnnImportMode.FULL) }
    }

    @Test fun zipAcceptsDotPrefixAndReportsSpecificMissingComponent() {
        val root = bundle()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> root.listFiles()!!.forEach { file ->
            zip.putNextEntry(ZipEntry("./model/${file.name}")); zip.write(file.readBytes()); zip.closeEntry()
        } }
        val installed = File(folder.newFolder(), "installed")
        MnnZipBundleInstaller().install(ByteArrayInputStream(output.toByteArray()), installed)
        assertTrue(MnnBundleReadinessAnalyzer.analyze(installed).canLoad)
        File(root, "llm.mnn.weight").delete()
        val error = assertThrows(IllegalArgumentException::class.java) { findMnnImportBundleRoot(root, MnnImportMode.FULL) }
        assertTrue(error.message.orEmpty().contains("llm.mnn.weight"))
    }
}

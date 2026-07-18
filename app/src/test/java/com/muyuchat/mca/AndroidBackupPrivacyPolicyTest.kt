package com.muyuchat.mca

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidBackupPrivacyPolicyTest {
    @Test
    fun `cloud backup and device transfer exclude local image history database`() {
        val legacyRules = excludedDatabasePaths(
            sourceFile("app/src/main/res/xml/backup_rules.xml")
        )
        val extractionRules = sourceFile("app/src/main/res/xml/data_extraction_rules.xml")
        val document = parseXml(extractionRules)
        val expectedDatabase = imageHistoryDatabasePaths()
        val expectedFiles = localImageFilePaths()
        val expectedExternalFiles = setOf("image_models")
        val expectedSharedPreferences = installationLocalSharedPreferences()

        assertEquals(expectedDatabase, legacyRules["database"].orEmpty().intersect(expectedDatabase))
        assertEquals(expectedFiles, legacyRules["file"].orEmpty().intersect(expectedFiles))
        assertEquals(
            expectedExternalFiles,
            legacyRules["external"].orEmpty().intersect(expectedExternalFiles)
        )
        assertEquals(
            expectedSharedPreferences,
            legacyRules["sharedpref"].orEmpty().intersect(expectedSharedPreferences)
        )
        listOf("cloud-backup", "device-transfer").forEach { sectionName ->
            val section = document.getElementsByTagName(sectionName).item(0)
                ?: error("Missing $sectionName backup section")
            val actual = buildMap<String, MutableSet<String>> {
                val children = section.childNodes
                for (index in 0 until children.length) {
                    val child = children.item(index)
                    if (child.nodeName == "exclude") {
                        val domain = child.attributes?.getNamedItem("domain")?.nodeValue
                        val path = child.attributes?.getNamedItem("path")?.nodeValue
                        if (domain != null && path != null) {
                            getOrPut(domain) { linkedSetOf() }.add(path)
                        }
                    }
                }
            }
            assertEquals(
                "Missing image-history database exclusions in $sectionName",
                expectedDatabase,
                actual["database"].orEmpty().intersect(expectedDatabase)
            )
            assertEquals(
                "Missing local-image file exclusions in $sectionName",
                expectedFiles,
                actual["file"].orEmpty().intersect(expectedFiles)
            )
            assertEquals(
                "Missing external image-model exclusions in $sectionName",
                expectedExternalFiles,
                actual["external"].orEmpty().intersect(expectedExternalFiles)
            )
            assertEquals(
                "Missing image-parameter preference exclusion in $sectionName",
                expectedSharedPreferences,
                actual["sharedpref"].orEmpty().intersect(expectedSharedPreferences)
            )
        }
    }

    private fun excludedDatabasePaths(xml: String): Map<String, Set<String>> {
        val document = parseXml(xml)
        return buildMap<String, MutableSet<String>> {
            val nodes = document.getElementsByTagName("exclude")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val domain = node.attributes?.getNamedItem("domain")?.nodeValue
                val path = node.attributes?.getNamedItem("path")?.nodeValue
                if (domain != null && path != null) {
                    getOrPut(domain) { linkedSetOf() }.add(path)
                }
            }
        }
    }

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(xml.byteInputStream(Charsets.UTF_8))

    private fun imageHistoryDatabasePaths(): Set<String> = setOf(
        "mca.db",
        "mca.db-wal",
        "mca.db-shm",
        "mca.db-journal"
    )

    private fun localImageFilePaths(): Set<String> = setOf(
        "image_assets",
        "image_execution_journal",
        "image_loras",
        "image_upscalers",
        "image_models",
        "chat_sessions.json"
    )

    private fun installationLocalSharedPreferences(): Set<String> = setOf(
        "mca_api.xml",
        "mca_cloud_api.xml",
        "mca_web_search.xml",
        "mca_web_search_diagnostics.xml",
        "mca_local_api_idempotency_journal.xml",
        "mca_local_image_models.xml",
        "image_loras_v1.xml",
        "image_upscalers_v1.xml",
        "image_upscaler_product_selection_v1.xml",
        "mca_image_generation_ui_parameters.xml"
    )

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            File(root, relativePath).takeIf(File::isFile)?.let {
                return it.readText(Charsets.UTF_8)
            }
            directory = root.parentFile
        }
        error("Unable to locate $relativePath")
    }
}

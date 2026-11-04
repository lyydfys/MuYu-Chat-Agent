package com.muyuchat.feature.dsh.workspace.ui

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the read-only DSH plugin inventory.
 *
 * The official plugin manager owns `profiles/web/package.json`, so the console may only *read* it.
 * These tests fix the parser's behaviour for the shapes a real file has (other top-level fields,
 * devDependencies, escaped strings, wrong value types) and for the failure shapes (missing file,
 * missing key, malformed JSON) — each must be a distinct state, never a silently empty list.
 */
class DshPluginCatalogTest {

    private fun listed(text: String, maxPlugins: Int = DSH_PLUGIN_MAX_ROWS): DshPluginCatalog.Listed {
        val result = parseInstalledPlugins(text, maxPlugins)
        assertTrue("expected Listed, was $result", result is DshPluginCatalog.Listed)
        return result as DshPluginCatalog.Listed
    }

    @Test
    fun `typical package json lists declared dependencies in order`() {
        val catalog = listed(
            """
            {
              "name": "dsh-profile-web",
              "version": "0.1.0",
              "dependencies": {
                "@mca/dsh-terminal-bridge": "1.2.3",
                "dsh-tool-android": "0.4.0"
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("@mca/dsh-terminal-bridge" to "1.2.3", "dsh-tool-android" to "0.4.0"),
            catalog.plugins.map { it.name to it.version }
        )
        assertTrue(catalog.plugins.all { it.declaredInPackageJson })
        assertFalse(catalog.truncated)
    }

    @Test
    fun `unrelated fields including nested output are skipped`() {
        val catalog = listed(
            """
            {
              "scripts": {"build": "tsc && node ./x.js", "nested": {"a": [1, 2, {"b": "}"}]}},
              "config": {"enabled": true, "ratio": 1.5, "none": null},
              "dependencies": {"only-plugin": "2.0.0"},
              "workspaces": ["packages/*"]
            }
            """.trimIndent()
        )

        assertEquals(listOf("only-plugin" to "2.0.0"), catalog.plugins.map { it.name to it.version })
    }

    @Test
    fun `devDependencies are not part of the installed plugin list`() {
        val catalog = listed(
            """{"devDependencies":{"typescript":"5.0.0"},"dependencies":{"plugin":"1.0.0"}}"""
        )
        assertEquals(listOf("plugin"), catalog.plugins.map { it.name })
    }

    @Test
    fun `missing dependencies is its own state`() {
        val result = parseInstalledPlugins("""{"name":"x","version":"1.0.0"}""")
        assertTrue("was $result", result is DshPluginCatalog.MissingDependencies)
        assertEquals(DshPluginCatalog.MissingDependencies, parseInstalledPlugins("{}"))
    }

    @Test
    fun `empty dependencies and blank documents are empty not errors`() {
        assertEquals(DshPluginCatalog.Empty, parseInstalledPlugins("""{"dependencies":{}}"""))
        assertEquals(DshPluginCatalog.Empty, parseInstalledPlugins("   "))
        assertEquals(DshPluginCatalog.Empty, parseInstalledPlugins(""))
    }

    @Test
    fun `malformed json is unreadable with a reason`() {
        val cases = listOf(
            """{"dependencies": {"a": "1.0.0"""",              // unterminated object
            """{"dependencies": {"a" "1.0.0"}}""",            // missing colon
            """{"dependencies": ["a"]}""",                    // dependencies is an array
            """[]""",                                         // root is not an object
            """{"a": 1,}""",                                  // key after comma is missing
            """{"dependencies":{"a":"1.0.0"}} trailing""",    // trailing text
            """{"dependencies": {"a": "1.0.0",}}"""           // trailing comma in deps
        )
        cases.forEach { text ->
            val result = parseInstalledPlugins(text)
            assertTrue("expected Unreadable for <$text>, was $result", result is DshPluginCatalog.Unreadable)
            assertTrue((result as DshPluginCatalog.Unreadable).reason.isNotBlank())
        }
    }

    @Test
    fun `non string versions are listed without inventing a version`() {
        val catalog = listed("""{"dependencies":{"object-version":{"version":"1.0.0"},"number":3}}""")
        assertEquals(listOf("object-version" to "", "number" to ""), catalog.plugins.map { it.name to it.version })
    }

    @Test
    fun `escaped strings are decoded`() {
        val catalog = listed(
            """{"dependencies":{"pkg-\u0041":"1.0.0","quote\"name":"2.0.0","tab\tname":"3.0.0"}}"""
        )
        assertEquals(
            listOf("pkg-A", "quote\"name", "tab\tname"),
            catalog.plugins.map { it.name }
        )
    }

    @Test
    fun `long dependency lists are truncated at the caller ceiling`() {
        val deps = (1..5).joinToString(",") { "\"plugin-$it\": \"1.0.0\"" }
        val catalog = listed("""{"dependencies":{$deps}}""", maxPlugins = 3)

        assertEquals(3, catalog.plugins.size)
        assertTrue("the truncation must be observable", catalog.truncated)
        assertEquals(listOf("plugin-1", "plugin-2", "plugin-3"), catalog.plugins.map { it.name })
    }

    @Test
    fun `inventory reports the home as not created when package json is absent`() {
        val root = Files.createTempDirectory("dsh-plugins-missing").toFile()
        try {
            val inventory = readPluginInventory(root)
            assertEquals(DshPluginCatalog.NotCreatedYet, inventory.catalog)
            assertTrue(inventory.installedDirectories.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `inventory reads the official profile and installed directories`() {
        val root = Files.createTempDirectory("dsh-plugins-present").toFile()
        try {
            val profileDir = File(root, "dsh/home/profiles/web").apply { mkdirs() }
            File(profileDir, "package.json").writeText(
                """{"dependencies":{"declared-plugin":"1.0.0"}}""",
                Charsets.UTF_8
            )
            val nodeModules = File(profileDir, "node_modules").apply { mkdirs() }
            File(nodeModules, "declared-plugin").mkdirs()
            File(nodeModules, ".bin").mkdirs()
            File(nodeModules, "stray-file.txt").writeText("not a plugin")
            val scoped = File(nodeModules, "@mca").apply { mkdirs() }
            File(scoped, "terminal-bridge").mkdirs()

            val inventory = readPluginInventory(root)

            val catalog = inventory.catalog as DshPluginCatalog.Listed
            assertEquals(listOf("declared-plugin"), catalog.plugins.map { it.name })
            assertEquals(
                listOf("@mca/terminal-bridge", ".bin", "declared-plugin"),
                inventory.installedDirectories
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `installed directory listing ignores files and caps the row count`() {
        val root = Files.createTempDirectory("dsh-plugins-dirs").toFile()
        try {
            val profileDir = File(root, "dsh/home/profiles/web").apply { mkdirs() }
            val nodeModules = File(profileDir, "node_modules").apply { mkdirs() }
            repeat(5) { index -> File(nodeModules, "p$index").mkdirs() }
            File(nodeModules, "file.js").writeText("x")

            val names = listInstalledPluginDirectories(root)

            assertEquals(listOf("p0", "p1", "p2", "p3", "p4"), names)
        } finally {
            root.deleteRecursively()
        }
    }
}

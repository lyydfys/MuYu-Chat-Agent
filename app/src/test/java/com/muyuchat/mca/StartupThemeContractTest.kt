package com.muyuchat.mca

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class StartupThemeContractTest {
    @Test
    fun `day and night startup themes match McaTheme backgrounds`() {
        val root = projectRoot()
        val light = theme(
            root = root,
            relativePath = "app/src/main/res/values/styles.xml"
        )
        val night = theme(
            root = root,
            relativePath = "app/src/main/res/values-night/styles.xml"
        )

        assertTheme(
            theme = light,
            expectedParent = "android:style/Theme.Material.Light.NoActionBar",
            expectedBackground = "#F8F9FA",
            expectedLightStatusBar = "true"
        )
        assertTheme(
            theme = night,
            expectedParent = "android:style/Theme.Material.NoActionBar",
            expectedBackground = "#121212",
            expectedLightStatusBar = "false"
        )
        assertMcaThemeBackground(root, "LightColors", "#F8F9FA")
        assertMcaThemeBackground(root, "DarkColors", "#121212")
        listOf(light, night).forEach(::assertNoSplashBranding)
    }

    private fun assertTheme(
        theme: StartupTheme,
        expectedParent: String,
        expectedBackground: String,
        expectedLightStatusBar: String
    ) {
        assertEquals(
            "${theme.relativePath} must keep the production theme name",
            EXPECTED_THEME_NAME,
            theme.name
        )
        assertEquals("${theme.relativePath} parent", expectedParent, theme.parent)
        assertEquals(
            "${theme.relativePath} fontFamily",
            "sans",
            theme.items["android:fontFamily"]
        )
        assertEquals(
            "${theme.relativePath} windowBackground",
            expectedBackground,
            theme.items["android:windowBackground"]
        )
        assertEquals(
            "${theme.relativePath} statusBarColor",
            expectedBackground,
            theme.items["android:statusBarColor"]
        )
        assertEquals(
            "${theme.relativePath} navigationBarColor",
            expectedBackground,
            theme.items["android:navigationBarColor"]
        )
        assertEquals(
            "${theme.relativePath} windowLightStatusBar",
            expectedLightStatusBar,
            theme.items["android:windowLightStatusBar"]
        )
    }

    private fun assertNoSplashBranding(theme: StartupTheme) {
        SPLASH_BRANDING_ITEMS.forEach { item ->
            assertFalse(
                "${theme.relativePath} must keep startup background-only, without $item",
                theme.items.containsKey(item)
            )
        }
    }

    private fun assertMcaThemeBackground(
        root: File,
        schemeName: String,
        expectedBackground: String
    ) {
        val relativePath =
            "core/designsystem/src/main/java/com/muyuchat/core/designsystem/McaTheme.kt"
        val source = File(root, relativePath).readText(Charsets.UTF_8)
        val schemeBody = Regex(
            """private\s+val\s+$schemeName\s*=\s*\w+ColorScheme\s*\((.*?)\n\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(source)?.groupValues?.get(1)
            ?: error("Unable to locate $schemeName in $relativePath")
        val background = Regex(
            """\bbackground\s*=\s*Color\(\s*0xFF([0-9A-Fa-f]{6})\s*\)"""
        ).find(schemeBody)?.groupValues?.get(1)
            ?: error("$schemeName must declare an explicit background color")
        assertEquals(
            "$schemeName background must remain the startup theme source of truth",
            expectedBackground.removePrefix("#").uppercase(),
            background.uppercase()
        )
    }

    private fun theme(root: File, relativePath: String): StartupTheme {
        val file = File(root, relativePath)
        assertTrue("Missing $relativePath", file.isFile)
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val styleNodes = document.getElementsByTagName("style")
        val style = (0 until styleNodes.length)
            .map { styleNodes.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == EXPECTED_THEME_NAME }
            ?: error("$relativePath must define $EXPECTED_THEME_NAME")
        val itemNodes = style.getElementsByTagName("item")
        val items = buildMap {
            for (index in 0 until itemNodes.length) {
                val item = itemNodes.item(index) as Element
                put(item.getAttribute("name"), item.textContent.trim())
            }
        }
        return StartupTheme(
            relativePath = relativePath,
            name = style.getAttribute("name"),
            parent = style.getAttribute("parent"),
            items = items
        )
    }

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private data class StartupTheme(
        val relativePath: String,
        val name: String,
        val parent: String,
        val items: Map<String, String>
    )

    private companion object {
        const val EXPECTED_THEME_NAME = "Theme.MuYuChatAgent"
        val SPLASH_BRANDING_ITEMS = setOf(
            "android:windowSplashScreenAnimatedIcon",
            "android:windowSplashScreenIconBackgroundColor",
            "android:windowSplashScreenBrandingImage"
        )
    }
}
package com.muyuchat.mca.appearance

import com.muyuchat.core.designsystem.appearance.McaAppIconVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the launcher-icon choice that can be proven without a `PackageManager`.
 *
 * The component toggling itself needs a real package manager and a launcher, so it is verified on device;
 * what is pinned here is everything that would otherwise fail silently: the preference codec, the
 * fallback for a variant this build does not ship, the alias names the manifest must actually declare,
 * and the "three variants, own file" budget.
 */
class AppearanceAppIconTest {

    @Test
    fun `图标偏好使用独立文件与独立键`() {
        assertEquals("mca_appearance_icon", AppearanceAppIconPreferences.PREFERENCES_NAME)
        assertFalse(AppearanceAppIconPreferences.PREFERENCES_NAME.contains("chat"))
        assertEquals("appearance_icon_variant", AppearanceAppIconPreferences.KEY_ICON_VARIANT)
        assertTrue(
            "图标键不能与明暗键相同",
            AppearanceAppIconPreferences.KEY_ICON_VARIANT != AppearancePreferences.KEY_THEME_MODE
        )
        assertTrue(
            "图标文件不能与壁纸文件相同",
            AppearanceAppIconPreferences.PREFERENCES_NAME != AppearanceWallpaperPreferences.PREFERENCES_NAME
        )
        assertTrue(
            "图标文件不能与启动图文件相同",
            AppearanceAppIconPreferences.PREFERENCES_NAME != AppearanceLaunchImagePreferences.PREFERENCES_NAME
        )
    }

    @Test
    fun `图标变体可以保存并在重启后读回`() {
        val store = MemoryAppearanceStore()
        val preferences = AppearanceAppIconPreferences(store)

        assertNull("未设置时没有变体", preferences.loadVariant())

        preferences.saveVariant(McaAppIconVariant.ALT2)

        assertEquals(McaAppIconVariant.ALT2, preferences.loadVariant())
        assertEquals(
            "只写图标键",
            setOf(AppearanceAppIconPreferences.KEY_ICON_VARIANT),
            store.values.keys
        )
        assertEquals(
            "写入的是稳定 wireName，不是组件名",
            "alt2",
            store.values[AppearanceAppIconPreferences.KEY_ICON_VARIANT]
        )
        // Models the next launch.
        assertEquals(McaAppIconVariant.ALT2, AppearanceAppIconPreferences(store).loadVariant())
    }

    @Test
    fun `未知或损坏的图标持久值不会被当成有效变体`() {
        // The controller resolves null to Default; the codec must never invent a variant, or a
        // hand-edited file could make the row claim an icon the manifest does not declare.
        listOf("purple", "", "   ", "alt3", "default|alt1").forEach { dirty ->
            val preferences = AppearanceAppIconPreferences(
                MemoryAppearanceStore(mapOf(AppearanceAppIconPreferences.KEY_ICON_VARIANT to dirty))
            )
            assertNull("损坏值 [$dirty] 必须解析为 null", preferences.loadVariant())
        }
    }

    @Test
    fun `变体的 wireName 唯一且小写`() {
        val names = McaAppIconVariant.entries.map { it.wireName }
        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            assertEquals(name, name.lowercase())
            assertEquals(name, name.trim())
        }
    }

    @Test
    fun `恰好三个变体且默认变体可用`() {
        // The lead's budget: 3 variants, not N. Each extra variant is another enabled/disabled
        // component the launcher re-reads plus another adaptive-icon resource.
        assertEquals(3, McaAppIconVariant.entries.size)
        assertEquals(McaAppIconVariant.DEFAULT, McaAppIconVariant.Default)
        McaAppIconVariant.entries.forEach { variant ->
            assertTrue("每个变体必须有中文标签，得到 ${variant.label}", variant.label.isNotBlank())
            assertTrue("每个变体必须有别名组件，得到 $variant", !variant.aliasClassName.isNullOrBlank())
        }
    }

    @Test
    fun `别名组件名与清单声明一致`() {
        // These strings are the contract with AndroidManifest.xml: if an alias is renamed there and not
        // here, setComponentEnabledSetting throws and the user keeps a stale icon.
        assertEquals("com.muyuchat.mca.MainActivityIconDefault", McaAppIconVariant.DEFAULT.aliasClassName)
        assertEquals("com.muyuchat.mca.MainActivityIconAlt1", McaAppIconVariant.ALT1.aliasClassName)
        assertEquals("com.muyuchat.mca.MainActivityIconAlt2", McaAppIconVariant.ALT2.aliasClassName)
    }

    @Test
    fun `启动图偏好使用独立文件与独立键`() {
        assertEquals("mca_appearance_launch_image", AppearanceLaunchImagePreferences.PREFERENCES_NAME)
        assertEquals("appearance_launch_image_file", AppearanceLaunchImagePreferences.KEY_LAUNCH_IMAGE_FILE)
        assertTrue(
            "启动图不能与壁纸共用文件",
            AppearanceLaunchImagePreferences.PREFERENCES_NAME != AppearanceWallpaperPreferences.PREFERENCES_NAME
        )
        assertTrue(
            "启动图必须有自己的私有目录，否则清除壁纸会连带删除它",
            AppearanceLaunchImages.DIRECTORY != AppearanceWallpaperStore.DIRECTORY
        )
        assertEquals(
            "启动图复用壁纸的解码上限，不另立一套",
            AppearanceWallpaperStore.MAX_DIMENSION,
            AppearanceLaunchImages.MAX_DIMENSION
        )
    }

    @Test
    fun `启动图文件名可以保存并清除`() {
        val store = MemoryAppearanceStore()
        val preferences = AppearanceLaunchImagePreferences(store)

        assertNull(preferences.loadFileName())
        preferences.saveFileName("launch.jpg")
        assertEquals("launch.jpg", preferences.loadFileName())
        assertEquals(
            setOf(AppearanceLaunchImagePreferences.KEY_LAUNCH_IMAGE_FILE),
            store.values.keys
        )

        preferences.saveFileName(null)
        assertNull("清除必须是删除键", store.values[AppearanceLaunchImagePreferences.KEY_LAUNCH_IMAGE_FILE])
    }

    @Test
    fun `空白启动图文件名视为未设置`() {
        val preferences = AppearanceLaunchImagePreferences(
            MemoryAppearanceStore(
                mapOf(AppearanceLaunchImagePreferences.KEY_LAUNCH_IMAGE_FILE to "   ")
            )
        )
        assertNull(preferences.loadFileName())
    }
}

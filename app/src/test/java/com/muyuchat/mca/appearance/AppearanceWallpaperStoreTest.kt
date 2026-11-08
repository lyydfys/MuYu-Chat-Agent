package com.muyuchat.mca.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory and persistence contract of the global wallpaper.
 *
 * The decode/file paths need a real `Context`, an `ImageDecoder` and a picker grant, so this file pins
 * what can be proven on a plain JVM: the subsample arithmetic that bounds every decode, and the
 * preference discipline (own file, own key, never the picker Uri). The rendering path is covered by the
 * design-system component and the real device check, not here.
 */
class AppearanceWallpaperStoreTest {

    @Test
    fun `采样倍数把长边压到上限以内`() {
        // A 1080p phone photo is 1080x2400: the *long* edge is the tall one, so even a modern wallpaper
        // is halved to 540x1200 for the decode pass and then kept at 1080x2400 by the final scale, which
        // is still sharper than the screen. This is the common case, not an edge case.
        assertEquals(2, sampleSizeFor(width = 1080, height = 2400, maxDimension = 2048))
        // A landscape tablet-ish image fits without subsampling.
        assertEquals(1, sampleSizeFor(width = 1920, height = 1080, maxDimension = 2048))
        // A 4000px photo is halved once: 2000 fits, 4000 does not.
        assertEquals(2, sampleSizeFor(width = 4000, height = 3000, maxDimension = 2048))
        // An 8000x6000 camera image becomes 2000x1500; without subsampling that decode alone would be
        // 48 MiB of ARGB_8888 before the final scale ever runs.
        assertEquals(4, sampleSizeFor(width = 8000, height = 6000, maxDimension = 2048))
        // Absurd but legal input still terminates at a power of two.
        assertEquals(8, sampleSizeFor(width = 16384, height = 16384, maxDimension = 2048))
    }

    @Test
    fun `采样结果始终不超过上限`() {
        val sources = listOf(
            1 to 1, 1080 to 2400, 2048 to 2048, 2049 to 2048, 3000 to 4000,
            4096 to 2160, 6000 to 4000, 12000 to 9000, 32768 to 24576
        )
        sources.forEach { (width, height) ->
            val sample = sampleSizeFor(width, height, AppearanceWallpaperStore.MAX_DIMENSION)
            assertTrue("sample 必须是 2 的幂，得到 $sample", sample > 0 && sample and (sample - 1) == 0)
            assertTrue(
                "解采样后 ${width}x$height / $sample 不能超过上限",
                width / sample <= AppearanceWallpaperStore.MAX_DIMENSION &&
                    height / sample <= AppearanceWallpaperStore.MAX_DIMENSION
            )
        }
    }

    @Test
    fun `非法的尺寸或上限返回一倍`() {
        assertEquals(1, sampleSizeFor(width = 0, height = 100, maxDimension = 2048))
        assertEquals(1, sampleSizeFor(width = 100, height = 0, maxDimension = 2048))
        assertEquals(1, sampleSizeFor(width = 100, height = 100, maxDimension = 0))
        assertEquals(1, sampleSizeFor(width = -5, height = -5, maxDimension = 2048))
    }

    @Test
    fun `上限本身必须能容纳一屏壁纸`() {
        // 2048 longest edge covers every phone density in use; the worst-case square decode is bounded
        // to 2048*2048*4 bytes = 16 MiB of ARGB_8888, and the RGB_565 preview path halves that.
        assertEquals(2048, AppearanceWallpaperStore.MAX_DIMENSION)
        assertTrue(AppearanceWallpaperStore.MAX_DIMENSION >= 1440)
        assertEquals(32L * 1024L * 1024L, AppearanceWallpaperStore.MAX_SOURCE_BYTES)
        assertTrue(AppearanceWallpaperStore.MAX_SOURCE_PIXELS >= 64L * 1024L * 1024L)
    }

    @Test
    fun `壁纸偏好使用独立文件与独立键`() {
        // A wallpaper is a different edit cadence from the colour mode, and neither belongs in the
        // per-conversation chat appearance record.
        assertEquals("mca_appearance_wallpaper", AppearanceWallpaperPreferences.PREFERENCES_NAME)
        assertFalse(AppearanceWallpaperPreferences.PREFERENCES_NAME.contains("chat"))
        assertEquals(
            "wallpaper key must not collide with the theme key",
            false,
            AppearanceWallpaperPreferences.KEY_WALLPAPER_FILE == AppearancePreferences.KEY_THEME_MODE
        )
        assertEquals("appearance_wallpaper_file", AppearanceWallpaperPreferences.KEY_WALLPAPER_FILE)
    }

    @Test
    fun `壁纸文件名可以保存并在重启后读回`() {
        val store = MemoryAppearanceStore()
        val preferences = AppearanceWallpaperPreferences(store)

        assertNull("未设置时没有文件名", preferences.loadFileName())

        preferences.saveFileName("wallpaper-1.jpg")

        assertEquals("wallpaper-1.jpg", preferences.loadFileName())
        assertEquals(
            "只写壁纸键，不污染其它键",
            setOf(AppearanceWallpaperPreferences.KEY_WALLPAPER_FILE),
            store.values.keys
        )
        // A new instance over the same store models the next launch.
        assertEquals("wallpaper-1.jpg", AppearanceWallpaperPreferences(store).loadFileName())
    }

    @Test
    fun `清除壁纸会删除键而不是写入空串`() {
        val store = MemoryAppearanceStore()
        val preferences = AppearanceWallpaperPreferences(store)
        preferences.saveFileName("wallpaper-1.png")

        preferences.saveFileName(null)

        assertNull("清除必须是删除键", store.values[AppearanceWallpaperPreferences.KEY_WALLPAPER_FILE])
        assertNull(preferences.loadFileName())
    }

    @Test
    fun `空白文件名不会被当成壁纸`() {
        val preferences = AppearanceWallpaperPreferences(
            MemoryAppearanceStore(mapOf(AppearanceWallpaperPreferences.KEY_WALLPAPER_FILE to "   "))
        )

        assertNull("空白值必须视为未设置，否则启动时会一直尝试解码", preferences.loadFileName())
    }
}

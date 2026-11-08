package com.muyuchat.mca.appearance

import com.muyuchat.core.designsystem.appearance.McaAppearanceThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of [AppearanceController] over the shared in-memory store.
 *
 * The load-bearing promises are:
 *  1. the published flow changes as soon as a choice is selected, before any repaint asks for it;
 *  2. a corrupted stored value can never lock the app out of following the system;
 *  3. a failed write keeps the choice live for this process and reports the failure instead of
 *     throwing into the Compose click handler;
 *  4. the flow's first value is already the persisted mode, so a first frame can read `themeMode.value`
 *     without waiting for a collector;
 *  5. the app enum and the design-system enum round-trip without losing a value.
 *
 * Everything here is plain JVM: the controller takes [AppearancePreferences] and never sees a Context.
 */
class AppearanceControllerTest {

    private fun controller(store: MemoryAppearanceStore): AppearanceController =
        AppearanceController(AppearancePreferences(store))

    @Test
    fun `选择后立即反映在 StateFlow`() {
        val store = MemoryAppearanceStore()
        val controller = controller(store)
        assertEquals(AppearanceThemeMode.SYSTEM, controller.themeMode.value)

        val outcome = controller.select(AppearanceThemeMode.DARK)

        assertEquals(
            "选择必须在写盘返回后立刻出现在 StateFlow 上",
            AppearanceThemeMode.DARK,
            controller.themeMode.value
        )
        assertEquals(AppearanceSelectionOutcome.Persisted, outcome)
        assertEquals("dark", store.values[AppearancePreferences.KEY_THEME_MODE])

        AppearanceThemeMode.entries.forEach { mode ->
            controller.select(mode)
            assertEquals("选择 $mode 后 StateFlow 必须是它", mode, controller.themeMode.value)
        }
    }

    @Test
    fun `未知或损坏的持久值回退到跟随系统`() {
        // A hand-edited, truncated or future-build value must not pin the app to a scheme this build
        // cannot paint; SYSTEM is the behaviour of every build before this preference existed.
        listOf("紫色", "", "   ", "darkk", "1", "system-light", "\u0000").forEach { dirty ->
            val store = MemoryAppearanceStore(
                mapOf(AppearancePreferences.KEY_THEME_MODE to dirty)
            )
            val controller = controller(store)

            assertEquals("损坏值 [$dirty] 必须回退到跟随系统", AppearanceThemeMode.SYSTEM, controller.themeMode.value)
            assertEquals(AppearanceThemeMode.SYSTEM, controller.themeMode.value)
        }
    }

    @Test
    fun `写盘失败时内存值仍生效且记录错误`() {
        val store = MemoryAppearanceStore(
            mapOf(AppearancePreferences.KEY_THEME_MODE to AppearanceThemeMode.LIGHT.wireName)
        )
        store.writeFailure = IllegalStateException("磁盘已满")
        val controller = controller(store)
        assertEquals(AppearanceThemeMode.LIGHT, controller.themeMode.value)

        val outcome = controller.select(AppearanceThemeMode.DARK)

        assertEquals(
            "写盘失败不能让用户的选择回滚",
            AppearanceThemeMode.DARK,
            controller.themeMode.value
        )
        assertEquals(AppearanceThemeMode.DARK, controller.themeMode.value)

        val failure = outcome as? AppearanceSelectionOutcome.KeptInMemoryOnly
        assertNotNull("写盘失败必须回报失败结果，不能静默成功", failure)
        requireNotNull(failure)
        assertEquals(AppearanceThemeMode.DARK, failure.mode)
        assertTrue("用户可见文案必须存在", failure.userMessage.isNotBlank())
        assertTrue(
            "诊断信息必须保留原始原因，失败才能定位",
            failure.diagnostic?.contains("磁盘已满") == true
        )
        assertEquals(
            "失败的写不能改动已经存下来的值",
            AppearanceThemeMode.LIGHT.wireName,
            store.values[AppearancePreferences.KEY_THEME_MODE]
        )
        assertEquals(0, store.writeCount)
    }

    @Test
    fun `写盘失败后再次选择成功仍会持久化`() {
        // The failure must not poison the controller or the store port: the next accepted write wins.
        val store = MemoryAppearanceStore()
        store.writeFailure = IllegalStateException("只读")
        val controller = controller(store)
        assertTrue(controller.select(AppearanceThemeMode.DARK) is AppearanceSelectionOutcome.KeptInMemoryOnly)

        store.writeFailure = null

        assertEquals(AppearanceSelectionOutcome.Persisted, controller.select(AppearanceThemeMode.LIGHT))
        assertEquals(AppearanceThemeMode.LIGHT, controller.themeMode.value)
        assertEquals("恢复后写盘必须落到同一实例的存储上", "light", store.values[AppearancePreferences.KEY_THEME_MODE])
    }

    @Test
    fun `app 枚举与 designsystem 枚举双向映射无损`() {
        AppearanceThemeMode.entries.forEach { mode ->
            assertEquals("app -> designsystem -> app 必须还原 $mode", mode, mode.toMcaChoice().toThemeMode())
            assertEquals(
                "两侧的 wireName 必须表示同一个模式",
                mode.wireName,
                mode.toMcaChoice().wireName
            )
        }
        McaAppearanceThemeChoice.entries.forEach { choice ->
            assertEquals(
                "designsystem -> app -> designsystem 必须还原 $choice",
                choice,
                choice.toThemeMode().toMcaChoice()
            )
        }
    }

    @Test
    fun `初值直接来自 loadThemeMode 且不依赖任何收集者`() {
        val cases = listOf(
            emptyMap<String, String>() to AppearanceThemeMode.SYSTEM,
            mapOf(AppearancePreferences.KEY_THEME_MODE to "light") to AppearanceThemeMode.LIGHT,
            mapOf(AppearancePreferences.KEY_THEME_MODE to "dark") to AppearanceThemeMode.DARK
        )

        cases.forEach { (stored, expected) ->
            val controller = controller(MemoryAppearanceStore(stored))

            // No collector is started anywhere in this test: the value a first frame would read is
            // already there, which is what removed the need for a separate synchronous accessor.
            assertEquals("初值必须来自 loadThemeMode()", expected, controller.themeMode.value)
        }
    }

    @Test
    fun `选择会持久化并在新实例中读回`() {
        val store = MemoryAppearanceStore()
        controller(store).select(AppearanceThemeMode.DARK)

        // A new controller over the same store models the next process launch.
        val relaunched = controller(store)

        assertEquals(AppearanceThemeMode.DARK, relaunched.themeMode.value)
        assertEquals(AppearanceThemeMode.DARK, relaunched.themeMode.value)
    }

    @Test
    fun `连续快速选择以最后一次为准`() {
        val store = MemoryAppearanceStore()
        val controller = controller(store)
        val taps = listOf(
            AppearanceThemeMode.DARK,
            AppearanceThemeMode.LIGHT,
            AppearanceThemeMode.DARK,
            AppearanceThemeMode.SYSTEM,
            AppearanceThemeMode.DARK
        )

        taps.forEach { controller.select(it) }

        assertEquals(AppearanceThemeMode.DARK, controller.themeMode.value)
        assertEquals(
            "磁盘上的值必须是最后一次选择，不能是被超越的中间写",
            AppearanceThemeMode.DARK.wireName,
            store.values[AppearancePreferences.KEY_THEME_MODE]
        )
        assertEquals("每一次选择都应被写入一次", taps.size, store.writeCount)
    }
}

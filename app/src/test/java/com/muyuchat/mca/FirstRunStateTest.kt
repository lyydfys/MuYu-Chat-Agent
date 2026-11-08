package com.muyuchat.mca

import com.muyuchat.core.designsystem.appearance.McaAppearanceThemeChoice
import com.muyuchat.core.designsystem.onboarding.McaOnboardingPathChoice
import com.muyuchat.core.designsystem.onboarding.McaOnboardingStep
import com.muyuchat.mca.appearance.AppearanceController
import com.muyuchat.mca.appearance.AppearancePreferenceStore
import com.muyuchat.mca.appearance.AppearancePreferences
import com.muyuchat.mca.appearance.AppearanceThemeMode
import com.muyuchat.mca.appearance.MemoryAppearanceStore
import com.muyuchat.mca.onboarding.FailingPreferenceStore
import com.muyuchat.mca.onboarding.OnboardingFlowController
import com.muyuchat.mca.onboarding.OnboardingFlowUiState
import com.muyuchat.mca.onboarding.OnboardingPreferenceStore
import com.muyuchat.mca.onboarding.OnboardingPreferences
import com.muyuchat.mca.onboarding.OnboardingRefusal
import com.muyuchat.mca.onboarding.OnboardingWriteTarget
import com.muyuchat.mca.onboarding.RecordingOnboardingStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the composition-root state that gates the first frame.
 *
 * [FirstRunState] is the only thing standing between the persisted preferences and the first screen, so
 * these tests drive it the way `MainActivity` does — one call per tap, each callback replacing the whole
 * projection — and assert on the projection *and* on the keys that reached the store. The two owners it
 * wraps are already covered by their own tests; what is pinned here is the wiring between them: which
 * callback maps to which controller method, that the guide's answers survive, and that the class itself
 * stays constructible without Android.
 */
class FirstRunStateTest {

    private fun state(
        onboardingStore: OnboardingPreferenceStore = RecordingOnboardingStore(),
        appearanceStore: AppearancePreferenceStore = MemoryAppearanceStore()
    ) = FirstRunState(
        appearance = AppearanceController(AppearancePreferences(appearanceStore)),
        onboarding = OnboardingFlowController(OnboardingPreferences(onboardingStore))
    )

    /** Walks welcome -> path -> model the way the guide does. */
    private fun FirstRunState.reachModelPage(choice: McaOnboardingPathChoice = McaOnboardingPathChoice.EXPERIENCE) {
        start()
        selectPath(choice)
        continuePath()
    }

    /** Walks the whole flow and stops on the done page (reached, not yet completed). */
    private fun FirstRunState.reachDonePage(choice: McaOnboardingPathChoice = McaOnboardingPathChoice.EXPERIENCE) {
        reachModelPage(choice)
        skipModel()
    }

    @Test
    fun `全新安装首次读取停在欢迎页`() {
        val onboarding = RecordingOnboardingStore()
        val appearance = MemoryAppearanceStore()

        val firstRun = state(onboardingStore = onboarding, appearanceStore = appearance)

        assertEquals(McaOnboardingStep.WELCOME, firstRun.flow.step)
        assertFalse(firstRun.flow.completed)
        assertNull(firstRun.flow.path)
        assertFalse(firstRun.flow.skippedModel)
        assertTrue(firstRun.flow.canAdvance)
        assertEquals(1, firstRun.flow.stepIndex)
        assertEquals(4, firstRun.flow.stepCount)
        assertNull(firstRun.flow.rejection)
        assertNull(firstRun.flow.writeFailure)
        assertEquals(McaAppearanceThemeChoice.SYSTEM, firstRun.appearanceChoice)
        assertEquals("构造首屏状态不能写引导偏好", 0, onboarding.writeCount)
        assertEquals("构造首屏状态不能写外观偏好", 0, appearance.writeCount)
    }

    @Test
    fun `走完四页后 flow completed 为真`() {
        val firstRun = state()
        assertEquals(McaOnboardingStep.WELCOME, firstRun.flow.step)

        firstRun.start()
        assertEquals(McaOnboardingStep.PATH, firstRun.flow.step)

        firstRun.selectPath(McaOnboardingPathChoice.EXPERIENCE)
        assertEquals(McaOnboardingStep.PATH, firstRun.flow.step)

        firstRun.continuePath()
        assertEquals(McaOnboardingStep.MODEL, firstRun.flow.step)

        firstRun.skipModel()
        assertEquals(McaOnboardingStep.DONE, firstRun.flow.step)
        assertFalse("到达完成页不等于已经完成", firstRun.flow.completed)
        assertFalse(firstRun.flow.canAdvance)

        firstRun.enter()
        assertTrue("进入应用必须调用控制器 complete", firstRun.flow.completed)
        assertEquals(McaOnboardingStep.DONE, firstRun.flow.step)
        assertFalse(firstRun.flow.canAdvance)
    }

    @Test
    fun `未选路径时继续不会前进且产生拒绝`() {
        val onboarding = RecordingOnboardingStore()
        val firstRun = state(onboardingStore = onboarding)
        firstRun.start()
        val writesBefore = onboarding.writeCount

        firstRun.continuePath()

        assertEquals("拒绝不能挪动页面", McaOnboardingStep.PATH, firstRun.flow.step)
        assertEquals(OnboardingRefusal.PATH_NOT_SELECTED, firstRun.flow.rejection)
        assertFalse(firstRun.flow.canAdvance)
        assertNull("拒绝不会同时报告写盘失败", firstRun.flow.writeFailure)
        assertEquals("拒绝不能写盘", writesBefore, onboarding.writeCount)
    }

    @Test
    fun `选择路径后可继续`() {
        val firstRun = state()
        firstRun.start()

        firstRun.selectPath(McaOnboardingPathChoice.PRODUCTION)

        assertEquals("选择不等于翻页", McaOnboardingStep.PATH, firstRun.flow.step)
        assertEquals(McaOnboardingPathChoice.PRODUCTION, firstRun.flow.path)
        assertNull(firstRun.flow.rejection)
        assertTrue(firstRun.flow.canAdvance)

        firstRun.continuePath()
        assertEquals(McaOnboardingStep.MODEL, firstRun.flow.step)
        assertEquals(McaOnboardingPathChoice.PRODUCTION, firstRun.flow.path)
    }

    @Test
    fun `跳过模型后完成页标记 skippedModel`() {
        val onboarding = RecordingOnboardingStore()
        val firstRun = state(onboardingStore = onboarding)
        firstRun.reachModelPage()

        firstRun.skipModel()

        assertEquals(McaOnboardingStep.DONE, firstRun.flow.step)
        assertTrue(firstRun.flow.skippedModel)
        assertFalse(firstRun.flow.completed)
        assertNull(firstRun.flow.rejection)
        assertTrue(
            "跳过的答案必须落盘，重启后的完成页才能说清没有模型",
            onboarding.flags[OnboardingPreferences.KEY_SKIPPED_MODEL] ?: false
        )
    }

    @Test
    fun `replay 重置后重新停在欢迎页`() {
        val onboarding = RecordingOnboardingStore()
        val appearance = MemoryAppearanceStore()
        val firstRun = state(onboardingStore = onboarding, appearanceStore = appearance)
        firstRun.reachDonePage()
        firstRun.enter()
        assertTrue(firstRun.flow.completed)
        firstRun.selectAppearance(McaAppearanceThemeChoice.DARK)
        val appearanceValues = appearance.values.toMap()
        val appearanceWrites = appearance.writeCount

        firstRun.replay()

        assertEquals(McaOnboardingStep.WELCOME, firstRun.flow.step)
        assertFalse(firstRun.flow.completed)
        assertNull(firstRun.flow.path)
        assertFalse(firstRun.flow.skippedModel)
        assertTrue(firstRun.flow.canAdvance)
        assertEquals(1, firstRun.flow.stepIndex)
        assertNull(firstRun.flow.rejection)

        // The four onboarding keys are the only thing reset may touch: no chat, model or appearance
        // data is written, and nothing else in the preferences is disturbed.
        assertFalse(onboarding.flags[OnboardingPreferences.KEY_COMPLETED] ?: false)
        assertNull(onboarding.values[OnboardingPreferences.KEY_STEP])
        assertNull(onboarding.values[OnboardingPreferences.KEY_PATH])
        assertFalse(onboarding.flags[OnboardingPreferences.KEY_SKIPPED_MODEL] ?: false)
        val touched = onboarding.values.keys + onboarding.flags.keys
        assertTrue(
            "replay 只能写引导自己的四个键，实际写了 $touched",
            touched.all { it in ONBOARDING_KEYS }
        )
        assertEquals("外观偏好不能被重看引导影响", appearanceValues, appearance.values)
        assertEquals(appearanceWrites, appearance.writeCount)
        assertEquals(McaAppearanceThemeChoice.DARK, firstRun.appearanceChoice)
    }

    @Test
    fun `flow 只由控制器返回值驱动`() {
        // Every callback must leave the projection equal to what the controller itself would project
        // from what is now persisted. A hand-built state (or one that drifts from disk) fails here.
        val onboarding = RecordingOnboardingStore()
        val controller = OnboardingFlowController(OnboardingPreferences(onboarding))
        val firstRun = FirstRunState(
            appearance = AppearanceController(AppearancePreferences(MemoryAppearanceStore())),
            onboarding = controller
        )

        fun assertMirrorsPersistence(label: String) {
            assertEquals(
                "$label 之后 flow 与持久状态不一致",
                controller.load().business(),
                firstRun.flow.business()
            )
        }

        assertMirrorsPersistence("首次读取")
        firstRun.start(); assertMirrorsPersistence("前进")
        firstRun.selectPath(McaOnboardingPathChoice.EXPERIENCE); assertMirrorsPersistence("选择路径")
        firstRun.continuePath(); assertMirrorsPersistence("进入模型页")
        firstRun.skipModel(); assertMirrorsPersistence("跳过模型")
        firstRun.enter(); assertMirrorsPersistence("进入应用")
        firstRun.replay(); assertMirrorsPersistence("重看引导")
    }

    @Test
    fun `写盘失败时 flow 仍前进但 writeFailure 非空`() {
        val failing = FailingPreferenceStore()
        val firstRun = state(onboardingStore = failing)

        firstRun.start()

        assertEquals("写盘失败不能让流程卡死", McaOnboardingStep.PATH, firstRun.flow.step)
        assertNull("合法操作不算拒绝", firstRun.flow.rejection)
        val failure = requireNotNull(firstRun.flow.writeFailure) { "写盘失败必须可判定" }
        assertEquals(OnboardingWriteTarget.STEP, failure.target)
    }

    @Test
    fun `拒绝与写盘失败不会同时出现`() {
        // The guide renders one notice line and prefers the refusal, so the two channels must never
        // compete: the controller returns before writing on every refusal path. A broken store probes
        // that from the other side, because every legal tap reports a write failure too.
        val firstRun = state(onboardingStore = FailingPreferenceStore())

        fun assertSingleChannel(label: String) {
            val flow = firstRun.flow
            assertFalse(
                "$label 同时报告了拒绝（${flow.rejection}）与写盘失败（${flow.writeFailure?.target}）",
                flow.rejection != null && flow.writeFailure != null
            )
        }

        firstRun.back(); assertSingleChannel("欢迎页后退")
        firstRun.continuePath(); assertSingleChannel("未选路径继续")
        firstRun.start(); assertSingleChannel("前进")
        firstRun.selectPath(McaOnboardingPathChoice.EXPERIENCE); assertSingleChannel("选择路径")
        firstRun.continuePath(); assertSingleChannel("进入模型页")
        firstRun.skipModel(); assertSingleChannel("跳过模型")
        firstRun.enter(); assertSingleChannel("进入应用")
        firstRun.replay(); assertSingleChannel("重看引导")
    }

    @Test
    fun `darkTheme 真值表`() {
        val firstRun = state()

        // SYSTEM (the default): the platform decides.
        assertTrue("跟随系统：系统深色时为深色", firstRun.darkTheme(systemDark = true))
        assertFalse("跟随系统：系统浅色时为浅色", firstRun.darkTheme(systemDark = false))

        firstRun.selectAppearance(McaAppearanceThemeChoice.LIGHT)
        assertFalse("固定浅色忽略系统深色", firstRun.darkTheme(systemDark = true))
        assertFalse("固定浅色在系统浅色下也是浅色", firstRun.darkTheme(systemDark = false))

        firstRun.selectAppearance(McaAppearanceThemeChoice.DARK)
        assertTrue("固定深色忽略系统浅色", firstRun.darkTheme(systemDark = false))
        assertTrue("固定深色在系统深色下也是深色", firstRun.darkTheme(systemDark = true))

        firstRun.selectAppearance(McaAppearanceThemeChoice.SYSTEM)
        assertTrue(firstRun.darkTheme(systemDark = true))
        assertFalse(firstRun.darkTheme(systemDark = false))
    }

    @Test
    fun `selectAppearance 后 appearanceChoice 立即反映新值`() {
        // The settings row reads this synchronously after its own callback, before any recomposition.
        val appearance = MemoryAppearanceStore()
        val firstRun = state(appearanceStore = appearance)
        assertEquals(McaAppearanceThemeChoice.SYSTEM, firstRun.appearanceChoice)

        firstRun.selectAppearance(McaAppearanceThemeChoice.DARK)

        assertEquals(McaAppearanceThemeChoice.DARK, firstRun.appearanceChoice)
        assertEquals(AppearanceThemeMode.DARK.wireName, appearance.values[AppearancePreferences.KEY_THEME_MODE])

        firstRun.selectAppearance(McaAppearanceThemeChoice.LIGHT)
        assertEquals(McaAppearanceThemeChoice.LIGHT, firstRun.appearanceChoice)
        assertEquals(AppearanceThemeMode.LIGHT.wireName, appearance.values[AppearancePreferences.KEY_THEME_MODE])
    }

    @Test
    fun `完成页后退不退出流程也不伪装成已完成`() {
        // This is the state the guide's system-back lands in: the terminal page refuses `back` while
        // `completed` is still false, which is why the refusal's copy must not claim the flow is done.
        val firstRun = state()
        firstRun.reachDonePage()

        firstRun.back()

        assertEquals(McaOnboardingStep.DONE, firstRun.flow.step)
        assertFalse("还没按进入应用，完成标记必须仍为假", firstRun.flow.completed)
        assertEquals(OnboardingRefusal.FLOW_COMPLETED, firstRun.flow.rejection)
    }

    @Test
    fun `replay 之后可以再完成一次`() {
        val firstRun = state()
        firstRun.reachDonePage()
        firstRun.enter()
        assertTrue(firstRun.flow.completed)

        firstRun.replay()
        assertEquals(McaOnboardingStep.WELCOME, firstRun.flow.step)

        firstRun.reachDonePage(McaOnboardingPathChoice.PRODUCTION)
        firstRun.enter()
        assertTrue(firstRun.flow.completed)
        assertEquals(McaOnboardingStep.DONE, firstRun.flow.step)
    }

    @Test
    fun `状态类不依赖 Android 上下文`() {
        // The Lead's replacement for a "remember semantics" test: remember() needs a composition, so
        // what is actually checkable on the JVM is that the constructible half of the file never touches
        // a Context, a store or a hand-built projection. That is what lets every test above run here.
        val body = firstRunStateClassBody()

        assertFalse("状态类体内不能出现 LocalContext", body.contains("LocalContext"))
        assertFalse("状态类体内不能直接开偏好文件", body.contains("getSharedPreferences"))
        assertFalse(
            "状态类不能自己造 store，必须由组合根注入",
            body.contains("SharedPreferencesOnboardingStore(") || body.contains("SharedPreferencesAppearanceStore(")
        )
        assertFalse(
            "状态类不能自己拼投影，只能接收控制器返回值",
            body.contains("OnboardingFlowUiState(")
        )
        val assignments = Regex("""\bflow\s*=\s*[^\n]+""").findAll(body).map { it.value.trim() }.toList()
        assertTrue("必须真的有赋值语句，否则这个断言是空转", assignments.isNotEmpty())
        assignments.forEach { assignment ->
            assertTrue(
                "flow 只能由控制器返回值替换，发现：$assignment",
                Regex("""^flow = onboarding\.[A-Za-z]+\(""").containsMatchIn(assignment)
            )
        }
        // The composable builder is the only place allowed to know about Context.
        assertTrue(
            "组合根必须用 applicationContext 构造，避免泄漏 Activity",
            firstRunHostSource().contains("LocalContext.current.applicationContext")
        )
    }

    /** The `FirstRunState` class body only: from the class declaration to the remember builder. */
    private fun firstRunStateClassBody(): String {
        val source = firstRunHostSource()
        val start = source.indexOf("internal class FirstRunState(")
        val end = source.indexOf("internal fun rememberFirstRunState(")
        assertTrue("FirstRunHost.kt 里找不到 FirstRunState 类声明", start >= 0)
        assertTrue("FirstRunHost.kt 里找不到 rememberFirstRunState，源码结构变了", end > start)
        return source.substring(start, end)
    }

    private fun firstRunHostSource(): String {
        val file = File(projectRoot(), "app/src/main/java/com/muyuchat/mca/FirstRunHost.kt")
        assertTrue("缺少源码文件：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** Walks up to the Gradle root, the same way the preferences golden test locates its fixture. */
    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    /**
     * The fields that describe where the flow *is*, without the two transient per-call channels.
     *
     * `load()` always reports a clean projection (no refusal, no write failure), so the comparison has to
     * ignore those two while still pinning step, answers and completion.
     */
    private fun OnboardingFlowUiState.business(): List<Any?> = listOf(
        step, path, skippedModel, completed, canAdvance, stepIndex, stepCount
    )

    private companion object {
        val ONBOARDING_KEYS = setOf(
            OnboardingPreferences.KEY_COMPLETED,
            OnboardingPreferences.KEY_STEP,
            OnboardingPreferences.KEY_PATH,
            OnboardingPreferences.KEY_SKIPPED_MODEL
        )
    }
}

package com.muyuchat.mca

import com.muyuchat.mca.appearance.AppearanceController
import com.muyuchat.mca.appearance.AppearancePreferences
import com.muyuchat.mca.appearance.AppearanceThemeMode
import com.muyuchat.mca.appearance.AppearanceThemeResolver
import com.muyuchat.mca.appearance.MemoryAppearanceStore
import com.muyuchat.mca.appearance.SharedPreferencesAppearanceStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-side wiring contract for the appearance slice.
 *
 * `app/src/test` has no Compose host, so the composition-level facts are pinned by reading the
 * production sources, in the same way `MainShellMotionContractTest` pins the shell. What matters and
 * cannot be expressed as a pure function is:
 *  - `McaTheme` actually receives a `darkTheme` argument (before this slice it always used the system
 *    default, so the whole feature was invisible);
 *  - the settings hub receives the real persisted choice and a real callback, not the parameter
 *    defaults that would silently pin every user to 跟随系统;
 *  - the preference owner is built from `applicationContext`, so it cannot leak the Activity.
 *
 * The logic-level cases at the bottom are deliberately redundant with the appearance module's own
 * tests: they run the same resolver and controller through this module's test classpath, so a
 * dependency-scope regression (for example `:app` losing its `:core:designsystem` edge) is caught here
 * and not only in a module the shell does not exercise.
 */
class AppearanceWiringContractTest {

    private val mainActivity = repoFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt")
    private val firstRunHost = repoFile("app/src/main/java/com/muyuchat/mca/FirstRunHost.kt")

    @Test
    fun `McaTheme 收到 darkTheme 参数`() {
        // 断言随代码搬家：主题节点现在由 FirstRunContent 提供（同一节点同时拥有引导与主界面），
        // 判据不变 —— 主题必须拿到从持久化偏好解析出来的 darkTheme，且不能有第二个节点。
        assertTrue(
            "McaTheme must be called with an explicit darkTheme argument; without it every user sees the system value",
            firstRunHost.contains("McaTheme(darkTheme = state.darkThemeForFrame())")
        )
        assertTrue(
            "the flag must be resolved from the persisted preference, not hardcoded",
            firstRunHost.contains("darkThemeForFrame()")
        )
        assertFalse(
            "the no-argument McaTheme call must not come back: it silently pins the app to the system scheme",
            firstRunHost.contains("McaTheme {")
        )
        assertFalse(
            "the shell must not open a second theme node: one McaTheme owns both the guide and the app",
            mainActivity.contains("McaTheme(")
        )
        assertTrue(
            "the system value must enter through the resolver, so 固定浅色/固定深色 can overrule it",
            firstRunHost.contains("isSystemInDarkTheme()") &&
                firstRunHost.contains("AppearanceThemeResolver.resolveDarkTheme")
        )
    }

    @Test
    fun `设置首页收到真实的外观回调而非默认值`() {
        val call = callBlock(mainActivity, "SettingsHubScreen(")

        assertTrue(
            "SettingsHubScreen must pass appearanceThemeChoice explicitly; omitting it falls back to the default 跟随系统",
            call.contains("appearanceThemeChoice =")
        )
        assertTrue(
            "SettingsHubScreen must pass a real change callback; the default is a no-op and would drop every tap",
            call.contains("onAppearanceThemeChoiceChange =")
        )
        assertTrue(
            "SettingsHubScreen must pass the onboarding replay callback, otherwise the row is dead",
            call.contains("onReplayOnboarding =")
        )
        assertTrue(
            "the choice must come from the root appearance state",
            call.contains("appearanceThemeChoice = appearanceRememberedChoice")
        )
        assertTrue(
            "the callback must be the root writer, not a local lambda",
            call.contains("onAppearanceThemeChoiceChange = onAppearanceRememberedChange")
        )
        // The root state itself must be wired to the controller, not to a constant. The observed
        // accessor is now read by FirstRunContent (which owns the theme node); the shell must forward
        // the value it is handed instead of reaching for the state object it no longer holds.
        assertTrue(
            "the selected value must be read through the observed accessor inside FirstRunContent",
            firstRunHost.contains("content(state.appearanceChoiceForFrame(), state::selectAppearance, state::replay)")
        )
        assertTrue(
            "the shell must forward the choice it is handed",
            mainActivity.contains("appearanceRememberedChoice = appearanceChoice,")
        )
        assertFalse(
            "the shell must not read the appearance state itself: the observed accessor is read once in " +
                "FirstRunContent, and a second read here could reach for the non-observed getter",
            mainActivity.contains("firstRun.")
        )
        assertTrue(mainActivity.contains("onAppearanceRememberedChange = onAppearanceChange,"))
        assertTrue(mainActivity.contains("onReplayOnboarding = onReplay"))
    }

    @Test
    fun `主题模式必须经被观察状态读取以触发重组`() {
        // StateFlow.value is not snapshot state: a composable that reads it keeps painting the value it
        // saw on the first composition. The composition-safe path is a collected state, and both
        // accessors used by the shell have to go through it.
        assertTrue(
            "the theme accessor must collect the appearance flow",
            firstRunHost.contains("val mode by themeModeFlow.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "every composition-facing accessor must collect, not read .value",
            Regex("themeModeFlow\\.collectAsStateWithLifecycle\\(\\)").findAll(firstRunHost).count() >= 2
        )
        assertFalse(
            "McaTheme must not be driven by the non-observed resolver overload",
            Regex("McaTheme\\(darkTheme = state\\.darkTheme\\(").containsMatchIn(firstRunHost)
        )
        assertTrue(
            "McaTheme must be driven by the observed accessor, which collects the appearance flow",
            firstRunHost.contains("McaTheme(darkTheme = state.darkThemeForFrame())")
        )
    }

    @Test
    fun `FirstRunState 不得标注 Stable`() {
        // @Stable promises that a public property change notifies composition. This class deliberately
        // exposes readers over StateFlow.value (appearanceChoice, darkTheme, themeModeFlow), which change
        // without invalidating composition, so the annotation would be a false promise masking mutable
        // data — exactly what the architecture contract forbids. Composition uses the collecting
        // accessors instead; the guard keeps a future edit from re-adding the annotation.
        assertFalse(
            "FirstRunState must not be @Stable while it exposes StateFlow.value readers",
            Regex("@Stable\\s*\\n\\s*internal class FirstRunState").containsMatchIn(firstRunHost)
        )
        assertFalse(
            "the Stable import must not come back just for this class",
            Regex("import androidx\\.compose\\.runtime\\.Stable\\b").containsMatchIn(firstRunHost)
        )
        // The non-composition readers are still allowed, but only with the warning in their KDoc.
        assertTrue(
            "appearanceChoice must stay documented as composition-unsafe",
            Regex(
                "Do NOT read this inside a composable[\\s\\S]{0,400}val appearanceChoice"
            ).containsMatchIn(firstRunHost)
        )
        assertTrue(
            "darkTheme(systemDark) must stay documented as composition-unsafe",
            Regex(
                "Do NOT call this inside a composable[\\s\\S]{0,600}fun darkTheme\\(systemDark: Boolean\\)"
            ).containsMatchIn(firstRunHost)
        )
    }

    @Test
    fun `FirstRunState 用 applicationContext 而非 Activity context`() {
        assertTrue(
            "the preference owners must be built from the application context",
            firstRunHost.contains("LocalContext.current.applicationContext")
        )
        assertTrue(
            "the appearance store must receive that application context",
            firstRunHost.contains("SharedPreferencesAppearanceStore(appContext)")
        )
        assertFalse(
            "the Activity context must never reach the preference store",
            Regex("SharedPreferencesAppearanceStore\\(\\s*LocalContext").containsMatchIn(firstRunHost)
        )
        assertFalse(
            "an Activity-held context in a remember block would leak it for the process lifetime",
            Regex("SharedPreferencesAppearanceStore\\(\\s*context\\s*\\)").containsMatchIn(firstRunHost)
        )
    }

    @Test
    fun `外观偏好文件名保持 mca_appearance`() {
        // A rename would orphan every stored choice: the file is the user's data, not an implementation
        // detail. It must also stay distinct from the chat background store.
        assertEquals("mca_appearance", SharedPreferencesAppearanceStore.PREFERENCES_NAME)
        assertFalse(
            "the colour mode must not share the chat appearance file",
            SharedPreferencesAppearanceStore.PREFERENCES_NAME.contains("chat")
        )
    }

    @Test
    fun `深色解析真值表`() {
        // 跟随系统 defers to the platform.
        assertTrue(AppearanceThemeResolver.resolveDarkTheme(systemDark = true, mode = AppearanceThemeMode.SYSTEM))
        assertFalse(AppearanceThemeResolver.resolveDarkTheme(systemDark = false, mode = AppearanceThemeMode.SYSTEM))
        // 固定浅色 overrules a dark system, which is the only reason the control is worth having.
        assertFalse(AppearanceThemeResolver.resolveDarkTheme(systemDark = true, mode = AppearanceThemeMode.LIGHT))
        // 固定深色 overrules a light system.
        assertTrue(AppearanceThemeResolver.resolveDarkTheme(systemDark = false, mode = AppearanceThemeMode.DARK))
    }

    @Test
    fun `选择外观后状态流立即反映`() {
        val store = MemoryAppearanceStore()
        val controller = AppearanceController(AppearancePreferences(store))
        assertEquals(AppearanceThemeMode.SYSTEM, controller.themeMode.value)

        controller.select(AppearanceThemeMode.DARK)

        assertEquals(
            "the flow must carry the choice without waiting for a collector",
            AppearanceThemeMode.DARK,
            controller.themeMode.value
        )
        assertEquals(
            "the choice must also reach the store",
            "dark",
            store.values[AppearancePreferences.KEY_THEME_MODE]
        )
    }

    /**
     * Slices out one parenthesised call, so an assertion cannot accidentally be satisfied by a matching
     * parameter name elsewhere in the 900-line file.
     */
    private fun callBlock(source: String, anchor: String): String {
        val start = source.indexOf(anchor)
        require(start >= 0) { "Unable to locate $anchor in the source" }
        val open = start + anchor.length - 1
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("Unbalanced parentheses after $anchor")
    }

    /**
     * Reads a repository file wherever the test working directory happens to be: Gradle runs module
     * tests from the module directory as well as from the root, so the root is found by walking up to
     * the file that only exists there.
     */
    private fun repoFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                check(file.isFile) { "Missing $relativePath" }
                return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
            }
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }
}

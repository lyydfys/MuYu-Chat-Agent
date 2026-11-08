package com.muyuchat.mca

import com.muyuchat.core.designsystem.onboarding.McaOnboardingStep
import com.muyuchat.mca.onboarding.OnboardingFlowController
import com.muyuchat.mca.onboarding.OnboardingPath
import com.muyuchat.mca.onboarding.OnboardingPreferenceStore
import com.muyuchat.mca.onboarding.OnboardingPreferences
import com.muyuchat.mca.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首启引导第三页「准备一个模型」的诚实性契约。
 *
 * ## 被钉住的产品决定
 *
 * 本仓库目前**没有**把模型下载端口接进引导流程。装配层（`FirstRunHost`）因此把
 * `onDownloadRecommended` 与 `onSkipModel` 都映射到 `OnboardingFlowController.skipModel()`，
 * 也就是「点了下载按钮，也只记录『没有选中模型』」。
 *
 * 这个决定的**好处**是完成页不会说谎：`skippedModel = true` 时完成页说「还没有可用模型。进入后到
 * 「模型管理」选一个」，与事实一致；若改用 `markModelReady()`（它会把 `skippedModel` 写成 false），
 * 完成页就会说「模型已准备好，直接开始对话。」——而实际一个模型都没装。那是本条契约要拦住的谎话。
 *
 * 这个决定的**代价**是按钮名与行为不符。S1 已修掉这个代价：设计系统的
 * `McaOnboardingModelPage` 现在有 `downloadAvailable` 开关，装配层在
 * `OnboardingFlowHost` 的 MODEL 分支显式传 `downloadAvailable = false`，主按钮因此改称
 * 「稍后下载模型」、「下载完成后会自动加载」整段隐藏、冗余的第二个动作消失 —— 页面不再承诺一次
 * 不会发生的下载。契约因此分成三层：
 * - 状态记录必须诚实（逻辑级断言，钉住 `skipModel()` 的语义）；
 * - 装配层不得偷偷换成 `markModelReady()`（源码级断言，钉住回调映射）；
 * - 未接线时的文案不得承诺下载（源码级断言，钉住 `downloadAvailable` 的三处表现）。
 *
 * 剩下唯一的缺口是真正接线下载：那时应把装配层的 `downloadAvailable` 改成 `true`、
 * 把 `onDownloadRecommended` 接到真实下载入口；`装配层必须显式声明当前没有下载能力`
 * 会因此失败并指出要改哪里 —— 这是有意的设计，不是遗漏。
 *
 * ## 为什么是源码级 + 逻辑级而不是 Compose 测试
 *
 * `:app` 单元测试源集没有 Robolectric，也没有 `compose-ui-test-junit4`（在 `androidTest`，要真机）。
 * 而这条契约的核心（记录的标志位是什么）本來就是纯状态机行为，用真实的
 * `OnboardingFlowController` + 内存假 store 直接断言比点界面更接近事实。
 */
class OnboardingModelStepHonestyTest {

    @Test
    fun `引导完成页的模型文案由 skippedModel 驱动`() {
        // 完成页是用户离开引导前看到的最后一句关于模型的话。两个分支必须由 skippedModel 决定，
        // 若有人把它改成恒定文案，无论哪种真实状态都会有一半用户被误导。
        // 用 withoutComments 而不是 codeOnly：这两句文案本身就在字符串字面量里，剥掉就无从断言；
        // 该区域内没有字符串跨行、也没有含大括号的模板，所以大括号配对仍然可靠。
        val source = withoutComments(source(PAGES))
        val donePage = source.substringAfter("public fun McaOnboardingDonePage(")
        val skippedStart = donePage.indexOf("if (skippedModel) {")
        assertTrue("完成页必须按 skippedModel 分成两套模型文案", skippedStart >= 0)

        val skippedBranch = blockAfter(donePage, skippedStart)
        assertTrue(
            "skippedModel = true 时必须说「还没有可用模型」，不能谎报已准备好",
            skippedBranch.contains("还没有可用模型")
        )
        assertTrue("跳过时还要指出下一步去哪：模型管理", skippedBranch.contains("模型管理"))

        val elseStart = donePage.indexOf("else {", skippedStart + skippedBranch.length)
        assertTrue("完成页必须有一个 else 分支给出「已准备好」的那句", elseStart >= 0)
        val readyBranch = blockAfter(donePage, elseStart)
        assertTrue("skippedModel = false 时才允许说「模型已准备好」", readyBranch.contains("模型已准备好"))
        assertFalse("「还没有可用模型」不能同时出现在「已准备好」的分支里", readyBranch.contains("还没有可用模型"))
    }

    @Test
    fun `已接线下载时仍不得谎报模型已准备好`() {
        // 接线之后这条契约反向成立：下载入口必须是真的下载（真实不可用时才退化为跳过），
        // 仍然不允许用 markModelReady() 把 skippedModel 写成 false 来让完成页说「模型已准备好」。
        val source = withoutComments(source(FIRST_RUN_HOST))
        val call = callArguments(source, "OnboardingFlowHost(")
        val downloadTarget = argumentOf(call, "onDownloadRecommended")

        assertFalse(
            "下载入口不能再无条件映射到 ::skipModel：有推荐模型时它必须走真实下载",
            downloadTarget == "::skipModel"
        )
        assertTrue(
            "下载入口必须按 modelStep.available 区分：可下载走真实入口，不可下载才退化为跳过",
            downloadTarget.contains("if (modelStep.available)") && downloadTarget.contains("onDownloadModel")
        )
        assertTrue(
            "不可下载时的退化路径必须是 ::skipModel（记录「没选模型」），而不是假装成功",
            downloadTarget.contains("::skipModel")
        )
        assertFalse(
            "装配层仍然不得引用 markModelReady：只有真正下载完成才允许把 skippedModel 写成 false",
            source.contains("markModelReady")
        )
    }

    @Test
    fun `跳过模型会记录 skippedModel`() {
        // 逻辑级：用真实控制器 + 内存假 store 走一遍「走到模型页 -> 跳过」，断言投影与持久化都记为已跳过。
        val (controller, store) = freshFlow()
        val atModel = advanceToModel(controller)

        assertEquals("前置条件：应当停在模型页", McaOnboardingStep.MODEL, atModel.step)
        assertFalse("还没有做过模型选择时，skippedModel 必须是 false", atModel.skippedModel)

        val afterSkip = controller.skipModel()
        assertEquals("跳过之后应当到达完成页", McaOnboardingStep.DONE, afterSkip.step)
        assertTrue("跳过必须记录 skippedModel = true，完成页才会说「还没有可用模型」", afterSkip.skippedModel)
        assertTrue("跳过不是完成：completed 此时仍为 false", !afterSkip.completed)

        // 重新播种一个控制器，确认标志位真的落到了 store，而不是只在内存投影里。
        val reloaded = OnboardingFlowController(OnboardingPreferences(store)).load()
        assertEquals("重启后必须仍在完成页", McaOnboardingStep.DONE, reloaded.step)
        assertTrue("skippedModel 必须被持久化，否则重启后完成页会改口说「已准备好」", reloaded.skippedModel)
    }

    @Test
    fun `确认就绪的入口与跳过的记录相反`() {
        // 这条钉住「两个入口语义不同」这件事本身：markModelReady() 清掉跳过标记，skipModel() 设置它。
        // 有了这条，就不存在「两个方法其实等价」的误读，也解释了为什么装配层选错会造成谎报。
        val (readyController, _) = freshFlow()
        advanceToModel(readyController)
        val afterReady = readyController.markModelReady()
        assertEquals("确认就绪后应当到达完成页", McaOnboardingStep.DONE, afterReady.step)
        assertFalse("markModelReady() 必须把 skippedModel 写成 false", afterReady.skippedModel)

        val (skipController, _) = freshFlow()
        advanceToModel(skipController)
        val afterSkip = skipController.skipModel()
        assertTrue("skipModel() 必须把 skippedModel 写成 true", afterSkip.skippedModel)

        assertNotEquals(
            "两个入口对 skippedModel 的记录必须相反，否则完成页无法区分真实状态",
            afterReady.skippedModel,
            afterSkip.skippedModel
        )
    }

    @Test
    fun `引导不调用任何真实下载入口`() {
        // 反向断言必须用「仓库里真实存在的下载符号」，否则断言永远为真。已 grep 确认：
        // ModelScopeHubOperations.downloadRecommended(...) 是真实的推荐模型下载入口，
        // downloadModel / DownloadRequest / resumeDownload 也真实存在于其它模块。
        val realDownloadSymbols = listOf(
            "downloadRecommended",
            "downloadModel",
            "DownloadRequest",
            "resumeDownload",
            "ModelScopeHubOperations"
        )
        val owners = listOf(
            FIRST_RUN_HOST to "首启装配层",
            ONBOARDING_HOST to "引导宿主"
        )
        owners.forEach { (path, label) ->
            val source = withoutComments(source(path))
            realDownloadSymbols.forEach { symbol ->
                assertFalse(
                    "$label 直接调用了真实下载入口 $symbol：接线下载时应当走明确的下载 owner，并同时改写本条契约",
                    source.contains(symbol)
                )
            }
        }

        // 防止上面的反向断言变空洞：两个文件都必须仍然保留「下载推荐模型」这条路径，
        // 说明它们确实渲染了那个按钮，而不是把模型页整个删掉来让「不下载」变成真的。
        assertTrue(
            "装配层必须仍然把 onDownloadRecommended 交给宿主",
            withoutComments(source(FIRST_RUN_HOST)).contains("onDownloadRecommended")
        )
        // 这里必须断言「函数签名里有这个参数」，而不是任意位置的字符串：
        // 宿主内部 `onDownloadRecommended = onDownloadRecommended` 的用法会让宽松的 contains 永远成立。
        assertTrue(
            "引导宿主必须仍然声明 onDownloadRecommended 入参，否则「下载推荐模型」按钮已被移除",
            withoutComments(source(ONBOARDING_HOST)).contains("onDownloadRecommended: () -> Unit")
        )
    }

    @Test
    fun `下载完成后的加载承诺必须与真实启动参数一致`() {
        // 下载用 useAfterDownload = false 启动，所以「下载完成后会自动加载」是不成立的承诺。
        // 这条测试在接线后反转成「防止旧承诺复活」：旧句必须消失，新句必须说清真实下一步。
        val pages = withoutComments(source(PAGES))
        val modelPage = pages
            .substringAfter("public fun McaOnboardingModelPage(")
            .substringBefore("public fun McaOnboardingDonePage(")

        assertTrue(
            "McaOnboardingModelPage 必须保留 downloadAvailable 开关，且默认 true（既有调用方与金样零影响）",
            modelPage.contains("downloadAvailable: Boolean = true")
        )
        assertEquals(
            "旧的「下载完成后会自动加载」必须彻底消失：本流程以 useAfterDownload = false 启动下载",
            0,
            modelPage.split("下载完成后会自动加载").size - 1
        )
        assertTrue(
            "文案必须说清真实下一步：下载完成后到「模型管理」里加载",
            modelPage.contains("下载完成后可在「模型管理」里加载")
        )
        assertTrue(
            "这段文案必须被 downloadAvailable 与 failed 一起守住，未接线或已失败时都不渲染",
            modelPage.contains("if (downloadAvailable && !failed) {")
        )
        val detailBranch = blockAfter(modelPage, modelPage.indexOf("if (downloadAvailable && !failed) {"))
        assertTrue(
            "加载说明必须留在这个分支内",
            detailBranch.contains("下载完成后可在「模型管理」里加载")
        )
        // 未接线时的两个文案分支仍然必须在，且仍然指向「模型管理」。
        val labelWhen = blockAfter(modelPage, modelPage.indexOf("val primaryLabel = when {"))
        assertTrue(
            "未接线时主按钮不得再读「下载推荐模型」",
            labelWhen.contains("!downloadAvailable -> \"稍后下载模型\"")
        )
        assertTrue(
            "已接线（默认态）必须保留「下载推荐模型」",
            labelWhen.contains("else -> \"下载推荐模型\"")
        )
        val secondaryWhen = blockAfter(modelPage, modelPage.indexOf("val secondaryLabel: String? = when {"))
        assertTrue(
            "未接线时不应再出现第二个做同一件事的按钮",
            secondaryWhen.contains("!downloadAvailable -> null")
        )
        val bodyWhen = blockAfter(modelPage, modelPage.indexOf("text = when {"))
        assertTrue(
            "未接线态的正文必须告诉用户去哪里下载（模型管理）",
            bodyWhen.contains("!downloadAvailable ->") && bodyWhen.contains("模型管理")
        )
    }

    @Test
    fun `装配层的下载可用性必须来自真实 owner`() {
        // 接线后：页面拿到的 downloadAvailable 必须是 modelStep.available（owner 的真实状态），
        // 既不能写死 false（会永远不下载），也不能写死 true（没有推荐模型时就承诺下载）。
        val host = withoutComments(source(ONBOARDING_HOST))
        val pageArgs = callArguments(host, "McaOnboardingModelPage(")

        assertEquals(
            "downloadAvailable 必须由 owner 状态驱动，不能写死",
            "modelStep.available",
            argumentOf(pageArgs, "downloadAvailable")
        )
        assertTrue(
            "页面还必须拿到真实进度与失败原因，否则进度条与重试只是装饰",
            pageArgs.contains("progressPercent = modelStep.progressPercent") &&
                pageArgs.contains("failure = modelStep.failure")
        )
    }

    // --- helpers -------------------------------------------------------------

    /** 每次给一个全新的控制器与它背后的内存 store，避免测试之间互相污染。 */
    private fun freshFlow(): Pair<OnboardingFlowController, FakeStore> {
        val store = FakeStore()
        return OnboardingFlowController(OnboardingPreferences(store)) to store
    }

    /** 走过去：WELCOME -> PATH -> (选择路径) -> MODEL。返回停在模型页的投影。 */
    private fun advanceToModel(controller: OnboardingFlowController) =
        controller.load().let { controller.advance() }
            .let { controller.selectPath(OnboardingPath.EXPERIENCE) }
            .let { controller.advance() }
            .also { assertEquals("前置条件：应当停在模型页", McaOnboardingStep.MODEL, it.step) }

    /** 内存实现，正是 [OnboardingPreferenceStore] 这层接缝存在的意义：编解码不需要 Android。 */
    private class FakeStore : OnboardingPreferenceStore {
        private val strings = mutableMapOf<String, String>()
        private val booleans = mutableMapOf<String, Boolean>()

        override fun read(key: String): String? = strings[key]

        override fun write(key: String, value: String?) {
            if (value == null) strings.remove(key) else strings[key] = value
        }

        override fun readBoolean(key: String): Boolean = booleans[key] ?: false

        override fun writeBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }
    }

    /** 取出 `name(` 之后、括号配对为止的实参文本。 */
    private fun callArguments(source: String, name: String): String {
        val start = source.indexOf(name)
        require(start >= 0) { "找不到调用 $name" }
        val open = source.indexOf('(', start)
        require(open >= 0) { "找不到 $name 的左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        throw AssertionError("调用 $name 的括号不配对")
    }

    /** 取某个具名实参的值（`onX = ::method` 的 `::method`），去掉尾部逗号与空白。 */
    private fun argumentOf(call: String, name: String): String {
        val marker = "$name = "
        val start = call.indexOf(marker)
        assertTrue("调用里必须存在具名实参 $name", start >= 0)
        val rest = call.substring(start + marker.length)
        val end = rest.indexOf(',')
        return (if (end >= 0) rest.substring(0, end) else rest).trim()
    }

    /** 只剥注释、保留字符串：用于断言具体中文文案，也用于本文件里所有大括号配对（这些区域无含括号的模板）。 */
    private fun withoutComments(source: String): String = strip(source, keepStringBodies = true)

    private fun strip(source: String, keepStringBodies: Boolean): String = buildString(source.length) {
        var index = 0
        var state = NORMAL
        while (index < source.length) {
            val char = source[index]
            val next = if (index + 1 < source.length) source[index + 1] else ZERO
            when (state) {
                NORMAL -> when {
                    char == '/' && next == '/' -> {
                        state = LINE_COMMENT
                        index++
                    }

                    char == '/' && next == '*' -> {
                        state = BLOCK_COMMENT
                        index++
                    }

                    char == '"' -> {
                        state = STRING
                        append('"')
                    }

                    else -> append(char)
                }

                LINE_COMMENT -> if (char == '\n') {
                    state = NORMAL
                    append('\n')
                }

                BLOCK_COMMENT -> if (char == '*' && next == '/') {
                    state = NORMAL
                    index++
                }

                STRING -> when {
                    char == '\\' -> {
                        if (keepStringBodies) {
                            append(char)
                            if (next != ZERO) append(next)
                        }
                        index++
                    }

                    char == '"' -> {
                        state = NORMAL
                        append('"')
                    }

                    else -> if (keepStringBodies) append(char)
                }
            }
            index++
        }
    }

    /** 返回 [start] 之后第一个 `{` 所打开代码块的正文（不含两侧大括号）。 */
    private fun blockAfter(source: String, start: Int): String {
        require(start >= 0) { "找不到要检查的代码块起点" }
        val open = source.indexOf('{', start)
        require(open >= 0) { "从索引 $start 起找不到代码块" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        throw AssertionError("源码大括号不配对，无法判定代码块范围")
    }

    private fun source(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("找不到仓库根（从 ${System.getProperty("user.dir")} 向上没有 settings.gradle.kts）")
    }

    private companion object {
        const val NORMAL = 0
        const val LINE_COMMENT = 1
        const val BLOCK_COMMENT = 2
        const val STRING = 3
        const val ZERO = '\u0000'

        const val PAGES = "core/designsystem/src/main/java/com/muyuchat/core/designsystem/onboarding/McaOnboardingPages.kt"
        const val FIRST_RUN_HOST = "app/src/main/java/com/muyuchat/mca/FirstRunHost.kt"
        const val ONBOARDING_HOST = "app/src/main/java/com/muyuchat/mca/onboarding/OnboardingFlowHost.kt"
    }
}

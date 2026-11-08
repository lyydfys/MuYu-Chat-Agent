package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首启引导「装配层」的契约：门控、返回键、四个步骤的覆盖、拒绝文案，以及「谁可以写持久化」。
 *
 * 这是使用权在 `:app` 装配层，不是设计系统。设计系统的页面契约在
 * `:core:designsystem`（`McaOnboardingAccessibilityContractTest`）里钉住；这里只钉「谁在什么时候
 * 渲染它、谁负责它的状态、它能不能自己落盘」。
 *
 * 为什么是源码级契约而不是 Compose 测试：`:app` 的单元测试源集只有
 * `junit` + `kotlinx-coroutines-test` + `json` + `sqlite-jdbc`，**没有** Robolectric，也没有
 * `compose-ui-test-junit4`（那个在 `androidTestImplementation`，要真机/模拟器才能跑）。在没有设备
 * 的 JVM 单测里 `createComposeRule()` 要么直接编译不过，要么运行期崩在 `IllegalStateException`，
 * 所以这里沿用仓库已有的「读 `.kt` 原文断言结构」做法（`SettingsSurfaceGoldenTest`、
 * `McaControlStateContractTest` 都是先例）。
 *
 * 为了让断言不是「字符串恰好出现」，凡是涉及分支的断言都用大括号配对先切出 `when` / `if` / `else`
 * 的真实块，再在块内断言；注释与字符串字面量会先被剥掉，所以「注释里写了一句」不会让断言通过。
 */
class OnboardingGateContractTest {

    @Test
    fun `未完成引导时不渲染主界面`() {
        // 门控仍然只有一个，但它随职责搬进了 FirstRunContent（引导与主界面同属的首帧外壳），所以结构
        // 断言跟着搬家而不是放宽：主界面只能经 content 槽进来，槽在 else 分支里调用，引导分支根本拿
        // 不到它，用户就不可能看到一层空壳聊天界面压在引导下面。
        val shell = codeOnly(source(FIRST_RUN_HOST))
        val gate = shell.indexOf("if (!state.flow.completed) {")
        assertTrue("FirstRunContent 不再以「引导未完成」作为主界面的门控，首启流程可能整段失效", gate >= 0)

        val guideBranch = blockAfter(shell, gate)
        assertTrue("未完成引导时必须渲染引导页 state.Guide(", guideBranch.contains("state.Guide("))
        assertFalse("未完成引导时不能调用主界面内容槽：content( 不得出现在引导分支内", guideBranch.contains("content("))

        val elseStart = shell.indexOf("else {", gate + guideBranch.length)
        assertTrue("引导完成后必须存在 else 分支来渲染主界面", elseStart >= 0)
        val appBranch = blockAfter(shell, elseStart)
        assertTrue("引导完成后必须调用内容槽渲染主界面，否则用户完成引导后进不去应用", appBranch.contains("content("))

        // 两条补充锚点，防止「搬家」被当成「放宽」：外壳自己不许写死主界面，Activity 也不许再自持门控。
        assertFalse("主界面不能写死在首帧外壳里，必须由调用方通过 content 槽提供", shell.contains("McaApp("))
        val activity = codeOnly(source(MAIN_ACTIVITY))
        assertFalse(
            "MainActivity 不得再自持门控：两条渲染路径都必须经 FirstRunContent，否则可以绕过门控直接组合主界面",
            activity.contains("flow.completed")
        )
    }

    @Test
    fun `引导页接管系统返回键`() {
        // 返回键必须由宿主接管并转发给控制器：页面自己不知道「上一步是哪个」，直接 pop 会让用户
        // 从引导里掉出去（首启没有任何「下面还有一层」可以回退）。
        val source = withoutComments(source(ONBOARDING_HOST))
        assertTrue("引导宿主必须安装 BackHandler，否则系统返回键会绕过引导流程", source.contains("BackHandler {"))
        assertTrue("返回键必须转发给控制器的 onBack 回调，而不是就地改页面", source.contains("BackHandler { onBack() }"))
    }

    @Test
    fun `引导覆盖全部四个步骤`() {
        // 四个页面一个都不能少，也不能加 else 兜底：漏掉一个分支时，加 else 会让用户停在一个
        // 「看起来正常但不是那一页」的界面上，而不加 else 至少是编译错误。
        val source = codeOnly(source(ONBOARDING_HOST))
        val whenStart = source.indexOf("when (state.step) {")
        assertTrue("引导宿主必须按 state.step 分派页面", whenStart >= 0)

        val whenBlock = blockAfter(source, whenStart)
        listOf("WELCOME", "PATH", "MODEL", "DONE").forEach { step ->
            assertTrue("McaOnboardingStep.$step 分支缺失：该步骤没有页面可渲染", whenBlock.contains("McaOnboardingStep.$step ->"))
        }
        assertFalse("when 必须穷尽四个步骤：出现 else 兜底会掩盖被漏掉的页面", whenBlock.contains("else ->"))
    }

    @Test
    fun `拒绝原因有完整中文提示`() {
        // 四种拒绝各对应一次「用户点了但没反应」：没有提示时用户只会以为自己点坏了。
        // 用大括号配对先切出 hint() 的 when 块，再在块内找分支，所以注释里提到常量不算数。
        val source = withoutComments(source(ONBOARDING_HOST))
        val hintStart = source.indexOf("private fun OnboardingRefusal.hint(")
        assertTrue("引导宿主必须把拒绝原因翻译成用户可读的中文提示", hintStart >= 0)

        val hintBody = blockAfter(source, hintStart)
        listOf("PATH_NOT_SELECTED", "AT_FIRST_STEP", "NOT_ON_MODEL_STEP", "FLOW_COMPLETED").forEach { refusal ->
            assertTrue(
                "OnboardingRefusal.$refusal 在 hint() 里没有分支：用户会遇到一次没有任何提示的死点击",
                hintBody.contains("OnboardingRefusal.$refusal ->")
            )
        }
        assertFalse("hint() 不能有 else 兜底：新增拒绝原因时必须被迫补一条中文提示", hintBody.contains("else ->"))
    }

    @Test
    fun `完成页的拒绝文案区分是否已进入应用`() {
        // FLOW_COMPLETED 是唯一一个「同一种拒绝、两种处境」的情况：控制器在完成页上无论是否已进入
        // 应用都会返回它。若只给一句「引导已经完成，可以在设置里重新查看」，那么对还站在最后一页的
        // 用户来说这句话既不是事实（他没完成），也不可执行（引导内部到不了设置）。
        val source = withoutComments(source(ONBOARDING_HOST))
        val hintBody = blockAfter(source, source.indexOf("private fun OnboardingRefusal.hint("))
        val completedBranch = hintBody.substringAfter("OnboardingRefusal.FLOW_COMPLETED ->")

        assertTrue("FLOW_COMPLETED 必须按 completed 区分两种文案", completedBranch.contains("if (completed) {"))
        assertTrue(
            "已完成时必须说明可以在设置里重新查看",
            completedBranch.contains("\"引导已经完成，可以在设置里重新查看。\"")
        )
        assertTrue(
            "未完成（人还站在最后一页）时必须引导点「进入应用」，不能让他去设置",
            completedBranch.contains("\"这是最后一步，点下面的「进入应用」继续。\"")
        )
    }

    @Test
    fun `拒绝优先于写盘失败展示`() {
        // 控制器从不在同一投影里同时报告两者（拒绝会在写盘之前返回），但展示顺序仍是契约：
        // 若先看 writeFailure，一次被拒绝的点击会显示「没能保存」，而实际上什么都没发生过。
        val source = codeOnly(source(ONBOARDING_HOST))
        val noticeBody = blockAfter(source, source.indexOf("private fun OnboardingFlowUiState.notice(): String? {"))
        val rejectionIndex = noticeBody.indexOf("rejection")
        val writeFailureIndex = noticeBody.indexOf("writeFailure")

        assertTrue("notice() 必须先看 rejection", rejectionIndex >= 0)
        assertTrue("notice() 必须同时处理 writeFailure", writeFailureIndex >= 0)
        assertTrue("拒绝原因必须排在写盘失败之前，否则一次被拒绝的点击会被说成「没保存」", rejectionIndex < writeFailureIndex)
        assertTrue(
            "拒绝必须直接返回中文提示，而不是落到写盘失败那句话",
            noticeBody.contains("if (refusal != null) return refusal.hint(completed)")
        )
    }

    @Test
    fun `写盘失败提示不暴露诊断原因`() {
        // reason 来自 store，是诊断文本（可能含路径/异常名），只能进日志，不能当用户提示。
        val source = codeOnly(source(ONBOARDING_HOST))
        val noticeBody = blockAfter(source, source.indexOf("private fun OnboardingFlowUiState.notice(): String? {"))

        assertTrue("写盘失败必须给一句面向用户的中文提示", noticeBody.contains("WRITE_FAILURE_HINT"))
        assertFalse("写盘失败的 reason 是诊断文本，不能直接渲染给用户", noticeBody.contains(".reason"))
    }

    @Test
    fun `引导页不直接写持久化`() {
        // 持久化只能由 OnboardingFlowController 做：宿主一旦自己 new 一个 store，页面状态就会有
        // 两个权威来源，进程死亡后的恢复路径也就分叉了。
        val source = withoutComments(source(ONBOARDING_HOST))

        assertFalse("引导宿主不能自己构造持久化 owner（OnboardingPreferences）", source.contains("OnboardingPreferences"))
        assertFalse("引导宿主不能直接碰 SharedPreferences", source.contains("SharedPreferences"))
        assertFalse("宿主不能自己 load 持久化状态：页面必须来自调用方传入的投影", source.contains("onboarding.load("))
    }

    @Test
    fun `能力同意对话框在引导与主界面两态都可用`() {
        // DSH 能力同意是应用级入口：放在 if/else 之内会让引导期间的同意请求永远弹不出来（首启就被
        // 静默拒绝），放在两个分支里各一份又会变成两个 owner。门控搬进 FirstRunContent 之后，两种状态
        // 都在同一个函数里，这个结构反而更容易被完整钉住；断言随代码搬家，判据一字未改。
        val shell = codeOnly(source(FIRST_RUN_HOST))
        val gate = shell.indexOf("if (!state.flow.completed) {")
        assertTrue("FirstRunContent 必须保留首启门控", gate >= 0)

        val guideBranch = blockAfter(shell, gate)
        val elseStart = shell.indexOf("else {", gate + guideBranch.length)
        assertTrue("引导完成后必须存在 else 分支", elseStart >= 0)
        val appBranch = blockAfter(shell, elseStart)

        val dialog = shell.indexOf("com.muyuchat.mca.composition.DshCapabilityConsentDialog()")
        assertTrue("能力同意对话框必须被调用", dialog >= 0)
        assertTrue("能力同意对话框必须在 if/else 之外（两种状态都要能弹）", dialog > elseStart + appBranch.length)
        assertFalse("能力同意对话框不能只挂在引导分支里", guideBranch.contains("DshCapabilityConsentDialog"))
        assertFalse("能力同意对话框不能只挂在主界面分支里", appBranch.contains("DshCapabilityConsentDialog"))
        assertEquals(
            "能力同意对话框只能有一个调用点：两个分支各写一份会变成两个 owner",
            1,
            shell.split("DshCapabilityConsentDialog()").size - 1
        )
        // 对话框不能退回 MainActivity：那里它只会跟着内容槽一起、在引导完成后才被组合。
        assertFalse(
            "能力同意对话框不能留在 MainActivity：它必须与门控同层，才能在两态都可用",
            source(MAIN_ACTIVITY).contains("DshCapabilityConsentDialog")
        )
    }

    @Test
    fun `引导不依赖 MainUiState 字段`() {
        // 首启发生在会话/引擎装配之前：装配层若读了 MainUiState 的字段，引导就会被一个「还没有值」
        // 的全局状态绑住，而这正是首启最不该依赖的东西。
        val owners = listOf(
            ONBOARDING_HOST to "引导宿主",
            FIRST_RUN_HOST to "首启装配层"
        )
        owners.forEach { (path, label) ->
            val source = withoutComments(source(path))
            assertFalse("$label 不能依赖 MainUiState：引导必须在没有会话状态时也能渲染", source.contains("MainUiState"))
        }
        // 只断言「不含 MainUiState」会有一条空洞路径：把装配层整个删空也满足它。所以再加正向锚点，
        // 要求这两个文件仍然是「首启装配层 + 引导宿主」，而不是被清空或改名后偷偷绕过检查。
        val assembly = withoutComments(source(FIRST_RUN_HOST))
        assertTrue("首启装配层必须仍然声明 FirstRunState", assembly.contains("internal class FirstRunState("))
        assertTrue("首启装配层必须仍然把投影交给引导宿主（OnboardingFlowHost(", assembly.contains("OnboardingFlowHost("))
        assertTrue("引导宿主必须仍然是一个 Composable 宿主函数", withoutComments(source(ONBOARDING_HOST)).contains("internal fun OnboardingFlowHost("))
    }

    @Test
    fun `引导宿主不自持页面状态`() {
        // 宿主是纯渲染：页面由传入的 OnboardingFlowUiState 投影决定。它一旦 remember 了自己的
        // step，进程死亡后的恢复就会和持久化的 step 打架，用户会被弹回错误的页面。
        val source = codeOnly(source(ONBOARDING_HOST))

        assertFalse("引导宿主不能 remember 页面状态，页面必须完全由传入投影决定", source.contains("remember {"))
        assertFalse("引导宿主不能持有可变状态（mutableStateOf）", source.contains("mutableStateOf"))
    }

    /**
     * 剥掉注释与字符串字面量的内容，只留结构。
     *
     * 两个用途：大括号配对时不被字符串里的 `{`（例如 `"...${System.currentTimeMillis()}.json"`）
     * 打断；断言分支存在时，注释里写的 `OnboardingRefusal.X ->` 不会让断言假通过。
     */
    private fun codeOnly(source: String): String = strip(source, keepStringBodies = false)

    /** 只剥注释、保留字符串字面量：用于断言具体中文文案（文案本身在字符串里）。 */
    private fun withoutComments(source: String): String = strip(source, keepStringBodies = true)

    private fun strip(source: String, keepStringBodies: Boolean): String = buildString(source.length) {
        var index = 0
        var state = NORMAL
        while (index < source.length) {
            val char = source[index]
            val next = if (index + 1 < source.length) source[index + 1] else ZERO
            when (state) {
                NORMAL -> when {
                    char == '/' && next == '/' -> { state = LINE_COMMENT; index++ }
                    char == '/' && next == '*' -> { state = BLOCK_COMMENT; index++ }
                    char == '"' -> { state = STRING; append('"') }
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
                    char == '"' -> { state = NORMAL; append('"') }
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
        require(open >= 0) { "从索引 $start 起找不到代码块，无法判定结构边界" }
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
        throw AssertionError("源码的大括号不平衡，无法判定代码块范围")
    }

    private fun source(relativePath: String): String = File(projectRoot(), relativePath).readText(Charsets.UTF_8)

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

        const val ONBOARDING_HOST = "app/src/main/java/com/muyuchat/mca/onboarding/OnboardingFlowHost.kt"
        const val FIRST_RUN_HOST = "app/src/main/java/com/muyuchat/mca/FirstRunHost.kt"
        const val MAIN_ACTIVITY = "app/src/main/java/com/muyuchat/mca/MainActivity.kt"
    }
}

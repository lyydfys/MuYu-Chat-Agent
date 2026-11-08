package com.muyuchat.mca.onboarding

import com.muyuchat.core.designsystem.onboarding.McaOnboardingPathChoice
import com.muyuchat.core.designsystem.onboarding.McaOnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the first-run flow controller.
 *
 * Everything runs on a JVM: the controller takes [OnboardingPreferences], which takes an
 * [OnboardingPreferenceStore]. Each test drives the flow exactly like the UI would — one call per tap — and
 * then asserts on the returned projection *and* on the keys that landed in the store, because "the flow
 * resumes where the user was" is a persistence promise, not an in-memory one.
 */
class OnboardingFlowControllerTest {

    private fun controller(store: OnboardingPreferenceStore) =
        OnboardingFlowController(OnboardingPreferences(store))

    /** What a fresh process sees: a new controller and a new codec over the same store. */
    private fun restored(store: OnboardingPreferenceStore) = controller(store)

    /** Walks welcome -> path -> pick -> model the way the UI does, and stops on the model page. */
    private fun controllerOnModelPage(
        store: RecordingOnboardingStore,
        path: OnboardingPath = OnboardingPath.EXPERIENCE
    ): OnboardingFlowController {
        val controller = controller(store)
        controller.advance()
        controller.selectPath(path)
        assertEquals(McaOnboardingStep.MODEL, controller.advance().step)
        return controller
    }

    @Test
    fun `全新安装显示欢迎页`() {
        val store = RecordingOnboardingStore()
        val state = controller(store).load()

        assertEquals(McaOnboardingStep.WELCOME, state.step)
        assertFalse(state.completed)
        assertNull(state.path)
        assertFalse(state.skippedModel)
        assertTrue("欢迎页是唯一无条件允许前进的页面", state.canAdvance)
        assertEquals(1, state.stepIndex)
        assertEquals(4, state.stepCount)
        assertNull("首次读取不构成拒绝", state.rejection)
        assertNull(state.writeFailure)
        assertEquals("读取不能写盘", 0, store.writeCount)
    }

    @Test
    fun `未选路径不能从路径页前进`() {
        val store = RecordingOnboardingStore()
        val controller = controller(store)
        assertEquals(McaOnboardingStep.PATH, controller.advance().step)
        val writesBefore = store.writeCount

        val refused = controller.advance()

        assertEquals("拒绝不能挪动页面", McaOnboardingStep.PATH, refused.step)
        assertEquals(OnboardingRefusal.PATH_NOT_SELECTED, refused.rejection)
        assertFalse(refused.canAdvance)
        assertNull(refused.path)
        assertEquals("拒绝不能写盘", writesBefore, store.writeCount)
        assertEquals(OnboardingStep.PATH.wireName, store.values[OnboardingPreferences.KEY_STEP])
    }

    @Test
    fun `选择体验路径后可以前进到模型页`() {
        val store = RecordingOnboardingStore()
        val controller = controller(store)
        controller.advance()

        val selected = controller.selectPath(OnboardingPath.EXPERIENCE)

        assertEquals("选择不等于翻页", McaOnboardingStep.PATH, selected.step)
        assertEquals(McaOnboardingPathChoice.EXPERIENCE, selected.path)
        assertTrue(selected.canAdvance)
        assertNull(selected.rejection)

        val model = controller.advance()
        assertEquals(McaOnboardingStep.MODEL, model.step)
        assertEquals(McaOnboardingPathChoice.EXPERIENCE, model.path)
        assertEquals(3, model.stepIndex)
        assertNull(model.rejection)
    }

    @Test
    fun `选择生产路径后可以前进到模型页`() {
        val store = RecordingOnboardingStore()
        val controller = controller(store)
        controller.advance()

        val selected = controller.selectPath(OnboardingPath.PRODUCTION)

        assertEquals(McaOnboardingStep.PATH, selected.step)
        assertEquals(McaOnboardingPathChoice.PRODUCTION, selected.path)
        assertTrue(selected.canAdvance)

        val model = controller.advance()
        assertEquals(McaOnboardingStep.MODEL, model.step)
        assertEquals(McaOnboardingPathChoice.PRODUCTION, model.path)
        assertEquals(OnboardingPath.PRODUCTION.wireName, store.values[OnboardingPreferences.KEY_PATH])
    }

    @Test
    fun `跳过模型后进入完成页并记录 skippedModel`() {
        val store = RecordingOnboardingStore()
        val controller = controllerOnModelPage(store)

        val skipped = controller.skipModel()

        assertEquals(McaOnboardingStep.DONE, skipped.step)
        assertTrue(skipped.skippedModel)
        assertFalse("到达完成页不等于引导已完成", skipped.completed)
        assertFalse(skipped.canAdvance)
        assertEquals(4, skipped.stepIndex)
        assertEquals(4, skipped.stepCount)
        assertNull(skipped.rejection)
        assertTrue(store.flags[OnboardingPreferences.KEY_SKIPPED_MODEL] ?: false)
        assertEquals(OnboardingStep.DONE.wireName, store.values[OnboardingPreferences.KEY_STEP])
        assertFalse(
            "完成标记只能由 complete 写入",
            store.flags[OnboardingPreferences.KEY_COMPLETED] ?: false
        )
    }

    @Test
    fun `完成页再前进不会越界`() {
        val store = RecordingOnboardingStore()
        val controller = controllerOnModelPage(store)
        assertEquals(McaOnboardingStep.DONE, controller.advance().step)
        val writesBefore = store.writeCount

        val again = controller.advance()

        assertEquals(McaOnboardingStep.DONE, again.step)
        assertEquals(OnboardingRefusal.FLOW_COMPLETED, again.rejection)
        assertFalse(again.canAdvance)
        assertEquals("越界的前进不能写盘", writesBefore, store.writeCount)
    }

    @Test
    fun `每一步都立刻写盘`() {
        val store = RecordingOnboardingStore()
        val controller = controller(store)

        controller.advance()
        assertEquals(McaOnboardingStep.PATH, restored(store).load().step)
        assertEquals("欢迎页 -> 路径页只有一次写盘", 1, store.writeCount)

        controller.selectPath(OnboardingPath.EXPERIENCE)
        assertEquals(McaOnboardingPathChoice.EXPERIENCE, restored(store).load().path)
        assertEquals(2, store.writeCount)

        controller.advance()
        assertEquals(McaOnboardingStep.MODEL, restored(store).load().step)
        assertEquals(3, store.writeCount)

        controller.skipModel()
        val resumed = restored(store).load()
        assertEquals(McaOnboardingStep.DONE, resumed.step)
        assertTrue("跳过的答案与完成页一起落盘", resumed.skippedModel)
        assertEquals("跳过 = 答案 + 步骤两次写盘", 5, store.writeCount)

        controller.complete()
        assertTrue(restored(store).load().completed)
        assertEquals("完成 = 步骤 + 标记两次写盘", 7, store.writeCount)
    }

    @Test
    fun `进程被杀后恢复到上次所在页`() {
        // The state an interrupted install leaves behind: the step key written, the process gone.
        val store = RecordingOnboardingStore()
        store.values[OnboardingPreferences.KEY_STEP] = OnboardingStep.PATH.wireName

        val state = restored(store).load()

        assertEquals(McaOnboardingStep.PATH, state.step)
        assertFalse(state.completed)
        assertFalse(state.canAdvance)
        assertEquals(2, state.stepIndex)
    }

    @Test
    fun `进程被杀后重新进入仍停在模型页`() {
        val store = RecordingOnboardingStore()
        controllerOnModelPage(store, path = OnboardingPath.PRODUCTION)

        // A brand new controller, i.e. the app relaunched after the process was killed.
        val state = controller(store).load()

        assertEquals(McaOnboardingStep.MODEL, state.step)
        assertEquals(McaOnboardingPathChoice.PRODUCTION, state.path)
        assertTrue(state.canAdvance)
        assertNull(state.rejection)
    }

    @Test
    fun `完成标记与步骤不矛盾`() {
        val store = RecordingOnboardingStore()
        val controller = controllerOnModelPage(store, path = OnboardingPath.PRODUCTION)
        controller.skipModel()
        assertFalse(
            "到达完成页只是页面，标记还没写",
            store.flags[OnboardingPreferences.KEY_COMPLETED] ?: false
        )

        val done = controller.complete()

        assertTrue(done.completed)
        assertEquals(McaOnboardingStep.DONE, done.step)
        assertTrue(store.flags[OnboardingPreferences.KEY_COMPLETED] ?: false)
        assertEquals(
            "完成标记必须与 done 步骤成对出现",
            OnboardingStep.DONE.wireName,
            store.values[OnboardingPreferences.KEY_STEP]
        )
        val reread = restored(store).load()
        assertTrue(reread.completed)
        assertEquals(McaOnboardingStep.DONE, reread.step)
    }

    @Test
    fun `损坏的 step 值回退到欢迎页`() {
        val store = RecordingOnboardingStore()
        store.values[OnboardingPreferences.KEY_STEP] = "finished"

        val state = controller(store).load()

        assertEquals(McaOnboardingStep.WELCOME, state.step)
        assertFalse("损坏的偏好不能让新用户被当成已完成", state.completed)
        assertTrue(state.canAdvance)
        assertEquals(1, state.stepIndex)
    }

    @Test
    fun `reset 后重新显示引导`() {
        val store = RecordingOnboardingStore()
        val controller = controllerOnModelPage(store, path = OnboardingPath.PRODUCTION)
        controller.skipModel()
        assertTrue(controller.complete().completed)

        val fresh = controller.reset()

        assertEquals(McaOnboardingStep.WELCOME, fresh.step)
        assertFalse(fresh.completed)
        assertNull(fresh.path)
        assertFalse(fresh.skippedModel)
        assertTrue(fresh.canAdvance)
        assertEquals(1, fresh.stepIndex)
        assertNull(fresh.rejection)
        assertNull(fresh.writeFailure)
        assertFalse(restored(store).load().completed)
    }

    @Test
    fun `complete 幂等`() {
        val store = RecordingOnboardingStore()
        val controller = controllerOnModelPage(store)
        val first = controller.complete()
        val writesAfterFirst = store.writeCount

        val second = controller.complete()

        assertEquals("重复确认不产生额外写盘", writesAfterFirst, store.writeCount)
        assertEquals(first, second)
        assertTrue(second.completed)
        assertEquals(McaOnboardingStep.DONE, second.step)
        assertNull("重复确认不算拒绝", second.rejection)
    }

    @Test
    fun `canAdvance 在四个页面的取值符合预期`() {
        val store = RecordingOnboardingStore()
        val controller = controller(store)

        val welcome = controller.load()
        assertTrue("欢迎页总能前进", welcome.canAdvance)

        val pathUnanswered = controller.advance()
        assertEquals(McaOnboardingStep.PATH, pathUnanswered.step)
        assertFalse("未选路径的路径页不能前进", pathUnanswered.canAdvance)

        val pathAnswered = controller.selectPath(OnboardingPath.EXPERIENCE)
        assertTrue("选了路径就能前进", pathAnswered.canAdvance)

        val model = controller.advance()
        assertEquals(McaOnboardingStep.MODEL, model.step)
        assertTrue("模型页可以跳过或确认", model.canAdvance)

        val done = controller.advance()
        assertEquals(McaOnboardingStep.DONE, done.step)
        assertFalse("完成页是终态，不能再前进", done.canAdvance)
    }

    @Test
    fun `前进按钮与前进结果始终一致`() {
        // canAdvance is what the primary button renders; advance() is what the tap does. They must agree on
        // every page — including the refused ones — or the first-run flow shows a live button that silently
        // does nothing.
        val store = RecordingOnboardingStore()
        val controller = controller(store)

        fun assertAgrees(page: OnboardingFlowUiState, outcome: OnboardingFlowUiState) {
            assertEquals(
                "页面 ${page.step} 的按钮状态与前进结果不一致",
                page.canAdvance,
                outcome.rejection == null
            )
        }

        val welcome = controller.load()
        val pathPage = controller.advance()
        assertAgrees(welcome, pathPage)
        assertEquals(McaOnboardingStep.PATH, pathPage.step)
        assertFalse("未选路径时主按钮必须是禁用的", pathPage.canAdvance)
        assertAgrees(pathPage, controller.advance())

        val answeredPath = controller.selectPath(OnboardingPath.EXPERIENCE)
        assertTrue(answeredPath.canAdvance)
        val model = controller.advance()
        assertAgrees(answeredPath, model)
        assertEquals(McaOnboardingStep.MODEL, model.step)

        val done = controller.advance()
        assertAgrees(model, done)
        assertEquals(McaOnboardingStep.DONE, done.step)
        assertAgrees(done, controller.advance())
    }

    @Test
    fun `后退逐页回到欢迎页`() {
        val store = RecordingOnboardingStore()
        val controller = controllerOnModelPage(store)

        val onPath = controller.back()
        assertEquals(McaOnboardingStep.PATH, onPath.step)
        assertEquals("后退保留已选路径", McaOnboardingPathChoice.EXPERIENCE, onPath.path)
        assertTrue(onPath.canAdvance)
        assertEquals(OnboardingStep.PATH.wireName, store.values[OnboardingPreferences.KEY_STEP])

        val onWelcome = controller.back()
        assertEquals(McaOnboardingStep.WELCOME, onWelcome.step)
        assertEquals("后退不清除路径答案", McaOnboardingPathChoice.EXPERIENCE, onWelcome.path)
        assertTrue(onWelcome.canAdvance)
        assertEquals(OnboardingStep.WELCOME.wireName, store.values[OnboardingPreferences.KEY_STEP])
    }

    @Test
    fun `欢迎页后退返回自身且不退出流程`() {
        val store = RecordingOnboardingStore()
        val controller = controller(store)
        val writesBefore = store.writeCount

        val state = controller.back()

        assertEquals(McaOnboardingStep.WELCOME, state.step)
        assertEquals(OnboardingRefusal.AT_FIRST_STEP, state.rejection)
        assertFalse(state.completed)
        assertEquals("后退到底不能写盘", writesBefore, store.writeCount)
        assertEquals("流程还在", McaOnboardingStep.WELCOME, restored(store).load().step)
    }

    @Test
    fun `完成页后退不会重新打开流程`() {
        val store = RecordingOnboardingStore()
        val controller = controllerOnModelPage(store)
        controller.complete()
        val writesBefore = store.writeCount

        val state = controller.back()

        assertEquals(McaOnboardingStep.DONE, state.step)
        assertTrue("完成是终态", state.completed)
        assertEquals(OnboardingRefusal.FLOW_COMPLETED, state.rejection)
        assertFalse(state.canAdvance)
        assertEquals(writesBefore, store.writeCount)
        assertTrue(restored(store).load().completed)
    }

    @Test
    fun `完成态不再接受路径选择`() {
        val store = RecordingOnboardingStore()
        val controller = controllerOnModelPage(store)
        controller.complete()
        val writesBefore = store.writeCount

        val state = controller.selectPath(OnboardingPath.EXPERIENCE)

        assertEquals(OnboardingRefusal.FLOW_COMPLETED, state.rejection)
        assertTrue(state.completed)
        assertEquals(writesBefore, store.writeCount)
    }

    @Test
    fun `未在模型页时记录模型答案会被拒绝`() {
        val store = RecordingOnboardingStore()
        val controller = controller(store)
        controller.advance()
        val writesBefore = store.writeCount

        val skipped = controller.skipModel()
        val ready = controller.markModelReady()

        assertEquals(McaOnboardingStep.PATH, skipped.step)
        assertEquals(OnboardingRefusal.NOT_ON_MODEL_STEP, skipped.rejection)
        assertEquals(OnboardingRefusal.NOT_ON_MODEL_STEP, ready.rejection)
        assertEquals(writesBefore, store.writeCount)
        assertFalse(store.flags[OnboardingPreferences.KEY_SKIPPED_MODEL] ?: false)
    }

    @Test
    fun `标记模型已就绪会清掉之前的跳过记录`() {
        val store = RecordingOnboardingStore()
        // A resumed install that recorded a skip but is still standing on the model page.
        store.values[OnboardingPreferences.KEY_STEP] = OnboardingStep.MODEL.wireName
        store.values[OnboardingPreferences.KEY_PATH] = OnboardingPath.EXPERIENCE.wireName
        store.flags[OnboardingPreferences.KEY_SKIPPED_MODEL] = true
        val controller = controller(store)
        assertEquals(McaOnboardingStep.MODEL, controller.load().step)
        assertTrue(controller.load().skippedModel)

        val ready = controller.markModelReady()

        assertEquals(McaOnboardingStep.DONE, ready.step)
        assertFalse("已就绪的答案覆盖之前的跳过", ready.skippedModel)
        assertFalse(store.flags[OnboardingPreferences.KEY_SKIPPED_MODEL] ?: false)
        assertEquals(OnboardingStep.DONE.wireName, store.values[OnboardingPreferences.KEY_STEP])
    }

    @Test
    fun `写盘失败不崩溃并给出错误通道`() {
        val store = RecordingOnboardingStore()
        val failing = FailingPreferenceStore(store, failOn = { key -> key == OnboardingPreferences.KEY_STEP })
        val controller = controller(failing)

        val state = controller.advance()

        assertEquals("写盘失败不能让流程卡死", McaOnboardingStep.PATH, state.step)
        assertNull("合法操作不算拒绝", state.rejection)
        val failure = requireNotNull(state.writeFailure) { "写盘失败必须可判定" }
        assertEquals(OnboardingWriteTarget.STEP, failure.target)
        assertEquals("disk full", failure.reason)
        assertNull("失败的写盘不能落盘", store.values[OnboardingPreferences.KEY_STEP])
    }

    @Test
    fun `完成时写盘失败也不崩溃`() {
        val store = RecordingOnboardingStore()
        var failing = false
        val guarded = FailingPreferenceStore(store, failOn = { failing })
        val controller = controller(guarded)
        controller.advance()
        controller.selectPath(OnboardingPath.EXPERIENCE)
        controller.advance()
        controller.skipModel()
        failing = true

        val state = controller.complete()

        assertTrue("内存里仍然完成，用户不会被卡住", state.completed)
        val failure = requireNotNull(state.writeFailure) { "完成写盘失败必须可判定" }
        assertEquals(OnboardingWriteTarget.COMPLETION, failure.target)
        assertFalse("没写成功就不能说已经落盘", store.flags[OnboardingPreferences.KEY_COMPLETED] ?: false)
    }

    @Test
    fun `落盘写入顺序不会产生非法的完成组合`() {
        // Any point at which the process could be killed must read back as a legal flow state. The snapshot
        // store reports the raw keys after every single write, which is exactly those points.
        val frozen = mutableListOf<RecordingOnboardingStore>()
        val store = SnapshottingStore { values, flags ->
            frozen += RecordingOnboardingStore().also {
                it.values.putAll(values)
                it.flags.putAll(flags)
            }
        }
        val controller = controller(store)
        controller.advance()
        controller.selectPath(OnboardingPath.EXPERIENCE)
        controller.advance()
        controller.skipModel()
        controller.complete()

        assertEquals("每个写盘点都要有快照", 7, frozen.size)
        frozen.forEach { snapshot ->
            val completed = snapshot.flags[OnboardingPreferences.KEY_COMPLETED] ?: false
            if (completed) {
                assertEquals(
                    "completed=true 时步骤必须是 done",
                    OnboardingStep.DONE.wireName,
                    snapshot.values[OnboardingPreferences.KEY_STEP]
                )
            }
            // Whatever the freeze point, the codec hands back a readable state instead of throwing.
            val state = OnboardingPreferences(snapshot).load()
            assertFalse(
                "冻结点不能读成 completed 与非 done 的组合",
                state.completed && state.step != OnboardingStep.DONE
            )
        }
    }

    /** Store that reports a snapshot of the raw keys after every write, i.e. a kill at that exact moment. */
    private class SnapshottingStore(
        private val onWrite: (Map<String, String>, Map<String, Boolean>) -> Unit
    ) : OnboardingPreferenceStore {
        private val values = LinkedHashMap<String, String>()
        private val flags = LinkedHashMap<String, Boolean>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
            onWrite(values, flags)
        }

        override fun readBoolean(key: String): Boolean = flags[key] ?: false

        override fun writeBoolean(key: String, value: Boolean) {
            flags[key] = value
            onWrite(values, flags)
        }
    }
}

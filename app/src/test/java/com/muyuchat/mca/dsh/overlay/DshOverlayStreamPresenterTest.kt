package com.muyuchat.mca.dsh.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presenter: the switch, the render throttle and the 300 ms end debounce.
 *
 * A recording `onState` stands in for the service, which is how "the window was asked to redraw exactly
 * this" is asserted without Compose.
 */
class DshOverlayStreamPresenterTest {

    private class Recorder {
        val states = mutableListOf<DshOverlayStream>()
        var clock = 1_000L

        fun presenter(enabled: Boolean = true) = DshOverlayStreamPresenter(
            onState = { states += it },
            streamingEnabled = { enabled },
            clock = { clock }
        )
    }

    @Test
    fun deltasPushStreamingStatesWithTheTailKept() {
        val recorder = Recorder()
        val presenter = recorder.presenter()

        presenter.onDelta("你好")
        recorder.clock += 150
        presenter.onDelta("，世界")

        assertEquals(2, recorder.states.size)
        val last = recorder.states.last() as DshOverlayStream.Streaming
        assertEquals("你好，世界", last.text)
        assertEquals(1_000L, last.startedAtMs)
    }

    @Test
    fun aFastStreamIsThrottledButTheStateStillHasEveryCharacter() {
        val recorder = Recorder()
        val presenter = recorder.presenter()

        repeat(30) { index ->
            recorder.clock += 10
            presenter.onDelta(if (index == 0) "起" else "字")
        }

        assertTrue("pushes are throttled: ${recorder.states.size}", recorder.states.size < 30)
        val state = presenter.state as DshOverlayStream.Streaming
        assertEquals("起" + "字".repeat(29), state.text)
    }

    @Test
    fun aToolStartPushesOneToolState() {
        val recorder = Recorder()
        val presenter = recorder.presenter()

        presenter.onDelta("先想一下")
        presenter.onToolStart("read_file")

        val last = recorder.states.last()
        assertEquals(DshOverlayStream.ToolRunning(label = "读取文件"), last)
    }

    @Test
    fun theTurnEndPushesTheFinishedState() {
        val recorder = Recorder()
        val presenter = recorder.presenter()

        presenter.onDelta("一段回答")
        presenter.onTurnEnd("一段回答")

        assertEquals(DshOverlayStream.Finished("一段回答"), recorder.states.last())
    }

    @Test
    fun aNewStreamIsDebouncedForThreeHundredMillisecondsAfterTheEnd() {
        val recorder = Recorder()
        val presenter = recorder.presenter()

        presenter.onDelta("第一轮")
        presenter.onTurnEnd("第一轮")
        recorder.clock += 100

        presenter.onDelta("第二轮")
        assertEquals(
            "a new stream must not start inside the debounce window",
            DshOverlayStream.Finished("第一轮"),
            presenter.state
        )

        recorder.clock += 250
        presenter.onDelta("第二轮")
        assertTrue(presenter.state is DshOverlayStream.Streaming)
    }

    @Test
    fun anAbortSettlesToIdle() {
        val recorder = Recorder()
        val presenter = recorder.presenter()

        presenter.onDelta("半截回答")
        presenter.onAbort()

        assertEquals(DshOverlayStream.Idle, presenter.state)
        assertEquals(DshOverlayStream.Idle, recorder.states.last())
    }

    @Test
    fun anAbortWhileIdleDoesNotPokeTheWindow() {
        val recorder = Recorder()
        val presenter = recorder.presenter()

        presenter.onAbort()

        assertTrue(recorder.states.isEmpty())
        assertEquals(DshOverlayStream.Idle, presenter.state)
    }

    @Test
    fun streamingOffMeansTheReducerIsNeverReached() {
        val recorder = Recorder()
        val presenter = recorder.presenter(enabled = false)

        presenter.onDelta("不应该出现")
        presenter.onDelta("也不应该出现")
        presenter.onToolStart("shell")
        presenter.onTurnEnd("结束")
        presenter.onAbort()

        assertTrue("no state was pushed: ${recorder.states}", recorder.states.isEmpty())
        assertEquals("the reducer never ran", DshOverlayStream.Idle, presenter.state)
    }

    @Test
    fun aDisabledSwitchAlsoStopsTheClockFromAdvancingTheDebounce() {
        val recorder = Recorder()
        var enabled = true
        val presenter = DshOverlayStreamPresenter(
            onState = { recorder.states += it },
            streamingEnabled = { enabled },
            clock = { recorder.clock }
        )

        presenter.onDelta("第一轮")
        presenter.onTurnEnd("第一轮")
        enabled = false
        recorder.clock += 10
        presenter.onDelta("第二轮")

        // Switching live text off does not start a new stream behind the user's back.
        assertTrue(presenter.state is DshOverlayStream.Finished)
        assertEquals(2, recorder.states.size)
    }

    @Test
    fun resetForgetsTheHalfTurnWithoutPushingAnything() {
        val recorder = Recorder()
        val presenter = recorder.presenter()

        presenter.onDelta("半截回答")
        recorder.states.clear()
        presenter.reset()

        assertEquals(DshOverlayStream.Idle, presenter.state)
        assertTrue(recorder.states.isEmpty())
    }
}

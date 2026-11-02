package com.muyuchat.core.dsh.vscreen

/**
 * A preview surface for the virtual display.
 *
 * Implementations render the newest captured frame; none of them receives a token, a path or a
 * socket, so the preview cannot leak a credential. A window implementation must degrade to a no-op
 * when the platform permission is missing instead of failing the capture path.
 */
interface DshVscreenPreviewWindow {
    val available: Boolean

    fun show(displayId: Int, frameWidth: Int, frameHeight: Int)

    fun render(frame: DshVscreenFrame)

    fun hide()
}

/** Read-only projection of the preview relay for status and tests. */
data class DshVscreenPreviewState(
    val displayId: Int,
    val hasFrame: Boolean,
    val width: Int,
    val height: Int,
    val capturedAtMillis: Long?,
    val ageMillis: Long?,
    val deliveredFrames: Long,
    val droppedFrames: Long,
    val windowAvailable: Boolean,
    val lastStopReason: String?
)

/**
 * In-process preview owner.
 *
 * It keeps exactly one bounded frame (`MAX_FRAME_BYTES`), forwards it to the optional window, and
 * clears it when the owner reports the display stopped. Frames are never persisted here (the frame
 * sink does that for `see`), never logged, and never exposed over a socket: the preview is a local
 * rendering aid only.
 */
class DshVscreenPreviewRelay(
    private val window: DshVscreenPreviewWindow? = null,
    private val maxFrameBytes: Int = DshVscreenLimits.MAX_FRAME_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : DshVscreenPreviewPort {

    private val lock = Any()

    private var current: Snapshot? = null
    private var deliveredFrames: Long = 0L
    private var droppedFrames: Long = 0L
    private var lastStopReason: String? = null

    override fun onFrame(displayId: Int, frame: DshVscreenFrame, deliveredAtMillis: Long) {
        if (frame.bytes.isEmpty() ||
            frame.bytes.size > maxFrameBytes ||
            frame.width <= 0 ||
            frame.height <= 0
        ) {
            synchronized(lock) { droppedFrames += 1L }
            return
        }
        synchronized(lock) {
            current = Snapshot(displayId, frame, deliveredAtMillis)
            deliveredFrames += 1L
        }
        forwardToWindow(displayId, frame)
    }

    override fun onDisplayStopped(displayId: Int, reason: String) {
        synchronized(lock) {
            if (current?.displayId == displayId) current = null
            lastStopReason = reason
        }
        runCatching { window?.hide() }
    }

    /** Latest admitted frame, or null when nothing was delivered for the active display. */
    fun latest(): DshVscreenFrame? = synchronized(lock) { current?.frame }

    fun state(): DshVscreenPreviewState {
        val snapshot = synchronized(lock) { current }
        val now = clock()
        return DshVscreenPreviewState(
            displayId = snapshot?.displayId ?: -1,
            hasFrame = snapshot != null,
            width = snapshot?.frame?.width ?: 0,
            height = snapshot?.frame?.height ?: 0,
            capturedAtMillis = snapshot?.frame?.capturedAtMillis,
            ageMillis = snapshot?.let { (now - it.deliveredAtMillis).coerceAtLeast(0L) },
            deliveredFrames = synchronized(lock) { deliveredFrames },
            droppedFrames = synchronized(lock) { droppedFrames },
            windowAvailable = window?.available == true,
            lastStopReason = synchronized(lock) { lastStopReason }
        )
    }

    private fun forwardToWindow(displayId: Int, frame: DshVscreenFrame) {
        val target = window ?: return
        if (!target.available) return
        try {
            target.show(displayId, frame.width, frame.height)
            target.render(frame)
        } catch (failure: RuntimeException) {
            // A preview window failure must never fail the capture path; the frames keep flowing.
        }
    }

    private class Snapshot(
        val displayId: Int,
        val frame: DshVscreenFrame,
        val deliveredAtMillis: Long
    )
}

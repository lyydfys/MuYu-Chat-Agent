package com.muyuchat.feature.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R07.2: the full-screen preview exit owner. Drives the real [ImagePreviewActionGate] state machine
 * (no source-text assertions, no Compose/Android runtime needed).
 */
class ImagePreviewActionGateTest {
    @Test
    fun `repeated back during the exit is consumed exactly once`() {
        val gate = ImagePreviewActionGate()

        assertTrue("the first Back owns the played-out exit", gate.requestClose())
        assertTrue(gate.isClosing)
        assertFalse("a second Back must be consumed, not restart the exit", gate.requestClose())
        assertFalse(gate.requestClose())
        assertTrue("the exit keeps running to its end", gate.isClosing)
    }

    @Test
    fun `closing refuses every new business action`() {
        val gate = ImagePreviewActionGate()
        val businessActions = enumValues<ImagePreviewAction>()
            .filterNot { it == ImagePreviewAction.SAVE }
        businessActions.forEach { action ->
            assertTrue("$action must be available before closing", gate.allows(action))
        }

        gate.requestClose()

        businessActions.forEach { action ->
            assertFalse("$action must be refused while the exit is playing", gate.allows(action))
        }
        // Closing stays available as the escape hatch, but it never starts a second exit.
        assertFalse(gate.requestClose())
    }

    @Test
    fun `closing collapses the overflow submenu and it cannot reopen`() {
        val gate = ImagePreviewActionGate()
        gate.openMenu()
        assertTrue(gate.isMenuOpen)

        gate.requestClose()

        assertFalse("closing must collapse the submenu", gate.isMenuOpen)
        gate.openMenu()
        assertFalse("the submenu must stay collapsed while closing", gate.isMenuOpen)
    }

    @Test
    fun `save is refused while another save is in flight even before closing`() {
        val gate = ImagePreviewActionGate()
        assertTrue(gate.beginSave())
        assertFalse(gate.allows(ImagePreviewAction.SAVE))
        assertTrue("other actions are untouched by an in-flight save", gate.allows(ImagePreviewAction.FAVORITE))
        gate.finishSave()

        assertTrue(gate.allows(ImagePreviewAction.SAVE))
        assertTrue("after finishing, a new save may start again", gate.beginSave())
    }

    @Test
    fun `save is never resubmitted by the visual exit`() {
        val gate = ImagePreviewActionGate()
        assertTrue(gate.beginSave())
        assertTrue(gate.isSaving)
        assertFalse("a second tap while saving must not start a second write", gate.beginSave())

        assertTrue(gate.requestClose())
        assertFalse("the exit must not submit another save", gate.beginSave())
        gate.finishSave()
        assertFalse(gate.isSaving)
        assertFalse("after the exit started a new save stays refused", gate.beginSave())
        assertFalse(gate.allows(ImagePreviewAction.SAVE))
    }

    @Test
    fun `a save started before closing settles once while the exit completes`() = runTest {
        val gate = ImagePreviewActionGate()
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var submitted = 0
        var settled = 0
        val saveOwner = launch {
            if (gate.beginSave()) {
                submitted += 1
                try {
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                } finally {
                    settled += 1
                    gate.finishSave()
                }
            }
        }
        writeStarted.await()

        assertTrue(gate.requestClose())
        assertFalse("a tap during the exit must be refused", gate.beginSave())

        releaseWrite.complete(Unit)
        saveOwner.join()

        assertEquals("the save is submitted once", 1, submitted)
        assertEquals("the started save settles itself once", 1, settled)
        assertFalse(gate.isSaving)
        assertTrue(gate.isClosing)
    }

    @Test
    fun `the normal path is not blocked while the preview is open`() {
        val gate = ImagePreviewActionGate()
        enumValues<ImagePreviewAction>().forEach { action ->
            assertTrue("$action must be allowed before closing", gate.allows(action))
        }
        gate.openMenu()
        assertTrue(gate.isMenuOpen)
        gate.closeMenu()
        assertFalse(gate.isMenuOpen)
        assertFalse(gate.isClosing)
        assertFalse(gate.isSaving)
    }
}

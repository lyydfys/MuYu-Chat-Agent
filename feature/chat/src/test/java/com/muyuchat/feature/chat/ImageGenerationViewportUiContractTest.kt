package com.muyuchat.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageGenerationViewportUiContractTest {
    @Test
    fun `mask editor shares one viewport across image drawing overlay and positioned cursor`() {
        val source = sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ImageGenerationEditor.kt"
        )

        assertTrue(source.contains("private enum class MaskEditorInteractionMode"))
        assertTrue(source.contains("detectTransformGestures"))
        assertTrue(source.contains("viewportState = transform.stateAfterGesture("))
        assertTrue(source.contains("fun currentDrawingTransform(): ImageEditingViewportTransform"))
        assertTrue(source.contains("currentDrawingTransform().viewToNormalized("))
        assertTrue(source.contains(".graphicsLayer {"))
        assertTrue(source.contains("viewportState = viewportState,"))
        assertTrue(source.contains("val transform = ImageEditingViewportTransform.create("))
        assertTrue(source.contains("positionedCursor?.let { cursor ->"))
    }

    @Test
    fun `viewport controls are save disabled and do not participate in mask dirty history`() {
        val source = sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ImageGenerationEditor.kt"
        )

        assertTrue(source.contains("val dirty = maskState != emptyMaskState || activeStroke != null"))
        assertFalse(source.contains("val dirty = viewportState"))
        assertTrue(source.contains("onClick = { interactionMode = MaskEditorInteractionMode.DRAW }"))
        assertTrue(source.contains("onClick = { interactionMode = MaskEditorInteractionMode.PAN }"))
        assertTrue(source.contains("enabled = viewportState.zoom > ImageEditingViewportState.MIN_ZOOM && !saving"))
        assertTrue(source.contains("enabled = viewportState.zoom < ImageEditingViewportState.MAX_ZOOM && !saving"))
        assertTrue(source.contains("onClick = { viewportState = ImageEditingViewportState() }"))
        assertTrue(source.contains("ImageGenerationBitmapEditing.renderMask("))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Unable to locate source file: $relativePath")
    }
}

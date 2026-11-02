package com.muyuchat.feature.video.workspace

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-2 accessibility guard (98a round3 findings, 2026-10-26):
 * - the 11 unlabeled clickable containers in the node-list sheet were the parameter / connect
 *   buttons, whose own merged semantics carried no label;
 * - port dots announced only "输出端口 x"/"输入端口 x" without the node they belong to.
 *
 * Round v1-08 guard (98a round6 findings, 2026-10-28):
 * - 18 clickable containers on the video page had neither text nor contentDescription because the
 *   label lived in a child node; every video button must now declare its name on the container
 *   through `A11yLabeledControl`;
 * - the DiT output port's accessibility bounds were clipped to 82x132px (29.8x48dp) because the
 *   48dp clickable box was placed half outside the node card; the box must stay inside the card
 *   with a >=48dp hit target while the 16dp dot keeps its old centre.
 *
 * These are source-contract assertions because the real a11y tree is verified on device; the
 * tests keep a future edit from silently dropping the labels or shrinking the port target again.
 */
class VideoAccessibilityLabelsContractTest {

    /** Video surfaces whose functional controls must name their own clickable container. */
    private val labelledControlSources = listOf(
        "ui/VideoWorkspaceScreen.kt",
        "ui/VideoGraphCanvas.kt",
        "ui/VideoModelAssetsSection.kt",
        "ui/VideoRunStatusSection.kt",
        "ui/RemoteComfySection.kt",
        "ui/VideoNodeSheet.kt",
        "ui/VideoImportDialog.kt",
        "ui/VideoLibraryComponents.kt",
        "ui/VideoLibraryOverlay.kt",
        "ui/VideoPlayerDialog.kt",
    )

    @Test
    fun `node list buttons and ports carry a spoken label`() {
        val canvas = source("ui/VideoGraphCanvas.kt")
        assertTrue(
            "node parameter button needs an explicit label",
            canvas.contains("\"\${VideoNodePresentations.labelOf(node.typeId)} 参数\""),
        )
        assertTrue(
            "connect-out button needs an explicit label",
            canvas.contains("从 \${port.name} 连出\""),
        )
        assertTrue(
            "connect-target button needs an explicit label",
            canvas.contains("连接 \${VideoNodePresentations.labelOf(target.typeId)} 的 \${port.name} 输入端口"),
        )
        assertTrue(
            "output port label must name its node",
            canvas.contains("\"\${VideoNodePresentations.labelOf(node.typeId)} 输出端口 \${port.name}\""),
        )
        assertTrue(
            "input port label must name its node",
            canvas.contains("\"\${VideoNodePresentations.labelOf(node.typeId)} 输入端口 \${port.name}\""),
        )
    }

    @Test
    fun `every video button labels the clickable container instead of a child node`() {
        labelledControlSources.forEach { relative ->
            val text = source(relative)
            listOf("McaButton(", "TextButton(").forEach { buttonCall ->
                var index = text.indexOf(buttonCall)
                while (index >= 0) {
                    val before = text.substring(0, index)
                    val wrapper = before.lastIndexOf("A11yLabeledControl(")
                    val previousButton = maxOf(
                        before.lastIndexOf("McaButton("),
                        before.lastIndexOf("TextButton("),
                    )
                    val position = "$relative @${index} ($buttonCall)"
                    assertTrue(
                        "$position: the clickable container must be wrapped in A11yLabeledControl",
                        wrapper >= 0 && wrapper > previousButton,
                    )
                    val declaration = text.substring(wrapper, index)
                    assertTrue(
                        "$position: the wrapper must declare the spoken name on the container",
                        declaration.contains("contentDescription ="),
                    )
                    index = text.indexOf(buttonCall, index + buttonCall.length)
                }
            }
        }
    }

    @Test
    fun `button labels are no longer attached to a child Text`() {
        val canvas = source("ui/VideoGraphCanvas.kt")
        assertFalse(
            "canvas tool button must not hide its label in the inner Text",
            canvas.contains("Text(label, modifier = Modifier.semantics { contentDescription = description })"),
        )
        labelledControlSources.forEach { relative ->
            val text = source(relative)
            assertFalse(
                "$relative must not put contentDescription on the button's own modifier",
                Regex("""McaButton\([\s\S]{0,400}?modifier\s*=\s*Modifier\s*\.\s*semantics\s*\{""").containsMatchIn(text),
            )
        }
    }

    @Test
    fun `ports keep a 48dp hit target fully inside the node card`() {
        val canvas = source("ui/VideoGraphCanvas.kt")
        val constant = Regex("""private val PORT_HIT_DP = (\d+(?:\.\d+)?)\.dp""").find(canvas)
        assertTrue("PORT_HIT_DP constant must exist", constant != null)
        assertTrue(
            "port hit target constant must be at least 48dp, was ${constant!!.groupValues[1]}dp",
            constant.groupValues[1].toFloat() >= 48f,
        )
        val portStart = canvas.indexOf("private fun BoxScope.PortDot")
        assertTrue("PortDot must exist", portStart >= 0)
        val portBlock = canvas.substring(portStart, minOf(canvas.length, portStart + 2400))
        assertTrue(
            "port label must be declared on the clickable container through A11yLabeledControl",
            portBlock.contains("A11yLabeledControl(") &&
                portBlock.contains("contentDescription = contentDescription") &&
                portBlock.contains("clickActionLabel = \"连接端口\""),
        )
        assertTrue(
            "port state (selected) must stay on the same container",
            portBlock.contains("stateDescription = if (highlight) \"已选择\" else null"),
        )
        assertTrue("port keeps its real touch container", portBlock.contains(".clickable("))
        assertTrue(
            "port touch box must be exactly the >=48dp target",
            portBlock.contains(".size(PORT_HIT_DP)"),
        )
        assertTrue(
            "port clickable box must enforce the >=48dp minimum",
            portBlock.contains(".sizeIn(minWidth = PORT_HIT_DP, minHeight = PORT_HIT_DP)"),
        )
        assertTrue(
            "port clickable box must sit inside the card (no outward 24dp offset)",
            canvas.contains("offsetDp = Offset(0f, NODE_PORT_BASE_DP + NODE_PORT_STEP_DP * index - 24f)"),
        )
        assertFalse(
            "port clickable box must not be offset outside the card again",
            Regex("""offsetDp = Offset\(\s*-?24f?[,)]""").containsMatchIn(canvas),
        )
        assertTrue(
            "the visible dot must keep its old centre on the card edge",
            canvas.contains("dotOffsetX = PORT_HIT_DP / 2f") &&
                canvas.contains("dotOffsetX = -PORT_HIT_DP / 2f"),
        )
    }

    @Test
    fun `drawer image entry has the same spoken-label treatment as agent dsh and video`() {
        val drawer = source("/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatHistoryDrawerScreens.kt")
        val imageEntry = drawer.indexOf("text = \"图片\"")
        assertTrue("image entry must exist", imageEntry >= 0)
        val block = drawer.substring(imageEntry, minOf(imageEntry + 400, drawer.length))
        assertTrue("image entry needs its accessibility label", block.contains("accessibilityLabel = \"打开图片工作区\""))
        assertTrue("image entry needs a stable test tag", block.contains("testTag = \"drawer.images\""))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/muyuchat/feature/video/workspace/$relative"),
            File("feature/video-workspace/src/main/java/com/muyuchat/feature/video/workspace/$relative"),
            File(relative.removePrefix("/")),
            // Feature-module test tasks run with the module directory as CWD.
            File("..${relative}"),
            File("../..${relative}"),
        )
        val file = candidates.firstOrNull { it.isFile }
        checkNotNull(file) { "source file not found: $relative (cwd=${File(".").canonicalPath})" }
        return file.readText()
    }
}

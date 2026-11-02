package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the R12 accessibility finding: the composer-menu scrim was a full-screen clickable with no
 * accessible name, so TalkBack exposed it as an unlabelled control. It stays clickable (it is the
 * dismiss affordance) but must carry a name and a click label.
 *
 * Found by `scripts/audit-a11y-naming.ps1` over real device dumps, not by reading the code.
 */
class ChatComposerMenuScrimA11yContractTest {
    @Test
    fun `composer menu scrim is named and its click action is labelled`() {
        val source = source("ChatComposerActionMenuViews.kt")

        // The scrim must be a semantic target with a name, and the same block must pass an
        // onClickLabel so the announced action is not just "active".
        assertTrue(
            "scrim must expose a content description",
            source.contains("contentDescription = \"关闭菜单\"")
        )
        assertTrue(
            "the scrim click must carry the shared close vocabulary",
            source.contains("clickable(onClickLabel = \"关闭\")")
        )

        // The name and the label must sit on the scrim that covers the layer, not on some other
        // clickable: verify they appear inside the same `if (menu.isComposed)` block.
        val block = scrimBlock(source)
        assertTrue("the named scrim must live in the menu layer block", block.contains("contentDescription = \"关闭菜单\""))
        assertTrue("the scrim click label must live in the menu layer block", block.contains("onClickLabel = \"关闭\""))
        assertTrue("the scrim must still dismiss the menu", block.contains("menu.dismiss()"))
    }

    private fun scrimBlock(source: String): String {
        val start = source.indexOf("if (menu.isComposed)")
        check(start >= 0) { "expected an `if (menu.isComposed)` scrim block" }
        val end = source.indexOf("// R05", start).let { if (it < 0) source.length else it }
        return source.substring(start, end)
    }

    private fun source(fileName: String): String {
        val candidates = listOf(
            File("src/main/java/com/muyuchat/feature/chat/$fileName"),
            File("feature/chat/src/main/java/com/muyuchat/feature/chat/$fileName")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("cannot locate $fileName from ${File(".").absolutePath}")
        return file.readText(Charsets.UTF_8)
    }
}

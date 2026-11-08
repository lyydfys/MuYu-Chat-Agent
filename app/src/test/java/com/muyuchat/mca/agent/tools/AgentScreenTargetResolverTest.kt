package com.muyuchat.mca.agent.tools

import com.muyuchat.core.dsh.a11y.policy.A11yNode
import com.muyuchat.core.dsh.a11y.policy.A11yNodeRef
import com.muyuchat.core.dsh.a11y.policy.A11yRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the screen tool's "I could not find that control" self-rescue.
 *
 * The exact-match reader stays authoritative; this only has to answer the question a failed tap
 * leaves open — *what did the model probably mean?* — with a short, ranked, bounded list. The cases
 * therefore pin ranking (exact beats contains beats fuzzy), the negative path (no candidates beats a
 * wrong candidate), and that a candidate line is actionable (it carries the bounds a coordinate tap
 * needs).
 */
class AgentScreenTargetResolverTest {

    @Test
    fun `an exact match outranks a containment match`() {
        val nodes = listOf(
            node(0, "搜索联系人"),          // contains "搜索"
            node(1, "搜索"),                // exact
            node(2, "搜索好友")             // contains, deeper capture order
        )

        val resolved = AgentScreenTargetResolver.resolve("搜索", nodes)

        assertEquals(3, resolved.size)
        assertEquals("exact first", "搜索", resolved[0].text)
        assertEquals("containment second", "搜索联系人", resolved[1].text)
        assertEquals("containment keeps capture order", "搜索好友", resolved[2].text)
    }

    @Test
    fun `a lookalike that merely resembles the needle is not offered`() {
        val nodes = listOf(
            node(0, "搜索联系人"),
            // Shares the character "搜" with the needle but is a different control; a fuzzy grade
            // cannot separate it from a genuine near-miss, so it must not become a candidate.
            node(1, "搜图"),
            node(2, "联系人")
        )

        val resolved = AgentScreenTargetResolver.resolve("搜索", nodes)

        assertEquals(listOf("搜索联系人"), resolved.map { it.text })
    }

    @Test
    fun `matching ignores case and surrounding or inner whitespace`() {
        val nodes = listOf(
            node(0, "  Search   Contacts "),
            node(1, "Log In")
        )

        assertTrue(
            "case and whitespace must not matter",
            AgentScreenTargetResolver.resolve("search contacts", nodes).single().text == "  Search   Contacts "
        )
        assertTrue(AgentScreenTargetResolver.resolve("  LOG   IN  ", nodes).single().text == "Log In")
    }

    @Test
    fun `no resemblance returns no candidates instead of the closest guess`() {
        val nodes = listOf(
            node(0, "WLAN"),
            node(1, "蓝牙"),
            node(2, "电池")
        )

        assertTrue(AgentScreenTargetResolver.resolve("微信", nodes).isEmpty())
        assertTrue(AgentScreenTargetResolver.resolve("", nodes).isEmpty())
        assertTrue(AgentScreenTargetResolver.resolve("搜索", emptyList()).isEmpty())
    }

    @Test
    fun `at most five candidates are returned`() {
        val nodes = (0 until 40).map { index -> node(index, "搜索项 $index") }

        val resolved = AgentScreenTargetResolver.resolve("搜索", nodes)

        assertEquals(AgentScreenTargetResolver.MAX_CANDIDATES, resolved.size)
        assertEquals(5, resolved.size)
        // Deterministic: the same tree must always yield the same list.
        assertEquals(
            resolved.map { it.index },
            AgentScreenTargetResolver.resolve("搜索", nodes).map { it.index }
        )
    }

    @Test
    fun `a candidate line carries bounds so a coordinate tap is possible`() {
        val nodes = listOf(
            node(3, "设置", x = 40, y = 120, w = 300, h = 88, clickable = true)
        )

        val text = AgentScreenTargetResolver.describeCandidatesOf("设置", nodes)

        assertFalse(text.isEmpty())
        assertTrue("index must be addressable", text.contains("index=3"))
        assertTrue("pixel bounds must be present", text.contains("bounds=40,120 300x88"))
        assertTrue(text.contains("text=\"设置\""))
        assertTrue("actionability must be visible", text.contains("(tap)"))
        assertTrue(text.contains("match=exact/text"))
    }

    @Test
    fun `an empty candidate list describes as an empty string`() {
        assertEquals("", AgentScreenTargetResolver.describeCandidates(emptyList()))
        assertEquals("", AgentScreenTargetResolver.describeCandidatesOf("微信", listOf(node(0, "WLAN"))))
    }

    @Test
    fun `a sentence-shaped goal finds the control it names`() {
        val nodes = listOf(
            node(0, "首页"),
            node(1, "搜索", clickable = true),
            node(2, "我的")
        )

        // The model's original goal, not a label: the resolver must still reach the button.
        val resolved = AgentScreenTargetResolver.resolve("帮我点开「搜索」按钮", nodes)

        assertEquals("搜索", resolved.first().text)
    }

    @Test
    fun `description route resolves icon-only rows whose text is empty`() {
        val nodes = listOf(
            node(0, "", desc = "返回上一页", clickable = true),
            node(1, "", desc = "更多选项", clickable = true)
        )

        val resolved = AgentScreenTargetResolver.resolve("返回上一页", nodes)

        assertEquals(1, resolved.size)
        assertEquals("返回上一页", resolved.single().desc)
    }

    @Test
    fun `a password field is never offered as a candidate`() {
        val nodes = listOf(
            node(0, "输入密码", password = true),
            node(1, "输入邮箱")
        )

        val resolved = AgentScreenTargetResolver.resolve("输入", nodes)

        assertEquals(1, resolved.size)
        assertFalse("redacted credentials must not be tappable candidates", resolved.single().password)
    }

    @Test
    fun `actionable nodes win a tie inside the same match kind`() {
        val nodes = listOf(
            node(0, "搜索", clickable = false),
            node(1, "搜索", clickable = true)
        )

        val resolved = AgentScreenTargetResolver.resolve("搜索", nodes)

        assertEquals("the clickable twin must come first", 1, resolved.first().index)
    }

    @Test
    fun `explicit text id and description targets all match`() {
        val nodes = listOf(
            node(0, "确定", cls = "android.widget.Button"),
            node(1, "", desc = "确定按钮", cls = "android.widget.ImageButton")
        )

        val byText = AgentScreenTargetResolver.resolve(ScreenTarget(byText = listOf("确定")), nodes)
        // Only node 0 has text; node 1 is reached through its description, which is exactly why
        // resolve(goal) sends its needles down both routes.
        assertEquals(listOf(0), byText.map { it.index })

        // The a11y node model has no viewId, so the id route compares the class name. The two nodes
        // deliberately have different classes here, so the route can tell them apart.
        val byId = AgentScreenTargetResolver.resolve(
            ScreenTarget(byId = listOf("android.widget.button")), nodes
        )
        assertEquals(listOf(0), byId.map { it.index })

        val byDesc = AgentScreenTargetResolver.resolve(
            ScreenTarget(byDescription = listOf("确定按钮")), nodes
        )
        assertEquals(listOf(1), byDesc.map { it.index })
    }

    @Test
    fun `a near-miss word returns nothing rather than the wrong control`() {
        // "登陆" vs "登录": one character differs with no containment either way. The resolver
        // deliberately does not guess here — a wrong control is worse than no candidate — so the
        // model is told to retry with a different word instead of tapping blindly.
        val nodes = listOf(
            node(0, "登录", clickable = true),
            node(1, "注册", clickable = true)
        )

        assertTrue(AgentScreenTargetResolver.resolve("登陆", nodes).isEmpty())
        // The same graph still resolves when the model uses the visible label.
        assertEquals(listOf("登录"), AgentScreenTargetResolver.resolve("登录", nodes).map { it.text })
    }

    @Test
    fun `sibling identifiers never match each other`() {
        // android.widget.Button vs android.widget.ImageButton: only the exact class matches.
        val nodes = listOf(
            node(0, "确定", cls = "android.widget.Button"),
            node(1, "图片", cls = "android.widget.ImageButton")
        )

        val resolved = AgentScreenTargetResolver.resolve(
            ScreenTarget(byId = listOf("android.widget.Button")), nodes
        )

        assertEquals("只有真正的 id 命中才算候选", listOf(0), resolved.map { it.index })
    }

    @Test
    fun `needles drop one-character noise and spaces`() {
        val needles = AgentScreenTargetResolver.needlesOf("打开 微信")

        assertTrue(needles.contains("微信"))
        assertTrue("single characters are too broad", needles.none { it.length < 2 })
    }

    // ---------------------------------------------------------------- fixtures

    private fun node(
        index: Int,
        text: String,
        desc: String = "",
        cls: String = "android.widget.TextView",
        x: Int = 0,
        y: Int = 0,
        w: Int = 100,
        h: Int = 50,
        clickable: Boolean = false,
        input: Boolean = false,
        password: Boolean = false
    ): A11yNode = A11yNode(
        index = index,
        depth = 0,
        text = text,
        desc = desc,
        cls = cls,
        rect = A11yRect(x, y, w, h),
        clickable = clickable,
        input = input,
        checked = false,
        selected = false,
        scrollable = false,
        password = password,
        redacted = password,
        ref = A11yNodeRef(1L, index)
    )
}

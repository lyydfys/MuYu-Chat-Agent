package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageIntentTest {
    @Test
    fun classifierSeparatesGenerateChatAndAmbiguousWithoutChangingLegacyParser() {
        val generated = classifyChatImageIntent("帮我生成一张蓝色玻璃花瓶")
        assertEquals(ChatImageIntentRoute.GENERATE, generated.route)
        assertEquals("image_action", generated.reason)
        assertEquals("蓝色玻璃花瓶", generated.prompt)

        val discussion = classifyChatImageIntent("不要生成图片，只解释一下提示词怎么写。")
        assertEquals(ChatImageIntentRoute.CHAT, discussion.route)
        assertEquals("negative_or_discussion", discussion.reason)
        assertNull(discussion.intent)

        val ambiguous = classifyChatImageIntent("拍一张")
        assertEquals(ChatImageIntentRoute.AMBIGUOUS, ambiguous.route)
        assertEquals("image_action_missing_subject", ambiguous.reason)
        assertNull(ambiguous.intent)
    }

    @Test
    fun explicitImageSkillWithBlankPayloadIsAmbiguousAndKeepsSourceText() {
        val decision = classifyChatImageIntent("/image ")
        assertEquals(ChatImageIntentRoute.AMBIGUOUS, decision.route)
        assertEquals("explicit_image_skill_missing_prompt", decision.reason)
        assertEquals("/image", decision.sourceText.trim())
    }

    @Test
    fun explicitChineseImageCommandsReturnOnlyTheImagePrompt() {
        assertEquals("一只坐在窗边的橘猫", parseExplicitChatImageIntent("生成图片：一只坐在窗边的橘猫")?.prompt)
        assertEquals("雨夜里的小书店", parseExplicitChatImageIntent("请帮我生成一张图片，雨夜里的小书店")?.prompt)
        assertEquals("海边灯塔", parseExplicitChatImageIntent("帮我画一张：海边灯塔")?.prompt)
        assertEquals("蓝色玻璃花瓶", parseExplicitChatImageIntent("画出，蓝色玻璃花瓶")?.prompt)
        assertEquals("一只戴红围巾的柴犬", parseExplicitChatImageIntent("我想画一个一只戴红围巾的柴犬")?.prompt)
        assertEquals("夜间城市街景", parseExplicitChatImageIntent("帮我生图：夜间城市街景")?.prompt)
        assertEquals("一只戴帽子的狗", parseExplicitChatImageIntent("请你生成一张图片：一只戴帽子的狗")?.prompt)
        assertEquals("雪山湖泊", parseExplicitChatImageIntent("给我画一张雪山湖泊")?.prompt)
        assertEquals("夜景人像", parseExplicitChatImageIntent("麻烦来一张夜景人像")?.prompt)
        assertEquals("美女", parseExplicitChatImageIntent("帮我生成一张美女图片")?.prompt)
        assertEquals("角色自拍", parseExplicitChatImageIntent("帮我生成角色自拍图片")?.prompt)
        assertEquals("自拍", parseExplicitChatImageIntent("给角色拍张自拍")?.prompt)
        assertEquals("自拍, 穿红色外套", parseExplicitChatImageIntent("给角色拍张自拍，穿红色外套")?.prompt)
        assertEquals("海报构图", parseExplicitChatImageIntent("/image: 海报构图")?.prompt)
    }

    @Test
    fun imageSkillKeepsEveryPromptFormatForTheModelBridge() {
        assertEquals(
            "中文正向：1girl, 红色外套\n负面提示词：低清晰度\n{\"lora\":\"character\"}",
            parseExplicitChatImageIntent(
                "/image 中文正向：1girl, 红色外套\n负面提示词：低清晰度\n{\"lora\":\"character\"}"
            )?.prompt
        )
        assertEquals(
            "生成一段代码，读取图片文件",
            parseExplicitChatImageIntent("/image 生成一段代码，读取图片文件")?.prompt
        )
        assertEquals("quiet forest", parseExplicitChatImageIntent("/draw quiet forest")?.prompt)
    }

    @Test
    fun naturalLanguageAndRolePlayImageRequestsAreDetected() {
        assertEquals("美女", parseExplicitChatImageIntent("帮我生成一张美女")?.prompt)
        assertEquals("角色在雨夜街头", parseExplicitChatImageIntent("给我做一张角色在雨夜街头")?.prompt)
        assertEquals("自拍, 穿红色外套", parseExplicitChatImageIntent("让角色拍一张自拍，穿红色外套")?.prompt)
        assertEquals("换个姿势再拍一张", parseExplicitChatImageIntent("换个姿势再拍一张")?.prompt)
        assertEquals("a cinematic portrait of a woman", parseExplicitChatImageIntent("create an image of a cinematic portrait of a woman")?.prompt)
    }

    @Test
    fun automaticRouteKeepsTheCompletePromptForTheBridge() {
        val input = "帮我生成一张美女图片\n中文正向：精致脸，长发\n英文正向：beautiful face, long hair\n负向：lowres, blurry"
        val intent = parseExplicitChatImageIntent(input)
        assertEquals("美女, 中文正向：精致脸，长发\n英文正向：beautiful face, long hair\n负向：lowres, blurry", intent?.prompt)
        assertEquals(input, intent?.sourceText)

        val rolePlay = "换个姿势再拍一张\n保留角色的红色外套和短发"
        assertEquals(rolePlay, parseExplicitChatImageIntent(rolePlay)?.sourceText)
    }

    @Test
    fun imageSlashCommandRequiresATokenBoundary() {
        assertEquals("quiet forest", parseExplicitChatImageIntent("/image quiet forest")?.prompt)
        assertEquals("quiet forest", parseExplicitChatImageIntent(" /IMAGE: quiet forest ")?.prompt)
        assertTrue(hasExplicitChatImageCommand("/image "))
        assertTrue(hasExplicitChatImageCommand("  /IMAGE"))
        assertNull(parseExplicitChatImageIntent("/imageanything quiet forest"))
        assertNull(parseExplicitChatImageIntent("/images quiet forest"))
        assertNull(parseExplicitChatImageIntent("/image "))
        assertNull(parseExplicitChatImageIntent("帮我 /image quiet forest"))
    }

    @Test
    fun ordinaryDiscussionAndNegativeRequestsStayOnTheChatPath() {
        assertNull(parseExplicitChatImageIntent("你觉得这张生成图片的构图怎么样？"))
        assertNull(parseExplicitChatImageIntent("不要生成图片，只解释一下提示词怎么写。"))
        assertNull(parseExplicitChatImageIntent("我想了解如何生成一张图片"))
        assertNull(parseExplicitChatImageIntent("生成一段代码，读取图片文件"))
        assertNull(parseExplicitChatImageIntent("帮我画一个九九乘法表"))
        assertNull(parseExplicitChatImageIntent("请生成一个故事，背景是图片展览"))
        assertNull(parseExplicitChatImageIntent("来一杯咖啡"))
        assertNull(parseExplicitChatImageIntent("来一段代码"))
    }

    @Test
    fun nonImageMediaAndPromptDiscussionDoNotTriggerAutomaticImageGeneration() {
        assertNull(parseExplicitChatImageIntent("帮我拍一段视频"))
        assertNull(parseExplicitChatImageIntent("帮我生成一段文字"))
        assertNull(parseExplicitChatImageIntent("帮我总结 lora:character 的含义"))
    }

    @Test
    fun blankPromptsAreRejectedAfterTrimmingOptionalSeparators() {
        assertNull(parseExplicitChatImageIntent("生成图片：   "))
        assertNull(parseExplicitChatImageIntent("/image   "))
        assertNull(parseExplicitChatImageIntent("帮我画一张：\n  "))
        assertNull(parseExplicitChatImageIntent("拍一张"))
    }

    @Test
    fun textualMentionsOfTheCommandDoNotBecomeImageRequests() {
        // Attachments are a separate ChatMessage field and are not part of this parser's input.
        // Text that merely documents the command must remain an ordinary chat turn.
        assertNull(parseExplicitChatImageIntent("命令 /image 后面可以填写图片描述。"))
        assertNull(parseExplicitChatImageIntent("提示词附件里写着“生成图片：一只猫”，帮我分析这句话。"))
    }

    @Test
    fun contextualFollowUpRequiresTheSameSessionAndKeepsBatchCountsSeparate() {
        val context = ChatImageIntentContext(
            sessionId = "session-a",
            lastImageRequestId = "image-1",
            lastImageSessionId = "session-a"
        )
        val decision = classifyChatImageIntent("再生成两张，每张三个人", context)
        assertEquals(ChatImageIntentRoute.GENERATE, decision.route)
        assertEquals("same_session_image_follow_up", decision.reason)
        assertEquals(2, decision.requestedOutputCount)
        assertEquals(3, decision.visualSubjectCount)
        assertEquals("image-1", decision.referenceImageRequestId)

        val otherSession = classifyChatImageIntent(
            "再生成两张",
            context.copy(sessionId = "session-b")
        )
        assertEquals(ChatImageIntentRoute.AMBIGUOUS, otherSession.route)
        assertEquals(null, otherSession.referenceImageRequestId)
    }

    @Test
    fun correctionNegationDoesNotCancelAnExplicitReplacementAction() {
        val decision = classifyChatImageIntent("不要画猫，改画一只狗")
        assertEquals(ChatImageIntentRoute.GENERATE, decision.route)
        assertEquals("corrected_image_action", decision.reason)
        assertTrue(decision.prompt.orEmpty().contains("一只狗"))
    }
}

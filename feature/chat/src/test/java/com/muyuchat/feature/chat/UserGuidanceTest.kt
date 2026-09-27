package com.muyuchat.feature.chat
import org.junit.Assert.*
import org.junit.Test
class UserGuidanceTest {
 @Test fun successIsNotError() { assertNull(userNotice("正在加载模型")); assertNull(userNotice("图片生成完成")) }
 @Test fun missingModel() { assertEquals(GuidanceAction.MODELS, userNotice("请先在模型管理的本地页导入并选择图像生成引擎。")?.action) }
 @Test fun badParameters() { assertEquals(GuidanceAction.EDIT, userNotice("图片采样器不可用")?.action); assertEquals(GuidanceAction.EDIT, userNotice("CFG 必须为 0-30 的有限数值")?.action) }
 @Test fun unknownFailureKeepsDetails() { assertEquals(GuidanceAction.DETAILS, userNotice("图片生成失败：native internal error")?.action); assertTrue(userNotice("worker decode 超时")!!.problem.contains("推理进程")) }
 @Test fun incompletePackage() { assertEquals(GuidanceAction.MODELS, userNotice("模型文件不完整")?.action) }
 @Test fun englishAdviceDoesNotHideModelFailure() {
     assertEquals(GuidanceAction.MODELS, userNotice("模型组件校验失败\n建议使用英文提示词重试：a cat")?.action)
     assertEquals(GuidanceAction.DETAILS, userNotice("图片生成失败：native internal error\n建议使用英文提示词重试：a cat")?.action)
 }
 @Test fun explicitPromptRequirementOffersEdit() {
     assertEquals(GuidanceAction.EDIT, userNotice("请使用英文提示词")?.action)
 }
}

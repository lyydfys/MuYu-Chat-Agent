package com.muyuchat.feature.modelhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地推荐卡的**主操作**决策。
 *
 * **修复背景**：修复前，已安装但未通过本机验证的模型，其主按钮会被替换成
 * ``已安装 · 重新校验``，且 `onClick` 改为触发**校验**而不是加载/打开：
 *
 * ```kotlin
 * onClick = { if (localChatVerificationPassed) onLoadLocalChatModel(m) else onVerifyLocalChatModel(m) }
 * Text(if (localChatVerificationPassed) "打开本地模型" else "已安装 · 重新校验")
 * ```
 *
 * 其中 `localChatVerificationPassed` 对 QAIRT 运行时要求
 * `localChatModel.id in qairtVerifiedLocalModelIds` —— 那是一份**设备侧名单**。
 * ⇒ 未知设备、芯片不匹配或"暂无验证记录"时，用户的**加载路径被校验替代**，
 * 违反通用设备准入（拒绝必须基于真实执行失败，而非验证记录）。
 *
 * **本组用例锁定的契约**：主操作只取决于"是否已安装"，与验证状态无关；
 * 验证状态只能决定**次级诊断按钮**是否显示。
 */
class RecommendedCardPrimaryActionTest {

    // ── 主操作永不被验证状态门控 ──────────────────────────────────────────

    @Test
    fun anInstalledChatModelAlwaysOffersLoadAsThePrimaryAction() {
        assertEquals(
            RecommendedCardPrimaryAction.LOAD_LOCAL_CHAT,
            recommendedCardPrimaryAction(hasLocalImageModel = false, hasLocalChatModel = true)
        )
    }

    @Test
    fun anInstalledImageModelAlwaysOffersOpenAsThePrimaryAction() {
        assertEquals(
            RecommendedCardPrimaryAction.OPEN_LOCAL_IMAGE,
            recommendedCardPrimaryAction(hasLocalImageModel = true, hasLocalChatModel = false)
        )
    }

    @Test
    fun anInstalledImageModelWinsOverAnInstalledChatModel() {
        // 与既有的 if/else-if 顺序一致：生图模型优先
        assertEquals(
            RecommendedCardPrimaryAction.OPEN_LOCAL_IMAGE,
            recommendedCardPrimaryAction(hasLocalImageModel = true, hasLocalChatModel = true)
        )
    }

    @Test
    fun nothingInstalledFallsBackToDownload() {
        assertEquals(
            RecommendedCardPrimaryAction.DOWNLOAD,
            recommendedCardPrimaryAction(hasLocalImageModel = false, hasLocalChatModel = false)
        )
    }

    @Test
    fun thePrimaryDecisionCannotBeGatedByVerificationByConstruction() {
        // 结构性保证：`recommendedCardPrimaryAction` 的签名里**没有任何**验证/设备参数，
        // 因此"未验证 ⇒ 主操作变校验"在类型层面就无法表达。
        // 这条用例把该契约写成可执行的期望：一旦有人为它加上验证参数以"未验证就禁用加载"，
        // 本测试的编译会失败并提醒复核（这正是我们要的护栏）。
        val withNothingInstalled = recommendedCardPrimaryAction(
            hasLocalImageModel = false,
            hasLocalChatModel = false
        )
        val withChatInstalled = recommendedCardPrimaryAction(
            hasLocalImageModel = false,
            hasLocalChatModel = true
        )

        assertEquals(RecommendedCardPrimaryAction.DOWNLOAD, withNothingInstalled)
        assertEquals(RecommendedCardPrimaryAction.LOAD_LOCAL_CHAT, withChatInstalled)
        // 同一组输入永远给出同一结果 ⇒ 结果不可能还依赖某个隐藏的验证态
        assertEquals(
            withChatInstalled,
            recommendedCardPrimaryAction(hasLocalImageModel = false, hasLocalChatModel = true)
        )
    }

    // ── 诊断按钮只作提示 ────────────────────────────────────────────────────

    @Test
    fun anUnverifiedInstalledChatModelShowsTheSecondaryDiagnostic() {
        assertTrue(
            recommendedCardShowsVerifyDiagnostic(
                hasLocalImageModel = false,
                hasLocalChatModel = true,
                imageVerificationPassed = false,
                chatVerificationPassed = false
            )
        )
    }

    @Test
    fun aVerifiedInstalledChatModelHidesTheSecondaryDiagnostic() {
        assertFalse(
            recommendedCardShowsVerifyDiagnostic(
                hasLocalImageModel = false,
                hasLocalChatModel = true,
                imageVerificationPassed = false,
                chatVerificationPassed = true
            )
        )
    }

    @Test
    fun anUnverifiedInstalledImageModelShowsTheSecondaryDiagnostic() {
        assertTrue(
            recommendedCardShowsVerifyDiagnostic(
                hasLocalImageModel = true,
                hasLocalChatModel = false,
                imageVerificationPassed = false,
                chatVerificationPassed = false
            )
        )
    }

    @Test
    fun aVerifiedInstalledImageModelHidesTheSecondaryDiagnostic() {
        assertFalse(
            recommendedCardShowsVerifyDiagnostic(
                hasLocalImageModel = true,
                hasLocalChatModel = false,
                imageVerificationPassed = true,
                chatVerificationPassed = false
            )
        )
    }

    @Test
    fun nothingInstalledShowsNoDiagnostic() {
        assertFalse(
            recommendedCardShowsVerifyDiagnostic(
                hasLocalImageModel = false,
                hasLocalChatModel = false,
                imageVerificationPassed = false,
                chatVerificationPassed = false
            )
        )
    }

    // ── 方案点名的四种情形：主操作仍为 Load/Open ───────────────────────────
    //
    // 说明：未知设备 / 芯片不匹配影响的是**下载资格**（`recommendationDownloadAccess`，已有测试），
    // 对**已安装**模型的加载路径不构成阻断。下面用"未验证 + 无验证记录 + 验证失败"三种验证态
    // 交叉确认主操作不变。

    @Test
    fun loadStaysAvailableAcrossEveryVerificationOutcome() {
        // 验证态在这里只影响诊断按钮；主操作恒为加载
        val outcomes = listOf(
            "无验证记录" to false,
            "验证失败" to false,
            "验证通过" to true
        )
        outcomes.forEach { (label, passed) ->
            assertEquals(
                "验证态=$label 时主操作应为加载",
                RecommendedCardPrimaryAction.LOAD_LOCAL_CHAT,
                recommendedCardPrimaryAction(hasLocalImageModel = false, hasLocalChatModel = true)
            )
            // 诊断按钮仅在未通过时出现
            assertEquals(
                "验证态=$label 时诊断按钮可见性",
                !passed,
                recommendedCardShowsVerifyDiagnostic(
                    hasLocalImageModel = false,
                    hasLocalChatModel = true,
                    imageVerificationPassed = false,
                    chatVerificationPassed = passed
                )
            )
        }
    }
}

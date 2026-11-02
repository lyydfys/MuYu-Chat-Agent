package com.muyuchat.mca.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 生成器（G00/Q01 性能设施）。
 *
 * 边界与编译范围：
 * - 本文件只存在于 `:benchmark`（`com.android.test`，`targetProjectPath = ":app"`）。该模块在
 *   `settings.gradle.kts` 中是 opt-in（`MCA_INCLUDE_BENCHMARK=true` 或
 *   `-Pmca.includeBenchmark=true`），且 `com.android.test` 产物从不进入生产 APK，因此这里没有
 *   任何生产编译面；它也不会让 `:app` 多出测试代码。任务要求「只在 benchmark 特定 build type
 *   编译」由模块本身（只有 `benchmark` build type，经 opt-in include）满足，不需要额外的
 *   sourceSet 开关。
 * - 依赖 `androidx.benchmark:benchmark-macro-junit4`（`BaselineProfileRule`）与
 *   `androidx.test.uiautomator`（生产 UI 选择器），两者都只在本模块出现。
 *
 * 运行（必需真机，API 29+，target app 已构建为 profileable）：
 *
 * ```powershell
 * $env:MCA_INCLUDE_BENCHMARK = 'true'
 * .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest `
 *   "-Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.BaselineProfileGenerator"
 * ```
 *
 * 更推荐用 `scripts/baseline-profile.ps1`：它负责「生成 -> 复制到 app/src/main/baseline-prof.txt
 * -> 构建 APK -> 校验 assets/dexopt/baseline.prof -> 证据 JSON」的完整闭环，并在没有设备时如实
 * 输出 NOT_RUN。
 *
 * profile block 覆盖的场景（与任务一致）：
 * 1. 冷启动 + 聊天首屏：`startActivityAndWait()` + `waitForChatSurface()`；
 * 2. 聊天首屏列表：若当前会话已有消息，对最高的竖向滚动容器做一次 fling（空会话时跳过，不失败）；
 * 3. 应用菜单（抽屉/菜单之一）：`打开应用菜单` -> 菜单页 -> 滚动 -> `关闭`；
 * 4. 历史抽屉（抽屉/菜单之二）：`打开历史` -> 抽屉 `图片` 入口出现 -> 滚动历史列表。
 *
 * 选择器全部来自现有生产语义（contentDescription / 可见文案），不启用 `testTagsAsResourceId`，
 * 也不使用屏幕坐标。生产文案变化时这里会明确失败，而不是悄悄测一个别的界面。
 *
 * 生成产物：`androidx.benchmark.Outputs` 会把它放进连通测试的 additional test output，文件名是
 * `<outputFilePrefix>-baseline-prof.txt`（本类固定为 `mca-baseline-prof.txt`）。脚本从
 * `benchmark/build/outputs/connected_android_test_additional_output/` 下取出并复制到
 * `app/src/main/baseline-prof.txt`——AGP 8.13.2 的 `VariantSources.getArtProfile()` 正是读取
 * `src/main/baseline-prof.txt`，随后由 `CompileArtProfileTask` 编译进
 * `assets/dexopt/baseline.prof`。
 *
 * 无设备时本类不会被运行，`scripts/baseline-profile.ps1` 记录 NOT_RUN；不得把任何「收益」写进证据。
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        val maxIterations = profileIntArgument(
            PROFILE_MAX_ITERATIONS_ARG,
            PROFILE_MAX_ITERATIONS_DEFAULT
        )
        val stableIterations = profileIntArgument(
            PROFILE_STABLE_ITERATIONS_ARG,
            PROFILE_STABLE_ITERATIONS_DEFAULT
        )
        println(
            "BaselineProfileGenerator: package=${BenchmarkTarget.PACKAGE_NAME}, " +
                "maxIterations=$maxIterations, stableIterations=$stableIterations, " +
                "outputFilePrefix=$OUTPUT_FILE_PREFIX"
        )

        baselineProfileRule.collect(
            packageName = BenchmarkTarget.PACKAGE_NAME,
            maxIterations = maxIterations,
            stableIterations = stableIterations,
            outputFilePrefix = OUTPUT_FILE_PREFIX,
            includeInStartupProfile = false,
            strictStability = false,
            filterPredicate = ::shouldKeepProfileRule,
        ) {
            // 1) 冷启动 + 聊天首屏。
            startActivityAndWait()
            waitForChatSurface()
            // 2) 聊天首屏的消息列表：空会话时没有可滚动节点，跳过而不是失败。
            flingTallestVerticalList(times = 1, required = false)

            // 3) 应用菜单：仅在聊天首屏可见时唯一；菜单页自身的关闭按钮是稳定的生产语义。
            openAppMenuFromChat()
            flingTallestVerticalList(times = 3, required = true)
            closeAppMenu()
            waitForChatSurface()

            // 4) 历史抽屉：等抽屉里的 `图片` 入口出现来证明抽屉已渲染，再滚动会话列表。
            //    不依赖返回键语义，本轮结束状态由 macrobenchmark 在迭代间杀进程重置。
            openHistoryDrawer()
            flingTallestVerticalList(times = 2, required = true)
        }
    }
}

private const val OUTPUT_FILE_PREFIX = "mca"
private const val PROFILE_MAX_ITERATIONS_ARG = "mca.profileMaxIterations"
private const val PROFILE_STABLE_ITERATIONS_ARG = "mca.profileStableIterations"
private const val PROFILE_MAX_ITERATIONS_DEFAULT = 15
private const val PROFILE_STABLE_ITERATIONS_DEFAULT = 3

/**
 * 生成 profile 时保留的类范围：应用自身 + 首屏实际用到的 AndroidX/Kotlin 运行时。
 *
 * `BaselineProfileRule.filterPredicate` 收到的是 profile 规则行（例如
 * `HSPLcom/muyuchat/mca/MainActivity;->onCreate(Landroid/os/Bundle;)V`），因此用子串匹配包路径。
 * 库自带的 baseline profile 由 AAR 提供并由 AGP 合并，不依赖这里；这里只避免把与首屏无关的
 * 系统/第三方执行痕迹写进生产 profile。
 */
private val PROFILE_KEEP_MARKERS = listOf(
    "com/muyuchat/",
    "androidx/compose/",
    "androidx/activity/",
    "androidx/lifecycle/",
    "androidx/core/",
    "androidx/profileinstaller/",
    "androidx/startup/",
    "kotlinx/coroutines/",
)

private fun shouldKeepProfileRule(rule: String): Boolean =
    PROFILE_KEEP_MARKERS.any { marker -> rule.contains(marker) }

private fun profileIntArgument(name: String, defaultValue: Int): Int =
    InstrumentationRegistry.getArguments()
        .getString(name)
        ?.trim()
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: defaultValue

private fun MacrobenchmarkScope.flingTallestVerticalList(times: Int, required: Boolean) {
    val target = device.findObjects(By.scrollable(true))
        .filter { it.visibleBounds.height() > it.visibleBounds.width() }
        .maxByOrNull { it.visibleBounds.height() }
    if (target == null) {
        check(!required) {
            "No vertical scrollable container found; the profile block expected a rendered list " +
                "but the production UI did not expose one. This is a real UI change, not a " +
                "performance result."
        }
        return
    }
    target.setGestureMargin(device.displayWidth / 5)
    repeat(times) { target.fling(Direction.DOWN) }
    device.waitForIdle()
}

/** 打开应用菜单（聊天首屏头部的 `打开应用菜单`）。 */
private fun MacrobenchmarkScope.openAppMenuFromChat() {
    requireObject(By.desc("打开应用菜单"), "app menu button").click()
    device.waitForIdle()
    requireObject(By.desc("关闭"), "app menu close button")
}

/** 关闭应用菜单，回到聊天首屏。 */
private fun MacrobenchmarkScope.closeAppMenu() {
    requireObject(By.desc("关闭"), "app menu close button").click()
    device.waitForIdle()
}

/** 打开历史抽屉；`图片` 入口出现即证明抽屉已渲染（生产入口，不使用坐标）。 */
private fun MacrobenchmarkScope.openHistoryDrawer() {
    requireObject(By.desc("打开历史"), "history drawer button").click()
    device.waitForIdle()
    requireObject(By.text("图片"), "history drawer image entry")
}

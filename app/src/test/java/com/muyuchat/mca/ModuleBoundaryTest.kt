package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **模块边界与依赖方向的可执行契约。**
 *
 * 背景：经实测，当前 revision 的依赖方向**已经是干净的**——
 * `feature:*` 与 `api:local` 都不 import `com.muyuchat.mca.*`（app 包），也**没有跨 feature import**；
 * `core:*` 中只有 `core:designsystem` 依赖 Compose（那是它的职责）。
 *
 * ⇒ 但**此前没有任何测试锁定这些性质**：一次无意的 `import com.muyuchat.mca.X` 就能悄悄把
 * feature 重新绑回 app，而编译与既有测试**都不会报错**（Gradle 依赖不会因此失败，因为存在传递依赖）。
 *
 * ⇒ 本测试把「已达成的好性质」变成**回归护栏**，对应重构方案的边界测试要求
 * （feature/core 的依赖与 import 方向，避免依赖倒挂）。
 *
 * 设计要点：
 * - 纯 JVM、只读源码文件，**不依赖 Gradle 配置解析**，因此改构建脚本不会让它失效。
 * - 内置**正控制**（[scannerDetectsAViolationWhenOneIsPresent]），证明扫描器**真的能失败**，
 *   避免出现「断言恒真」的空过测试。
 */
class ModuleBoundaryTest {

    // ── 扫描器（纯逻辑，可被正控制直接验证）──────────────────────────────

    /** 一条被发现的越界 import。 */
    internal data class BoundaryViolation(val file: String, val line: Int, val importPath: String)

    /**
     * 在给定的 (模块名 -> 源码文本) 集合中，找出**禁止的 import**。
     *
     * @param sources    相对路径 -> 文件内容
     * @param forbidden  谓词：给定 (相对路径, import 的 fully-qualified name) 是否越界
     */
    internal fun scanForbiddenImports(
        sources: Map<String, String>,
        forbidden: (relativePath: String, importPath: String) -> Boolean
    ): List<BoundaryViolation> {
        val found = mutableListOf<BoundaryViolation>()
        sources.forEach { (path, text) ->
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (!line.startsWith("import ")) return@forEachIndexed
                val symbol = line.removePrefix("import ").substringBefore(" as ").trim()
                if (forbidden(path, symbol)) {
                    found += BoundaryViolation(path, index + 1, symbol)
                }
            }
        }
        return found.sortedWith(compareBy({ it.file }, { it.line }))
    }

    private fun moduleOf(relativePath: String): String =
        relativePath.replace('\\', '/').split('/').take(2).joinToString("/")

    // ── 真实源码的断言 ─────────────────────────────────────────────────────

    private val repoRoot: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir) { "未找到包含 settings.gradle.kts 的仓库根（测试工作目录=$${File(".").absolutePath}）" }
        dir
    }

    /** 读取 main 源码（排除 build 产物），key 为相对仓库根的路径。 */
    private fun mainSources(vararg modulePrefixes: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        modulePrefixes.forEach { prefix ->
            val moduleDir = File(repoRoot, prefix)
            if (!moduleDir.isDirectory) return@forEach
            moduleDir.walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.path.replace('\\', '/').contains("/src/main/") }
                .forEach { file ->
                    val rel = file.relativeTo(repoRoot).path.replace('\\', '/')
                    result[rel] = file.readText()
                }
        }
        return result
    }

    private val featureAndApiPrefixes = arrayOf(
        "feature/chat", "feature/agent", "feature/modelhub", "feature/settings", "api/local"
    )

    @Test
    fun featureAndApiModulesNeverImportTheAppPackage() {
        val sources = mainSources(*featureAndApiPrefixes)
        assertTrue("未读到任何 feature/api 源码，路径推断可能有误", sources.isNotEmpty())

        val violations = scanForbiddenImports(sources) { path, symbol ->
            moduleOf(path).let { it.startsWith("feature/") || it.startsWith("api/") } &&
                symbol.startsWith("com.muyuchat.mca.")
        }

        assertEquals(
            "feature/api 模块不得 import app 包（com.muyuchat.mca.*）——这会把 Feature 重新绑回 app：" +
                violations.joinToString("; ") { "${it.file}:${it.line} -> ${it.importPath}" },
            0,
            violations.size
        )
    }

    @Test
    fun coreModulesNeverImportTheAppPackage() {
        val sources = mainSources(
            "core/native", "core/sd-native", "core/engine", "core/modelstore", "core/download",
            "core/telemetry", "core/deviceprofile", "core/tuning", "core/advisor", "core/benchmark",
            "core/designsystem"
        )
        assertTrue(sources.isNotEmpty())

        val violations = scanForbiddenImports(sources) { path, symbol ->
            moduleOf(path).startsWith("core/") && symbol.startsWith("com.muyuchat.mca.")
        }

        assertEquals(
            "core 模块不得 import app 包：" +
                violations.joinToString("; ") { "${it.file}:${it.line} -> ${it.importPath}" },
            0,
            violations.size
        )
    }

    @Test
    fun featuresNeverImportEachOtherInternally() {
        val sources = mainSources("feature/chat", "feature/agent", "feature/modelhub", "feature/settings")
        assertTrue(sources.isNotEmpty())

        val violations = scanForbiddenImports(sources) { path, symbol ->
            val owner = moduleOf(path).removePrefix("feature/")
            symbol.startsWith("com.muyuchat.feature.") &&
                symbol.removePrefix("com.muyuchat.feature.").substringBefore('.').let { it != owner }
        }

        assertEquals(
            "feature 之间不得互相 import 内部类型；跨功能协作应经共享用例或 app 组合根：" +
                violations.joinToString("; ") { "${it.file}:${it.line} -> ${it.importPath}" },
            0,
            violations.size
        )
    }

    @Test
    fun composeIsOnlyAllowedInTheDesignSystemWithinCore() {
        val sources = mainSources(
            "core/native", "core/sd-native", "core/engine", "core/modelstore", "core/download",
            "core/telemetry", "core/deviceprofile", "core/tuning", "core/advisor", "core/benchmark",
            "core/designsystem"
        )

        val violations = scanForbiddenImports(sources) { path, symbol ->
            val module = moduleOf(path)
            module.startsWith("core/") &&
                symbol.startsWith("androidx.compose.") &&
                module != "core/designsystem"
        }

        assertEquals(
            "core 中只有 core:designsystem 可以依赖 Compose（其余 core 模块须与 UI 无关）：" +
                violations.joinToString("; ") { "${it.file}:${it.line} -> ${it.importPath}" },
            0,
            violations.size
        )
    }

    // ── 🔴 正控制：证明扫描器**真的能**发现违规（避免空过）────────────────

    @Test
    fun scannerDetectsAViolationWhenOneIsPresent() {
        val synthetic = mapOf(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/Leaky.kt" to
                """
                package com.muyuchat.feature.chat

                import com.muyuchat.mca.MainViewModel
                import androidx.compose.runtime.Composable
                import com.muyuchat.feature.agent.AgentScreen

                class Leaky
                """.trimIndent()
        )

        val appImports = scanForbiddenImports(synthetic) { path, symbol ->
            moduleOf(path).startsWith("feature/") && symbol.startsWith("com.muyuchat.mca.")
        }
        assertEquals("应恰好发现 1 处 app 包 import", 1, appImports.size)
        assertEquals("com.muyuchat.mca.MainViewModel", appImports.single().importPath)
        assertEquals(3, appImports.single().line)

        val crossFeature = scanForbiddenImports(synthetic) { path, symbol ->
            val owner = moduleOf(path).removePrefix("feature/")
            symbol.startsWith("com.muyuchat.feature.") &&
                symbol.removePrefix("com.muyuchat.feature.").substringBefore('.').let { it != owner }
        }
        assertEquals("应恰好发现 1 处跨 feature import", 1, crossFeature.size)
        assertEquals("com.muyuchat.feature.agent.AgentScreen", crossFeature.single().importPath)
    }

    @Test
    fun scannerStaysCleanForTextThatOnlyMentionsThePackage() {
        // 反向正控制：注释/字符串里出现包名**不算**违规，避免误报逼得后人放宽规则
        val benign = mapOf(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/Fine.kt" to
                """
                package com.muyuchat.feature.chat

                // 说明：本模块历史上曾 import com.muyuchat.mca.MainViewModel，已移除。
                internal const val DOC = "com.muyuchat.mca.MainViewModel"
                """.trimIndent()
        )

        val violations = scanForbiddenImports(benign) { path, symbol ->
            moduleOf(path).startsWith("feature/") && symbol.startsWith("com.muyuchat.mca.")
        }
        assertEquals("注释与字符串不应被判为违规", 0, violations.size)
    }
}

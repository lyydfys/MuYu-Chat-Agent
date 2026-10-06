package com.muyuchat.mca

import java.io.File

/**
 * 位置无关的源文本夹具（v4-①）。
 *
 * 读者的断言内容是「某段文本存在 / 顺序如此」，与它落在哪个文件无关；切片把体搬走时断言不应因
 * 搬家失效。[sourceOf] 通过**标记**定位承载文件（内容级定位，不含任何路径字面量），
 * [packageSources] 提供 `com/muyuchat/mca` 全部主源码的聚合文本（用于跨文件的 contains/absence 断言）。
 *
 * 例外（保留显式源，属断言对象本身）：文件身份的计数契约
 * `GenerationFlagAccountingContractTest`、双源 no-clearout 的
 * `LocalImageGenerationOwnershipContractTest`、lease 顺序比较的 `ImageUpscaleProductClosureTest`。
 */
internal object SourceTextFixtures {

    /** 全部 `com/muyuchat/mca` 主源码文件（按名排序）。 */
    fun packageFileSources(): List<File> =
        packageDirectory().listFiles { file -> file.extension == "kt" }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 全部 `com/muyuchat/mca` 主源码文件（按名排序）拼接文本。 */
    fun packageSources(): String =
        packageFileSources().joinToString("\n") { it.readText(Charsets.UTF_8) }

    /** 含 [marker] 的主源码文件（按名排序，可能多个）。 */
    fun sourcesContaining(marker: String): List<File> =
        packageFileSources().filter { it.readText(Charsets.UTF_8).contains(marker) }

    /** 承载 [marker] 的首个主源码文件的全文（按名排序取第一个；未命中即报错）。 */
    fun sourceOf(marker: String): String {
        val files = sourcesContaining(marker)
        require(files.isNotEmpty()) { "No mca source contains marker: $marker" }
        return files.first().readText(Charsets.UTF_8)
    }

    private fun packageDirectory(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidate = File(root, "app/src/main/java/com/muyuchat/mca")
            if (candidate.isDirectory) return candidate
            directory = root.parentFile
        }
        error("com/muyuchat/mca package directory not found from ${System.getProperty("user.dir")}")
    }
}

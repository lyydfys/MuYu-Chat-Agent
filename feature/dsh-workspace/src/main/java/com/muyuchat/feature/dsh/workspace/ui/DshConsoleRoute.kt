package com.muyuchat.feature.dsh.workspace.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.dsh.contract.console.DshConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshConsoleOfficialSurface
import com.muyuchat.feature.dsh.workspace.DshConsoleRow
import com.muyuchat.feature.dsh.workspace.DshConsoleTone
import com.muyuchat.feature.dsh.workspace.DshConsoleUiState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muyuchat.core.dsh.contract.console.DshConsolePort
import com.muyuchat.core.dsh.contract.console.DshPluginAdminPort
import com.muyuchat.core.dsh.contract.console.DshPluginAdminResult
import com.muyuchat.core.dsh.contract.console.DshPluginName
import com.muyuchat.core.dsh.contract.console.DshPluginOperation
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsolePort
import com.muyuchat.feature.dsh.workspace.DshConsoleViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Route wrapper of the DSH-only console.
 *
 * It owns exactly one [DshConsoleViewModel] for the injected projection port and, optionally, the
 * D23 maintenance section driven by [maintenancePort]. The console overlays the official page
 * inside the same tab: the underlying WebView stays composed so returning from the console does
 * not recreate the renderer, lose scroll state or resend DSH work. Notices are shown in this
 * surface's own snackbar, not in the MCA chat shell.
 *
 * When [maintenancePort] is null the maintenance section renders an honest disabled note; the
 * console itself still works from its own port.
 */
@Composable
fun DshConsoleRoute(
    port: DshConsolePort,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    maintenancePort: DshMaintenanceConsolePort? = null,
    /** In-app plugin administration; null keeps the plugin block read-only. */
    pluginAdminPort: DshPluginAdminPort? = null,
    /** Opens the native terminal page; null hides the entry entirely. */
    onOpenTerminal: (() -> Unit)? = null
) {
    val viewModel: DshConsoleViewModel = viewModel(
        key = "dsh-console",
        factory = DshConsoleViewModel.factory(port)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-read on every console entry so a host that changed while the page was hidden is not
    // shown from the first snapshot forever.
    LaunchedEffect(viewModel) { viewModel.start(); viewModel.refresh() }
    // The screen-capture consent dialog is another Activity: this page stops while it is open and
    // resumes when the user answers. Re-project on resume so the consent row and action state show
    // the owner's new fact instead of a stale "waiting" projection.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.text, duration = SnackbarDuration.Short)
        viewModel.consumeNotice(current.id)
    }

    Box(modifier = modifier.fillMaxSize()) {
        DshConsoleScreen(
            state = state,
            onIntent = viewModel::execute,
            onBack = onBack,
            modifier = Modifier.fillMaxSize(),
            maintenanceContent = {
                DshMaintenanceConsoleHost(
                    port = maintenancePort,
                    refreshKey = (state as? DshConsoleUiState.Ready)?.snapshot?.revision
                )
                // Additive device-privilege block of the same console column (Root + ADB status).
                // It adds no destination and does not reorder the existing sections.
                DshDeviceAccessSection()
                DshPictureInPictureSection()
                // Additive container block (proot + user-supplied rootfs): read-only verdict plus
                // the one fixed probe. It adds no destination and no layout change.
                if (onOpenTerminal != null) {
                    DshTerminalEntry(onOpenTerminal)
                }
                DshContainerSection()
                // Web kernel choice: system WebView (default) or GeckoView, with the effective
                // kernel reported so an unsupported ABI never looks like a silent failure.
                DshWebKernelSection()
                // Read-only plugin inventory. The official plugin manager owns installation, so this
                // block only lists what is already there and links to the official page.
                DshPluginsSection(
                    pluginAdminPort = pluginAdminPort,
                    availableIntents = (state as? DshConsoleUiState.Ready)?.availableIntents
                        ?: emptySet(),
                    onIntent = viewModel::execute
                )
            }
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 插件只读清单（DSHA ⑦ 的最小对齐）
//
// 事实：`<DSH_HOME>/profiles/web/package.json` 与 `pnpm-workspace.yaml` 在首次 seed 之后归官方插件
// 管理器所有（见 DshConfigCompositionPolicy / DshGatewayConfigSeeder）。MCA 只读展示，不接管安装/删除；
// 「打开官方插件页」只提交既有的 OpenOfficialSurface(PLUGINS) intent，由官方界面自己负责。
// ---------------------------------------------------------------------------------------------

/** One plugin row as listed by the console (name + declared version + source). */
internal data class DshInstalledPlugin(
    val name: String,
    val version: String,
    /** True when the row came from package.json `dependencies`; false for a node_modules directory. */
    val declaredInPackageJson: Boolean
)

/** Result of reading the official plugin inventory; every failure has its own state. */
internal sealed interface DshPluginCatalog {
    data class Listed(val plugins: List<DshInstalledPlugin>, val truncated: Boolean) : DshPluginCatalog
    data object Empty : DshPluginCatalog
    data object MissingDependencies : DshPluginCatalog
    data object NotCreatedYet : DshPluginCatalog
    data class Unreadable(val reason: String) : DshPluginCatalog
}

/** Display bound of one plugin list; the parser/render never grows without a ceiling. */
internal const val DSH_PLUGIN_MAX_ROWS: Int = 200

/** Upper bound of one package.json read; a real one is a few KiB. */
internal const val DSH_PLUGIN_PACKAGE_MAX_BYTES: Long = 1L * 1024 * 1024

internal const val DSH_PROFILE_PACKAGE_RELATIVE: String = "dsh/home/profiles/web/package.json"
internal const val DSH_PROFILE_NODE_MODULES_RELATIVE: String = "dsh/home/profiles/web/node_modules"

/**
 * Parses a `package.json` document and extracts its `dependencies` map.
 *
 * The module cannot take a JSON dependency (feature modules stay dependency-narrow), so this is a
 * small, strict recursive-descent reader for the one shape that matters. It never evaluates
 * anything, skips every unrelated value structurally, and reports a typed state for: blank file,
 * missing `dependencies`, malformed JSON and truncation.
 */
internal fun parseInstalledPlugins(
    packageJsonText: String,
    maxPlugins: Int = DSH_PLUGIN_MAX_ROWS
): DshPluginCatalog {
    val text = packageJsonText.trim()
    if (text.isEmpty()) return DshPluginCatalog.Empty
    val scanner = DshJsonScanner(text)
    scanner.skipWhitespace()
    if (scanner.atEnd() || scanner.peek() != '{') {
        return DshPluginCatalog.Unreadable("根节点不是 JSON 对象")
    }
    scanner.advance() // '{'
    scanner.skipWhitespace()
    if (scanner.peek() == '}') {
        // `{}` is a well-formed document that simply has no dependencies key; reading a key here
        // would report it as malformed JSON instead of MissingDependencies.
        scanner.advance()
        scanner.skipWhitespace()
        if (!scanner.atEnd()) return DshPluginCatalog.Unreadable("JSON 之后存在多余内容")
        return DshPluginCatalog.MissingDependencies
    }
    var dependencies: List<DshInstalledPlugin>? = null
    var truncated = false
    while (true) {
        scanner.skipWhitespace()
        val key = scanner.readString() ?: return DshPluginCatalog.Unreadable("对象键不是合法字符串")
        scanner.skipWhitespace()
        if (!scanner.consume(':')) return DshPluginCatalog.Unreadable("对象键后缺少冒号")
        scanner.skipWhitespace()
        if (key == "dependencies") {
            if (scanner.peek() != '{') return DshPluginCatalog.Unreadable("dependencies 不是对象")
            scanner.advance()
            val rows = mutableListOf<DshInstalledPlugin>()
            // A trailing comma before the closing brace is malformed JSON, not an empty tail.
            var trailingComma = false
            while (true) {
                scanner.skipWhitespace()
                if (scanner.peek() == '}') {
                    if (trailingComma) {
                        return DshPluginCatalog.Unreadable("依赖列表末尾有多余逗号")
                    }
                    break
                }
                val name = scanner.readString()
                    ?: return DshPluginCatalog.Unreadable("依赖名不是合法字符串")
                scanner.skipWhitespace()
                if (!scanner.consume(':')) return DshPluginCatalog.Unreadable("依赖名后缺少冒号")
                scanner.skipWhitespace()
                val version = if (scanner.peek() == '"') {
                    scanner.readString().orEmpty()
                } else {
                    if (!scanner.skipValue()) return DshPluginCatalog.Unreadable("依赖版本无法解析")
                    ""
                }
                if (rows.size < maxPlugins) {
                    rows += DshInstalledPlugin(name, version, declaredInPackageJson = true)
                } else {
                    truncated = true
                }
                trailingComma = false
                scanner.skipWhitespace()
                when (scanner.peek()) {
                    ',' -> {
                        scanner.advance()
                        trailingComma = true
                    }
                    '}' -> break
                    else -> return DshPluginCatalog.Unreadable("依赖列表缺少逗号或右括号")
                }
            }
            // The inner loop stops *at* the closing brace of the dependencies object; consuming it
            // here is what keeps the outer loop's own terminator in sync. Leaving it behind made
            // every document look like it had trailing content.
            scanner.skipWhitespace()
            if (!scanner.consume('}')) {
                return DshPluginCatalog.Unreadable("dependencies 对象没有闭合")
            }
            dependencies = rows
        } else if (!scanner.skipValue()) {
            return DshPluginCatalog.Unreadable("对象值无法解析")
        }
        scanner.skipWhitespace()
        when (scanner.peek()) {
            ',' -> scanner.advance()
            '}' -> {
                scanner.advance()
                break
            }
            else -> return DshPluginCatalog.Unreadable("对象缺少逗号或右括号")
        }
    }
    scanner.skipWhitespace()
    if (!scanner.atEnd()) return DshPluginCatalog.Unreadable("JSON 之后存在多余内容")
    return when {
        dependencies == null -> DshPluginCatalog.MissingDependencies
        dependencies.isEmpty() -> DshPluginCatalog.Empty
        else -> DshPluginCatalog.Listed(dependencies, truncated)
    }
}

/** Minimal strict JSON scanner: strings with escapes, numbers, literals, nesting, trailing text. */
private class DshJsonScanner(private val text: String) {
    private var index = 0

    fun atEnd(): Boolean = index >= text.length
    fun peek(): Char? = text.getOrNull(index)
    fun advance() {
        index += 1
    }

    fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index += 1
    }

    fun consume(char: Char): Boolean {
        if (text.getOrNull(index) != char) return false
        index += 1
        return true
    }

    /** Reads one JSON string (quoted, escapes decoded); null when malformed or not a string. */
    fun readString(): String? {
        if (text.getOrNull(index) != '"') return null
        index += 1
        val out = StringBuilder()
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' -> {
                    index += 1
                    return out.toString()
                }
                char == '\\' -> {
                    index += 1
                    val escape = text.getOrNull(index) ?: return null
                    when (escape) {
                        '"', '\\', '/' -> out.append(escape)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 >= text.length) return null
                            val hex = text.substring(index + 1, index + 5)
                            val code = hex.toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                    index += 1
                }
                char.code < 0x20 -> return null
                else -> {
                    out.append(char)
                    index += 1
                }
            }
        }
        return null // unterminated
    }

    /** Structurally skips one JSON value; false when the document is malformed. */
    fun skipValue(): Boolean {
        skipWhitespace()
        val char = peek() ?: return false
        return when {
            char == '"' -> readString() != null
            char == '{' -> skipObject()
            char == '[' -> skipArray()
            else -> skipScalar()
        }
    }

    /**
     * Skips one complete JSON value, recursively.
     *
     * package.json carries nested objects and arrays, and a value may even be a string containing
     * `}`; only a real recursive walk gets those right. The previous single-loop version could not
     * tell a `key: value` colon from a separator, so any nested object (devDependencies, scripts,
     * a dependency whose version is an object) aborted the whole document.
     */
    private fun skipObject(): Boolean {
        if (!consume('{')) return false
        skipWhitespace()
        if (peek() == '}') {
            advance()
            return true
        }
        while (true) {
            skipWhitespace()
            if (readString() == null) return false
            skipWhitespace()
            if (!consume(':')) return false
            if (!skipValue()) return false
            skipWhitespace()
            when (peek()) {
                ',' -> advance()
                '}' -> {
                    advance()
                    return true
                }
                else -> return false
            }
        }
    }

    private fun skipArray(): Boolean {
        if (!consume('[')) return false
        skipWhitespace()
        if (peek() == ']') {
            advance()
            return true
        }
        while (true) {
            if (!skipValue()) return false
            skipWhitespace()
            when (peek()) {
                ',' -> advance()
                ']' -> {
                    advance()
                    return true
                }
                else -> return false
            }
        }
    }

    private fun skipScalar(): Boolean {
        val start = index
        while (index < text.length) {
            val char = text[index]
            if (char.isWhitespace() || char == ',' || char == '}' || char == ']') break
            index += 1
        }
        if (index == start) return false
        val token = text.substring(start, index)
        return token == "true" || token == "false" || token == "null" || token.toDoubleOrNull() != null
    }
}

// ---- Filesystem inventory (read-only) --------------------------------------------------------

/** What one inventory scan found: catalog + discovered installation directories (best effort). */
internal data class DshPluginInventory(
    val catalog: DshPluginCatalog,
    val installedDirectories: List<String>
)

internal fun readPluginInventory(appFilesDir: File): DshPluginInventory {
    val packageFile = File(appFilesDir, DSH_PROFILE_PACKAGE_RELATIVE)
    val catalog = when {
        !packageFile.isFile -> DshPluginCatalog.NotCreatedYet
        packageFile.length() > DSH_PLUGIN_PACKAGE_MAX_BYTES ->
            DshPluginCatalog.Unreadable("package.json 超过读取上限")
        else -> runCatching {
            parseInstalledPlugins(packageFile.readText(Charsets.UTF_8))
        }.getOrElse { DshPluginCatalog.Unreadable("package.json 无法读取") }
    }
    return DshPluginInventory(catalog, listInstalledPluginDirectories(appFilesDir))
}

/** Directories actually present under profiles/web/node_modules (handles `@scope/name`). */
internal fun listInstalledPluginDirectories(appFilesDir: File): List<String> {
    val nodeModules = File(appFilesDir, DSH_PROFILE_NODE_MODULES_RELATIVE)
    val entries = runCatching { nodeModules.listFiles()?.toList() }.getOrNull().orEmpty()
    val names = mutableListOf<String>()
        // Order the rows the way the official install list reads: scoped packages first (their
        // owner is the scope), then dot-directories such as `.bin`, then plain names. A raw name
        // sort would put `.bin` above `@scope/name` because '.' < '@'.
        entries.sortedWith(
            compareBy(
                { if (it.name.startsWith("@")) 0 else if (it.name.startsWith(".")) 1 else 2 },
                { it.name }
            )
        ).forEach { entry ->
        if (!entry.isDirectory) return@forEach
        if (entry.name.startsWith("@")) {
            runCatching { entry.listFiles()?.toList() }.getOrNull()
                .orEmpty()
                .sortedBy { it.name }
                .forEach { scoped ->
                    if (scoped.isDirectory) names += entry.name + "/" + scoped.name
                }
        } else {
            names += entry.name
        }
    }
    return names.take(DSH_PLUGIN_MAX_ROWS)
}

@Composable
private fun DshPluginsSection(
    pluginAdminPort: DshPluginAdminPort?,
    availableIntents: Set<DshConsoleIntent>,
    onIntent: (DshConsoleIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    var refreshKey by remember { mutableIntStateOf(0) }
    var inventory by remember { mutableStateOf<DshPluginInventory?>(null) }
    LaunchedEffect(refreshKey) {
        inventory = withContext(Dispatchers.IO) { readPluginInventory(context.filesDir) }
    }
    val openSurface = DshConsoleIntent.OpenOfficialSurface(DshConsoleOfficialSurface.PLUGINS)
    val openEnabled = openSurface in availableIntents
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "插件（只读）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "列表来自官方 DSH 家的 profiles/web/package.json 与已安装目录；" +
                "安装、升级、删除都由官方插件管理器负责，本版不提供安装/删除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (val current = inventory) {
            null -> DshConsoleRowView(
                DshConsoleRow(label = "插件清单", value = "正在读取…")
            )
            else -> {
                DshPluginsCatalogRows(current)
                DshConsoleRowView(
                    DshConsoleRow(
                        label = "已安装目录",
                        value = if (current.installedDirectories.isEmpty()) {
                            "无（或不可读）"
                        } else {
                            current.installedDirectories.take(8).joinToString("、") +
                                if (current.installedDirectories.size > 8) " 等 ${current.installedDirectories.size} 个" else ""
                        }
                    )
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            McaButton(
                style = McaButtonStyle.Text,
                onClick = { refreshKey += 1 },
                modifier = Modifier.weight(1f)
            ) {
                Text("重新读取")
            }
            McaButton(
                style = McaButtonStyle.Outlined,
                enabled = openEnabled,
                onClick = { onIntent(openSurface) },
                modifier = Modifier.weight(1f)
            ) {
                Text("打开官方插件页")
            }
        }
        if (pluginAdminPort != null) {
            DshPluginAdminBlock(
                port = pluginAdminPort,
                installedDirectories = inventory?.installedDirectories.orEmpty(),
                onChanged = { refreshKey += 1 }
            )
        }
        Text(
            text = "每行都由官方插件管理器管理；MCA 只显示已声明的依赖与已安装目录。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DshPluginsCatalogRows(inventory: DshPluginInventory) {
    when (val catalog = inventory.catalog) {
        DshPluginCatalog.NotCreatedYet -> DshConsoleRowView(
            DshConsoleRow(
                label = "插件清单",
                value = "DSH 家尚未创建（首次运行官方 DSH 后出现）",
                tone = DshConsoleTone.NEUTRAL
            )
        )
        DshPluginCatalog.MissingDependencies -> DshConsoleRowView(
            DshConsoleRow(
                label = "插件清单",
                value = "package.json 没有 dependencies；由官方插件管理器维护",
                tone = DshConsoleTone.NEUTRAL
            )
        )
        DshPluginCatalog.Empty -> DshConsoleRowView(
            DshConsoleRow(
                label = "插件清单",
                value = "当前没有已声明的插件",
                tone = DshConsoleTone.NEUTRAL
            )
        )
        is DshPluginCatalog.Unreadable -> DshConsoleRowView(
            DshConsoleRow(
                label = "插件清单",
                value = "无法解析：${catalog.reason}",
                tone = DshConsoleTone.NEGATIVE
            )
        )
        is DshPluginCatalog.Listed -> {
            DshConsoleRowView(
                DshConsoleRow(
                    label = "已声明插件",
                    value = "${catalog.plugins.size} 个" +
                        if (catalog.truncated) "（列表已截断）" else ""
                )
            )
            catalog.plugins.take(DSH_PLUGIN_ROWS_VISIBLE).forEach { plugin ->
                DshConsoleRowView(
                    DshConsoleRow(
                        label = plugin.name,
                        value = plugin.version.ifBlank { "版本未知" } + " · 由官方插件管理器管理"
                    )
                )
            }
            if (catalog.plugins.size > DSH_PLUGIN_ROWS_VISIBLE) {
                Text(
                    text = "仅显示前 $DSH_PLUGIN_ROWS_VISIBLE 个；其余请到官方插件页查看。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Rows rendered inline; the parser keeps up to [DSH_PLUGIN_MAX_ROWS]. */
private const val DSH_PLUGIN_ROWS_VISIBLE: Int = 12

/**
 * Install / remove affordances of the plugin block.
 *
 * The writes are performed by the packaged pnpm CLI (see `DshPluginAdmin`); this composable only
 * validates nothing itself 鈥?it shows the owner's typed answer verbatim and refreshes the inventory
 * after a success. A non-null port is the only difference between the read-only and the managed
 * block, so a build without the runtime can never show a dead button.
 */
@Composable
private fun DshPluginAdminBlock(
    port: DshPluginAdminPort,
    installedDirectories: List<String>,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    fun report(result: DshPluginAdminResult) {
        message = when (result) {
            is DshPluginAdminResult.Succeeded ->
                (if (result.operation == DshPluginOperation.INSTALL) "已安装 " else "已删除 ") +
                    result.plugin + "（下次启动运行时生效）"
            is DshPluginAdminResult.Rejected -> "未执行：" + result.reason
            is DshPluginAdminResult.Failed ->
                "失败（" + result.reasonCode + "）" +
                    if (result.detail.isBlank()) "" else "：" + result.detail
        }
    }

    fun submit(action: suspend () -> DshPluginAdminResult) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val result = runCatching { action() }.getOrElse { error ->
                DshPluginAdminResult.Rejected(error.message ?: "调用失败")
            }
            report(result)
            busy = false
            if (result is DshPluginAdminResult.Succeeded) {
                name = ""
                version = ""
                onChanged()
            }
        }
    }

    Text(
        text = "安装 / 删除（由随包的 pnpm 在官方 profile 内执行）",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        singleLine = true,
        enabled = !busy,
        label = { Text("插件名，例如 dsh-plugin-market 或 @scope/name") },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = version,
        onValueChange = { version = it },
        singleLine = true,
        enabled = !busy,
        label = { Text("版本（可留空 = 最新）") },
        modifier = Modifier.fillMaxWidth()
    )
    DshPluginName.rejectReason(name)?.takeIf { name.isNotBlank() }?.let { reason ->
        Text(
            text = reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        McaButton(
            style = McaButtonStyle.Primary,
            enabled = !busy && DshPluginName.isAcceptable(name),
            onClick = { submit { port.install(name.trim(), version.trim().ifBlank { null }) } },
            modifier = Modifier.weight(1f)
        ) {
            Text(if (busy) "处理中縲" else "安装")
        }
    }
    if (installedDirectories.isNotEmpty()) {
        Text(
            text = "已安装目录",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        installedDirectories.take(8).forEach { directory ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = directory,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                McaButton(
                    style = McaButtonStyle.Text,
                    enabled = !busy,
                    onClick = { pendingRemove = directory }
                ) {
                    Text("删除")
                }
            }
        }
    }
    message?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除插件") },
            text = { Text("确定从官方 profile 中删除 " + target + " 吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    submit { port.remove(target) }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            }
        )
    }
}

/**
 * Entry row of the native terminal page.
 *
 * A one-row block rather than a new console destination: the page is a peer of the console inside
 * the same DSH tab, and the row is rendered only when the composition root injected a terminal
 * owner, so a build without the runtime owner never shows a dead button.
 */
@Composable
private fun DshTerminalEntry(onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "原生终端",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "在应用内直接操作 DSH 的 shell（PTY 由运行时提供），支持多标签、扩展键行与双指缩放。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        McaButton(
            style = McaButtonStyle.Outlined,
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("打开终端")
        }
    }
}

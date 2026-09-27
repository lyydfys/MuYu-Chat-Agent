package com.muyuchat.feature.chat

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val DEFAULT_SEARCH_URL_TEMPLATE = "https://www.google.com/search?q={query}"

@Composable
fun InternalBrowserDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    searchUrlTemplate: String = DEFAULT_SEARCH_URL_TEMPLATE
) {
    var task by remember(initialUrl) {
        mutableStateOf(BrowserTaskStateMachine.create("settings:web-preview", initialUrl))
    }
    InternalBrowserDialog(
        task = task,
        onTaskEvent = { task = BrowserTaskStateMachine.reduce(task, it) },
        onDismiss = onDismiss,
        searchUrlTemplate = searchUrlTemplate
    )
}

@Composable
fun InternalBrowserDialog(
    task: BrowserTask,
    onTaskEvent: (BrowserTaskEvent) -> Unit,
    onDismiss: () -> Unit,
    searchUrlTemplate: String = DEFAULT_SEARCH_URL_TEMPLATE
) {
    Dialog(
        onDismissRequest = {
            onTaskEvent(BrowserTaskEvent(task.taskId, task.navigationId, BrowserTaskAction.CANCEL))
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 14.dp),
            color = MaterialTheme.colorScheme.background,
            shape = MaterialTheme.shapes.medium
        ) {
            InternalBrowserContent(task, onTaskEvent, searchUrlTemplate, onDismiss)
        }
    }
}

private data class BrowserNavigationKey(val taskId: String, val navigationId: Long, val url: String)
private data class BrowserHistoryCommand(val url: String)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InternalBrowserContent(
    task: BrowserTask,
    onTaskEvent: (BrowserTaskEvent) -> Unit,
    searchUrlTemplate: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val latestTask by rememberUpdatedState(task)
    val latestOnEvent by rememberUpdatedState(onTaskEvent)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadedNavigation by remember { mutableStateOf<BrowserNavigationKey?>(null) }
    var historyCommand by remember { mutableStateOf<BrowserHistoryCommand?>(null) }
    var activeHistoryCommand by remember { mutableStateOf<BrowserHistoryCommand?>(null) }
    var navigationHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf(task.query.orEmpty()) }
    var searchFocused by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var rendererGone by remember { mutableStateOf(false) }
    var rendererEpoch by remember { mutableIntStateOf(0) }
    val disposedViews = remember { java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>()) }

    fun disposeView(view: WebView) {
        if (!disposedViews.add(view)) return
        runCatching { view.stopLoading() }
        view.webChromeClient = null
        runCatching { view.destroy() }
        if (webView === view) {
            webView = null
            loadedNavigation = null
        }
    }

    fun emit(
        action: BrowserTaskAction,
        url: String? = null,
        message: String? = null,
        query: String? = null,
        userInitiated: Boolean = false,
        expected: BrowserNavigationKey? = null
    ) {
        val current = latestTask
        if (expected != null && (expected.taskId != current.taskId || expected.navigationId != current.navigationId)) return
        latestOnEvent(BrowserTaskEvent(
            current.taskId, current.navigationId, action, url, message, query, userInitiated
        ))
    }

    fun close() {
        webView?.stopLoading()
        historyCommand = null
        emit(BrowserTaskAction.CANCEL)
        latestOnDismiss()
    }

    fun requestNavigation(url: String, userInitiated: Boolean, query: String? = null) {
        if (!latestTask.windowVisible || latestTask.phase == BrowserTaskPhase.CANCELLED) return
        webView?.stopLoading()
        emit(BrowserTaskAction.REQUEST_NAVIGATION, url = url, query = query, userInitiated = userInitiated)
    }

    fun goBack() {
        if (navigationHistory.size <= 1) {
            close()
            return
        }
        val url = navigationHistory[navigationHistory.lastIndex - 1]
        historyCommand = BrowserHistoryCommand(url)
        requestNavigation(url, userInitiated = true)
    }

    fun submitSearch() {
        val url = InternalBrowserUrlPolicy.buildSearchUrl(searchUrlTemplate, searchQuery)
        if (url == null) {
            localError = "搜索地址或关键词无效"
            return
        }
        localError = null
        searchExpanded = false
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        requestNavigation(url, userInitiated = true, query = searchQuery.trim())
    }

    BackHandler {
        if (searchFocused) {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            searchFocused = false
        } else goBack()
    }
    ConsumeImeBackHandler()

    LaunchedEffect(task.taskId) {
        if (task.phase == BrowserTaskPhase.QUEUED) emit(BrowserTaskAction.VISIBLE)
        if (rendererGone && !task.isTerminal) {
            rendererGone = false
            rendererEpoch += 1
        }
    }
    LaunchedEffect(task.taskId, task.navigationId, task.phase, task.windowVisible, webView) {
        val view = webView ?: return@LaunchedEffect
        val url = task.currentUrl
        if (!task.windowVisible || task.phase == BrowserTaskPhase.AWAITING_CONFIRMATION || task.isTerminal) {
            view.stopLoading()
            return@LaunchedEffect
        }
        if (url == null || (task.phase != BrowserTaskPhase.NAVIGATING && task.phase != BrowserTaskPhase.SEARCHING)) return@LaunchedEffect
        if (!InternalBrowserUrlPolicy.allowsNavigation(task.initialUrl, url, additionalApprovedOrigins = task.approvedOrigins)) {
            emit(BrowserTaskAction.FAIL, message = "导航来源未获批准")
            return@LaunchedEffect
        }
        val navigation = BrowserNavigationKey(task.taskId, task.navigationId, url)
        if (loadedNavigation == navigation) return@LaunchedEffect
        view.stopLoading()
        loadedNavigation = navigation
        localError = null
        progress = 0
        val command = historyCommand
        historyCommand = null
        activeHistoryCommand = command?.takeIf { it.url == url }
        view.loadUrl(url)
    }
    val loading = task.phase == BrowserTaskPhase.NAVIGATING || task.phase == BrowserTaskPhase.SEARCHING
    val currentUrl = task.currentUrl ?: task.initialUrl
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { goBack() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(pageTitle.ifBlank { InternalBrowserUrlPolicy.allowedHost(currentUrl).orEmpty() },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(currentUrl, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { searchExpanded = !searchExpanded }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Search, contentDescription = "搜索网页")
            }
            IconButton(
                onClick = {
                    if (task.phase == BrowserTaskPhase.FAILED || task.phase == BrowserTaskPhase.BLOCKED) {
                        emit(BrowserTaskAction.RETRY)
                    } else {
                        requestNavigation(currentUrl, userInitiated = true)
                    }
                }, modifier = Modifier.size(38.dp)
            ) { Icon(Icons.Default.Refresh, contentDescription = "刷新网页") }
            IconButton(onClick = { close() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭网页")
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.phase.browserLabel(), style = MaterialTheme.typography.labelMedium)
            task.query?.takeIf { it.isNotBlank() }?.let {
                Text(it, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { webView?.zoomOut() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ZoomOut, contentDescription = "缩小网页")
            }
            IconButton(onClick = { webView?.zoomIn() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ZoomIn, contentDescription = "放大网页")
            }
        }
        if (searchExpanded) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextField(value = searchQuery, onValueChange = { searchQuery = it.take(512) },
                    modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.isFocused },
                    singleLine = true, label = { Text("搜索关键词") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }))
                IconButton(onClick = { submitSearch() }) {
                    Icon(Icons.Default.Search, contentDescription = "开始搜索")
                }
            }
        }
        if (task.phase == BrowserTaskPhase.AWAITING_CONFIRMATION) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("访问新的来源", style = MaterialTheme.typography.titleSmall)
                Text(task.pendingUrl.orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { emit(BrowserTaskAction.CONFIRM_NAVIGATION, userInitiated = true) }) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("访问", modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = { emit(BrowserTaskAction.REJECT_NAVIGATION, message = "已取消此跳转") }) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("取消跳转", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
        task.events.takeLast(2).forEach { event ->
            Text("${event.phase.browserLabel()} · ${event.url.orEmpty()}",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().height(2.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!rendererGone) key(rendererEpoch, task.taskId, task.navigationId) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                    // A fresh main-navigation renderer owns immutable callback identity.
                    val ownedNavigation = BrowserNavigationKey(task.taskId, task.navigationId, currentUrl)
                    WebView(context).apply {
                        webView = this
                        loadedNavigation = null
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            allowFileAccess = false
                            allowContentAccess = false
                            allowUniversalAccessFromFileURLs = false
                            allowFileAccessFromFileURLs = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            setSupportMultipleWindows(false)
                            setGeolocationEnabled(false)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
                        }
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        setDownloadListener { _, _, _, _, _ -> localError = "网页下载已拦截" }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!request.isForMainFrame) return false
                                val url = request.url.toString()
                                val current = latestTask
                                if (ownedNavigation.taskId != current.taskId || ownedNavigation.navigationId != current.navigationId) return true
                                if (url == current.currentUrl && loadedNavigation?.taskId == current.taskId &&
                                    loadedNavigation?.navigationId == current.navigationId && !current.isTerminal
                                ) return false
                                view.stopLoading()
                                emit(BrowserTaskAction.REQUEST_NAVIGATION, url = url,
                                    userInitiated = request.hasGesture(), expected = ownedNavigation)
                                return true
                            }
                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                val current = latestTask
                                if (ownedNavigation.taskId != current.taskId || ownedNavigation.navigationId != current.navigationId) return true
                                if (url == current.currentUrl && loadedNavigation?.taskId == current.taskId &&
                                    loadedNavigation?.navigationId == current.navigationId && !current.isTerminal
                                ) return false
                                view.stopLoading()
                                emit(BrowserTaskAction.REQUEST_NAVIGATION, url = url,
                                    userInitiated = view.hitTestResult.type != WebView.HitTestResult.UNKNOWN_TYPE,
                                    expected = ownedNavigation)
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                val expected = ownedNavigation
                                val current = latestTask
                                if (expected.taskId != current.taskId || expected.navigationId != current.navigationId || current.isTerminal) {
                                    view.stopLoading()
                                    return
                                }
                                if (url != expected.url || !InternalBrowserUrlPolicy.allowsNavigation(
                                    current.initialUrl, url, additionalApprovedOrigins = current.approvedOrigins
                                )) {
                                    view.stopLoading()
                                    emit(BrowserTaskAction.REQUEST_NAVIGATION, url = url, expected = expected)
                                } else progress = 0
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                val expected = ownedNavigation
                                if (url != expected.url || view.url != url) return
                                if (expected.taskId != latestTask.taskId || expected.navigationId != latestTask.navigationId || latestTask.isTerminal) return
                                if (activeHistoryCommand?.url == url && navigationHistory.size > 1) {
                                    navigationHistory = navigationHistory.dropLast(1)
                                } else if (navigationHistory.lastOrNull() != url) {
                                    navigationHistory = (navigationHistory + url).takeLast(100)
                                }
                                activeHistoryCommand = null
                                progress = 100
                                emit(BrowserTaskAction.COMPLETE, url = url, expected = expected)
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame && request.url.toString() == ownedNavigation.url) {
                                    emit(BrowserTaskAction.FAIL, message = "网页加载失败 (${error.errorCode})：${error.description}", expected = ownedNavigation)
                                }
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                if (request.isForMainFrame && request.url.toString() == ownedNavigation.url) {
                                    emit(BrowserTaskAction.FAIL, message = "网页返回 HTTP ${errorResponse.statusCode}", expected = ownedNavigation)
                                }
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel()
                                emit(BrowserTaskAction.FAIL, message = "HTTPS 证书校验失败 (${error.primaryError})", expected = ownedNavigation)
                            }
                            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                val wasCurrent = webView === view
                                disposeView(view)
                                if (wasCurrent) {
                                    rendererGone = true
                                }
                                emit(BrowserTaskAction.FAIL, message = "网页渲染进程已停止", expected = ownedNavigation)
                                return true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                if (webView === view && view.url == latestTask.currentUrl && !latestTask.isTerminal) progress = newProgress
                            }
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                if (webView === view && view.url == latestTask.currentUrl) pageTitle = title.orEmpty()
                            }
                        }
                    }
                }, onRelease = { view ->
                    disposeView(view)
                })
            }
            (localError ?: task.message)?.let { message ->
                Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(message, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (loading && progress == 0) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(28.dp))
        }
    }
}

private fun BrowserTaskPhase.browserLabel(): String = when (this) {
    BrowserTaskPhase.QUEUED -> "等待窗口"
    BrowserTaskPhase.SEARCHING -> "搜索中"
    BrowserTaskPhase.AWAITING_CONFIRMATION -> "待批准"
    BrowserTaskPhase.NAVIGATING -> "访问中"
    BrowserTaskPhase.COMPLETED -> "已加载"
    BrowserTaskPhase.CANCELLED -> "已取消"
    BrowserTaskPhase.BLOCKED -> "已拦截"
    BrowserTaskPhase.FAILED -> "加载失败"
}

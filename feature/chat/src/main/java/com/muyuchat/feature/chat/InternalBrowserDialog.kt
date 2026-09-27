package com.muyuchat.feature.chat

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.SslErrorHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

private const val DEFAULT_SEARCH_URL_TEMPLATE = "https://www.google.com/search?q={query}"

/**
 * Small, visible, cancellable web preview for an explicitly opened source.
 * The WebView is deliberately not exposed as a general browser entry point.
 */
@Composable
fun InternalBrowserDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    searchUrlTemplate: String = DEFAULT_SEARCH_URL_TEMPLATE
) {
    val normalizedUrl = remember(initialUrl) {
        InternalBrowserUrlPolicy.normalizeInitialUrl(initialUrl)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 14.dp),
            color = MaterialTheme.colorScheme.background,
            shape = MaterialTheme.shapes.large
        ) {
            if (normalizedUrl == null) {
                InvalidBrowserUrl(onDismiss = onDismiss)
            } else {
                InternalBrowserContent(
                    initialUrl = normalizedUrl,
                    searchUrlTemplate = searchUrlTemplate,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun InvalidBrowserUrl(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("无法在应用内打开此链接", style = MaterialTheme.typography.titleMedium)
        Text(
            "来源链接必须是安全的 HTTPS 网页。下一步：返回来源卡片，复制链接后在可信浏览器中查看。",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) {
            Text("关闭")
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InternalBrowserContent(
    initialUrl: String,
    searchUrlTemplate: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var pageTitle by rememberSaveable(initialUrl) { mutableStateOf("") }
    var loading by rememberSaveable(initialUrl) { mutableStateOf(true) }
    var progress by rememberSaveable(initialUrl) { mutableIntStateOf(0) }
    var errorText by rememberSaveable(initialUrl) { mutableStateOf<String?>(null) }
    var searchExpanded by rememberSaveable(initialUrl) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(initialUrl) { mutableStateOf("") }
    var searchFocused by rememberSaveable(initialUrl) { mutableStateOf(false) }
    var rendererGone by rememberSaveable(initialUrl) { mutableStateOf(false) }
    val searchHost = remember(searchUrlTemplate) {
        InternalBrowserUrlPolicy.buildSearchUrl(searchUrlTemplate, "mca")?.let {
            InternalBrowserUrlPolicy.allowedHost(it)
        }
    }
    val additionalAllowedHosts = remember(searchHost) {
        searchHost?.let { setOf(it) } ?: emptySet()
    }

    fun submitSearch() {
        val url = InternalBrowserUrlPolicy.buildSearchUrl(searchUrlTemplate, searchQuery)
        if (url == null) {
            errorText = "搜索链接配置无效或关键词为空。下一步：输入关键词，或联系应用维护者配置 HTTPS 搜索地址。"
            return
        }
        val view = webView
        if (view == null) {
            errorText = "网页尚未准备好。下一步：等待页面加载完成后重试。"
            return
        }
        errorText = null
        searchExpanded = false
        view.loadUrl(url)
    }

    BackHandler {
        if (searchFocused) {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            searchFocused = false
        } else {
            val view = webView
            if (view?.canGoBack() == true) {
                view.goBack()
            } else {
                onDismiss()
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView?.apply {
                stopLoading()
                webChromeClient = null
                destroy()
            }
            webView = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val view = webView
                    if (view?.canGoBack() == true) view.goBack() else onDismiss()
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pageTitle.ifBlank { Uri.parse(currentUrl).host.orEmpty() },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    currentUrl,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = { webView?.zoomOut() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.ZoomOut, contentDescription = "缩小网页")
            }
            IconButton(onClick = { webView?.zoomIn() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.ZoomIn, contentDescription = "放大网页")
            }
            IconButton(onClick = { searchExpanded = !searchExpanded }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Search, contentDescription = "在网页中搜索")
            }
            IconButton(onClick = { webView?.reload() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新网页")
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭网页")
            }
        }
        if (searchExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(512) },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { searchFocused = it.isFocused },
                    singleLine = true,
                    label = { Text("搜索关键词") },
                    placeholder = { Text("在安全 HTTPS 搜索引擎中查找") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() })
                )
                TextButton(onClick = { submitSearch() }) { Text("搜索") }
            }
        }
        if (loading) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (rendererGone) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("网页渲染已停止", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "MCA 已隔离网页渲染进程，避免网页崩溃影响应用。下一步：关闭此页面后重新打开来源。",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        onClick = {
                            rendererGone = false
                            errorText = null
                            loading = true
                            progress = 0
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("重新加载")
                    }
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        WebView(context).apply {
                            webView = this
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                safeBrowsingEnabled = true
                            }
                            }
                            android.webkit.CookieManager.getInstance()
                                .setAcceptThirdPartyCookies(this, false)
                            settings.setGeolocationEnabled(false)
                            setDownloadListener { _, _, _, _, _ ->
                            errorText = "网页下载已拦截。下一步：复制链接后使用系统下载工具，并确认来源可信。"
                        }
                            webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val url = request.url.toString()
                                val allowed = InternalBrowserUrlPolicy.allowsNavigation(
                                    initialUrl,
                                    url,
                                    additionalAllowedHosts
                                )
                                if (!allowed) {
                                    errorText = "已拦截跨域跳转：$url"
                                }
                                return !allowed
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                val allowed = InternalBrowserUrlPolicy.allowsNavigation(
                                    initialUrl,
                                    url,
                                    additionalAllowedHosts
                                )
                                if (!allowed) errorText = "已拦截跨域跳转：$url"
                                return !allowed
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                currentUrl = url
                                loading = true
                                progress = 0
                                errorText = null
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (!InternalBrowserUrlPolicy.allowsNavigation(initialUrl, url, additionalAllowedHosts)) {
                                    view.stopLoading()
                                    errorText = "已拦截不安全或跨域跳转。下一步：返回来源页，或复制链接后在可信浏览器中查看。"
                                    loading = false
                                    progress = 100
                                    return
                                }
                                currentUrl = url
                                loading = false
                                progress = 100
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                if (request.isForMainFrame) {
                                    loading = false
                                    errorText = "网页加载失败：${error.description}"
                                }
                            }

                            @Suppress("DEPRECATION")
                            override fun onReceivedSslError(
                                view: WebView,
                                handler: SslErrorHandler,
                                error: SslError
                            ) {
                                // Never continue through an invalid certificate.  The
                                // source can be copied to a trusted browser if the user
                                // needs to inspect the certificate details.
                                handler.cancel()
                                loading = false
                                errorText = "HTTPS 证书校验失败。下一步：检查设备时间或在可信浏览器中确认该网址。"
                            }

                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: android.webkit.RenderProcessGoneDetail
                            ): Boolean {
                                // A renderer crash must not take down MCA.  The dialog
                                // remains visible with a retryable error instead.
                                runCatching { view.destroy() }
                                webView = null
                                rendererGone = true
                                loading = false
                                progress = 0
                                errorText = "网页渲染进程已停止。下一步：关闭后重新打开该来源。"
                                return true
                            }
                            }
                            webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title.orEmpty()
                            }
                            }
                            loadUrl(initialUrl)
                        }
                    },
                    update = { view ->
                        if (view.url.isNullOrBlank()) view.loadUrl(initialUrl)
                    }
                )
            }
            errorText?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (loading && progress == 0) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                )
            }
        }
    }
}

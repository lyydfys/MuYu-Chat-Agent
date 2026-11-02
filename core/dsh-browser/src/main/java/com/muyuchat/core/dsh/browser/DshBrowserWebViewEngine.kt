package com.muyuchat.core.dsh.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin Android adapter of [DshBrowserEnginePort] over an off-screen `WebView` in the `:browser`
 * process.
 *
 * Design constraints that come from the platform, not from preference:
 *  - A `WebView` only lays out when it is attached to a real window. A service process has no
 *    Activity, so the adapter adds one invisible `TYPE_APPLICATION_OVERLAY` window (the
 *    `SYSTEM_ALERT_WINDOW` permission is the precondition; without it the engine throws
 *    `UNAVAILABLE` instead of pretending to work). This mirrors the pinned reference's design.
 *  - Because that window is invisible, clicks are dispatched with scripted `element.click()`
 *    (`via: "js"`), never real touch coordinates. The reference does the same when its host
 *    window is not shown.
 *  - One `WebView` per tab id; every WebView access runs on the main looper.
 *  - File/content access, mixed content and third-party cookies are off; downloads are cancelled
 *    (there is no download staging sink in this build) and reported through `caps.downloads`.
 *  - The profile is the process-local `dsh-browser` data directory; cookies are session-only and
 *    cleared by [close].
 *
 * Honest limits, reported rather than hidden: DNS rebinding to loopback cannot be detected by a
 * URL-string policy; the adapter re-applies [DshBrowserUrlPolicy] to every main-frame navigation
 * and redirect, and the session re-validates the observed final URL.
 */
class DshBrowserWebViewEngine(
    private val context: Context,
    private val maxTabs: Int = DshBrowserLimits.MAX_TABS
) : DshBrowserEnginePort {

    private class Tab(val id: String, val web: WebView) {
        var pendingLoad: CompletableDeferred<DshBrowserLoadOutcome>? = null
        var documentEpoch: Long = 0L
        var lastBlockedUrl: String? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val tabs = ConcurrentHashMap<String, Tab>()
    private var windowRoot: FrameLayout? = null
    private var windowManager: WindowManager? = null
    private var closed = false

    private val overlayGranted: Boolean
        get() = Settings.canDrawOverlays(context)

    override suspend fun createTab(tabId: String, startUrl: String?) = withContext(Dispatchers.Main.immediate) {
        if (closed) throw DshBrowserEngineException(DshBrowserEngineError.CLOSED, "the browser engine is closed")
        if (!overlayGranted) {
            throw DshBrowserEngineException(
                DshBrowserEngineError.UNAVAILABLE,
                DshBrowserProcessPolicy.OVERLAY_PERMISSION
            )
        }
        if (tabs.containsKey(tabId)) return@withContext
        if (tabs.size >= maxTabs) {
            throw DshBrowserEngineException(DshBrowserEngineError.INTERNAL, "tab cap reached")
        }
        val root = ensureWindowOnMain()
        val web = newWebView()
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val tab = Tab(tabId, web)
        web.webViewClient = BrowserClient { tabs[tabId] }
        web.setDownloadListener(DownloadListener { _, _, _, _, _ ->
            // Fail closed: no download staging sink exists in this build, so the request is dropped
            // and the DSH home is never written by a page. `caps.downloads` reports "reject".
            web.stopLoading()
        })
        tabs[tabId] = tab
        if (!startUrl.isNullOrBlank()) web.loadUrl(startUrl)
        Unit
    }

    override suspend fun closeTab(tabId: String) = withContext(Dispatchers.Main.immediate) {
        val tab = tabs.remove(tabId) ?: return@withContext
        tab.pendingLoad?.cancel()
        windowRoot?.removeView(tab.web)
        tab.web.stopLoading()
        tab.web.destroy()
        Unit
    }

    override suspend fun load(tabId: String, url: String): DshBrowserLoadOutcome =
        withContext(Dispatchers.Main.immediate) {
            val tab = tabs[tabId] ?: throw closedOrMissing()
            tab.lastBlockedUrl = null
            val completion = CompletableDeferred<DshBrowserLoadOutcome>()
            tab.pendingLoad = completion
            tab.web.loadUrl(url)
            withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) { completion.await() }
                ?: DshBrowserLoadOutcome(
                    state = DshBrowserLoadState.LOADING,
                    url = tab.web.url ?: url,
                    title = tab.web.title ?: "",
                    ready = false
                )
        }

    override suspend fun observe(tabId: String, includeText: Boolean): DshBrowserObservation =
        withContext(Dispatchers.Main.immediate) {
            val tab = tabs[tabId] ?: throw closedOrMissing()
            tab.lastBlockedUrl?.let { reason ->
                tab.lastBlockedUrl = null
                throw DshBrowserEngineException(DshBrowserEngineError.NAVIGATION_BLOCKED, reason)
            }
            val raw = evalJs(tab, SNAPSHOT_JS) ?: throw DshBrowserEngineException(
                DshBrowserEngineError.SCRIPT_FAILED,
                "snapshot script returned nothing"
            )
            val root = JSONObject(raw)
            val viewport = root.optJSONObject("viewport")
            val nodesJson = root.optJSONArray("nodes")
            val nodes = ArrayList<DshBrowserNode>()
            if (nodesJson != null) {
                for (index in 0 until nodesJson.length()) {
                    val n = nodesJson.optJSONObject(index) ?: continue
                    val ref = n.optString("ref", "")
                    if (ref.isEmpty()) continue
                    val boundsJson = n.optJSONArray("bounds")
                    val bounds = if (boundsJson != null && boundsJson.length() == 4) {
                        DshBrowserBounds(
                            left = boundsJson.optInt(0),
                            top = boundsJson.optInt(1),
                            width = boundsJson.optInt(2),
                            height = boundsJson.optInt(3)
                        )
                    } else {
                        null
                    }
                    nodes.add(
                        DshBrowserNode(
                            ref = ref,
                            role = n.optString("role", "").take(DshBrowserLimits.MAX_ROLE_CHARS),
                            name = n.optString("name", "").take(DshBrowserLimits.MAX_NAME_CHARS),
                            bounds = bounds,
                            inView = n.optBoolean("inView", false),
                            disabled = n.optBoolean("disabled", false),
                            dup = n.optBoolean("dup", false)
                        )
                    )
                }
            }
            val textLines = if (includeText) readTextLines(tab) else null
            val mutation = tab.documentEpoch * 1_000_000L + root.optLong("mutation", 0L)
            DshBrowserObservation(
                url = root.optString("url", tab.web.url ?: DshBrowserTabs.ABOUT_BLANK),
                title = root.optString("title", tab.web.title ?: "").take(300),
                viewport = DshBrowserViewport(
                    width = viewport?.optInt("width", 0) ?: 0,
                    height = viewport?.optInt("height", 0) ?: 0,
                    scale = viewport?.optDouble("scale", 1.0) ?: 1.0
                ),
                nodes = nodes,
                textLines = textLines,
                truncated = root.optBoolean("truncated", false),
                mutationCount = mutation
            )
        }

    override suspend fun resolve(tabId: String, ref: String): DshBrowserTarget? =
        withContext(Dispatchers.Main.immediate) {
            val tab = tabs[tabId] ?: throw closedOrMissing()
            val raw = evalJs(tab, resolveScript(ref)) ?: return@withContext null
            val json = JSONObject(raw)
            if (!json.optBoolean("found", false)) {
                return@withContext DshBrowserTarget(
                    ref = ref,
                    found = false,
                    disabled = false,
                    editable = false,
                    inView = false,
                    name = "",
                    role = "",
                    bounds = null
                )
            }
            val boundsJson = json.optJSONArray("bounds")
            DshBrowserTarget(
                ref = ref,
                found = true,
                disabled = json.optBoolean("disabled", false),
                editable = json.optBoolean("editable", false),
                inView = json.optBoolean("inView", false),
                name = json.optString("name", "").take(DshBrowserLimits.MAX_NAME_CHARS),
                role = json.optString("role", "").take(DshBrowserLimits.MAX_ROLE_CHARS),
                bounds = if (boundsJson != null && boundsJson.length() == 4) {
                    DshBrowserBounds(
                        boundsJson.optInt(0),
                        boundsJson.optInt(1),
                        boundsJson.optInt(2),
                        boundsJson.optInt(3)
                    )
                } else {
                    null
                }
            )
        }

    override suspend fun click(tabId: String, target: DshBrowserTarget): DshBrowserClickDispatch =
        withContext(Dispatchers.Main.immediate) {
            val tab = tabs[tabId] ?: throw closedOrMissing()
            val raw = evalJs(tab, clickScript(target.ref))
            val dispatched = raw != null && JSONObject(raw).optBoolean("ok", false)
            if (!dispatched) {
                throw DshBrowserEngineException(DshBrowserEngineError.SCRIPT_FAILED, "click script failed")
            }
            DshBrowserClickDispatch(
                via = "js",
                clickedAt = listOf(
                    (target.bounds?.left ?: 0) + (target.bounds?.width ?: 0) / 2,
                    (target.bounds?.top ?: 0) + (target.bounds?.height ?: 0) / 2
                )
            )
        }

    override suspend fun type(
        tabId: String,
        target: DshBrowserTarget,
        text: String,
        replace: Boolean
    ): DshBrowserTypeDispatch = withContext(Dispatchers.Main.immediate) {
        val tab = tabs[tabId] ?: throw closedOrMissing()
        val raw = evalJs(tab, typeScript(target.ref, text, replace))
            ?: throw DshBrowserEngineException(DshBrowserEngineError.SCRIPT_FAILED, "type script failed")
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) {
            throw DshBrowserEngineException(DshBrowserEngineError.SCRIPT_FAILED, "the field rejected the text")
        }
        DshBrowserTypeDispatch(
            value = json.optString("value", "").take(DshBrowserLimits.MAX_TEXT_CHARS),
            via = json.optString("via", "setter"),
            dispatched = json.optBoolean("dispatched", true)
        )
    }

    override suspend fun read(tabId: String, ref: String?): DshBrowserTextRead =
        withContext(Dispatchers.Main.immediate) {
            val tab = tabs[tabId] ?: throw closedOrMissing()
            val raw = evalJs(tab, readScript(ref))
                ?: throw DshBrowserEngineException(DshBrowserEngineError.SCRIPT_FAILED, "read script failed")
            val json = JSONObject(raw)
            DshBrowserTextRead(
                text = json.optString("text", ""),
                isField = json.optBoolean("field", false),
                tag = json.optString("tag", ""),
                type = json.optString("type", ""),
                value = json.optString("value", ""),
                placeholder = json.optString("placeholder", ""),
                disabled = json.optBoolean("disabled", false)
            )
        }

    override suspend fun navigate(tabId: String, op: DshBrowserNavOp): DshBrowserNavResult =
        withContext(Dispatchers.Main.immediate) {
            val tab = tabs[tabId] ?: throw closedOrMissing()
            when (op) {
                DshBrowserNavOp.BACK -> {
                    if (!tab.web.canGoBack()) return@withContext DshBrowserNavResult(noHistory = true)
                    tab.web.goBack()
                }
                DshBrowserNavOp.FORWARD -> {
                    if (!tab.web.canGoForward()) return@withContext DshBrowserNavResult(noHistory = true)
                    tab.web.goForward()
                }
                DshBrowserNavOp.RELOAD -> tab.web.reload()
                DshBrowserNavOp.CLOSE -> throw DshBrowserEngineException(
                    DshBrowserEngineError.INTERNAL,
                    "close is handled by the session"
                )
            }
            DshBrowserNavResult(noHistory = false)
        }

    override suspend fun scroll(
        tabId: String,
        direction: DshBrowserScrollDirection,
        amount: Int
    ): DshBrowserScrollPosition = withContext(Dispatchers.Main.immediate) {
        val tab = tabs[tabId] ?: throw closedOrMissing()
        val raw = evalJs(tab, scrollScript(direction, amount))
            ?: throw DshBrowserEngineException(DshBrowserEngineError.SCRIPT_FAILED, "scroll script failed")
        val json = JSONObject(raw)
        DshBrowserScrollPosition(
            scrollY = json.optInt("scrollY", 0),
            innerHeight = json.optInt("innerHeight", 0),
            docHeight = json.optInt("docHeight", 0)
        )
    }

    override fun capabilities(): DshBrowserEngineCaps = DshBrowserEngineCaps(
        engine = "android-webview",
        userAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull(),
        viewport = null,
        processIsolation = overlayGranted,
        overlayAvailable = overlayGranted,
        downloadsPolicy = "reject"
    )

    override suspend fun close() = withContext(Dispatchers.Main.immediate) {
        if (closed) return@withContext
        closed = true
        tabs.keys.toList().forEach { id ->
            tabs.remove(id)?.let { tab ->
                runCatching { tab.pendingLoad?.cancel() }
                runCatching { windowRoot?.removeView(tab.web) }
                runCatching { tab.web.stopLoading() }
                runCatching { tab.web.destroy() }
            }
        }
        runCatching { windowRoot?.let { windowManager?.removeViewImmediate(it) } }
        windowRoot = null
        windowManager = null
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        runCatching { CookieManager.getInstance().flush() }
        runCatching { WebStorage.getInstance().deleteAllData() }
        Unit
    }

    // ------------------------------------------------------------ platform plumbing

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val web = WebView(context)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            web,
            DshBrowserProcessPolicy.ALLOW_THIRD_PARTY_COOKIES
        )

        web.webChromeClient = WebChromeClient()
        web.setBackgroundColor(0x00000000)
        return web
    }

    private fun ensureWindowOnMain(): FrameLayout {
        windowRoot?.let { return it }
        val wm = windowManager
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }
        val root = FrameLayout(context)
        root.visibility = View.INVISIBLE
        val metrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, params)
        windowRoot = root
        return root
    }

    /** Runs one scripted DOM operation; null means "the page returned nothing usable". */
    private suspend fun evalJs(tab: Tab, script: String): String? {
        val deferred = CompletableDeferred<String?>()
        main.post {
            try {
                tab.web.evaluateJavascript(script) { value ->
                    val text: String? = value
                    val cleaned = runCatching {
                        if (text == null || text == "null" || text.isEmpty()) {
                            null
                        } else if (text.startsWith("\"") && text.endsWith("\"")) {
                            JSONObject("{\"v\":$text}").optString("v")
                        } else {
                            text
                        }
                    }.getOrNull()
                    deferred.complete(cleaned)
                }
            } catch (error: Throwable) {
                deferred.completeExceptionally(
                    DshBrowserEngineException(
                        DshBrowserEngineError.SCRIPT_FAILED,
                        error.message ?: "evaluateJavascript failed"
                    )
                )
            }
        }
        return withTimeoutOrNull(SCRIPT_TIMEOUT_MILLIS) { deferred.await() }
    }

    private suspend fun readTextLines(tab: Tab): List<String>? {
        val raw = evalJs(tab, TEXT_JS) ?: return null
        val json = JSONObject(raw)
        val lines = json.optJSONArray("lines") ?: return emptyList()
        val result = ArrayList<String>(lines.length())
        for (index in 0 until lines.length()) {
            result.add(lines.optString(index).take(DshBrowserLimits.TEXT_LINE_CHARS))
        }
        return result
    }

    private fun closedOrMissing(): DshBrowserEngineException =
        if (closed) {
            DshBrowserEngineException(DshBrowserEngineError.CLOSED, "the browser engine is closed")
        } else {
            DshBrowserEngineException(DshBrowserEngineError.CLOSED, "no WebView for tab")
        }

    /** Main-frame navigation/redirect admission: the same pure policy the session uses. */
    private inner class BrowserClient(private val tab: () -> Tab?) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return true
            if (!request.isForMainFrame) return false
            return when (val decision = DshBrowserUrlPolicy.evaluateRedirect(url)) {
                is DshBrowserUrlDecision.Allow -> false
                is DshBrowserUrlDecision.Block -> {
                    tab()?.lastBlockedUrl = decision.reason.name.lowercase()
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            tab()?.let { current ->
                current.documentEpoch += 1
                current.lastBlockedUrl = null
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val current = tab() ?: return
            current.pendingLoad?.complete(
                DshBrowserLoadOutcome(
                    state = DshBrowserLoadState.READY,
                    url = url ?: view?.url ?: "",
                    title = view?.title ?: "",
                    ready = true
                )
            )
            current.pendingLoad = null
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?
        ) {
            if (request?.isForMainFrame == false) return
            val current = tab() ?: return
            current.pendingLoad?.complete(
                DshBrowserLoadOutcome(
                    state = DshBrowserLoadState.ERROR,
                    url = request?.url?.toString() ?: view?.url ?: "",
                    title = view?.title ?: "",
                    ready = false,
                    errorDetail = "net-error:${error?.errorCode ?: -1}"
                )
            )
            current.pendingLoad = null
        }
    }

    companion object {
        private const val SCRIPT_TIMEOUT_MILLIS = 10_000L
        private const val LOAD_TIMEOUT_MILLIS = 25_000L

        internal const val COMMON_JS = """
function __dshnm(el){var v=el.getAttribute&&(el.getAttribute('aria-label')||el.getAttribute('placeholder')||el.getAttribute('title')||el.getAttribute('alt'));if(!v){v=(el.innerText||el.textContent||'');}if(!v&&el.value!==undefined&&el.value!==null){v=String(el.value);}v=String(v||'').replace(/\s+/g,' ').replace(/^\s+|\s+$/g,'');if(v.length>120)v=v.slice(0,120);return v;}
function __dshrl(el){var r=el.getAttribute&&el.getAttribute('role');if(r)return r;var t=el.tagName?el.tagName.toUpperCase():'';if(t==='A')return 'link';if(t==='BUTTON')return 'button';if(t==='SELECT')return 'combobox';if(t==='INPUT'||t==='TEXTAREA'){var ty=(el.getAttribute('type')||'').toLowerCase();if(ty==='submit'||ty==='button'||ty==='checkbox'||ty==='radio')return ty;return 'textbox';}if(el.hasAttribute&&el.hasAttribute('onclick'))return 'clickable';if(el.hasAttribute&&el.hasAttribute('tabindex'))return 'focusable';return 'text';}
function __dshdp(el){var parts=[],n=el,d=0;while(n&&n.nodeType===1&&d<6&&n!==document.body){var i=1,s=n;while((s=s.previousElementSibling)){if(s.tagName===n.tagName)i++;}parts.unshift(n.tagName.toLowerCase()+'['+i+']');n=n.parentElement;d++;}return parts.join('>');}
function __dshfnv(s){var h=0x811c9dc5;for(var i=0;i<s.length;i++){h^=s.charCodeAt(i);h=(h*0x01000193)>>>0;}return ('0000000'+h.toString(16)).slice(-7);}
function __dshfp(el){var tag=(el.tagName||'').toLowerCase();var ty=(el.getAttribute&&el.getAttribute('type')||'').toLowerCase();var href='';if(tag==='a'&&el.href){try{var u=new URL(el.href);href=u.host+u.pathname;}catch(e){href=String(el.href).slice(0,80);}}return tag+'|'+ty+'|'+__dshrl(el)+'|'+__dshnm(el)+'|'+href+'|'+__dshdp(el);}
function __dshnodes(){var SEL='a[href],button,input,select,textarea,[role],[onclick],[tabindex],[contenteditable]';var all=document.querySelectorAll(SEL);var out=[],used={},trunc=false;for(var j=0;j<all.length;j++){var el=all[j];var r=el.getBoundingClientRect();if(r.width<1||r.height<1)continue;var st=window.getComputedStyle?window.getComputedStyle(el):null;if(st&&(st.visibility==='hidden'||st.display==='none'))continue;if(out.length>=400){trunc=true;break;}var base='r'+__dshfnv(__dshfp(el));var ref=base,dup=1;while(used[ref]){dup++;ref=base+'-'+dup;}used[ref]=1;el.setAttribute('data-dsh-ref',ref);var inView=(r.bottom>0&&r.top<window.innerHeight&&r.right>0&&r.left<window.innerWidth);var dis=!!(el.disabled||(el.getAttribute&&el.getAttribute('aria-disabled')==='true'));out.push({ref:ref,role:__dshrl(el),name:__dshnm(el),bounds:[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)],inView:inView,disabled:dis,dup:dup>1});}return {nodes:out,truncated:trunc};}
function __dshmut(){if(!window.__dshMutObs){window.__dshMut=0;window.__dshMutObs=new MutationObserver(function(m){window.__dshMut+=m.length;});window.__dshMutObs.observe(document,{subtree:true,childList:true,attributes:true,characterData:true});}return window.__dshMut||0;}
function __dshfind(ref){return document.querySelector('[data-dsh-ref="'+ref+'"]');}
"""

        internal val SNAPSHOT_JS = "(function(){try{$COMMON_JS" +
            "var r=__dshnodes();return JSON.stringify({url:location.href,title:document.title||''," +
            "viewport:{width:window.innerWidth||0,height:window.innerHeight||0,scale:1}," +
            "mutation:__dshmut(),nodes:r.nodes,truncated:r.truncated});}catch(e){return null;}})()"

        internal val TEXT_JS = "(function(){try{var b=document.body?(document.body.innerText||document.body.textContent||''):'';" +
            "var lines=String(b).split('\\n').filter(function(s){return s.replace(/\\s+/g,'').length>0;}).slice(0,60);" +
            "return JSON.stringify({lines:lines});}catch(e){return null;}})()"

        internal fun resolveScript(ref: String): String =
            "(function(){try{$COMMON_JS" +
                "var el=__dshfind('$ref');if(!el)return JSON.stringify({found:false});" +
                "try{el.scrollIntoView({block:'center',inline:'center'});}catch(e){}" +
                "var r=el.getBoundingClientRect();var tag=(el.tagName||'').toUpperCase();" +
                "var editable=(tag==='INPUT'||tag==='TEXTAREA'||tag==='SELECT'||el.isContentEditable===true);" +
                "return JSON.stringify({found:true,disabled:!!(el.disabled||(el.getAttribute&&el.getAttribute('aria-disabled')==='true'))," +
                "editable:editable,inView:(r.bottom>0&&r.top<window.innerHeight&&r.right>0&&r.left<window.innerWidth)," +
                "name:__dshnm(el),role:__dshrl(el),bounds:[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)]});" +
                "}catch(e){return null;}})()"

        internal fun clickScript(ref: String): String =
            "(function(){try{$COMMON_JS" +
                "var el=__dshfind('$ref');if(!el)return JSON.stringify({ok:false,reason:'stale-ref'});" +
                "try{el.focus();}catch(e){}" +
                "try{el.click();return JSON.stringify({ok:true});}catch(e){return JSON.stringify({ok:false});}" +
                "}catch(e){return null;}})()"

        internal fun typeScript(ref: String, text: String, replace: Boolean): String {
            val encoded = JSONObject.quote(text)
            return "(function(){try{$COMMON_JS" +
                "var el=__dshfind('$ref');if(!el)return JSON.stringify({ok:false,reason:'stale-ref'});" +
                "var text=$encoded,replace=${if (replace) "true" else "false"},done=false,via='';" +
                "try{el.focus();}catch(e){}" +
                "try{if(replace&&el.select)el.select();done=document.execCommand('insertText',false,text);}catch(e){done=false;}" +
                "if(done){via='execCommand';}else{try{var proto=(el.tagName==='TEXTAREA')?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;" +
                "var setter=Object.getOwnPropertyDescriptor(proto,'value');var next=replace?text:(String(el.value||'')+text);" +
                "if(setter&&setter.set){setter.set.call(el,next);}else{el.value=next;}" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));done=true;via='setter';}catch(e2){done=false;}}" +
                "var v=String(el.value===undefined?'':el.value).slice(0,4000);" +
                "return JSON.stringify({ok:done,value:v,via:via,dispatched:done});}catch(e){return null;}})()"
        }

        internal fun readScript(ref: String?): String {
            val selector = if (ref == null) "null" else "'$ref'"
            return "(function(){try{$COMMON_JS" +
                "var ref=$selector;var el=ref?__dshfind(ref):null;" +
                "if(!el){var b=document.body?(document.body.innerText||document.body.textContent||''):'';" +
                "return JSON.stringify({text:String(b),ref:'',field:false});}" +
                "var tag=(el.tagName||'').toUpperCase();var isField=(tag==='INPUT'||tag==='TEXTAREA'||tag==='SELECT');" +
                "var val=isField?String(el.value===undefined?'':el.value):'';" +
                "if(tag==='SELECT'&&el.selectedIndex>=0&&el.options&&el.options[el.selectedIndex]){var op=el.options[el.selectedIndex];val=String(op.text||op.value||'');}" +
                "return JSON.stringify({text:String(el.innerText||el.textContent||''),ref:ref||'',tag:tag.toLowerCase()," +
                "type:String((el.getAttribute&&el.getAttribute('type'))||'').toLowerCase(),value:val," +
                "placeholder:String((el.getAttribute&&el.getAttribute('placeholder'))||'')," +
                "disabled:!!(el.disabled||(el.getAttribute&&el.getAttribute('aria-disabled')==='true')),field:isField});" +
                "}catch(e){return null;}})()"
        }

        internal fun scrollScript(direction: DshBrowserScrollDirection, amount: Int): String {
            val delta = when (direction) {
                DshBrowserScrollDirection.TOP -> "-999999"
                DshBrowserScrollDirection.BOTTOM -> "999999"
                DshBrowserScrollDirection.UP -> "-${amount.coerceIn(1, DshBrowserLimits.MAX_SCROLL_AMOUNT)}"
                DshBrowserScrollDirection.DOWN -> "${amount.coerceIn(1, DshBrowserLimits.MAX_SCROLL_AMOUNT)}"
            }
            return "(function(){try{window.scrollBy(0,$delta);return JSON.stringify({" +
                "scrollY:window.scrollY||0,innerHeight:window.innerHeight||0," +
                "docHeight:(document.documentElement&&document.documentElement.scrollHeight)||0});" +
                "}catch(e){return null;}})()"
        }
    }
}

package com.example.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.toColorInt
import org.json.JSONObject

/**
 * Strips the WebView-specific markers ("; wv" and "Version/x.x") from the default WebView
 * user-agent so Google Sign-In / OAuth (accounts.google.com) does not reject the in-app
 * login flow with 403 disallowed_useragent.
 */
fun stripWebViewMarkersFromUserAgent(defaultUserAgent: String): String {
    return defaultUserAgent
        .replace(Regex(";\\s*wv"), "")
        .replace(Regex("\\bwv\\b"), "")
        .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
        .replace(Regex("\\(\\s+"), "(")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

fun sanitizedChromeUserAgent(context: Context): String {
    return stripWebViewMarkersFromUserAgent(WebSettings.getDefaultUserAgent(context))
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun applyCommonWebViewSettings(webView: WebView, supportMultipleWindows: Boolean) {
    // Configure the host window's soft input mode to adjustResize so IME insets are dispatched
    // properly to the app while keeping keyboard input responsive.
    webView.context.findHostActivity()?.window?.setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
    )

    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    webView.setBackgroundColor("#081421".toColorInt())
    webView.overScrollMode = View.OVER_SCROLL_NEVER
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false

    // Ensure WebView reliably takes and keeps Android View focus for keyboard input
    webView.isFocusable = true
    webView.isFocusableInTouchMode = true
    webView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    webView.setOnTouchListener { view, event ->
        if ((event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP) && !view.hasFocus()
        ) {
            view.requestFocusFromTouch()
        }
        false
    }

    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, true)

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false
        allowFileAccess = true
        allowContentAccess = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(supportMultipleWindows)
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        userAgentString = sanitizedChromeUserAgent(webView.context)
    }
}

/**
 * Fixes web-side CSS/DOM issues that can dismiss the Android soft keyboard immediately after
 * tapping an input:
 * 1) Injects `user-select: text` onto editable elements (including inside Shadow DOM and
 *    same-origin iframes) so global game `user-select: none` CSS does not block typing.
 * 2) Prevents canvas/game scripts from calling `blur()` on the active input or `canvas.focus()`
 *    right after opening the keyboard unless the user genuinely tapped elsewhere or pressed
 *    Enter/Escape/Tab.
 */
fun injectWebInputCompatibilityFix(webView: WebView?) {
    if (webView == null) return
    val script = """
        (function() {
            function patchWindow(win) {
                try {
                    if (!win || !win.document || win.__spbuInputFixApplied) return;
                    win.__spbuInputFixApplied = true;
                    var doc = win.document;

                    var cssText =
                        'input, textarea, select, [contenteditable], [contenteditable="true"], [contenteditable=""] {' +
                        '  -webkit-user-select: text !important;' +
                        '  user-select: text !important;' +
                        '  -webkit-touch-callout: default !important;' +
                        '  touch-action: manipulation !important;' +
                        '  pointer-events: auto !important;' +
                        '}';

                    function injectStyle(root) {
                        if (!root) return;
                        var id = 'spbu-android-input-fix';
                        if (root.getElementById && root.getElementById(id)) return;
                        var style = doc.createElement('style');
                        style.id = id;
                        style.textContent = cssText;
                        (root.head || root.documentElement || root).appendChild(style);
                    }

                    injectStyle(doc);

                    function isEditable(el) {
                        if (!el || !el.tagName) return false;
                        var tag = el.tagName.toLowerCase();
                        return tag === 'input' || tag === 'textarea' || el.isContentEditable;
                    }

                    var protectedEl = null;
                    var protectedAt = 0;
                    var lastResizeAt = 0;
                    var userDismissed = false;

                    function isProtectedNow() {
                        if (!protectedEl || userDismissed) return false;
                        if (!protectedEl.isConnected || protectedEl.disabled) return false;
                        var now = Date.now();
                        return (now - protectedAt) < 1000 || (now - lastResizeAt) < 700;
                    }

                    var origBlur = win.HTMLElement && win.HTMLElement.prototype.blur;
                    var origFocus = win.HTMLElement && win.HTMLElement.prototype.focus;

                    if (origBlur) {
                        win.HTMLElement.prototype.blur = function() {
                            if (this === protectedEl && isProtectedNow()) {
                                return;
                            }
                            return origBlur.apply(this, arguments);
                        };
                    }

                    if (origFocus) {
                        win.HTMLElement.prototype.focus = function() {
                            if (!isEditable(this) && protectedEl && doc.activeElement === protectedEl && isProtectedNow()) {
                                return;
                            }
                            return origFocus.apply(this, arguments);
                        };
                    }

                    function onUserTapOutside(e) {
                        if (!protectedEl) return;
                        var t = e.target;
                        if (t === protectedEl || (protectedEl.contains && protectedEl.contains(t))) return;
                        if (isEditable(t)) {
                            userDismissed = true;
                            return;
                        }
                        if ((Date.now() - protectedAt) > 250) {
                            userDismissed = true;
                        }
                    }

                    doc.addEventListener('pointerdown', onUserTapOutside, true);
                    doc.addEventListener('touchstart', onUserTapOutside, true);

                    doc.addEventListener('keydown', function(e) {
                        if (e.key === 'Escape' || e.key === 'Tab' ||
                            (e.key === 'Enter' && protectedEl && protectedEl.tagName &&
                             protectedEl.tagName.toLowerCase() === 'input')) {
                            userDismissed = true;
                        }
                    }, true);

                    doc.addEventListener('submit', function() {
                        userDismissed = true;
                    }, true);

                    doc.addEventListener('focusin', function(e) {
                        var t = e.target;
                        if (isEditable(t)) {
                            protectedEl = t;
                            protectedAt = Date.now();
                            userDismissed = false;
                            if (t.getRootNode && t.getRootNode() !== doc) {
                                injectStyle(t.getRootNode());
                            }
                        }
                    }, true);

                    win.addEventListener('resize', function() {
                        lastResizeAt = Date.now();
                    }, true);

                    doc.addEventListener('focusout', function(e) {
                        var target = e.target;
                        if (target !== protectedEl) return;
                        if (!isEditable(e.relatedTarget) && isProtectedNow()) {
                            win.requestAnimationFrame(function() {
                                if (target === protectedEl && isProtectedNow() && doc.activeElement !== target) {
                                    try {
                                        if (origFocus) origFocus.call(target, { preventScroll: false });
                                        else target.focus();
                                    } catch (_) {}
                                }
                            });
                        }
                    }, true);

                    var frames = doc.querySelectorAll('iframe');
                    for (var i = 0; i < frames.length; i++) {
                        try { patchWindow(frames[i].contentWindow); } catch (_) {}
                    }

                    if (win.MutationObserver) {
                        var mo = new win.MutationObserver(function(mutations) {
                            for (var m = 0; m < mutations.length; m++) {
                                var nodes = mutations[m].addedNodes;
                                for (var n = 0; n < nodes.length; n++) {
                                    var node = nodes[n];
                                    if (node && node.tagName && node.tagName.toLowerCase() === 'iframe') {
                                        node.addEventListener('load', function() {
                                            try { patchWindow(this.contentWindow); } catch (_) {}
                                        });
                                        try { patchWindow(node.contentWindow); } catch (_) {}
                                    }
                                }
                            }
                        });
                        if (doc.documentElement) {
                            mo.observe(doc.documentElement, { childList: true, subtree: true });
                        }
                    }
                } catch (_) {}
            }
            patchWindow(window);
        })();
    """.trimIndent()
    webView.evaluateJavascript(script, null)
}

/**
 * Pans the WebView vertically via translationY when the soft keyboard covers the focused HTML
 * input, avoiding WebView height resizing during typing on the main game canvas.
 */
fun panWebViewAboveKeyboard(
    webView: WebView,
    coveredBottomPx: Int,
    marginPx: Float,
) {
    if (coveredBottomPx <= 0 || webView.height <= 0) {
        webView.translationY = 0f
        return
    }
    val js = """
        (function() {
            function findActive(doc) {
                var el = doc && doc.activeElement;
                while (el) {
                    if (el.shadowRoot && el.shadowRoot.activeElement) {
                        el = el.shadowRoot.activeElement;
                        continue;
                    }
                    if (el.tagName && el.tagName.toLowerCase() === 'iframe') {
                        try {
                            if (el.contentDocument && el.contentDocument.activeElement) {
                                var inner = el.contentDocument.activeElement;
                                var ir = inner.getBoundingClientRect();
                                var fr = el.getBoundingClientRect();
                                var itag = (inner.tagName || '').toLowerCase();
                                if (itag === 'input' || itag === 'textarea' || inner.isContentEditable) {
                                    return JSON.stringify({ bottom: fr.top + ir.bottom, vh: window.innerHeight });
                                }
                            }
                        } catch (_) {}
                    }
                    break;
                }
                if (!el || el === doc.body || el === doc.documentElement) return '';
                var tag = (el.tagName || '').toLowerCase();
                var editable = tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable;
                if (!editable) return '';
                var r = el.getBoundingClientRect();
                return JSON.stringify({ bottom: r.bottom, vh: window.innerHeight });
            }
            return findActive(document);
        })();
    """.trimIndent()
    webView.evaluateJavascript(js) { rawResult ->
        val cleaned = rawResult?.trim('"')?.replace("\\\"", "\"")?.takeIf {
            it.isNotEmpty() && it != "null"
        } ?: run {
            webView.translationY = 0f
            return@evaluateJavascript
        }
        try {
            val json = JSONObject(cleaned)
            val bottomCss = json.optDouble("bottom", -1.0)
            val vhCss = json.optDouble("vh", 0.0)
            if (bottomCss <= 0.0 || vhCss <= 0.0) {
                webView.translationY = 0f
                return@evaluateJavascript
            }
            val inputBottomPx = ((bottomCss / vhCss) * webView.height).toFloat()
            val visibleBottomPx = (webView.height - coveredBottomPx - marginPx).coerceAtLeast(0f)
            if (inputBottomPx > visibleBottomPx) {
                val shift = (inputBottomPx - visibleBottomPx).coerceAtMost(coveredBottomPx.toFloat())
                webView.translationY = -shift
            } else {
                webView.translationY = 0f
            }
        } catch (_: Exception) {
            webView.translationY = 0f
        }
    }
}

private fun handleUrlLoading(context: Context, uri: Uri?): Boolean {
    if (uri == null) return false
    val scheme = uri.scheme?.lowercase().orEmpty()
    // Keep all web navigation and inline schemes (about:blank for OAuth popups, data, blob) in-app
    if (scheme == "http" || scheme == "https" || scheme == "about" ||
        scheme == "data" || scheme == "blob" || scheme == "javascript"
    ) {
        return false
    }
    if (scheme == "tel" || scheme == "mailto") {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Ignore when no handler app exists
        }
        return true
    }
    return true
}

fun configureSimulatorWebView(
    webView: WebView,
    onProgressChanged: (Int) -> Unit,
    onTitleChanged: (String?) -> Unit,
    onPageStarted: (String?, Boolean) -> Unit,
    onPageFinished: (String?, Boolean) -> Unit,
    onNavigationStateChanged: (Boolean) -> Unit,
    onPageError: (Int, String, Boolean) -> Unit,
    onShowJsDialog: (JsDialogState) -> Unit,
    onFileChooserRequest: (ValueCallback<Array<Uri>>, Array<String>, Boolean) -> Boolean,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustomView: () -> Unit,
    onCreateChildWebView: (Message) -> Unit,
    onRenderProcessGoneCallback: () -> Unit,
) {
    applyCommonWebViewSettings(webView = webView, supportMultipleWindows = true)

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            return handleUrlLoading(webView.context, request?.url)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onPageStarted(url, view?.canGoBack() == true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            CookieManager.getInstance().flush()
            injectWebInputCompatibilityFix(view)
            onPageFinished(url, view?.canGoBack() == true)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            injectWebInputCompatibilityFix(view)
            onNavigationStateChanged(view?.canGoBack() == true)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                val code = error?.errorCode ?: ERROR_UNKNOWN
                val desc = error?.description?.toString().orEmpty()
                val offline = !isNetworkAvailable(webView.context) ||
                    code == ERROR_HOST_LOOKUP ||
                    code == ERROR_CONNECT ||
                    code == ERROR_TIMEOUT
                onPageError(code, desc, offline)
            }
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            onRenderProcessGoneCallback()
            return true
        }
    }

    webView.setWebChromeClient(object : WebChromeClient() {
        override fun onRequestFocus(view: WebView?) {
            super.onRequestFocus(view)
            view?.requestFocusFromTouch()
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (newProgress >= 75) {
                injectWebInputCompatibilityFix(view)
            }
            onProgressChanged(newProgress)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            onTitleChanged(title)
        }

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            if (result == null) return false
            onShowJsDialog(JsDialogState.Alert(message.orEmpty(), result))
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            if (result == null) return false
            onShowJsDialog(JsDialogState.Confirm(message.orEmpty(), result))
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?,
        ): Boolean {
            if (result == null) return false
            onShowJsDialog(
                JsDialogState.Prompt(
                    message = message.orEmpty(),
                    defaultValue = defaultValue.orEmpty(),
                    result = result,
                ),
            )
            return true
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            if (filePathCallback == null) return false
            val acceptTypes = fileChooserParams?.acceptTypes
                ?.filter { it.isNotBlank() }
                ?.toTypedArray()
                ?: arrayOf("*/*")
            val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            return onFileChooserRequest(filePathCallback, acceptTypes, allowMultiple)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view != null && callback != null) {
                onShowCustomView(view, callback)
            }
        }

        override fun onHideCustomView() {
            onHideCustomView()
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            if (resultMsg == null) return false
            onCreateChildWebView(resultMsg)
            return true
        }
    })
}

fun createPopupWebView(
    context: Context,
    onTitleChanged: (String?) -> Unit,
    onShowJsDialog: (JsDialogState) -> Unit,
    onCloseRequested: () -> Unit,
): WebView {
    return WebView(context).apply {
        applyCommonWebViewSettings(webView = this, supportMultipleWindows = false)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                return handleUrlLoading(context, request?.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                injectWebInputCompatibilityFix(view)
            }
        }

        setWebChromeClient(object : WebChromeClient() {
            override fun onRequestFocus(view: WebView?) {
                super.onRequestFocus(view)
                view?.requestFocusFromTouch()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                onTitleChanged(title)
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (result == null) return false
                onShowJsDialog(JsDialogState.Alert(message.orEmpty(), result))
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (result == null) return false
                onShowJsDialog(JsDialogState.Confirm(message.orEmpty(), result))
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                if (result == null) return false
                onShowJsDialog(
                    JsDialogState.Prompt(
                        message = message.orEmpty(),
                        defaultValue = defaultValue.orEmpty(),
                        result = result,
                    ),
                )
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                CookieManager.getInstance().flush()
                onCloseRequested()
            }
        })
    }
}

fun destroyWebViewSafely(webView: WebView?) {
    if (webView == null) return
    (webView.parent as? ViewGroup)?.removeView(webView)
    webView.stopLoading()
    webView.destroy()
}

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val activeNetwork = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

package com.example.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
        .replace("; wv", "")
        .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

fun sanitizedChromeUserAgent(context: Context): String {
    return stripWebViewMarkersFromUserAgent(WebSettings.getDefaultUserAgent(context))
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun applyCommonWebViewSettings(webView: WebView, supportMultipleWindows: Boolean) {
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    webView.setBackgroundColor("#081421".toColorInt())
    webView.overScrollMode = View.OVER_SCROLL_NEVER
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false

    // Ensure WebView reliably takes and keeps Android View focus inside Compose AndroidView
    // so tapping an HTML input does not immediately lose focus and dismiss the soft keyboard.
    webView.isFocusable = true
    webView.isFocusableInTouchMode = true
    webView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    webView.setOnTouchListener { view, event ->
        if ((event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP) && !view.hasFocus()
        ) {
            view.requestFocus()
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
            onPageFinished(url, view?.canGoBack() == true)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
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

    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
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
    }
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
            }
        }

        webChromeClient = object : WebChromeClient() {
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
        }
    }
}

/**
 * Pans the WebView vertically via translationY when the soft keyboard covers the currently
 * focused HTML input, without resizing the WebView (which would trigger window.onresize in the
 * web game and blur/reset the focused input).
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
            var el = document.activeElement;
            if (!el || el === document.body || el === document.documentElement) return '';
            var tag = (el.tagName || '').toLowerCase();
            var editable = tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable;
            if (!editable) return '';
            var r = el.getBoundingClientRect();
            return JSON.stringify({ bottom: r.bottom, vh: window.innerHeight });
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

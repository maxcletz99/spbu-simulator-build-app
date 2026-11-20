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

@SuppressLint("SetJavaScriptEnabled")
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
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    webView.setBackgroundColor("#081421".toColorInt())
    webView.overScrollMode = View.OVER_SCROLL_NEVER
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false

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
        setSupportMultipleWindows(true)
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val uri = request?.url ?: return false
            val scheme = uri.scheme?.lowercase().orEmpty()
            // Keep all http/https navigation inside the in-app WebView, never external browser
            if (scheme == "http" || scheme == "https") {
                return false
            }
            if (scheme == "tel" || scheme == "mailto") {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    webView.context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    // Ignore when no handler app exists
                }
                return true
            }
            return true
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

@SuppressLint("SetJavaScriptEnabled")
fun createPopupWebView(
    context: Context,
    onCloseRequested: () -> Unit,
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor("#081421".toColorInt())
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val scheme = request?.url?.scheme?.lowercase().orEmpty()
                return !(scheme == "http" || scheme == "https")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                onCloseRequested()
            }
        }
    }
}

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val activeNetwork = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

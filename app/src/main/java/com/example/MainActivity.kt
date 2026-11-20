package com.example

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ui.GameControlsOverlay
import com.example.ui.OrientationMode
import com.example.ui.PopupHeaderBar
import com.example.ui.SimulatorLoadState
import com.example.ui.SimulatorStatusOverlay
import com.example.ui.SimulatorViewModel
import com.example.ui.configureSimulatorWebView
import com.example.ui.createPopupWebView
import com.example.ui.destroyWebViewSafely
import com.example.ui.panWebViewAboveKeyboard
import com.example.ui.theme.SpbuSimulatorTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val simulatorViewModel: SimulatorViewModel by viewModels()

    private lateinit var rootContainer: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var mainWebView: WebView
    private lateinit var popupContainer: LinearLayout
    private lateinit var popupWebViewSlot: FrameLayout
    private lateinit var customViewContainer: FrameLayout
    private lateinit var statusComposeView: ComposeView
    private lateinit var controlsComposeView: ComposeView

    private var popupWebView: WebView? = null
    private var popupTitle by mutableStateOf("")
    private var customFullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPressMs: Long = 0L

    private var lastAppliedFullscreen: Boolean? = null
    private var lastAppliedOrientation: OrientationMode? = null

    private val singleFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        pendingFileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        pendingFileCallback = null
    }

    private val multiFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        pendingFileCallback?.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
        pendingFileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setupNativeViewHierarchy()
        setupInsetsListener()
        setupBackNavigation()
        observeUiState()

        mainWebView.requestFocus()
        mainWebView.loadUrl(simulatorViewModel.uiState.value.targetUrl)
    }

    private fun setupNativeViewHierarchy() {
        val bgColor = "#081421".toColorInt()

        rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(bgColor)
        }

        // 1. Direct native container for the primary WebView (never wrapped in Compose AndroidView,
        // avoiding Compose FocusInterop focus-stealing that closes the soft keyboard).
        webContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        mainWebView = WebView(this).also { webView ->
            configureSimulatorWebView(
                webView = webView,
                onProgressChanged = simulatorViewModel::onProgressChanged,
                onTitleChanged = simulatorViewModel::onPageTitleChanged,
                onPageStarted = simulatorViewModel::onPageStarted,
                onPageFinished = simulatorViewModel::onPageFinished,
                onNavigationStateChanged = simulatorViewModel::onNavigationStateChanged,
                onPageError = simulatorViewModel::onPageError,
                onShowJsDialog = simulatorViewModel::showJsDialog,
                onFileChooserRequest = { callback, acceptTypes, allowMultiple ->
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = callback
                    val mimeType = acceptTypes.firstOrNull()?.takeIf { it.contains("/") } ?: "*/*"
                    if (allowMultiple) {
                        multiFileLauncher.launch(mimeType)
                    } else {
                        singleFileLauncher.launch(mimeType)
                    }
                    true
                },
                onShowCustomView = { view, callback ->
                    showCustomFullscreenView(view, callback)
                },
                onHideCustomView = {
                    hideCustomFullscreenView()
                },
                onCreateChildWebView = { resultMsg ->
                    openPopupWebView(resultMsg)
                },
                onRenderProcessGoneCallback = {
                    simulatorViewModel.prepareRetry()
                    webView.reload()
                },
            )
        }
        webContainer.addView(mainWebView)
        rootContainer.addView(webContainer)

        // 2. Native container for in-app OAuth / Google Sign-In popup WebViews
        val popupHeaderComposeView = ComposeView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setContent {
                SpbuSimulatorTheme(darkTheme = true) {
                    PopupHeaderBar(
                        title = popupTitle,
                        onBack = {
                            val popup = popupWebView
                            if (popup?.canGoBack() == true) {
                                popup.goBack()
                            } else {
                                closePopupWebView()
                            }
                        },
                        onClose = { closePopupWebView() },
                    )
                }
            }
        }

        popupWebViewSlot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        popupContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(popupHeaderComposeView)
            addView(popupWebViewSlot)
        }
        rootContainer.addView(popupContainer)

        // 3. Container for HTML5 fullscreen custom views
        customViewContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        rootContainer.addView(customViewContainer)

        // 4. Compose overlay for loading/error screens, top progress bar, and JS dialogs.
        // Kept non-focusable so it never steals View focus or dismisses the WebView keyboard.
        statusComposeView = ComposeView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setContent {
                val uiState by simulatorViewModel.uiState.collectAsStateWithLifecycle()
                SpbuSimulatorTheme(darkTheme = true) {
                    SimulatorStatusOverlay(
                        uiState = uiState,
                        onRetry = {
                            simulatorViewModel.prepareRetry()
                            if (mainWebView.url.isNullOrBlank()) {
                                mainWebView.loadUrl(uiState.targetUrl)
                            } else {
                                mainWebView.reload()
                            }
                        },
                        onDismissJsDialog = simulatorViewModel::clearJsDialog,
                    )
                }
            }
        }
        rootContainer.addView(statusComposeView)

        // 5. Compact top-right collapsible game controls pill (WRAP_CONTENT so touches elsewhere
        // go directly to WebView without passing through ComposeView).
        controlsComposeView = ComposeView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            )
            setContent {
                val uiState by simulatorViewModel.uiState.collectAsStateWithLifecycle()
                SpbuSimulatorTheme(darkTheme = true) {
                    GameControlsOverlay(
                        uiState = uiState,
                        onToggleExpanded = {
                            simulatorViewModel.setControlsExpanded(!uiState.controlsExpanded)
                        },
                        onBack = {
                            if (mainWebView.canGoBack()) {
                                mainWebView.goBack()
                            }
                        },
                        onHome = {
                            simulatorViewModel.prepareRetry()
                            mainWebView.loadUrl(uiState.targetUrl)
                            simulatorViewModel.setControlsExpanded(false)
                        },
                        onReload = {
                            simulatorViewModel.prepareRetry()
                            mainWebView.reload()
                            simulatorViewModel.setControlsExpanded(false)
                        },
                        onCycleOrientation = simulatorViewModel::cycleOrientation,
                        onToggleKeepAwake = simulatorViewModel::toggleKeepScreenOn,
                        onToggleFullscreen = simulatorViewModel::toggleFullscreen,
                    )
                }
            }
        }
        rootContainer.addView(controlsComposeView)

        setContentView(rootContainer)
    }

    private fun setupInsetsListener() {
        val marginPx = 20f * resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, windowInsets ->
            val barInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val isFullscreen = simulatorViewModel.uiState.value.isFullscreen || customFullscreenView != null

            // Keep webContainer size steady (without shrinking by imeInsets.bottom on every frame)
            // so the web game/iframes never fire window.resize and blur the focused input.
            // Instead, pan mainWebView upward via translationY if the focused input would be covered.
            val targetBottom = if (isFullscreen) 0 else barInsets.bottom
            val targetLeft = if (isFullscreen) 0 else barInsets.left
            val targetTop = if (isFullscreen) 0 else barInsets.top
            val targetRight = if (isFullscreen) 0 else barInsets.right
            if (webContainer.paddingLeft != targetLeft ||
                webContainer.paddingTop != targetTop ||
                webContainer.paddingRight != targetRight ||
                webContainer.paddingBottom != targetBottom
            ) {
                webContainer.setPadding(targetLeft, targetTop, targetRight, targetBottom)
            }

            val coveredBottomPx = (imeInsets.bottom - targetBottom).coerceAtLeast(0)
            panWebViewAboveKeyboard(
                webView = mainWebView,
                coveredBottomPx = coveredBottomPx,
                marginPx = marginPx,
            )

            val popupBottom = maxOf(barInsets.bottom, imeInsets.bottom)
            if (popupContainer.paddingLeft != barInsets.left ||
                popupContainer.paddingTop != barInsets.top ||
                popupContainer.paddingRight != barInsets.right ||
                popupContainer.paddingBottom != popupBottom
            ) {
                popupContainer.setPadding(barInsets.left, barInsets.top, barInsets.right, popupBottom)
            }

            windowInsets
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                simulatorViewModel.uiState.collect { uiState ->
                    if (mainWebView.keepScreenOn != uiState.keepScreenOn) {
                        mainWebView.keepScreenOn = uiState.keepScreenOn
                    }

                    if (lastAppliedOrientation != uiState.orientationMode) {
                        lastAppliedOrientation = uiState.orientationMode
                        requestedOrientation = uiState.orientationMode.requestedOrientation
                    }

                    val shouldHideBars = uiState.isFullscreen || customFullscreenView != null
                    if (lastAppliedFullscreen != shouldHideBars) {
                        lastAppliedFullscreen = shouldHideBars
                        val controller = WindowCompat.getInsetsController(window, rootContainer)
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        if (shouldHideBars) {
                            controller.hide(WindowInsetsCompat.Type.systemBars())
                        } else {
                            controller.show(WindowInsetsCompat.Type.systemBars())
                        }
                        ViewCompat.requestApplyInsets(rootContainer)
                    }

                    val showingBlockingOverlay =
                        (!uiState.hasCompletedInitialLoad && uiState.loadState is SimulatorLoadState.Loading) ||
                            uiState.loadState is SimulatorLoadState.Error

                    val targetStatusHeight = if (showingBlockingOverlay) {
                        FrameLayout.LayoutParams.MATCH_PARENT
                    } else {
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    }
                    val statusParams = statusComposeView.layoutParams
                    if (statusParams.height != targetStatusHeight) {
                        statusParams.height = targetStatusHeight
                        statusComposeView.layoutParams = statusParams
                    }

                    updateControlsVisibility()
                }
            }
        }
    }

    private fun updateControlsVisibility() {
        val hideControls = customFullscreenView != null || popupWebView != null
        controlsComposeView.visibility = if (hideControls) View.GONE else View.VISIBLE
    }

    private fun openPopupWebView(resultMsg: android.os.Message) {
        closePopupWebView()
        popupTitle = ""

        val child = createPopupWebView(
            context = this,
            onTitleChanged = { title ->
                if (!title.isNullOrBlank()) {
                    popupTitle = title
                }
            },
            onShowJsDialog = simulatorViewModel::showJsDialog,
            onCloseRequested = { closePopupWebView() },
        )

        popupWebView = child
        popupWebViewSlot.removeAllViews()
        popupWebViewSlot.addView(child)
        popupContainer.visibility = View.VISIBLE
        updateControlsVisibility()
        child.requestFocusFromTouch()

        val transport = resultMsg.obj as? WebView.WebViewTransport
        transport?.webView = child
        resultMsg.sendToTarget()
    }

    private fun closePopupWebView() {
        val existing = popupWebView
        popupWebView = null
        popupTitle = ""
        popupWebViewSlot.removeAllViews()
        popupContainer.visibility = View.GONE
        destroyWebViewSafely(existing)
        updateControlsVisibility()
        mainWebView.requestFocus()
    }

    private fun showCustomFullscreenView(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
    ) {
        customViewCallback?.onCustomViewHidden()
        customFullscreenView = view
        customViewCallback = callback
        customViewContainer.removeAllViews()
        customViewContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        customViewContainer.visibility = View.VISIBLE
        updateControlsVisibility()
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun hideCustomFullscreenView() {
        customFullscreenView = null
        customViewCallback = null
        customViewContainer.removeAllViews()
        customViewContainer.visibility = View.GONE
        updateControlsVisibility()
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        customFullscreenView != null -> {
                            customViewCallback?.onCustomViewHidden()
                            hideCustomFullscreenView()
                        }
                        popupWebView != null -> {
                            val popup = popupWebView
                            if (popup?.canGoBack() == true) {
                                popup.goBack()
                            } else {
                                closePopupWebView()
                            }
                        }
                        simulatorViewModel.uiState.value.controlsExpanded -> {
                            simulatorViewModel.setControlsExpanded(false)
                        }
                        mainWebView.canGoBack() -> {
                            mainWebView.goBack()
                        }
                        else -> {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastBackPressMs < 2000L) {
                                finish()
                            } else {
                                lastBackPressMs = now
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.press_back_again_to_exit),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        mainWebView.onResume()
        popupWebView?.onResume()
    }

    override fun onPause() {
        mainWebView.onPause()
        popupWebView?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        destroyWebViewSafely(popupWebView)
        popupWebView = null
        destroyWebViewSafely(mainWebView)
        super.onDestroy()
    }
}
